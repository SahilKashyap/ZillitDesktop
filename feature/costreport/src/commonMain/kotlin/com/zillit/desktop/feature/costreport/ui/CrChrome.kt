// The cost report's shared chrome: header bar, filter cells, stepper, cards. The
// tab model is one small piece of it, not what the file is about.
@file:Suppress("LongMethod", "TooManyFunctions", "LongParameterList", "MatchingDeclarationName")

package com.zillit.desktop.feature.costreport.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/** One tab of the header bar. [badge] is drawn after the label — the worksheet's "N over". */
internal data class CrHeaderTab(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val badge: String? = null,
)

/**
 * The cost report's header strip — the web's `CrHeaderBar`, shared by the
 * accountant's worksheet and the crew tool so the two cannot drift: a back
 * slot, underline tabs sitting on the bar's own bottom rule, the amber
 * Analytics call to action, and whatever the host parks on the right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CrHeaderBar(
    tabs: List<CrHeaderTab>,
    activeId: String,
    onTab: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onAnalytics: (() -> Unit)? = null,
    trailing: @Composable FlowRowScope.() -> Unit = {},
) {
    val colors = ZillitTheme.colors
    Column(modifier.fillMaxWidth().background(colors.canvas)) {
        // The back arrow and tabs hold the left; everything else flows from the
        // right and wraps under itself when the pane is narrow, as the web's
        // flex-wrap does, so the tabs are never pushed off.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitIcon(
                        ZillitIcons.ChevronLeft,
                        tint = colors.textSecondary,
                        size = 14.dp,
                        contentDescription = "Back",
                    )
                }
            }
            Row(Modifier.height(HEADER_HEIGHT)) {
                tabs.forEach { tab -> HeaderTab(tab, tab.id == activeId) { onTab(tab.id) } }
            }
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.Center,
            ) {
                if (onAnalytics != null) AnalyticsButton(onAnalytics)
                trailing()
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

/** The amber call to action that opens the Analytics page. */
@Composable
private fun AnalyticsButton(onClick: () -> Unit) {
    Box(Modifier.height(HEADER_HEIGHT), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CrPalette.ANALYTICS)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZillitIcon(ZillitIcons.BarChart, tint = Color.White, size = 13.dp)
            ZillitText(
                "Analytics",
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }
    }
}

private val HEADER_HEIGHT: Dp = 60.dp

@Composable
private fun HeaderTab(tab: CrHeaderTab, active: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val underline = CrPalette.cta
    Box(
        modifier = Modifier
            .height(HEADER_HEIGHT)
            .hoverable(hover)
            .clickable(onClick = onClick)
            // Drawn rather than laid out: a full-width child would stretch the
            // tab to the whole bar, since a Box sizes to its widest child.
            .drawBehind {
                if (active) {
                    val stroke = UNDERLINE.toPx()
                    val inset = TAB_PADDING.toPx()
                    drawRect(underline, Offset(inset, size.height - stroke), Size(size.width - inset * 2, stroke))
                }
            }
            .padding(horizontal = TAB_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tab.icon?.let {
                ZillitIcon(it, tint = if (active) CrPalette.cta else colors.textMuted, size = 13.dp)
            }
            ZillitText(
                text = tab.label,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp),
                color = when {
                    active -> colors.textPrimary
                    hovered -> colors.textSecondary
                    else -> colors.textMuted
                },
                maxLines = 1,
            )
            tab.badge?.let { badge ->
                ZillitText(
                    text = badge.uppercase(),
                    style = ZillitTheme.typography.labelSmall.copy(
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    ),
                    color = CrPalette.over,
                    modifier = Modifier
                        .background(CrPalette.LOCK_RED.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

private val TAB_PADDING: Dp = 12.dp
private val UNDERLINE: Dp = 2.5.dp

/** A paper card of small action buttons, as the web groups Save/Save Version/History. */
@Composable
internal fun CrActionGroup(content: @Composable RowScope.() -> Unit) {
    val colors = ZillitTheme.colors
    Box(Modifier.height(HEADER_HEIGHT), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * A button inside a [CrActionGroup]: ghost by default, amber-filled when
 * [primary]. A disabled one says why in its tooltip.
 */
@Composable
internal fun CrGroupButton(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    primary: Boolean = false,
    tooltip: String = "",
    badge: String? = null,
) {
    val colors = ZillitTheme.colors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val active = enabled && !busy
    ZillitTooltip(tooltip) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        primary -> CrPalette.cta
                        hovered && active -> colors.surfaceHover
                        else -> Color.Transparent
                    },
                )
                .hoverable(hover)
                .alpha(if (active || busy) 1f else DISABLED_ALPHA)
                .clickable(enabled = active, onClick = onClick)
                .padding(horizontal = if (primary) 12.dp else 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val ink = if (primary) CrPalette.ctaInk else colors.textSecondary
            when {
                busy -> ZillitSpinner(size = 12.dp, color = ink)
                icon != null -> ZillitIcon(icon, tint = ink, size = 12.dp)
            }
            ZillitText(
                text = label,
                style = ZillitTheme.typography.label.copy(
                    fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold,
                ),
                color = ink,
                maxLines = 1,
            )
            badge?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = CrPalette.over,
                    modifier = Modifier
                        .background(CrPalette.LOCK_RED.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** A labelled filter cell: the small uppercase label over its control. */
@Composable
internal fun CrFieldCell(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
            ),
            color = ZillitTheme.colors.textMuted,
        )
        content()
    }
}

/**
 * The week stepper: ‹ 11 May–17 May 2026 ›, the forward arrow stopping at this
 * week, and "Go to this week" whenever another week is showing.
 */
@Composable
internal fun CrPeriodStepper(
    range: String,
    canGoNext: Boolean,
    showGoCurrent: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGoCurrent: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = ZillitTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperArrow(ZillitIcons.ChevronLeft, "Previous week", enabled, onPrevious)
            ZillitText(
                text = range,
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            StepperArrow(ZillitIcons.ChevronRight, "Next week", enabled && canGoNext, onNext)
        }
        if (showGoCurrent) {
            ZillitText(
                text = "Go to this week",
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
                color = colors.textMuted,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(999.dp))
                    .clickable(enabled = enabled, onClick = onGoCurrent)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun StepperArrow(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .alpha(if (enabled) 1f else STEPPER_DISABLED_ALPHA)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon, tint = colors.textSecondary, size = 12.dp, contentDescription = description)
    }
}

/** A segmented control in the web's style: the active option filled with the amber CTA. */
@Composable
internal fun <T> CrSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    mono: Boolean = false,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(9.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            ZillitText(
                text = label,
                style = (if (mono) ZillitTheme.typography.numeric else ZillitTheme.typography.bodySmall)
                    .copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp),
                color = if (active) CrPalette.ctaInk else colors.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) CrPalette.cta else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
        }
    }
}

/** A small uppercase caption before a control, as the web's `CrGroup`. */
@Composable
internal fun CrGroupLabel(label: String) {
    ZillitText(
        text = label.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
        ),
        color = ZillitTheme.colors.textMuted,
    )
}

/** The loader over a pane while its figures are fetched — the web's `BackdropLoader`. */
@Composable
internal fun BoxScope.CrLoaderOverlay(visible: Boolean, message: String) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ZillitTheme.colors.canvas.copy(alpha = 0.72f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZillitSpinner(size = 26.dp, color = CrPalette.cta)
                ZillitText(message, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textSecondary)
            }
        }
    }
}

/**
 * The top-right card that tracks a long write after its dialog has closed —
 * the web's `ProgressToast`. A success clears itself; a failure stays until
 * it is closed, so there is time to read it.
 */
@Composable
internal fun BoxScope.CrProgressCard(
    title: String?,
    detail: String,
    loading: Boolean,
    success: Boolean,
    onClose: () -> Unit,
) {
    val colors = ZillitTheme.colors
    AnimatedVisibility(
        visible = title != null,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
    ) {
        if (success && !loading) {
            androidx.compose.runtime.LaunchedEffect(title) {
                kotlinx.coroutines.delay(SUCCESS_DISMISS_MILLIS)
                onClose()
            }
        }
        Row(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 380.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceRaised)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                loading -> ZillitSpinner(size = 22.dp, color = CrPalette.cta)
                success -> StatusDot(colors.success, ZillitIcons.Check)
                else -> StatusDot(colors.danger, ZillitIcons.Close)
            }
            Column(Modifier.weight(1f)) {
                ZillitText(
                    title.orEmpty(),
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                if (detail.isNotBlank()) {
                    ZillitText(detail, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 3)
                }
            }
            if (!loading) {
                ZillitIcon(
                    ZillitIcons.Close,
                    tint = colors.textMuted,
                    size = 12.dp,
                    contentDescription = "Close",
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, icon: ImageVector) {
    Box(Modifier.size(24.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        ZillitIcon(icon, tint = Color.White, size = 11.dp)
    }
}

/** "There are unallocated costs at the bottom…" with its View — the crew tool only, as on the web. */
@Composable
internal fun CrUncodedBanner(onView: () -> Unit, modifier: Modifier = Modifier) {
    ZillitNotice(
        text = "There are unallocated costs at the bottom due to lack of nominal coding.",
        tone = StatusTone.Progress,
        icon = ZillitIcons.Info,
        modifier = modifier,
        action = {
            ZillitText(
                text = "View",
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, ZillitTheme.colors.border, RoundedCornerShape(8.dp))
                    .clickable(onClick = onView)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        },
    )
}

internal val FILTER_MIN_WIDTH: Dp = 150.dp
private const val DISABLED_ALPHA = 0.4f
private const val STEPPER_DISABLED_ALPHA = 0.3f
private const val SUCCESS_DISMISS_MILLIS = 3_000L
