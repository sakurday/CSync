use crate::i18n::{msg, tr};
use crate::storage::{Config, Identity, Store};
use anyhow::{Context, Result, bail, ensure};
use csync_core::{
    protocol::*,
    security::{DeviceVerifier, Trust, fingerprint},
    sync::Coordinator,
};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use std::{
    collections::{HashMap, VecDeque},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicIsize, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};
use tokio::{
    io::AsyncWrite,
    net::{TcpListener, TcpStream},
    sync::{Semaphore, broadcast, oneshot},
    time::timeout,
};
use tokio_rustls::TlsAcceptor;

pub const WAKE: u32 = 0x8000 + 7;

pub enum UiEvent {
    Refresh,
    Paired,
    Pair {
        pin: String,
        name: String,
        reply: oneshot::Sender<bool>,
    },
    Remote {
        clip: Clip,
        reply: oneshot::Sender<bool>,
    },
}

pub struct UiSink {
    tx: mpsc::Sender<UiEvent>,
    pub hwnd: AtomicIsize,
}
impl UiSink {
    pub fn new(tx: mpsc::Sender<UiEvent>) -> Self {
        Self {
            tx,
            hwnd: AtomicIsize::new(0),
        }
    }
    pub fn send(&self, event: UiEvent) {
        if self.tx.send(event).is_ok() {
            let hwnd = self.hwnd.load(Ordering::Relaxed);
            if hwnd != 0 {
                unsafe {
                    windows_sys::Win32::UI::WindowsAndMessaging::PostMessageW(
                        hwnd as _, WAKE, 0, 0,
                    );
                }
            }
        }
    }
}

#[derive(Clone)]
pub enum Bus {
    Commit(u64, Clip),
    Changed,
}

pub struct Host {
    pub config: Mutex<Config>,
    pub store: Store,
    pub trust: Arc<Mutex<Trust>>,
    pub id: String,
    pub ui: UiSink,
    pub paused: AtomicBool,
    pub bus: broadcast::Sender<Bus>,
    coordinator: Mutex<Coordinator>,
    pub active: Mutex<HashMap<String, String>>,
    pub status: Mutex<String>,
    pub acceptor: TlsAcceptor,
    pub slots: Arc<Semaphore>,
}

impl Host {
    pub fn new(store: Store, config: Config, identity: Identity, ui: UiSink) -> Result<Arc<Self>> {
        let trust = Arc::new(Mutex::new(Trust::new(config.peers.keys().cloned())));
        let tls = rustls::ServerConfig::builder_with_protocol_versions(&[&rustls::version::TLS13])
            .with_client_cert_verifier(Arc::new(DeviceVerifier {
                trust: trust.clone(),
            }))
            .with_single_cert(
                vec![CertificateDer::from(identity.cert)],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(identity.key)),
            )?;
        let (bus, _) = broadcast::channel(64);
        Ok(Arc::new(Self {
            config: Mutex::new(config),
            store,
            trust,
            id: identity.id,
            ui,
            paused: AtomicBool::new(false),
            bus,
            coordinator: Mutex::new(Coordinator::default()),
            active: Mutex::new(HashMap::new()),
            status: Mutex::new(msg("sync.starting", &[])),
            acceptor: TlsAcceptor::from(Arc::new(tls)),
            slots: Arc::new(Semaphore::new(8)),
        }))
    }
    pub fn status(&self, value: impl Into<String>) {
        *self.status.lock().unwrap() = value.into();
        self.ui.send(UiEvent::Refresh);
    }
    /// Called only on the clipboard message thread, after the OS write succeeds.
    pub fn publish(&self, clip: Clip) {
        if let Some(revision) = self.coordinator.lock().unwrap().commit(&clip) {
            let _ = self.bus.send(Bus::Commit(revision, clip));
        }
    }
    pub fn revoke_all(&self) -> Result<()> {
        let mut config = self.config.lock().unwrap();
        let mut next = config.clone();
        next.peers.clear();
        self.store.save(&next)?;
        *config = next;
        self.trust.lock().unwrap().peers.clear();
        self.trust.lock().unwrap().close_pairing();
        let _ = self.bus.send(Bus::Changed);
        Ok(())
    }
    pub fn revoke_peer(&self, pin: &str) -> Result<()> {
        let mut config = self.config.lock().unwrap();
        ensure!(config.peers.contains_key(pin), "unknown device");
        let mut next = config.clone();
        next.peers.remove(pin);
        // Persist first: a failed save must leave the current trust and sessions intact.
        self.store.save(&next)?;
        *config = next;
        self.trust.lock().unwrap().peers.remove(pin);
        let _ = self.bus.send(Bus::Changed);
        Ok(())
    }
}

pub async fn serve(host: Arc<Host>) -> Result<()> {
    let port = host.config.lock().unwrap().port;
    let v4 = TcpListener::bind((std::net::Ipv4Addr::UNSPECIFIED, port)).await?;
    let v6 = (|| -> Result<TcpListener> {
        let socket = socket2::Socket::new(
            socket2::Domain::IPV6,
            socket2::Type::STREAM,
            Some(socket2::Protocol::TCP),
        )?;
        socket.set_only_v6(true)?;
        socket.set_nonblocking(true)?;
        socket.bind(&std::net::SocketAddr::from(([0u16; 8], port)).into())?;
        socket.listen(16)?;
        Ok(TcpListener::from_std(socket.into())?)
    })()
    .ok();
    host.status(msg("windows.tcp_ready", &[("port", format!("{port}"))]));
    let mut attempts: HashMap<std::net::IpAddr, VecDeque<Instant>> = HashMap::new();
    loop {
        let result = tokio::select! {
            result = v4.accept() => result,
            result = async { match &v6 { Some(v6) => v6.accept().await, None => std::future::pending().await } } => result,
        };
        let (socket, address) = result?;
        if !host.config.lock().unwrap().public_access && !local_ip(address.ip()) {
            continue;
        }
        // Bound rate-limit state and reject before expensive TLS handshakes.
        attempts.retain(|_, q| {
            q.back()
                .is_some_and(|t| t.elapsed() < Duration::from_secs(60))
        });
        if attempts.len() >= 256 && !attempts.contains_key(&address.ip()) {
            continue;
        }
        let queue = attempts.entry(address.ip()).or_default();
        while queue
            .front()
            .is_some_and(|t| t.elapsed() >= Duration::from_secs(60))
        {
            queue.pop_front();
        }
        if queue.len() >= 12 {
            continue;
        }
        queue.push_back(Instant::now());
        let Ok(permit) = host.slots.clone().try_acquire_owned() else {
            continue;
        };
        let _ = socket.set_nodelay(true);
        let host = host.clone();
        tokio::spawn(async move {
            let _permit = permit;
            if let Err(error) = connection(host.clone(), socket).await {
                host.status(msg(
                    "windows.connection_incomplete",
                    &[("error", format!("{error:#}"))],
                ));
            }
        });
    }
}

fn local_ip(ip: std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V4(ip) => ip.is_loopback() || ip.is_private() || ip.is_link_local(),
        std::net::IpAddr::V6(ip) => {
            ip.is_loopback()
                || (ip.segments()[0] & 0xfe00 == 0xfc00)
                || (ip.segments()[0] & 0xffc0 == 0xfe80)
                || ip.to_ipv4_mapped().is_some_and(|v4| local_ip(v4.into()))
        }
    }
}

async fn send<W: AsyncWrite + Unpin>(writer: &mut W, msg: &Message) -> Result<()> {
    timeout(Duration::from_secs(10), write_message(writer, msg)).await??;
    Ok(())
}

pub async fn connection(host: Arc<Host>, stream: TcpStream) -> Result<()> {
    let mut tls = timeout(Duration::from_secs(10), host.acceptor.accept(stream)).await??;
    let pin = fingerprint(
        tls.get_ref()
            .1
            .peer_certificates()
            .and_then(|c| c.first())
            .ok_or_else(|| anyhow::anyhow!("no identity"))?
            .as_ref(),
    );
    let first = timeout(Duration::from_secs(10), read_message(&mut tls)).await??;
    if let Message::Pair {
        version,
        token,
        name,
    } = first
    {
        ensure!(version == VERSION, "unsupported pairing request");
        ensure!(valid_name(&name), "invalid device name");
        let accepted = {
            let mut trust = host.trust.lock().unwrap();
            if token.is_empty() {
                trust.consume_ip_pairing()
            } else {
                trust.consume_pairing(&token)
            }
        };
        ensure!(accepted, "pairing expired or invalid");
        host.ui.send(UiEvent::Refresh);
        let (tx, rx) = oneshot::channel();
        host.ui.send(UiEvent::Pair {
            pin: pin.clone(),
            name: name.clone(),
            reply: tx,
        });
        let approved = timeout(Duration::from_secs(60), rx)
            .await
            .unwrap_or(Ok(false))
            .unwrap_or(false);
        if !approved {
            send(
                &mut tls,
                &Message::Error {
                    code: "pairing_rejected".into(),
                },
            )
            .await?;
            return Ok(());
        }
        send(&mut tls, &Message::PairChallenge).await?;
        ensure!(
            matches!(
                timeout(Duration::from_secs(60), read_message(&mut tls)).await??,
                Message::PairConfirm
            ),
            "phone did not confirm pairing"
        );
        let saved: Result<()> = (|| {
            let mut config = host.config.lock().unwrap();
            ensure!(config.peers.len() < 16, "too many devices");
            let mut next = config.clone();
            next.peers.insert(pin.clone(), name);
            host.store
                .save(&next)
                .context(tr("windows.pair_save_failed", &[]))?;
            *config = next;
            let mut trust = host.trust.lock().unwrap();
            trust.peers.insert(pin.clone());
            trust.close_pairing();
            Ok(())
        })();
        if let Err(error) = saved {
            let _ = send(
                &mut tls,
                &Message::Error {
                    code: "pairing_storage_failed".into(),
                },
            )
            .await;
            return Err(error);
        }
        let name = host.config.lock().unwrap().name.clone();
        send(
            &mut tls,
            &Message::Paired {
                version: VERSION,
                server: host.id.clone(),
                name,
            },
        )
        .await?;
        host.ui.send(UiEvent::Paired);
        return Ok(());
    }
    ensure!(
        host.trust.lock().unwrap().peers.contains(&pin),
        "unauthorized device"
    );
    match first {
        Message::Hello { version, name } if version == VERSION && valid_name(&name) => {}
        _ => bail!("expected hello"),
    }
    let session = uuid::Uuid::new_v4().to_string();
    let mut events = host.bus.subscribe();
    host.active
        .lock()
        .unwrap()
        .insert(pin.clone(), session.clone());
    let _ = host.bus.send(Bus::Changed); // A newly connected transport supersedes the previous one.
    host.ui.send(UiEvent::Refresh);
    let guard = ActiveGuard {
        host: host.clone(),
        pin: pin.clone(),
        session: session.clone(),
    };
    send(
        &mut tls,
        &Message::Ready {
            version: VERSION,
            server: host.id.clone(),
            session: session.clone(),
        },
    )
    .await?;
    let (mut reader, mut writer) = tokio::io::split(tls);
    let (tx, mut rx) = tokio::sync::mpsc::channel(8);
    let task = tokio::spawn(async move {
        loop {
            let result = timeout(Duration::from_secs(110), read_message(&mut reader)).await;
            let Ok(Ok(message)) = result else {
                break;
            };
            if tx.send(message).await.is_err() {
                break;
            }
        }
    });
    let mut heartbeat = tokio::time::interval(Duration::from_secs(40));
    heartbeat.tick().await;
    let mut last_sequence = 0;
    let mut rate = VecDeque::<Instant>::new();
    let result: Result<()> = async {
        loop {
            tokio::select! {
                incoming = rx.recv() => {
                    ensure!(host.trust.lock().unwrap().peers.contains(&pin), "revoked");
                    ensure!(host.active.lock().unwrap().get(&pin) == Some(&session), "superseded");
                    match incoming.ok_or_else(|| anyhow::anyhow!("connection closed"))? {
                        Message::Clip { clip } => {
                            clip.validate()?;
                            ensure!(clip.origin == pin && clip.sequence > last_sequence, "invalid origin or replay");
                            last_sequence = clip.sequence;
                            while rate.front().is_some_and(|t| t.elapsed() > Duration::from_secs(1)) { rate.pop_front(); }
                            ensure!(rate.len() < 32, "clipboard rate exceeded");
                            rate.push_back(Instant::now());
                            if host.paused.load(Ordering::Relaxed) {
                                send(&mut writer, &Message::Ack { id: clip.id, status: "paused".into() }).await?;
                                continue;
                            }
                            let id = clip.id.clone();
                            let (reply, response) = oneshot::channel();
                            host.ui.send(UiEvent::Remote { clip, reply });
                            if !timeout(Duration::from_secs(5), response).await.unwrap_or(Ok(false)).unwrap_or(false) {
                                send(&mut writer, &Message::Ack { id, status: "unavailable".into() }).await?;
                            }
                        }
                        Message::Ping { nonce } => send(&mut writer, &Message::Pong { nonce }).await?,
                        Message::Pong { .. } => {},
                        Message::Ack { status, .. } => {
                            if matches!(status.as_str(), "paused" | "shizuku_unavailable" | "clipboard_unavailable") {
                                host.status(msg("windows.remote_not_applied", &[("status", msg(&format!("status.{status}"), &[]))]));
                            }
                        },
                        _ => bail!("unexpected message"),
                    }
                }
                event = events.recv() => {
                    ensure!(host.trust.lock().unwrap().peers.contains(&pin), "revoked");
                    ensure!(host.active.lock().unwrap().get(&pin) == Some(&session), "superseded");
                    match event? {
                        Bus::Commit(revision, clip) if !host.paused.load(Ordering::Relaxed) => send(&mut writer, &Message::Commit { revision, clip }).await?,
                        _ => {},
                    }
                }
                _ = heartbeat.tick() => send(&mut writer, &Message::Ping { nonce: 1 }).await?,
            }
        }
    }.await;
    task.abort();
    drop(guard);
    result
}

fn valid_name(name: &str) -> bool {
    !name.is_empty() && name.chars().count() <= 64 && !name.chars().any(char::is_control)
}

struct ActiveGuard {
    host: Arc<Host>,
    pin: String,
    session: String,
}
impl Drop for ActiveGuard {
    fn drop(&mut self) {
        let mut active = self.host.active.lock().unwrap();
        if active.get(&self.pin) == Some(&self.session) {
            active.remove(&self.pin);
        }
        drop(active);
        self.host.ui.send(UiEvent::Refresh);
    }
}
