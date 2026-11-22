# Engine API — Kotlin ↔ Rust contract

This file is the contract between the Kotlin app and the Rust engine
(`rust/engine`, cdylib `proxyshare_engine`). Any change to the contract
updates this file in the same step.

## Loading

Kotlin loads the library once, from `engine.NativeEngine`:

```kotlin
System.loadLibrary("proxyshare_engine")
```

The library file is `libproxyshare_engine.so`, built by the `cargoBuild` /
`cargoBuildRelease` Gradle tasks into `app/src/main/jniLibs` for
arm64-v8a, armeabi-v7a and x86_64 (release) or arm64-v8a and x86_64 (debug).

## Functions

### `nativeVersion(): String`

- JNI name: `Java_com_example_proxyshare_engine_NativeEngine_nativeVersion`
- Kotlin: `@JvmStatic external fun nativeVersion(): String` on
  `object NativeEngine` (`@JvmStatic` matters: the Rust side takes a
  `JClass` second parameter, so the method must be static.)
- Rust: `pub extern "system" fn ... (env: JNIEnv, _class: JClass) -> jstring`
- Returns the engine version string, e.g. `"engine 0.5.0"`.
- On failure (including a Rust panic) it returns null instead of letting an
  exception cross the FFI boundary; every JNI entry point wraps its body in
  `catch_unwind`.

### Engine lifecycle

Each engine is owned by Kotlin through a handle (`Long`, > 0; 0 is never
a valid handle). Handles are never reused. All of these are `@JvmStatic`
methods on `object NativeEngine`, so the Rust side takes a `JClass`
second parameter.

| Kotlin | Rust | Meaning |
| --- | --- | --- |
| `nativeCreate(): Long` | creates an `Engine`, stores it in the global handle table | Returns the handle. |
| `nativeStart(h: Long, cfg: String): String` | `Engine::start` | Parses the config and starts listening. Returns `""` on success, an error text on failure. Starting an already starting or running engine is a no-op returning `""`. |
| `nativeStop(h: Long)` | `Engine::stop` | Stops listening, cancels the accept tasks and closes the sockets, waiting for all of them (at most 2 s each). Safe on a stopped engine. |
| `nativeCloseClient(h: Long, ip: String)` | `Engine::close_client` | Aborts every open connection from that client IP (including ones still sniffing) and reports them closed. Safe on an unknown handle or a malformed IP (no-op). |
| `nativeDenyClient(h: Long, ip: String)` | `Engine::deny_client` | Refuses the client until the proxy restarts. Existing connections stay open. Safe on an unknown handle or a malformed IP (no-op). |
| `nativeAllowClient(h: Long, ip: String)` | `Engine::allow_client` | Lifts a block set by nativeDenyClient. Safe on an unknown handle or a malformed IP (no-op). |
| `nativeSetOutboundNetwork(h: Long, network: Long)` | `Engine::set_outbound_network` | Sets the Android Network handle (net_handle_t) for outbound TCP connections; 0 means the default network. Applies to connections opened after the call. Safe on a stopped or unknown handle (no-op). |
| `nativeSetVpnActive(h: Long, active: Boolean)` | `Engine::set_vpn_active` | Tells the engine whether a VPN network is active right now. Only consulted when the config sets `require_vpn` (see "VPN watchdog"). Safe on a stopped or unknown handle (no-op). |
| `nativeDestroy(h: Long)` | stops the engine and removes it from the handle table | Unknown handles are a no-op. |
| `nativeSnapshot(h: Long): String` | `Engine::snapshot` | The snapshot text (below), or `""` for an unknown handle. |
| `nativeHistory(h: Long): String` | `Engine::history` | The last 120 seconds of speed samples as `up,down;up,down;…` (oldest first, one pair per second, gaps filled with `0,0`), or `""` for an unknown handle. See "Engine-owned stats". |
| `nativeSessionLog(h: Long, sinceId: Long): String` | `Engine::session_log_since` | Closed-connection records with an id greater than `sinceId`, one line per record (below), oldest first; `""` when there is nothing newer. See "Engine-owned stats". |
| `nativeSessionLogClear(h: Long)` | `Engine::clear_session_log` | Clears the session log ring and restarts the id counter at 0. Safe on an unknown handle (no-op). |
| `nativeDiagLog(): String` | reads the diagnostics ring buffer | The log text (below), or `""` when empty. Takes no handle: the buffer is global, not per-engine. |
| `nativeDiagClear()` | clears the diagnostics ring buffer | No handle: the buffer is global, not per-engine. |
| `nativeValidate(kind: Int, value: String): String` | `validate::validate` | Validates one settings value in Rust, the single validation source (see "Validation"). Returns `""` when valid, or a stable error code. Takes no handle: validation is pure, not per-engine. |
| `nativeSetListener(h: Long, listener: EngineListener)` | builds a `JniListener` and registers it as the engine's event sink | `EngineListener` is a Kotlin interface `{ fun onEvent(type: Int, a: String, b: String) }`. The Rust side caches the `JavaVM`, holds a `GlobalRef` to the listener and caches the `onEvent` method ID. Events are sent from the tokio worker threads ("ps-worker") with `attach_current_thread`; `onEvent` runs on that worker thread, so the Kotlin side must post to the main thread before touching UI or Compose state. |

## Server (0.0.4, extended in 0.0.5)

- `start` binds one `TcpListener` per listener spec through socket2
  (`SO_REUSEADDR`, backlog 128) and runs one accept loop per listener
  on the global tokio runtime (`Builder::new_multi_thread`, 2 worker
  threads named "ps-worker"). Single mode (neither `http_port` nor
  `socks_port` set) binds one listener on `port` serving every enabled
  protocol; split mode binds one listener per configured port, each
  serving only its own protocols (see Config). All listeners are bound
  before the engine goes Running; if any bind fails, the ones already
  bound are released and the engine goes to Error naming the failing
  port.
- A client is accepted only when it passes the config's client filter
  (`allowed_clients` list, `private_only`); others are dropped with no
  event. The accept loop also enforces `max_connections` (counting every
  connection task currently running, including in-flight sniffs); further
  connections are dropped with no event. Every refusal is counted by
  reason (see "Connection limits"), never dropped silently.
- Each accepted connection is handled by its own tokio task: the first
  byte is peeked (up to 5 s) and classified — `0x04` SOCKS4, `0x05`
  SOCKS5, anything else HTTP. A sniffed protocol that this listener
  does not serve closes the connection quietly. The peek does not consume
  the byte, so the handler that runs next still sees the whole stream;
  a path where no handler reads the stream consumes the byte first, so
  the close is a clean FIN instead of a reset.
- Each connection is handled by its protocol's handler (see below):
  the connection is registered when it opens and unregistered with
  its final byte counts when it ends. (The Rust-internal
  `ConnectionOpened` / `ConnectionClosed` events still exist for
  tests, but since 0.5.1 they are no longer sent through JNI — see
  "Events".)

## Failed-auth throttle (0.0.10)

- After 5 failed authentication attempts within 60 s from one IP, the
  IP is blocked for 5 minutes. A blocked IP is closed before any
  protocol byte is read.
- When a block starts, and when a blocked IP tries to connect again,
  an `AuthFailed` event (type 5) is emitted with the IP only (a =
  the IP, b = ""). It never carries a password or any credential.
- The guard (`auth::AuthGuard`) takes the time as an argument, so
  tests use fake Instants and never sleep. One guard lives in Engine;
  a handler reports an auth failure as a `PermissionDenied` error,
  which the engine records against the connection's client IP.

## Blocked domains (0.4.0)

- Config key `blocked_domains`: a comma list of exact hosts
  (`ads.example.com`) or suffix wildcards (`*.example.com`, which
  also matches `example.com` itself). Matching is case-insensitive
  and ignores a trailing dot; IP literals never match, and neither
  does an empty host or an empty list. The pure function is
  `config::domain_blocked(list, host)`.
- Checked before any outbound connect, after the request is parsed
  (and after auth): HTTP CONNECT and plain HTTP get a 403, SOCKS5
  CONNECT gets 0x02 (connection not allowed by ruleset), SOCKS4
  CONNECT gets 0x5B. The refused connection is marked blocked on
  its record, so the record in the engine session log carries the
  flag; the connection is otherwise closed normally (no error event).
- UDP ASSOCIATE datagrams are NOT filtered: there is no refusal
  reply for a datagram, so the block applies to TCP connects only.

## Authentication (0.0.9)

- When the config has both `auth_user` and `auth_pass`, every protocol
  must authenticate. No auth is configured by default.
- SOCKS5: only method 0x02 (username/password) is accepted, otherwise
  [0x05, 0xFF]. Then RFC 1929: [0x01, ulen, user, plen, pass]; reply
  [0x01, 0x00] on success, [0x01, 0x01] on failure and close.
- HTTP: without a valid `Proxy-Authorization: Basic` header, reply 407
  with `Proxy-Authenticate: Basic realm="ProxyShare"`, `Content-Length:
  0` and `Connection: close`. This applies to CONNECT and plain
  requests. The header is hop-by-hop: the rewrite drops it, so it is
  never forwarded to the target.
- SOCKS4 has no auth: it is refused with 0x5B when auth is required.
- Credentials are compared in constant time (`auth::ct_eq`); the
  base64 decoder is hand-written, no crates. Attempted passwords are
  never logged or emitted.

## SOCKS4/4a (0.0.8)

- CONNECT only (CD = 1); anything else gets a 0x5B reply. A target
  on the `blocked_domains` list also gets a 0x5B reply, before any
  outbound connect, and the connection is recorded as blocked.
  Request:
  VN = 4, CD, DSTPORT, DSTIP, USERID (null-terminated), and for 4a
  (DSTIP is 0.0.0.x with x != 0) a null-terminated hostname after the
  USERID. USERID is read and ignored. Reply is [0x00, 0x5A, 0, 0, 0, 0,
  0, 0] on success and [0x00, 0x5B, ...] on failure. Every read has a
  10 s timeout, like SOCKS5.
- Every protocol now has a handler, so no connection reports
  `handler_missing` (type 4) anymore. The `HandlerMissing` event stays
  in the contract (type 4, do not renumber) for backward compatibility.

## SOCKS5 (0.0.5)

- Method negotiation (no-auth only for now): the client offers a list
  of methods; if it includes 0x00 the engine answers `[0x05, 0x00]`.
  Any other list gets `[0x05, 0xFF]` and the connection closes.
- Requests: ATYP IPv4 (0x01), domain (0x03), IPv6 (0x04). CMD CONNECT
  (0x01) and UDP ASSOCIATE (0x03). The target host and port are
  recorded on the connection as soon as the request is parsed, so the
  snapshot shows them while the relay runs.
- Reply codes: 0x00 success (with the bound address of the upstream
  socket), 0x02 connection not allowed by ruleset (blocked domain),
  0x04 host unreachable (the name did not resolve), 0x05
  connection refused (no address accepted the connection), 0x07
  command not supported (anything other than CONNECT and UDP
  ASSOCIATE), 0x08 address type not supported. An error reply is sent before the connection
  closes; any request bytes the client already sent go unread, so such
  a close may be a reset rather than a clean FIN — the client has the
  reply code by then.
- The greeting and the request must arrive within 10 s of the first
  byte, or the connection is closed.

## SOCKS5 UDP (0.1.0–0.1.1)

- Pure header functions only, no sockets yet: `parse_udp_header`
  and `build_udp_header` in rust/engine/src/udp.rs. A datagram is
  RSV(2) = 0, FRAG(1) = 0, ATYP, DST.ADDR, DST.PORT, DATA. FRAG != 0
  means drop the datagram. `Target` in socks5.rs is `pub(crate)` so
  udp.rs can use it.
- UDP ASSOCIATE (0.1.1): the request's DST fields are read and
  ignored. One UDP socket is bound on the TCP connection's local IP
  (port 0) and its address goes back in the success reply. The
  association runs until the TCP control connection closes or the UDP
  socket sits idle: a datagram from the client's IP is parsed, its
  target resolved with lookup_host, its DATA sent to the target from
  the same socket, and the client's UDP source address remembered;
  a datagram from anyone else gets the sender's address prepended as
  a header and is sent to the remembered client address, and is
  dropped while no client address is known yet. Only the IP that
  opened the association may send. Whole datagrams count into the
  record's up/down counters; the destination host:port is recorded as
  metadata only.

## HTTP (0.0.6–0.0.7)

- CONNECT (0.0.7) opens a tunnel to `host:port` (the host may be an
  IPv6 literal in brackets): the record's target is set as soon as the
  target is parsed, DNS or connect failure gets a 502, success replies
  `HTTP/1.1 200 Connection Established` and any bytes the client
  already sent after the head are forwarded, then both directions are
  relayed untouched with the same counters as SOCKS5. A target on the
  `blocked_domains` list gets a 403 before any outbound connect, and
  the connection is recorded as blocked; plain HTTP forwarding gets
  the same 403 for a blocked host.
- Plain HTTP forwarding, every method other than CONNECT: the request
  line must carry an absolute URI (`http://host[:port]/path`); anything
  else gets a 400. A CONNECT request (authority-form) is a tunnel, not
  a 400.
- The request is rewritten to origin-form, the hop-by-hop headers are
  dropped (`Proxy-Connection`, `Proxy-Authorization`, `Connection`,
  `Keep-Alive`, `TE`, `Trailer`, `Upgrade`), and `Connection: close`
  is added (no keep-alive in this version).
- The engine connects to `host:port` (default 80), sends the rewritten
  head plus any body bytes already read, then relays both directions
  untouched. A target that cannot be reached gets a 502.
- Bodies are never parsed or logged; only the target host/port and the
  byte counts are recorded. The rewritten head is not counted; body
  bytes already read are counted as `up`.
- IPv6 literals in absolute URIs are not supported: the rewrite keeps
  the brackets, so the lookup fails and the client gets a 502.

## Outbound and relay (0.0.5)

- `Connector` (`rust/engine/src/connector.rs`) holds the Android
  Network handle for outbound sockets (`network: AtomicU64`, 0 =
  default network; set with `Engine::set_outbound_network`, reached
  from Kotlin as `nativeSetOutboundNetwork`). `connect_addr` opens
  each outbound TCP socket with `TcpSocket` (v4 or v6 by address) and
  binds it with `netbind::bind_socket_to_network` (0.1.6), which calls
  `android_setsocknetwork` (libandroid) on Android and does nothing on
  other targets. Resolved addresses are tried IPv4 first (stable sort;
  a VPN is often IPv4-only) and each connect attempt is capped at
  5 s, even when the configured timeout is longer.
- **Staggered attempts (0.4.5, RFC 8305 simplified):** the next
  address starts 300 ms after the previous one when nothing has
  connected yet; first success wins and the losers are dropped with
  the JoinSet. The 5 s cap per attempt and the IPv4-first ordering
  are unchanged.
- **IPv6 fail-fast breaker (0.4.5):** a process-wide breaker
  (`V6_DOWN_UNTIL_MS`): a failed or timed-out IPv6 attempt trips it
  for 60 s, a successful one clears it. While down, IPv6 addresses
  are skipped when IPv4 ones exist, and an IPv6-only/literal target
  is refused immediately with `ConnectError::Unreachable`. The
  breaker resets when the outbound network handle changes
  (`set_network`). The pure helpers take the deadline and the clock
  as parameters (`v6_filter(addrs, down_until_ms, now)`), so tests
  move time without sleeping.
- **DNS cache (0.4.5):** resolved addresses are cached per (network
  handle, lowercase host, port) — 60 s positive TTL, 5 s negative
  TTL, max 512 entries (expired entries dropped first, then the
  whole map is cleared). Flushed on engine start and on network
  change; IP literals are never cached. The port is part of the key
  because the cached SocketAddrs carry the port they were resolved
  with.
- **Client sockets get TCP_NODELAY too (0.4.5):** the accept loop
  sets it on every accepted client socket, matching the outbound
  side.
- **DNS uses the outbound network too (0.3.0):** when a network is set
  and the device is API 29+, `Connector::resolve` runs
  `android_getaddrinfofornetwork` (libandroid, loaded with dlsym at
  runtime) inside `spawn_blocking`, converts the addrinfo list to
  `SocketAddr`s and always calls `freeaddrinfo`. Network == 0, API <
  29, or a missing symbol all fall back to `lookup_host`, so DNS never
  leaks through the default network in Wi-Fi/Mobile outbound mode.
  Non-Android builds always use `lookup_host`, so host tests pass.
- **Known limitation:** UDP ASSOCIATE sockets are NOT bound to the
  network yet — only outbound TCP connections and DNS are.
- A connect failure is one of three kinds: the name did not resolve
  (`Dns`), no address accepted the connection (`Connect`), or the
  target is IPv6-only while the IPv6 breaker is down
  (`Unreachable`). The SOCKS5 handler maps them to the reply codes
  0x04, 0x05 and 0x03; the HTTP handler maps every connect failure
  to 502, so `Unreachable` needs no code change there.
- `relay` (`rust/engine/src/relay.rs`) copies bytes both ways with two
  `pump` tasks joined with `tokio::join!`. Each pump counts live into
  the connection record's counters (client -> target is `up`, target ->
  client is `down`), so the snapshot and the totals update during a
  transfer, not only at the end. A pump ends when its read side hits
  EOF, errors, or sits idle for `idle_timeout_s`; it then shuts down
  its write half, so the peer sees EOF and its own pump ends too. When
  either pump ends, the pair is closed. Each pump's buffer starts at
  8 KB and doubles up to 64 KB whenever a read fills it (0.4.5),
  instead of allocating 32 KB per direction up front.
- The counters count relayed payload only, not the SOCKS5 greeting,
  request or reply bytes.

## Config

The engine is configured with a plain text of `key=value` lines, parsed by
`Config::parse` in `rust/engine/src/config.rs`.

| Key | Type | Default | Notes |
| --- | --- | --- | --- |
| `bind_addr` | IP address | `0.0.0.0` | Address the proxy listens on. |
| `port` | 1–65535 | `1080` | Port the proxy listens on. Ignored in split mode. |
| `http_port` | 1–65535 | — (none) | Optional HTTP-only port. When set, split mode: `port` is ignored. |
| `socks_port` | 1–65535 | — (none) | Optional SOCKS4/SOCKS5 port. When set, split mode: `port` is ignored. |
| `protocols` | comma list of `http`, `socks4`, `socks5` | `http,socks4,socks5` | At least one. |
| `auth_user` | string | — (none) | Needs `auth_pass` too. |
| `auth_pass` | string | — (none) | Needs `auth_user` too. |
| `allowed_clients` | comma list of CIDRs | — (none) | Empty means any client, subject to `private_only`. |
| `blocked_domains` | comma list of hosts / `*.suffix` wildcards | — (none) | Empty means none blocked. See "Blocked domains". |
| `private_only` | `true` / `false` | `true` | Only private/link-local clients may connect. |
| `max_connections` | whole number ≥ 1 | `256` | Most connections at once. Capped by the fd budget at start: effective max = min(configured, (nofile_soft - 128) / 2), never below 1. See "Connection limits". |
| `idle_timeout_s` | seconds ≥ 0 | `300` | Idle time before a connection is closed. |
| `connect_timeout_s` | seconds ≥ 0 | `10` | Time allowed to connect to the target. |
| `limit_down_kbps` | whole number ≥ 0 | `0` | Download speed limit in KB/s, total across all connections. 0 = unlimited. See "Speed limit". |
| `limit_up_kbps` | whole number ≥ 0 | `0` | Upload speed limit in KB/s, total across all connections. 0 = unlimited. See "Speed limit". |
| `require_vpn` | `true` / `false` | `false` | Refuse new connections while no VPN is active (the live state arrives through `nativeSetVpnActive`). Existing connections are never closed. See "VPN watchdog". |
| `idle_stop_secs` | seconds ≥ 0 | `0` | Stop the proxy after this many seconds with no active connection. 0 = off. See "Engine-owned stats". |

Example:

```
bind_addr = 0.0.0.0
port = 1080
protocols = http, socks5
allowed_clients = 192.168.1.0/24
private_only = true
```

Parsing rules:

- One `key=value` per line. Blank lines and lines starting with `#` are ignored.
- Whitespace around the key and the value is trimmed; the split is on the first `=`.
- Keys are matched case-sensitively. Unknown keys are ignored.
- A key given twice wins with its last value.
- A bad value is an error naming the line, the key and the value, e.g.
  `config line 2: invalid value for 'port': 'abc' (expected a port 1-65535)`.
- `auth_user` without `auth_pass` (or the reverse) is an error.
- Split mode: when `http_port` or `socks_port` is set, `port` is
  ignored. The HTTP listener exists only when `http_port` is set and
  `http` is in `protocols`; the SOCKS listener exists only when
  `socks_port` is set and at least one of `socks4` / `socks5` is in
  `protocols`. A listener serves only its own protocols; any other
  sniffed protocol on it is closed without an event.
  `http_port == socks_port` is an error, and so is a split config with
  no listener at all.

## Snapshot

`snapshot_text` in `rust/engine/src/model.rs` renders the whole engine as
text. First line:

```
state<TAB>total_up<TAB>total_down<TAB>active<TAB>rejected_limit<TAB>rejected_denied<TAB>rejected_not_allowed<TAB>live_up<TAB>live_down<TAB>devices<TAB>speed_up<TAB>speed_down
```

Then one line per connection:

```
id<TAB>client<TAB>host<TAB>port<TAB>proto<TAB>started_ms<TAB>up<TAB>down<TAB>blocked
```

- `state` is one of `stopped`, `starting`, `running`, `error`. The error
  message is not in the snapshot; it arrives through the event callback.
- `total_up` / `total_down` are the engine's lifetime byte counts; `active`
  is the number of connection lines that follow.
- `rejected_limit` / `rejected_denied` / `rejected_not_allowed` are the
  lifetime totals of connections refused for the connection limit, for a
  denied client, and for a client outside `allowed_clients`. The Kotlin
  parser treats them as optional (older snapshots lack them: 0).
- `live_up` / `live_down` are the engine's lifetime byte counts
  including open connections (the relay adds every chunk as it is
  written); `total_up` / `total_down` count closed connections only,
  so Kotlin displays the live totals and baselines them per session.
  `devices` is the number of distinct client IPs with an open
  connection. `speed_up` / `speed_down` are the latest sampler
  reading in bytes per second (see "Engine-owned stats"). All five
  are optional in the Kotlin parser (older snapshots lack them: 0).
- `client` is the client IP, `host` the target host, `proto` one of `http`,
  `socks4`, `socks5`, `started_ms` the unix time the connection opened in
  milliseconds, `up` / `down` the connection's byte counts, `blocked`
  "1" when the connection was refused for a blocked domain, else "0".
  For a SOCKS5 connection the host and port are empty / 0 until the request is parsed;
  after that they are the target the client asked for.

Example:

```
running	1024	2048	1	0	0	0	1024	2048	1	512	1024
1	192.168.1.5	example.com	443	http	1794500000000	1024	2048	0
```

## Diagnostics log (0.4.2, extended in 0.4.4)

The engine keeps an in-process ring buffer of the last 1000 log lines
(`rust/engine/src/diag.rs`); Kotlin reads it with `nativeDiagLog()`
and clears it with `nativeDiagClear()`. One line per entry, oldest
first:

```
ts_ms<TAB>LEVEL<TAB>message<TAB>count
```

- `ts_ms` is the unix time in milliseconds, `LEVEL` one of `INFO`,
  `WARN`, `ERROR`, `count` how many times this exact line repeated
  in a row (1 when it did not repeat). A repeat — same level and
  message within 60 s — bumps the last line's count and timestamp
  instead of pushing a new line.
- The message never contains a tab or newline (sanitized on write).
- Logged: engine start/stop (start carries a config summary: bind
  address, ports, protocols, private-only, max connections, auth
  on/off, blocked-domain count — never the password or the username),
  bind errors, accept errors, upstream connect failures (target
  host:port and the reason), blocked requests (host and client IP),
  auth failures (client IP only).
- The log never contains traffic payloads (no MITM) or credentials.
  Kotlin redacts anything resembling credentials (`redactLog` in
  `engine/Engine.kt`) before the log is shared from Settings >
  Diagnostics.

## Client control (0.4.3)

- One abort handle per connection task, keyed by the pre-assigned
  connection id (`Engine::close_client`): closing a client aborts
  every open connection from that IP, including ones still sniffing
  (no record yet). The aborted tasks are dropped at their next await
  point, so their own unregister never runs: `close_client` removes
  the records itself and folds the counters into the totals. A client
  that is only disconnected can reconnect immediately.
- `close_client` also appends each removed connection to the engine
  session log (see "Engine-owned stats") and emits the internal
  `ConnectionClosed` event for it; connections closed by the relay
  itself are logged the same way when they unregister.
- `denied_clients`: a runtime-only set of client IPs (never
  persisted), checked in the accept path before `allowed_clients`
  and the connection limit. A denied client is closed without any
  reply and without an event. Deny does NOT close existing
  connections — the UI calls deny first, then close. `stop()` clears
  the set: a block lasts until the proxy restarts. `allow_client`
  lifts a block early.

## Speed limit (0.4.3)

- Config keys `limit_down_kbps` / `limit_up_kbps` (0 = unlimited)
  apply to the TOTAL of all connections in one direction, through
  one shared token bucket per direction (`rust/engine/src/relay.rs`,
  `RateLimiter`): tokens refill at the configured bytes per second,
  capped at max(rate, next chunk) so a chunk bigger than the rate can
  never deadlock. Each pump takes its chunk from its direction's
  bucket after the read and before the write; with no limit
  configured no bucket exists and the pump does not wait.
- UDP ASSOCIATE datagrams are NOT limited (they do not go through
  the relay pumps) — the same carve-out as the blocked-domains check.

## VPN watchdog (0.4.6)

- Config key `require_vpn` (default `false`). When it is on, new
  connections are refused while no VPN is active: SOCKS5 gets reply
  0x02 (connection not allowed by ruleset), SOCKS4 gets 0x5B, HTTP
  (CONNECT and plain) gets 403. The refusal happens after the target
  is set, next to the blocked-domains check, and is logged
  ("refused {host}:{port}: VPN is down (require_vpn)"). Refused
  connections are NOT marked blocked (that flag is for blocked
  domains only) and still appear in the session log with 0 bytes.
  Existing connections are never closed by a VPN drop.
- The live VPN state is pushed from Kotlin through
  `nativeSetVpnActive(h, active)` (see the lifecycle table); the
  controller pushes the current state when the proxy starts and
  every change while it runs. The engine defaults to "no VPN active"
  (fail-closed): with `require_vpn` on, everything is refused until
  Kotlin reports a VPN. UDP ASSOCIATE is refused the same way (the
  check sits in the SOCKS5 handler before the command dispatch).

## Connection limits (0.5.0)

- At start the engine raises the soft RLIMIT_NOFILE to min(hard,
  65536) (`raise_nofile_limit` in `rust/engine/src/engine.rs`,
  libc) and logs the result at INFO: "fd limit: nofile soft N,
  effective max connections M". The effective limit is
  min(config.max_connections, (nofile_soft - 128) / 2), never below
  1 — each proxied connection holds 2 fds (client + upstream), and
  128 fds are kept for listeners, the runtime and everything else.
  The pure cap (`effective_max_connections` in config.rs) has unit
  tests. When the limit cannot be read, the configured value is
  used uncapped.
- Refusals are never silent: the engine counts them per reason
  (limit / denied client / client outside allowed_clients) and
  exposes the three totals as trailing snapshot fields. Each reason
  logs at most once per 5 s: WARN "connection limit reached: N
  rejected in the last 5 s (max M)" (and analogous lines for the
  other two reasons).
- The abort-handle slot is reserved BEFORE the connection task is
  spawned and filled afterwards, so a task that finishes fast can
  no longer leave a stale handle behind; close_client aborts only
  the stored handles.
- JNI events: the worker threads attach permanently
  (`attach_current_thread_permanently`) instead of attaching and
  detaching on every event. Best-effort, never panics.
- Handshake timeout (verified, no change needed): the sniffer waits
  5 s for the first byte, and SOCKS5 negotiation, the SOCKS4
  request and the HTTP head each have a 10 s read timeout, so a
  silent client can never hold a slot until the 300 s idle timeout.

## Engine-owned stats (0.5.1)

Since 0.5.1 the engine owns the live stats, so Kotlin holds no
timers, refcounts or samplers and no JNI upcall fires per
connection. Kotlin reads the snapshot once a second while the UI
is visible (the existing poll loop) and pulls the history and the
session log from the same loop; nothing runs while the proxy is
idle.

- Live counters: every relay chunk is added to engine-wide
  `live_up` / `live_down` atomics as it is written, so the
  snapshot's live totals include open connections; the per-session
  `total_up` / `total_down` still count closed connections only.
  The engine also keeps a distinct-client refcount (IP -> open
  connections) and exposes it as `devices` in the snapshot.
- Speed history: a sampler task starts on the idle -> busy edge
  (active 0 -> 1) and stops after 2 consecutive ticks with no
  active connection; ticks are 1 s tokio intervals. Each tick
  diffs the live counters over the real elapsed time, smooths with
  an EMA (alpha 0.5, snapped to 0 under 64 B/s) and pushes
  `(unix_sec, up_bps, down_bps)` into a ring of 120. Reading
  (`nativeHistory`, `Engine::history`) fills gaps with zeros by
  timestamp, so the returned history is always the last 120 s
  aligned to "now", even if the UI was hidden. The snapshot also
  carries the latest sample as `speed_up` / `speed_down`.
  `start()` clears the history ring.
- Session log: a ring of the last 500 closed-connection records
  with a monotonically increasing id. A record is appended when a
  connection unregisters (normal close or `close_client`); `stop()`
  does not log its drain. One line per record, oldest first:

  ```text
  log_id<TAB>conn_id<TAB>client<TAB>host<TAB>port<TAB>proto<TAB>started_ms<TAB>up<TAB>down<TAB>blocked
  ```

  `nativeSessionLog(h, sinceId)` returns only records with an id
  greater than `sinceId` (the Kotlin side keeps the last seen id
  and appends, capped at 500); `nativeSessionLogClear` clears the
  ring. Ids keep increasing across a clear. The ring survives
  `stop()` / restart.
- Edge events: `Busy` (type 10) fires on the 0 -> 1 active edge,
  `Idle` (type 11) on 1 -> 0, `DevicesChanged` (type 12, a = the
  new distinct-client count) when a client IP gains or loses its
  last open connection. They fire only on the edge.
- Idle auto-stop: config `idle_stop_secs` (0 = off). On the Idle
  edge the engine starts a tokio sleep of that length, cancelled
  by a Busy edge; on expiry it emits `IdleStop` (type 13) and
  Kotlin stops the proxy. The 60 s wake-lock grace stays in
  Kotlin, driven by the same Busy / Idle edges.

## Validation (0.5.2)

Settings text is validated once, in Rust (`rust/engine/src/validate.rs`),
through `nativeValidate(kind, value)`. Kotlin used to validate
CIDR/domain/port itself while Rust validated the same values again in
`cidr`/`config`, and the two could drift; now Kotlin calls this one
function and keeps only the error-code -> string-resource map.

- Kinds: 0 = CIDR/IP, 1 = domain pattern, 2 = port.
- Returns `""` when valid, or a stable error code: `empty`,
  `has_scheme` (contains `://`), `has_path` (contains `/`),
  `bad_cidr`, `bad_port`, `bad_domain`, `bad_kind` (unknown kind, a
  caller bug).
- CIDR: `Cidr::parse` is the authority, so IPv6 works too
  (`2001:db8::/32`, bare `::1`); the old Kotlin validator only knew
  IPv4. Port: 1024-65535 (no privileged ports) — the stricter UI
  rule; the engine config itself accepts 1-65535. Domain: an
  optional leading `*.` wildcard, then labels of letters, digits
  and `-` (not leading/trailing); uppercase and a trailing dot are
  accepted; a single label is accepted.
- Bulk "Paste list" validates each line through the same call, one
  JNI call per line (not a hot path).

## Events

The engine reports things to Kotlin through one callback,
`onEvent(type: Int, a: String, b: String)`. The type numbers are stable and
are never renumbered. `a` and `b` are always strings; an empty string means
"no value".

| type | Event | a | b |
| --- | --- | --- | --- |
| 1 | State changed | new state name (`stopped`, `starting`, `running`, `error`) | error message, or `""` |
| 2 | Connection opened | connection id | snapshot line of that connection |
| 3 | Connection closed | connection id | final snapshot line of that connection |
| 4 | Handler missing | connection id | snapshot line of that connection |
| 10 | Busy | `""` | `""` |
| 11 | Idle | `""` | `""` |
| 12 | Devices changed | new distinct-client count | `""` |
| 13 | Idle stop | `""` | `""` |

Types 2 and 3 are Rust-internal only since 0.5.1: the `Event`
variants and their tests still exist, but `JniListener` does not
forward them through the callback (Kotlin reads the session log
and the snapshot instead). Types 10–13 are the edge events from
"Engine-owned stats".

## Rules

- No proxy logic in Kotlin. Kotlin holds only this thin JNI wrapper.
- No MITM: the engine never inspects or decrypts traffic payloads. Only
  metadata (client IP, target host:port, protocol, byte counts) may be
  recorded or passed to Kotlin.
- `unsafe` only at the FFI boundary, each block commented.
- The Kotlin package name is fixed (`com.example.proxyshare`): JNI symbol
  names depend on it.
