package com.zillit.desktop.core.cabinet

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One thing a File Cabinet row opens: a Home unit, a chat room, "Full Script".
 *
 * @property key what the viewer needs to find it again (a unit id, a tab key).
 * @property name the label, already resolved for the reader.
 */
data class CabinetEntry(val key: String, val name: String)

/**
 * The read-only view of one File Cabinet row — the web mounts the tool's own
 * page inside a modal with its `isFileCabinet` flag set; the desktop asks the
 * feature that owns the tool for the same thing.
 *
 * Settings owns the list, the search, the selection and the window the viewer
 * appears in. A feature owns what is inside the window. That split is the
 * point of this interface: settings does not depend on any tool, and a tool
 * does not depend on settings.
 *
 * A viewer never writes. It reads the tool's records the way the web does with
 * the cabinet flag on (the archived ones), shows them, and offers whatever the
 * tool's own read-only actions are (open, download).
 */
interface CabinetViewer {
    /** The row's identifier — the web's `commonUnits[].identifier`, e.g. `home` or `script_distribution`. */
    val identifier: String

    /**
     * True when the row expands into [entries] rather than opening directly —
     * Home's units, Group Chat's rooms, Script Distribution's "Full Script" and
     * "Pages". A row that expands but has no entries yet (still loading, or a
     * production with none) opens nothing.
     */
    val expands: Boolean get() = false

    /**
     * What the row expands into. Called when the row is first opened, and again
     * if the page reloads; a failure is an empty list, as on the web, where an
     * empty submenu is just an empty submenu.
     */
    suspend fun entries(): List<CabinetEntry> = emptyList()

    /**
     * The viewer's body, for [entry] (null for a row that does not expand).
     * Fills [modifier] and scrolls itself; the window around it, its title and
     * its Close button are settings'.
     */
    @Composable
    fun Content(entry: CabinetEntry?, modifier: Modifier)
}

/** The viewers a build contributes, looked up by the row they serve. */
class CabinetViewers(viewers: List<CabinetViewer> = emptyList()) {
    private val byIdentifier = viewers.associateBy { it.identifier }

    /** The viewer for [identifier]; null where the row is select-and-download only. */
    operator fun get(identifier: String): CabinetViewer? = byIdentifier[identifier]

    companion object {
        /** No viewers: every row is select-and-download only. */
        val None = CabinetViewers()
    }
}
