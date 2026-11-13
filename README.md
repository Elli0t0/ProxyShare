# ProxyShare

Run an HTTP/HTTPS/SOCKS4/SOCKS5 proxy server on the phone so a laptop on the
same Wi-Fi or hotspot can route traffic through the phone, mainly to share the
phone's active VPN. No root.

## Features

- HTTP, SOCKS4 and SOCKS5 proxy on one port, with optional authentication
  (SOCKS5 RFC 1929, HTTP Basic) and a failed-auth throttle.
- Live Connections monitor: per-client traffic, speeds, durations.
- Settings: port, bind address, protocols, auth, allowed clients, limits,
  theme (System/Light/Dark).
- Outbound mode (default / VPN only / Wi-Fi / mobile) with a VPN kill
  switch, and an exit-IP check.
- "Connect your laptop" sheet with copyable curl/terminal commands and
  plain-text Windows and Firefox setup steps.

## How to use

1. Start the proxy on Home.
2. Tap "Connect your laptop" and pick the phone's address.
3. On the laptop, set the proxy (HTTP or SOCKS5) to that host and port —
   use the copyable curl commands, the terminal export line, or the
   Windows/Firefox steps in the sheet.

## Privacy

ProxyShare never inspects or decrypts traffic payloads (no MITM). It only
records metadata: client IP, target host:port, protocol, byte counts.

## Layout

- `rust/engine/` — proxy engine, a Rust crate (cdylib, `proxyshare_engine`).
  0.0.2: JNI hello world (`nativeVersion`) only; 0.0.3: models, config,
  CIDR (parse + contains, unit tested); 0.0.4: server shell (tokio
  runtime, accept loop, sniffer, JNI lifecycle) — no handlers yet.
- `engine/` — thin JNI wrapper only. No proxy logic in Kotlin.
- `service/` — Android foreground service hosting the engine.
- `data/` — settings persistence (DataStore).
- `ui/` — screens, theme, components.

The Kotlin ↔ Rust contract is in `docs/ENGINE_API.md`.

## Stack

Kotlin, Jetpack Compose, Material 3, Coroutines, DataStore. No Hilt.
No Room unless a step says so. minSdk 26, targetSdk 36, single module,
package `com.example.proxyshare`.

## Versions

See `CHANGELOG.md`. One push = one commit = one CI run. CI builds the debug
and release APKs (release with R8) and uploads both as artifacts; the release
APK size is printed in the job summary. Signing is persistent via
`PROXYSHARE_*` repo secrets (see the workflow) so every update installs over
the previous one.
