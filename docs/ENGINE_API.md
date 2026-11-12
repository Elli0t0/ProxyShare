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
- Returns the engine version string, e.g. `"engine 0.0.3"`.
- On failure (including a Rust panic) it returns null instead of letting an
  exception cross the FFI boundary; every JNI entry point wraps its body in
  `catch_unwind`.

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
  milliseconds, `up` / `down` the connection's byte counts.

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

This is the fixed mapping of the `Event` enum in `rust/engine/src/model.rs`
(`Event::to_callback`). The JNI callback registration — Kotlin handing the
engine its `onEvent` — and the JNI getters for the config and the snapshot
land with the engine start in a later step; the formats above are decided
now so both sides can build against them.

## Rules

- No proxy logic in Kotlin. Kotlin holds only this thin JNI wrapper.
- No MITM: the engine never inspects or decrypts traffic payloads. Only
  metadata (client IP, target host:port, protocol, byte counts) may be
  recorded or passed to Kotlin.
- `unsafe` only at the FFI boundary, each block commented.
- The Kotlin package name is fixed (`com.example.proxyshare`): JNI symbol
  names depend on it.
