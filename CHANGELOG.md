# Changelog

## 0.6.5 — PAC server + Scan-to-set-up QR

- New Settings > Server switch "PAC / setup page" (default off) with a
  port field (default 8899). A tiny web server on the phone serves
  /proxy.pac and a one-page setup site, so a laptop can configure
  itself from a QR code instead of typing host and port.
- Rust: new module pac.rs — config key `pac_port` (0 = off, default
  8899), bound to the same bind_addr as the proxy; same client rules
  as the proxy (allowed_clients / private_only, rejected client closed
  with no reply); no auth on the PAC itself (it only contains ip:port).
  Routes: GET /proxy.pac (application/x-ns-proxy-autoconfig), GET /
  and /setup (small HTML, no JS, no external resources), else 404;
  at most 4 KB of request, 3 s timeout, close after reply. The proxy
  host in the PAC is the address the client connected to
  (stream.local_addr()), so it works on hotspot, Wi-Fi and USB;
  IPv6 literals bracketed. A PAC bind failure must NOT stop the proxy:
  WARN in the diagnostics log + snapshot `pac_ok=false`. The setup
  page has "Auto (PAC)" with the PAC URL, "Manual" with HTTP/SOCKS
  host:port, short steps for Windows, macOS, Linux, Firefox, and says
  the browser will ask for the username and password when auth is on
  (never prints the password).
- Kotlin: new dependency com.google.zxing:core:3.5.3 (the one allowed
  new library); Connect page gains a "Scan to set up" item with the QR
  of `http://<selected host>:<pac_port>/` and the URL as copyable
  text; when PAC is off it shows a button that turns it on
  (saveRestart). QR renders on a white background so it scans on a
  dark theme. Tests: Rust +16 (pac_body x4, route x2, setup page x2,
  pac_ports, http response, live loop x4, oversized request dropped;
  config pac_port parse/summary), Kotlin +5 (qrPixels 512x512 non-blank
  + deterministic + differs per text, pac off sends 0, pac on sends
  the port; backup round trip covers the new fields).

## 0.6.4 — Block private networks

- New Settings > Security switch "Block private networks" (default
  off): proxy clients can no longer reach devices on the phone's own
  network (router admin page, other LAN devices, localhost services).
  Default off because VPN-internal 10.x targets would break otherwise.
- Rust: new config key `block_private` (config.rs, parse + summary)
  and pure `is_private_ip` (10/8, 172.16/12, 192.168/16, loopback,
  link-local, unspecified, broadcast; IPv6 loopback, unspecified,
  unique-local fc00::/7, link-local fe80::/10, IPv4-mapped judged by
  its IPv4 half). The Connector drops private addresses from the
  resolved list after DNS (never on the hostname, so a domain pointing
  at a private address is caught too) and refuses with a new
  `ConnectError::Blocked` when nothing is left.
- Refusal maps like blocked domains: HTTP 403, SOCKS5 0x02, SOCKS4
  0x5B, record marked blocked, logged as a private-target refusal.
- Kotlin: AppSettings.blockPrivate -> toEngineConfig, and in the
  backup JSON. Tests: Rust +6 (is_private_ip table, config
  parse/summary, connector refuses loopback + refuses after DNS +
  allows when off, engine HTTP CONNECT to a private target is 403 and
  marked blocked); Kotlin +2 (blockPrivate omitted by default, sent
  when on), and the backup round trip now covers the new field.

## 0.6.3 — Backup / restore settings as JSON

- New Settings > Backup page: Export writes every setting to a
  `proxyshare-settings.json` file (system file picker), Import reads
  one back. The password and username are never in the file.
- Import validates each field through the existing Rust validation
  (bad domains/clients are dropped, ports and limits are clamped to
  the UI ranges, unknown keys ignored, missing keys keep the current
  values); a bad file shows "Not a ProxyShare settings file" and
  changes nothing. On success it shows "Settings imported" and
  restarts the engine if it is running (saveRestart path).
- The import applies in one DataStore edit (SettingsRepository
  replaceAll, which never touches the auth secrets). Pure
  toBackupJson / settingsFromBackupJson in SettingsLogic, unit-tested:
  round trip, secrets absent, garbage fails, ports clamped, missing
  keys keep defaults.

## 0.6.2 — Health checks on Home

- New Health card on Home: one row says "Everything looks fine"
  or "N issues"; tapping it expands the six checks in place
  (notifications, battery, VPN, shareable address, port listening,
  password), each with a Fix button when it fails.
- The checks are a pure function, computeHealth(HealthInputs),
  unit-tested: all-good, each single failure, stable order.
  The engine error item carries the real error message (a new
  `message` field on HealthItem), and a stopped proxy is a warning,
  not a failure.
- Fixes open the right place: notification settings, battery
  optimization settings, hotspot settings, or Settings > Security
  for an empty password (AppRoot passes the requested sub-page to
  SettingsScreen). A missing VPN is a warning with no fix.
- The two Android facts (notifications, battery) are re-read on
  resume only (LifecycleEventEffect), never polled; engine,
  settings, VPN and address changes refresh the list reactively.

## 0.6.1 — One Activity tab instead of Connections + Logs

- Bottom bar is now 3 tabs: Home, Activity, Settings. Activity merges
  the old Connections and Logs tabs into one segmented row:
  Live | History | Engine | Usage (Live is the old Connections
  screen; the rest are the old Logs tabs).
- The selected segment is remembered across rotations. The "Session
  log" action in the Live menu now switches Activity to History.
- The status bar is still counted once: ActivityScreen adds
  statusBarsPadding and the old screens get topInset = false.
- The old Logs ⋮ menu (share/clear log) moved beside the segments,
  shown on History and Engine only; Live keeps its own menu, Usage
  keeps its Reset button. No behaviour changed: search, "Blocked
  only" filter, long-press to block, Follow toggle, and the 1 s
  refresh loops, which stop when their segment is not visible.
- Removed the now-unused LogsScreen wrapper and the nav_logs /
  logs_history / logs_engine / logs_usage strings.

## 0.6.0 — Settings as a short list + sub-pages

- Settings is now a short list of 5 rows (icon, title, one-line live
  summary), each opening a sub-page: Server, Security, Limits, Battery &
  startup, Appearance. Pure refactor: same settings, same behaviour,
  same saveRestart/saveLive logic, same dialogs.
- Navigation is plain state, same style as AppRoot: a SettingsPage enum
  (SERVER, SECURITY, LIMITS, BATTERY, APPEARANCE) + BackHandler; the
  detail page is back arrow + title + the same cards. Backup and Rules
  will add rows to the enum and the list loop (0.6.3, 0.6.7).
- Each SectionCard body moved into its own private composable
  (ServerSettings, ProtocolsSettings, SecuritySettings, LimitsSettings,
  BatterySettings, AppearanceSettings, AboutSettings) without changing
  its logic. Protocols lives on the Server page; About stays on the
  list, below the rows. The restart and open-proxy banners show on the
  list and on every detail page.
- Summaries are pure functions in SettingsLogic.kt, unit-tested:
  serverSummary ("HTTP 8080 · SOCKS 1080", or "Port 8080" in
  single-port mode), securitySummary ("Password on · private only"),
  limitsSummary ("Unlimited" or "↓ 2 MB/s ↑ 1 MB/s"),
  batterySummary ("Saver on · Boot off"), appearanceSummary
  ("System theme"). The status-bar inset rule is unchanged: the
  Scaffold passes no insets, each page adds statusBarsPadding once.
- Tests: Kotlin 142 → 150 (8 new summary tests), Rust 170.

## 0.5.9 — Simpler Home

- Home is now four things, top to bottom: the red VPN-down banner (moved
  above the power card, shown only when needed), the power card
  (unchanged), one address card, and a compact outbound card. The screen
  title, the live stat tiles, the open-warning banner, the protocol /
  password status chip and the standalone laptop button are gone — the
  stat tiles duplicated Connections, and the open-warning text still
  shows in Settings.
- Address card: the primary address as one large monospace "ip:port"
  (advertiseAddress: HTTP port first, SOCKS port as fallback) with a
  Copy button, a "Connect a device" button opening the Connect page, and
  a "More addresses (N)" TextButton expanding to the other addresses.
  Mobile-only still shows the hotspot hint + button instead of an
  address. New pure helper otherAddresses (same port choice, mobile
  skipped) + 3 tests. Home no longer collects the per-second snapshot —
  the stat tiles were its only use.
- Outbound card: one FlowRow of 4 FilterChips (Default, VPN only, Wi-Fi,
  Mobile), a "Check exit IP" TextButton with the result as one plain
  text line (red on failure), and a collapsed "More options" section at
  the bottom holding the "Block traffic when VPN is down" switch (Default
  mode only) and the check-URL field.
- Deleted: ProtocolAddressRow, kindLabel, CopyChip, and 12 unused
  strings (stat_devices, other_addresses, mobile_unreachable,
  outbound_only, check_exit_ip_failed, if_wifi/hotspot/ethernet/usb/
  mobile/vpn/other). PowerButton untouched.
- Deviations from the prompt: the primary copy uses a "Copy" TextButton,
  not a Copy icon — Icons.Filled.ContentCopy is extended-only and not in
  the pinned core set (same as 0.5.8). The exit-IP URL field was kept
  inside "More options": it is editable nowhere else, and removing it
  would have orphaned the setting.
- Tests: Kotlin 139 → 142 (3 new otherAddresses tests), Rust 170.

## 0.5.8 — Connect page replaces the laptop sheet

- The laggy "Connect your laptop" ModalBottomSheet is gone: it started
  half-open, rebuilt its own network flow every 15 s, and a recomposition
  mid-drag cancelled the gesture. It is replaced by a full-screen Connect
  page (ConnectScreen.kt, renamed from LaptopSheet.kt) with a normal
  LazyColumn fed by the shared NetworkWatcher.interfaces flow, everything
  remember()ed, and no per-second collection.
- Connect page, top to bottom: status line ("Proxy running" / "Start the
  proxy first, then run these on your laptop."), address chips (FlowRow of
  FilterChip, "ip (name)"; one address is plain text; mobile-only shows the
  hotspot hint + "Open hotspot settings"), endpoint rows "HTTP ip:8080" /
  "SOCKS5 ip:1080" with Copy buttons, credentials rows when auth is on
  (user + Copy, password with Show/Hide + Copy — this replaces the
  Credentials card on Home), and a Quick test | Linux/macOS | Windows
  segmented row. Commands render per tab (key = title); Windows shows the
  plain-text steps. Back arrow + system Back close the page; AppRoot shows
  it instead of the Scaffold (no bottom bar).
- Home: the button now opens the page via onOpenConnect from AppRoot; no
  sheet state left in HomeScreen. CredentialsCard, its private copyText and
  the now-unused credentials_title / password_hint strings are deleted.
  (user_label stays — the Connect page uses it.) PowerButton untouched.
- Deviations from the prompt: the endpoint rows use a "Copy" TextButton,
  not a Copy icon — Icons.Filled.ContentCopy is extended-only and not in
  the pinned core set (same pattern as CommandCard). FlowRow needs
  @OptIn(ExperimentalLayoutApi::class) in the pinned Compose 1.7.
- Tests: laptopCommands/resolveSheetHost tests unchanged; new tests prove
  every Cmd title is unique across the http/socks variants and that
  commandsForTab splits Quick test (both curl commands) / Linux-macOS
  setup / Windows (none). Tests: Kotlin 137 → 139, Rust 170.
- Note: the rename took two commits — push_files cannot delete, so the old
  LaptopSheet.kt was removed in a separate delete commit right before this
  push; the version is one CI run (the delete's run was cancelled by it).

## 0.5.7 — fix Logs crash on duplicate LazyColumn keys

- Bug: scrolling Logs → History (and the Engine tab) crashed with
  `IllegalArgumentException: Key ... was already used` when two
  entries were identical (same ms, same host, 0 bytes; or repeated
  engine lines), because both LazyColumns keyed rows by concatenated
  content.
- Fix: `SessionLogEntry` (ConnectionsLogic.kt) and `DiagLine`
  (engine/Engine.kt) each gained `val id: Long`, assigned at creation
  from a process-wide `LogIds` AtomicLong counter, and both
  LazyColumns now use `key = { it.id }`. The id is a constructor
  property with a default, so `copy()` — the merge path — keeps the
  old id and existing call sites still compile. (Your sample put it
  in the class body, but then a merged line's `copy()` would get a
  fresh id; the constructor placement is what makes the merge test
  pass. Existing `==` tests were updated instead — see below.)
- Tests: 3 identical `SessionLogEntry` objects and 3 identical
  `DiagLine` objects each produce 3 distinct ids; a merged
  `DiagLine.copy(count = 2)` keeps the first id. The three
  `parsesDiagLogLines` `assertEquals` now pass the parsed line's id
  into the expected object. Tests: Kotlin 134 → 137, Rust 170.
- Sweep (item 6): the only other `key = {` in ui/ is the clients
  list (`key = { it.client }`), already unique — groups are built
  by `groupByClient`, one group per client. Blocked domains and
  allowed clients render with plain `for` loops in a Column, no
  LazyColumn keys at all. Nothing else to change.

## 0.5.6 — small UI cleanup

- Connections: when the proxy is stopped the dimmed "Live traffic"
  chart card with the "Proxy stopped" overlay is gone entirely — only
  the centered "Proxy stopped — start it from Home" message and the
  "Go to Home" button remain. The Active / Session up / Session down
  stat cards stay, and nothing changes while running.
- Logs: the first tab is now "History" (new `logs_history` string);
  the bottom bar still says "Connections". Empty text unchanged.
- Home: the separate "No password · private networks only" chip and
  the protocol chips are now one chip, e.g. "SOCKS5 · No password ·
  private networks only" — protocol parts (HTTP / SOCKS4 / SOCKS5),
  password state and private-only are joined with " · ", and the chip
  fills the width so long text wraps instead of shrinking. The
  Credentials card when auth is on is unchanged; the now-unused
  `ProtocolChip` and the `no_password_private` string are deleted.
  (`proxy_stopped` stays: Home's hero status text still uses it.)
- No behavior or setting changes. No test referenced the old texts.

## 0.5.5 — package renamed to io.github.elli0t0.proxyshare

- Pure rename, no behavior change: the Kotlin package (and the app
  id) is now `io.github.elli0t0.proxyshare` instead of
  `com.example.proxyshare` — namespace and `applicationId` in
  `app/build.gradle.kts`, every Kotlin file moved to
  `java/io/github/elli0t0/proxyshare/...` with its `package` and
  `import` lines updated, all 18 JNI exports in `rust/engine/src/lib.rs`
  renamed to `Java_io_github_elli0t0_proxyshare_engine_NativeEngine_*`,
  the `EngineListener` class path in `rust/engine/src/listener.rs`
  updated, the ProGuard keep rule, README and `docs/ENGINE_API.md`
  updated. `AndroidManifest.xml` needed no change (all relative
  names, FileProvider authority is `${applicationId}`).
- **This build installs as a NEW app**: the old
  `com.example.proxyshare` install must be uninstalled manually, and
  settings do not carry over. A unit test asserts
  `BuildConfig.APPLICATION_ID == "io.github.elli0t0.proxyshare"`.

## 0.5.3 — Wi-Fi lock while clients are connected

- ProxyService now holds a `WifiManager.WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`
  on API 29+, `WIFI_MODE_FULL_HIGH_PERF` below) alongside the CPU wake
  lock, so a phone acting as a Wi-Fi client (not a hotspot) does not
  power-save the radio with the screen off. Acquired/released in the
  same places as the CPU lock, so Battery saver still applies: released
  60 s after the last client disconnects, re-acquired on the first
  client; always released in `onDestroy`. No new permission (`WAKE_LOCK`
  is enough) and no new setting or UI. Acquire/release are wrapped in
  `runCatching`, so a missing `WifiManager` or Wi-Fi being off can
  never crash the service. The hold decision is the pure function
  `shouldHoldServiceLocks(clientCount, batterySaver)` (held when
  `!batterySaver || clientCount > 0`), covered by
  `ServiceLocksTest` (3 tests).

## 0.5.2 — single validation source + robustness tests

- Validate once, in Rust: new `nativeValidate(kind, value)` JNI call
  (`rust/engine/src/validate.rs`, kind 0 = CIDR/IP, 1 = domain
  pattern, 2 = port) returns `""` when valid or a stable error code
  (`empty`, `has_scheme`, `has_path`, `bad_cidr`, `bad_port`,
  `bad_domain`, `bad_kind`). Kotlin keeps only the error-code ->
  string-resource map; the duplicate Kotlin validators and their
  tests are deleted, their cases ported to Rust (including
  `*.example.com`, uppercase hosts, a trailing dot, IPv6 CIDR, port
  0 and 65536). The CIDR validator now accepts IPv6 (the Rust
  `Cidr::parse` is the authority; the old Kotlin one only knew
  IPv4). Bulk "Paste list" validates each line through the same
  call, one JNI call per line.
- Parser robustness tests (Rust): table-driven garbage tests for the
  SOCKS5, SOCKS4 and HTTP request parsers — truncated handshakes at
  every byte boundary, oversized host length, invalid UTF-8 host,
  version byte 0, a CONNECT with no port, header lines over 8 KB —
  each finishing within 2 s, never panicking; plus an engine test
  that garbage connections close promptly and the engine keeps
  accepting afterwards. A 9 KB header line inside the 16 KB total
  head cap still reads (no per-line cap); a head over 16 KB fails.
- Connection-limit test: opening max_connections + 50 raw sockets
  counts the extra ones in `rejected_limit` and new connections
  work again once the held ones close.
- Logs tab icon is now the list-style `Menu` icon from
  material-icons-core (the `i` read like "About"); `List` was
  already taken by the Connections tab, and no document icon exists
  in core. `scripts/bench.sh` stays where it is: README references
  it, so the move condition did not hold.
- Tests: Rust 151 -> 170 (validate cases, SOCKS5/SOCKS4/HTTP
  garbage tables, engine garbage + max-connections); Kotlin 133
  -> 130 (3 validator tests deleted, paste test kept with a stub
  validator).

## 0.5.1 — the engine owns the stats

- Rust now owns every live stat, so Kotlin holds no timers,
  refcounts or samplers and no JNI upcall fires per connection:
  per connection the callback went from 2 upcalls (open + close)
  to 0 — only the Busy / Idle / DevicesChanged edge events, plus
  the existing 1 s snapshot/history poll while the UI is visible.
  No periodic timer runs while there is no traffic.
- Engine-wide live byte counters (`live_up` / `live_down`,
  monotonic, including open connections) in the snapshot; the
  Kotlin `totalUp + Σ conn.up` hack is gone. Distinct-client
  refcount in the engine, exposed as `devices` in the snapshot.
  Speed history in the engine: a sampler starts on the idle ->
  busy edge, ticks once a second (EMA alpha 0.5, snapped to 0
  under 64 B/s), keeps a 120 s ring, stops after 2 empty ticks,
  and `nativeHistory` returns the last 120 s gap-filled with
  zeros, so the chart is full even right after returning to the
  app. The snapshot also carries the latest sample
  (`speed_up` / `speed_down`).
- Session log in the engine: a ring of the last 500 closed
  connections with monotonic ids; `nativeSessionLog(sinceId)`
  returns only newer records, `nativeSessionLogClear` clears it.
- Edge events only: Busy (10) / Idle (11) / DevicesChanged (12)
  fire on the edge; ConnectionOpened / ConnectionClosed are no
  longer sent through JNI (the Rust variants and tests stay).
- Idle auto-stop moved into the engine: config `idle_stop_secs`
  (0 = off); on the Idle edge a sleep starts, cancelled by Busy,
  and on expiry the engine emits IdleStop (13) and Kotlin stops
  the proxy. Settings "Stop automatically when idle" now just
  writes that key. The 60 s wake-lock grace stays in Kotlin,
  driven by the Busy / Idle edges.
- Removed Kotlin code: `DeviceCounter`, the `activeConns`
  refcount/resync, `rebaseline` / `SamplerState` / `smoothSpeed` /
  `startSpeedSampler`, and the session-log-from-events collector
  (all in `ProxyController` / `Engine.kt`). The notification now
  reads "1 device · SOCKS :1080" (connection count dropped).
- Tests: Rust 132 -> 151 (history ring + smoothing, session log,
  devices, edge events, sampler and idle-stop under paused time);
  Kotlin 140 -> 133 (speed/history tests moved to Rust, the
  deleted-code tests removed).

## 0.5.0 — engine robustness

- Connection limit: default 64 -> 256 (engine config and Settings).
  At start the engine raises the soft RLIMIT_NOFILE to min(hard,
  65536) and caps the effective limit by the fd budget: each
  proxied connection holds 2 fds, so effective_max =
  min(config.max_connections, (nofile_soft - 128) / 2), never below
  1. The resulting limit is logged at INFO. Expected: pages no
  longer stall past 64 browser connections.
- Never drop silently: refused connections are counted per reason
  (limit / denied client / client outside allowed_clients) and the
  snapshot carries the three totals as trailing fields. Each reason
  logs at most once per 5 s: WARN "connection limit reached: N
  rejected in the last 5 s (max M)". Connections shows "Rejected: N"
  under the Peak line when > 0; tapping it explains "connection
  limit reached — raise Max connections in Settings". Settings >
  Limits "Max connections" now allows 1..2000 with the helper text
  "Each connection uses 2 file descriptors".
- Abort-handle race fixed: the handle slot is reserved BEFORE the
  connection task is spawned and filled afterwards, so a task that
  finishes fast can no longer leave a stale entry behind (close_client
  aborts only the stored handles).
- JNI events: worker threads attach permanently
  (attach_current_thread_permanently) instead of attaching and
  detaching on every event. Best-effort, never panics.
- Handshake timeout verified (no change needed): the sniffer waits
  5 s for the first byte, and SOCKS5 negotiation, SOCKS4 request
  and the HTTP head each have a 10 s read timeout, so a silent
  client can never hold a slot until the 300 s idle timeout.
- Tests: Kotlin 138 -> 140 (rejected-count parsing), Rust 125 -> 132
  (effective_max, snapshot fields, handle race, limit/denied/
  disallowed rejections).

## 0.4.7 — bottom navigation order

- Bottom navigation swapped: Logs now sits in the middle (Home,
  Connections, Logs, Settings) and Settings moves to the bottom
  right. The Connections menu's "View session log" jump follows the
  new Logs position. No behavior or engine change; ENGINE_VERSION
  bumped to 0.4.7 to stay equal to the app version.
- Tests unchanged: Kotlin 138, Rust 125.

## 0.4.6 — VPN watchdog, client names, usage history

- VPN watchdog: a new VpnWatcher (data/VpnWatcher.kt) exposes a
  shared Flow<Boolean> for "a VPN network is active", collected
  only while the service runs or the UI is visible (WhileSubscribed
  5 s). While the proxy runs in Default or VPN-only mode with no VPN
  active, Home shows a red banner ("VPN is not connected — laptop
  traffic may bypass it", or "…sharing is paused" in VPN-only
  mode), and the service posts one notification when the VPN drops
  while clients are connected (separate channel, default
  importance). New setting "Block traffic when VPN is down"
  (default OFF, Default mode only, in Home's Outbound card): while
  on and no VPN is active, the engine refuses new connections with
  SOCKS5 0x02 / SOCKS4 0x5B / HTTP 403 and closes nothing existing.
  Plumbing: engine config `require_vpn` plus a runtime JNI setter
  nativeSetVpnActive (the live VPN state is pushed by
  ProxyController while running; the flag defaults to fail-closed).
- Client names: the Connections client card ⋮ menu has "Rename".
  Names (ip -> name, max 50, trimmed to 24 chars) live in
  SettingsRepository as a JSON string. A named client shows its
  name as the card title (IP stays on the secondary line) and in
  the session log rows; with exactly one device connected, the
  notification shows its name instead of "1 device".
- Usage history: per-day up/down totals are persisted in DataStore
  as a JSON map "yyyy-MM-dd" -> [up, down] (35 days kept), flushed
  at engine stop, service destroy, and when the UI goes to
  background while running — never polled. Logs gets a third tab
  "Usage": today, last 7 days as a Canvas bar chart (up/down bars
  per day, forced LTR), this month's total, and a "Reset usage"
  button with a confirm dialog.
- Tests: Kotlin 122 -> 138 (client names, usage history, require_vpn
  config). Rust 122 -> 125 (require_vpn refusal: SOCKS5 0x02,
  HTTP 403, plus the VPN toggle).

## 0.4.5 — engine performance and IPv6 fail-fast

- TCP_NODELAY on the client side too: the accept loop sets it on
  every accepted client socket (the outbound side already had it).
  Expected: lower latency on interactive traffic (less Nagle delay
  on small request/response exchanges).
- IPv6 fail-fast circuit breaker (rust/engine/src/connector.rs):
  a failed or timed-out IPv6 attempt trips the breaker for 60 s; a
  successful one clears it. While down, IPv6 addresses are skipped
  when IPv4 ones exist, and an IPv6-only/literal target is refused
  immediately with a new ConnectError::Unreachable, mapped to
  SOCKS5 0x03 (network unreachable) and HTTP 502, so clients fall
  back to IPv4 at once instead of waiting out the 5 s cap per
  attempt. The breaker resets when the outbound network handle
  changes. Expected: pages that resolve to dead IPv6 addresses stop
  stalling for 5 s per connection on an IPv4-only VPN.
- DNS cache (connector.rs): resolved addresses are cached per
  (network handle, lowercase host, port) — 60 s positive TTL, 5 s
  negative TTL, max 512 entries (expired entries dropped first,
  then the whole map is cleared). Flushed on engine start and on
  network change; IP literals are never cached. Expected: fewer
  repeated lookups on pages that open many connections to the same
  hosts.
- Adaptive pump buffer (rust/engine/src/relay.rs): each pump starts
  at 8 KB and doubles up to 64 KB whenever a read fills the buffer,
  instead of allocating 32 KB per direction up front. Expected:
  less memory for the common case (idle or tiny connections) with
  the same throughput for large transfers.
- Staggered connect attempts (RFC 8305, simplified): when a
  hostname resolves to several addresses, the next attempt starts
  300 ms after the previous one when nothing has connected yet;
  first success wins, the losers are dropped. The 5 s cap per
  attempt and the IPv4-first ordering are unchanged. Expected: a
  dead first address no longer blocks the whole connect — the live
  one starts ~300 ms in.
- Benchmark script scripts/bench.sh (PHONE_IP [PORT]): 20
  sequential new connections through socks5h with connect / TLS /
  first-byte timings, then a 50 MB download from
  speed.cloudflare.com with its average speed. Run it as
  `sh scripts/bench.sh PHONE_IP [PORT]`. No Kotlin changes; no
  new dependencies.
- Tests: Rust 111 -> 122 (IPv6 breaker filter + deadline, DNS
  cache hit/expiry/negative/flush/size cap, breaker-refuses-
  IPv6-only + staggered-attempts integration, pump buffer growth).
  Kotlin 122 (unchanged).

## 0.4.4 — Logs tab + bug fixes

- Logs is now its own screen (4th bottom tab): the session log
  moved out of the Connections ⋮ menu (the menu item now switches
  to the Logs tab) and sits next to the engine log under one
  OptionRow ("Connections" | "Engine"), with a ⋮ menu (Share,
  Clear) that acts on the shown tab. The Diagnostics card is gone
  from Settings entirely.
- Engine log tab: HH:mm:ss timestamps (java.time), one row per
  line with a level badge (INFO neutral, WARN amber, ERROR red),
  monospace 12sp message, and "×N" when a line repeated. Filter
  chips (All / Warn+ / Error), a search field, and a "Follow"
  toggle that pins the view to the newest visible line. Refreshes
  once a second ONLY while the screen is visible
  (repeatOnLifecycle STARTED) — nothing polls in the background.
  Share uses the existing redaction; Clear calls a new JNI
  nativeDiagClear(). New dependency: androidx lifecycle-runtime-ktx
  2.8.7 (same version as the rest of lifecycle; repeatOnLifecycle
  lives there and lifecycle-runtime-compose does not bring it in —
  a small addition, R8 strips the unused parts).
- Engine diagnostics (rust/engine/src/diag.rs): ring buffer 300 ->
  1000 lines; repeats collapse — a line with the same level and
  message within 60 s bumps the last line's count and timestamp
  instead of pushing (DiagLine.count). Wire format is now
  `ts_ms<TAB>LEVEL<TAB>message<TAB>count`; the count field is
  optional on the Kotlin side, so lines written before 0.4.4 still
  parse (as count 1).
- Client card ⋮ menu: the button and its DropdownMenu now share
  one Box, so the menu opens under the button instead of at the
  card's left edge.
- Pluralization: `connections_count` is a plurals resource now
  ("1 connection" / "N connections"). Grepped the other "%d" strings:
  devices/clients/connections notifications were already plurals,
  "Blocked: N" and "N added, M invalid" carry no noun, ports are
  numbers — no other fix needed.
- "Connect your laptop" sheet: when the only non-VPN address is
  the carrier (mobile) one, it shows "Turn on the hotspot or join
  the same Wi-Fi as your laptop" + the "Open hotspot settings"
  button, and the commands render with the placeholder PHONE_IP
  instead of the carrier address. With exactly one address the
  "Use this address" radio group is hidden and the address is just
  shown. The duplicate Firefox text section is deleted (the Firefox
  command card stays).
- Tests: Kotlin 115 -> 122 (engine-log filter + timestamp format,
  sheet address selection). Rust 109 -> 111 (repeat collapsing).

## 0.4.3 — client control + speed limit

- Disconnect / block one client: the engine keeps an abort handle per
  connection task (keyed by the pre-assigned connection id) and
  `close_client(ip)` aborts all of an IP's connections, including
  ones still sniffing; it removes the records, folds the counters
  into the totals and emits ConnectionClosed for each, so the UI's
  counts and session log stay right. A runtime-only `denied_clients`
  set (never persisted, cleared by stop()) is checked on accept
  before allowed_clients and the connection limit; a denied client
  is closed without a reply or an event. JNI: nativeCloseClient,
  nativeDenyClient, nativeAllowClient (documented in
  docs/ENGINE_API.md). ClientCard overflow menu: "Disconnect"
  (close now, the client can reconnect) and "Block until proxy
  restarts" (deny + close, with a confirm dialog); a blocked client
  shows a "Blocked" badge with an "Unblock" action.
- Speed limit (token bucket): config keys `limit_down_kbps` /
  `limit_up_kbps` (0 = unlimited), applied to the TOTAL of all
  connections in one direction — one shared `RateLimiter` per
  direction in relay.rs, taken after each read and before the write
  in pump(). The bucket clock is tokio's Instant, not std's: the
  two agree in production, but only tokio's moves under
  tokio::time::pause, which the unit test uses to prove 1 MB at
  512 KB/s takes about 2 s without waiting. Settings > Limits has
  two numeric fields ("Download limit (KB/s)", "Upload limit
  (KB/s)", 0 = off, validated 0..1,000,000, restart banner as
  usual); the Connections chart shows a "Limited" chip when a limit
  is active and draws the limit as a dashed horizontal line (the
  download limit when set, else the upload limit). UDP ASSOCIATE
  datagrams are NOT limited — they never go through the relay pumps
  (same carve-out as the blocked-domains check).
- Tests: Kotlin 109 -> 115 (denied-clients state, limits sent /
  omitted, chart ceiling + limit line position). Rust 102 -> 109
  (token bucket timing, close/deny/allow incl. deny leaves existing
  connections open and stop clears the deny set, limits parse +
  summary).

## 0.4.2 — in-app session log, diagnostics, idle auto-stop

- Session log screen (Connections menu > "Session log"): the last 500
  closed connections, newest first — host:port, protocol, client,
  bytes up/down, duration, and a "Blocked" badge. Search filters
  host/client; a "Blocked only" chip; Export JSON and Clear in the
  menu. Tapping a row's host copies it; long-pressing a hostname
  offers "Block this domain" (same validator as the list; IP literals
  are not offered, the engine never matches them). The restart
  banner flag moved to ProxyController so the block action sets the
  same banner Settings shows.
- Engine diagnostics log: the Rust engine had no log lines at all, so
  a ring buffer of the last 300 lines was added (rust/engine/src/diag.rs,
  `ts_ms<TAB>LEVEL<TAB>message`) with a `nativeDiagLog` JNI getter
  (documented in docs/ENGINE_API.md). Logged: engine start/stop with
  a config summary (never the password or the username), bind errors,
  accept errors, upstream connect failures (host, reason), blocked
  requests, auth failures (client IP only). Settings > Diagnostics
  shows it monospace, selectable, auto-scrolled to the bottom, with
  Refresh and Share (ShareCompat text/plain; `redactLog` strips
  key=value secrets, userinfo in URLs and Proxy-Authorization
  headers before sharing).
- Idle auto-stop: Settings > "Battery & startup" > "Stop automatically
  when idle" — Off (default) / 15 min / 30 min / 1 h / 2 h (radio list
  in a dialog; OptionRow does not fit 5 options). ProxyService stops
  the engine and the service when activeConns stays 0 for the chosen
  time and posts "ProxyShare stopped (idle)"; the countdown cancels
  the moment a connection opens.
- Small things: the Connections empty state (running, 0 clients) now
  shows the exact address to type on the laptop with a Copy button
  instead of only the generic hint; when the only address is the
  carrier one, the "Open hotspot settings" button stays visible on
  Home too.
- Tests: Kotlin 103 -> 109 (diag log parsing + redaction, idle-stop
  setting + label, session-log filter; the 0.4.1 figure of 105 was 2
  high — recounting the test files gives 103 before this step). Rust
  97 -> 102 (ring buffer order, 300-line cap, message sanitization,
  config summary never includes credentials).

## 0.4.1 — bugs and visual fixes

- The app is English-only now: res/values-fa/ is deleted, and no
  translations are added anywhere.
- "Clients" counted connections, not devices: a new DeviceCounter
  refcounts distinct client IPs from the engine events (type 2 open /
  type 3 close), cleared on start/stop and resynced from the snapshot
  when the UI becomes visible. The Home tile is now "Devices", and
  the notification reads "1 device · 45 connections · SOCKS :1080"
  (the connections part is dropped when 0).
- Status-bar collision fixed: AppRoot wraps the Scaffold in a Box
  with a solid surface cover over the status-bar area, so scrolled
  content no longer overlaps the clock/icons (all three tabs, light
  and dark).
- One shared OptionRow component (ui/components/OptionRow.kt)
  replaces all three M3 segmented controls (Home outbound, Settings
  security, Settings theme). The selected segment is filled in
  primaryContainer — the M3 defaults read inverted with dynamic
  color. The old SegmentedButton-colors comment in HomeScreen is
  gone.
- Settings: the Server card is split — Server (ports, listen
  address) and a new "Battery & startup" card (Battery saver,
  Battery optimization, Start on boot). The Exit IP check URL field
  moved next to the Outbound card on Home ("Check URL (optional)",
  supporting text "Empty = built-in list"). When only one protocol
  is enabled its switch is disabled and the card notes "At least one
  protocol must stay on".
- Blocked domains polish: a subtitle explains the list only works
  for clients that send the hostname (SOCKS5 with remote DNS,
  HTTP/HTTPS proxy); the laptop sheet's SOCKS5 curl example now uses
  socks5h:// (the old --socks5-hostname form and the separate quick
  test are folded into the one command) plus a Firefox line about
  "Proxy DNS when using SOCKS v5"; "Paste list" bulk-adds domains
  (one per line, blanks and #-comments ignored, duplicates dropped)
  and reports "N added, M invalid"; Connections shows "Blocked: N"
  under the Peak line when any session-log entry is blocked.
- Tests: Kotlin 102 -> 105 (DeviceCounter refcounts/resync, pasted
  list parsing; laptop commands updated for the socks5h + Firefox
  change). Rust unchanged at 97.

## 0.4.0 — blocked domains

- New engine config `blocked_domains`: exact hosts ("ads.example.com")
  or suffix wildcards ("*.example.com", which also matches example.com
  itself). Case-insensitive, trailing dot ignored, IP literals never
  match. Checked before any outbound connect: HTTP CONNECT / plain
  HTTP get a 403, SOCKS5 gets 0x02 (not allowed by ruleset), SOCKS4
  gets 0x5B. The refused connection is marked blocked and appears in
  the session log.
- Settings > Security has a "Blocked domains" list (same add/remove
  UI as Allowed clients; validation rejects spaces, schemes and
  paths). Stored in SettingsRepository, sent to the engine on start
  (restart banner when changed, like other engine settings).
- Snapshot lines carry a 9th field, "1" when the connection was
  refused for a blocked domain, else "0". The session-log JSON
  export includes `"blocked":true` on those entries.
- Tests: Rust 88 -> 97 (domain_blocked unit tests, config parse,
  snapshot line, 5 engine tests: 403/403/0x02/0x5B + an unblocked IP
  literal still connecting); Kotlin 97 -> 102 (validation, config
  join, parseConnLine flag, session-log flag). UDP ASSOCIATE
  datagrams are NOT filtered (no refusal reply exists for them).

## 0.3.8 — update check

- Settings > About has a manual "Check for updates" button (no
  background polling, no new permission — INTERNET was already
  there). It GETs /repos/Elli0t0/ProxyShare/releases/latest (8 s
  timeout, User-Agent set — GitHub requires it), parses tag_name
  and html_url with org.json, and shows "Checking…", "You're up to
  date (0.3.8)", "0.3.9 is available" with an "Open" button (opens
  the release page), or "Couldn't check" on any failure. The repo
  is now one constant (GITHUB_REPO) shared by the About row and the
  check. isNewer() compares major.minor.patch numerically (0.3.10
  > 0.3.9, equal -> false, garbage -> false). Tests: 5 new
  (isNewer + parseLatestRelease); org.json:json is a test-only
  dependency so the parser test can run on the JVM (android.jar's
  org.json is a stub in unit tests) — zero APK cost.

## 0.3.7 — exit-IP check, engine speed, notification

- Exit-IP check is robust now: it tries the custom URL first (if
  set) then api.ipify.org, icanhazip.com and
  https://1.1.1.1/cdn-cgi/trace (the IP literal works even when DNS
  is filtered), 5 s per endpoint, and only accepts a real IPv4/IPv6
  address from the body (parseExitIp: plain IP or the ip= line of a
  trace body — an HTML error page is never shown as the IP). On
  failure the chip shows a friendly message instead of raw exception
  text: "DNS looks blocked or filtered on this network" (any failure
  mentioned 127.0.0.1 / 0.0.0.0 or UnknownHostException), "No answer
  (timeout)", or "Could not reach any check service". On success the
  network mode shows next to the IP ("1.2.3.4 · VPN only").
  Settings > Server has an "Exit IP check URL" field now (empty =
  built-in list, default changed from api.ipify.org to empty).
- Rust release profile: opt-level "z" -> 3 (throughput matters
  more than a few hundred KB). libproxyshare_engine.so in the
  published APK, before (v0.3.6, opt-level "z"): arm64-v8a 606,776
  B (592.6 KB), armeabi-v7a 433,888 B (423.7 KB), x86_64 652,032 B
  (636.8 KB). After (v0.3.7, opt-level 3): arm64-v8a 729,112 B
  (712.0 KB), armeabi-v7a 537,176 B (524.6 KB), x86_64 780,240 B
  (762.0 KB) — about +20% per ABI, as expected for -O3.
- Notification shows the live client count without polling: it
  rebuilds on activeConns changes only ("2 clients · SOCKS :1080",
  plurals resource en+fa, 0 clients shows the old text) and
  setOnlyAlertOnce(true) so it never buzzes.

## 0.3.6 — visual fixes only

- Double top inset fixed: the Scaffold no longer adds the status
  bar inset (contentWindowInsets = 0; the NavigationBar pads the
  nav-bar inset itself) and each screen keeps exactly one
  statusBarsPadding. Home and Settings reorder it after
  verticalScroll, so content scrolls up under the status bar
  instead of being clipped; all three titles sit at the same y.
- Chart axis label: niceCeiling() now rounds in the display unit
  (KB/MB/GB), so the label reads "5 MB/s" instead of "4.8 MB/s";
  formatBytes drops a trailing ".0" ("5 MB", "500 KB"). Tests
  updated (TrafficChart + formatBytes).
- Outbound segmented buttons: explicit SegmentedButton colors
  (selected keeps secondaryContainer with its border blended in;
  unselected uses outlineVariant/onSurfaceVariant) — the defaults
  read inverted because Theme.kt defines no secondaryContainer or
  outlineVariant of its own and dynamic color makes the selected
  container dim against a near-black surface.
- Addresses card: primary header is one line ("Hotspot · ap0",
  name small and secondary), same for the other-address rows and
  the VPN rows; the "Other addresses" toggle is now a compact
  clickable text and sits 8dp from the VPN rows; protocol chips
  are read-only, so only enabled ones show, each with a leading
  check icon.
- Cleanup: res/drawable/ic_launcher_background.xml was already
  removed in 0.3.5 (nothing references it anymore).

## 0.3.5 — launcher icon rebuilt as a proper adaptive icon

- Launcher icon rebuilt exactly per spec, all vectors, no PNGs:
  background is now a color resource
  (res/values/ic_launcher_background.xml, #5890B0) instead of a
  shape drawable; foreground is the spec's vector verbatim
  (S-route, symbol box x 26.9..81.1, y 38.9..69.8, inside the
  66dp safe zone, no scale/inset/padding); both
  mipmap-anydpi-v26 adaptive-icon files rewritten to reference
  @color/ic_launcher_background + @drawable/ic_launcher_foreground
  with a monochrome layer. Manifest icon/roundIcon were already
  correct. No mipmap PNG/WebP files existed to delete. The old
  drawable/ic_launcher_background.xml shape is now unused and
  will be removed in a cleanup commit. Nothing else changed.

## 0.3.4 — launcher icon: S-route on blue, to spec

- Launcher icon is now the S-route from the reference image:
  flat #5890B0 blue filling the whole canvas edge to edge, no
  text; two white circles joined by a thick smooth S-shaped line
  (bottom arm y=63, top arm y=44, one steep rise between x=42 and
  x=54, circles (33,63) and (75,44), r 6, stroke 5), traced from
  the reference's measured geometry and scaled to 50% of the
  canvas (symbol box x 27..81, y 38..69 — 25%+ empty margin on
  every side, farthest point 28.8 units from center, inside the
  33-unit circle mask). Nothing else changed.

## 0.3.3 — launcher icon rebuilt to spec (small centered share mark)

- Launcher icon rebuilt exactly to the spec: flat #45B5B0 teal
  filling the whole canvas edge to edge, no rounded corners, no
  text; a small white share mark (three circles joined by two thin
  lines) centered in the middle, symbol box x 28..80, y 28..80
  (48% of the canvas, 26% empty teal margin on every side). The
  farthest mark point is 31.6 units from center, inside the 33-unit
  circle mask, so no launcher mask can crop it. Nothing else changed.

## 0.3.2 — new launcher icon (share mark)

- Launcher icon is now the share mark from the new logo (three
  circles joined by two lines, no text). Traced from the logo's
  measured geometry and scaled to fit the 66dp safe zone with margin
  (x 26..82, y 24..84): the 0.3.1 S-route ran to x=89 and the
  launcher mask clipped the right circle. Nothing else changed.

## 0.3.1 — launcher icon redraw

- Launcher icon redrawn from the logo's real geometry (measured off
  the logo file): long horizontal arms, one steep S-rise in the
  middle, big circles at both ends (centers (27,65) and (81,42),
  r 8, stroke 7). The old vector spread the rise across the whole
  width, which read as a wobbly worm. Nothing else changed.

## 0.3.0 — correctness + features

- Connector (Rust): resolved addresses are tried IPv4 first (stable
  sort; a VPN is often IPv4-only) and each connect attempt is capped
  at 5 s, so a dead AAAA can't stall a page load (pure order_addrs /
  attempt_timeout helpers, unit-tested). DNS now uses the outbound
  network too: on API 29+ with a network set, resolution runs
  android_getaddrinfofornetwork (libandroid, dlsym at runtime) inside
  spawn_blocking, converts the addrinfo list and always calls
  freeaddrinfo; network == 0, API < 29, or a missing symbol all fall
  back to lookup_host. Non-Android builds always use lookup_host, so
  host tests pass. Documented in docs/ENGINE_API.md; UDP ASSOCIATE
  sockets are still not bound to the network.
- Quick Settings tile: new ProxyTileService (start/stop from the
  notification shade, state follows the engine). Add it from the tile
  editor. Tapping Start from the shade falls back to opening the app
  when a background foreground-service start is blocked.
- Laptop sheet (Linux/macOS): added `curl --proxy socks5h://…` quick
  test, Chromium/Chrome `--proxy-server="socks5://…"`, and GNOME
  gsettings (mode + socks/http/https host/port, with auth lines when
  enabled). The existing `export http_proxy=… https_proxy=…` line
  already covered the spec's two-export form, so it was not duplicated.
  LaptopCommandsTest updated (6 commands with credentials).
- Session log: the last 500 closed connections are kept in memory
  (host, port, proto, client, bytes up/down, duration) from the
  ConnectionClosed events (type 3 payload parsed with the shared
  parseConnLine). Connections overflow menu: "Export log (JSON)" via
  ShareCompat/FileProvider and "Clear". Privacy note in the UI: the
  log holds only connection metadata, no packet inspection.
- Optional, default OFF: "Start on boot" (Settings > Server, applies
  live) + BOOT_COMPLETED receiver that starts ProxyService only when
  enabled.

## 0.2.9 — battery optimization

- Poll only while visible: ProxyController gains uiVisible (MainActivity
  onStart/onStop); the Engine poll loop suspends on uiVisible.first
  (zero wakeups while hidden) and runs at 1 s everywhere — fastPolling
  and the 250 ms mode are gone. Public suspend pollNow() for the resume
  case. The speed sampler uses the same gating; on return it polls once
  and re-baselines (pure rebaseline helper, unit-tested) so no fake
  spike is reported, and pushes no samples while hidden.
- Wake lock only with clients: activeConns is tracked from engine events
  (type 2 opened, 3 closed, no polling), reset on start/stop, resynced
  from snapshot.active when the UI becomes visible. New "Battery saver"
  setting (default ON, Settings > Server, applies live): ProxyService
  holds the wake lock while a client is connected or saver is off, and
  releases it 60 s after the last client disconnects (collectLatest
  cancels the grace wait when a client connects). Settings also has a
  "Battery optimization" row opening the system page, with a note that
  the foreground service keeps running either way.
- Lifecycle-aware collection: Home, Connections and Settings read
  ProxyController/engine flows with collectAsStateWithLifecycle().
  One shared NetworkWatcher object (stateIn, WhileSubscribed 5 s) feeds
  Home and Connections; the safety re-poll in networkInterfaces() went
  from 5 s to 15 s (the NetworkCallback covers real changes).
- Rust relay buffer 16 KB -> 32 KB (fewer syscalls, better throughput).
- docs/TESTING.md gains the battery manual-test notes (screen off +
  hotspot, idle 5 min, laptop page load; dumpsys power wake-lock check).

## 0.2.8 — UI/bug polish

- Centered hints: the mobile-only hint on Home and the empty-state hints
  on Connections are now centered (textAlign Center + fillMaxWidth).
  The "Turn on the hotspot…" hint appeared twice on Home (hero + Addresses
  card); it now shows once, in the Addresses card only (hero hint removed).
- Mobile-only state: when every ranked interface is mobile, Home hides
  the address rows and copy chips entirely and shows one compact block:
  the hint + an "Open hotspot settings" button (TetherSettings, falling
  back to wireless, then settings). Pure isMobileOnly helper, unit-tested.
- Interface detection (data/NetworkInfo.kt): alias suffixes stripped
  ("tun0:1" -> "tun0"); rows show the kind label ("Mobile") with the raw
  name only as a small secondary caption; listIPv4 drops unusable
  addresses (v4-* interfaces, 192.0.0.0/29, 169.254.0.0/16). Hotspot:
  a wlan* interface that is not the currently connected Wi-Fi interface
  is HOTSPOT (connectedWifiIface via allNetworks, @Suppress DEPRECATION).
  New IfKind.USB for rndis*/usb* ("USB tethering"); ranking is now
  HOTSPOT, USB, WIFI, ETHERNET, OTHER, MOBILE. NetworkInfoTest updated.
- Connections cleanup: screen title "Connections" (titleMedium, same as
  Home); Settings got its title too ("Settings"). Header tiles are now
  Active | Session ↑ | Session ↓ (liveUp/liveDown totals, animate=false);
  the old "Up: · Down: · Active:" totals line is gone — speeds appear
  only in the chart legend. A "Peak ↑ x/s · ↓ y/s" line sits under the
  chart. When stopped: chart dimmed (alpha 0.4) with a "Proxy stopped"
  overlay, and the radar empty state is replaced by "Proxy stopped —
  start it from Home" plus a button that switches to the Home tab.
  Chart max label now sits just above the top grid line.
- Security: android:allowBackup="false" in the manifest (the proxy
  password lives in DataStore). No dataExtractionRules added — lint
  does not run in CI, and the flag alone covers the backup case.

## 0.2.7 — live traffic graph on Connections

- Traffic chart: new ui/components/TrafficChart.kt (Canvas only, no chart
  library) between the header tiles and the client list. Upload line in
  tertiary, download in primary, 2dp round strokes, cubic-smoothed
  curves, soft gradient fill under each line (alpha 0.15).
- Data: ProxyController keeps a rolling history of the last 120
  SpeedSample (2 minutes at 1 Hz) as StateFlow<List<SpeedSample>>,
  cleared on every start so it survives tab switches and rotation.
  Pure helper pushSample(history, sample, max) caps at 120, order
  preserved (unit-tested).
- Y axis auto-scales to a nice ceiling (1-2-5-10 x 10^k, e.g. 10, 50,
  100, 500 KB/s, 1, 5 MB/s); it grows instantly and shrinks 10% per
  tick so the chart doesn't jump. 3 faint grid lines, max label
  top-left. Time runs left to right (LTR forced, so RTL doesn't mirror
  it). Idle (fewer than 2 samples or all zero) draws flat lines at the
  bottom, not an empty box.
- Legend row above the chart: colored dot + Upload/Download + current
  speed (tabular digits, no AnimatedContent). Chart has a content
  description; paths are remembered per sample list (no per-frame
  allocations beyond the path). "Upload"/"Download" reuse the existing
  stat_upload/stat_download strings; new traffic_title and
  traffic_chart_desc strings in en + fa.

## 0.2.6 — fix releases, speed flicker, counters and misleading hint

- Releases: the workflow now publishes real releases (prerelease: false,
  make_latest: true). Old prereleases were left as-is at the user's
  request. (Workflow edit landed as commit f9d7403; run #61 green.)
- Real totals: Snapshot gains liveUp/liveDown (closed totals + active
  connections' bytes, monotonic). Home tiles and the Connections totals
  line use them. The native counters are cumulative across restarts, so
  Engine keeps a start baseline and the totals display resets each
  session (sessionTotals, unit-tested).
- Stable speed: ProxyController samples the live totals once per second
  with delay() + SystemClock.elapsedRealtime() and exposes
  StateFlow<SpeedSample>; EMA alpha 0.5, snaps to exactly 0 on a tick
  with no new bytes, resets to 0 when stopped. It keeps ticking while
  idle (the old LaunchedEffect(snapshot) never reran). The header tiles
  read it; per-connection rows keep their own computeSpeeds.
- No flicker: StatTile gains animate = false (speed tiles use it, plain
  Text, no AnimatedContent); changing numbers use tabular digits
  ("tnum"), maxLines = 1, softWrap = false; the three header tiles
  share one height (IntrinsicSize.Min + fillMaxHeight); labels
  shortened to "Active", "Upload", "Download" (fa too).
- Misleading hint fixed: a mobile carrier address is never advertised
  — Connections empty state and the Home hero show the mobile-only hint
  instead of the unreachable address (advertiseAddress, unit-tested).

## 0.2.5 — Connections + Settings polish, shared components, new launcher icon

- New shared components in ui/components: SectionCard, StatTile, InfoChip,
  SettingRow, CopyChip — Home, Connections and Settings now share the same
  20dp cards, 16dp padding and 12dp gaps. StatTile and SectionCard moved out
  of Home/Settings; the private ProtoChip, SwitchRow and copy buttons are
  gone. No behavior or setting-key changes.
- Connections: header is three StatTiles (Active, Upload speed, Download
  speed) plus the totals line; client cards have a round avatar with the
  last octet of the IP, client IP, total traffic and connection count;
  expanded rows show the host in bold, a protocol chip, live up/down
  speeds and duration. Empty state is a Canvas radar drawing with "No
  clients yet" and the hint "Set your laptop's proxy to <primary address>".
- Settings: rows use SettingRow with short subtitles; sections in order
  Server, Protocols, Security, Limits, Appearance, About. About shows the
  app version, the engine version and the GitHub repository text. The
  engine version line moved here from Home.
- New launcher icon: adaptive icon drawn from the logo the user sent —
  teal (#44BBB9) background with the white S-route mark (vector, no image
  files). Manifest now points at @mipmap/ic_launcher (it used the default
  system icon before).
- AppSettings.enabledPorts() moved from HomeScreen to SettingsRepository
  so Home and Connections share it. RTL: start/end everywhere, no
  left/right padding in the touched screens.

## 0.2.4 — Home screen visual polish (no behavior changes)

- Home rebuilt around a hero card: large round PowerButton (144dp,
  pulsing ring while running, progress ring while starting, static
  rings when "remove animations" is on, light haptic on tap), state
  headline, primary address line, Retry on error. Stat tiles
  (Clients / Upload / Download) appear while running with animated
  numbers.
- 16dp grid, 12dp gaps, tonal cards (hero 28dp, others 20dp);
  header is the small "ProxyShare" title only; engine version text
  removed from Home (moves to Settings > About next step).
- Addresses card: other interfaces expand with AnimatedVisibility;
  credentials monospace; outbound card is compact with the exit-IP
  result as a chip with a colored dot; "Connect your laptop" is a
  tonal button. Power button carries a contentDescription.
- New ui/components/PowerButton.kt (PowerState + EngineState mapping,
  4 unit tests). Kotlin tests now 48.

## 0.2.3 — ports UI, ranked addresses, per-protocol rows

- Settings gains separatePorts (default on), httpPort 8080, socksPort
  1080; toEngineConfig writes http_port/socks_port in split mode and
  the single port in single mode, only for the enabled protocols.
- Settings > Server: "Separate ports for HTTP and SOCKS" switch; on,
  HTTP and SOCKS port fields (1024-65535, must differ); off, the
  single Port field. Restart banner as usual.
- Home addresses card: rankInterfaces (hotspot, Wi-Fi, Ethernet,
  other, mobile; VPN dropped) — first is PRIMARY, shown large
  monospace with one copyable row per enabled protocol (HTTP and
  SOCKS5/4); other interfaces under "Other addresses"; mobile tagged
  "carrier address, usually not reachable from your laptop"; mobile
  as the only address shows the hotspot/Wi-Fi hint; VPN stays in its
  own greyed "outbound only" section.
- Laptop sheet: laptopCommands(host, httpPort, socksPort, user, pass)
  — a null port omits those commands. Notification text is now
  "HTTP :8080 · SOCKS :1080" (only the enabled ones).
- Tests: 4 new toEngineConfig tests (split, single, only-HTTP,
  only-SOCKS), 1 rankInterfaces test, 3 laptopCommands null-port
  tests. Existing tests untouched apart from the expected default
  config and the laptopCommands call order.

## 0.2.2 — separate ports per protocol (Rust only)

- Config gains optional `http_port` and `socks_port`. Neither set:
  single mode, unchanged. At least one set: split mode, `port` is
  ignored; the HTTP listener exists only with http_port + http
  enabled, the SOCKS listener only with socks_port + a SOCKS protocol
  enabled; each listener serves only its own protocols, anything else
  sniffed on it is closed without an event. Equal ports and a split
  config with no listener are errors.
- start() binds ALL listeners before going Running; a bind failure
  releases the ones already bound and names the failing port. stop()
  waits for ALL accept loops, so an immediate restart can rebind.
- Tests: split HTTP + SOCKS5 work with the snapshot counting both
  (5 up / 64 down), wrong protocols closed with no event, equal ports
  and no-listener errors, second-bind failure releasing the first
  port, start/stop/start in split mode, `port` ignored, config
  parsing (bad value, 0, last-wins). Existing tests untouched.

## 0.2.1 — security presets: Password / No password

- Settings > Security: the bare auth switch is now a Password | No
  password segmented row. With No password the username/password
  fields and Regenerate hide, the stored password is kept, and a note
  warns that anyone reaching the port can use the connection.
- No password + private-only off shows a red warning card at the top
  of Settings and on Home ("Open to everyone on every network the
  phone is connected to."). Home shows a compact "No password ·
  private networks only" chip when auth is off and private-only is on.
- No Rust change: the no-auth paths were already proven by
  socks5_connect_relays_to_echo_server (0x00 only), 
  http_get_is_rewritten_and_relayed (no Proxy-Authorization) and
  socks4_connect_relays_to_echo_server, all on a no-auth config.
  toEngineConfig() already wrote no auth lines when auth is off; the
  test now spells out a stored user + password.

## 0.2.0 — laptop setup sheet + first complete build

- Home gets a "Connect your laptop" button opening a ModalBottomSheet:
  pick a non-VPN address, then copyable commands built by the pure
  laptopCommands() (curl HTTP, curl SOCKS5, Linux/macOS export line,
  credentials URL-encoded when auth is on), plus plain-text Windows
  and Firefox steps. (Step was numbered 0.1.10, but that version is
  the Settings screen, so this ships as 0.2.0.)
- New docs/TESTING.md manual checklist; README gains a feature list
  and a short "How to use".
- CI now uploads both the debug and the release APK (R8 on) and prints
  the release APK size in the job summary (workflow edit via web UI).

## 0.1.10 — Settings screen

- New ui/screens/SettingsScreen.kt on SettingsRepository: Server
  (port 1024-65535, bind address from live interface IPs), Protocols
  (at least one stays on), Security (auth switch, username, hidden
  password with Regenerate, private-only, allowed-clients CIDR
  add/remove with validation), Limits (max connections, idle and
  connect timeouts), Appearance (theme System/Light/Dark applied
  live via MainActivity, keep screen on). No language selector.
- Save on change; the stored password is never cleared by toggling
  auth. While running, a change shows "Restart the proxy to apply"
  with a Restart button (ProxyController.restart(): stop + start
  under one mutex). Pure validators in SettingsLogic.kt, tested.
  (Step was numbered 0.1.9, but that version is the Connections
  screen, so this ships as 0.1.10.)

## 0.1.9 — Connections screen (live monitor)

- New ui/screens/ConnectionsScreen.kt: header (totals + live total
  speed), per-client expandable cards sorted by traffic, per-row
  host:port + protocol chip + live speed + duration. started_ms is a
  unix time, so duration is now minus startedMs. Fast polling only
  while the screen is visible (DisposableEffect).
- New ui/screens/ConnectionsLogic.kt: computeSpeeds, formatBytes,
  groupByClient, formatDuration (all pure, all tested). HomeScreen's
  private formatBytes removed — it now uses the shared one.
- AppRoot wires the Connections tab to the screen; Settings stays a
  placeholder. Strings en/fa. (Step was numbered 0.1.8, but that
  version is the crash fix, so this ships as 0.1.9.)

## 0.1.8 — fix crash on open (native lib failed to load)

- Root cause, found in the shipped 0.1.7 APK: libproxyshare_engine.so
  references android_setsocknetwork but has NO libandroid DT_NEEDED,
  so System.loadLibrary fails and the app dies in Application.onCreate.
  cargo-ndk defaulted to platform 21, whose libandroid stub lacks the
  symbol (API 23+), so the linker dropped the dependency.
- Fix: cargo ndk --platform 26 (== minSdk) in both cargoBuild tasks,
  so the stub exports the symbol and the .so records the DT_NEEDED.
- No code changed; engine version bumped to 0.1.8 to match the app.

## 0.1.7 — outbound mode + kill switch + exit-IP check

- AppSettings gains outboundMode ("default"/"vpn_only"/"wifi"/
  "mobile") and exitIpUrl (default https://api.ipify.org), both
  Kotlin-only: toEngineConfig() is unchanged (test proves it).
- New service/NetworkSelector.kt: OutboundMode + setting-string
  mapping, one NetworkCallback per mode, checkExitIp (manual only).
- ProxyController: start waits up to 2 s for the mode's network
  (else Error "No <mode> network available"); while running it
  pushes the network handle to the engine; VPN_ONLY kill switch
  stops at once on network loss, never falls back.
- Engine gains setError() for pre-start errors. Home: segmented
  Default / VPN only / Wi-Fi / Mobile (stopped only) + "Check exit
  IP" with a result card. Strings en/fa.

## 0.1.6 — outbound network binding (Rust only, TCP only)

- New rust/engine/src/netbind.rs: bind_socket_to_network calls
  android_setsocknetwork (libandroid) on Android, no-op elsewhere.
- Connector holds an AtomicU64 network handle (0 = default); connect
  opens each outbound socket with TcpSocket and binds it before
  connecting. Timeout, per-address loop, TCP_NODELAY and error
  mapping unchanged.
- Engine::set_outbound_network + JNI nativeSetOutboundNetwork
  (nativeStop pattern) + Kotlin NativeEngine/Engine wrappers.
- Known limitation: UDP ASSOCIATE sockets and DNS lookups are NOT
  bound yet — TCP only.

## 0.1.5 — navigation shell + Home screen

- New ui/AppRoot.kt (a composable, not the Application class):
  Scaffold with a bottom NavigationBar — Home, Connections,
  Settings — plain rememberSaveable state, no navigation library.
  Connections and Settings are placeholders until 0.1.8 and 0.1.9
  (private placeholder composables in AppRoot.kt). Only core
  Material icons (Home, List, Settings).
- New data/NetworkInfo.kt: IfKind (WIFI, HOTSPOT, ETHERNET, MOBILE,
  VPN, OTHER), NetIf, classifyInterface, listIPv4 (up, non-loopback
  IPv4 only), networkChanges callbackFlow (available/lost/link
  properties, VPN networks included) and networkInterfaces, which
  re-lists on every network change and every 5 s (hotspot toggles do
  not always fire callbacks), debounced 300 ms, on Dispatchers.IO.
- HomeScreen rewritten, driven by ProxyController state: large
  animated Start/Stop button (64 dp, 28 dp corners, animated color
  and label), status card (Stopped / Starting / Running / Error with
  the message), running totals (up/down bytes, active connections)
  from the snapshot, address card (one row per non-VPN interface,
  "label (name): IP:port" with a copy button; the raw interface name
  is always shown, so wlan1-as-hotspot stays visible; VPN
  interfaces listed separately, greyed out, "outbound only"),
  credentials card when auth is on (user + copy, password hidden
  with tap to reveal + copy), protocol chips (HTTP / SOCKS4 /
  SOCKS5) reflecting the settings, warning banner when auth is off
  and private_only is off. API 33+: an explanation dialog, then the
  POST_NOTIFICATIONS request; the proxy still starts when it is
  denied. Temporary 0.1.4 buttons removed. settings.keepScreenOn is
  honored while running. MainActivity now shows AppRoot.
- Strings in values/ and values-fa/. Unit test for
  classifyInterface (6 tests: every prefix group + unknown).
- 3 new files this step (AppRoot, NetworkInfo, NetworkInfoTest) —
  the placeholders live in AppRoot.kt to stay within the rule.

## 0.1.4 — service + ProxyController

- CI: `./gradlew testDebugUnitTest` now runs before assembleDebug
  (workflow edit, separate commit).
- New app/ProxyShareApp.kt: Application subclass calling
  ProxyController.init(this) in onCreate; registered with
  android:name in the manifest.
- New service/ProxyController.kt: app-scoped singleton (object, no
  Hilt) owning the one Engine, its own CoroutineScope (SupervisorJob
  + Dispatchers.Default) and a SettingsRepository. Exposes state /
  snapshot / events, plus a settings flow (needed for the
  notification's port text; the settings screen will use it too).
  start() reads settings, runs ensurePassword(), starts the engine;
  stop() also resets an Error state to Stopped. Both are safe to call
  twice and never throw; concurrent starts are guarded by a Mutex.
- New service/ProxyService.kt: foreground service (specialUse,
  PROPERTY_SPECIAL_USE_FGS_SUBTYPE local_proxy_server). Ongoing
  notification (channel proxy_running, IMPORTANCE_LOW) with a Stop
  action and a tap action opening MainActivity, using a new small
  vector status icon (ic_proxy_status, swap arrows) instead of the
  launcher icon; text "Running on port <port>", refreshed if the
  settings land or change after start. Holds a non-reference-counted
  PARTIAL_WAKE_LOCK only while running. Observes the engine state:
  Stopped (from the app) stops the service; Error shows the error
  text, keeps the notification visible (detach), and stops the
  service. ACTION_STOP does the same from the notification.
- Manifest: FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE,
  POST_NOTIFICATIONS, WAKE_LOCK permissions; the service declared
  exported=false with foregroundServiceType specialUse.
- HomeScreen (temporary, replaced in 0.1.5): Start and Stop buttons
  plus the live state text (Start launches the service, Stop calls
  ProxyController.stop()).
- New dependency: androidx.core 1.16.0, for ServiceCompat,
  ContextCompat and NotificationCompat. It was already in the APK
  through activity-compose, so the size cost is ~0.
- Rust side untouched except ENGINE_VERSION, now "engine 0.1.4".

## 0.1.3 — engine/Engine.kt (step 0.0.10b)

- New app/src/main/java/com/example/proxyshare/engine/Engine.kt:
  Kotlin wrapper that owns the native handle (`nativeCreate` in the
  constructor). Exposes `state: StateFlow<EngineState>` (sealed
  interface Stopped/Starting/Running/Error, mirroring the Rust
  EngineState), `snapshot: StateFlow<Snapshot>` and `events:
  SharedFlow<EngineEvent>` (extra buffer 256, DROP_OLDEST).
- `start(settings)` and `stop()` are suspend functions on
  Dispatchers.IO; `start` maps settings with `toEngineConfig()`,
  returns "" or the error text, and polls `nativeSnapshot` every 1 s
  while running; `setFastPolling(true)` switches the poll to 250 ms
  for the connections screen; `close()` destroys the handle and is
  safe to call twice. The listener is registered once in init;
  events arrive on a native thread and are only tryEmit-ted, and a
  type-1 event also updates the state (with its error message).
- `parseSnapshot` is verbatim from the spec: null for a missing or
  short head line, bad head numbers fall back to 0, a malformed
  connection line is skipped, never a crash.
- Tests: 7 parser tests in app/src/test/.../EngineTest.kt (good
  snapshot, empty text, short head, bad head numbers, malformed
  connection lines skipped, blank lines ignored, state-name mapping).
- Correction: 0.1.2 was Kotlin-only and left ENGINE_VERSION at
  "engine 0.1.1", so its home screen showed the old engine version.
  It is "engine 0.1.3" now, in step with the app version.
- Step 0.0.10b shipped as 0.1.3: 0.0.10, 0.1.0, 0.1.1 and 0.1.2
  were taken. The CI workflow still does not run
  `./gradlew testDebugUnitTest` — run it locally until the workflow
  gains the step.

## 0.1.2 — SettingsRepository (step 0.0.10a)

- New app/src/main/java/com/example/proxyshare/data/SettingsRepository.kt:
  Preferences DataStore with every Config field plus themeMode,
  language and keepScreenOn. Exposes `Flow<AppSettings>` and a
  suspend update function per field; `ensurePassword` generates a
  random 12-character password at first run when auth is on and none
  is set. The password is never logged.
- `AppSettings.toEngineConfig()` maps settings to the engine's
  key=value text (verbatim from the spec): auth keys only when auth
  is enabled with a non-blank user and password, `allowed_clients`
  only when the list is non-empty — no empty keys are written.
  Defaults: port 8080, all three protocols on, private_only true.
- Unit test (new app/src/test/.../SettingsRepositoryTest.kt, JUnit
  4.13.2 added to the version catalog): the full default mapping,
  auth keys omitted when the password is empty or auth is off,
  `allowed_clients` omitted when empty / joined when set, password
  length and character set. 6 tests.
- Step 0.0.10a shipped as 0.1.2: 0.0.10, 0.1.0 and 0.1.1 were taken.
  Note: the CI workflow does not run `./gradlew testDebugUnitTest`
  yet — run it locally until the workflow gains the step.

## 0.1.1 — SOCKS5 UDP ASSOCIATE (step 0.0.9b)

- CMD 0x03 in socks5.rs: `read_request` now returns the command too
  and `handle` dispatches CONNECT (existing body, now in `connect`)
  vs UDP ASSOCIATE (new `associate`) vs anything else (0x07).
- `associate` binds one UdpSocket on the TCP connection's local IP
  (port 0), replies success with its address, then loops with
  `tokio::select!` over the TCP control connection (read 0 or error
  ends the association) and `udp.recv_from` with the idle timeout.
  Client-IP datagrams are parsed, resolved with lookup_host, their
  DATA sent to the target and the client's UDP source remembered;
  other datagrams get the sender's address prepended as a header and
  go to the remembered client address, and are dropped while no
  client address is known yet. Only the opening IP may send. Whole
  datagrams count into up/down; the destination host:port is metadata
  only. The `#[allow(dead_code)]` on `parse_udp_header` is gone —
  it is used now.
- Tests: 3 new engine tests with a local UDP echo server (it listens
  on 127.0.0.2, a different loopback IP from the client, because the
  dispatch treats a datagram from the client's own IP as a client
  datagram): round trip (answer comes back wrapped in a header),
  foreign-IP datagram ignored (snapshot counters stay 0), closing
  the TCP connection ends the association (ConnectionClosed + no UDP
  answer). Total tests now 74 (71 + 3).
- docs/ENGINE_API.md records UDP ASSOCIATE.
- Step 0.0.9b shipped as 0.1.1: 0.0.9, 0.0.10 and 0.1.0 were taken.

## 0.1.0 — SOCKS5 UDP header, pure functions (step 0.0.9a)

- New rust/engine/src/udp.rs: `parse_udp_header` and
  `build_udp_header` (verbatim from the spec). Datagram layout is
  RSV(2) = 0, FRAG(1) = 0, ATYP, DST.ADDR, DST.PORT, DATA; FRAG != 0
  means drop. No sockets in this step.
- `Target` in socks5.rs is now `pub(crate)` (plus `Debug` /
  `PartialEq` derives so the tests can compare it) so udp.rs can use
  it; lib.rs has `pub mod udp`.
- Tests: v4, domain and v6 round trips, truncated input returns None,
  FRAG != 0 returns None. Total tests now 71 (66 + 5).
- docs/ENGINE_API.md records the UDP header functions.
- Step 0.0.9a shipped as 0.1.0: 0.0.9 and 0.0.10 were both taken.

## 0.0.10 — failed-auth throttle (step 0.0.8b)

- New `AuthGuard` in rust/engine/src/auth.rs (verbatim from the spec):
  after 5 failed attempts within 60 s from one IP, the IP is blocked
  for 5 minutes; the time is passed in, so tests use fake Instants
  and never sleep. One guard lives in Engine.
- A blocked IP is closed in `handle_connection` before any protocol
  byte is read. A handler reports an auth failure as a
  `PermissionDenied` error, which the engine records against the
  connection's client IP; when a block starts, an `AuthFailed` event
  (type 5) is emitted with the IP only. A blocked IP that tries again
  also emits `AuthFailed`.
- Tests: 3 guard unit tests (4 failures do not block, the 5th does,
  the block ends after 5 minutes, failures older than 60 s do not
  count) + 1 engine test (5 wrong SOCKS5 logins, then the next
  connection closes at the door and `AuthFailed` is emitted). The
  model test now covers the type-5 callback.
- docs/ENGINE_API.md records the throttle and the type-5 event (and
  fixes the stale Server line that still said HTTP/SOCKS4 had no
  handler).

## 0.0.9 — authentication (step 0.0.8a)

- New rust/engine/src/auth.rs: `ct_eq` and `base64_decode`
  (hand-written, no crates), `required` / `check` / `check_basic`
  against the config's `auth_user` / `auth_pass`.
- SOCKS5: with auth, only method 0x02 is accepted, then RFC 1929
  ([0x01, 0x00] on success, [0x01, 0x01] on failure and close);
  without auth, no-auth only as before.
- HTTP: with auth, a missing or wrong `Proxy-Authorization: Basic`
  header gets a 407 with `Proxy-Authenticate: Basic
  realm="ProxyShare"`; applies to CONNECT and plain requests.
- SOCKS4: refused with 0x5B when auth is required (it has no auth).
- Tests: auth.rs unit tests (base64, ct_eq, check_basic); every
  protocol with right, wrong and no credentials — SOCKS5 3, HTTP
  CONNECT 3 + plain GET 3, SOCKS4 1 with auth → 0x5B. Attempted
  passwords are never logged or emitted.
- docs/ENGINE_API.md records the auth contract.

## 0.0.8 — SOCKS4/4a

- New rust/engine/src/socks4.rs: `handle` (same signature as
  socks5::handle), CONNECT only, 10 s timeout on every read,
  `read_cstring` for USERID and the 4a hostname, 0x5A on success and
  0x5B on failure (wrong command, DNS or connect failure).
- Engine: explicit `Protocol::Socks4` arm copied from the Socks5 arm;
  every protocol now has a handler, so the placeholder arm and its
  `handler_missing` emission are gone. `Event::HandlerMissing` stays
  in the contract (type 4) with `#[allow(dead_code)]`. The old
  handler_missing test is deleted; the disabled-protocol test still
  asserts no event is emitted for a disabled protocol.
- Tests: plain SOCKS4 and SOCKS4a through the engine to a local echo
  server (0x5A, echo works, 5 up / 5 down), BIND gets 0x5B, and a
  mixed test (HTTP, SOCKS4, SOCKS5 one after another on the same port,
  snapshot totals 10 up / 12 down).
- docs/ENGINE_API.md records the SOCKS4 contract.

## 0.0.7 — HTTP CONNECT tunnels (step 0.0.6a)

- CONNECT tunnels in rust/engine/src/http.rs: `split_host_port` (host
  may be an IPv6 literal in brackets), target set as soon as parsed,
  502 for DNS or connect failure, `200 Connection Established` on
  success, bytes already sent after the head forwarded, then relayed
  untouched. The head cap is 16 KiB for both CONNECT and plain HTTP
  (was 64 KiB in 0.0.6). Plain HTTP still forwards GET/POST: the 501
  in the 0.0.6a spec assumed 0.0.6a landed before 0.0.6b, which is
  already green.
- Tests: `split_host_port` unit test; CONNECT to a local echo server
  (200, echo works, 5 up / 5 down), CONNECT to a closed port gives
  502, invalid-UTF-8 garbage gives 400.
- docs/ENGINE_API.md records the CONNECT contract.

## 0.0.6 — plain HTTP forwarding (step 0.0.6b)

- New rust/engine/src/http.rs: `rewrite_head` (pure function, the
  reference rewrite verbatim: absolute URI to origin-form, hop-by-hop
  headers dropped, `Connection: close` added) and `handle` (reads the
  head with a 10 s timeout, 400 for anything but an absolute-URI
  request, 502 for an unreachable target, sends the rewritten head
  plus body bytes already read, then relays both directions untouched;
  bodies are never parsed or logged).
- Engine: HTTP connections now emit `ConnectionOpened` (type 2) at
  registration and `ConnectionClosed` (type 3) at the end, like SOCKS5;
  SOCKS4 still reports `handler_missing` (type 4). `nativeVersion()`
  now returns "engine 0.0.6".
- Tests: `rewrite_head` unit tests (absolute URI with and without a
  port, all 7 hop-by-hop headers dropped, relative URI / CONNECT /
  https / bad port rejected); GET and POST against a local recording
  server (rewritten head received, body intact), relative URI gives
  400, unreachable target gives 502.
- docs/ENGINE_API.md records the HTTP contract.

## 0.0.5 — relay + SOCKS5 CONNECT

- New rust/engine/src/connector.rs: an `Outbound` trait (the seam 0.1.1
  uses to bind sockets to an Android Network) with a plain `TcpOutbound`
  implementation: resolves via `tokio::net::lookup_host`, tries the
  addresses in order, applies `connect_timeout_s` to the lookup and to
  each connect attempt, sets `TCP_NODELAY`. A failure is `Dns` (name did
  not resolve) or `Connect` (no address accepted it), so the handler can
  answer with the right SOCKS5 reply code.
- New rust/engine/src/relay.rs: `pump` (16 KiB buffer, live counting into
  the connection record's counters, idle timeout, clean shutdown on EOF)
  and `relay` (two pumps joined with `tokio::join!`, one per direction;
  when either side ends, the other ends with it).
- New rust/engine/src/socks5.rs: method negotiation (no-auth only — any
  other list gets `[0x05, 0xFF]`), CONNECT requests with ATYP IPv4 /
  domain / IPv6, reply codes 0x00, 0x04, 0x05, 0x07, 0x08, a 10 s
  negotiation timeout, the record's target filled in as soon as the
  request is parsed, and the relay counting live into the record.
- Engine: SOCKS5 connections now emit `ConnectionOpened` (type 2) at
  registration and `ConnectionClosed` (type 3) with the final counts at
  the end (unregistered first, so the snapshot already carries the final
  totals); HTTP and SOCKS4 still report `handler_missing` (type 4). The
  sniffer only peeks, so the SOCKS5 handler reads the stream from its
  first byte; the paths where no handler reads the stream still consume
  the sniffed byte first, so the close is a clean FIN. Counters count
  relayed payload only, not the greeting, request or reply bytes.
- Tests: a SOCKS5 client talking to a local echo server through the
  engine (greeting, CONNECT, payload echoed back, opened/closed events,
  5 up / 5 down, snapshot totals kept after close); unknown method ->
  0xFF; BIND -> 0x07; bad ATYP -> 0x08; unresolvable domain -> 0x04;
  connector and relay unit tests over real sockets and in-memory
  duplex streams. `nativeVersion()` now returns "engine 0.0.5".
- FIX (same version, run #19 red): cargo test failed to compile with 3
  errors in relay.rs — E0505 "cannot move out of `ctr` / `up` /
  `down` because it is borrowed". This red was my own doing: in the
  previous push I changed two `tokio::join!` arms to `async move`
  "to be safe" without the compiler asking for it, and a `move` arm
  moves the counter while the sibling arm borrows it. In edition 2021
  a plain `async` arm captures by reference, so a value borrowed by
  one arm can also be read by the other — the plain version was
  correct. Both arms are back to plain `async`. Lesson: never apply a
  speculative fix the compiler never asked for.
- FIX (same version, run #20 red): cargo test passed (33 passed), but
  clippy failed with one lint — `async_fn_in_trait` on
  `Outbound::connect` (an `async fn` in a trait cannot state the
  `Send` bound its future needs). The trait and the `TcpOutbound`
  impl are now a plain `fn` returning `impl Future<Output =
  Result<TcpStream, ConnectError>> + Send`, with the body wrapped in
  `async move`; the call site (socks5) and the tests await it the
  same way, so nothing else changed.
- FIX (same version, runs #20–#21 red): the `Outbound` trait's
  async-in-trait signature failed clippy, and the trait had exactly
  one implementation with nothing needing it before 0.1.1, so it is
  deleted: `Connector` is now a plain struct with an inherent async
  `connect`, and the SOCKS5 handler takes `&Connector` directly.
- FIX (same version, run #22 red): cargo test passed (33 passed), but
  clippy failed with one lint — `explicit_auto_deref` on `&*engine.
  outbound`: the parameter is now a concrete `&Connector`, so the
  explicit deref is redundant (auto-deref does it). One line.
- docs/ENGINE_API.md records the SOCKS5, outbound and relay contracts.

## 0.0.4 — server shell + JNI lifecycle

- New rust/engine/src/engine.rs: a global tokio runtime (multi-thread, 2
  workers named "ps-worker"), an Engine stored in a global handle table
  (Mutex<HashMap<i64, Arc<Engine>>>), start() binding a TcpListener via
  socket2 (SO_REUSEADDR, backlog 128) with an accept loop enforcing
  max_connections and the client filter (allowed_clients + private_only),
  a connection registry (id, counters as AtomicU64), stop() cancelling
  the accept task and closing sockets (connection tasks are woken by the
  shutdown signal, so everything closes well within 2 s).
- New rust/engine/src/sniffer.rs: ProtocolSniffer via peek() — 0x04
  Socks4, 0x05 Socks5, else Http — with a 5 s wait for the first byte.
  Handlers are not implemented yet: a sniffed connection is registered,
  its sniffed byte consumed, reported as a handler_missing event (type 4)
  and closed cleanly.
- New rust/engine/src/listener.rs: JniListener (cached JavaVM, GlobalRef
  to the Kotlin listener, cached onEvent method ID, events sent from
  tokio threads via attach_current_thread) behind a small EventSink trait,
  so the engine core is testable without Java.
- JNI API: nativeCreate(): Long, nativeStart(h, cfg): String ("" = ok,
  else error text), nativeStop(h), nativeDestroy(h), nativeSnapshot(h):
  String, nativeSetListener(h, listener: EngineListener). Kotlin
  EngineListener interface + the five externs on NativeEngine; keep rule
  for EngineListener. nativeVersion() now returns "engine 0.0.4".
- Event type 4 (handler missing) added to the contract and to
  docs/ENGINE_API.md, with the lifecycle and server-shell sections.
- Tests: bind failure (port in use) gives a clear error naming the
  address and port; start/stop twice is safe (start is idempotent,
  stop is idempotent, start after stop rebinds); snapshot reflects the
  state; a SOCKS5 connection reports handler_missing and closes; a
  disabled protocol closes quietly with no event.
- FIX (same version, run #12 red): cargo test failed to compile with 5
  errors in engine.rs — `IpAddr` has no `is_private` (it is on
  `Ipv4Addr`/`Ipv6Addr`, so the check is now per family);
  `HashMap::new` is not const, so the `ENGINES` static is now a
  `LazyLock`; an `async move` block captures the whole `listener` Arc,
  so the task's clone is made before the spawn; and draining the
  connection map while adding its counters to the same struct is two
  mutable borrows, so the drain is collected first.
- FIX (same version, run #13 red): cargo test compiled, but 4 tests
  failed. Three panicked with "there is no reactor running":
  `TcpListener::from_std` needs a tokio runtime context, but `start()`
  runs on the caller's (plain) thread. The bind now returns a plain
  std listener, and the accept task converts it with `from_std` inside
  the runtime (on failure it reports an Error state); the engine no
  longer stores the listener — the task owns it, so `stop()` closes it
  by aborting the task. The fourth test failed because the
  disabled-protocol path closed the connection with the sniffed byte
  still unread, so the kernel sent RST and the client's read got
  "Connection reset by peer": the byte is now consumed right after
  sniffing, before the enabled check, so every close is a clean FIN.
- FIX (same version, run #14 red): cargo test passed (22 passed), but
  clippy `-D warnings` failed with 4 style lints: Engine needs a
  `Default` impl next to `new()`; `.map(|j| String::from(j))` is a
  redundant closure (use `.map(String::from)`); the nativeCreate match
  simplifies to `catch_unwind(AssertUnwindSafe(create_engine))
  .unwrap_or_default()` (the closure was redundant too). No behavior
  changed — the tests had already passed.
- FIX (same version, run #15 red): cargo test failed on one test,
  start_stop_twice_is_safe: "start after stop" failed to bind the same
  port — "Address already in use". Cause: `stop()` only aborted the
  accept task, and the socket stays bound until the task is actually
  dropped, so an immediate restart raced the teardown (it passed in
  run #14 by luck). `stop()` now waits for the accept task to finish
  unwinding (bounded by 2 s, skipped when called inside a runtime,
  where block_on would panic), so the socket is closed before `stop()`
  returns — which is also what the real app needs when the user toggles
  the proxy off and on quickly.
- FIX (same version, run #16 red): cargo test passed (22 passed) again,
  but clippy failed with one lint: `let _ = rt.block_on(...)` — block_on
  returns `()` (unit), so binding it is a `let_unit_value` error. Dropped
  the `let _ =`. One line.
- Run #17 GREEN (2026-11-12); v0.0.4 release APK is signed
  (ProxyShare-0.0.4-release.apk). Step 0.0.4 complete.

## 0.0.3 — Rust models, config, CIDR

- New rust/engine/src/model.rs: Protocol (Http, Socks4, Socks5), ConnInfo,
  EngineState (Stopped, Starting, Running, Error(String)), Event
  (StateChanged, ConnectionOpened, ConnectionClosed) with to_callback()
  mapping to (type, a, b) — 1 state, 2 opened, 3 closed. snapshot_text():
  first line "state total_up total_down active", then one
  "id client host port proto started_ms up down" line per connection.
- New rust/engine/src/config.rs: Config parsed from plain "key=value" text
  (hand-written parser; blank lines and # comments ignored; unknown keys
  ignored; last value wins; clear errors naming line, key and value).
  Keys: bind_addr, port, protocols, auth_user/auth_pass (both or neither),
  allowed_clients, private_only, max_connections, idle_timeout_s,
  connect_timeout_s. Defaults: 0.0.0.0:1080, all three protocols, no auth,
  no client list, private_only true, 64 connections, 300 s idle, 10 s
  connect.
- New rust/engine/src/cidr.rs: IPv4/IPv6 CIDR parse + contains (bare IP =
  single host; host bits masked at parse; other family never contains).
- docs/ENGINE_API.md now records the decided formats: the config key table,
  the snapshot text format, and the onEvent(type:Int, a:String, b:String)
  callback. JNI wiring for config/snapshot/events lands with the engine
  start; no networking yet.
- Unit tests for the config parser and Cidr (v4 + v6) and the model formats.

## 0.0.2 — Rust toolchain + JNI hello world

- Rust workspace at rust/; engine crate proxyshare_engine (cdylib) with jni
  0.21, tokio 1 (minimal features), socket2 0.5, libc 0.2. Release profile:
  opt-level z, LTO, codegen-units 1, strip, panic unwind.
  (Verified: latest stable is jni 0.22.4 and socket2 0.6.5; stayed on the
  pinned 0.21 / 0.5 because the step's JNI snippet targets the 0.21 API.)
- rust/.cargo/config.toml: 16 KB page-size link arg for aarch64 and x86_64
  (Android 15+).
- Gradle cargoBuild (debug, arm64-v8a + x86_64) and cargoBuildRelease (all
  ABIs, --release) wired before preDebugBuild / preReleaseBuild; readable
  error if cargo or cargo-ndk is missing. rust/target and jniLibs ignored.
- One JNI function: nativeVersion() returns "engine 0.0.2"; wrapped in
  catch_unwind so no panic crosses JNI (returns null on failure).
- Kotlin NativeEngine object (@JvmStatic) + version shown on the home screen
  ("Engine unavailable" if the library fails to load).
- proguard keeps native methods; docs/ENGINE_API.md records the contract.
- CI: Rust toolchain + cargo-ndk, cargo test, cargo clippy -D warnings,
  and APK .so checks.
- Run #10 GREEN (2026-11-12); v0.0.2 release APK is signed
  (ProxyShare-0.0.2-release.apk).
- FIX (same version): the 0.0.1 release APK shipped unsigned — the
  persistent signing config was only applied to the debug build type.
  The release build type now uses the same persistent key.
- FIX (same version, run #7 red): tasks.named("preDebugBuild") throws at
  configuration time — AGP registers preDebugBuild/preReleaseBuild only
  after the build script is evaluated. Replaced with tasks.configureEach,
  which fires when the task is registered.
- FIX (same version, run #8 red): clippy -D warnings failed on
  `unused_mut` — in jni 0.21, JNIEnv::new_string takes &self, so the
  `mut` on the env parameter from the spec snippet is unused. Dropped it.
  Also moved [profile.release] to the workspace root rust/Cargo.toml:
  cargo ignores profiles in a non-root member, so the engine's release
  profile (opt-level z, LTO, strip) was silently ignored.
- FIX (same version, run #9 red): the debug build compiled and packaged,
  then failed storing the configuration cache — `doFirst
  { checkCargoToolchain() }` makes the cargoBuild task action reference the
  build script object, which cannot be serialized. The cargo/cargo-ndk
  presence check now runs inside the shell command itself (same readable
  error messages); the task command line is plain data, so it is cache-safe.

## 0.0.1 — Kotlin skeleton

- Folders engine/ service/ data/ ui/theme ui/components ui/screens; no core/
  folder.
- Dependencies only: Compose BOM, material3, activity-compose,
  lifecycle-runtime-compose, kotlinx-coroutines-android, datastore-preferences.
- ProxyShareTheme: System/Light/Dark, dynamic color on Android 12+, fallback
  palette, large rounded shapes, edge-to-edge.
- MainActivity with a centered placeholder from strings; manifest
  supportsRtl=true, enableOnBackInvokedCallback=true; strings in English +
  Persian.
- Release: isMinifyEnabled + isShrinkResources, proguard-android-optimize.txt,
  locale filter en/fa, packaging excludes, Kotlin -Xno-param-assertions /
  -Xno-call-assertions / -Xno-receiver-assertions, -assumenosideeffects for
  Log.v/d in proguard-rules.pro.
- Gradle wrapper properties (9.5.0); CI generates the wrapper scripts + jar
  with `gradle wrapper` and verifies ./gradlew assembleDebug +
  ./gradlew assembleRelease.
- No proxy engine yet — that is a later step. No Rust crate in this step, so
  the cargo test / clippy checks apply from the step that adds rust/engine.
