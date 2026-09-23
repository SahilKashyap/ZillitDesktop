package com.zillit.desktop.feature.sides.ui.flows

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.sides.domain.AutoPlan
import com.zillit.desktop.feature.sides.domain.CallSheetRef
import com.zillit.desktop.feature.sides.domain.GeneratePoller
import com.zillit.desktop.feature.sides.domain.ScheduleRef
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.domain.parseSceneList
import com.zillit.desktop.feature.sides.ui.AutoState
import com.zillit.desktop.feature.sides.ui.ConfirmKind
import com.zillit.desktop.feature.sides.ui.DocKind
import com.zillit.desktop.feature.sides.ui.SidesDialog
import com.zillit.desktop.feature.sides.ui.SidesStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The Autogenerate dialog — the web's `AutogenerateSidesModal`.
 *
 * Call sheets are managed here (there is no standalone page): pick a
 * published or uploaded one, or upload a new one; the selected sheet's
 * extracted scenes drive the run, in call-sheet order unless rearranged.
 * A schedule is optional. Published sheets cannot be deleted — the backend
 * protects them — so only uploaded rows offer the bin.
 */
@Suppress("TooManyFunctions") // One handler per dialog act.
internal class AutoFlow(
    private val store: SidesStore,
    private val pdf: PdfFlow,
    private val onPublished: () -> Unit,
) {

    private fun form(change: AutoState.() -> AutoState) = store.update { copy(auto = auto?.change()) }

    fun open(scriptId: String, scheduleId: String) {
        store.update { copy(auto = AutoState(scriptId = scriptId, selectedScheduleId = scheduleId)) }
        store.runTask {
            val (scripts, sheets, schedules) = coroutineScope {
                val scripts = async { store.repository.scripts() }
                val sheets = async { store.repository.callSheets() }
                val schedules = async { store.repository.schedules() }
                Triple(scripts.await(), sheets.await(), schedules.await())
            }
            val loadedSheets = (sheets as? ZillitResult.Success)?.data.orEmpty()
            form {
                copy(
                    loading = false,
                    scriptTitle = (scripts as? ZillitResult.Success)?.data
                        ?.firstOrNull { it.id == scriptId }?.title.orEmpty(),
                    callSheets = loadedSheets,
                    schedules = (schedules as? ZillitResult.Success)?.data.orEmpty(),
                )
            }
            listOf(scripts, sheets, schedules).filterIsInstance<ZillitResult.Failure>()
                .firstOrNull()?.let { store.failed(it.error) }
            // Default to the latest call sheet once loaded.
            loadedSheets.firstOrNull()?.let { selectCallSheet(it.id) }
        }
    }

    fun close() {
        val current = store.current.auto ?: return
        if (current.running || current.publishing) return
        store.update { copy(auto = null) }
    }

    // ── Call sheets ──

    fun selectCallSheet(id: String) {
        form { copy(selectedCallSheetId = id, rearrange = false, order = emptyList(), orderText = "", detail = null) }
        if (id.isBlank()) return
        form { copy(detailLoading = true) }
        store.runTask {
            val detail = store.repository.callSheet(id)
            if (store.current.auto?.selectedCallSheetId != id) return@runTask
            form { copy(detailLoading = false, detail = (detail as? ZillitResult.Success)?.data) }
            if (detail is ZillitResult.Failure) store.failed(detail.error)
        }
    }

    fun viewCallSheet(sheet: CallSheetRef) = pdf.open(
        title = sheet.title,
        subtitle = str(S.sides_section_call_sheet),
        fileName = SidesRules.downloadName(sheet.title, "call_sheet"),
    ) { store.transfer.presign(sheet.attachment) }

    fun askDeleteCallSheet(sheet: CallSheetRef) = store.update {
        copy(
            dialog = SidesDialog.Confirm(
                kind = ConfirmKind.CallSheet,
                id = sheet.id,
                title = str(S.desktop_sides_delete_call_sheet_title),
                message = str(S.desktop_sides_delete_call_sheet_message),
            ),
        )
    }

    suspend fun deleteCallSheet(id: String): Boolean =
        when (val deleted = store.repository.deleteCallSheet(id)) {
            is ZillitResult.Success -> {
                if (store.current.auto?.selectedCallSheetId == id) selectCallSheet("")
                store.notice(str(S.desktop_sides_call_sheet_deleted))
                reloadCallSheets()
                true
            }
            is ZillitResult.Failure -> {
                store.failed(deleted.error)
                false
            }
        }

    private fun reloadCallSheets() = store.runTask {
        (store.repository.callSheets() as? ZillitResult.Success)?.let { form { copy(callSheets = it.data) } }
    }

    fun upload(kind: DocKind) = store.update { copy(dialog = SidesDialog.UploadDoc(kind = kind)) }

    /** A fresh upload becomes the selection, as on the web. */
    fun callSheetUploaded(id: String?) {
        reloadCallSheets()
        if (!id.isNullOrBlank()) selectCallSheet(id)
    }

    // ── Schedules ──

    fun selectSchedule(id: String) = form { copy(selectedScheduleId = id) }

    fun viewSchedule(schedule: ScheduleRef) = pdf.open(
        title = schedule.title,
        subtitle = str(S.schedule),
        fileName = SidesRules.downloadName(schedule.title, "schedule"),
    ) { store.repository.scheduleDownloadUrl(schedule.id) }

    fun askDeleteSchedule(schedule: ScheduleRef) = store.update {
        copy(
            dialog = SidesDialog.Confirm(
                kind = ConfirmKind.Schedule,
                id = schedule.id,
                title = str(S.desktop_sides_delete_schedule_title),
                message = str(S.desktop_sides_delete_schedule_message),
            ),
        )
    }

    suspend fun deleteSchedule(id: String): Boolean =
        when (val deleted = store.repository.deleteSchedule(id)) {
            is ZillitResult.Success -> {
                if (store.current.auto?.selectedScheduleId == id) form { copy(selectedScheduleId = "") }
                store.notice(str(S.desktop_sides_schedule_deleted))
                reloadSchedules()
                true
            }
            is ZillitResult.Failure -> {
                store.failed(deleted.error)
                false
            }
        }

    private fun reloadSchedules() = store.runTask {
        (store.repository.schedules() as? ZillitResult.Success)?.let { form { copy(schedules = it.data) } }
    }

    fun scheduleUploaded(id: String?) {
        reloadSchedules()
        if (!id.isNullOrBlank()) selectSchedule(id)
    }

    // ── Order and options ──

    fun rearrange(on: Boolean) = form {
        val seed = if (on && order.isEmpty()) scenes else order
        copy(rearrange = on, order = seed, orderText = seed.joinToString(", "))
    }

    /** Chips dragged: the order and the typed text agree. */
    fun order(order: List<String>) = form { copy(order = order, orderText = order.joinToString(", ")) }

    /** Typed: parsed on the web's split rule, the chips follow. */
    fun orderText(text: String) = form { copy(orderText = text, order = parseSceneList(text)) }

    fun displayMode(mode: String) = form { copy(displayMode = mode) }

    // ── Run and review ──

    fun generate() {
        val current = store.current.auto ?: return
        if (current.running || store.refuses(RightsKind.Post)) return
        val refusal = when {
            current.selectedCallSheetId.isBlank() -> str(S.sides_pick_call_sheet_first)
            current.orderedScenes.isEmpty() -> str(S.sides_no_scenes_to_generate)
            else -> null
        }
        if (refusal != null) return store.failed(refusal)
        val plan = AutoPlan(
            scriptId = current.scriptId,
            callSheetId = current.selectedCallSheetId,
            scheduleId = current.selectedScheduleId,
            sceneNumbers = current.orderedScenes,
            displayMode = current.displayMode,
            title = current.callSheetTitle.takeIf { it.isNotBlank() }?.let { "Sides - $it" }.orEmpty(),
        )
        form { copy(running = true, result = null, viewed = false) }
        store.runTask {
            val poller = GeneratePoller(
                generate = { store.repository.autoGenerate(plan) },
                get = store.repository::sidesById,
            )
            when (val outcome = poller.run(onTick = { tick -> form { copy(result = tick) } })) {
                is ZillitResult.Success -> {
                    form { copy(running = false, result = outcome.data.sides) }
                    if (outcome.data.timedOut) store.failed(str(S.desktop_sides_still_rendering))
                }
                is ZillitResult.Failure -> {
                    form { copy(running = false, result = null) }
                    store.failed(outcome.error)
                }
            }
        }
    }

    fun view() {
        val result = store.current.auto?.result ?: return
        form { copy(viewed = true) }
        pdf.open(
            title = result.title.ifBlank { str(S.txt_sides) },
            subtitle = str(S.desktop_sides_generated_subtitle),
            fileName = SidesRules.downloadName(result.title),
        ) { store.repository.downloadUrl(result.id, countDownload = false) }
    }

    fun download() {
        val result = store.current.auto?.result ?: return
        if (store.refuses(RightsKind.Download)) return
        pdf.save(SidesRules.downloadName(result.title)) {
            store.repository.downloadUrl(result.id, countDownload = true)
        }
    }

    fun publish() {
        val result = store.current.auto?.result ?: return
        if (store.current.auto?.publishing == true) return
        form { copy(publishing = true) }
        store.runTask {
            when (val published = store.repository.publish(result.id)) {
                is ZillitResult.Success -> {
                    store.update { copy(auto = null) }
                    store.notice(str(S.desktop_sides_published))
                    onPublished()
                }
                is ZillitResult.Failure -> {
                    form { copy(publishing = false) }
                    store.failed(published.error)
                }
            }
        }
    }
}
