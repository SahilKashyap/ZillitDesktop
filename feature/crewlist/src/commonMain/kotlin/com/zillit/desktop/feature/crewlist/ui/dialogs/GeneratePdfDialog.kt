package com.zillit.desktop.feature.crewlist.ui.dialogs

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.crewlist.domain.CrewListViewer
import com.zillit.desktop.feature.crewlist.ui.GenerateAction
import com.zillit.desktop.feature.crewlist.ui.components.CrewCopy
import com.zillit.desktop.feature.crewlist.ui.components.CrewIcons
import com.zillit.desktop.feature.crewlist.ui.components.crewPalette
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The Generate PDF chooser (ZL-19847): the "(Not on Zillit)" question as an
 * explicit Yes/No, then the four actions in the web's hierarchy — Cancel
 * (quiet) · View (outline) · Publish to Doc Distribution (outline, send icon)
 * · Publish (the primary call, last). The widget offers View and Download only:
 * it may be showing another production, where publishing would land in the
 * wrong one.
 */
@Composable
internal fun GeneratePdfDialog(
    visible: Boolean,
    copy: CrewCopy,
    hideExternalLabel: Boolean,
    compact: Boolean,
    onHideExternalLabel: (Boolean) -> Unit,
    onRun: (GenerateAction) -> Unit,
    onDismiss: () -> Unit,
) {
    ZillitDialogShell(
        title = copy.t("generate_pdf", str(S.generate_pdf)),
        icon = CrewIcons.Document,
        visible = visible,
        onDismiss = onDismiss,
        width = if (compact) 460.dp else 640.dp,
        actions = {
            ZillitButton(text = copy.t("Cancel", str(S.cancel)), variant = ButtonVariant.Tertiary, onClick = onDismiss)
            if (compact) {
                ZillitButton(
                    text = copy.t("View", str(S.view)),
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Eye,
                    onClick = { onRun(GenerateAction.View) },
                )
                ZillitButton(
                    text = str(S.download),
                    leadingIcon = ZillitIcons.Download,
                    onClick = { onRun(GenerateAction.Download) },
                )
            } else {
                ZillitButton(
                    text = copy.t("View", str(S.view)),
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Eye,
                    onClick = { onRun(GenerateAction.View) },
                )
                ZillitButton(
                    text = copy.t("publish_to_doc_distribution", str(S.dd_publish_to_distribution)),
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Send,
                    onClick = { onRun(GenerateAction.Distribute) },
                )
                ZillitButton(
                    text = copy.t("Publish", str(S.publish)),
                    onClick = { onRun(GenerateAction.Publish) },
                )
            }
        },
    ) {
        val staffList = copy.toolName == CrewListViewer.STAFF_LIST
        ZillitText(
            text = copy.t(
                if (staffList) "crew_list_created_other_project" else "crew_list_created",
                str(S.desktop_cl_created_description),
            ),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ConsentBox(copy, hideExternalLabel, onHideExternalLabel)
    }
}

/** The tinted question box — the web's `.crewlist-gen-consent`. */
@Composable
private fun ConsentBox(copy: CrewCopy, hide: Boolean, onChange: (Boolean) -> Unit) {
    val palette = crewPalette()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.consentFill)
            .border(1.dp, palette.consentBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ZillitText(
            text = copy.t(
                "hide_external_user_label_description",
                str(S.desktop_cl_hide_external_label_description),
            ),
            style = ZillitTheme.typography.bodyMedium.copy(lineHeight = 19.5.sp),
            color = palette.consentInk,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            CrewRadio(label = copy.t("No", str(S.no)), selected = !hide) { onChange(false) }
            CrewRadio(label = copy.t("Yes", str(S.yes)), selected = hide) { onChange(true) }
        }
    }
}

/** A radio: an 18-point ring, the accent dot when chosen, its label beside it. */
@Composable
internal fun CrewRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ring by animateColorAsState(
        when {
            selected -> colors.accent
            hovered -> colors.accent.copy(alpha = 0.6f)
            else -> colors.borderStrong
        },
        label = "radio",
    )
    Row(
        modifier = Modifier
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .border(if (selected) 5.dp else 1.5.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Spacer(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        }
        ZillitText(text = label, style = ZillitTheme.typography.bodyMedium, color = colors.textPrimary)
    }
}
