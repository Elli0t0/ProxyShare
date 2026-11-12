package com.example.proxyshare.engine

/** Thin JNI wrapper for the Rust engine (libproxyshare_engine.so). No proxy logic here. */
object NativeEngine {
    init {
        System.loadLibrary("proxyshare_engine")
    }

    @JvmStatic
    external fun nativeVersion(): String
}
