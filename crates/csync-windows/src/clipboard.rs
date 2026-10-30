use crate::storage::wide;
use anyhow::{Result, ensure};
use csync_core::protocol::{Clip, MAX_TEXT};
use std::time::Duration;
use windows_sys::Win32::{
    Foundation::{GlobalFree, HWND},
    System::{DataExchange::*, Memory::*},
};

const UNICODE_TEXT: u32 = 13;
pub struct Clipboard {
    hwnd: HWND,
    marker_format: u32,
    sensitive_format: u32,
    last_write: Option<(u32, String)>,
    last_seen: u32,
}

struct Open;
impl Drop for Open {
    fn drop(&mut self) {
        unsafe {
            CloseClipboard();
        }
    }
}

impl Clipboard {
    pub unsafe fn new(hwnd: HWND) -> Result<Self> {
        ensure!(
            AddClipboardFormatListener(hwnd) != 0,
            "cannot watch clipboard"
        );
        Ok(Self {
            hwnd,
            marker_format: RegisterClipboardFormatW(wide("CSync.Event.v1").as_ptr()),
            sensitive_format: RegisterClipboardFormatW(
                wide("ExcludeClipboardContentFromMonitorProcessing").as_ptr(),
            ),
            last_write: None,
            last_seen: GetClipboardSequenceNumber(),
        })
    }
    fn open(&self) -> Result<Open> {
        for delay in [0, 5, 10, 20, 40] {
            if delay != 0 {
                std::thread::sleep(Duration::from_millis(delay));
            }
            if unsafe { OpenClipboard(self.hwnd) } != 0 {
                return Ok(Open);
            }
        }
        anyhow::bail!("clipboard is busy")
    }
    pub fn read(&mut self) -> Result<Option<String>> {
        unsafe {
            let _open = self.open()?;
            let sequence = GetClipboardSequenceNumber();
            if sequence == self.last_seen {
                return Ok(None);
            }
            if IsClipboardFormatAvailable(self.sensitive_format) != 0
                || IsClipboardFormatAvailable(UNICODE_TEXT) == 0
            {
                return Ok(None);
            }
            let memory = GetClipboardData(UNICODE_TEXT);
            if memory.is_null() {
                return Ok(None);
            }
            let count = GlobalSize(memory) / 2;
            if count == 0 || count > MAX_TEXT + 1 {
                return Ok(None);
            }
            let pointer = GlobalLock(memory).cast::<u16>();
            if pointer.is_null() {
                return Ok(None);
            }
            let slice = std::slice::from_raw_parts(pointer, count);
            let len = slice.iter().position(|c| *c == 0).unwrap_or(count);
            let text = String::from_utf16_lossy(&slice[..len]);
            GlobalUnlock(memory);
            if text.is_empty() || text.len() > MAX_TEXT {
                return Ok(None);
            }
            self.last_seen = sequence;
            if let Some((sequence, hash)) = &self.last_write {
                if *sequence == GetClipboardSequenceNumber()
                    && *hash == csync_core::protocol::text_hash(&text)
                {
                    return Ok(None);
                }
            }
            Ok(Some(text))
        }
    }
    pub fn write(&mut self, clip: &Clip) -> Result<()> {
        clip.validate()?;
        ensure!(!clip.text.contains('\0'), "Windows text cannot contain NUL");
        unsafe {
            let _open = self.open()?;
            ensure!(EmptyClipboard() != 0, "cannot clear clipboard");
            let text = wide(&clip.text);
            put(
                UNICODE_TEXT,
                std::slice::from_raw_parts(text.as_ptr().cast(), text.len() * 2),
            )?;
            // Optional marker is informational. Sequence + content protects against echo,
            // without suppressing later user copies of identical text.
            let _ = put(self.marker_format, clip.id.as_bytes());
            self.last_write = Some((GetClipboardSequenceNumber(), clip.hash.clone()));
        }
        Ok(())
    }
}
impl Drop for Clipboard {
    fn drop(&mut self) {
        unsafe {
            RemoveClipboardFormatListener(self.hwnd);
        }
    }
}

unsafe fn put(format: u32, bytes: &[u8]) -> Result<()> {
    let memory = GlobalAlloc(GMEM_MOVEABLE, bytes.len());
    ensure!(!memory.is_null(), "clipboard allocation failed");
    let pointer = GlobalLock(memory);
    if pointer.is_null() {
        GlobalFree(memory);
        anyhow::bail!("cannot lock clipboard memory");
    }
    std::ptr::copy_nonoverlapping(bytes.as_ptr(), pointer.cast::<u8>(), bytes.len());
    GlobalUnlock(memory);
    if SetClipboardData(format, memory).is_null() {
        GlobalFree(memory);
        anyhow::bail!("cannot set clipboard");
    }
    Ok(())
}
