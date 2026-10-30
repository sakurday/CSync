use rand::{RngCore, rngs::OsRng};
use rustls::{
    DigitallySignedStruct, DistinguishedName, Error, SignatureScheme,
    client::danger::HandshakeSignatureValid,
    pki_types::{CertificateDer, UnixTime},
    server::danger::{ClientCertVerified, ClientCertVerifier},
};
use sha2::{Digest, Sha256};
use std::{
    collections::HashSet,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use subtle::ConstantTimeEq;

pub fn fingerprint(der: &[u8]) -> String {
    hex::encode(Sha256::digest(der))
}

#[derive(Debug)]
pub struct Trust {
    pub peers: HashSet<String>,
    pairing: Option<(String, Instant)>,
}

impl Trust {
    pub fn new(peers: impl IntoIterator<Item = String>) -> Self {
        Self {
            peers: peers.into_iter().collect(),
            pairing: None,
        }
    }
    pub fn open_pairing(&mut self) -> String {
        let mut bytes = [0u8; 32];
        OsRng.fill_bytes(&mut bytes);
        let token = hex::encode(bytes);
        self.pairing = Some((token.clone(), Instant::now() + Duration::from_secs(120)));
        token
    }
    pub fn close_pairing(&mut self) {
        self.pairing = None;
    }
    pub fn pairing_open(&self) -> bool {
        self.pairing
            .as_ref()
            .is_some_and(|(_, expiry)| Instant::now() < *expiry)
    }
    pub fn consume_pairing(&mut self, candidate: &str) -> bool {
        // Hash comparison always performs fixed-size work. Tokens never appear in logs.
        let matched = self.pairing.as_ref().is_some_and(|(token, expiry)| {
            let a = Sha256::digest(token.as_bytes());
            let b = Sha256::digest(candidate.as_bytes());
            Instant::now() < *expiry && candidate.len() == 64 && bool::from(a.ct_eq(&b))
        });
        if matched {
            self.pairing = None;
        }
        matched
    }
    pub fn consume_ip_pairing(&mut self) -> bool {
        if self.pairing_open() {
            self.pairing = None;
            true
        } else {
            false
        }
    }
}

/// Enrollment permits an untrusted certificate only during the local pairing
/// window. TLS CertificateVerify still verifies possession of its private key.
/// Application messages remain unauthorized until token AND local approval pass.
#[derive(Debug)]
pub struct DeviceVerifier {
    pub trust: Arc<Mutex<Trust>>,
}

impl ClientCertVerifier for DeviceVerifier {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }
    fn verify_client_cert(
        &self,
        cert: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        _: UnixTime,
    ) -> Result<ClientCertVerified, Error> {
        if cert.len() > 8192 || !intermediates.is_empty() {
            return Err(Error::General("unsupported device certificate".into()));
        }
        let trust = self.trust.lock().unwrap();
        if trust.peers.contains(&fingerprint(cert.as_ref())) || trust.pairing_open() {
            Ok(ClientCertVerified::assertion())
        } else {
            Err(Error::General("device is not paired".into()))
        }
    }
    fn verify_tls12_signature(
        &self,
        m: &[u8],
        c: &CertificateDer<'_>,
        d: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        rustls::crypto::verify_tls12_signature(
            m,
            c,
            d,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }
    fn verify_tls13_signature(
        &self,
        m: &[u8],
        c: &CertificateDer<'_>,
        d: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        rustls::crypto::verify_tls13_signature(
            m,
            c,
            d,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn pairing_token_is_single_use_and_bad_attempt_does_not_consume() {
        let mut trust = Trust::new([]);
        let token = trust.open_pairing();
        assert!(!trust.consume_pairing(&"0".repeat(64)));
        assert!(trust.consume_pairing(&token));
        assert!(!trust.consume_pairing(&token));
        assert!(!trust.pairing_open());
    }
    #[test]
    fn closing_pairing_invalidates_token() {
        let mut trust = Trust::new([]);
        let token = trust.open_pairing();
        trust.close_pairing();
        assert!(!trust.consume_pairing(&token));
    }
}
