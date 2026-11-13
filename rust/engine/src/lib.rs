//! Thin JNI boundary for the ProxyShare engine.
//!
//! The engine itself lives here in Rust. Kotlin calls in through the
//! `NativeEngine` object only; no proxy logic exists in Kotlin.
//! This file exposes the engine lifecycle (create/start/stop/destroy,
//! snapshot, listener) plus the version. It never inspects traffic
//! payloads (no MITM): only metadata (client IP, target host:port, protocol,
//! byte counts) may ever leave the engine.
//!
//! The engine's types live in sibling modules: [`model`] (protocols,
//! connections, state, events), [`config`] (key=value configuration),
//! [`cidr`] (client network ranges), [`engine`] (runtime, listener,
//! accept loop, handle table), [`sniffer`] (first-byte protocol sniff),
//! [`auth`] (proxy authentication), [`connector`] (outbound
//! connections), [`relay`] (byte pumps between client and target),
//! [`socks4`] (SOCKS4/4a CONNECT), [`socks5`] (SOCKS5 negotiation and
//! CONNECT), [`udp`] (SOCKS5 UDP datagram header parse/build, pure
//! functions), [`http`] (plain HTTP forwarding and CONNECT tunnels),
//! [`listener`] (the JNI event callback), [`netbind`] (bind an outbound
//! socket to an Android Network).

pub mod auth;
pub mod cidr;
pub mod config;
pub mod connector;
pub mod engine;
pub mod http;
pub mod listener;
pub mod model;
pub mod netbind;
pub mod relay;
pub mod sniffer;
pub mod socks4;
pub mod socks5;
pub mod udp;

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;

use crate::engine::{create_engine, destroy_engine, with_engine};
use crate::listener::JniListener;

/// The engine version string, e.g. "engine 0.1.6".
pub const ENGINE_VERSION: &str = "engine 0.1.6";

/// Convert a Rust &str to a Java string (null on failure).
fn to_jstring(env: &JNIEnv, text: &str) -> jstring {
    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Read a Java string into a Rust String.
fn from_jstring(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s)
        .map(String::from)
        .map_err(|e| format!("failed to read a string from Java: {e}"))
}

/// Returns the engine version string, e.g. "engine 0.1.5".
///
/// Never lets a panic or exception cross the FFI boundary: a panic is caught
/// and a null jstring is returned instead.
#[no_mangle]
pub extern "system" fn Java_com_example_proxyshare_engine_NativeEngine_nativeVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    // FFI boundary: catch_unwind keeps a panic inside Rust. JNIEnv is not
    // UnwindSafe, so the closure is wrapped in AssertUnwindSafe.
    let result = catch_unwind(AssertUnwindSafe(|| to_jstring(&env, ENGINE_VERSION)));
    match result {
        Ok(ptr) => ptr,
        // A panic above means the version string could not be built; null
        // tells the JVM side the call failed instead of unwinding across JNI.
        Err(_) => std::ptr::null_mut(),
    }
}

/// Create an engine and return its handle (> 0). Destroy it with
/// nativeDestroy. Returns 0 if the engine could not be created.
#[no_mangle]
pub extern "system" fn Java_com_example_proxyshare_engine_NativeEngine_nativeCreate<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    catch_unwind(AssertUnwindSafe(create_engine)).unwrap_or_default()
}

/// Start the engine with a "key=value" config text. Returns "" on success,
/// or an error text on failure. An unknown handle is an error.
#[no_mangle]
pub extern "system" fn Java_com_example_proxyshare_engine_NativeEngine_nativeStart<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    config: JString<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let text = match from_jstring(&mut env, &config) {
            Ok(text) => text,
            Err(e) => return to_jstring(&env, &e),
        };
        match with_engine(handle, |engine| engine.start(&text)) {
            Some(Ok(())) => to_jstring(&env, ""),
            Some(Err(e)) => to_jstring(&env, &e),
            None => to_jstring(&env, &format!("invalid engine handle {handle}")),
        }
    }));
    match result {
        Ok(s) => s,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// Stop the engine. Safe on a stopped or unknown handle (no-op).
#[no_mangle]
pub extern "system" fn Java_com_example_proxyshare_engine_NativeEngine_nativeStop<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        with_engine(handle, |engine| engine.stop());
    }));
}

/// Set the Android Network handle (net_handle_t) for outbound TCP
/// connections. 0 means the default network. Safe on a stopped or
/// unknown handle (no-op).
#[no_mangle]
pub extern "system" fn Java_com_example_proxyshare_engine_NativeEngine_nativeSetOutboundNetwork<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    network: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        with_engine(handle, |engine| engine.set_outbound_network(network as u64));
    }));
}

/// Stop the engine if running and release its handle. An unknown handle
/// is a no-op.
#[no_mangle]
pub extern "system" fn Java_com_example_proxyshare_engine_NativeEngine_nativeDestroy<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| destroy_engine(handle)));
}

/// The engine snapshot text (see docs/ENGINE_API.md), or "" for an
/// unknown handle.
#[no_mangle]
pub extern "system" fn Java_com_example_proxyshare_engine_NativeEngine_nativeSnapshot<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        match with_engine(handle, |engine| engine.snapshot()) {
            Some(text) => to_jstring(&env, &text),
            None => to_jstring(&env, ""),
        }
    }));
    match result {
        Ok(s) => s,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// Register the callback that receives engine events. The listener is an
/// EngineListener; the Rust side keeps it as a GlobalRef. An unknown
/// handle, or a listener that cannot be kept, is a silent no-op.
#[no_mangle]
pub extern "system" fn Java_com_example_proxyshare_engine_NativeEngine_nativeSetListener<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    listener: JObject<'local>,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let sink = match JniListener::new(&mut env, &listener) {
            Ok(sink) => std::sync::Arc::new(sink),
            Err(_) => {
                let _ = env.exception_clear();
                return;
            }
        };
        with_engine(handle, |engine| engine.set_sink(sink));
    }));
}
