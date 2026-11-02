//! Current-user startup state is owned by Windows, never by portable settings.
use crate::storage::wide;
use anyhow::{Context, Result, ensure};
use std::ptr::{null, null_mut};
use windows_sys::Win32::{Foundation::*, System::Registry::*};

const RUN: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
const APPROVED: &str = r"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";
const NAME: &str = "CSync";

struct Key(HKEY);
impl Drop for Key {
    fn drop(&mut self) {
        unsafe {
            RegCloseKey(self.0);
        }
    }
}

fn registry_result(status: u32) -> Result<()> {
    if status == ERROR_SUCCESS {
        Ok(())
    } else {
        Err(std::io::Error::from_raw_os_error(status as i32).into())
    }
}

fn read_value(root: HKEY, path: &str) -> Result<Option<(u32, Vec<u8>)>> {
    let path = wide(path);
    let name = wide(NAME);
    // Bound the allocation and retry if another process changes the value size.
    for _ in 0..3 {
        let mut kind = 0;
        let mut size = 0;
        let status = unsafe {
            RegGetValueW(
                root,
                path.as_ptr(),
                name.as_ptr(),
                RRF_RT_ANY | RRF_NOEXPAND,
                &mut kind,
                null_mut(),
                &mut size,
            )
        };
        if status == ERROR_FILE_NOT_FOUND || status == ERROR_PATH_NOT_FOUND {
            return Ok(None);
        }
        registry_result(status).context("cannot read startup registry value")?;
        ensure!(size <= 65536, "startup registry value is too large");
        let mut bytes = vec![0u8; size as usize];
        let status = unsafe {
            RegGetValueW(
                root,
                path.as_ptr(),
                name.as_ptr(),
                RRF_RT_ANY | RRF_NOEXPAND,
                &mut kind,
                bytes.as_mut_ptr().cast(),
                &mut size,
            )
        };
        if status == ERROR_MORE_DATA {
            continue;
        }
        if status == ERROR_FILE_NOT_FOUND || status == ERROR_PATH_NOT_FOUND {
            return Ok(None);
        }
        registry_result(status).context("cannot read startup registry value")?;
        bytes.truncate(size as usize);
        return Ok(Some((kind, bytes)));
    }
    anyhow::bail!("startup registry value changed while reading")
}

fn enabled_at(root: HKEY, run: &str, approved: &str) -> Result<bool> {
    let Some((kind, bytes)) = read_value(root, run)? else {
        return Ok(false);
    };
    ensure!(
        kind == REG_SZ || kind == REG_EXPAND_SZ,
        "invalid startup command type"
    );
    ensure!(bytes.len() % 2 == 0, "invalid startup command encoding");
    let units: Vec<_> = bytes
        .chunks_exact(2)
        .map(|chunk| u16::from_le_bytes([chunk[0], chunk[1]]))
        .take_while(|unit| *unit != 0)
        .collect();
    let command = String::from_utf16(&units).context("invalid startup command encoding")?;
    if command.trim().is_empty() {
        return Ok(false);
    }
    if let Some((kind, bytes)) = read_value(root, approved)? {
        ensure!(
            kind == REG_BINARY && bytes.len() >= 4,
            "invalid startup approval value"
        );
        let state = u32::from_le_bytes(bytes[..4].try_into().unwrap());
        return Ok(matches!(state, 0 | 2 | 6));
    }
    Ok(true)
}

pub fn startup_enabled() -> Result<bool> {
    enabled_at(HKEY_CURRENT_USER, RUN, APPROVED)
}

fn delete_value(root: HKEY, path: &str) -> Result<()> {
    let mut handle = null_mut();
    let status = unsafe { RegOpenKeyExW(root, wide(path).as_ptr(), 0, KEY_SET_VALUE, &mut handle) };
    if status == ERROR_FILE_NOT_FOUND || status == ERROR_PATH_NOT_FOUND {
        return Ok(());
    }
    registry_result(status)?;
    let key = Key(handle);
    let status = unsafe { RegDeleteValueW(key.0, wide(NAME).as_ptr()) };
    if status == ERROR_FILE_NOT_FOUND {
        return Ok(());
    }
    registry_result(status)
}

fn set_at(root: HKEY, run: &str, approved: &str, command: Option<&str>) -> Result<()> {
    let Some(command) = command else {
        return delete_value(root, run);
    };
    let mut handle = null_mut();
    registry_result(unsafe {
        RegCreateKeyExW(
            root,
            wide(run).as_ptr(),
            0,
            null(),
            0,
            KEY_SET_VALUE,
            null(),
            &mut handle,
            null_mut(),
        )
    })?;
    let key = Key(handle);
    let text = wide(command);
    registry_result(unsafe {
        RegSetValueExW(
            key.0,
            wide(NAME).as_ptr(),
            0,
            REG_SZ,
            text.as_ptr().cast(),
            (text.len() * 2) as u32,
        )
    })?;
    // Only an explicit user enable action clears a prior Task Manager disable.
    delete_value(root, approved)
}

pub fn set_startup(enabled: bool) -> Result<()> {
    let command = if enabled {
        Some(format!(
            "\"{}\" --background",
            std::env::current_exe()?.display()
        ))
    } else {
        None
    };
    set_at(HKEY_CURRENT_USER, RUN, APPROVED, command.as_deref())
        .context("cannot update startup settings")
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn registry_is_authoritative_and_explicit_enable_resets_approval() {
        // Never touch the real Windows startup keys in tests.
        let path = format!(r"Software\AppDataLow\CSync\Tests\{}", uuid::Uuid::new_v4());
        let mut handle = null_mut();
        registry_result(unsafe {
            RegCreateKeyExW(
                HKEY_CURRENT_USER,
                wide(&path).as_ptr(),
                0,
                null(),
                0,
                KEY_ALL_ACCESS,
                null(),
                &mut handle,
                null_mut(),
            )
        })
        .unwrap();
        let root = Key(handle);
        let result = std::panic::catch_unwind(|| {
            assert!(!enabled_at(root.0, "Run", "Approved").unwrap());
            set_at(
                root.0,
                "Run",
                "Approved",
                Some(r#""C:\Other Folder\CSync.exe" --background"#),
            )
            .unwrap();
            assert!(
                enabled_at(root.0, "Run", "Approved").unwrap(),
                "another portable folder still reads the registered entry"
            );
            let mut handle = null_mut();
            registry_result(unsafe {
                RegCreateKeyExW(
                    root.0,
                    wide("Approved").as_ptr(),
                    0,
                    null(),
                    0,
                    KEY_SET_VALUE,
                    null(),
                    &mut handle,
                    null_mut(),
                )
            })
            .unwrap();
            let approved = Key(handle);
            for (state, expected) in [(2, true), (3, false), (6, true), (7, false)] {
                let mut bytes = [0u8; 12];
                bytes[0] = state;
                registry_result(unsafe {
                    RegSetValueExW(
                        approved.0,
                        wide(NAME).as_ptr(),
                        0,
                        REG_BINARY,
                        bytes.as_ptr(),
                        bytes.len() as u32,
                    )
                })
                .unwrap();
                assert_eq!(enabled_at(root.0, "Run", "Approved").unwrap(), expected);
            }
            set_at(
                root.0,
                "Run",
                "Approved",
                Some(r#""C:\New Folder\CSync.exe" --background"#),
            )
            .unwrap();
            assert!(enabled_at(root.0, "Run", "Approved").unwrap());
            assert!(read_value(root.0, "Approved").unwrap().is_none());
            set_at(root.0, "Run", "Approved", None).unwrap();
            set_at(root.0, "Run", "Approved", None).unwrap();
            assert!(!enabled_at(root.0, "Run", "Approved").unwrap());
        });
        drop(root);
        registry_result(unsafe { RegDeleteTreeW(HKEY_CURRENT_USER, wide(&path).as_ptr()) })
            .unwrap();
        result.unwrap();
    }
}
