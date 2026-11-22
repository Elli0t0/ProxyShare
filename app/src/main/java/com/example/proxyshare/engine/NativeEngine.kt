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

    /** Set the Android Network handle for outbound TCP connections (0 = default network). */
    @JvmStatic
    external fun nativeSetOutboundNetwork(handle: Long, network: Long)

    /** Tell the engine whether a VPN network is active (only read when the config sets require_vpn). */
    @JvmStatic
    external fun nativeSetVpnActive(handle: Long, active: Boolean)

    /** Abort every open connection from one client IP (see docs/ENGINE_API.md). */
    @JvmStatic
    external fun nativeCloseClient(handle: Long, ip: String)

    /** Refuse a client until the proxy restarts (existing connections stay open). */
    @JvmStatic
    external fun nativeDenyClient(handle: Long, ip: String)

    /** Lift a block set by nativeDenyClient. */
    @JvmStatic
    external fun nativeAllowClient(handle: Long, ip: String)

    /** Stop the engine if running and release its handle. */
    @JvmStatic
    external fun nativeDestroy(handle: Long)

    /** The engine snapshot text (see docs/ENGINE_API.md), or "" for a bad handle. */
    @JvmStatic
    external fun nativeSnapshot(handle: Long): String

    /** The diagnostics log text (see docs/ENGINE_API.md), or "" when empty. */
    @JvmStatic
    external fun nativeDiagLog(): String

    /** The last 120 seconds of speed as "up,down;up,down;…" oldest first (see docs/ENGINE_API.md), or "" for a bad handle. */
    @JvmStatic
    external fun nativeHistory(handle: Long): String

    /** Session-log records newer than `sinceId`, one "log_id<TAB><snapshot line>" per line, oldest first (see docs/ENGINE_API.md), or "" when nothing is newer or the handle is bad. */
    @JvmStatic
    external fun nativeSessionLog(handle: Long, sinceId: Long): String

    /** Drop every session-log record. Safe on a bad handle (no-op). */
    @JvmStatic
    external fun nativeSessionLogClear(handle: Long)

    /** Clear the diagnostics ring buffer (Logs screen "Clear"). */
    @JvmStatic
    external fun nativeDiagClear()

    /** Register the callback that receives engine events. */
    @JvmStatic
    external fun nativeSetListener(handle: Long, listener: EngineListener)
}
