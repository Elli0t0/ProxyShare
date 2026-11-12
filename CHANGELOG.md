# Changelog

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
- FIX (same version, run #8 red): clippy -D warnings failed on
  `unused_mut` — in jni 0.21, JNIEnv::new_string takes &self, so the
  `mut` on the env parameter from the spec snippet is unused. Dropped it.
  Also moved [profile.release] to the workspace root rust/Cargo.toml:
  cargo ignores profiles in a non-root member, so the engine's release
  profile (opt-level z, LTO, strip) was silently ignored.
- FIX: the 0.0.1 release APK shipped unsigned
  (ProxyShare-0.0.1-release-unsigned.apk) — the persistent signing config was
  only applied to the debug build type, so assembleRelease never signed it
  and the phone refused it ("signature cannot be verified"). The release
  build type now uses the same persistent key.

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
