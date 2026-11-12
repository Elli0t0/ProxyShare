# Changelog

## 0.0.1 — Kotlin skeleton

- Folders engine/ service/ data/ ui/theme ui/components ui/screens; no core/
  folder (its empty placeholder is removed separately — the push tool cannot
  delete files).
- Dependencies only: Compose BOM, material3, activity-compose,
  lifecycle-runtime-compose, kotlinx-coroutines-android, datastore-preferences.
- ProxyShareTheme: System/Light/Dark, dynamic color on Android 12+, fallback
  palette below that, large rounded shapes, edge-to-edge.
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
