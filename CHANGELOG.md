# Changelog

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
