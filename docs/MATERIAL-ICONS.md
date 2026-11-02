# UI icons

The Google Material SVG downloads in `icons/` are the source for settings,
delete, check_circle, error, mobile, menu, sync, computer, add and chevron_right.

Run `python scripts/generate-material-icons.py` after changing these sources.
It preserves each path and its 24 × 24 viewport, generates Android vector
drawables and `crates/csync-windows/web/material-icons.mjs`, and leaves the
original downloads untouched. Compose supplies MaterialTheme tint; Web SVGs
use currentColor. No remote icon font or runtime asset loading is used.

Android uses the downloaded settings/delete/check_circle/error paths. The
downloaded three-line menu path matches the existing host drag handle geometry
and replaces that resource. Windows uses mobile for the Devices destination and
device avatars, settings for Settings, and delete on individual deletion buttons.
The compact desktop rail retains both icons and text labels.

All UI symbols now come from the supplied SVGs, including sync, computer,
add, chevron_right, qr_code and qr_code_scanner added on 2026-11-02. The QR code icon labels the desktop pairing QR; the scanner icon labels the Android scan action. Android's chevron retains autoMirrored
for right-to-left layouts. The CSync launcher/window/tray/notification/About brand
assets remain derived from `icon_512.png`.

After generation, rebuild the Web bundle and both applications. Do not edit
generated vectors or material-icons.mjs by hand.
