package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.DocDistHost
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRepository
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.datetime.LocalDate

/**
 * What a section of the view model may touch.
 *
 * The tool is one view model with one state, but its handlers span six
 * surfaces; one class holding all of them is a two-thousand-line file. Each
 * surface's handlers live in their own `*Section` over this narrow seam, and
 * the view model itself only routes events. Nothing here is public: the seam
 * exists so the sections can be read on their own, not so anyone else can
 * reach the state.
 */
internal interface VmScope {
    val state: DocDistUiState
    val repository: DocDistRepository
    val host: DocDistHost

    fun update(reducer: DocDistUiState.() -> DocDistUiState)
    fun run(block: suspend CoroutineScope.() -> Unit): Job

    fun report(error: ZillitError)
    fun fail(message: String)
    fun notice(message: String)

    /** Today, in the machine's zone — for default dates. */
    fun today(): LocalDate

    /** Reloads whatever page is on screen. */
    fun reload()

    /** Reloads the library listing under the current folder and filters. */
    fun loadLibrary()

    /** Refuses a write without posting rights, offering the ask; true when refused. */
    fun refusesWrite(): Boolean

    /** Refuses a download without download rights, offering the ask; true when refused. */
    fun refusesDownload(): Boolean

    fun askForRights(kind: RightsKind)
}

/** Reports the failure, or hands the value on. */
internal inline fun <T> VmScope.onSuccess(result: ZillitResult<T>, block: (T) -> Unit) {
    when (result) {
        is ZillitResult.Success -> block(result.data)
        is ZillitResult.Failure -> report(result.error)
    }
}

/** The one folder ticked, when exactly one is — the composer's and publish's folder context. */
internal fun DocDistUiState.singleSelectedFolder(): LibraryFolder? =
    selectedFolderIds.singleOrNull()?.let { id -> folders.firstOrNull { it.id == id } }

/** Adds or removes, whichever the current membership implies. */
internal fun <T> Set<T>.toggled(value: T): Set<T> =
    if (value in this) this - value else this + value

/** "1 file", "3 files" — [one] and [other] are catalogue keys taking the count. */
internal fun plural(count: Int, one: String, other: String): String = str(if (count == 1) one else other, count)
