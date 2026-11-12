# ProxyShare

Run an HTTP/HTTPS/SOCKS4/SOCKS5 proxy server on the phone so a laptop on the
same Wi-Fi or hotspot can route traffic through the phone, mainly to share the
phone's active VPN. No root.

## Privacy

ProxyShare never inspects or decrypts traffic payloads (no MITM). It only
records metadata: client IP, target host:port, protocol, byte counts.

## Layout

- `core/` — proxy engine, plain Kotlin, no Android imports.
- `service/` — Android foreground service hosting the engine.
- `data/` — settings persistence (DataStore).
- `ui/` — screens, theme, components.

## Stack

Kotlin, Jetpack Compose, Material 3, Coroutines, DataStore. No Hilt.
No Room unless a step says so. minSdk 26, targetSdk 36, single module,
package `com.example.proxyshare`.

## Versions

See `CHANGELOG.md`. One push = one commit = one CI run. CI builds the debug
APK; signing is persistent via `PROXYSHARE_*` repo secrets (see the workflow)
so every update installs over the previous one.
