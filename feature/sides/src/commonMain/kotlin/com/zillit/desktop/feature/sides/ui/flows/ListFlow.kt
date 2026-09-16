package com.zillit.desktop.feature.sides.ui.flows

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.feature.sides.domain.Script
import com.zillit.desktop.feature.sides.domain.SidesRecord
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.ui.ConfirmKind
import com.zillit.desktop.feature.sides.ui.SidesDialog
import com.zillit.desktop.feature.sides.ui.SidesLayout
import com.zillit.desktop.feature.sides.ui.SidesStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * The generated-sides list, its archived history and the row verbs — the
 * web's `SidesPage`.
 *
 * Alongside the list it fetches the three Generate gates the web reads
 * before opening either generator: the active script, whether any archived
 * script exists, and the latest schedule. They are only needed by a poster,
 * so a viewer without the right skips those calls, as the web does.
 */
internal class ListFlow(
    private val store: SidesStore,
    private val pdf: PdfFlow,
) {

    private var generatingWatch: Job? = null

    fun load() {
        store.update { copy(list = list.copy(loading = true)) }
        store.runTask {
            val mayPost = store.current.viewer.mayPost
            val (sides, gates) = coroutineScope {
                val sides = async { store.repository.sides(history = false) }
                val gates = async { if (mayPost) loadGates() else null }
                sides.await() to gates.await()
            }
            store.update {
                copy(
                    list = list.copy(
                        loading = false,
                        sides = (sides as? ZillitResult.Success)?.data ?: list.sides,
                        activeScript = gates?.activeScript ?: list.activeScript,
                        hasHistoryScripts = gates?.hasHistory ?: list.hasHistoryScripts,
                        latestScheduleId = gates?.scheduleId ?: list.latestScheduleId,
                    ),
                )
            }
            if (sides is ZillitResult.Failure) store.failed(sides.error)
            if (store.current.list.historyOpen) loadHistory()
            watchGenerating()
        }
    }

    private class Gates(val activeScript: Script?, val hasHistory: Boolean, val scheduleId: String)

    private suspend fun loadGates(): Gates = coroutineScope {
        val active = async { store.repository.activeScript() }
        val history = async { store.repository.scriptsHistory(limit = 1) }
        val schedules = async { store.repository.schedules(limit = 1) }
        Gates(
            activeScript = (active.await() as? ZillitResult.Success)?.data,
            hasHistory = (history.await() as? ZillitResult.Success)?.data?.isNotEmpty() ?: false,
            scheduleId = (schedules.await() as? ZillitResult.Success)?.data?.firstOrNull()?.id.orEmpty(),
        )
    }

    fun loadHistory() {
        store.update { copy(list = list.copy(historyLoading = true)) }
        store.runTask {
            when (val history = store.repository.sides(history = true)) {
                is ZillitResult.Success -> store.update {
                    copy(list = list.copy(historyLoading = false, history = history.data))
                }
                is ZillitResult.Failure -> {
                    store.update { copy(list = list.copy(historyLoading = false)) }
                    store.failed(history.error)
                }
            }
        }
    }

    /** The web's fallback poll: a 5 s re-fetch while anything is generating. */
    private fun watchGenerating() {
        if (!store.current.list.anyGenerating) return
        if (generatingWatch?.isActive == true) return
        generatingWatch = store.runTask {
            delay(GENERATING_REFETCH_MS)
            if (store.current.generate == null) load()
        }
    }

    fun setLayout(layout: SidesLayout) = store.update { copy(list = list.copy(layout = layout)) }

    fun toggleHistory() {
        val opening = !store.current.list.historyOpen
        store.update { copy(list = list.copy(historyOpen = opening)) }
        if (opening) loadHistory()
    }

    fun searchHistory(query: String) = store.update { copy(list = list.copy(historySearch = query)) }

    /** The Autogenerate gate: posting rights, then a published script with a file. */
    fun activeScriptForAutogenerate(): String? {
        if (store.refuses(RightsKind.Post)) return null
        val active = store.current.list.activeScript
        if (active?.currentVersion == null) {
            store.failed("No published script available. Please upload script to generate sides")
            return null
        }
        return active.id
    }

    /** The Generate gate: posting rights, then an active script OR any archived one. */
    fun mayOpenGenerate(): Boolean {
        if (store.refuses(RightsKind.Post)) return false
        val list = store.current.list
        if (list.activeScript?.currentVersion == null && !list.hasHistoryScripts) {
            store.failed("No active script or pages found. Please upload script to pages to generate sides")
            return false
        }
        return true
    }

    fun view(record: SidesRecord) = pdf.open(
        title = record.title.ifBlank { "Sides" },
        subtitle = "Sides",
        fileName = SidesRules.downloadName(record.title),
    ) { store.repository.downloadUrl(record.id, countDownload = false) }

    fun download(record: SidesRecord) {
        if (store.refuses(RightsKind.Download)) return
        pdf.save(SidesRules.downloadName(record.title)) {
            store.repository.downloadUrl(record.id, countDownload = true)
        }
    }

    fun askDelete(record: SidesRecord) {
        if (store.refuses(RightsKind.Post)) return
        store.update {
            copy(
                dialog = SidesDialog.Confirm(
                    kind = ConfirmKind.Sides,
                    id = record.id,
                    title = "Delete sides?",
                    message = "This permanently deletes \"${record.title}\".",
                ),
            )
        }
    }

    suspend fun delete(id: String): Boolean = when (val deleted = store.repository.deleteSides(id)) {
        is ZillitResult.Success -> {
            store.notice("Deleted")
            load()
            true
        }
        is ZillitResult.Failure -> {
            store.failed(deleted.error)
            false
        }
    }

    private companion object {
        const val GENERATING_REFETCH_MS = 5_000L
    }
}
