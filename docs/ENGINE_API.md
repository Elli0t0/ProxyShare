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
- Returns the engine version string, e.g. `"engine 0.0.9"`.
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
| `nativeStop(h: Long)` | `Engine::stop` | Stops listening, cancels the accept task and closes the sockets (within 2 s). Safe on a stopped engine. |
| `nativeDestroy(h: Long)` | stops the engine and removes it from the handle table | Unknown handles are a no-op. |
| `nativeSnapshot(h: Long): String` | `Engine::snapshot` | The snapshot text (below), or `""` for an unknown handle. |
| `nativeSetListener(h: Long, listener: EngineListener)` | builds a `JniListener` and registers it as the engine's event sink | `EngineListener` is a Kotlin interface `{ fun onEvent(type: Int, a: String, b: String) }`. The Rust side caches the `JavaVM`, holds a `GlobalRef` to the listener and caches the `onEvent` method ID. Events are sent from the tokio worker threads ("ps-worker") with `attach_current_thread`; `onEvent` runs on that worker thread, so the Kotlin side must post to the main thread before touching UI or Compose state. |

## Server (0.0.4, extended in 0.0.5)

- `start` binds one `TcpListener` through socket2 (`SO_REUSEADDR`,
  backlog 128) and runs an accept loop on the global tokio runtime
  (`Builder::new_multi_thread`, 2 worker threads named "ps-worker").
- A client is accepted only when it passes the config's client filter
  (`allowed_clients` list, `private_only`); others are dropped with no
  event. The accept loop also enforces `max_connections` (counting every
  connection task currently running, including in-flight sniffs); further
  connections are dropped with no event.
- Each accepted connection is handled by its own tokio task: the first
  byte is peeked (up to 5 s) and classified — `0x04` SOCKS4, `0x05`
  SOCKS5, anything else HTTP. A sniffed protocol that is not enabled in
  the config closes the connection quietly. The peek does not consume
  the byte, so the handler that runs next still sees the whole stream;
  a path where no handler reads the stream consumes the byte first, so
  the close is a clean FIN instead of a reset.
- SOCKS5 connections are handled (see below): a `ConnectionOpened`
  event (type 2) is emitted when the connection is registered, and a
  `ConnectionClosed` event (type 3) with the final byte counts when it
  ends. HTTP and SOCKS4 have no handler yet: a sniffed connection is
  registered, its sniffed byte is consumed, it is reported as a
  `handler_missing` event (type 4), and it is closed cleanly.

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

- CONNECT only (CD = 1); anything else gets a 0x5B reply. Request:
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
  (0x01) only. The target host and port are recorded on the connection
  as soon as the request is parsed, so the snapshot shows them while
  the relay runs.
- Reply codes: 0x00 success (with the bound address of the upstream
  socket), 0x04 host unreachable (the name did not resolve), 0x05
  connection refused (no address accepted the connection), 0x07
  command not supported (anything other than CONNECT), 0x08 address
  type not supported. An error reply is sent before the connection
  closes; any request bytes the client already sent go unread, so such
  a close may be a reset rather than a clean FIN — the client has the
  reply code by then.
- The greeting and the request must arrive within 10 s of the first
  byte, or the connection is closed.

## HTTP (0.0.6–0.0.7)

- CONNECT (0.0.7) opens a tunnel to `host:port` (the host may be an
  IPv6 literal in brackets): the record's target is set as soon as the
  target is parsed, DNS or connect failure gets a 502, success replies
  `HTTP/1.1 200 Connection Established` and any bytes the client
  already sent after the head are forwarded, then both directions are
  relayed untouched with the same counters as SOCKS5.
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

- `Connector` (`rust/engine/src/connector.rs`) is a plain struct; 0.1.1
  adds a network-handle field to it.
- A connect failure is one of two kinds: the name did not resolve
  (`Dns`) or no address accepted the connection (`Connect`). The
  SOCKS5 handler maps them to the reply codes 0x04 and 0x05.
- `relay` (`rust/engine/src/relay.rs`) copies bytes both ways with two
  `pump` tasks joined with `tokio::join!`. Each pump counts live into
  the connection record's counters (client -> target is `up`, target ->
  client is `down`), so the snapshot and the totals update during a
  transfer, not only at the end. A pump ends when its read side hits
  EOF, errors, or sits idle for `idle_timeout_s`; it then shuts down
  its write half, so the peer sees EOF and its own pump ends too. When
  either pump ends, the pair is closed.
- The counters count relayed payload only, not the SOCKS5 greeting,
  request or reply bytes.

## Config

The engine is configured with a plain text of `key=value` lines, parsed by
`Config::parse` in `rust/engine/src/config.rs`.

| Key | Type | Default | Notes |
| --- | --- | --- | --- |
| `bind_addr` | IP address | `0.0.0.0` | Address the proxy listens on. |
| `port` | 1–65535 | `1080` | Port the proxy listens on. |
| `protocols` | comma list of `http`, `socks4`, `socks5` | `http,socks4,socks5` | At least one. |
| `auth_user` | string | — (none) | Needs `auth_pass` too. |
| `auth_pass` | string | — (none) | Needs `auth_user` too. |
| `allowed_clients` | comma list of CIDRs | — (none) | Empty means any client, subject to `private_only`. |
| `private_only` | `true` / `false` | `true` | Only private/link-local clients may connect. |
| `max_connections` | whole number ≥ 1 | `64` | Most connections at once. |
| `idle_timeout_s` | seconds ≥ 0 | `300` | Idle time before a connection is closed. |
| `connect_timeout_s` | seconds ≥ 0 | `10` | Time allowed to connect to the target. |

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

## Snapshot

`snapshot_text` in `rust/engine/src/model.rs` renders the whole engine as
text. First line:

```
state<TAB>total_up<TAB>total_down<TAB>active
```

Then one line per connection:

```
id<TAB>client<TAB>host<TAB>port<TAB>proto<TAB>started_ms<TAB>up<TAB>down
```

- `state` is one of `stopped`, `starting`, `running`, `error`. The error
  message is not in the snapshot; it arrives through the event callback.
- `total_up` / `total_down` are the engine's lifetime byte counts; `active`
  is the number of connection lines that follow.
- `client` is the client IP, `host` the target host, `proto` one of `http`,
  `socks4`, `socks5`, `started_ms` the unix time the connection opened in
  milliseconds, `up` / `down` the connection's byte counts. For a SOCKS5
  connection the host and port are empty / 0 until the request is parsed;
  after that they are the target the client asked for.

Example:

```
running	1024	2048	1
1	192.168.1.5	example.com	443	http	1794500000000	1024	2048
```

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

This is the fixed mapping of the `Event` enum in `rust/engine/src/model.rs`
(`Event::to_callback`). The callback is registered with
`nativeSetListener`; see the lifecycle table above.

## Rules

- No proxy logic in Kotlin. Kotlin holds only this thin JNI wrapper.
- No MITM: the engine never inspects or decrypts traffic payloads. Only
  metadata (client IP, target host:port, protocol, byte counts) may be
  recorded or passed to Kotlin.
- `unsafe` only at the FFI boundary, each block commented.
- The Kotlin package name is fixed (`com.example.proxyshare`): JNI symbol
  names depend on it.
