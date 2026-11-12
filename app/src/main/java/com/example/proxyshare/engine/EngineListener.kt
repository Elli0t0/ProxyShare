package com.example.proxyshare.engine

/**
 * Receives engine events from the Rust side.
 *
 * onEvent is invoked on a Rust worker thread ("ps-worker"), not the main
 * thread: post to the main thread before touching UI or Compose state.
 * See docs/ENGINE_API.md for the event type numbers.
 */
interface EngineListener {
    fun onEvent(type: Int, a: String, b: String)
}
