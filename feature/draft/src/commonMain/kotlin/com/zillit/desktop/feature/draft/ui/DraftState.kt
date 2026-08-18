package com.zillit.desktop.feature.draft.ui

import com.zillit.desktop.feature.draft.domain.ElementType
import com.zillit.desktop.feature.draft.domain.Screenplay
import com.zillit.desktop.feature.draft.domain.ScreenplayLayout
import com.zillit.desktop.feature.draft.domain.ScriptElement
import com.zillit.desktop.feature.draft.domain.ScriptSummary
import com.zillit.desktop.feature.draft.domain.SmartType
import com.zillit.desktop.feature.draft.domain.TitlePage

/** Where the caret should be put next: an element and an offset into it. */
data class CaretRequest(val elementId: String, val offset: Int, val nonce: Long)

/** The open script and everything the editor shows around it. */
data class OpenScript(
    val screenplay: Screenplay,
    /** The element the caret is in. */
    val focusedId: String? = null,
    /** Set by the view model when it moves the caret; the editor consumes it. */
    val caret: CaretRequest? = null,
    val dirty: Boolean = false,
    val savedAtMillis: Long? = null,
    val navigatorOpen: Boolean = true,
    val titlePageOpen: Boolean = false,
    val editingTitle: Boolean = false,
) {
    val elements: List<ScriptElement> get() = screenplay.elements
    val focused: ScriptElement? get() = elements.firstOrNull { it.id == focusedId }
    val pagination: ScreenplayLayout.Pagination get() = ScreenplayLayout.paginate(elements)
    val pageCount: Int get() = pagination.pageCount
    val sceneCount: Int get() = screenplay.scenes.size
    val characters: List<String> get() = SmartType.characters(elements)

    /** Dialogue lines per character, for the navigator's cast list. */
    val castLines: List<Pair<String, Int>>
        get() {
            val counts = linkedMapOf<String, Int>()
            var speaker: String? = null
            elements.forEach { e ->
                when (e.type) {
                    ElementType.Character -> speaker = SmartType.cueName(e.text).also { counts.putIfAbsent(it, 0) }
                    ElementType.Dialogue -> speaker?.let { counts[it] = (counts[it] ?: 0) + 1 }
                    else -> if (!e.type.isSpeech) speaker = null
                }
            }
            return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
        }

    /** SmartType for the focused element, if it has any. */
    val suggestions: List<String>
        get() = focused?.let { SmartType.suggestions(it.type, it.text, elements, it.id) }.orEmpty()
}

data class DraftUiState(
    val projectId: String? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val scripts: List<ScriptSummary> = emptyList(),
    val open: OpenScript? = null,
    val creating: Boolean = false,
    val newTitle: String = "",
    val confirmDelete: ScriptSummary? = null,
    val renaming: ScriptSummary? = null,
    val renameTitle: String = "",
)

sealed interface DraftEvent {
    data object Refresh : DraftEvent
    data object DismissError : DraftEvent
    data object ClearNotice : DraftEvent

    // -- the list --
    data object StartNew : DraftEvent
    data class NewTitleChanged(val title: String) : DraftEvent
    data object CreateNew : DraftEvent
    data object CancelNew : DraftEvent
    data class Open(val id: String) : DraftEvent
    data class RequestDelete(val script: ScriptSummary) : DraftEvent
    data object ConfirmDelete : DraftEvent
    data object CancelDelete : DraftEvent
    data class StartRename(val script: ScriptSummary) : DraftEvent
    data class RenameTitleChanged(val title: String) : DraftEvent
    data object ConfirmRename : DraftEvent
    data object CancelRename : DraftEvent
    data object Import : DraftEvent

    // -- the editor --
    data object CloseScript : DraftEvent
    data class Focus(val elementId: String) : DraftEvent
    data class TextChanged(val elementId: String, val text: String) : DraftEvent
    data class SetType(val elementId: String, val type: ElementType) : DraftEvent
    /** Enter: split at [offset]; the tail becomes the next element in the flow. */
    data class Split(val elementId: String, val offset: Int) : DraftEvent
    /** Backspace at offset 0: join with the element above. */
    data class MergeUp(val elementId: String) : DraftEvent
    /** Delete on an empty element: remove it and move to the neighbour. */
    data class RemoveEmpty(val elementId: String) : DraftEvent
    /** Tab: change type or accept the first suggestion. */
    data class Tab(val elementId: String) : DraftEvent
    /** Shift+Tab: the previous type. */
    data class BackTab(val elementId: String) : DraftEvent
    data class Accept(val elementId: String, val suggestion: String) : DraftEvent
    /** Arrow up/down out of an element. */
    data class MoveFocus(val elementId: String, val delta: Int, val toEnd: Boolean) : DraftEvent
    data class JumpTo(val elementId: String) : DraftEvent
    data object ToggleNavigator : DraftEvent
    data object OpenTitlePage : DraftEvent
    data object CloseTitlePage : DraftEvent
    data class TitlePageChanged(val page: TitlePage) : DraftEvent
    data object StartEditTitle : DraftEvent
    data class ScriptTitleChanged(val title: String) : DraftEvent
    data object FinishEditTitle : DraftEvent
    data object SaveNow : DraftEvent
    data class Export(val format: ExportFormat) : DraftEvent
    data object SendToDrive : DraftEvent
}

enum class ExportFormat(val label: String, val extension: String) {
    Pdf("PDF", "pdf"),
    FinalDraft("Final Draft (.fdx)", "fdx"),
    Fountain("Fountain (.fountain)", "fountain"),
}

sealed interface DraftEffect {
    data class Notice(val text: String) : DraftEffect
}
