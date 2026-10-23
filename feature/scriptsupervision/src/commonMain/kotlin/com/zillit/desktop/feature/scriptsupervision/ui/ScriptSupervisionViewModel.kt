package com.zillit.desktop.feature.scriptsupervision.ui

import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.permissions.rightsRefusalMessage
import com.zillit.desktop.feature.scriptsupervision.data.ScriptSupervisionApi
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptSupervisionViewer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** A line of feedback over the page — a write's outcome, in the server's own words. */
data class SsNotice(val text: String, val ok: Boolean)

data class SsState(
    val viewer: ScriptSupervisionViewer = ScriptSupervisionViewer(),
    val page: SsPage = SsPage.Start,
    val projectId: String? = null,
    val toast: SsNotice? = null,
)

sealed interface SsEvent {
    /** The tool's window opened, or the production changed. */
    data object Load : SsEvent
    data class Open(val page: SsPage) : SsEvent
    data class AskRights(val kind: RightsKind) : SsEvent
    data object DismissToast : SsEvent
}

/**
 * What every Continuity Script Supervision screen shares: the rights gate, the
 * open production, and where the reader is — the web's module context.
 *
 * Rights first, as Costumes & Set Sync does it: the service refuses a
 * production whose tool is switched off, and a 403 anywhere in this app is
 * read as "no longer a member", so nothing calls until the tool's own row has
 * confirmed it. The view model is built once per graph — before any production
 * is open — so the viewer is a lambda resolved on each [SsEvent.Load], and
 * while it is unresolved it is re-read a few times rather than left denied:
 * the rights payload lands a beat after the production does.
 */
class ScriptSupervisionViewModel(
    val api: ScriptSupervisionApi,
    private val viewer: () -> ScriptSupervisionViewer,
    private val rights: RightsRequestBus? = null,
    private val projectId: () -> String? = { null },
) : ZillitViewModel<SsState, SsEvent, Nothing>(SsState()) {

    private var rightsPoll: Job? = null

    override fun onEvent(event: SsEvent) {
        when (event) {
            SsEvent.Load -> load()
            is SsEvent.Open -> setState { copy(page = event.page) }
            is SsEvent.AskRights -> askRights(event.kind)
            SsEvent.DismissToast -> setState { copy(toast = null) }
        }
    }

    /** Called each time the tool's window is shown. */
    fun start() = onEvent(SsEvent.Load)

    fun open(page: SsPage) = onEvent(SsEvent.Open(page))

    fun toast(text: String, ok: Boolean) = setState { copy(toast = SsNotice(text, ok)) }

    fun dismissToast() = onEvent(SsEvent.DismissToast)

    fun askRights(kind: RightsKind) {
        rights?.ask(toolTitle(), kind)
        toast(rightsRefusalMessage(toolTitle(), kind, asked = rights != null), ok = false)
    }

    private fun load() {
        setState { copy(viewer = viewer(), projectId = projectId()) }
        if (currentState.viewer.resolved) return
        rightsPoll?.cancel()
        rightsPoll = launch {
            repeat(RIGHTS_POLLS) {
                delay(RIGHTS_POLL_MS)
                val again = viewer()
                if (again.resolved) {
                    setState { copy(viewer = again, projectId = projectId()) }
                    return@launch
                }
            }
        }
    }

    private companion object {
        const val RIGHTS_POLLS = 20
        const val RIGHTS_POLL_MS = 250L
    }
}
