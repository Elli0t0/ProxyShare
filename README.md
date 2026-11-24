# ProxyShare

Run an HTTP/HTTPS/SOCKS4/SOCKS5 proxy server on the phone so a laptop on the
same Wi-Fi or hotspot can route traffic through the phone, mainly to share the
phone's active VPN. No root.

## Features

- HTTP, SOCKS4 and SOCKS5 proxy on one port, with optional authentication
  (SOCKS5 RFC 1929, HTTP Basic) and a failed-auth throttle.
- Live Connections monitor: per-client traffic, speeds, durations.
- Health card on Home: one row says whether sharing looks fine,
  tapping it lists the checks (notifications, battery, VPN, shareable
  address, port listening, password) with a Fix button for each
  failing item.
- Settings: port, bind address, protocols, auth, allowed clients, blocked
  domains, block private networks, limits, theme (System/Light/Dark).
- PAC server (Settings > Server, off by default): serves /proxy.pac and a
  setup page, so a laptop can configure itself by scanning the QR code on
  the "Connect your laptop" page.
- Routing rules engine: PROXY / DIRECT / BLOCK rules per domain or IP/CIDR
  (hot-reloaded, no restart); DIRECT traffic goes out through the phone's
  real non-VPN network. The Rules tab UI comes next.
- Backup: export and import all settings as a JSON file
  (Settings > Backup); the password is never included.
- Outbound mode (default / VPN only / Wi-Fi / mobile) with a VPN kill
  switch, and an exit-IP check.
- "Connect your laptop" page with address picker, endpoint rows, credentials
  and copyable curl/terminal commands under Quick test / Linux/macOS / Windows
  tabs; plain-text Windows and Firefox setup steps; Linux/macOS adds a
  SOCKS5 quick test, Chromium/Chrome `--proxy-server`, and GNOME
  gsettings.
- Quick Settings tile to start/stop the proxy from the shade; session
  log (last 500 closed connections, metadata only) with JSON export;
  optional "Start on boot".
- In-app session log screen (Activity tab > History): search, "Blocked only"
  filter, tap a host to copy it, long-press a hostname to block it.
- Engine diagnostics log (Activity tab > Engine): last 1000 lines,
  level filters, search, Follow toggle, repeat counts (×N),
  refreshed once a second only while visible, shareable with
  credentials redacted; optional idle auto-stop (15 min / 30 min /
  1 h / 2 h) in "Battery & startup".
- Client control: disconnect one client, or block it until the proxy
  restarts (Connections card menu); per-direction speed limits
  (Settings > Limits), shown as a dashed line on the traffic chart.
- VPN watchdog: a red Home banner and one notification when the VPN
  drops while clients are connected; optional "Block traffic when
  VPN is down" (Default mode) refuses new connections until the VPN
  is back. Client names (Connections card menu > Rename) and per-day
  usage history (Activity tab > Usage: today, last 7 days, this month).
- Outbound DNS runs on the selected network (API 29+), addresses are
  tried IPv4 first with each attempt capped at 5 s.

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
package `io.github.elli0t0.proxyshare`.

## Versions

See `CHANGELOG.md`. One push = one commit = one CI run. CI builds the debug
and release APKs (release with R8) and uploads both as artifacts; the release
APK size is printed in the job summary. Signing is persistent via
`PROXYSHARE_*` repo secrets (see the workflow) so every update installs over
the previous one.

## Benchmark

`scripts/bench.sh` measures the proxy from a laptop on the same Wi-Fi or
hotspot as the phone (needs curl, proxy running):

```sh
sh scripts/bench.sh PHONE_IP [PORT]
```

It prints 20 sequential new connections through socks5h (connect / TLS /
first-byte timings each) and then a 50 MB download from speed.cloudflare.com
with its average speed. PORT defaults to 1080 (the split-mode SOCKS port).
