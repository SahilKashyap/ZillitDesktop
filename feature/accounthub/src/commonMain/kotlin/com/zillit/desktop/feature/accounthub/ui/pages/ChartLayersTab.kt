package com.zillit.desktop.feature.accounthub.ui.pages

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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.TrackingNode
import com.zillit.desktop.feature.accounthub.domain.TrackingSet
import com.zillit.desktop.feature.accounthub.domain.TrackingSets
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.LayerDelete
import com.zillit.desktop.feature.accounthub.ui.components.CoaCard
import com.zillit.desktop.feature.accounthub.ui.components.CoaChevron
import com.zillit.desktop.feature.accounthub.ui.components.CoaIconWash
import com.zillit.desktop.feature.accounthub.ui.components.CoaIcons
import com.zillit.desktop.feature.accounthub.ui.components.CoaLine
import com.zillit.desktop.feature.accounthub.ui.components.HubConfirmDialog
import com.zillit.desktop.feature.accounthub.ui.components.coaMono

/**
 * The Layers tab — the web's `TrackingCodesTab`: tagging dimensions beside the
 * nominal chart, each a colour, a prefix and a flat list of codes, one set open
 * at a time.
 */
@Composable
internal fun ColumnScope.ChartLayersTab(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val chart = state.chart
    val canEdit = state.viewer.canActAsAccountant

    LayersIntro(canEdit, onEvent)
    when {
        chart.layersLoading && chart.trackingSets.isEmpty() -> CoaLoadingCard("Loading layers…")
        chart.trackingSets.isEmpty() -> LayersEmpty(canEdit, onEvent)
        else -> ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            chart.trackingSets.forEachIndexed { index, set ->
                LayerCard(
                    set = set,
                    color = layerColor(set, index),
                    nodes = chart.layerNodes(set),
                    open = chart.openLayer == set.id,
                    canEdit = canEdit,
                    onEvent = onEvent,
                )
            }
        }
    }
}

/** What a layer is, since the idea is new — and the new-set button. */
@Composable
private fun LayersIntro(canEdit: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    CoaCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            CoaIconWash(CoaIcons.Sliders, box = 36.dp, glyph = 16.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ZillitText(
                    "Layers — analytical dimensions",
                    style = ZillitTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                )
                ZillitText(
                    "Group of codes that hang off every transaction line item separately from the COA. Example: a " +
                        "Locations layer lets you tag each line as London, Spain or LA so the cost report can split " +
                        "spend by location without polluting the nominal taxonomy. Up to 10 layers per project; each " +
                        "can be a tree of any depth.",
                    style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 19.sp),
                    color = colors.textSecondary,
                )
            }
            if (canEdit) {
                ZillitButton(
                    text = "New set",
                    onClick = { onEvent(AccountHubEvent.ComposeLayerSet(null)) },
                    leadingIcon = CoaIcons.Plus,
                )
            }
        }
    }
}

@Composable
private fun LayersEmpty(canEdit: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .drawBehind {
                drawRoundRect(
                    colors.borderStrong,
                    cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            }
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CoaIconWash(CoaIcons.Sliders, box = 56.dp, glyph = 22.dp)
        ZillitText(
            "No layers yet",
            style = ZillitTheme.typography.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 8.dp),
        )
        ZillitText(
            "Add your first set to start tagging line items by location, episode, funding source, or any other " +
                "dimension your cost report needs.",
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 440.dp),
        )
        if (canEdit) {
            ZillitButton(
                text = "New set",
                onClick = { onEvent(AccountHubEvent.ComposeLayerSet(null)) },
                leadingIcon = CoaIcons.Plus,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** A set (`SetCard`): its chip, name and count; open, its codes and "Add code". */
@Suppress("LongMethod") // A card, read top to bottom; the order is the reading order.
@Composable
private fun LayerCard(
    set: TrackingSet,
    color: Color,
    nodes: List<TrackingNode>,
    open: Boolean,
    canEdit: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    CoaCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEvent(AccountHubEvent.ToggleLayerOpen(set.id)) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                CoaChevron(open = open, tint = colors.textMuted, size = 12.dp)
            }
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(color),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    set.shownPrefix,
                    style = coaMono(10.sp, FontWeight.ExtraBold, 0.4.sp),
                    color = Color.White,
                    maxLines = 1,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(
                        set.name.ifBlank { "Unnamed layer" },
                        style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                        maxLines = 1,
                    )
                    if (!set.isActive) DisabledChip()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val muted = ZillitTheme.typography.bodySmall.copy(fontSize = 11.5.sp)
                    ZillitText("Prefix ", style = muted, color = colors.textMuted)
                    ZillitText(set.shownPrefix, style = coaMono(11.5.sp), color = colors.textSecondary)
                    ZillitText(
                        "  ·  ${nodes.size} code${if (nodes.size == 1) "" else "s"}",
                        style = muted,
                        color = colors.textMuted,
                    )
                }
            }
            if (canEdit) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LayerIconButton(CoaIcons.Edit, "Edit set", size = 30) {
                        onEvent(AccountHubEvent.ComposeLayerSet(set))
                    }
                    LayerIconButton(CoaIcons.Trash, "Delete set", size = 30, danger = true) {
                        onEvent(AccountHubEvent.AskDeleteLayer(LayerDelete.WholeSet(set)))
                    }
                }
            }
        }
        if (open) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)) {
                if (nodes.isEmpty()) {
                    ZillitText(
                        "No codes yet — add the first one below.",
                        style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = colors.textMuted,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                nodes.forEach { node -> LayerNodeRow(set, node, color, canEdit, onEvent) }
                if (canEdit) {
                    DashedAddButton("Add code", Modifier.padding(top = 8.dp)) {
                        onEvent(AccountHubEvent.ComposeLayerNode(set.id, null))
                    }
                }
            }
        }
    }
}

/** One code (`NodeRow`): the set's dot, the code, its label over its description, the flag and actions. */
@Composable
private fun LayerNodeRow(
    set: TrackingSet,
    node: TrackingNode,
    color: Color,
    canEdit: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
        ZillitText(
            node.code,
            style = coaMono(12.sp, FontWeight.Bold, 0.2.sp),
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 1.dp),
            maxLines = 1,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CoaLine(node.name, ZillitTheme.typography.bodyMedium, colors.textSecondary)
            val description = node.description.trim()
            if (description.isNotEmpty()) {
                ZillitTooltip(description) {
                    CoaLine(description, ZillitTheme.typography.bodySmall.copy(fontSize = 11.5.sp), colors.textMuted)
                }
            }
        }
        if (!node.isActive) DisabledChip()
        if (canEdit) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                LayerIconButton(CoaIcons.Edit, "Edit", size = 24) {
                    onEvent(AccountHubEvent.ComposeLayerNode(set.id, node))
                }
                LayerIconButton(CoaIcons.Trash, "Delete", size = 24, danger = true) {
                    onEvent(AccountHubEvent.AskDeleteLayer(LayerDelete.OneNode(set.id, node)))
                }
            }
        }
    }
}

@Composable
private fun DisabledChip() {
    val colors = ZillitTheme.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(colors.surfaceSunken)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        ZillitText(
            "DISABLED",
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
            ),
            color = colors.textMuted,
        )
    }
}

/** The layer cards' bordered icon buttons (`IconBtn`) — always shown, the delete one red. */
@Composable
private fun LayerIconButton(
    icon: ImageVector,
    tooltip: String,
    size: Int,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            hovered && danger -> colors.danger.copy(alpha = 0.08f)
            hovered -> colors.surfaceHover
            else -> Color.Transparent
        },
        label = "layerIconButton",
    )
    val shape = RoundedCornerShape(7.dp)
    ZillitTooltip(tooltip) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(shape)
                .background(background)
                .border(1.dp, colors.border, shape)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon,
                contentDescription = tooltip,
                tint = if (danger) colors.danger else colors.textSecondary,
                size = if (size > SMALL_BUTTON) 13.dp else 12.dp,
            )
        }
    }
}

@Composable
private fun DashedAddButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val edge = if (hovered) colors.accent else colors.borderStrong
    val content = if (hovered) colors.accent else colors.textSecondary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .drawBehind {
                drawRoundRect(
                    edge,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(
                        1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    ),
                )
            }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(CoaIcons.Plus, tint = content, size = 11.dp)
        ZillitText(
            label,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = content,
        )
    }
}

/** The set editor, the code editor, the delete confirmation and the in-use refusal. */
@Composable
internal fun ChartLayerDialogs(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val chart = state.chart
    LayerSetDialog(state, onEvent)
    LayerNodeDialog(state, onEvent)
    val delete = chart.layerDelete
    HubConfirmDialog(
        visible = delete != null,
        title = "Confirm delete",
        message = delete?.message.orEmpty(),
        confirmLabel = if (chart.layerDeleting) "Deleting…" else "Delete",
        loading = chart.layerDeleting,
        onConfirm = { onEvent(AccountHubEvent.ConfirmDeleteLayer) },
        onDismiss = { onEvent(AccountHubEvent.DismissDeleteLayer) },
    )
    val refusal = chart.layerInUse
    ZillitDialogShell(
        title = refusal?.title.orEmpty(),
        icon = ZillitIcons.Warning,
        visible = refusal != null,
        width = DIALOG_WIDTH,
        onDismiss = { onEvent(AccountHubEvent.DismissLayerInUse) },
        actions = { ZillitButton(text = "OK", onClick = { onEvent(AccountHubEvent.DismissLayerInUse) }) },
    ) {
        // The server's words name the modules still holding the code.
        val colors = ZillitTheme.colors
        ZillitText(
            refusal?.message.orEmpty(),
            style = ZillitTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            color = colors.danger,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.danger.copy(alpha = 0.06f))
                .border(1.dp, colors.danger.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Suppress("LongMethod") // A form, read top to bottom; the order is the reading order.
@Composable
private fun LayerSetDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val draft = state.chart.layerSetDraft
    val saving = draft?.saving == true
    val isNew = draft?.isNew == true
    ZillitDialogShell(
        title = if (isNew) "New layer" else "Edit \"${draft?.set?.name?.ifBlank { "layer" }}\"",
        icon = CoaIcons.Sliders,
        visible = draft != null,
        width = DIALOG_WIDTH,
        onDismiss = { onEvent(AccountHubEvent.DismissLayerSet) },
        actions = {
            LayerDialogActions(
                saving = saving,
                saveLabel = if (isNew) "Create set" else "Save",
                savingLabel = if (isNew) "Creating…" else "Saving…",
                canSave = draft?.set?.name?.isNotBlank() == true,
                onSave = { onEvent(AccountHubEvent.SaveLayerSet) },
            )
        },
    ) {
        val set = draft?.set ?: return@ZillitDialogShell
        fun update(next: TrackingSet) = onEvent(AccountHubEvent.EditLayerSet(next))
        LayerField("Name", required = true) {
            ZillitTextField(
                value = set.name,
                onValueChange = { update(set.copy(name = it)) },
                placeholder = "e.g. Locations, Episodes, Funding Source",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LayerField(
            "Prefix",
            hint = "2–10 letters/digits. Used on every code (LOC-EUR-LON). Leave blank to auto-derive from name.",
        ) {
            ZillitTextField(
                value = set.prefix,
                onValueChange = { update(set.copy(prefix = TrackingSets.normalisePrefix(it))) },
                placeholder = "Auto",
                modifier = Modifier.width(160.dp),
            )
        }
        LayerField("Colour", hint = "Drives the chip on every line-item picker.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrackingSets.DEFAULT_COLORS.forEach { hex ->
                    val chosen = set.color.equals(hex, ignoreCase = true)
                    val swatch = RoundedCornerShape(8.dp)
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(swatch)
                            .background(parseHex(hex) ?: ZillitTheme.colors.accent)
                            .border(
                                if (chosen) 2.dp else 1.dp,
                                if (chosen) ZillitTheme.colors.textPrimary else ZillitTheme.colors.border,
                                swatch,
                            )
                            .clickable { update(set.copy(color = hex)) },
                    )
                }
            }
        }
        LayerField("Status") {
            ZillitCheckbox(
                checked = set.isActive,
                onCheckedChange = { update(set.copy(isActive = it)) },
                label = "Active — picker shows codes from this set on every line item.",
            )
        }
    }
}

@Suppress("LongMethod") // A form, read top to bottom; the order is the reading order.
@Composable
private fun LayerNodeDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val chart = state.chart
    val draft = chart.layerNodeDraft
    val saving = draft?.saving == true
    val isNew = draft?.isNew == true
    val set = chart.trackingSets.firstOrNull { it.id == draft?.node?.setId }
    val prefix = set?.shownPrefix?.ifBlank { null } ?: "PFX"
    ZillitDialogShell(
        title = if (isNew) {
            "New code in \"${set?.name.orEmpty()}\""
        } else {
            "Edit \"${draft?.node?.code?.ifBlank { "code" }}\""
        },
        icon = CoaIcons.Sliders,
        visible = draft != null,
        width = DIALOG_WIDTH,
        onDismiss = { onEvent(AccountHubEvent.DismissLayerNode) },
        actions = {
            LayerDialogActions(
                saving = saving,
                saveLabel = if (isNew) "Add code" else "Save",
                savingLabel = if (isNew) "Adding…" else "Saving…",
                canSave = draft != null && draft.node.code.isNotBlank() && draft.node.name.isNotBlank(),
                onSave = { onEvent(AccountHubEvent.SaveLayerNode) },
            )
        },
    ) {
        val node = draft?.node ?: return@ZillitDialogShell
        fun update(next: TrackingNode) = onEvent(AccountHubEvent.EditLayerNode(next))
        LayerField("Code", required = true, hint = "Convention: $prefix-…") {
            ZillitTextField(
                value = node.code,
                onValueChange = { update(node.copy(code = it)) },
                placeholder = "$prefix-LON",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LayerField("Label", required = true) {
            ZillitTextField(
                value = node.name,
                onValueChange = { update(node.copy(name = it)) },
                placeholder = "London",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LayerField("Description", hint = "Optional notes shown on hover in the picker.") {
            ZillitTextField(
                value = node.description,
                onValueChange = { update(node.copy(description = it)) },
                placeholder = "Soundstage hire + studio support",
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            )
        }
        LayerField("Status") {
            ZillitCheckbox(
                checked = node.isActive,
                onCheckedChange = { update(node.copy(isActive = it)) },
                label = "Active",
            )
        }
    }
}

/**
 * The primary action alone — the web's `ModalShell` footer with `hideCancel`
 * (03f047d47): × and Esc discard, so a Cancel beside Save was a second way to
 * do the same thing. While the call is out the button spins with its "…ing"
 * label.
 */
@Composable
private fun LayerDialogActions(
    saving: Boolean,
    saveLabel: String,
    savingLabel: String,
    canSave: Boolean,
    onSave: () -> Unit,
) {
    ZillitButton(
        text = if (saving) savingLabel else saveLabel,
        onClick = onSave,
        loading = saving,
        enabled = canSave,
    )
}

/** The layer dialogs' field: an uppercase label, the control, a hint beneath. */
@Composable
private fun LayerField(
    label: String,
    required: Boolean = false,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            ZillitText(
                label.uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
                color = colors.textMuted,
            )
            if (required) {
                ZillitText(
                    " *",
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.danger,
                )
            }
        }
        content()
        if (hint != null) {
            ZillitText(
                hint,
                style = ZillitTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = colors.textMuted,
            )
        }
    }
}

/** A set's own colour, or the palette's by position when it has none or it will not parse. */
@Composable
private fun layerColor(set: TrackingSet, index: Int): Color =
    parseHex(set.color) ?: parseHex(TrackingSets.colorFor(index)) ?: ZillitTheme.colors.accent

/** `#FB923C` → a colour; anything else is null. */
private fun parseHex(hex: String): Color? {
    val clean = hex.trim().removePrefix("#")
    if (clean.length != HEX_LENGTH) return null
    val value = clean.toLongOrNull(HEX_RADIX) ?: return null
    return Color((value or ALPHA_MASK).toInt())
}

private val DIALOG_WIDTH = 520.dp
private const val SMALL_BUTTON = 24
private const val HEX_LENGTH = 6
private const val HEX_RADIX = 16
private const val ALPHA_MASK = 0xFF000000L
