# CSync protocol v1

Both TCP and RFCOMM carry TLS 1.3, then identical framed JSON. There is no cleartext application endpoint and no HTTP server.

## Frame

`uint32 length (network/big endian)` followed by exactly `length` UTF-8 JSON bytes. Accept 1 through 1,581,056 bytes (`256 * 1024 * 6 + 8192`). The limit permits worst-case JSON escaping of a 256 KiB UTF-8 text. Validate the length before allocating. Reject malformed UTF-8/JSON, incompatible versions, invalid hashes, and unexpected message types.

## Device identity and pairing

Identity = lowercase SHA-256 of the full DER device certificate. Windows generates a self-signed EC certificate and protects its PKCS#8 key with DPAPI. Android creates a P-256 signing key in Android Keystore, stores the certificate with the key, and supplies it through an X509ExtendedKeyManager. Reinstallation or identity replacement requires re-pairing.

The user enters the Windows host and port. During a 120-second pairing window the server permits an unknown client certificate to complete TLS; TLS still verifies possession of both private keys. The phone provisionally accepts the server certificate only for enrollment. Both sides independently display the first 12 hexadecimal characters of SHA-256 over `server_fingerprint + ":" + client_fingerprint` as a verification code. The user must compare the codes and confirm on both devices.

The phone sends `{"type":"pair","version":1,"token":"","name":"Phone"}`. The server consumes the pairing window and asks the Windows user to confirm. If approved, it sends `{"type":"pair_challenge"}`. Only after the Android user confirms does the phone send `{"type":"pair_confirm"}`. The server persists the client fingerprint and returns `{"type":"paired","version":1,"server":"<server identity>","name":"Windows"}`; the phone verifies that identity matches the TLS certificate before persisting it. Subsequent connections pin the exact server certificate.

Enrollment is TCP-only. Denial, timeout or disconnection before both confirmations does not register a device. There is no clipboard access before enrollment. Bluetooth connections must already be trusted. The protocol requires updated applications on both sides for new pairing.

## Session

Each reconnect performs fresh mutually authenticated TLS. The first message is `{"type":"hello","version":1,"name":"Phone"}`. Server returns `{"type":"ready","version":1,"server":"...","session":"<uuid>"}`. The most recent authenticated connection for a device supersedes its previous transport.

New local copy from the phone:

```json
{"type":"clip","clip":{"id":"<uuid>","origin":"<client identity>","sequence":1,"text":"hello","hash":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"}}
```

`sequence` is positive and strictly increasing per connection; clients keep a process-local counter. `origin` must equal the TLS peer identity. `hash` is SHA-256 over exact UTF-8 bytes, with no normalization. Empty clips / automatic clearing are not forwarded. Event deduplication uses 1024 bounded UUID entries; identical text with a new UUID remains a new event.

The native clipboard thread applies an incoming copy and only then commits it. A committed local or remote event broadcasts `{"type":"commit","revision":1,"clip":{...}}` to all active sessions, including its sender. Revisions establish server processing order and are monotonic during the server process; clients reset their revision on a new session. No system clock comparison is required.

Writing the committed event at the origin is unnecessary. Android tracks pending local event IDs, removes them on own commits, and does not let an earlier remote commit overwrite a pending local copy. OS failure / pause returns `{"type":"ack","id":"...","status":"unavailable"}` or `paused`. The Android receiver returns applied / paused / superseded / shizuku_unavailable / clipboard_unavailable acknowledgments. An unavailable write does not create a durable offline queue.

Keepalive messages are `ping` / `pong` with a numeric `nonce`. A dedicated reader preserves partially received frames while broadcasts are written, preventing cancellation of an incomplete length/body read. The server bounds connections, input queues, broadcast retention, rate-limit tracking, frame size, TLS handshake time, and write time. Lagging or invalid peers are disconnected.

Sessions retain no historical clipboard snapshot for reconnect. Reconnect never overwrites the current clipboard with an earlier saved value.
