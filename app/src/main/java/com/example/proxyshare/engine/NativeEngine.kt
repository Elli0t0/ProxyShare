package com.example.proxyshare.engine

/** Thin JNI wrapper for the Rust engine (libproxyshare_engine.so). No proxy logic here. */
object NativeEngine {
    init {
        System.loadLibrary("proxyshare_engine")
    }

    @JvmStatic
    external fun nativeVersion(): String

    /** Create an engine and return its handle (> 0). Destroy it with nativeDestroy. */
    @JvmStatic
    external fun nativeCreate(): Long

    /**
     * Start the engine with a "key=value" config text (see docs/ENGINE_API.md).
     * Returns "" on success, or an error text on failure.
     */
    @JvmStatic
    external fun nativeStart(handle: Long, config: String): String

    /** Stop the engine. Safe to call on a stopped or destroyed engine. */
    @JvmStatic
    external fun nativeStop(handle: Long)

    /** Stop the engine if running and release its handle. */
    @JvmStatic
    external fun nativeDestroy(handle: Long)

    /** The engine snapshot text (see docs/ENGINE_API.md), or "" for a bad handle. */
    @JvmStatic
    external fun nativeSnapshot(handle: Long): String

    /** Register the callback that receives engine events. */
    @JvmStatic
    external fun nativeSetListener(handle: Long, listener: EngineListener)
}
