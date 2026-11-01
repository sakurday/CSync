# Security design

## Trust boundary

The local OS, Shizuku installation, paired peer, and user-approved desktop are trusted. The LAN, Internet, mDNS-like discovery data (not implemented in v1), DNS and IP routing are untrusted. A paired device intentionally has permission to receive new clipboard contents and submit text to the paired clipboard.

TLS 1.3 encrypts and authenticates TCP connections. Previously paired devices pin each other's certificate identities. During IP enrollment, the phone provisionally accepts the TLS server certificate and both sides display a 48-bit verification code derived from both certificate fingerprints. The user must compare the codes and confirm on both devices; accepting a mismatched code defeats this protection. The TLS handshake verifies possession of both private keys.

Pairing is only possible while explicitly enabled locally, for 120 seconds. A request consumes the window. Windows approval and an explicit phone confirmation are both required before the server persists the phone fingerprint. Names are bounded labels, not identities. No clipboard data is exposed before enrollment.

Default TCP source filtering is for operational convenience, not authentication or a complete network boundary. TLS and enrollment are always required. Public mode does not remove authentication. No UPnP rule, router setting, or Windows firewall exception is created automatically.

## Stored information

Windows: settings (device names, fingerprints, network configuration), a DPAPI-encrypted device identity. Android: private preferences (network configuration and pinned server identity) and a non-exportable Keystore signing key. App backup is disabled. No release signing key is stored in source.

Clipboard bodies exist transiently in OS clipboards, IPC, TLS buffers and bounded queues; they are not written to disk by the application. This does not prevent the OS, keyboard, paired peer or other authorized software from retaining clipboard data. This application is not a password vault and cannot recognize all sensitive text. Users should pause sync when needed.

The Shizuku component has a narrow AIDL surface and performs only clipboard-related operations on its own executor thread. Calling-application Binder identity is not reused for privileged clipboard calls. Network handling and device private keys remain in the ordinary app process. No generic shell execution or remote command interface is exposed.

## Resource abuse

The server accepts up to eight concurrent transport sessions / handshakes. TCP connection attempts are limited per source before TLS (12 per minute, at most 256 rate-limit entries), messages have fixed maximum sizes, names have a small limit, and clipboard submissions are limited to 32 per second per peer. Peers exceeding limits / lagging behind the bounded bus are disconnected. TLS / application handshakes and network writes have deadlines.

These measures bound normal application work; they do not make a publicly reachable PC immune to volumetric denial of service. Firewall / router boundaries remain outside this application's control.

## Revocation and recovery

Desktop "撤销全部设备" clears persistent trust, closes enrollment, broadcasts a trust change and disconnects live sessions. Reconnect with the revoked key fails TLS. Android "解除电脑配对" clears local trust and stops its service; use desktop revocation as well to remove the phone's access on that computer.

Deleting or changing an identity requires explicit pairing again. Server certificate pinning never silently falls back to platform CA trust or an accept-all trust manager. Permission revocation, unsupported Android interfaces, and unavailable clipboards fail with state information instead of falling back to polling, accessibility scraping, logcat scraping or focus-stealing overlays.
