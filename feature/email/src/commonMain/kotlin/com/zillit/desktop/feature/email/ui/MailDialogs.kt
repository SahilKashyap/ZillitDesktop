package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.domain.SELECTION_LIMIT_MESSAGE

/** The one-button dialogs — the web's `Modal.info`. */
@Composable
internal fun InfoDialog(info: MailInfo, onDismiss: () -> Unit) {
    ModalCard(onDismiss = onDismiss, modifier = Modifier.testTag(INFO_DIALOG_TAG)) {
        val (title, body) = when (info) {
            MailInfo.SelectionLimit -> str(S.selection_limited) to SELECTION_LIMIT_MESSAGE
        }
        ZillitText(text = title, style = ZillitTheme.typography.titleMedium)
        ZillitText(text = body, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ZillitButton(text = str(S.ah_ok), onClick = onDismiss)
        }
    }
}

/**
 * The web's `GeneralSettingsModal`: one switch, "Conversation View", with
 * the change confirmed before it is saved. The web reloads the page to
 * apply it; here the list regroups in place, so the confirmation only says
 * what the switch does.
 */
@Composable
internal fun ConversationViewDialog(
    enabled: Boolean,
    saving: Boolean,
    onChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by remember { mutableStateOf<Boolean?>(null) }
    ModalCard(onDismiss = onDismiss, modifier = Modifier.testTag(CONVERSATION_DIALOG_TAG)) {
        ZillitText(text = str(S.desktop_email_toggle_conversation_view), style = ZillitTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                ZillitText(
                    text = str(S.email_trailing),
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                ZillitText(
                    text = str(S.email_trailing_sub),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            ZillitSwitch(
                checked = pending ?: enabled,
                enabled = !saving,
                onCheckedChange = { pending = it },
                modifier = Modifier.testTag(CONVERSATION_SWITCH_TAG),
            )
        }
        val next = pending?.takeIf { it != enabled }
        if (next != null) {
            ZillitText(
                text = str(
                    if (next) S.desktop_email_conversation_view_turn_on else S.desktop_email_conversation_view_turn_off,
                ),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            DialogButtons(
                action = str(S.dm_filter_apply),
                loading = saving,
                onConfirm = { onChange(next) },
                onDismiss = onDismiss,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ZillitButton(text = str(S.close), variant = ButtonVariant.Tertiary, onClick = onDismiss)
            }
        }
    }
}

/**
 * The switcher's one-time tour — the web's `MailboxTour`, five steps, each
 * a short explanation over a miniature of the surface it describes. Kept
 * as one card with Next/Back rather than coach marks anchored to the
 * sidebar, which Compose has no ready equivalent for.
 */
@Composable
internal fun MailboxTourDialog(onDismiss: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    val colors = ZillitTheme.colors
    val current = TOUR_STEPS[step]

    ModalCard(onDismiss = onDismiss, modifier = Modifier.testTag(TOUR_DIALOG_TAG)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                modifier = Modifier.size(TOUR_DISC).clip(CircleShape).background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(current.icon, contentDescription = null, tint = colors.textOnAccent, size = TOUR_ICON)
            }
            ZillitText(
                text = str(current.title),
                style = ZillitTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = "${step + 1} / ${TOUR_STEPS.size}",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        ZillitText(text = str(current.body), style = ZillitTheme.typography.bodyMedium, color = colors.textSecondary)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            if (step > 0) ZillitButton(text = str(S.back), variant = ButtonVariant.Tertiary, onClick = { step-- })
            if (step < TOUR_STEPS.lastIndex) {
                ZillitButton(text = str(S.next), onClick = { step++ }, modifier = Modifier.testTag(TOUR_NEXT_TAG))
            } else {
                ZillitButton(text = str(S.done_text), onClick = onDismiss, modifier = Modifier.testTag(TOUR_NEXT_TAG))
            }
        }
    }
}

private class TourStep(val title: String, val body: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** The web's five `mailbox_tour_*` strings, as catalogue keys. */
private val TOUR_STEPS = listOf(
    TourStep(S.desktop_email_tour_1_title, S.desktop_email_tour_1_body, ZillitIcons.Users),
    TourStep(S.desktop_email_tour_2_title, S.desktop_email_tour_2_body, ZillitIcons.Mail),
    TourStep(S.desktop_email_tour_3_title, S.desktop_email_tour_3_body, ZillitIcons.Bell),
    TourStep(S.desktop_email_tour_4_title, S.desktop_email_tour_4_body, ZillitIcons.Send),
    TourStep(S.desktop_email_tour_5_title, S.desktop_email_tour_5_body, ZillitIcons.Settings),
)

internal const val INFO_DIALOG_TAG = "email-info-dialog"
internal const val CONVERSATION_DIALOG_TAG = "email-conversation-dialog"
internal const val CONVERSATION_SWITCH_TAG = "email-conversation-switch"
internal const val TOUR_DIALOG_TAG = "email-tour-dialog"
internal const val TOUR_NEXT_TAG = "email-tour-next"

private val TOUR_DISC = 36.dp
private val TOUR_ICON = 18.dp
