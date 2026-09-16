package com.zillit.desktop.feature.esignature.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.esignature.domain.EsignFileTransfer
import com.zillit.desktop.feature.esignature.domain.EsignPdf
import com.zillit.desktop.feature.esignature.domain.EsignRepository
import com.zillit.desktop.feature.esignature.domain.SignerOptionLike
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** What the host is being asked to pick, so the answer lands in the right place. */
enum class PickPurpose { Document, Csv, PadImage, FieldUpload }

/**
 * The view model's surface for its flows — state, effects, the seams — so
 * the editor, signing, template and bulk logic can each live in a file of
 * its own without any of them reaching into the view model's protected
 * plumbing.
 */
internal interface EsignStore {
    val current: EsignUiState

    /** An envelope opened from any flow: its badge rows are read. */
    fun readEnvelopeBadges(envelopeId: String) {}

    val repository: EsignRepository
    val transfer: EsignFileTransfer
    val pdf: EsignPdf
    val newId: () -> String
    val signerOptions: () -> List<SignerOptionLike>
    val currentUserName: () -> String
    val now: () -> Long

    fun update(reducer: EsignUiState.() -> EsignUiState)
    fun effect(effect: EsignEffect)
    fun runTask(block: suspend CoroutineScope.() -> Unit): Job

    /** Refuses a write without posting rights, and asks for them. */
    fun refusesPost(): Boolean

    /** Asks the host for a file; the answer arrives as [EsignEvent.FilePicked]. */
    fun requestPick(purpose: PickPurpose)

    fun notice(message: String) = effect(EsignEffect.Notice(message))
    fun failed(message: String) = effect(EsignEffect.Failed(message))
}

/** Runs [block] and routes a failure to the toast, answering the data or null. */
internal suspend fun <T> EsignStore.orFail(block: suspend () -> ZillitResult<T>): T? =
    when (val result = block()) {
        is ZillitResult.Success -> result.data
        is ZillitResult.Failure -> {
            failed(result.error.userMessage)
            null
        }
    }

internal const val PAGE_RENDER_WIDTH = 900
internal const val MARK_RASTER_WIDTH = 800
internal const val MARK_RASTER_HEIGHT = 300
