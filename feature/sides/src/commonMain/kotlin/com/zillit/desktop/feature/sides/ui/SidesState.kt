package com.zillit.desktop.feature.sides.ui

import com.zillit.desktop.feature.sides.domain.CallSheetRef
import com.zillit.desktop.feature.sides.domain.ManualPlan
import com.zillit.desktop.feature.sides.domain.PageSelection
import com.zillit.desktop.feature.sides.domain.SceneDisplayMode
import com.zillit.desktop.feature.sides.domain.SceneInfo
import com.zillit.desktop.feature.sides.domain.ScenePage
import com.zillit.desktop.feature.sides.domain.ScheduleRef
import com.zillit.desktop.feature.sides.domain.Script
import com.zillit.desktop.feature.sides.domain.ScriptVersion
import com.zillit.desktop.feature.sides.domain.SidesPdfPage
import com.zillit.desktop.feature.sides.domain.SidesRecord
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.domain.SidesViewer
import com.zillit.desktop.feature.sides.domain.VersionScenes

/** The tool's two segments — the web's `Sides | Script` header switch. */
enum class SidesDestination(val label: String) {
    Sides("Sides"),
    Scripts("Script"),
}

/** The sides list's two presentations — the web's persisted list/table toggle. */
enum class SidesLayout { List, Table }

/** A file picked or dropped, held client-side until the form submits. */
data class PickedDoc(val name: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is PickedDoc && other.name == name && other.bytes.size == bytes.size
    override fun hashCode(): Int = name.hashCode() * 31 + bytes.size
}

/** Why the host was asked for a file; the answer routes on it. */
sealed interface PickPurpose {
    /** The open dialog's file field. */
    data object Dialog : PickPurpose

    /** Replace (or first-upload) a script's file. */
    data class ReplaceScript(val script: Script) : PickPurpose
}

// ── Sides list ────────────────────────────────────────────────────────────

data class SidesListState(
    val loading: Boolean = false,
    val sides: List<SidesRecord> = emptyList(),
    val historyOpen: Boolean = false,
    val historyLoading: Boolean = false,
    val history: List<SidesRecord> = emptyList(),
    val historySearch: String = "",
    val layout: SidesLayout = SidesLayout.List,
    /** The Generate gates: an active script with a file, or any archived script. */
    val activeScript: Script? = null,
    val hasHistoryScripts: Boolean = false,
    val latestScheduleId: String = "",
) {
    val filteredHistory: List<SidesRecord> get() = SidesRules.filterHistory(history, historySearch)
    val anyGenerating: Boolean get() = sides.any { it.status.wire == "generating" }
}

// ── Scripts manager ───────────────────────────────────────────────────────

data class ScriptsState(
    val loading: Boolean = false,
    val scripts: List<Script> = emptyList(),
    val versions: Map<String, List<ScriptVersion>> = emptyMap(),
    val pages: Map<String, List<ScenePage>> = emptyMap(),
    val pagesLoading: Set<String> = emptySet(),
    val collapsedPages: Set<String> = emptySet(),
    val pageSearch: Map<String, String> = emptyMap(),
    /** Scripts with a replacement upload in flight. */
    val replacing: Set<String> = emptySet(),
    /** The script whose version menu is open, if any. */
    val versionMenu: String? = null,
) {
    fun versionsOf(scriptId: String): List<ScriptVersion> = versions[scriptId].orEmpty()
    fun pagesOf(scriptId: String): List<ScenePage> = pages[scriptId].orEmpty()
    fun visiblePages(scriptId: String): List<ScenePage> =
        SidesRules.filterPages(pagesOf(scriptId), pageSearch[scriptId].orEmpty())
}

// ── Generate form ─────────────────────────────────────────────────────────

/**
 * The manual generate page — the web's `GenerateSidesForm` state, with the
 * generated draft and its review stage.
 */
data class GenerateState(
    val loading: Boolean = true,
    val scripts: List<Script> = emptyList(),
    val activeScriptId: String = "",
    val activeVersionId: String = "",
    val scriptId: String = "",
    val extraScriptIds: List<String> = emptyList(),
    val versions: Map<String, List<ScriptVersion>> = emptyMap(),
    /** Pages of the PRIMARY script only — added scripts contribute versions alone. */
    val pages: List<ScenePage> = emptyList(),
    val scenesByVersion: Map<String, List<SceneInfo>> = emptyMap(),
    val scenesByPage: Map<String, List<SceneInfo>> = emptyMap(),
    /** Version and page ids whose scene fetch is in flight. */
    val scenesLoading: Set<String> = emptySet(),
    val openVersions: Set<String> = emptySet(),
    val openPages: Set<String> = emptySet(),
    val versionPicks: Map<String, List<String>> = emptyMap(),
    val pagePicks: Map<String, List<String>> = emptyMap(),
    val wholePages: Set<String> = emptySet(),
    val displayMode: String = SceneDisplayMode.CROSSOUT,
    val title: String = "",
    val rearrange: Boolean = false,
    val order: List<String> = emptyList(),
    val running: Boolean = false,
    /** Updated on every poll tick; terminal when done. */
    val result: SidesRecord? = null,
    val viewed: Boolean = false,
    val publishing: Boolean = false,
) {
    val selectedScript: Script? get() = scripts.firstOrNull { it.id == scriptId }
    val isActiveScript: Boolean get() = scriptId.isNotBlank() && scriptId == activeScriptId
    val primaryVersions: List<ScriptVersion> get() = versions[scriptId].orEmpty()

    val addableScripts: List<Script>
        get() = scripts.filter { it.id != scriptId && it.id !in extraScriptIds }

    val versionScenesPayload: List<VersionScenes>
        get() = versionPicks.filterValues { it.isNotEmpty() }.map { (id, scenes) -> VersionScenes(id, scenes) }

    val pageSelections: List<PageSelection>
        get() {
            val picked = pagePicks.filterValues { it.isNotEmpty() }.map { (id, scenes) -> PageSelection(id, scenes) }
            val whole = wholePages.filter { pagePicks[it].isNullOrEmpty() }.map { PageSelection(it) }
            return picked + whole
        }

    val sceneCount: Int get() = versionScenesPayload.sumOf { it.sceneNumbers.size }
    val readyToSubmit: Boolean get() = sceneCount > 0 || pageSelections.isNotEmpty()

    /** Every picked scene number once, in pick order — the reorder pool. */
    val allSelectedScenes: List<String>
        get() = (versionScenesPayload.flatMap { it.sceneNumbers } + pagePicks.values.flatten()).distinct()

    val plan: ManualPlan
        get() = ManualPlan(
            scriptId = scriptId,
            title = title.trim(),
            versionScenes = versionScenesPayload,
            pageSelections = pageSelections,
            displayMode = displayMode,
            sceneOrder = if (rearrange) order else emptyList(),
        )

    /** The success summary reads the FORM selection, never the poll record. */
    val resultScenes: List<String> get() = versionScenesPayload.flatMap { it.sceneNumbers }.distinct()

    val resultPages: List<ResultPage>
        get() = pageSelections.mapNotNull { selection ->
            val page = pages.firstOrNull { it.id == selection.pageId } ?: return@mapNotNull null
            if (page.sceneNumber.isBlank()) return@mapNotNull null
            ResultPage(number = page.sceneNumber, color = page.color, scenes = selection.sceneNumbers)
        }

    /** The viewer's info popover: scenes and pages, one row each. */
    val selectionInfo: List<Pair<String, String>>
        get() = buildList {
            if (resultScenes.isNotEmpty()) add("Scenes" to resultScenes.joinToString(", "))
            resultPages.forEach { page ->
                add("Page ${page.number}" to page.scenes.joinToString(", ").ifBlank { "Whole PDF" })
            }
        }
}

data class ResultPage(val number: String, val color: String, val scenes: List<String>)

// ── Autogenerate dialog ───────────────────────────────────────────────────

/** The web's `AutogenerateSidesModal` state. */
data class AutoState(
    val scriptId: String,
    val scriptTitle: String = "",
    val loading: Boolean = true,
    val callSheets: List<CallSheetRef> = emptyList(),
    val schedules: List<ScheduleRef> = emptyList(),
    val selectedCallSheetId: String = "",
    val selectedScheduleId: String = "",
    val detail: CallSheetRef? = null,
    val detailLoading: Boolean = false,
    val rearrange: Boolean = false,
    val order: List<String> = emptyList(),
    val orderText: String = "",
    val displayMode: String = SceneDisplayMode.CROSSOUT,
    val running: Boolean = false,
    val result: SidesRecord? = null,
    val viewed: Boolean = false,
    val publishing: Boolean = false,
) {
    val publishedSheets: List<CallSheetRef> get() = callSheets.filter { !it.uploaded }
    val uploadedSheets: List<CallSheetRef> get() = callSheets.filter { it.uploaded }
    val publishedSchedules: List<ScheduleRef> get() = schedules.filter { !it.uploaded }
    val uploadedSchedules: List<ScheduleRef> get() = schedules.filter { it.uploaded }

    val selectedFromList: CallSheetRef? get() = callSheets.firstOrNull { it.id == selectedCallSheetId }

    /** The detail's scenes, else the list row's — the web's fallback. */
    val scenes: List<String>
        get() = detail?.scenes?.takeIf { it.isNotEmpty() } ?: selectedFromList?.scenes.orEmpty()

    val orderedScenes: List<String> get() = if (rearrange) order else scenes

    val disabledReason: String
        get() = SidesRules.autoDisabledReason(
            callSheetSelected = selectedCallSheetId.isNotBlank(),
            loadingScenes = detailLoading,
            orderedScenes = orderedScenes.size,
            rearranging = rearrange,
        )

    val generateDisabled: Boolean get() = selectedCallSheetId.isBlank() || orderedScenes.isEmpty()
    val callSheetTitle: String get() = (detail ?: selectedFromList)?.title.orEmpty()
}

// ── Dialogs ───────────────────────────────────────────────────────────────

enum class DocKind(val title: String, val noun: String) {
    CallSheet("Add Call Sheet", "call sheet"),
    Schedule("Add Schedule", "schedule"),
}

enum class ConfirmKind { Sides, Script, Page, CallSheet, Schedule }

sealed interface SidesDialog {
    val busy: Boolean

    data class AddScript(
        val title: String = "",
        val file: PickedDoc? = null,
        override val busy: Boolean = false,
    ) : SidesDialog

    data class PageEditor(
        val scriptId: String,
        val pageId: String? = null,
        val sceneNumber: String = "",
        val color: String = SidesRules.PAGE_COLORS.first(),
        val description: String = "",
        val currentFileName: String = "",
        val file: PickedDoc? = null,
        override val busy: Boolean = false,
    ) : SidesDialog {
        val isEdit: Boolean get() = pageId != null
    }

    data class UploadDoc(
        val kind: DocKind,
        val title: String = "",
        val file: PickedDoc? = null,
        override val busy: Boolean = false,
    ) : SidesDialog

    data class Confirm(
        val kind: ConfirmKind,
        val id: String,
        val title: String,
        val message: String,
        override val busy: Boolean = false,
    ) : SidesDialog
}

// ── PDF viewer ────────────────────────────────────────────────────────────

/** The in-app preview — the web's `PdfViewerModal`. */
data class SidesPdfView(
    val title: String,
    val subtitle: String = "",
    val info: List<Pair<String, String>> = emptyList(),
    val fileName: String = "sides.pdf",
    val loading: Boolean = true,
    val pages: List<SidesPdfPage> = emptyList(),
    /** The file is a Final Draft or other non-PDF — nothing to render. */
    val notPdf: Boolean = false,
    val error: String = "",
    val url: String = "",
    val bytes: ByteArray? = null,
)

// ── Whole tool ────────────────────────────────────────────────────────────

data class SidesUiState(
    val viewer: SidesViewer = SidesViewer(),
    val destination: SidesDestination = SidesDestination.Sides,
    val list: SidesListState = SidesListState(),
    val scripts: ScriptsState = ScriptsState(),
    /** Non-null while the generate page is open over the list. */
    val generate: GenerateState? = null,
    val auto: AutoState? = null,
    val dialog: SidesDialog? = null,
    val pdf: SidesPdfView? = null,
    val error: String? = null,
) {
    val loading: Boolean
        get() = if (destination == SidesDestination.Sides) list.loading else scripts.loading
}
