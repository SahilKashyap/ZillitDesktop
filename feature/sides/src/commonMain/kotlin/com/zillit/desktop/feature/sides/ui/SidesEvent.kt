package com.zillit.desktop.feature.sides.ui

import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.feature.sides.domain.CallSheetRef
import com.zillit.desktop.feature.sides.domain.ScenePage
import com.zillit.desktop.feature.sides.domain.ScheduleRef
import com.zillit.desktop.feature.sides.domain.Script
import com.zillit.desktop.feature.sides.domain.ScriptVersion
import com.zillit.desktop.feature.sides.domain.SidesRecord

/** Everything the Sides screens can do — one event per act. */
sealed interface SidesEvent {
    // ── Shell ──
    data class Open(val destination: SidesDestination) : SidesEvent
    data object Refresh : SidesEvent
    data object DismissError : SidesEvent

    // ── Sides list ──
    data class SetLayout(val layout: SidesLayout) : SidesEvent
    data object ToggleHistory : SidesEvent
    data class HistorySearch(val query: String) : SidesEvent
    data object OpenAutogenerate : SidesEvent
    data object OpenGenerate : SidesEvent
    data class ViewSides(val record: SidesRecord) : SidesEvent
    data class DownloadSides(val record: SidesRecord) : SidesEvent
    data class AskDeleteSides(val record: SidesRecord) : SidesEvent

    // ── Scripts manager ──
    data object AskAddScript : SidesEvent
    data class ReplaceScript(val script: Script) : SidesEvent
    data class ViewVersion(val script: Script, val version: ScriptVersion) : SidesEvent
    data class DownloadVersion(val script: Script, val version: ScriptVersion) : SidesEvent
    data class AskDeleteScript(val script: Script) : SidesEvent
    data class VersionMenu(val scriptId: String?) : SidesEvent
    data class TogglePages(val scriptId: String) : SidesEvent
    data class PageSearch(val scriptId: String, val query: String) : SidesEvent
    data class AskAddPage(val scriptId: String) : SidesEvent
    data class AskEditPage(val scriptId: String, val page: ScenePage) : SidesEvent
    data class ViewPage(val page: ScenePage) : SidesEvent
    data class AskDeletePage(val page: ScenePage) : SidesEvent

    // ── Dialogs (add script, page editor, uploads, confirms) ──
    data class DialogTitle(val value: String) : SidesEvent
    data class DialogSceneNumber(val value: String) : SidesEvent
    data class DialogColor(val value: String) : SidesEvent
    data class DialogDescription(val value: String) : SidesEvent
    data object DialogPickFile : SidesEvent
    data class DialogFileDropped(val file: PickedDoc) : SidesEvent
    data object DialogClearFile : SidesEvent
    data object DialogSubmit : SidesEvent
    data object DialogDismiss : SidesEvent

    /** The host's picker answered; a null file is a cancelled picker. */
    data class FilePicked(val purpose: PickPurpose, val file: PickedDoc?) : SidesEvent

    // ── Generate page ──
    data class GenPickScript(val scriptId: String) : SidesEvent
    data class GenAddScript(val scriptId: String) : SidesEvent
    data class GenRemoveScript(val scriptId: String) : SidesEvent
    data class GenToggleVersionOpen(val versionId: String) : SidesEvent
    data class GenTogglePageOpen(val pageId: String) : SidesEvent
    data class GenToggleScene(val versionId: String, val sceneNumber: String) : SidesEvent
    data class GenSetScenes(val versionId: String, val sceneNumbers: List<String>) : SidesEvent
    data class GenTogglePageScene(val pageId: String, val sceneNumber: String) : SidesEvent
    data class GenSetPageScenes(val pageId: String, val sceneNumbers: List<String>) : SidesEvent
    data class GenToggleWholePage(val pageId: String) : SidesEvent
    data class GenRearrange(val on: Boolean) : SidesEvent
    data class GenOrder(val order: List<String>) : SidesEvent
    data class GenDisplayMode(val mode: String) : SidesEvent
    data class GenTitle(val title: String) : SidesEvent
    data object GenSubmit : SidesEvent
    data object GenClose : SidesEvent
    data object GenBackToForm : SidesEvent
    data object GenView : SidesEvent
    data object GenDownload : SidesEvent
    data object GenPublish : SidesEvent

    // ── Autogenerate dialog ──
    data class AutoSelectCallSheet(val id: String) : SidesEvent
    data class AutoSelectSchedule(val id: String) : SidesEvent
    data class AutoViewCallSheet(val sheet: CallSheetRef) : SidesEvent
    data class AutoViewSchedule(val schedule: ScheduleRef) : SidesEvent
    data class AutoAskDeleteCallSheet(val sheet: CallSheetRef) : SidesEvent
    data class AutoAskDeleteSchedule(val schedule: ScheduleRef) : SidesEvent
    data class AutoUpload(val kind: DocKind) : SidesEvent
    data class AutoRearrange(val on: Boolean) : SidesEvent
    data class AutoOrder(val order: List<String>) : SidesEvent
    data class AutoOrderText(val text: String) : SidesEvent
    data class AutoDisplayMode(val mode: String) : SidesEvent
    data object AutoGenerate : SidesEvent
    data object AutoClose : SidesEvent
    data object AutoView : SidesEvent
    data object AutoDownload : SidesEvent
    data object AutoPublish : SidesEvent

    // ── PDF viewer ──
    data object ClosePdf : SidesEvent
    data object PdfOpenExternal : SidesEvent
    data object PdfDownload : SidesEvent
}

sealed interface SidesEffect {
    data class Notice(val message: String, val tone: ZillitToastTone = ZillitToastTone.Success) : SidesEffect

    /** Ask the host for a PDF/.fdx (or PDF-only) file; answered with [SidesEvent.FilePicked]. */
    data class PickFile(val purpose: PickPurpose, val pdfOnly: Boolean) : SidesEffect

    /** Open a signed URL in the system browser. */
    data class OpenUrl(val url: String) : SidesEffect

    /** Offer fetched bytes to save — the web's forced save-to-disk download. */
    data class SaveFile(val fileName: String, val bytes: ByteArray) : SidesEffect
}
