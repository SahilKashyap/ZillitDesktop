package com.zillit.desktop.feature.assetreport.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetExportFormat
import com.zillit.desktop.feature.assetreport.domain.PickedAssetFile

sealed interface AssetEvent {
    // -- the register ------------------------------------------------------------
    data object Refresh : AssetEvent

    /** The header's back arrow: out of the tool. */
    data object Leave : AssetEvent
    data class Search(val query: String) : AssetEvent
    data class FilterCategory(val filter: CategoryFilter) : AssetEvent
    data class FilterDepartments(val ids: List<String>) : AssetEvent

    /** Null returns to the production's default. */
    data class PickCurrency(val code: String?) : AssetEvent
    data class Export(val format: AssetExportFormat) : AssetEvent
    data object DismissError : AssetEvent

    // -- one asset ---------------------------------------------------------------
    data class Open(val lineItemId: String) : AssetEvent
    data object RetryRecord : AssetEvent

    /** Back from the detail — asks first when something is unsaved. */
    data object RequestClose : AssetEvent
    data object KeepEditing : AssetEvent
    data object DiscardAndClose : AssetEvent
    data object SaveAndClose : AssetEvent

    data class PickCategory(val category: AssetCategory) : AssetEvent
    data class NoteChanged(val text: String) : AssetEvent

    /** The header's Save: category and attachments. */
    data object SaveDetails : AssetEvent

    /** The note's own Save. */
    data object SaveNote : AssetEvent

    /** Click to upload: the OS chooser. */
    data object AddFiles : AssetEvent

    /** Drag and drop: files read from disk, and the ones too big to read. */
    data class DropFiles(
        val files: List<PickedAssetFile>,
        val tooLarge: List<Pair<String, Long>> = emptyList(),
    ) : AssetEvent

    data class RemoveFile(val key: String) : AssetEvent
    data class ViewFile(val key: String) : AssetEvent
    data object CloseViewer : AssetEvent
    data object DownloadViewed : AssetEvent
}

sealed interface AssetEffect {
    /** A one-off line for the toast; [success] greens it, otherwise it reads as a refusal. */
    data class Notice(val text: String, val success: Boolean = false) : AssetEffect

    /** Close the tool — the web's "Back to Film Tools". */
    data object Leave : AssetEffect
}

/** How the screen asks for a file's bytes: a pick from memory, a stored file from the bucket. */
fun interface AssetMediaLoader {
    suspend fun load(file: DraftFile): ZillitResult<ByteArray>
}
