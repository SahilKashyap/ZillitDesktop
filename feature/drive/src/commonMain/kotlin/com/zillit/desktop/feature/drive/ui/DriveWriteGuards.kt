package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.eligible

/**
 * The guards the screen already applies, stated where the write happens.
 *
 * Drive checks delete, download, share and view in their own handlers, but
 * create, rename and move run through `mutate`, which asks nothing. These say
 * what those three need, so the view model reads as one line each.
 *
 * Each returns the refusal to show, or null to let the write through.
 */
internal fun DriveUiState.refusalToCreate(): String? =
    "You do not have permission to create that.".takeIf { !viewer.canCreate }

/**
 * A row the listing no longer holds falls back to the tool-level grant rather
 * than to a free pass: `may` is the tool's right *and* the item's, so without
 * the item the tool's half is what is left to ask. Letting an unknown row
 * through would have made "not currently listed" a way round the check.
 */
internal fun DriveUiState.refusalToEdit(refs: List<DriveRef>): String? {
    val matched = items.filter { item -> refs.any { it.id == item.id } }
    val allowed = if (matched.isEmpty()) {
        viewer.canCreate
    } else {
        viewer.eligible(DriveAction.Edit, matched).isNotEmpty()
    }
    return "You do not have permission to change that.".takeIf { !allowed }
}
