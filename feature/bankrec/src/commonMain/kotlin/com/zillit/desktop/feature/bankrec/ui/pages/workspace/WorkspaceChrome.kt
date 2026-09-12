package com.zillit.desktop.feature.bankrec.ui.pages.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.PanelTotal
import com.zillit.desktop.feature.bankrec.domain.WorkspaceFilter
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.WorkspaceView
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons
import com.zillit.desktop.feature.bankrec.ui.components.BrDot
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.bg
import com.zillit.desktop.feature.bankrec.ui.components.edge
import com.zillit.desktop.feature.bankrec.ui.components.eyebrow
import com.zillit.desktop.feature.bankrec.ui.components.fg
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.components.titleStyle

/**
 * The full view's own bar: back, where you are, and the way out. The module's
 * header and tabs are hidden while it is up, so this is the only chrome left.
 */
@Composable
internal fun ExpandedBar(view: WorkspaceView, loading: Boolean, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitTooltip("Back to Bank Reconciliation (Esc)") {
            ZillitIconButton(
                icon = ZillitIcons.ChevronLeft,
                contentDescription = "Back to Bank Reconciliation",
                onClick = { onEvent(BankRecEvent.SetWorkspaceExpanded(false)) },
            )
        }
        Column(Modifier.weight(1f)) {
            ZillitText("ACCOUNTING · BANK RECONCILIATION", style = eyebrow(10.sp), color = colors.textMuted)
            ZillitText(
                text = if (loading) {
                    "Loading…"
                } else {
                    listOfNotNull(
                        view.account?.displayName?.ifBlank { null },
                        view.period?.let(BankRecFormat::periodLabel),
                    ).joinToString(" · ").ifBlank { "Workspace" }
                },
                style = titleStyle(16.sp),
                maxLines = 1,
            )
        }
        ZillitButton(
            text = "Exit full screen",
            onClick = { onEvent(BankRecEvent.SetWorkspaceExpanded(false)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = BankRecIcons.Compress,
        )
    }
    ZillitDivider()
}

/**
 * The period, the filter pills with their counts, and the period-wide actions.
 *
 * On one line when the pane is wide; the actions drop to a second line when it
 * is not, as the web's toolbar wraps — squeezing them clips "Sign Off", which
 * is the one button here nobody should have to hunt for.
 */
@Composable
internal fun Toolbar(state: BankRecUiState, view: WorkspaceView, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    BoxWithConstraints(Modifier.fillMaxWidth().background(colors.surface)) {
        if (maxWidth >= TOOLBAR_ONE_LINE) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterGroup(state, view, onEvent)
                Spacer(Modifier.weight(1f))
                ActionGroup(state, onEvent)
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterGroup(state, view, onEvent)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    ActionGroup(state, onEvent)
                }
            }
        }
    }
    ZillitDivider()
}

@Composable
private fun RowScope.FilterGroup(state: BankRecUiState, view: WorkspaceView, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val ws = state.workspace
    val counts = view.counts
    ZillitText(
        text = listOfNotNull(
            view.period?.let(BankRecFormat::periodLabel),
            listOfNotNull(
                view.account?.displayName?.ifBlank { null },
                view.account?.maskedNumber?.ifBlank { null },
            ).joinToString(" ").ifBlank { null },
        ).joinToString(" \u00b7 "),
        style = mono(12.5.sp, FontWeight.Medium),
        color = colors.textSecondary,
        maxLines = 1,
        modifier = Modifier.weight(1f, fill = false),
    )
    Spacer(Modifier.width(4.dp))
    FilterPill("All ${counts.all}", BrTone.Green, ws.filter == WorkspaceFilter.All) {
        onEvent(BankRecEvent.FilterWorkspace(WorkspaceFilter.All))
    }
    FilterPill("Unmatched ${counts.unmatched}", BrTone.Red, ws.filter == WorkspaceFilter.Unmatched) {
        onEvent(BankRecEvent.FilterWorkspace(WorkspaceFilter.Unmatched))
    }
    FilterPill("Suggested ${counts.suggested}", BrTone.Amber, ws.filter == WorkspaceFilter.Suggested) {
        onEvent(BankRecEvent.FilterWorkspace(WorkspaceFilter.Suggested))
    }
    FilterPill(
        "\u26a0 Fraud ${counts.fraud}",
        BrTone.Red,
        ws.filter == WorkspaceFilter.Fraud,
        pulse = counts.fraud > 0,
    ) { onEvent(BankRecEvent.FilterWorkspace(WorkspaceFilter.Fraud)) }
    FilterPill("FX ${counts.fx}", BrTone.Teal, ws.filter == WorkspaceFilter.Fx) {
        onEvent(BankRecEvent.FilterWorkspace(WorkspaceFilter.Fx))
    }
}

@Composable
private fun ActionGroup(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val ws = state.workspace
    ZillitButton(
        text = if (ws.rerunning) "Matching\u2026" else "Re-run Auto-Match",
        onClick = { onEvent(BankRecEvent.RerunAutoMatch) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        leadingIcon = BankRecIcons.Bolt,
        enabled = !ws.rerunning,
    )
    ZillitButton(text = "Sign Off", onClick = { onEvent(BankRecEvent.OpenSignOff) }, size = ButtonSize.Small)
    ToggleSquare(
        icon = if (ws.expanded) BankRecIcons.Compress else BankRecIcons.Expand,
        active = ws.expanded,
        tooltip = if (ws.expanded) "Exit expanded view (Esc)" else "Expand workspace",
    ) { onEvent(BankRecEvent.SetWorkspaceExpanded(!ws.expanded)) }
    ToggleSquare(
        icon = BankRecIcons.Menu,
        active = ws.showQuickEntry,
        tooltip = if (ws.showQuickEntry) "Hide Quick Entry panel" else "Show Quick Entry panel",
    ) { onEvent(BankRecEvent.ToggleQuickEntry) }
}

/** A rounded count pill: tinted at rest, solid when it is the filter in force. */
@Composable
private fun FilterPill(label: String, tone: BrTone, active: Boolean, pulse: Boolean = false, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (active) tone.fg() else tone.bg())
    val content by animateColorAsState(if (active) colors.textOnAccent else tone.fg())
    val pulseAlpha = if (pulse && !active) pulseAlpha() else 1f
    Box(
        Modifier.alpha(pulseAlpha).clip(CircleShape).background(background)
            .border(1.dp, if (hovered && !active) tone.fg() else tone.edge(), CircleShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        ZillitText(
            label,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
private fun pulseAlpha(): Float {
    val transition = rememberInfiniteTransition()
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = PULSE_LOW,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
    ).value
}

/** A square icon toggle, edged in the accent while it is on. */
@Composable
private fun ToggleSquare(icon: ImageVector, active: Boolean, tooltip: String, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ZillitTooltip(tooltip) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(6.dp))
                .background(if (active) colors.accentSoft else colors.surface)
                .border(
                    1.dp,
                    if (active) colors.accent else if (hovered) colors.borderStrong else colors.border,
                    RoundedCornerShape(6.dp),
                )
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon, tint = if (active) colors.accentText else colors.textSecondary, size = 15.dp)
        }
    }
}

/** Opening and closing bank balances against the ledger's, and the fraud prompt at the end. */
@Composable
internal fun BalanceBar(state: BankRecUiState, view: WorkspaceView, onEvent: () -> Unit) {
    val colors = ZillitTheme.colors
    val period = view.period ?: return
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Balance("Opening Bank", BankRecFormat.plainMoney(period.openingBank, view.statementCurrency))
        ZillitText("→", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        Balance("Closing Bank", BankRecFormat.plainMoney(period.closingBank, view.statementCurrency))
        ZillitText("|", style = ZillitTheme.typography.bodySmall, color = colors.border)
        Balance(
            "Closing Zillit",
            BankRecFormat.plainMoney(state.workspace.closingZillit ?: period.closingZillit, state.projectCurrency),
        )
        Spacer(Modifier.weight(1f))
        val fraud = view.counts.fraud
        if (fraud > 0) {
            Row(
                Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onEvent).padding(
                    horizontal = 4.dp,
                    vertical = 2.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ZillitText(
                    "⚠",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.danger,
                    modifier = Modifier.alpha(pulseAlpha()),
                )
                ZillitText(
                    "$fraud fraud flag${if (fraud == 1) "" else "s"} need review ›",
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = colors.danger,
                )
            }
        }
    }
    ZillitDivider()
}

@Composable
private fun Balance(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitText(label.uppercase(), style = eyebrow(10.sp), color = ZillitTheme.colors.textMuted, maxLines = 1)
        ZillitText(value, style = mono(13.sp, FontWeight.SemiBold), maxLines = 1)
    }
}

/**
 * A panel's header: its glyph, name and period, and its total — compact, with
 * the exact figure on hover, and converted when the panel spans currencies.
 */
@Composable
internal fun PanelHeader(icon: ImageVector, title: String, subtitle: String, total: PanelTotal, caption: String) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(icon, tint = colors.gold, size = 16.dp)
        ZillitText(title, style = titleStyle(13.5.sp), maxLines = 1)
        ZillitText(
            subtitle,
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ZillitTooltip(total.exact) {
            ZillitText(
                total.value,
                style = mono(13.sp, FontWeight.SemiBold),
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
    }
    ZillitDivider()
    Box(Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(horizontal = 16.dp, vertical = 5.dp)) {
        ZillitText(caption.uppercase(), style = eyebrow(9.5.sp), color = colors.textMuted, maxLines = 1)
    }
    ZillitDivider()
}

/** The counts as a legend, and the difference that decides whether the period is done. */
@Composable
internal fun SummaryBar(view: WorkspaceView) {
    val colors = ZillitTheme.colors
    val counts = view.counts
    ZillitDivider()
    Row(
        Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SummaryCount(colors.success, "Matched", counts.matched)
        SummaryCount(colors.warning, "Suggested", counts.suggested)
        SummaryCount(colors.danger, "Unmatched", counts.unmatched)
        SummaryCount(colors.danger, "Fraud flags", counts.fraud, pulse = counts.fraud > 0)
        SummaryCount(colors.teal, "FX", counts.fx)
        Spacer(Modifier.weight(1f))
        ZillitText("DIFFERENCE", style = eyebrow(10.sp), color = colors.textMuted)
        val difference = view.difference
        val text = (if (difference < 0) "-" else if (difference > 0) "+" else "") +
            BankRecFormat.compactMoney(kotlin.math.abs(difference), view.projectCurrency)
        ZillitTooltip(BankRecFormat.signedMoney(difference, view.projectCurrency)) {
            ZillitText(
                text,
                style = mono(18.sp, FontWeight.Bold),
                color = if (difference == 0.0) colors.success else colors.danger,
            )
        }
    }
}

@Composable
private fun SummaryCount(color: Color, label: String, count: Int, pulse: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BrDot(color, pulse = pulse)
        ZillitText("$label:", style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
        ZillitText(count.toString(), style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

private const val PULSE_LOW = 0.45f
private val TOOLBAR_ONE_LINE = 940.dp
private const val PULSE_MILLIS = 900
