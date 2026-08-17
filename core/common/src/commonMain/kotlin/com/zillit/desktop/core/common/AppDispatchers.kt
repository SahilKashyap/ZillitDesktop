package com.zillit.desktop.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injected dispatchers, so tests can swap in a test scheduler rather than
 * relying on real threads.
 *
 * The Android app reaches for `Dispatchers.IO` inline and wraps blocking calls
 * in `runBlocking`; on desktop that freezes the UI thread (plan §11.2), so
 * blocking work goes through [io] and nothing blocks [main].
 */
data class AppDispatchers(
    val main: CoroutineDispatcher = Dispatchers.Main,
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
)
