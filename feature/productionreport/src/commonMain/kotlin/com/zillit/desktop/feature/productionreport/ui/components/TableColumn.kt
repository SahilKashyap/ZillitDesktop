package com.zillit.desktop.feature.productionreport.ui.components

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp

/** One column: a fixed width, or a share of what is left. */
internal data class TableColumn(
    val title: String,
    val width: Dp? = null,
    val weight: Float = 1f,
    val alignment: Alignment.Horizontal = Alignment.Start,
)
