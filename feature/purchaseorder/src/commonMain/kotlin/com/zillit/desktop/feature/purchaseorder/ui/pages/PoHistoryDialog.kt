package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PoHistoryEntry
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState

/** An order's audit trail on its own — the web's `HistoryPanel`, opened off a Posted row. */
@Composable
internal fun PoHistoryDialog(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val panel = state.historyPanel ?: return
    ZillitDialogShell(
        title = str(S.history),
        subtitle = panel.title,
        onDismiss = { onEvent(PoEvent.CloseHistory) },
        visible = true,
        icon = ZillitIcons.Clock,
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(PoEvent.CloseHistory) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        },
    ) {
        val entries = panel.entries
        when {
            entries == null -> ZillitNotice(
                text = str(S.desktop_loading_history),
                tone = StatusTone.Progress,
                icon = ZillitIcons.Clock,
            )

            entries.isEmpty() -> ZillitText(
                text = str(S.desktop_dm_no_history_recorded),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )

            else -> HistoryEntries(state, entries)
        }
    }
}

/**
 * The trail's rows, newest first as the repository hands them: the action in
 * words (`reassigned_by` reads "Reassigned By"), who did it and in which role,
 * when, and the note if there is one.
 */
@Composable
internal fun HistoryEntries(state: PoUiState, entries: List<PoHistoryEntry>) {
    entries.forEach { entry ->
        val actor = historyActor(state, entry.userId)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = entry.action.asWords(),
                    style = ZillitTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                if (actor.isNotBlank()) {
                    ZillitText(
                        text = str(S.ah_format_by_name, actor),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                entry.note?.takeIf { it.isNotBlank() }?.let { note ->
                    ZillitText(
                        text = note,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
            ZillitText(
                text = EpochDate.dateTime(entry.at),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/** The system's own transitions carry the sentinel actor `system` — said as a word, not a token. */
private fun historyActor(state: PoUiState, userId: String?): String = when {
    userId.isNullOrBlank() -> ""
    userId == SYSTEM_ACTOR -> str(S.desktop_language_system_short)
    else -> state.personLabel(userId)
}

/** `reassigned_by` → `Reassigned By`, the web's `fmtAction`. */
internal fun String.asWords(): String =
    replace('_', ' ').split(' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

private const val SYSTEM_ACTOR = "system"
