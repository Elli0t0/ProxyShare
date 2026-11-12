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
- Rust: `pub extern "system" fn ... (mut env: JNIEnv, _class: JClass) -> jstring`
- Returns the engine version string, e.g. `"engine 0.0.2"`.
- On failure (including a Rust panic) it returns null instead of letting an
  exception cross the FFI boundary; every JNI entry point wraps its body in
  `catch_unwind`.

## Rules

- No proxy logic in Kotlin. Kotlin holds only this thin JNI wrapper.
- No MITM: the engine never inspects or decrypts traffic payloads. Only
  metadata (client IP, target host:port, protocol, byte counts) may be
  recorded or passed to Kotlin.
- `unsafe` only at the FFI boundary, each block commented.
- The Kotlin package name is fixed (`com.example.proxyshare`): JNI symbol
  names depend on it.
