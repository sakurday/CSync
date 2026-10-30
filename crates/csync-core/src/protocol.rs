use anyhow::{Result, bail, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const VERSION: u32 = 1;
pub const DEFAULT_PORT: u16 = 45876;
pub const MAX_TEXT: usize = 256 * 1024;
// JSON escaping can expand one source byte into six bytes.
pub const MAX_FRAME: usize = MAX_TEXT * 6 + 8192;
pub const BT_UUID: &str = "b5d37a72-0b98-4bca-9690-f1f709d093d8";

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Clip {
    pub id: String,
    pub origin: String,
    pub sequence: u64,
    pub text: String,
    pub hash: String,
}

impl Clip {
    pub fn new(origin: &str, sequence: u64, text: String) -> Result<Self> {
        let clip = Self {
            id: uuid::Uuid::new_v4().to_string(),
            origin: origin.into(),
            sequence,
            hash: text_hash(&text),
            text,
        };
        clip.validate()?;
        Ok(clip)
    }

    pub fn validate(&self) -> Result<()> {
        ensure!(
            !self.text.is_empty() && self.text.len() <= MAX_TEXT,
            "text size outside limit"
        );
        ensure!(uuid::Uuid::parse_str(&self.id).is_ok(), "invalid event id");
        ensure!(
            self.origin.len() == 64 && self.origin.bytes().all(|b| b.is_ascii_hexdigit()),
            "invalid origin"
        );
        ensure!(
            self.sequence > 0 && self.sequence <= i64::MAX as u64,
            "invalid sequence"
        );
        ensure!(self.hash == text_hash(&self.text), "content hash mismatch");
        Ok(())
    }
}

pub fn text_hash(text: &str) -> String {
    hex::encode(Sha256::digest(text.as_bytes()))
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Message {
    Pair {
        version: u32,
        token: String,
        name: String,
    },
    PairChallenge,
    PairConfirm,
    Paired {
        version: u32,
        server: String,
        name: String,
    },
    Hello {
        version: u32,
        name: String,
    },
    Ready {
        version: u32,
        server: String,
        session: String,
    },
    Clip {
        clip: Clip,
    },
    Commit {
        revision: u64,
        clip: Clip,
    },
    Ack {
        id: String,
        status: String,
    },
    Ping {
        nonce: u64,
    },
    Pong {
        nonce: u64,
    },
    Error {
        code: String,
    },
}

pub async fn read_message<R: AsyncRead + Unpin>(reader: &mut R) -> Result<Message> {
    let len = reader.read_u32().await? as usize;
    ensure!((1..=MAX_FRAME).contains(&len), "invalid frame length");
    let mut bytes = vec![0; len];
    reader.read_exact(&mut bytes).await?;
    Ok(serde_json::from_slice(&bytes)?)
}

pub async fn write_message<W: AsyncWrite + Unpin>(writer: &mut W, message: &Message) -> Result<()> {
    let bytes = serde_json::to_vec(message)?;
    if bytes.is_empty() || bytes.len() > MAX_FRAME {
        bail!("invalid outgoing frame length");
    }
    writer.write_u32(bytes.len() as u32).await?;
    writer.write_all(&bytes).await?;
    writer.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn unicode_and_escaped_text_round_trip() {
        let clip = Clip::new(&"a".repeat(64), 1, "你好\n\"手机\"\u{0001}🙂".into()).unwrap();
        let (mut a, mut b) = tokio::io::duplex(4096);
        write_message(&mut a, &Message::Clip { clip: clip.clone() })
            .await
            .unwrap();
        match read_message(&mut b).await.unwrap() {
            Message::Clip { clip: received } => assert_eq!(clip, received),
            _ => panic!("wrong message"),
        }
    }
    #[tokio::test]
    async fn oversized_frame_rejected_before_allocation() {
        let (mut a, mut b) = tokio::io::duplex(16);
        a.write_u32(u32::MAX).await.unwrap();
        assert!(read_message(&mut b).await.is_err());
    }
    #[test]
    fn validates_utf8_bytes_and_integrity() {
        assert!(Clip::new(&"a".repeat(64), 1, "你".repeat(MAX_TEXT / 3 + 1)).is_err());
        let mut clip = Clip::new(&"a".repeat(64), 1, "hello".into()).unwrap();
        clip.text.push('!');
        assert!(clip.validate().is_err());
    }
}
