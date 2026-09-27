package com.zillit.desktop.feature.payroll.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PayrollViewer

/**
 * The Notes a crew member left on their week — the web's
 * `TimecardNotesButton`.
 *
 * ## It gates itself
 *
 * Shown only to an accountant, and never on their OWN card: an accountant
 * reading their own week adds notes from their timecard instead. Every other
 * viewer — producers included — is shown nothing at all, which is why the
 * button is drawn here rather than by each surface.
 *
 * Read-only: the notes ride along on the timecard the surface already
 * fetched, and nothing here writes one.
 */
@Composable
internal fun TimecardNotesButton(
    viewer: PayrollViewer,
    timecard: PayrollTimecard?,
    crewName: String,
    modifier: Modifier = Modifier,
) {
    if (timecard == null || !viewer.isAccountant) return
    if (timecard.userId == viewer.userId) return
    var open by remember(timecard.id) { mutableStateOf(false) }
    ZillitButton(
        text = str(S.notes),
        onClick = { open = true },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.File,
        modifier = modifier,
    )
    // The button is offered even on a week with no notes: "none left" is
    // something the reader wants to know, not a reason to hide the door.
    NotesDialog(timecard, crewName, visible = open) { open = false }
}

@Composable
private fun NotesDialog(timecard: PayrollTimecard, crewName: String, visible: Boolean, onClose: () -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_payroll_timecard_notes, crewName),
        onDismiss = onClose,
        visible = visible,
        actions = {
            ZillitButton(text = str(S.close), onClick = onClose, variant = ButtonVariant.Secondary)
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            if (timecard.notes.isEmpty()) {
                ZillitText(
                    text = str(S.desktop_no_notes_yet),
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textMuted,
                )
                return@Column
            }
            // Newest first, as the web sorts them.
            timecard.notes.sortedByDescending { it.addedAt ?: 0L }.forEachIndexed { index, note ->
                if (index > 0) ZillitDivider()
                ZillitText(text = note.text, style = ZillitTheme.typography.bodyMedium)
                note.addedAt?.let {
                    ZillitText(
                        text = EpochDate.dateTime(it),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
        }
    }
}
