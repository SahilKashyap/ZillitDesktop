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
import com.zillit.desktop.feature.email.domain.SELECTION_LIMIT_MESSAGE

/** The one-button dialogs — the web's `Modal.info`. */
@Composable
internal fun InfoDialog(info: MailInfo, onDismiss: () -> Unit) {
    ModalCard(onDismiss = onDismiss, modifier = Modifier.testTag(INFO_DIALOG_TAG)) {
        val (title, body) = when (info) {
            MailInfo.SelectionLimit -> "Selection Limited" to SELECTION_LIMIT_MESSAGE
        }
        ZillitText(text = title, style = ZillitTheme.typography.titleMedium)
        ZillitText(text = body, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ZillitButton(text = "OK", onClick = onDismiss)
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
        ZillitText(text = "Toggle Conversation View", style = ZillitTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                ZillitText(
                    text = "Conversation View",
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                ZillitText(
                    text = "Enable Conversation View to group all related emails into a single thread.",
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
                text = "Turn ${if (next) "on" else "off"} Conversation View? The list will regroup.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            DialogButtons(
                action = "Apply",
                loading = saving,
                onConfirm = { onChange(next) },
                onDismiss = onDismiss,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ZillitButton(text = "Close", variant = ButtonVariant.Tertiary, onClick = onDismiss)
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
            ZillitText(text = current.title, style = ZillitTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            ZillitText(
                text = "${step + 1} / ${TOUR_STEPS.size}",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        ZillitText(text = current.body, style = ZillitTheme.typography.bodyMedium, color = colors.textSecondary)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            if (step > 0) ZillitButton(text = "Back", variant = ButtonVariant.Tertiary, onClick = { step-- })
            if (step < TOUR_STEPS.lastIndex) {
                ZillitButton(text = "Next", onClick = { step++ }, modifier = Modifier.testTag(TOUR_NEXT_TAG))
            } else {
                ZillitButton(text = "Done", onClick = onDismiss, modifier = Modifier.testTag(TOUR_NEXT_TAG))
            }
        }
    }
}

private class TourStep(val title: String, val body: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** The web's five `mailbox_tour_*` strings. */
private val TOUR_STEPS = listOf(
    TourStep(
        "Two Mailboxes, One Place",
        "As an Accounts department member, you now have a shared Accounts Mailbox for this project alongside " +
            "your personal mailbox. Here's a quick look at how to work with both.",
        ZillitIcons.Users,
    ),
    TourStep(
        "Switch Mailboxes Here",
        "Click the switcher at the top of the sidebar to choose between your Personal Mailbox and the shared " +
            "Accounts Mailbox. An orange highlight means the Accounts Mailbox is active — every folder and " +
            "email below belongs to it.",
        ZillitIcons.Mail,
    ),
    TourStep(
        "Unread at a Glance",
        "Each mailbox shows its own unread count inside the switcher's menu. A small orange dot on the icon " +
            "means the mailbox you're not viewing has new mail.",
        ZillitIcons.Bell,
    ),
    TourStep(
        "Send From the Active Mailbox",
        "New emails are sent from the mailbox shown in the switcher. In the Accounts Mailbox, drafts, contacts, " +
            "and signatures are shared with your whole department.",
        ZillitIcons.Send,
    ),
    TourStep(
        "Settings Follow the Mailbox",
        "Signatures, BCC presets, email forwarding, and conversation view apply to whichever mailbox is active " +
            "when you change them.",
        ZillitIcons.Settings,
    ),
)

internal const val INFO_DIALOG_TAG = "email-info-dialog"
internal const val CONVERSATION_DIALOG_TAG = "email-conversation-dialog"
internal const val CONVERSATION_SWITCH_TAG = "email-conversation-switch"
internal const val TOUR_DIALOG_TAG = "email-tour-dialog"
internal const val TOUR_NEXT_TAG = "email-tour-next"

private val TOUR_DISC = 36.dp
private val TOUR_ICON = 18.dp
