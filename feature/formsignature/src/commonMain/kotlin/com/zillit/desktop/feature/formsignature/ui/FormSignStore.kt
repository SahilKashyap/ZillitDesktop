package com.zillit.desktop.feature.formsignature.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import kotlinx.coroutines.Job

/**
 * The view model's state and effects, as the flows see them.
 *
 * The signing, sending and marks flows are classes of their own so the view
 * model stays a router; each needs to read state, replace it, raise a toast
 * and launch work, and nothing else of the view model.
 */
internal interface FormSignStore {
    val state: FormSignatureUiState
    fun update(reducer: FormSignatureUiState.() -> FormSignatureUiState)
    fun effect(effect: FormSignatureEffect)
    fun launch(block: suspend () -> Unit): Job

    fun fail(message: String) = effect(FormSignatureEffect.Failed(message))
    fun fail(error: ZillitError) = fail(error.localised())
    fun notice(message: String) = effect(FormSignatureEffect.Notice(message))
}

/** Unwraps, or fails the store and returns null — the flows' one-line error path. */
internal fun <T> FormSignStore.orFail(result: ZillitResult<T>): T? = when (result) {
    is ZillitResult.Success -> result.data
    is ZillitResult.Failure -> {
        fail(result.error)
        null
    }
}

internal const val PAGE_RENDER_WIDTH = 800
internal const val PDF_MIME = "application/pdf"
