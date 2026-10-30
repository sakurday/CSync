/// Exercise the real Win32 clipboard on a private, non-visible window station.
/// The user's interactive clipboard is never opened or changed by this test.
#[test]
#[ignore = "requires permission to create a private Win32 window station; does not use the interactive clipboard"]
fn native_clipboard_unicode_echo_and_identical_recopy() {
    use crate::{clipboard::Clipboard, storage::wide};
    use csync_core::protocol::Clip;
    use std::ptr::{null, null_mut};
    use windows_sys::Win32::{
        Foundation::*,
        System::{
            LibraryLoader::GetModuleHandleW, StationsAndDesktops::*, Threading::GetCurrentThreadId,
        },
        UI::WindowsAndMessaging::*,
    };
    unsafe {
        struct Station {
            old: HWINSTA,
            old_desktop: HDESK,
            station: HWINSTA,
            desktop: HDESK,
        }
        impl Drop for Station {
            fn drop(&mut self) {
                unsafe {
                    SetProcessWindowStation(self.old);
                    SetThreadDesktop(self.old_desktop);
                    if !self.desktop.is_null() {
                        CloseDesktop(self.desktop);
                    }
                    CloseWindowStation(self.station);
                }
            }
        }
        struct Window(HWND);
        impl Drop for Window {
            fn drop(&mut self) {
                unsafe {
                    DestroyWindow(self.0);
                }
            }
        }
        let old = GetProcessWindowStation();
        let old_desktop = GetThreadDesktop(GetCurrentThreadId());
        let station = CreateWindowStationW(
            wide(&format!("CSyncTest{}", uuid::Uuid::new_v4())).as_ptr(),
            0,
            WINSTA_ALL_ACCESS as u32,
            null(),
        );
        assert!(
            !station.is_null(),
            "private window station: {}",
            GetLastError()
        );
        let mut guard = Station {
            old,
            old_desktop,
            station,
            desktop: null_mut(),
        };
        assert_ne!(SetProcessWindowStation(station), 0);
        guard.desktop = CreateDesktopW(
            wide("CSyncClipboardTest").as_ptr(),
            null(),
            null(),
            0,
            0x01ff,
            null(),
        );
        assert!(!guard.desktop.is_null());
        assert_ne!(SetThreadDesktop(guard.desktop), 0);
        let window = || {
            Window(CreateWindowExW(
                0,
                wide("STATIC").as_ptr(),
                wide("").as_ptr(),
                WS_POPUP,
                0,
                0,
                1,
                1,
                null_mut(),
                null_mut(),
                GetModuleHandleW(null()),
                null(),
            ))
        };
        let first = window();
        let second = window();
        assert!(!first.0.is_null() && !second.0.is_null());
        let mut a = Clipboard::new(first.0).unwrap();
        let mut b = Clipboard::new(second.0).unwrap();
        let clip = Clip::new(&"a".repeat(64), 1, "CSync 原生剪贴板\n🙂".into()).unwrap();
        a.write(&clip).unwrap();
        assert_eq!(a.read().unwrap(), None, "a remote write must not echo");
        assert_eq!(b.read().unwrap().as_deref(), Some(clip.text.as_str()));
        b.write(&clip).unwrap();
        assert_eq!(
            a.read().unwrap().as_deref(),
            Some(clip.text.as_str()),
            "a later copy of equal text remains observable"
        );
    }
}
