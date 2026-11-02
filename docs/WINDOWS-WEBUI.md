# Windows Web UI

Windows retains the Rust/Win32 background host, clipboard listener, tray, TLS protocol,
DPAPI identity and portable `data` directory. Wry embeds Microsoft WebView2 only when
the settings window is opened. Closing the window drops the WebView and rejects an
outstanding pairing request; the host keeps syncing. Reopening reconstructs the UI.
When the tray cannot be registered, closing the window exits instead of leaving an
unreachable background process; background startup opens the window in that case.
Exit is available from the system tray. Tray registration retries every
5 seconds on failure and is refreshed every 30 seconds after success.

The Sync, Devices and Settings pages use Google `@material/web` 2.5.0 components. The sidebar and cards are
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
Port changes still require a complete exit/restart. Pairing remains
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

## Desktop device management (2026-11-01)

The persistent left navigation opens Sync, Devices and Settings. Devices owns IP
pairing and individual removal; there is no priority, sorting control or active-device
selector. The server continues broadcasting to all connected, trusted devices.
The default window size is 1300 × 1000 pixels. The sidebar becomes a compact labeled rail below 840px; content is capped at 960px.
Light/dark tonal surfaces, shape tokens and reduced-motion support use Material 3.

Add device opens the pairing panel, immediately starts the 120-second pairing window and shows a locally generated QR code containing private IPv4 candidates, the listening port, server certificate fingerprint and a one-time token. The code disappears on use, cancellation or expiry. The scan heading and QR code appear at the top of the connection card, before the manual address and port. Manual host/port entry remains available. The connection panel is hidden by default and only Add device reveals it. Successful enrollment or explicit cancellation hides it; subsequent background state updates and page navigation never reveal it again. Adding another device requires clicking Add device again.
Both peers still verify the same code. Delete device shows a confirmation and sends
`deletePeer` with the certificate identity, never a display name or row index.
The server persists removal before revoking trust, then broadcasts a session change;
only the revoked peer disconnects. Invalid identities and save failures preserve trust.
The old bulk-revoke backend remains for compatibility, but has no settings UI entry.

Verification: 13 Rust tests passed (2 ignored), including two TLS clients with the
same display name, retained-session broadcast, reconnect rejection, persisted trust
and failed-save behavior. Five web tests passed, including navigation, empty list,
safe names and deletion by identity. These tests use isolated data, no user clipboard.

Actual English UI was inspected with Computer Use at narrow and maximized window sizes.
Existing phone remained connected; production pairing/deletion was not exercised.

## Startup state

The startup switch reads the current-user Run entry named CSync and its StartupApproved\Run state directly from the Windows registry, including external enable/disable changes. Portable settings no longer store auto_start; legacy values are ignored and removed without changing trust or identity. Unsaved switch edits survive periodic state refresh. Only a switch edit explicitly saved by the user writes the registry; unrelated settings saves and ordinary launches never rewrite it. Enabling registers the current EXE with --background and clears this entry’s prior StartupApproved override. Read failures disable the switch and show an error rather than falling back to a configuration value. Registry tests use a unique non-startup test subtree, not the real Run key; on this workstation run the test executable from a normal-integrity temporary directory because the project directory inherits Low integrity.
