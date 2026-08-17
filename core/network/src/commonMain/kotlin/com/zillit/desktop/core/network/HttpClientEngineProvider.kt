package com.zillit.desktop.core.network

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * Supplies the platform HTTP engine.
 *
 * Indirection exists so tests can inject Ktor's `MockEngine` without the
 * production code knowing about it, and so adding a `wasmJs` target later
 * (plan §1) is a new implementation rather than an edit here.
 */
fun interface HttpClientEngineProvider {
    fun engine(): HttpClientEngineFactory<*>
}
