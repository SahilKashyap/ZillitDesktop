package com.zillit.desktop.feature.accounthub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.ui.SetupModalSection

/**
 * The two-pane drill-down the module setups open in — the web's `SetupModalShell`.
 *
 * Left: an eyebrow pill, the title and description, the section list with a
 * mono count per row, and a footer status. Right: "N / M · Section", the
 * Unsaved pill, Save changes and ×, the section body, and the kbd hints.
 * Esc closes and ⌘S saves, as there.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
fun SetupModalShell(
    eyebrow: String,
    title: String,
    description: String,
    sections: List<SetupModalSection>,
    activeId: String,
    onSection: (String) -> Unit,
    dirty: Boolean,
    saving: Boolean,
    loading: Boolean,
    loadError: String?,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(sectionId: String) -> Unit,
) {
    val colors = ZillitTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val activeIndex = sections.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    val active = sections.getOrNull(activeIndex)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.scrim.copy(alpha = SCRIM))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(WIDTH_FRACTION)
                .fillMaxHeight(HEIGHT_FRACTION)
                .sizeIn(maxWidth = MAX_WIDTH, maxHeight = MAX_HEIGHT)
                .shadow(ELEVATION, ZillitTheme.shapes.large)
                .clip(ZillitTheme.shapes.large)
                .background(colors.surface)
                .border(1.dp, colors.border, ZillitTheme.shapes.large)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Escape -> { onClose(); true }
                        event.isMetaPressed && event.key == Key.S -> {
                            if (dirty && !saving) onSave()
                            true
                        }
                        else -> false
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            // -- left nav --
            Column(modifier = Modifier.width(NAV_WIDTH).fillMaxHeight().background(colors.surfaceSunken)) {
                Column(
                    modifier = Modifier.padding(ZillitTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    Pill(eyebrow, tone = StatusTone.Pending)
                    ZillitText(
                        text = title,
                        style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    FieldHint(description)
                }
                HairLine()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(ZillitTheme.spacing.sm)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    sections.forEach { section ->
                        val isActive = section.id == activeId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ZillitTheme.shapes.medium)
                                .background(if (isActive) colors.accentSoft else colors.surfaceSunken)
                                .clickable { onSection(section.id) }
                                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            if (isActive) Box(Modifier.size(
                                width = 3.dp,
                                height = 14.dp,
                            ).clip(CircleShape).background(colors.accent))
                            ZillitText(
                                text = section.name,
                                style =
                                    ZillitTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    ),
                                color = if (isActive) colors.accentText else colors.textSecondary,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            MonoChip(section.count?.toString() ?: "—", active = isActive)
                        }
                    }
                }
                HairLine()
                Row(
                    modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    val (dot, text) = when {
                        loading -> colors.textMuted to "Loading…"
                        loadError != null -> colors.danger to "Load error"
                        dirty -> colors.warning to "Unsaved changes"
                        else -> colors.success to "Saved"
                    }
                    Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                    FieldHint(text)
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
            // -- right pane --
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MonoLabel(if (sections.isEmpty()) "" else "${activeIndex + 1} / ${sections.size}")
                        ZillitText(
                            text = active?.name ?: title,
                            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                        )
                    }
                    if (dirty) Pill("Unsaved", tone = StatusTone.Pending, dot = true)
                    ZillitButton(
                        text = if (saving) "Saving…" else "Save changes",
                        onClick = onSave,
                        size = ButtonSize.Small,
                        enabled = dirty && !saving,
                        loading = saving,
                    )
                    ZillitIconButton(icon = ZillitIcons.Close, contentDescription = "Close", onClick = onClose)
                }
                HairLine()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(colors.canvas)
                        .verticalScroll(rememberScrollState())
                        .padding(ZillitTheme.spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
                ) {
                    when {
                        loading -> Box(
                            Modifier.fillMaxWidth().height(LOADING_HEIGHT),
                            contentAlignment = Alignment.Center,
                        ) {
                            ZillitSpinner()
                        }
                        loadError != null -> LoadErrorBody(loadError)
                        active != null -> content(active.id)
                    }
                }
                HairLine()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    Kbd("esc")
                    FieldHint("to close")
                    Box(Modifier.size(3.dp).clip(CircleShape).background(colors.borderStrong))
                    Kbd("⌘S")
                    FieldHint("to save")
                    Fill()
                    FieldHint("Changes apply to new transactions only")
                }
            }
        }
    }
}

/** The web's `ErrorBody`: a title, then what went wrong. */
@Composable
private fun LoadErrorBody(message: String) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = "Couldn't load settings",
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.danger,
        )
        ZillitText(text = message, style = ZillitTheme.typography.bodySmall, color = colors.danger)
    }
}

private const val SCRIM = 0.55f
private const val WIDTH_FRACTION = 0.96f
private const val HEIGHT_FRACTION = 0.92f
private val MAX_WIDTH = 1320.dp
private val MAX_HEIGHT = 880.dp
private val NAV_WIDTH = 280.dp
private val ELEVATION = 24.dp
private val LOADING_HEIGHT = 240.dp
