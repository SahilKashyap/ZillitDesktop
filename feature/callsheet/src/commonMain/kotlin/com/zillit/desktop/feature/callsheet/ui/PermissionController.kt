package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.feature.callsheet.domain.AccessPerson

/**
 * The Permission tab — `PermissionTab.jsx`: one page of the crew axis of the
 * permission grid, and the call sheet's own switch that grants view, posting
 * and download together.
 *
 * Divergences, each fixing a web bug: the search filters the loaded page
 * once, without the web's 300 ms re-render loop (§11-20); the checkbox flips
 * only after the server answers, as on the web, and a person with no call
 * sheet cell is not listed.
 */
internal class PermissionController(private val ctx: SheetContext) {

    private var loadSerial = 0L

    fun onEvent(event: PermissionEvent) {
        when (event) {
            is PermissionEvent.Search -> update { copy(search = event.query) }
            is PermissionEvent.Page -> {
                val state = ctx.state.permission
                val pages = pageCount(state.total, state.pageSize)
                update { copy(page = event.page.coerceIn(0, (pages - 1).coerceAtLeast(0))) }
                load()
            }
            is PermissionEvent.PageSize -> {
                update { copy(pageSize = event.size, page = 0) }
                load()
            }
            is PermissionEvent.Toggle -> toggle(event.person, event.enable)
            PermissionEvent.Retry -> load()
        }
    }

    /** The tab opened, or a page asked for: the grid's crew axis, the viewer's own row dropped. */
    fun load() {
        val state = ctx.state
        if (!state.isPoster || !state.viewer.canViewGrid) return
        val serial = ++loadSerial
        val page = state.permission.page
        val size = state.permission.pageSize
        update { copy(loading = true, error = null) }
        ctx.launchWork {
            val result = ctx.repository.accessPage(page, size, ctx.state.me)
            if (serial != loadSerial) return@launchWork
            when (result) {
                is ZillitResult.Success -> update {
                    copy(
                        people = result.data.people,
                        total = result.data.total,
                        loading = false,
                        loaded = true,
                        processing = emptySet(),
                    )
                }
                is ZillitResult.Failure -> update {
                    copy(loading = false, loaded = true, error = result.error.localised())
                }
            }
        }
    }

    private fun toggle(person: AccessPerson, enable: Boolean) {
        val state = ctx.state
        if (!state.isPoster || !state.viewer.canEditGrid || person.isAdmin) return
        if (person.userId in state.permission.processing) return
        update { copy(processing = processing + person.userId) }
        ctx.launchWork {
            when (val result = ctx.repository.setToolAccess(person.userId, enable)) {
                is ZillitResult.Success -> {
                    val grant = result.data
                    update {
                        copy(
                            processing = processing - person.userId,
                            people = people.map { row ->
                                if (row.userId != person.userId) {
                                    row
                                } else {
                                    row.copy(
                                        canView = grant.canView,
                                        canPost = grant.canPost,
                                        canDownload = grant.canDownload,
                                    )
                                }
                            },
                        )
                    }
                    val fallback = if (enable) POSTING_UPDATED else VIEWING_UPDATED
                    ctx.toast(message(grant.message.ifBlank { fallback }))
                }
                is ZillitResult.Failure -> {
                    update { copy(processing = processing - person.userId) }
                    ctx.toast(result.error.localised().ifBlank { "Something went wrong" }, isError = true)
                }
            }
        }
    }

    /** A server message key as words: the dictionary's text, else a readable sentence. */
    private fun message(key: String): String =
        Labels.current.exact(key) ?: KNOWN_MESSAGES[key] ?: key.localisedMessage()

    private inline fun update(crossinline change: PermissionState.() -> PermissionState) = ctx.update {
        copy(permission = permission.change())
    }

    private companion object {
        const val POSTING_UPDATED = "posting_rights_updated_successfully"
        const val VIEWING_UPDATED = "viewing_rights_updated_successfully"
        val KNOWN_MESSAGES = mapOf(
            POSTING_UPDATED to "Posting rights updated successfully",
            VIEWING_UPDATED to "Viewing rights updated successfully",
        )
    }
}

internal fun pageCount(total: Int, size: Int): Int = if (size <= 0) 1 else ((total + size - 1) / size).coerceAtLeast(1)
