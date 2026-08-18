@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.recce.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.feature.recce.domain.RecceRepository
import com.zillit.desktop.feature.recce.domain.RecceStatus
import com.zillit.desktop.feature.recce.domain.RecceTransfer
import com.zillit.desktop.feature.recce.domain.RecceViewer
import com.zillit.desktop.feature.recce.domain.StopKind

/**
 * Recce: a list of scout days, a detail page, and a form — the web's three
 * routes as one window that pages inside itself.
 */
class RecceViewModel(
    private val repository: RecceRepository,
    private val transfer: RecceTransfer,
    private val units: suspend () -> ZillitResult<List<ProductionUnit>>,
    private val resolveViewer: () -> RecceViewer,
    private val newUniqueId: () -> String,
    private val timezone: () -> String,
) : ZillitViewModel<RecceUiState, RecceEvent, RecceEffect>(RecceUiState()) {

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        refresh()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: RecceEvent) {
        when (event) {
            RecceEvent.Refresh -> refresh()
            is RecceEvent.Filter -> setState { copy(filter = event.filter) }
            is RecceEvent.Search -> setState { copy(query = event.query) }
            is RecceEvent.FilterUnit -> setState { copy(unitFilter = event.unit) }
            is RecceEvent.Open -> open(event.id)
            RecceEvent.Back -> setState { copy(page = ReccePage.Index, selected = null, editor = null) }
            RecceEvent.New -> guardPost {
                setState { copy(page = ReccePage.Form(null), editor = RecceEditor(uniqueId = newUniqueId())) }
            }
            is RecceEvent.Edit -> guardPost { edit(event.id) }
            is RecceEvent.Delete -> guardPost { setState { copy(confirmDelete = event.id) } }
            RecceEvent.ConfirmDelete -> delete()
            RecceEvent.CancelDelete -> setState { copy(confirmDelete = null) }
            RecceEvent.GeneratePdf -> generatePdf()
            is RecceEvent.OpenUrl -> sendEffect(RecceEffect.OpenUrl(event.url))
            is RecceEvent.EditorChanged -> editEditor {
                copy(
                    title = event.title ?: title,
                    unit = if (event.clearUnit) "" else event.unit ?: unit,
                    dateYmd = event.dateYmd ?: dateYmd,
                    station = event.station ?: station,
                    weather = event.weather ?: weather,
                    crewNote = event.crewNote ?: crewNote,
                    rdv = event.rdv ?: rdv,
                )
            }
            RecceEvent.AddStop -> editEditor { copy(stops = stops + StopEditor(kind = StopKind.Continue)) }
            is RecceEvent.StopChanged -> editEditor {
                copy(stops = stops.mapIndexed { i, s -> if (i == event.index) event.stop else s })
            }
            is RecceEvent.RemoveStop -> editEditor { copy(stops = stops.filterIndexed { i, _ -> i != event.index }) }
            is RecceEvent.MoveStop -> editEditor { copy(stops = stops.moved(event.index, event.delta)) }
            RecceEvent.AddPerson -> editEditor { copy(personnel = personnel + PersonEditor()) }
            is RecceEvent.AddCrewMember -> addCrew(event.userId)
            is RecceEvent.PersonChanged -> editEditor {
                copy(personnel = personnel.mapIndexed { i, p -> if (i == event.index) event.person else p })
            }
            is RecceEvent.RemovePerson -> editEditor {
                copy(personnel = personnel.filterIndexed { i, _ -> i != event.index })
            }
            RecceEvent.SaveDraft -> save(RecceStatus.Draft)
            RecceEvent.Publish -> save(RecceStatus.Published)
            RecceEvent.CancelEdit -> cancelEdit()
            RecceEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun refresh() {
        setState { copy(loading = true) }
        launch {
            val list = repository.recces().orError()
            val unitList = units().orError()
            setState { copy(loading = false, recces = list ?: recces, units = unitList ?: units) }
        }
        launch { repository.crew().orError()?.let { crew -> setState { copy(crew = crew) } } }
    }

    private fun open(id: String) {
        setState { copy(page = ReccePage.Detail(id), selected = recces.firstOrNull { it.id == id }, loading = true) }
        launch {
            val recce = repository.recce(id).orError()
            setState {
                if (recce == null) {
                    copy(loading = false, page = ReccePage.Index, selected = null)
                } else {
                    copy(loading = false, selected = recce, recces = recces.replacing(recce))
                }
            }
        }
    }

    private fun edit(id: String) {
        val known = state.value.selected?.takeIf { it.id == id } ?: state.value.recces.firstOrNull { it.id == id }
        if (known != null) {
            setState { copy(page = ReccePage.Form(id), editor = RecceEditor.from(known)) }
        }
        launch {
            repository.recce(id).orError()?.let { fresh ->
                setState { copy(page = ReccePage.Form(id), editor = RecceEditor.from(fresh), selected = fresh) }
            }
        }
    }

    private fun addCrew(userId: String) {
        val member = state.value.crew.firstOrNull { it.userId == userId } ?: return
        editEditor {
            if (personnel.any { it.userId == userId }) return@editEditor this
            val row = PersonEditor(
                userId = member.userId,
                name = member.name,
                role = member.role,
                email = member.email,
                contact = member.contact,
            )
            // A blank seed row gives way to the first real person.
            val kept = personnel.filterNot { it.isBlankSeed() }
            copy(personnel = kept + row)
        }
    }

    private fun save(status: RecceStatus) {
        val editor = state.value.editor ?: return
        if (!state.value.viewer.mayEdit) {
            setState { copy(error = "You do not have posting rights for Recce") }
            return
        }
        if (status == RecceStatus.Published) {
            val problems = editor.publishProblems()
            if (problems.isNotEmpty()) {
                setState { copy(error = problems.joinToString("; ")) }
                return
            }
        }
        setState { copy(editor = editor.copy(saving = status), busy = true) }
        launch {
            val draft = editor.toDraft(status, timezone())
            val id = editor.id
            val outcome = if (id == null) repository.create(draft) else repository.update(id, draft).map { id }
            when (outcome) {
                is ZillitResult.Failure -> setState {
                    copy(busy = false, editor = editor.copy(saving = null), error = outcome.error.userMessage)
                }
                is ZillitResult.Success -> {
                    setState { copy(busy = false, editor = null) }
                    sendEffect(
                        RecceEffect.Notice(if (status == RecceStatus.Published) "Recce published" else "Draft saved"),
                    )
                    refresh()
                    open(outcome.data)
                }
            }
        }
    }

    private fun cancelEdit() {
        val editor = state.value.editor
        setState {
            copy(
                editor = null,
                page = editor?.id?.let { ReccePage.Detail(it) } ?: ReccePage.Index,
            )
        }
    }

    private fun delete() {
        val id = state.value.confirmDelete ?: return
        setState { copy(busy = true) }
        launch {
            when (val result = repository.delete(id)) {
                is ZillitResult.Failure -> setState {
                    copy(busy = false, confirmDelete = null, error = result.error.userMessage)
                }
                is ZillitResult.Success -> {
                    setState {
                        val viewingDeleted =
                            (page as? ReccePage.Detail)?.id == id || (page as? ReccePage.Form)?.id == id
                        copy(
                            busy = false,
                            confirmDelete = null,
                            recces = recces.filterNot { it.id == id },
                            page = if (viewingDeleted) ReccePage.Index else page,
                            selected = if (viewingDeleted) null else selected,
                            editor = if (viewingDeleted) null else editor,
                        )
                    }
                    sendEffect(RecceEffect.Notice("Recce deleted"))
                }
            }
        }
    }

    private fun generatePdf() {
        val id = state.value.selected?.id ?: return
        if (!state.value.viewer.mayDownload) {
            setState { copy(error = "You do not have download rights for Recce") }
            return
        }
        setState { copy(busy = true) }
        launch {
            val outcome = when (val report = repository.report(id)) {
                is ZillitResult.Failure -> report
                is ZillitResult.Success -> transfer.openReport(report.data)
            }
            when (outcome) {
                is ZillitResult.Failure -> setState { copy(busy = false, error = outcome.error.userMessage) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(RecceEffect.Notice("PDF generated"))
                }
            }
        }
    }

    private inline fun guardPost(block: () -> Unit) {
        if (state.value.viewer.mayEdit) {
            block()
        } else {
            setState { copy(error = "You do not have posting rights for Recce") }
        }
    }

    private fun editEditor(transform: RecceEditor.() -> RecceEditor) {
        setState { copy(editor = editor?.transform()?.copy(dirty = true)) }
    }

    private fun <T> ZillitResult<T>.orError(): T? = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> {
            val message = this.error.userMessage
            setState { copy(error = message) }
            null
        }
    }

    private fun <T> ZillitResult<T>.map(transform: (T) -> String): ZillitResult<String> = when (this) {
        is ZillitResult.Success -> ZillitResult.Success(transform(data))
        is ZillitResult.Failure -> this
    }
}

/** A seed row nobody touched — it gives way to the first real person. */
private fun PersonEditor.isBlankSeed(): Boolean =
    userId == null && name.isBlank() && role.isBlank() && contact.isBlank()

private fun <T> List<T>.moved(index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in indices || target !in indices) return this
    return toMutableList().also { it.add(target, it.removeAt(index)) }
}

private fun List<com.zillit.desktop.feature.recce.domain.Recce>.replacing(
    recce: com.zillit.desktop.feature.recce.domain.Recce,
) = if (any { it.id == recce.id }) map { if (it.id == recce.id) recce else it } else listOf(recce) + this
