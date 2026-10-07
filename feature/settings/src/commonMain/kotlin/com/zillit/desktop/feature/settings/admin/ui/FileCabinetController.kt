package com.zillit.desktop.feature.settings.admin.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.domain.AdminRepository
import com.zillit.desktop.feature.settings.admin.domain.cabinetModules

/**
 * The File Cabinet page's behaviour.
 *
 * Its own class because [AdminViewModel] already carries sixteen pages, and the
 * cabinet is the one with a life of its own: a request that outlives the page,
 * a socket that finishes it, a download that leaves the app. It reaches back
 * for exactly what it needs — the state, a way to change it, a way to launch
 * work and a way to reload — and nothing else.
 */
internal class FileCabinetController(
    private val repository: AdminRepository,
    private val isAdmin: () -> Boolean,
    private val state: () -> AdminUiState,
    private val update: (AdminUiState.() -> AdminUiState) -> Unit,
    private val launch: (suspend () -> Unit) -> Unit,
    private val reload: () -> Unit,
) {

    /**
     * The File Cabinet: which modules this production can archive, and whether
     * a ZIP is already being prepared. A request that cannot be read does not
     * hide the list — an admin can still see what there is, and the strip says
     * why the buttons are not what they expected.
     */
    fun onEvent(event: AdminEvent.Cabinet) {
        when (event) {
            is AdminEvent.CabinetToggled -> toggle(event.identifier)
            AdminEvent.CabinetSelectAllToggled -> toggleSelectAll()
            AdminEvent.CabinetRequestDownload -> requestDownload()
            AdminEvent.CabinetCancelRequest -> cancelRequest()
            AdminEvent.CabinetDownload -> download()
            AdminEvent.CabinetUrlOpened -> update { copy(fileCabinet = fileCabinet.copy(downloadUrl = null)) }
            AdminEvent.CabinetNoticeDismissed -> update { copy(fileCabinet = fileCabinet.copy(showNotice = false)) }
        }
    }

    suspend fun load(): ZillitResult<Unit> {
        val tools = repository.tools(includeAlwaysOn = true)
        val request = repository.downloadRequest()

        tools.getOrNull()?.let { rows ->
            val modules = cabinetModules(rows, { it.name.localised() }, str(S.group_chat), str(S.home))
            update {
                copy(
                    fileCabinet = fileCabinet.copy(
                        modules = modules,
                        // A reload must not keep a tick on a module that is gone.
                        selected = fileCabinet.selected.filterTo(mutableSetOf()) { id ->
                            modules.any { it.identifier == id }
                        },
                    ),
                )
            }
        }
        (request as? ZillitResult.Success)?.let { done ->
            update { copy(fileCabinet = fileCabinet.copy(request = done.data)) }
        }
        return (tools as? ZillitResult.Failure) ?: (request as? ZillitResult.Failure) ?: ZillitResult.Success(Unit)
    }

    /** Ticks and unticks follow the web: nothing changes while a request exists. */
    private fun toggle(identifier: String) = update {
        if (fileCabinet.request != null) {
            this
        } else {
            val now = if (identifier in fileCabinet.selected) {
                fileCabinet.selected - identifier
            } else {
                fileCabinet.selected + identifier
            }
            copy(fileCabinet = fileCabinet.copy(selected = now))
        }
    }

    private fun toggleSelectAll() = update {
        if (fileCabinet.request != null) {
            this
        } else {
            val now = if (fileCabinet.allSelected) emptySet() else fileCabinet.modules.map { it.identifier }.toSet()
            copy(fileCabinet = fileCabinet.copy(selected = now))
        }
    }

    private fun requestDownload() {
        val cabinet = state().fileCabinet
        // The web's order, not the order of the ticks.
        val identifiers = cabinet.modules.map { it.identifier }.filter { it in cabinet.selected }
        if (identifiers.isEmpty() || cabinet.request != null) return

        cabinetCall(reload = true, call = { repository.requestDownload(identifiers) }) { request ->
            copy(fileCabinet = fileCabinet.copy(request = request, selected = emptySet(), showNotice = true))
        }
    }

    private fun cancelRequest() {
        val request = state().fileCabinet.request ?: return
        cabinetCall(reload = true, call = { repository.cancelDownload(request.id) }) {
            copy(fileCabinet = fileCabinet.copy(request = null, selected = emptySet()))
        }
    }

    private fun download() {
        val request = state().fileCabinet.request?.takeIf { !it.isPreparing } ?: return
        cabinetCall(reload = false, call = { repository.downloadUrl(request.id) }) { url ->
            // The web clears its copy of the request once it has opened the URL.
            copy(
                outcome = str(S.desktop_fc_download_started),
                fileCabinet = fileCabinet.copy(request = null, downloadUrl = url),
            )
        }
    }

    /**
     * One File Cabinet call: admins only, one at a time, the failure on the strip.
     *
     * Not `AdminViewModel.mutate`: that reloads the list and closes a form, and neither is what
     * these do — the result is applied to the page directly, and [reload] reads
     * the server's own view of the request afterwards when the answer might not
     * be the whole story.
     */
    private fun <T> cabinetCall(
        reload: Boolean,
        call: suspend () -> ZillitResult<T>,
        onSuccess: AdminUiState.(T) -> AdminUiState,
    ) {
        if (!isAdmin()) {
            update { copy(error = str(S.desktop_only_admin_can_change)) }
            return
        }
        if (state().fileCabinet.isWorking) return
        update { copy(error = null, fileCabinet = fileCabinet.copy(isWorking = true)) }

        launch {
            when (val result = call()) {
                is ZillitResult.Success -> {
                    update {
                        val applied = onSuccess(result.data)
                        applied.copy(fileCabinet = applied.fileCabinet.copy(isWorking = false))
                    }
                    if (reload) reload()
                }

                is ZillitResult.Failure -> update {
                    copy(error = result.error.readable, fileCabinet = fileCabinet.copy(isWorking = false))
                }
            }
        }
    }
}
