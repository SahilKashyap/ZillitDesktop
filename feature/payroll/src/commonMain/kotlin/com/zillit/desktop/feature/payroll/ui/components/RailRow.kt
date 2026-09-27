package com.zillit.desktop.feature.payroll.ui.components

import com.zillit.desktop.core.designsystem.component.StatusTone

/** One crew member in the producer surfaces' left-hand rail. */
internal data class RailRow(
    /** What selecting this row names: a timecard id on the board, a user id on the estimate. */
    val key: String,
    val userId: String,
    val name: String,
    val designation: String,
    val department: String,
    val statusLabel: String,
    val statusTone: StatusTone,
)
