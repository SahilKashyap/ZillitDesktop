package com.zillit.desktop.feature.crewlist.ui.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.crewlist.ui.CrewWork
import com.zillit.desktop.feature.crewlist.ui.DistributionPrompt
import com.zillit.desktop.feature.crewlist.ui.components.CrewCopy
import com.zillit.desktop.feature.crewlist.ui.components.swallowPresses
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** The viewer's Publish, asked first — the web's Popconfirm, with its Yes and No. */
@Composable
internal fun PublishConfirmDialog(visible: Boolean, copy: CrewCopy, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ZillitDialogShell(
        title = copy.t("PublishCrewList", str(S.desktop_cl_publish_tool)),
        icon = ZillitIcons.Send,
        visible = visible,
        onDismiss = onCancel,
        width = 420.dp,
        actions = {
            ZillitButton(text = copy.t("No", str(S.no)), variant = ButtonVariant.Tertiary, onClick = onCancel)
            ZillitButton(text = copy.t("Yes", str(S.yes)), onClick = onConfirm)
        },
    ) {
        ZillitText(
            text = copy.t("publish_crew_list_confirmation", str(S.desktop_cl_publish_confirmation)),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/**
 * Document Distribution's two popups, naming the file (ZL-19833): the
 * confirmation before anything is sent, and "Published." once it is in the
 * library.
 */
@Composable
internal fun DistributionDialogs(
    prompt: DistributionPrompt?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val held = remember { arrayOfNulls<DistributionPrompt>(1) }
    prompt?.let { held[0] = it }
    val shown = prompt ?: held[0]
    val name = shown?.pdf?.fileName.orEmpty()
    val asking = prompt != null && prompt.stage != DistributionPrompt.Stage.Done
    val sending = prompt?.stage == DistributionPrompt.Stage.Sending

    ZillitDialogShell(
        title = str(S.dd_publish_confirm_title),
        icon = ZillitIcons.Send,
        visible = asking,
        onDismiss = { if (!sending) onCancel() },
        width = 460.dp,
        actions = {
            ZillitButton(text = str(S.cancel), variant = ButtonVariant.Tertiary, enabled = !sending, onClick = onCancel)
            ZillitButton(text = str(S.publish), loading = sending, enabled = !sending, onClick = onConfirm)
        },
    ) {
        ZillitText(
            text = if (sending) {
                str(S.dd_distribute_loading)
            } else {
                str(S.desktop_cl_publish_to_library_question, name)
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }

    ZillitDialogShell(
        title = str(S.cs_published),
        icon = ZillitIcons.Check,
        visible = prompt?.stage == DistributionPrompt.Stage.Done,
        onDismiss = onDismiss,
        width = 420.dp,
        actions = { ZillitButton(text = str(S.ok), onClick = onDismiss) },
    ) {
        ZillitText(
            text = "\"$name\" was added to Document Distribution.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/**
 * The wait while the backend renders the PDF or the post goes out — the web's
 * full-page Loader. It takes every press, so nothing is started twice.
 */
@Composable
internal fun WorkingOverlay(work: CrewWork?, copy: CrewCopy) {
    val held = remember { arrayOfNulls<CrewWork>(1) }
    work?.let { held[0] = it }
    AnimatedVisibility(visible = work != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(ZillitTheme.colors.scrim.copy(alpha = 0.28f))
                .swallowPresses(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .shadow(12.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(ZillitTheme.colors.surfaceRaised)
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ZillitSpinner(size = 22.dp)
                ZillitText(
                    text = when (work ?: held[0]) {
                        CrewWork.Publishing -> str(S.desktop_cl_publishing_tool, copy.toolName)
                        CrewWork.Distributing -> str(S.dd_distribute_loading)
                        else -> str(S.desktop_cl_generating_tool, copy.toolName)
                    },
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textPrimary,
                )
            }
        }
    }
}
