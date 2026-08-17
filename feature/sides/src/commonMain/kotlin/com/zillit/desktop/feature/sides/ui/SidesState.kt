package com.zillit.desktop.feature.sides.ui

import com.zillit.desktop.feature.sides.domain.GeneratePlan
import com.zillit.desktop.feature.sides.domain.SceneInfo
import com.zillit.desktop.feature.sides.domain.Script
import com.zillit.desktop.feature.sides.domain.ScriptVersion
import com.zillit.desktop.feature.sides.domain.SidesPdfPage
import com.zillit.desktop.feature.sides.domain.SidesRecord
import com.zillit.desktop.feature.sides.domain.SidesViewer

/** The tool's two surfaces: generated sides, and the scripts behind them. */
enum class SidesDestination(val label: String) {
    Sides("Sides"),
    Scripts("Scripts"),
}

/** The generate page, open over one script/version/scene selection. */
data class GenerateState(
    val scripts: List<Script> = emptyList(),
    val scriptId: String = "",
    val versions: List<ScriptVersion> = emptyList(),
    val versionId: String = "",
    val scenes: List<SceneInfo> = emptyList(),
    val scenesLoading: Boolean = false,
    val selected: Set<String> = emptySet(),
    val title: String = "",
    val displayMode: String = GeneratePlan.DISPLAY_CROSSOUT,
    val orderText: String = "",
    val running: Boolean = false,
    /** Updated on every poll tick; terminal when done. */
    val result: SidesRecord? = null,
    val publishing: Boolean = false,
) {
    val canStart: Boolean
        get() = scriptId.isNotBlank() && versionId.isNotBlank() &&
            selected.isNotEmpty() && !running
}

data class SidesPdfView(
    val sidesId: String = "",
    val title: String = "",
    val loading: Boolean = true,
    val pages: List<SidesPdfPage> = emptyList(),
)

data class SidesUiState(
    val viewer: SidesViewer = SidesViewer(),
    val destination: SidesDestination = SidesDestination.Sides,
    val showHistory: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val sidesList: List<SidesRecord> = emptyList(),
    val historyList: List<SidesRecord> = emptyList(),
    val scripts: List<Script> = emptyList(),
    val generate: GenerateState? = null,
    val pdf: SidesPdfView? = null,
) {
    val visibleSides: List<SidesRecord> get() = if (showHistory) historyList else sidesList
}

sealed interface SidesEvent {
    data class Open(val destination: SidesDestination) : SidesEvent
    data class ShowHistory(val history: Boolean) : SidesEvent
    data object Refresh : SidesEvent

    data object OpenGenerate : SidesEvent
    data class PickScript(val scriptId: String) : SidesEvent
    data class PickVersion(val versionId: String) : SidesEvent
    data class ToggleScene(val sceneNumber: String) : SidesEvent
    data class TitleChanged(val title: String) : SidesEvent
    data class DisplayModeChanged(val mode: String) : SidesEvent
    data class OrderChanged(val text: String) : SidesEvent
    data object StartGenerate : SidesEvent
    data object CloseGenerate : SidesEvent
    data object PublishResult : SidesEvent

    data class ViewSides(val id: String, val title: String) : SidesEvent
    data class DownloadSides(val id: String) : SidesEvent
    data class Publish(val id: String) : SidesEvent
    data class DeleteSides(val id: String) : SidesEvent
    data object ClosePdf : SidesEvent

    data object UploadScript : SidesEvent
    data class ScriptPicked(val fileName: String, val bytes: ByteArray) : SidesEvent
    data class DeleteScript(val id: String) : SidesEvent

    data object DismissError : SidesEvent
}

sealed interface SidesEffect {
    data class Notice(val message: String) : SidesEffect
    /** Ask the host for a PDF file; answered with [SidesEvent.ScriptPicked]. */
    data object PickScriptFile : SidesEffect
    /** Open a signed download URL in the system browser. */
    data class OpenUrl(val url: String) : SidesEffect
}
