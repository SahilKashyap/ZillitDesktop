package com.zillit.desktop.feature.sides.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.sides.ui.SidesDistributeTarget
import com.zillit.desktop.feature.sides.ui.SidesEvent

/**
 * The two popups of "Send to Document Distribution": the confirm that names
 * the file — the same pair every other tool's hand-off shows
 * (`dd_publish_confirm_title` / `desktop_added_to_document_distribution`) —
 * and then this tool's own question, whether to distribute the new library
 * document now or later.
 *
 * The target is held after it clears so the closing animation still has a
 * name to draw, as the other tools' dialogs do.
 */
@Composable
internal fun SidesDistributeDialogs(target: SidesDistributeTarget?, onEvent: (SidesEvent) -> Unit) {
    val held = remember { arrayOfNulls<SidesDistributeTarget>(1) }
    target?.let { held[0] = it }
    val name = (target ?: held[0])?.fileName.orEmpty()

    ConfirmSendDialog(
        visible = target != null && !target.filed,
        name = name,
        sending = target?.sending == true,
        onEvent = onEvent,
    )
    NowOrLaterDialog(visible = target?.filed == true, name = name, onEvent = onEvent)
}

/** "Publish to Document Distribution" — the confirm every tool's send shows. */
@Composable
private fun ConfirmSendDialog(visible: Boolean, name: String, sending: Boolean, onEvent: (SidesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.dd_publish_confirm_title),
        icon = ZillitIcons.Send,
        onDismiss = { if (!sending) onEvent(SidesEvent.CloseSendToDocDist) },
        visible = visible,
        scrollable = false,
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(SidesEvent.CloseSendToDocDist) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !sending,
            )
            ZillitButton(
                text = str(S.publish),
                onClick = { onEvent(SidesEvent.ConfirmSendToDocDist) },
                size = ButtonSize.Small,
                loading = sending,
                enabled = !sending,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_cl_publish_to_library_question, name),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** It landed: distribute it from the library now, or leave it for later. */
@Composable
private fun NowOrLaterDialog(visible: Boolean, name: String, onEvent: (SidesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.cs_published),
        icon = ZillitIcons.Check,
        onDismiss = { onEvent(SidesEvent.CloseSendToDocDist) },
        visible = visible,
        scrollable = false,
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.later),
                onClick = { onEvent(SidesEvent.CloseSendToDocDist) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.desktop_distribute_now),
                onClick = { onEvent(SidesEvent.DistributeNow) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Send,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(
                text = str(S.desktop_added_to_document_distribution, name),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
            ZillitText(
                text = str(S.desktop_sides_distribute_now_or_later),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
            )
        }
    }
}

private val DIALOG_WIDTH = 440.dp
