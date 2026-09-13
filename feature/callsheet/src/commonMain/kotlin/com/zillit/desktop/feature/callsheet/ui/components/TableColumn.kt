package com.zillit.desktop.feature.callsheet.ui.components

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp

/** One column: a fixed width, or a share of what is left. */
internal data class TableColumn(
    val title: String,
    val width: Dp? = null,
    val weight: Float = 1f,
    val alignment: Alignment.Horizontal = Alignment.Start,
)

/**
 * The two table looks the call sheet lists use: [Csc] — the rework's Drafts
 * table (`.csc-table`: card surface, radius 14, tracked uppercase header on
 * the secondary tint, hover rows) — and [Data], the older `DataTable`
 * (radius 12, report tokens, no hover) behind Approvals and Published history.
 */
internal enum class TableStyle { Csc, Data }
