package com.zillit.desktop.feature.crewlist.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The sheet's title bar: the tool's mark and name, how many people it lists,
 * and the way out to the floating widget.
 */
@Composable
internal fun CrewTitleBar(
    title: String,
    summary: String,
    onOpenWidget: (() -> Unit)?,
) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = ZillitToolIcons.CrewList, tint = colors.accentText, size = 19.dp)
            }
            Column(Modifier.weight(1f)) {
                ZillitText(text = title, style = ZillitTheme.typography.titleMedium, maxLines = 1)
                if (summary.isNotBlank()) {
                    ZillitText(
                        text = summary,
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
            if (onOpenWidget != null) {
                ZillitButton(
                    text = str(S.desktop_widget),
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Detach,
                    onClick = onOpenWidget,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

/**
 * The web's info banner above the toolbar. Two audiences, each gated on what
 * makes its line actionable: the department-order line is for admins (it
 * opens their editor), the email line for whoever may edit the document.
 */
@Composable
internal fun CrewNoticeBanner(
    copy: CrewCopy,
    showOrderLine: Boolean,
    showEmailLine: Boolean,
    onOpenOrder: () -> Unit,
) {
    if (!showOrderLine && !showEmailLine) return
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.infoSoft)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitIcon(icon = ZillitIcons.Info, tint = colors.info, size = 16.dp, modifier = Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (showOrderLine) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZillitText(
                        text = copy.t(
                            "crew_list_header_text",
                            str(S.desktop_cl_header_text),
                        ) + " or ",
                        style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = colors.textPrimary,
                        maxLines = 2,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    InlineLink(copy.t("click_here_label", str(S.tools_description_click_here)), onOpenOrder)
                }
            }
            if (showEmailLine) {
                ZillitText(
                    text = copy.t(
                        "crew_list_profile_email_hint",
                        str(S.desktop_cl_profile_email_hint),
                    ),
                    style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = colors.textPrimary,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun InlineLink(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall.copy(
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            textDecoration = if (hovered) TextDecoration.Underline else TextDecoration.None,
        ),
        color = ZillitTheme.colors.accentText,
        maxLines = 1,
        modifier = Modifier
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** What the toolbar offers, and in what state. */
internal class CrewToolbarModel(
    val busy: Boolean,
    val editing: Boolean,
    val showAddExternal: Boolean,
    val query: String,
    val compact: Boolean,
)

/**
 * Generate PDF · Preview · Refresh · Add External User · Edit/Done, with the
 * search on the right — the web's order. Edit sits with the actions, not the
 * search: it acts on the list, the search only filters the view of it.
 */
@Composable
internal fun CrewToolbar(
    model: CrewToolbarModel,
    copy: CrewCopy,
    onGenerate: () -> Unit,
    onPreview: () -> Unit,
    onRefresh: () -> Unit,
    onAddExternal: () -> Unit,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    onSearch: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitButton(
            text = copy.t("generate_pdf", str(S.generate_pdf)),
            leadingIcon = CrewIcons.Document,
            enabled = !model.busy,
            onClick = onGenerate,
        )
        if (!model.compact) {
            ZillitButton(
                text = copy.t("Preview", str(S.preview)),
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Eye,
                enabled = !model.busy,
                onClick = onPreview,
            )
        }
        ZillitButton(
            text = copy.t("refresh", str(S.refresh_text)),
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Reload,
            enabled = !model.busy,
            onClick = onRefresh,
        )
        if (!model.compact && model.showAddExternal) {
            ZillitButton(
                text = copy.t("Add External User", str(S.add_external_user)),
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.UserPlus,
                enabled = !model.busy,
                onClick = onAddExternal,
            )
        }
        if (!model.compact) {
            if (model.editing) {
                ZillitButton(text = copy.t("Done", str(S.ah_done)), leadingIcon = ZillitIcons.Check, onClick = onDone)
            } else {
                ZillitButton(
                    text = copy.t("Edit", str(S.edit)),
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Edit,
                    enabled = !model.busy,
                    onClick = onEdit,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        ZillitSearchField(
            value = model.query,
            onValueChange = onSearch,
            placeholder = copy.t("Search", str(S.search)),
            modifier = Modifier.width(if (model.compact) 180.dp else 240.dp),
        )
    }
}

/** "Your edits will be applied when you preview, generate or publish." — only while some are pending. */
@Composable
internal fun EditsHint(visible: Boolean, copy: CrewCopy) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(crewPalette().grip))
            ZillitText(
                text = copy.t(
                    "crew_list_edits_applied_hint",
                    str(S.desktop_cl_edits_applied_hint),
                ),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}
