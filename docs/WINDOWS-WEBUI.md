# Windows Web UI

Windows retains the Rust/Win32 background host, clipboard listener, tray, TLS protocol,
DPAPI identity and portable `data` directory. Wry embeds Microsoft WebView2 only when
the settings window is opened. Closing the window drops the WebView and rejects an
outstanding pairing request; the host keeps syncing. Reopening reconstructs the UI.
When the tray cannot be registered, closing the window exits instead of leaving an
unreachable background process; background startup opens the window in that case.
Settings also provides an explicit Exit button. Tray registration retries every
5 seconds on failure and is refreshed every 30 seconds after success.

The two pages use Google `@material/web` 2.5.0 components. The sidebar and cards are
CSS layouts; buttons, switches, fields and dialogs are official components. Assets
are compiled into the executable; no CDN, HTTP management port or remote fonts are
used. The page cannot navigate to external content, open windows or make network
requests. IPC exposes settings/actions and status, not clipboard text or identity.

## Building

`web/bundle.js` is checked in, so normal Cargo builds need no Node installation.
After editing `crates/csync-windows/web/app.js` or upgrading components:

```powershell
npm.cmd --prefix crates/csync-windows/web ci
npm.cmd --prefix crates/csync-windows/web run build
cargo fmt --all
cargo test --workspace --locked
cargo build --release --locked
```

Commit app.js, bundle.js and package-lock.json together. HTML and CSS are embedded
from index.html at Cargo build time. Third-party licenses accompany the sources;
esbuild also preserves bundled license comments.

## Runtime and verification

Microsoft Edge WebView2 Evergreen Runtime must be installed. Initialization errors
show a native message; background sync can continue without the settings view.
The runtime profile is `data/webview` (or `<--data-dir>/webview`), separate from
settings.json and identity.dpapi. The executable's directory must be writable.
Do not ship real runtime data. Runtime installation is not performed automatically.

Verify page navigation, pause/resume, settings validation/save, IP pairing with
both confirmations, timeout/rejection, close/reopen and --background startup.
Port and Bluetooth changes still require a complete exit/restart. Pairing remains
IP + port only. No QR/link pairing or clipboard history is added.

## Local file integrity

On this workstation the project directory has an inheritable Low mandatory label,
and freshly built/copied executables inherit it. Windows can therefore launch the
executable as a Low integrity process even from an ordinary user session. This is
separate from the application's `asInvoker` manifest and from file write access.
The user authorized changing only `dist/CSync.exe` to Medium integrity; do not change
the project directory, `data`, DACLs or system settings. Future replacement builds
must check the resulting executable's label. A Low parent process can still launch
a Low child even when its image is Medium; validate ordinary startup from Explorer.
The authorized label update was applied and ordinary Explorer startup was verified:
CSync and Explorer both ran at Medium integrity, CSync's own Shell_NotifyIcon result
reported success, and the phone reconnected. External icon-location queries from
a Low integrity diagnostic process failed even in this state and are not reliable
evidence of missing registration. Use the application's `trayAvailable` state.
The EXE's Medium label also means a Low integrity build process may no longer be
able to overwrite it; do not broaden folder permissions to work around this.
