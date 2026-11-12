# Changelog

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
