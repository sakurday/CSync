use crate::{
    server::{Host, UiEvent, UiSink, connection},
    storage::Store,
};
use csync_core::{protocol::*, security::fingerprint};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName};
use std::{
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
        mpsc,
    },
    time::Duration,
};
use tokio::{
    net::{TcpListener, TcpStream},
    time::timeout,
};
use tokio_rustls::{TlsConnector, client::TlsStream};

struct Client {
    id: String,
    connector: TlsConnector,
}
fn client(host: &Host) -> Client {
    let certificate = std::fs::read(host.store.directory.join("server-test.der")).unwrap();
    let generated = rcgen::generate_simple_self_signed(vec!["android-test".into()]).unwrap();
    let cert = generated.cert.der().to_vec();
    let id = fingerprint(&cert);
    let mut roots = rustls::RootCertStore::empty();
    roots.add(CertificateDer::from(certificate)).unwrap();
    let config = rustls::ClientConfig::builder_with_protocol_versions(&[&rustls::version::TLS13])
        .with_root_certificates(roots)
        .with_client_auth_cert(
            vec![CertificateDer::from(cert)],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(
                generated.signing_key.serialize_der(),
            )),
        )
        .unwrap();
    Client {
        id,
        connector: TlsConnector::from(Arc::new(config)),
    }
}
impl Client {
    async fn connect(&self, port: u16) -> anyhow::Result<TlsStream<TcpStream>> {
        Ok(self
            .connector
            .connect(
                ServerName::try_from("csync.local").unwrap(),
                TcpStream::connect((std::net::Ipv4Addr::LOCALHOST, port)).await?,
            )
            .await?)
    }
    async fn hello(&self, port: u16) -> anyhow::Result<TlsStream<TcpStream>> {
        let mut tls = self.connect(port).await?;
        write_message(
            &mut tls,
            &Message::Hello {
                version: VERSION,
                name: "Test phone".into(),
            },
        )
        .await?;
        anyhow::ensure!(matches!(
            read_message(&mut tls).await?,
            Message::Ready { .. }
        ));
        Ok(tls)
    }
}

fn host(directory: &str, port: u16) -> (Arc<Host>, mpsc::Receiver<UiEvent>) {
    let root = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .to_path_buf();
    let store = Store {
        directory: root.join(".build").join(directory),
    };
    std::fs::create_dir_all(&store.directory).unwrap();
    let identity = store.identity().unwrap();
    std::fs::write(store.directory.join("server-test.der"), &identity.cert).unwrap();
    let mut config = store.config().unwrap();
    config.port = if port == 0 { DEFAULT_PORT } else { port };
    store.save(&config).unwrap();
    let (tx, rx) = mpsc::channel();
    (
        Host::new(store, config, identity, UiSink::new(tx)).unwrap(),
        rx,
    )
}

async fn listener(host: Arc<Host>, port: u16) -> (u16, tokio::task::JoinHandle<()>) {
    let listener = TcpListener::bind((std::net::Ipv4Addr::UNSPECIFIED, port))
        .await
        .unwrap();
    let port = listener.local_addr().unwrap().port();
    (
        port,
        tokio::spawn(async move {
            while let Ok((socket, _)) = listener.accept().await {
                let host = host.clone();
                tokio::spawn(async move {
                    if let Err(error) = connection(host, socket).await {
                        eprintln!("Test connection rejected: {error}");
                    }
                });
            }
        }),
    )
}

fn mock_ui(host: &Arc<Host>, receiver: mpsc::Receiver<UiEvent>, mobile: Option<Arc<AtomicUsize>>) {
    let run = std::env::var("CSYNC_TEST_RUN").unwrap_or_default();
    let weak = Arc::downgrade(host);
    let runtime = tokio::runtime::Handle::current();
    std::thread::spawn(move || {
        while let Ok(event) = receiver.recv() {
            let Some(host) = weak.upgrade() else {
                break;
            };
            match event {
                UiEvent::Pair { reply, .. } => {
                    let _ = reply.send(true);
                }
                UiEvent::Remote { clip, reply } => {
                    if let Some(count) = &mobile {
                        assert!(
                            clip.text == format!("CSync Android→PC 后台验证 🙂 [{run}]"),
                            "unexpected clipboard forwarded; body omitted"
                        );
                        count.fetch_add(1, Ordering::SeqCst);
                        let next = host.clone();
                        let text = format!("CSync PC→Android 后台验证二 🙂 [{run}]");
                        runtime.spawn(async move {
                            tokio::time::sleep(Duration::from_secs(2)).await;
                            next.publish(Clip::new(&next.id, 2, text).unwrap());
                        });
                    }
                    host.publish(clip);
                    let _ = reply.send(true);
                }
                _ => {}
            }
        }
    });
}

#[tokio::test]
async fn tls_pairing_roundtrip_replay_origin_and_revocation() {
    let (host, ui) = host(&format!("security-test-{}", uuid::Uuid::new_v4()), 0);
    mock_ui(&host, ui, None);
    let (port, task) = listener(host.clone(), 0).await;
    let phone = client(&host);
    assert!(
        phone.hello(port).await.is_err(),
        "unpaired TLS identity must be rejected"
    );
    host.trust.lock().unwrap().open_pairing();
    let mut bad = phone.connect(port).await.unwrap();
    write_message(
        &mut bad,
        &Message::Pair {
            version: VERSION,
            token: "0".repeat(64),
            name: "Test".into(),
        },
    )
    .await
    .unwrap();
    assert!(read_message(&mut bad).await.is_err());
    assert!(!host.trust.lock().unwrap().peers.contains(&phone.id));
    let mut pair = phone.connect(port).await.unwrap();
    write_message(
        &mut pair,
        &Message::Pair {
            version: VERSION,
            token: String::new(),
            name: "Test".into(),
        },
    )
    .await
    .unwrap();
    assert!(matches!(
        read_message(&mut pair).await.unwrap(),
        Message::PairChallenge
    ));
    assert!(!host.store.config().unwrap().peers.contains_key(&phone.id));
    write_message(&mut pair, &Message::PairConfirm)
        .await
        .unwrap();
    assert!(matches!(
        read_message(&mut pair).await.unwrap(),
        Message::Paired { .. }
    ));
    assert!(host.store.config().unwrap().peers.contains_key(&phone.id));
    assert!(!host.trust.lock().unwrap().pairing_open());

    let mut tls = phone.hello(port).await.unwrap();
    let clip = Clip::new(&phone.id, 1, "你好\n🙂\"".into()).unwrap();
    write_message(&mut tls, &Message::Clip { clip: clip.clone() })
        .await
        .unwrap();
    match timeout(Duration::from_secs(3), read_message(&mut tls))
        .await
        .unwrap()
        .unwrap()
    {
        Message::Commit {
            revision,
            clip: result,
        } => {
            assert_eq!(revision, 1);
            assert_eq!(result, clip);
        }
        _ => panic!("expected commit"),
    }
    // A certificate cannot claim another device's clipboard origin.
    let spoofed = Clip::new(&host.id, 2, "spoof".into()).unwrap();
    write_message(&mut tls, &Message::Clip { clip: spoofed })
        .await
        .unwrap();
    assert!(
        timeout(Duration::from_secs(3), read_message(&mut tls))
            .await
            .unwrap()
            .is_err()
    );
    let mut tls = phone.hello(port).await.unwrap();
    host.revoke_all().unwrap();
    assert!(
        timeout(Duration::from_secs(3), read_message(&mut tls))
            .await
            .unwrap()
            .is_err()
    );
    assert!(phone.hello(port).await.is_err());
    task.abort();
}

#[tokio::test]
async fn qr_pairing_consumes_only_valid_token_and_rejects_replay() {
    let (host, ui) = host(&format!("qr-test-{}", uuid::Uuid::new_v4()), 0);
    mock_ui(&host, ui, None);
    let (port, task) = listener(host.clone(), 0).await;
    let phone = client(&host);
    let token = host.trust.lock().unwrap().open_pairing();
    for candidate in ["invalid".to_string(), token.clone()] {
        let mut link = phone.connect(port).await.unwrap();
        write_message(
            &mut link,
            &Message::Pair {
                version: VERSION,
                token: candidate.clone(),
                name: "QR phone".into(),
            },
        )
        .await
        .unwrap();
        if candidate != token {
            assert!(read_message(&mut link).await.is_err());
            assert!(host.trust.lock().unwrap().pairing_open());
            assert!(!host.store.config().unwrap().peers.contains_key(&phone.id));
            continue;
        }
        assert!(matches!(
            read_message(&mut link).await.unwrap(),
            Message::PairChallenge
        ));
        assert!(!host.trust.lock().unwrap().pairing_open());
        assert!(!host.store.config().unwrap().peers.contains_key(&phone.id));
        write_message(&mut link, &Message::PairConfirm)
            .await
            .unwrap();
        assert!(matches!(
            read_message(&mut link).await.unwrap(),
            Message::Paired { .. }
        ));
    }
    assert!(host.store.config().unwrap().peers.contains_key(&phone.id));
    let mut replay = phone.connect(port).await.unwrap();
    write_message(
        &mut replay,
        &Message::Pair {
            version: VERSION,
            token,
            name: "replay".into(),
        },
    )
    .await
    .unwrap();
    assert!(read_message(&mut replay).await.is_err());
    assert!(phone.hello(port).await.is_ok());
    task.abort();
}

#[tokio::test]
async fn deleting_one_device_preserves_other_sessions_and_saved_trust() {
    let (host, ui) = host(&format!("delete-peer-{}", uuid::Uuid::new_v4()), 0);
    mock_ui(&host, ui, None);
    let first = client(&host);
    let second = client(&host);
    {
        let mut config = host.config.lock().unwrap();
        for id in [&first.id, &second.id] {
            config.peers.insert(id.clone(), "Same name".into());
            host.trust.lock().unwrap().peers.insert(id.clone());
        }
        host.store.save(&config).unwrap();
    }
    let (port, task) = listener(host.clone(), 0).await;
    let mut removed = first.hello(port).await.unwrap();
    let mut retained = second.hello(port).await.unwrap();
    assert!(host.revoke_peer("not-a-device").is_err());
    host.revoke_peer(&first.id).unwrap();
    assert!(
        timeout(Duration::from_secs(3), read_message(&mut removed))
            .await
            .unwrap()
            .is_err()
    );
    assert!(first.hello(port).await.is_err());
    let persisted = host.store.config().unwrap();
    assert!(!persisted.peers.contains_key(&first.id));
    assert!(persisted.peers.contains_key(&second.id));
    assert!(host.trust.lock().unwrap().peers.contains(&second.id));
    let clip = Clip::new(&host.id, 1, "remaining session".into()).unwrap();
    host.publish(clip.clone());
    assert!(
        matches!(timeout(Duration::from_secs(3), read_message(&mut retained)).await.unwrap().unwrap(), Message::Commit { clip: received, .. } if received == clip)
    );
    // A failed disk save cannot revoke a still-authorized device in memory.
    let saved_path = host.store.directory.clone();
    let moved_path = saved_path.with_extension("save-failure");
    std::fs::rename(&saved_path, &moved_path).unwrap();
    let result = host.revoke_peer(&second.id);
    std::fs::rename(&moved_path, &saved_path).unwrap();
    assert!(result.is_err());
    assert!(host.config.lock().unwrap().peers.contains_key(&second.id));
    assert!(host.trust.lock().unwrap().peers.contains(&second.id));
    task.abort();
}

/// Invoked by scripts/test-device.ps1 after the APK and test APK are installed.
/// Only this test's isolated server auto-approves enrollment; the shipped app
/// always routes enrollment through its explicit native confirmation dialog.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "requires attached Android device and instrumentation"]
async fn android_device_server() {
    let port = 45877;
    let (host, ui) = host("device-server", port);
    let mobile = Arc::new(AtomicUsize::new(0));
    mock_ui(&host, ui, Some(mobile.clone()));
    host.trust.lock().unwrap().open_pairing();
    let (_, task) = listener(host.clone(), port).await;
    let ready = host.store.directory.join("ready");
    std::fs::write(&ready, "ready").unwrap();
    let active = host.clone();
    let run = std::env::var("CSYNC_TEST_RUN").unwrap_or_default();
    let publish = tokio::spawn(async move {
        for _ in 0..600 {
            if !active.active.lock().unwrap().is_empty() {
                tokio::time::sleep(Duration::from_secs(4)).await;
                active.publish(
                    Clip::new(
                        &active.id,
                        1,
                        format!("CSync PC→Android 前台验证一 🙂 [{run}]"),
                    )
                    .unwrap(),
                );
                return;
            }
            tokio::time::sleep(Duration::from_millis(250)).await;
        }
    });
    let done = host.store.directory.join("device-result.json");
    for _ in 0..600 {
        if done.exists() {
            let result: serde_json::Value =
                serde_json::from_slice(&std::fs::read(done).unwrap()).unwrap();
            assert_eq!(
                result["success"],
                true,
                "server delivery state: {}, mobile events: {}",
                host.status.lock().unwrap(),
                mobile.load(Ordering::SeqCst)
            );
            assert_eq!(
                mobile.load(Ordering::SeqCst),
                1,
                "remote writes must not echo back"
            );
            task.abort();
            publish.abort();
            return;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    task.abort();
    publish.abort();
    panic!("Android instrumentation did not complete in 5 minutes");
}
