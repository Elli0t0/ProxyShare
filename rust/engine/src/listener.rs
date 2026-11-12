//! The JNI event listener: calls back into Kotlin's EngineListener.
//!
//! The listener is built once (nativeSetListener) and then used from the
//! tokio worker threads. It holds the cached JavaVM, a GlobalRef to the
//! Kotlin listener object, and the cached onEvent method ID. Events are
//! best-effort: a failed attach or call is dropped, never panicked.

use jni::objects::{GlobalRef, JMethodID, JObject};
use jni::signature::{Primitive, ReturnType};
use jni::sys::jvalue;
use jni::{JavaVM, JNIEnv};

use crate::model::{Event, EventSink};

/// The signature of EngineListener.onEvent.
const ON_EVENT_SIG: &str = "(ILjava/lang/String;Ljava/lang/String;)V";

pub struct JniListener {
    vm: JavaVM,
    obj: GlobalRef,
    method: JMethodID,
}

impl JniListener {
    /// Build a listener from the Kotlin EngineListener object. `env` is
    /// the JNIEnv of the nativeSetListener call. Fails when the JavaVM
    /// cannot be cached, the global reference cannot be made, or the
    /// interface has no onEvent with the expected signature.
    pub fn new(env: &mut JNIEnv, listener: &JObject) -> Result<Self, String> {
        let vm = env
            .get_java_vm()
            .map_err(|e| format!("failed to cache the JavaVM: {e}"))?;
        let obj = env
            .new_global_ref(listener)
            .map_err(|e| format!("failed to keep the listener: {e}"))?;
        let method = env
            .get_method_id(
                "com/example/proxyshare/engine/EngineListener",
                "onEvent",
                ON_EVENT_SIG,
            )
            .map_err(|e| format!("failed to find EngineListener.onEvent: {e}"))?;
        Ok(Self { vm, obj, method })
    }
}

impl EventSink for JniListener {
    fn on_event(&self, event: Event) {
        let (t, a, b) = event.to_callback();
        // Attach this worker thread (a no-op if it is already attached);
        // the guard detaches it again when it drops.
        let mut guard = match self.vm.attach_current_thread() {
            Ok(guard) => guard,
            Err(_) => return, // The JVM is going away: drop the event.
        };
        let env: &mut JNIEnv = &mut guard;
        let a_str = match env.new_string(a.as_str()) {
            Ok(s) => s,
            Err(_) => {
                let _ = env.exception_clear();
                return;
            }
        };
        let b_str = match env.new_string(b.as_str()) {
            Ok(s) => s,
            Err(_) => {
                let _ = env.exception_clear();
                return;
            }
        };
        // SAFETY: `method` is the onEvent method ID of the EngineListener
        // interface, looked up above with the matching signature
        // "(ILjava/lang/String;Ljava/lang/String;)V", so the void return
        // type and the (int, String, String) arguments match, and both
        // string arguments are live local references in this frame.
        let args = [
            jvalue { i: t },
            jvalue { l: a_str.as_raw() },
            jvalue { l: b_str.as_raw() },
        ];
        let _ = unsafe {
            env.call_method_unchecked(
                self.obj.as_obj(),
                self.method,
                ReturnType::Primitive(Primitive::Void),
                &args,
            )
        };
        // If the listener threw, clear the exception so the VM does not
        // carry a pending exception back into Rust.
        let _ = env.exception_clear();
    }
}
