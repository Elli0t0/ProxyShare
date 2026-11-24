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
//! connections), [`diag`] (in-process diagnostics ring buffer, last
//! 300 log lines), [`relay`] (byte pumps between client and target),
//! [`socks4`] (SOCKS4/4a CONNECT), [`socks5`] (SOCKS5 negotiation and
//! CONNECT), [`udp`] (SOCKS5 UDP datagram header parse/build, pure
//! functions), [`http`] (plain HTTP forwarding and CONNECT tunnels),
//! [`listener`] (the JNI event callback), [`netbind`] (bind an outbound
//! socket to an Android Network), [`history`] (speed history ring and
//! sampler), [`sessionlog`] (session log ring), [`validate`] (the
//! single validation source for settings text: CIDR, domain, port).

pub mod auth;
pub mod cidr;
pub mod config;
pub mod connector;
pub mod diag;
pub mod engine;
pub mod history;
pub mod http;
pub mod listener;
pub mod model;
pub mod netbind;
pub mod relay;
pub mod sessionlog;
pub mod sniffer;
pub mod socks4;
pub mod socks5;
pub mod udp;
pub mod validate;

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;

use crate::engine::{create_engine, destroy_engine, with_engine};
use crate::listener::JniListener;

/// The engine version string, e.g. "engine 0.5.2".
pub const ENGINE_VERSION: &str = "engine 0.5.8";

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
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeVersion<'local>(
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
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeCreate<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    catch_unwind(AssertUnwindSafe(create_engine)).unwrap_or_default()
}

/// Start the engine with a "key=value" config text. Returns "" on success,
/// or an error text on failure. An unknown handle is an error.
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeStart<'local>(
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
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeStop<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        with_engine(handle, |engine| engine.stop());
    }));
}

/// Abort every open connection from one client IP (see
/// docs/ENGINE_API.md). Safe on an unknown handle or a malformed IP
/// (no-op).
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeCloseClient<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    ip: JString<'local>,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let Ok(text) = from_jstring(&mut env, &ip) else {
            let _ = env.exception_clear();
            return;
        };
        with_engine(handle, |engine| engine.close_client(&text));
    }));
}

/// Refuse a client until the proxy restarts (see docs/ENGINE_API.md).
/// Existing connections are NOT closed: call nativeCloseClient for
/// that. Safe on an unknown handle or a malformed IP (no-op).
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeDenyClient<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    ip: JString<'local>,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let Ok(text) = from_jstring(&mut env, &ip) else {
            let _ = env.exception_clear();
            return;
        };
        with_engine(handle, |engine| engine.deny_client(&text));
    }));
}

/// Lift a block set by nativeDenyClient. Safe on an unknown handle or
/// a malformed IP (no-op).
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeAllowClient<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    ip: JString<'local>,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let Ok(text) = from_jstring(&mut env, &ip) else {
            let _ = env.exception_clear();
            return;
        };
        with_engine(handle, |engine| engine.allow_client(&text));
    }));
}

/// Set the Android Network handle (net_handle_t) for outbound TCP
/// connections. 0 means the default network. Safe on a stopped or
/// unknown handle (no-op).
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeSetOutboundNetwork<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    network: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        with_engine(handle, |engine| engine.set_outbound_network(network as u64));
    }));
}

/// Tell the engine whether a VPN network is active right now (see
/// docs/ENGINE_API.md). Only consulted when the config sets
/// require_vpn. Safe on a stopped or unknown handle (no-op).
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeSetVpnActive<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    active: jboolean,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        with_engine(handle, |engine| engine.set_vpn_active(active != 0));
    }));
}

/// Stop the engine if running and release its handle. An unknown handle
/// is a no-op.
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeDestroy<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| destroy_engine(handle)));
}

/// The engine snapshot text (see docs/ENGINE_API.md), or "" for an
/// unknown handle.
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeSnapshot<'local>(
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

/// The last 120 seconds of speed as "up,down;up,down;…" oldest first
/// (see docs/ENGINE_API.md), or "" for an unknown handle.
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeHistory<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        match with_engine(handle, |engine| engine.history()) {
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

/// Session-log records newer than `since_id`, one
/// "log_id\t<snapshot line>" per line, oldest first (see
/// docs/ENGINE_API.md), or "" when nothing is newer or the handle is
/// unknown.
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeSessionLog<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    since_id: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        match with_engine(handle, |engine| engine.session_log_since(since_id as u64)) {
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

/// Drop every session-log record. Safe on an unknown handle (no-op).
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeSessionLogClear<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        with_engine(handle, |engine| engine.clear_session_log());
    }));
}

/// The diagnostics log text (see docs/ENGINE_API.md): one
/// `ts_ms<TAB>LEVEL<TAB>message<TAB>count` line per entry, oldest first,
/// or "" when empty. Never lets a panic or exception cross the FFI boundary.
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeDiagLog<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| to_jstring(&env, &crate::diag::snapshot_text())));
    match result {
        Ok(s) => s,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// Clear the diagnostics ring buffer (Logs screen "Clear"). No handle:
/// the buffer is global, not per-engine.
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeDiagClear<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    let result = catch_unwind(AssertUnwindSafe(crate::diag::clear));
    if result.is_err() {
        let _ = env.exception_clear();
    }
}

/// Validate one settings value in Rust, the single validation source
/// (0.5.2): `kind` 0 = CIDR/IP, 1 = domain pattern, 2 = port.
/// Returns "" when valid, or a stable error code ("empty",
/// "has_scheme", "has_path", "bad_cidr", "bad_port", "bad_domain",
/// "bad_kind"). Kotlin maps the code to a string resource and holds
/// no validation logic of its own. Takes no handle: validation is
/// pure, not per-engine.
#[no_mangle]
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeValidate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    kind: jint,
    value: JString<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let text = match from_jstring(&mut env, &value) {
            Ok(text) => text,
            Err(e) => return to_jstring(&env, &e),
        };
        to_jstring(&env, crate::validate::validate(kind, &text))
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
pub extern "system" fn Java_io_github_elli0t0_proxyshare_engine_NativeEngine_nativeSetListener<'local>(
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
