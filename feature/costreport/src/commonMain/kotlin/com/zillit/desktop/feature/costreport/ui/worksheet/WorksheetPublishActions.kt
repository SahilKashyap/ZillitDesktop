package com.zillit.desktop.feature.costreport.ui.worksheet

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costreport.domain.CrDates
import com.zillit.desktop.feature.costreport.domain.PostCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotPost
import com.zillit.desktop.feature.costreport.domain.WeekWindow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The three writes that commit the report — Publish, Lock and Export — plus
 * the dialogs that only read it (Overages, History).
 *
 * Publish and Lock close their dialog the moment they start and hand over to
 * the progress card: a post re-aggregates every cost bucket and takes ten to
 * thirty seconds on a large production, and a dialog blocking on that is a
 * worse wait than a card in the corner.
 */
internal class WorksheetPublishActions(private val vm: WorksheetViewModel) {

    @Suppress("CyclomaticComplexMethod") // One branch per dialog control.
    fun handles(event: WorksheetEvent): Boolean {
        when (event) {
            WorksheetEvent.OpenPublish -> openPublish()
            is WorksheetEvent.EditPublish -> vm.update {
                copy(modal = (modal as? WorksheetModal.Publish)?.copy(form = event.form) ?: modal)
            }
            WorksheetEvent.ConfirmPublish -> publish()
            WorksheetEvent.OpenLock -> vm.update { copy(modal = WorksheetModal.Lock()) }
            is WorksheetEvent.SetLockNote -> vm.update {
                copy(modal = (modal as? WorksheetModal.Lock)?.copy(note = event.note) ?: modal)
            }
            WorksheetEvent.ConfirmLock -> lock()
            WorksheetEvent.OpenExport -> vm.update { copy(modal = WorksheetModal.Export()) }
            is WorksheetEvent.SetExportFormat -> vm.update {
                copy(modal = (modal as? WorksheetModal.Export)?.copy(format = event.format) ?: modal)
            }
            WorksheetEvent.ConfirmExport -> export()
            WorksheetEvent.OpenOverages -> vm.update { copy(modal = WorksheetModal.Overages) }
            is WorksheetEvent.SetFlagNote -> vm.update {
                copy(flagNotes = flagNotes + (event.headerCode to event.note))
            }
            WorksheetEvent.GoToLiveCr -> vm.update { copy(modal = null, pane = WorksheetPane.Live) }
            WorksheetEvent.OpenHistory -> vm.update { copy(modal = WorksheetModal.History) }
            WorksheetEvent.CloseModal -> closeModal()
            else -> return false
        }
        return true
    }

    /** A dialog whose write is in flight stays up, as the web's do. */
    private fun closeModal() {
        val state = vm.ui
        if (state.modal is WorksheetModal.SaveVersion && state.savingVersion) return
        vm.update { copy(modal = null) }
    }

    // -- publish ------------------------------------------------------------------------------

    /**
     * Seeded with what the worksheet is showing, so "post what I'm looking at"
     * is one click; the custom period defaults to the last seven days ending
     * today. The versions offered are the ones saved for the worksheet's week.
     */
    private fun openPublish() {
        val state = vm.ui
        val today = CrDates.localDate(vm.nowMillis())
        val form = PublishForm(
            cadence = PostCadence.Daily,
            companyId = state.ws.applied.companyId,
            budgetKey = state.ws.applied.budgetKey,
            currency = state.ws.applied.currency ?: state.reference.currencies.defaultCode,
            etcVersionId = state.ws.pending.versionId,
            startDate = today.minus(CUSTOM_DAYS_BACK, DateTimeUnit.DAY).toString(),
            endDate = today.toString(),
        )
        vm.update { copy(modal = WorksheetModal.Publish(form)) }
        val week = state.ws.week ?: return
        vm.launchWork {
            val versions = vm.repository.etcVersions(week.weekEnding).getOrNull().orEmpty()
            vm.update {
                val open = modal as? WorksheetModal.Publish ?: return@update this
                copy(modal = open.copy(form = open.form.copy(versions = versions)))
            }
        }
    }

    private fun publish() {
        val state = vm.ui
        val form = (state.modal as? WorksheetModal.Publish)?.form ?: return
        if (state.posting != null) return
        val start = form.startDate.asDate()
        val end = form.endDate.asDate()
        if (form.cadence == PostCadence.Custom && isFuture(start, end)) {
            vm.notice(str(S.desktop_cr_end_not_later), error = true)
            return
        }
        val custom = form.cadence == PostCadence.Custom
        val post = SnapshotPost(
            cadence = form.cadence,
            note = form.note.trim().ifBlank { null },
            companyId = form.companyId,
            budgetVersionId = state.reference.budget(form.budgetKey)?.id,
            currency = form.currency,
            etcVersionId = form.etcVersionId,
            periodStartMs = start?.takeIf { custom }?.let(CrDates::startOfDay),
            periodEndMs = end?.takeIf { custom }?.let(CrDates::endOfDay),
        )
        post.refusal?.let { refusal ->
            vm.notice(refusal, error = true)
            return
        }
        vm.update {
            copy(
                modal = null,
                posting = form.cadence,
                progress = CrProgress(
                    ProgressStatus.Loading,
                    str(S.desktop_cr_posting_progress, form.cadence.progressLabel),
                    str(S.desktop_cr_aggregating),
                ),
            )
        }
        vm.launchWork {
            val progress = when (val result = vm.repository.postSnapshot(post)) {
                is ZillitResult.Failure ->
                    CrProgress(ProgressStatus.Error, str(S.ah_post_failed_toast), result.error.localised())
                is ZillitResult.Success -> {
                    val reference = result.data.value?.reference?.ifBlank { null } ?: form.cadence.progressLabel
                    CrProgress(
                        ProgressStatus.Success,
                        str(S.desktop_cr_posted_reference, reference),
                        str(S.desktop_cr_snapshot_in_history),
                    )
                }
            }
            vm.update { copy(posting = null, progress = progress) }
            if (progress.status == ProgressStatus.Success) vm.silentRefresh()
        }
    }

    // -- lock -----------------------------------------------------------------------------------

    /**
     * Lock moves the project's boundary through the end of the worksheet's
     * week — forward only, and never back — then posts a snapshot of that week
     * named "Period Lock — …", so the history keeps what the report looked
     * like at the moment it closed. A lock that lands without its snapshot is
     * still a lock, and says so.
     */
    @Suppress("LongMethod") // One block, in one place; the sweep's wrapped calls added the lines.
    private fun lock() {
        val state = vm.ui
        val modal = state.modal as? WorksheetModal.Lock ?: return
        val week = state.ws.week ?: return
        if (state.locking) return
        val note = modal.note.trim()
        val budgetId = state.reference.budget(state.ws.applied.budgetKey)?.id
        val currency = state.ws.applied.currency
        vm.update {
            copy(
                modal = null,
                locking = true,
                progress = CrProgress(
                    ProgressStatus.Loading,
                    str(S.desktop_cr_locking_progress, week.label),
                    str(S.desktop_cr_posting_locked_period),
                ),
            )
        }
        vm.launchWork {
            val locked = vm.repository.lockPeriod(week.endMs)
            if (locked is ZillitResult.Failure) {
                vm.update {
                    copy(
                        locking = false,
                        progress = CrProgress(
                            ProgressStatus.Error,
                            str(S.desktop_cr_lock_failed),
                            locked.error.localised(),
                        ),
                    )
                }
                return@launchWork
            }
            val posted = vm.repository.postSnapshot(
                SnapshotPost(
                    cadence = PostCadence.Custom,
                    periodStartMs = week.startMs,
                    periodEndMs = week.endMs,
                    name = str(S.desktop_cr_period_lock_named, week.label),
                    budgetVersionId = budgetId,
                    currency = currency,
                    note = note.ifBlank { str(S.desktop_cr_auto_note_on_lock, week.label) },
                ),
            )
            val progress = when (posted) {
                is ZillitResult.Success ->
                    CrProgress(
                        ProgressStatus.Success,
                        str(S.desktop_cr_locked_named, week.label),
                        str(S.desktop_cr_snapshot_posted),
                    )
                is ZillitResult.Failure ->
                    CrProgress(
                        ProgressStatus.Error,
                        str(S.desktop_cr_locked_named, week.label),
                        str(S.desktop_cr_snapshot_post_failed, posted.error.localised()),
                    )
            }
            vm.update { copy(locking = false, progress = progress) }
            vm.silentRefresh()
        }
    }

    // -- export ------------------------------------------------------------------------------------

    /**
     * The worksheet as it stands — this week, this budget, this currency and
     * every typed override — rendered by the server and saved to Downloads.
     */
    private fun export() {
        val state = vm.ui
        val format = (state.modal as? WorksheetModal.Export)?.format ?: return
        val week = state.ws.week ?: return
        if (state.exporting && !state.exportDone) return
        vm.update { copy(modal = null, exporting = true, exportDone = false) }
        val body = exportBody(state, week)
        vm.launchWork {
            val outcome = when (val bytes = vm.exporter.exportReport(format, body)) {
                is ZillitResult.Failure -> bytes
                is ZillitResult.Success -> vm.files.saveAndOpen(
                    "cost-report-wk${week.number}-${week.weekEnding}.${format.wire}",
                    bytes.data,
                )
            }
            when (outcome) {
                is ZillitResult.Failure -> {
                    vm.update { copy(exporting = false, exportDone = false) }
                    vm.notice(outcome.error.localised(), error = true)
                }
                is ZillitResult.Success -> vm.update { copy(exportDone = true) }
            }
        }
    }

    private fun exportBody(state: WorksheetUiState, week: WeekWindow): JsonObject = buildJsonObject {
        put("period_start", week.startMs)
        put("period_end", week.endMs)
        put(
            "budget_version_id",
            state.reference.budget(state.ws.applied.budgetKey)?.id?.let(::JsonPrimitive) ?: JsonNull,
        )
        put("project_name", state.projectName)
        vm.companyName()?.takeIf { it.isNotBlank() }?.let { put("company_name", it) }
        put("period_label", week.label)
        state.generatedBy?.let { put("generated_by", it) }
        state.ws.applied.currency?.let { put("currency", it) }
        put("etc_overrides", amounts(state.ws.overrides.etc))
        put("efc_overrides", amounts(state.ws.overrides.efc))
        put("vtp_overrides", amounts(state.ws.overrides.vtp))
    }

    private fun amounts(values: Map<String, Double>) = buildJsonObject { values.forEach { (k, v) -> put(k, v) } }

    private fun String.asDate(): LocalDate? = runCatching { LocalDate.parse(trim()) }.getOrNull()

    /** The date inputs stop at today on the web; a typed date past it is refused here. */
    private fun isFuture(vararg dates: LocalDate?): Boolean {
        val today = CrDates.localDate(vm.nowMillis())
        return dates.any { it != null && it > today }
    }

    private companion object {
        /** A custom post's default window: the six days before today, and today. */
        const val CUSTOM_DAYS_BACK = 6
    }
}
