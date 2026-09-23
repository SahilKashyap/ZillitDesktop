package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.FormConfigState
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.ReorderableColumn

/**
 * Rearrange — the web's right-hand panel: the sections, dragged into order,
 * and one click on a section for its fields, dragged the same way.
 *
 * Each row also carries up and down arrows on hover, which reach the same
 * moves: a drag is not the only way a person should have to reorder a form.
 * Both address rows by key and move within what the panel lists — the fields
 * on the form, the sections a person may configure — so a hidden field or the
 * terms section never takes a step that looks like nothing happened.
 */
@Composable
internal fun FormRearrangePanel(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit, modifier: Modifier) {
    val picked = config.rearrangeSection?.let { config.template.section(it) }
    SidePanel(
        title = picked?.label ?: str(S.desktop_rearrange),
        subtitle = if (picked != null) {
            str(S.desktop_hub_drag_fields_to_reorder)
        } else {
            str(S.desktop_hub_drag_sections_to_reorder_click_to_see_fields)
        },
        onClose = { onEvent(AccountHubEvent.ToggleRearrange(false)) },
        modifier = modifier,
        above = if (picked == null) {
            null
        } else {
            { BackToSections { onEvent(AccountHubEvent.PickRearrangeSection(null)) } }
        },
    ) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(ZillitTheme.spacing.md),
        ) {
            if (picked == null) {
                SectionList(config.template.configurable, onEvent)
            } else {
                FieldList(picked, onEvent)
            }
        }
    }
}

@Composable
private fun SectionList(sections: List<FormSection>, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ReorderableColumn(
        items = sections,
        key = { it.key },
        onMove = { from, to -> onEvent(AccountHubEvent.MoveFormSection(from, to)) },
        spacing = ZillitTheme.spacing.xs,
    ) { section, dragging ->
        val index = sections.indexOf(section)
        RearrangeRow(
            dragging = dragging,
            onClick = { onEvent(AccountHubEvent.PickRearrangeSection(section.key)) },
            onUp = sections.getOrNull(index - 1)?.let { above ->
                { onEvent(AccountHubEvent.MoveFormSection(section.key, above.key)) }
            },
            onDown = sections.getOrNull(index + 1)?.let { below ->
                { onEvent(AccountHubEvent.MoveFormSection(section.key, below.key)) }
            },
            label = section.label,
        ) {
            ZillitIcon(icon = ZillitIcons.File, tint = colors.accentText, size = 12.dp)
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = section.label,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                ZillitText(
                    text = "${section.fields.size} field${if (section.fields.size == 1) "" else "s"}",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            FormBadge(
                if (section.systemDefault) str(S.desktop_language_system_short) else str(S.custom),
                if (section.systemDefault) BadgeTone.System else BadgeTone.Custom,
            )
        }
    }
}

@Composable
private fun FieldList(section: FormSection, onEvent: (AccountHubEvent) -> Unit) {
    val fields = section.visible
    if (fields.isEmpty()) {
        FieldHint(str(S.desktop_hub_nothing_in_this_section_is_on_the_form), Modifier.padding(ZillitTheme.spacing.sm))
        return
    }
    ReorderableColumn(
        items = fields,
        key = { it.id },
        onMove = { from, to -> onEvent(AccountHubEvent.MoveFormField(section.key, from, to)) },
        spacing = ZillitTheme.spacing.xs,
    ) { field, dragging ->
        val index = fields.indexOf(field)
        FieldRow(section, field, dragging, fields.getOrNull(index - 1), fields.getOrNull(index + 1), onEvent)
    }
}

@Composable
private fun FieldRow(
    section: FormSection,
    field: FormField,
    dragging: Boolean,
    above: FormField?,
    below: FormField?,
    onEvent: (AccountHubEvent) -> Unit,
) {
    RearrangeRow(
        dragging = dragging,
        onClick = null,
        onUp = above?.let { { onEvent(AccountHubEvent.MoveFormField(section.key, field.id, it.id)) } },
        onDown = below?.let { { onEvent(AccountHubEvent.MoveFormField(section.key, field.id, it.id)) } },
        label = field.name,
    ) {
        ZillitText(
            text = field.name,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (field.required) FormBadge(str(S.docusign_prop_required), BadgeTone.System)
    }
}

/**
 * One draggable row: the grip, the caller's content, and the arrows that
 * show on hover. Lifted, with an accent edge, while it is being dragged.
 */
@Composable
private fun RearrangeRow(
    dragging: Boolean,
    onClick: (() -> Unit)?,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    label: String,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val reveal by animateFloatAsState(if (hovered && !dragging) 1f else 0f, label = "rearrangeArrows")
    val edge by animateColorAsState(if (dragging) colors.accent else colors.border, label = "rearrangeEdge")
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (dragging) Modifier.shadow(DRAG_ELEVATION, shape) else Modifier)
            .clip(shape)
            .background(if (hovered || dragging) colors.surfaceHover else colors.surface)
            .border(1.dp, edge, shape)
            .hoverable(interaction)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Show the fields in $label",
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(start = ZillitTheme.spacing.sm, end = ZillitTheme.spacing.xxs)
            .padding(vertical = ZillitTheme.spacing.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = GripIcon, tint = colors.textMuted, size = 12.dp)
        content()
        Row(modifier = Modifier.alpha(reveal)) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronUp,
                contentDescription = "Move $label up",
                onClick = { onUp?.invoke() },
                enabled = onUp != null,
                size = ARROW,
            )
            ZillitIconButton(
                icon = ZillitIcons.ChevronDown,
                contentDescription = "Move $label down",
                onClick = { onDown?.invoke() },
                enabled = onDown != null,
                size = ARROW,
            )
        }
    }
}

/** "‹ Sections" over a section's name. */
@Composable
private fun BackToSections(onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(end = ZillitTheme.spacing.xs, bottom = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitIcon(icon = ZillitIcons.ChevronLeft, tint = colors.textMuted, size = 10.dp)
        ZillitText(
            text = str(S.desktop_sections),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
    }
}

private val ARROW = 20.dp
private val DRAG_ELEVATION = 6.dp
