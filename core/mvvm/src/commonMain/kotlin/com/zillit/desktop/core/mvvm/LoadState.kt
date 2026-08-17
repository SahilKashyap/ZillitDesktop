package com.zillit.desktop.core.mvvm

import com.zillit.desktop.core.common.ZillitError

/**
 * The load lifecycle of one piece of screen content.
 *
 * Every list and detail screen in the app has this same shape, so it is modelled
 * once. [Refreshing] is distinct from [Loading] because the two render
 * differently: a first load shows a skeleton, a refresh keeps the stale content
 * visible and shows a subtle indicator. Collapsing them is why refresh in the
 * Android app blanks the screen.
 */
sealed interface LoadState<out T> {

    data object Idle : LoadState<Nothing>

    data object Loading : LoadState<Nothing>

    data class Refreshing<T>(val stale: T) : LoadState<T>

    data class Ready<T>(val value: T) : LoadState<T>

    data class Failed(val error: ZillitError, val hasStaleContent: Boolean = false) : LoadState<Nothing>

    /** Content to render right now, if any — stale content included. */
    val contentOrNull: T?
        get() = when (this) {
            is Ready -> value
            is Refreshing -> stale
            else -> null
        }

    val isBusy: Boolean get() = this is Loading || this is Refreshing
}

/** Begins a load, preserving existing content so a refresh does not blank the UI. */
fun <T> LoadState<T>.toLoading(): LoadState<T> =
    contentOrNull?.let { LoadState.Refreshing(it) } ?: LoadState.Loading

fun <T> LoadState<T>.toFailed(error: ZillitError): LoadState<T> =
    LoadState.Failed(error, hasStaleContent = contentOrNull != null)
