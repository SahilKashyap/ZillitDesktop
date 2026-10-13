package com.zillit.desktop

import com.zillit.desktop.core.cabinet.CabinetViewer
import com.zillit.desktop.core.cabinet.CabinetViewers

/**
 * The File Cabinet's read-only viewers, one per row a tool contributes.
 *
 * Each tool's feature module exposes a `CabinetViewer` for the rows it owns;
 * this is the one place they are gathered and handed to Admin Settings, so
 * settings never depends on a tool. A row with no viewer here is select-and-
 * download only.
 */
internal fun AppGraph.Ready.cabinetViewers(): CabinetViewers = CabinetViewers(
    listOfNotNull<CabinetViewer>(
        // One line per viewer, added as each tool's viewer lands.
    ),
)
