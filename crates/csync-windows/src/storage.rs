use anyhow::{Context, Result, ensure};
use csync_core::{protocol::DEFAULT_PORT, security::fingerprint};
use rcgen::{CertifiedKey, generate_simple_self_signed};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    ptr::null_mut,
};
use windows_sys::Win32::{Foundation::LocalFree, Security::Cryptography::*, System::Registry::*};

#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    pub name: String,
    pub port: u16,
    pub public_access: bool,
    pub bluetooth: bool,
    pub auto_start: bool,
    pub peers: BTreeMap<String, String>,
}
impl Default for Config {
    fn default() -> Self {
        Self {
            name: std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows".into()),
            port: DEFAULT_PORT,
            public_access: false,
            bluetooth: false,
            auto_start: false,
            peers: BTreeMap::new(),
        }
    }
}

pub struct Store {
    pub directory: PathBuf,
}
pub struct Identity {
    pub cert: Vec<u8>,
    pub key: Vec<u8>,
    pub id: String,
}
#[derive(Serialize, Deserialize)]
struct IdentityData {
    cert: Vec<u8>,
    key: Vec<u8>,
}

impl Store {
    pub fn open() -> Result<Self> {
        let args: Vec<String> = std::env::args().collect();
        let directory = if let Some(i) = args.iter().position(|s| s == "--data-dir") {
            let directory = PathBuf::from(args.get(i + 1).context("--data-dir requires a path")?);
            prepare_directory(&directory, None)?;
            directory
        } else {
            let executable = std::env::current_exe()?;
            let directory = executable
                .parent()
                .context("无法确定程序目录")?
                .join("data");
            let legacy = std::env::var_os("LOCALAPPDATA").map(|p| PathBuf::from(p).join("CSync"));
            prepare_directory(&directory, legacy.as_deref())?;
            directory
        };
        Ok(Self { directory })
    }
    pub fn config(&self) -> Result<Config> {
        let path = self.directory.join("settings.json");
        if path.exists() {
            Ok(serde_json::from_slice(&std::fs::read(path)?)?)
        } else {
            Ok(Config::default())
        }
    }
    pub fn save(&self, config: &Config) -> Result<()> {
        ensure!(config.port != 0, "port must be 1..65535");
        atomic_write(
            &self.directory.join("settings.json"),
            &serde_json::to_vec_pretty(config)?,
        )
    }
    pub fn identity(&self) -> Result<Identity> {
        let path = self.directory.join("identity.dpapi");
        let data: IdentityData = if path.exists() {
            serde_json::from_slice(&unprotect(&std::fs::read(path)?)?)?
        } else {
            let CertifiedKey { cert, signing_key } =
                generate_simple_self_signed(vec!["csync.local".into()])?;
            let data = IdentityData {
                cert: cert.der().to_vec(),
                key: signing_key.serialize_der(),
            };
            atomic_write(&path, &protect(&serde_json::to_vec(&data)?)?)?;
            data
        };
        Ok(Identity {
            id: fingerprint(&data.cert),
            cert: data.cert,
            key: data.key,
        })
    }
}

fn prepare_directory(directory: &Path, legacy: Option<&Path>) -> Result<()> {
    if directory.exists() {
        return Ok(());
    }
    if let Some(legacy) = legacy.filter(|p| p.join("identity.dpapi").is_file()) {
        // Publish identity and settings together; never mix two device identities.
        let staging = directory.with_extension(format!("migrate-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&staging)
            .context("无法在程序目录创建 data，请将程序放在可写目录")?;
        let result = (|| -> Result<()> {
            for name in ["identity.dpapi", "settings.json"] {
                let source = legacy.join(name);
                if source.exists() {
                    atomic_write(&staging.join(name), &std::fs::read(&source)?)?;
                }
            }
            std::fs::rename(&staging, directory).context("无法完成旧配置迁移")?;
            Ok(())
        })();
        if result.is_err() {
            let _ = std::fs::remove_dir_all(&staging);
        }
        result.context("迁移旧版配置失败，原文件已保留")?;
    } else {
        std::fs::create_dir_all(directory)
            .context("无法在程序目录创建 data，请将程序放在可写目录")?;
    }
    Ok(())
}

#[cfg(test)]
mod storage_tests {
    use super::*;
    #[test]
    fn migration_preserves_identity_and_trust_and_keeps_originals() {
        let root = std::env::current_dir()
            .unwrap()
            .join(".build")
            .join(format!("storage-{}", uuid::Uuid::new_v4()));
        let old = root.join("old");
        let new = root.join("data");
        std::fs::create_dir_all(&old).unwrap();
        let original = Store {
            directory: old.clone(),
        };
        let id = original.identity().unwrap().id;
        let mut config = Config::default();
        config.port = 45879;
        config.peers.insert("a".repeat(64), "Phone".into());
        original.save(&config).unwrap();
        prepare_directory(&new, Some(&old)).unwrap();
        let migrated = Store {
            directory: new.clone(),
        };
        assert_eq!(migrated.identity().unwrap().id, id);
        assert_eq!(migrated.config().unwrap().port, 45879);
        assert_eq!(migrated.config().unwrap().peers, config.peers);
        assert!(old.join("identity.dpapi").is_file());
        let mut updated = migrated.config().unwrap();
        updated.port = 45880;
        migrated.save(&updated).unwrap();
        prepare_directory(&new, Some(&old)).unwrap();
        assert_eq!(migrated.config().unwrap().port, 45880);
        std::fs::remove_dir_all(root).unwrap();
    }
}

fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    use windows_sys::Win32::Storage::FileSystem::{
        MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH, MoveFileExW,
    };
    let temp = path.with_extension("new");
    let mut file = std::fs::File::create(&temp)
        .with_context(|| format!("无法创建配置临时文件 {}", temp.display()))?;
    std::io::Write::write_all(&mut file, bytes).context("无法写入配置临时文件")?;
    file.sync_all().context("无法刷新配置临时文件")?;
    drop(file);
    ensure!(
        unsafe {
            MoveFileExW(
                wide(temp.as_os_str().to_string_lossy().as_ref()).as_ptr(),
                wide(path.as_os_str().to_string_lossy().as_ref()).as_ptr(),
                MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
            )
        } != 0,
        "cannot replace settings file"
    );
    Ok(())
}

pub fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(Some(0)).collect()
}

fn protect(bytes: &[u8]) -> Result<Vec<u8>> {
    crypt(bytes, true)
}
fn unprotect(bytes: &[u8]) -> Result<Vec<u8>> {
    crypt(bytes, false)
}
fn crypt(bytes: &[u8], encrypt: bool) -> Result<Vec<u8>> {
    unsafe {
        let input = CRYPT_INTEGER_BLOB {
            cbData: bytes.len() as u32,
            pbData: bytes.as_ptr() as *mut _,
        };
        let mut output: CRYPT_INTEGER_BLOB = std::mem::zeroed();
        let ok = if encrypt {
            CryptProtectData(
                &input,
                wide("CSync device identity").as_ptr(),
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        } else {
            CryptUnprotectData(
                &input,
                null_mut(),
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        };
        ensure!(
            ok != 0,
            "Windows could not protect/unprotect device identity"
        );
        let result = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        LocalFree(output.pbData.cast());
        Ok(result)
    }
}

pub fn set_startup(enabled: bool) -> Result<()> {
    unsafe {
        let mut key = null_mut();
        ensure!(
            RegCreateKeyExW(
                HKEY_CURRENT_USER,
                wide("Software\\Microsoft\\Windows\\CurrentVersion\\Run").as_ptr(),
                0,
                std::ptr::null(),
                0,
                KEY_SET_VALUE,
                std::ptr::null(),
                &mut key,
                null_mut()
            ) == 0,
            "cannot open startup settings"
        );
        let name = wide("CSync");
        let status = if enabled {
            let cmd = wide(&format!(
                "\"{}\" --background",
                std::env::current_exe()?.display()
            ));
            RegSetValueExW(
                key,
                name.as_ptr(),
                0,
                REG_SZ,
                cmd.as_ptr().cast(),
                (cmd.len() * 2) as u32,
            )
        } else {
            let status = RegDeleteValueW(key, name.as_ptr());
            if status == 2 { 0 } else { status }
        };
        RegCloseKey(key);
        ensure!(status == 0, "cannot update startup settings");
    }
    Ok(())
}
