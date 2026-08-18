@file:Suppress("TooManyFunctions") // One handler per keystroke and act; the editor is its event set.

package com.zillit.desktop.feature.draft.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.draft.data.ScreenplayCodec
import com.zillit.desktop.feature.draft.domain.DraftHost
import com.zillit.desktop.feature.draft.domain.DraftStore
import com.zillit.desktop.feature.draft.domain.ElementFlow
import com.zillit.desktop.feature.draft.domain.ElementType
import com.zillit.desktop.feature.draft.domain.FinalDraftXml
import com.zillit.desktop.feature.draft.domain.Fountain
import com.zillit.desktop.feature.draft.domain.Screenplay
import com.zillit.desktop.feature.draft.domain.ScreenplayLayout
import com.zillit.desktop.feature.draft.domain.ScreenplayRenderer
import com.zillit.desktop.feature.draft.domain.ScriptElement
import com.zillit.desktop.feature.draft.domain.ScriptSummary
import com.zillit.desktop.feature.draft.domain.SmartType
import com.zillit.desktop.feature.draft.domain.TitlePage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Zillit Draft: a screenwriting editor in the shape writers know from Final
 * Draft — typed elements, Enter and Tab that know what comes next, SmartType,
 * a scene navigator, page counts — saving as you type into the production's
 * local store, exporting to PDF, Final Draft and Fountain.
 */
class DraftViewModel(
    private val store: DraftStore,
    private val host: DraftHost,
    private val renderer: ScreenplayRenderer,
    private val projectId: () -> String?,
    private val newId: () -> String,
    private val nowMillis: () -> Long,
) : ZillitViewModel<DraftUiState, DraftEvent, DraftEffect>(DraftUiState()) {

    private var autosave: Job? = null
    private var caretNonce = 0L

    fun start() {
        val project = projectId()
        if (project != state.value.projectId || state.value.scripts.isEmpty()) {
            setState { copy(projectId = project, open = null) }
            refresh()
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out.
    override fun onEvent(event: DraftEvent) {
        when (event) {
            DraftEvent.Refresh -> refresh()
            DraftEvent.DismissError -> setState { copy(error = null) }
            DraftEvent.ClearNotice -> setState { copy(notice = null) }
            DraftEvent.StartNew -> setState { copy(creating = true, newTitle = "") }
            is DraftEvent.NewTitleChanged -> setState { copy(newTitle = event.title) }
            DraftEvent.CreateNew -> createNew()
            DraftEvent.CancelNew -> setState { copy(creating = false) }
            is DraftEvent.Open -> open(event.id)
            is DraftEvent.RequestDelete -> setState { copy(confirmDelete = event.script) }
            DraftEvent.ConfirmDelete -> deleteConfirmed()
            DraftEvent.CancelDelete -> setState { copy(confirmDelete = null) }
            is DraftEvent.StartRename -> setState { copy(renaming = event.script, renameTitle = event.script.title) }
            is DraftEvent.RenameTitleChanged -> setState { copy(renameTitle = event.title) }
            DraftEvent.ConfirmRename -> renameConfirmed()
            DraftEvent.CancelRename -> setState { copy(renaming = null) }
            DraftEvent.Import -> import()
            DraftEvent.CloseScript -> closeScript()
            is DraftEvent.Focus -> setState { copy(open = open?.copy(focusedId = event.elementId)) }
            is DraftEvent.TextChanged -> textChanged(event.elementId, event.text)
            is DraftEvent.SetType -> setType(event.elementId, event.type)
            is DraftEvent.Split -> split(event.elementId, event.offset)
            is DraftEvent.MergeUp -> mergeUp(event.elementId)
            is DraftEvent.RemoveEmpty -> removeEmpty(event.elementId)
            is DraftEvent.Tab -> tab(event.elementId, forward = true)
            is DraftEvent.BackTab -> tab(event.elementId, forward = false)
            is DraftEvent.Accept -> accept(event.elementId, event.suggestion)
            is DraftEvent.MoveFocus -> moveFocus(event.elementId, event.delta, event.toEnd)
            is DraftEvent.JumpTo -> placeCaret(event.elementId, 0)
            DraftEvent.ToggleNavigator -> setState { copy(open = open?.copy(navigatorOpen = !open.navigatorOpen)) }
            DraftEvent.OpenTitlePage -> setState { copy(open = open?.copy(titlePageOpen = true)) }
            DraftEvent.CloseTitlePage -> setState { copy(open = open?.copy(titlePageOpen = false)) }
            is DraftEvent.TitlePageChanged -> editScreenplay { it.copy(titlePage = event.page) }
            DraftEvent.StartEditTitle -> setState { copy(open = open?.copy(editingTitle = true)) }
            is DraftEvent.ScriptTitleChanged -> editScreenplay { it.copy(title = event.title) }
            DraftEvent.FinishEditTitle -> {
                setState { copy(open = open?.copy(editingTitle = false)) }
                saveNow()
            }
            DraftEvent.SaveNow -> saveNow()
            is DraftEvent.Export -> export(event.format)
            DraftEvent.SendToDrive -> sendToDrive()
        }
    }

    // -- the list ------------------------------------------------------------

    private fun refresh() {
        val project = state.value.projectId ?: return
        setState { copy(loading = true) }
        launch {
            val scripts = store.list(project).map { stored ->
                val screenplay = ScreenplayCodec.decode(stored)
                ScriptSummary(
                    id = stored.id,
                    title = stored.title,
                    pageCount = ScreenplayLayout.paginate(screenplay.elements).pageCount,
                    sceneCount = screenplay.scenes.size,
                    updatedAtMillis = stored.updatedAtMillis,
                )
            }.sortedByDescending { it.updatedAtMillis }
            setState { copy(loading = false, scripts = scripts) }
        }
    }

    private fun createNew() {
        val project = state.value.projectId ?: return
        val title = state.value.newTitle.trim().ifBlank { "Untitled" }
        val now = nowMillis()
        val first = ScriptElement(newId(), ElementFlow.first, "")
        val screenplay = Screenplay(
            id = newId(),
            projectId = project,
            title = title,
            titlePage = TitlePage(title = title),
            elements = listOf(first),
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        setState { copy(creating = false) }
        launch {
            store.save(ScreenplayCodec.encode(screenplay))
            openLoaded(screenplay)
            refresh()
        }
    }

    private fun open(id: String) {
        setState { copy(loading = true) }
        launch {
            val stored = store.load(id)
            if (stored == null) {
                setState { copy(loading = false, error = "That script is no longer here.") }
            } else {
                openLoaded(ScreenplayCodec.decode(stored))
            }
        }
    }

    private fun openLoaded(screenplay: Screenplay) {
        // Never open on nothing: an empty script gets its first heading.
        val elements = screenplay.elements.ifEmpty { listOf(ScriptElement(newId(), ElementFlow.first, "")) }
        val ready = screenplay.copy(elements = elements)
        setState { copy(loading = false, open = OpenScript(ready, savedAtMillis = ready.updatedAtMillis)) }
        placeCaret(elements.first().id, elements.first().text.length)
    }

    private fun deleteConfirmed() {
        val target = state.value.confirmDelete ?: return
        setState { copy(confirmDelete = null) }
        launch {
            store.delete(target.id)
            if (state.value.open?.screenplay?.id == target.id) setState { copy(open = null) }
            refresh()
            sendEffect(DraftEffect.Notice("Deleted \"${target.title}\""))
        }
    }

    private fun renameConfirmed() {
        val target = state.value.renaming ?: return
        val title = state.value.renameTitle.trim().ifBlank { return }
        setState { copy(renaming = null) }
        launch {
            store.load(target.id)?.let { store.save(it.copy(title = title, updatedAtMillis = nowMillis())) }
            refresh()
        }
    }

    private fun import() {
        val project = state.value.projectId ?: return
        launch {
            val file = host.pickImport() ?: return@launch
            val lower = file.name.lowercase()
            val parsed = when {
                lower.endsWith(".fdx") -> FinalDraftXml.parse(file.text, newId).let { it.titlePage to it.elements }
                else -> Fountain.parse(file.text, newId).let { it.titlePage to it.elements }
            }
            val (page, elements) = parsed
            if (elements.isEmpty()) {
                setState { copy(error = "Nothing readable in ${file.name}") }
                return@launch
            }
            val title = page.title.ifBlank { file.name.substringBeforeLast('.') }
            val now = nowMillis()
            val screenplay = Screenplay(newId(), project, title, page.copy(title = title), elements, now, now)
            store.save(ScreenplayCodec.encode(screenplay))
            refresh()
            openLoaded(screenplay)
            sendEffect(DraftEffect.Notice("Imported ${file.name}"))
        }
    }

    // -- the editor -----------------------------------------------------------

    private fun closeScript() {
        saveNow()
        setState { copy(open = null) }
        refresh()
    }

    private fun textChanged(id: String, text: String) {
        val open = state.value.open ?: return
        val element = open.elements.firstOrNull { it.id == id } ?: return
        val shown = if (element.type.isUppercase) text.uppercase() else text
        if (shown == element.text) return
        editElements { list -> list.map { if (it.id == id) it.copy(text = shown) else it } }
    }

    private fun setType(id: String, type: ElementType) {
        editElements { list ->
            list.map { e ->
                if (e.id != id) e else e.copy(type = type, text = if (type.isUppercase) e.text.uppercase() else e.text)
            }
        }
        val length = state.value.open?.elements?.firstOrNull { it.id == id }?.text?.length ?: 0
        placeCaret(id, length)
    }

    private fun split(id: String, offset: Int) {
        val open = state.value.open ?: return
        val index = open.elements.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val element = open.elements[index]
        val safeOffset = offset.coerceIn(0, element.text.length)
        val head = element.text.substring(0, safeOffset).trimEnd()
        val tail = element.text.substring(safeOffset).trimStart()
        // Enter in the middle of an element keeps the tail's type; at the end
        // it starts whatever usually follows.
        val nextType = if (tail.isEmpty()) ElementFlow.onEnter(element.type) else element.type
        val next = ScriptElement(newId(), nextType, if (nextType.isUppercase) tail.uppercase() else tail)
        editElements { list ->
            list.take(index) + element.copy(text = head) + next + list.drop(index + 1)
        }
        placeCaret(next.id, 0)
    }

    private fun mergeUp(id: String) {
        val open = state.value.open ?: return
        val index = open.elements.indexOfFirst { it.id == id }
        if (index <= 0) return
        val above = open.elements[index - 1]
        val current = open.elements[index]
        val joinAt = above.text.length
        val joined = if (current.text.isEmpty()) {
            above
        } else {
            val glue = if (above.text.isEmpty()) "" else " "
            above.copy(text = above.text + glue + (if (above.type.isUppercase) current.text.uppercase() else current
                .text))
        }
        editElements { list -> list.take(index - 1) + joined + list.drop(index + 1) }
        placeCaret(above.id, joinAt)
    }

    private fun removeEmpty(id: String) {
        val open = state.value.open ?: return
        val index = open.elements.indexOfFirst { it.id == id }
        if (index < 0 || open.elements.size == 1 || open.elements[index].text.isNotEmpty()) return
        val neighbour = open.elements.getOrNull(index + 1) ?: open.elements[index - 1]
        editElements { list -> list.filterNot { it.id == id } }
        placeCaret(neighbour.id, 0)
    }

    private fun tab(id: String, forward: Boolean) {
        val open = state.value.open ?: return
        val element = open.elements.firstOrNull { it.id == id } ?: return
        val suggestion = open.suggestions.firstOrNull()
        if (forward && suggestion != null && element.text.isNotBlank()) {
            accept(id, suggestion)
            return
        }
        val type = when {
            !forward -> ElementFlow.previous(element.type)
            element.text.isBlank() -> ElementFlow.onEmptyTab(element.type)
            else -> ElementFlow.onTab(element.type)
        }
        if (element.text.isBlank() || !forward) {
            setType(id, type)
        } else {
            // Tab on a full element: finish it and start the sideways one.
            split(id, element.text.length)
            state.value.open?.focused?.id?.let { setType(it, type) }
        }
    }

    private fun accept(id: String, suggestion: String) {
        editElements { list -> list.map { if (it.id == id) it.copy(text = suggestion) else it } }
        placeCaret(id, suggestion.length)
    }

    private fun moveFocus(id: String, delta: Int, toEnd: Boolean) {
        val open = state.value.open ?: return
        val index = open.elements.indexOfFirst { it.id == id }
        val target = open.elements.getOrNull(index + delta) ?: return
        placeCaret(target.id, if (toEnd) target.text.length else 0)
    }

    private fun placeCaret(id: String, offset: Int) {
        caretNonce++
        setState { copy(open = open?.copy(focusedId = id, caret = CaretRequest(id, offset, caretNonce))) }
    }

    private fun editElements(transform: (List<ScriptElement>) -> List<ScriptElement>) =
        editScreenplay { it.copy(elements = transform(it.elements)) }

    private fun editScreenplay(transform: (Screenplay) -> Screenplay) {
        setState { copy(open = open?.copy(screenplay = transform(open.screenplay), dirty = true)) }
        scheduleSave()
    }

    private fun scheduleSave() {
        autosave?.cancel()
        autosave = launch {
            delay(AUTOSAVE_MILLIS)
            persist()
        }
    }

    private fun saveNow() {
        autosave?.cancel()
        launch { persist() }
    }

    private suspend fun persist() {
        val open = state.value.open ?: return
        if (!open.dirty) return
        val now = nowMillis()
        val screenplay = open.screenplay.copy(updatedAtMillis = now)
        store.save(ScreenplayCodec.encode(screenplay))
        setState {
            copy(open = this.open?.takeIf { it.screenplay.id == screenplay.id }?.copy(dirty = false,
                savedAtMillis = now))
        }
    }

    // -- out ------------------------------------------------------------------

    private fun export(format: ExportFormat) {
        val open = state.value.open ?: return
        setState { copy(busy = true) }
        launch {
            persist()
            val screenplay = state.value.open?.screenplay ?: open.screenplay
            val bytes = when (format) {
                ExportFormat.Pdf -> renderer.pdf(screenplay)
                ExportFormat.FinalDraft -> FinalDraftXml.write(screenplay).encodeToByteArray()
                ExportFormat.Fountain -> Fountain.write(screenplay).encodeToByteArray()
            }
            when (val out = host.export(fileNameFor(screenplay, format), bytes)) {
                is ZillitResult.Failure -> setState { copy(busy = false, error = out.error.userMessage) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(DraftEffect.Notice("Exported ${format.label}"))
                }
            }
        }
    }

    private fun sendToDrive() {
        val open = state.value.open ?: return
        setState { copy(busy = true) }
        launch {
            persist()
            val screenplay = state.value.open?.screenplay ?: open.screenplay
            when (val out = host.sendToDrive(fileNameFor(screenplay, ExportFormat.Pdf), renderer.pdf(screenplay))) {
                is ZillitResult.Failure -> setState { copy(busy = false, error = out.error.userMessage) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(DraftEffect.Notice("Sent to Drive"))
                }
            }
        }
    }

    private fun fileNameFor(screenplay: Screenplay, format: ExportFormat): String {
        val stem = screenplay.title.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").replace(' ', '_').ifBlank { "Script" }
        return "$stem.${format.extension}"
    }

    /** For tests and the navigator: the cast as SmartType sees it. */
    fun characters(): List<String> = state.value.open?.let { SmartType.characters(it.elements) }.orEmpty()

    private companion object {
        const val AUTOSAVE_MILLIS = 800L
    }
}
