package com.zillit.desktop.feature.assetreport.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetFormat
import com.zillit.desktop.feature.assetreport.ui.AssetDetail
import com.zillit.desktop.feature.assetreport.ui.AssetEvent
import com.zillit.desktop.feature.assetreport.ui.AssetMediaLoader
import com.zillit.desktop.feature.assetreport.ui.AssetUiState
import com.zillit.desktop.feature.assetreport.ui.components.AssetIcons
import com.zillit.desktop.feature.assetreport.ui.components.AssetTint
import com.zillit.desktop.feature.assetreport.ui.components.AssetTopBar
import com.zillit.desktop.feature.assetreport.ui.components.CategoryPill
import com.zillit.desktop.feature.assetreport.ui.components.ExpenseCell
import com.zillit.desktop.feature.assetreport.ui.components.SaveButton
import com.zillit.desktop.feature.assetreport.ui.components.SectionLabel
import com.zillit.desktop.feature.assetreport.ui.components.assetPalette

/**
 * One asset — the web's `DetailPage`: the line's facts, the category beside the
 * note, the attachments. The header's Save owns the category and the files;
 * the note has its own.
 */
@Composable
internal fun AssetDetailPage(
    detail: AssetDetail,
    state: AssetUiState,
    media: AssetMediaLoader,
    onEvent: (AssetEvent) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        DetailTopBar(detail, onEvent)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // The web's 1000px break: narrower, the category stops sitting beside the note.
            val wide = maxWidth >= WIDE_BREAK
            ZillitScrollColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 30.dp, end = 30.dp, top = 24.dp, bottom = 48.dp),
            ) {
                // Centred rather than stretched edge to edge on a wide screen.
                Column(
                    modifier = Modifier
                        .widthIn(max = CONTENT_MAX_WIDTH)
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Section(str(S.details)) { MetaGrid(detail, state, columns = if (wide) 6 else 3) }
                    if (detail.hydrationFailed) {
                        ZillitNotice(
                            text = str(S.desktop_asset_detail_load_failed),
                            tone = StatusTone.Rejected,
                            icon = ZillitIcons.Warning,
                            action = {
                                ZillitButton(
                                    text = str(S.try_again),
                                    onClick = { onEvent(AssetEvent.RetryRecord) },
                                    variant = ButtonVariant.Secondary,
                                    leadingIcon = ZillitIcons.Reload,
                                )
                            },
                        )
                    }
                    if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            Section(str(S.av_category), Modifier.width(CATEGORY_COLUMN)) {
                                CategoryChoices(detail, stacked = true, onEvent = onEvent)
                            }
                            Section(str(S.av_comments), Modifier.weight(1f)) { NoteEditor(detail, state, onEvent) }
                        }
                    } else {
                        Section(str(S.av_category)) { CategoryChoices(detail, stacked = false, onEvent = onEvent) }
                        Section(str(S.av_comments)) { NoteEditor(detail, state, onEvent) }
                    }
                    AttachmentsSection(detail, media, onEvent)
                }
            }
        }
    }
}

@Composable
private fun DetailTopBar(detail: AssetDetail, onEvent: (AssetEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val line = detail.line
    AssetTopBar(
        backLabel = str(S.desktop_back_to_asset_register),
        onBack = { onEvent(AssetEvent.RequestClose) },
        title = {
            // PO Entry's breadcrumb: the parent as an amber caps link, a slash, the record.
            ZillitText(
                // Capitalised: the register's own title behind this overlay reads the same key.
                text = str(S.asset_title).uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.08.em),
                color = colors.accentText,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onEvent(AssetEvent.RequestClose) },
            )
            ZillitText(text = "/", style = ZillitTheme.typography.bodyMedium, color = colors.textDisabled)
            ZillitText(
                text = line.description.ifBlank { "—" },
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            ZillitText(
                text = listOf(line.account, line.poNumber).filter { it.isNotBlank() }.joinToString(" · "),
                style = ZillitTheme.typography.numeric.copy(fontSize = 11.sp),
                color = colors.textMuted,
                maxLines = 1,
            )
            CategoryPill(detail.categoryDraft)
        },
        right = {
            SaveButton(
                text = when {
                    detail.isSaving -> str(S.ah_saving)
                    detail.metaDirty -> str(S.save)
                    else -> str(S.saved)
                },
                enabled = detail.metaDirty && !detail.isLocked,
                onClick = { onEvent(AssetEvent.SaveDetails) },
                compact = true,
            )
        },
    )
}

@Composable
private fun Section(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(label)
        content()
    }
}

// -- details ---------------------------------------------------------------------------------

/** Six facts in hairline-separated cells — the web's `det-meta`. */
@Composable
private fun MetaGrid(detail: AssetDetail, state: AssetUiState, columns: Int) {
    val colors = ZillitTheme.colors
    val line = detail.line
    val cells: List<@Composable () -> Unit> = listOf(
        { MetaCell(AssetIcons.Store, str(S.ah_lbl_vendor)) { MetaValue(state.vendorName(line.vendorId)) } },
        { MetaCell(ZillitIcons.Users, str(S.department)) { MetaValue(state.departmentName(line.departmentId)) } },
        {
            MetaCell(AssetIcons.Tag, str(S.asset_lbl_expense_type)) {
                if (line.expenditureType.label.isBlank()) MetaValue("—") else ExpenseCell(line)
            }
        },
        {
            MetaCell(AssetIcons.List, str(S.ah_lbl_qty)) {
                MetaValue(AssetFormat.quantity(line.quantity), mono = true)
            }
        },
        {
            MetaCell(AssetIcons.Coins, str(S.asset_lbl_unit_cost)) {
                MetaValue(state.money(line.unitPrice, line.currency), mono = true)
            }
        },
        {
            MetaCell(ZillitIcons.Receipt, str(S.asset_total)) {
                MetaValue(state.money(line.total, line.currency), mono = true, color = colors.accentText)
            }
        },
    )
    val shape = RoundedCornerShape(13.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.divider)
            .border(1.dp, colors.border, shape),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        cells.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                row.forEach { cell -> Box(Modifier.weight(1f).fillMaxHeight()) { cell() } }
            }
        }
    }
}

@Composable
private fun MetaCell(icon: ImageVector, label: String, value: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (hovered) colors.surfaceSunken else colors.surface)
            .hoverable(interaction)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZillitIcon(icon = icon, tint = colors.textDisabled, size = 12.dp)
            ZillitText(
                text = label.uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.em,
                ),
                color = colors.textDisabled,
                maxLines = 1,
            )
        }
        value()
    }
}

@Composable
private fun MetaValue(text: String, mono: Boolean = false, color: Color = ZillitTheme.colors.textPrimary) {
    val base = if (mono) ZillitTheme.typography.numeric else ZillitTheme.typography.bodyLarge
    ZillitText(
        text = text,
        style = base.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = 2,
    )
}

// -- category ------------------------------------------------------------------------------------

/** Stacked beside the note; side by side when the page is too narrow for columns. */
@Composable
private fun CategoryChoices(detail: AssetDetail, stacked: Boolean, onEvent: (AssetEvent) -> Unit) {
    @Composable
    fun option(category: AssetCategory, modifier: Modifier) = CategoryOption(
        category = category,
        selected = detail.categoryDraft == category,
        enabled = !detail.isLocked,
        onClick = { onEvent(AssetEvent.PickCategory(category)) },
        modifier = modifier,
    )
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AssetCategory.choices.forEach { option(it, Modifier.fillMaxWidth()) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AssetCategory.choices.forEach { option(it, Modifier.weight(1f)) }
        }
    }
}

/**
 * Keep or Sell as a card: an icon well, the name over what it means, and a
 * tick that springs in. Clicking the picked card clears it.
 */
@Composable
private fun CategoryOption(
    category: AssetCategory,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val tint = assetPalette().category(category) ?: return
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lifted = hovered && enabled && !selected
    val look = categoryLook(tint, selected, lifted)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .offset(y = look.lift)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(if (lifted) Modifier.shadow(4.dp, shape) else Modifier)
            .clip(shape)
            .background(look.background)
            .border(1.5.dp, look.border, shape)
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryWell(category, tint, selected)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = category.wire,
                style = ZillitTheme.typography.bodyLarge.copy(
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.01).em,
                ),
                color = if (selected) tint.ink else ZillitTheme.colors.textPrimary,
                maxLines = 1,
            )
            ZillitText(
                text = category.subtitle,
                style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        CategoryTick(tint, selected)
    }
}

/** A card's animated look: lifted a pixel on hover, tinted when picked. */
private class CategoryLook(val lift: Dp, val border: Color, val background: Color)

@Composable
private fun categoryLook(tint: AssetTint, selected: Boolean, lifted: Boolean): CategoryLook {
    val colors = ZillitTheme.colors
    val lift by animateDpAsState(if (lifted) (-1).dp else 0.dp, label = "categoryLift")
    val border by animateColorAsState(
        when {
            selected -> tint.ink
            lifted -> colors.borderStrong
            else -> colors.border
        },
        label = "categoryBorder",
    )
    val background by animateColorAsState(
        when {
            selected -> tint.soft
            lifted -> colors.surfaceSunken
            else -> colors.surface
        },
        label = "categoryFill",
    )
    return CategoryLook(lift, border, background)
}

/** The icon well: quiet at rest, filled with the category's colour when picked. */
@Composable
private fun CategoryWell(category: AssetCategory, tint: AssetTint, selected: Boolean) {
    val colors = ZillitTheme.colors
    val well = RoundedCornerShape(11.dp)
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(well)
            .background(if (selected) tint.ink else colors.surfaceSunken)
            .border(1.dp, if (selected) tint.ink else colors.border, well),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = if (category == AssetCategory.Keep) AssetIcons.Archive else AssetIcons.Tag,
            tint = if (selected) Color.White else colors.textMuted,
            size = 20.dp,
        )
    }
}

/** The tick springs in on select rather than just appearing. */
@Composable
private fun CategoryTick(tint: AssetTint, selected: Boolean) {
    val tick by animateFloatAsState(
        targetValue = if (selected) 1f else TICK_REST,
        animationSpec = spring(dampingRatio = TICK_BOUNCE, stiffness = Spring.StiffnessMediumLow),
        label = "categoryTick",
    )
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) tint.ink else Color.Transparent)
            .border(1.5.dp, if (selected) tint.ink else ZillitTheme.colors.textDisabled, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = ZillitIcons.Check,
            tint = Color.White,
            size = 12.dp,
            modifier = Modifier.graphicsLayer {
                scaleX = tick
                scaleY = tick
                alpha = if (selected) 1f else 0f
            },
        )
    }
}

// -- the note ---------------------------------------------------------------------------------

/** One free-text note per line: a field that grows with its text, what state it is in, and its Save. */
@Composable
private fun NoteEditor(detail: AssetDetail, state: AssetUiState, onEvent: (AssetEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NoteField(
            value = detail.noteDraft,
            onValueChange = { onEvent(AssetEvent.NoteChanged(it)) },
            enabled = !detail.isLocked,
            placeholder = if (detail.isHydrating) "" else str(S.asset_note_hint),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZillitText(
                text = noteStatus(detail, state),
                style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                color = colors.textDisabled,
                modifier = Modifier.weight(1f),
            )
            SaveButton(
                text = when {
                    detail.isSaving -> str(S.ah_saving)
                    detail.noteJustSaved -> str(S.saved)
                    else -> str(S.save)
                },
                enabled = detail.noteDirty && !detail.isLocked && !detail.noteJustSaved,
                confirmed = detail.noteJustSaved,
                onClick = { onEvent(AssetEvent.SaveNote) },
                compact = false,
            )
        }
    }
}

/** "Last saved by Name · Designation · 12 Sep 2026", or where the note stands. */
internal fun noteStatus(detail: AssetDetail, state: AssetUiState): String {
    val record = detail.record
    return when {
        detail.isHydrating -> str(S.ah_loading)
        detail.noteDirty -> str(S.cs_exit_title)
        record != null && record.commentBy.isNotBlank() -> buildString {
            append(str(S.desktop_asset_last_saved))
            // Someone the crew list has lost is left out rather than printed as an id.
            state.author(record.commentBy)?.let { append(" by ").append(it) }
            AssetFormat.date(record.commentAtMillis).takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
        }
        else -> str(S.asset_not_saved_yet)
    }
}

@Composable
private fun NoteField(value: String, onValueChange: (String) -> Unit, enabled: Boolean, placeholder: String) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(13.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        interactionSource = interaction,
        textStyle = ZillitTheme.typography.bodyLarge.copy(
            fontSize = 14.sp,
            lineHeight = 21.7.sp,
            color = if (enabled) colors.textPrimary else colors.textMuted,
        ),
        cursorBrush = SolidColor(colors.accent),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NOTE_MIN_HEIGHT)
            .then(if (focused) Modifier else Modifier.shadow(1.dp, shape))
            .clip(shape)
            .background(if (enabled) colors.surface else colors.surfaceSunken)
            .border(1.dp, if (focused) colors.accent else colors.border, shape),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    ZillitText(
                        text = placeholder,
                        style = ZillitTheme.typography.bodyLarge,
                        color = colors.textDisabled,
                    )
                }
                inner()
            }
        },
    )
}

// -- leaving with unsaved changes ------------------------------------------------------------

/** The AD dashboard's guard, word for word: Discard, or Save & leave. */
@Composable
internal fun UnsavedChangesDialog(detail: AssetDetail?, onEvent: (AssetEvent) -> Unit) {
    val saving = detail?.isSaving == true
    ZillitDialogShell(
        title = str(S.cs_exit_title),
        subtitle = str(S.desktop_asset_unsaved_subtitle),
        visible = detail?.confirmLeave == true,
        onDismiss = { onEvent(AssetEvent.KeepEditing) },
        width = 440.dp,
        actions = {
            ZillitButton(
                text = str(S.ah_discard),
                onClick = { onEvent(AssetEvent.DiscardAndClose) },
                variant = ButtonVariant.Secondary,
                enabled = !saving,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = if (saving) str(S.ah_saving) else str(S.cs_exit_save_and_leave),
                onClick = { onEvent(AssetEvent.SaveAndClose) },
                enabled = !saving,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_asset_unsaved_explainer),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

private val WIDE_BREAK = 1000.dp
private val CONTENT_MAX_WIDTH = 1140.dp
private val CATEGORY_COLUMN = 280.dp
private val NOTE_MIN_HEIGHT = 104.dp
private const val DISABLED_ALPHA = 0.6f
private const val TICK_REST = 0.3f
private const val TICK_BOUNCE = 0.45f
