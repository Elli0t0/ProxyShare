# Changelog

## 0.1.0 — Project scaffold

- Repo + Gradle project: AGP 9.3.1, Kotlin 2.2.10, Compose BOM 2024.09.00,
  minSdk 26, targetSdk 36, package `com.example.proxyshare`.
- Layout `core/` / `service/` / `data/` / `ui/` (screens, theme, components).
- Minimal home screen, Material 3 dynamic color (Android 12+), light/dark/
  system theme, RTL, strings in English + Persian.
- DataStore + Coroutines + lifecycle-viewmodel dependencies ready.
- CI builds the debug APK; persistent debug signing via `PROXYSHARE_*`
  secrets (workflow generates a one-time key + artifact until they exist).
- No proxy engine yet — that is Step 1.
