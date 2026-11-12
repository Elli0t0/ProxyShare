//! Thin JNI boundary for the ProxyShare engine.
//!
//! The engine itself lives here in Rust. Kotlin calls in through the
//! `NativeEngine` object only; no proxy logic exists in Kotlin.
//! This file exposes version information only. It never inspects traffic
//! payloads (no MITM): only metadata (client IP, target host:port, protocol,
//! byte counts) may ever leave the engine, and that comes in a later step.

use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;

/// Returns the engine version string, e.g. "engine 0.0.2".
///
/// Never lets a panic or exception cross the FFI boundary: a panic is caught
/// and a null jstring is returned instead.
#[no_mangle]
pub extern "system" fn Java_com_example_proxyshare_engine_NativeEngine_nativeVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    // FFI boundary: catch_unwind keeps a panic inside Rust. JNIEnv is not
    // UnwindSafe, so the closure is wrapped in AssertUnwindSafe.
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        env.new_string("engine 0.0.2")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }));
    match result {
        Ok(ptr) => ptr,
        // A panic above means the version string could not be built; null
        // tells the JVM side the call failed instead of unwinding across JNI.
        Err(_) => std::ptr::null_mut(),
    }
}
