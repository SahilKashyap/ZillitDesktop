package com.zillit.desktop.feature.costreport.ui.analytics

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsFormat
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsModuleMeta

/**
 * The Analytics page — the web's standalone `/film-tools/cost-report/analytics`:
 * a header with Back and Filters, the title, the module strip, then the
 * overview or one module, with a skeleton while it loads.
 */
@Composable
fun AnalyticsScreen(state: AnalyticsUiState, onEvent: (AnalyticsEvent) -> Unit) {
    ProvideAnalyticsColors {
        val colors = analyticsColors
        Column(Modifier.fillMaxSize().background(colors.bg)) {
            AnalyticsHeader(state, onEvent)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val scroll = rememberScrollState()
                // A module opened from a card or an alert far down the page starts at its own top.
                LaunchedEffect(state.selected) { if (scroll.value > 0) scroll.animateScrollTo(0) }
                ZillitScrollColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = scroll,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier
                            .widthIn(max = 1640.dp)
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 32.dp, top = 26.dp, bottom = 90.dp),
                    ) {
                        TitleRow(state)
                        ModuleStrip(state, onEvent)
                        PageContent(state, onEvent)
                    }
                }
                FilterOverlay(state, onEvent)
            }
        }
    }
}

@Composable
private fun AnalyticsHeader(state: AnalyticsUiState, onEvent: (AnalyticsEvent) -> Unit) {
    val colors = analyticsColors
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .bottomRule(true, colors.line),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier.widthIn(max = 1640.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.line, RoundedCornerShape(9.dp))
                    .clickable { onEvent(AnalyticsEvent.Back) },
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(ZillitIcons.ChevronLeft, tint = colors.ink2, size = 15.dp)
            }
            Box(Modifier.padding(start = 2.dp).size(9.dp).background(colors.amber, RoundedCornerShape(3.dp)))
            ZillitText("Analytics", style = AnalyticsType.text(14.5f, FontWeight.ExtraBold, -0.01f), color = colors.ink)
            Spacer(Modifier.weight(1f))
            FiltersButton(state, onEvent)
        }
    }
}

@Composable
private fun FiltersButton(state: AnalyticsUiState, onEvent: (AnalyticsEvent) -> Unit) {
    val colors = analyticsColors
    val count = state.draft.activeCount(state.options)
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .height(38.dp)
            .clip(shape)
            .background(if (state.filtersOpen) colors.bg2 else colors.surface)
            .border(1.dp, colors.line, shape)
            .clickable { onEvent(AnalyticsEvent.ToggleFilters) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ZillitIcon(ZillitIcons.Filter, tint = colors.ink2, size = 14.dp)
        ZillitText("Filters", style = AnalyticsType.text(13f, FontWeight.Bold), color = colors.ink2)
        if (count > 0) {
            ZillitText(
                count.toString(),
                style = AnalyticsType.mono(10.5f, FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .background(colors.amber, CircleShape)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun TitleRow(state: AnalyticsUiState) {
    val colors = analyticsColors
    Column(Modifier.padding(bottom = 22.dp)) {
        ZillitText(
            buildAnnotatedString {
                append(state.titleWord)
                append(" ")
                withStyle(SpanStyle(color = colors.amber)) { append("Analytics") }
            },
            style = AnalyticsType.text(30f, FontWeight.ExtraBold, -0.022f),
            color = colors.ink,
        )
        if (state.subtitle.isNotBlank()) {
            ZillitText(
                state.subtitle,
                style = AnalyticsType.text(14f),
                color = colors.ink3,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}

// -- module strip ------------------------------------------------------------------------------

/** Overview, then a card per module with its filter-scoped total and movement. */
@Composable
private fun ModuleStrip(state: AnalyticsUiState, onEvent: (AnalyticsEvent) -> Unit) {
    FlowGrid(minCell = 158.dp, gap = 12.dp, fill = true, modifier = Modifier.padding(bottom = 24.dp)) {
        StripCard(
            on = state.isOverview,
            accent = analyticsColors.ink,
            onClick = { onEvent(AnalyticsEvent.Select(AnalyticsUiState.OVERVIEW)) },
        ) {
            // Ink on the light theme and near-white on the dark one, so its glyph takes the surface colour.
            StripIcon(
                ZillitIcons.Grid,
                on = state.isOverview,
                fill = analyticsColors.ink,
                glyph = analyticsColors.surface,
            )
            Spacer(Modifier.weight(1f).height(10.dp))
            StripLabel("Overview", state.isOverview)
            ZillitText(
                "All modules",
                style = AnalyticsType.text(11f),
                color = analyticsColors.ink3,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        state.modules.forEach { module -> ModuleCard(module, state.selected == module.id, onEvent) }
    }
}

@Composable
private fun ModuleCard(module: AnalyticsModuleMeta, on: Boolean, onEvent: (AnalyticsEvent) -> Unit) {
    val colors = analyticsColors
    val hex = colors.toneHex(module.tone)
    StripCard(on = on, accent = hex, onClick = { onEvent(AnalyticsEvent.Select(module.id)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StripIcon(moduleIcon(module.id), on = on, fill = colors.tone(module.tone).fg)
            Spacer(Modifier.weight(1f))
            DeltaChip(AnalyticsFormat.delta(module.delta, module.deltaFmt, "money", module.currency), module.deltaTone)
        }
        Spacer(Modifier.height(10.dp))
        StripLabel(module.label, on)
        ZillitText(
            AnalyticsFormat.money(module.total, module.currency),
            style = AnalyticsType.mono(19f, if (on) FontWeight.ExtraBold else FontWeight.Bold, -0.02f),
            color = if (on) colors.ink else colors.ink3,
            maxLines = 1,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** A strip card: raised and ringed in its accent when selected, a quieter glass when not. */
@Composable
private fun StripCard(on: Boolean, accent: Color, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = analyticsColors
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .defaultMinSize(minHeight = 104.dp)
            .then(if (on) Modifier.selectedRing(accent) else Modifier)
            .clip(shape)
            .background(if (on) colors.surface else colors.surfaceIdle)
            .border(1.dp, if (hovered && !on) colors.line2 else colors.line, shape)
            .hoverable(hover)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 12.dp),
    ) {
        content()
    }
}

/** The web's `0 0 0 3px` ring and soft drop shadow around the selected card. */
private fun Modifier.selectedRing(accent: Color): Modifier = this
    .shadow(
        10.dp,
        RoundedCornerShape(14.dp),
        ambientColor = accent.copy(alpha = RING_SHADOW),
        spotColor = accent.copy(alpha = RING_SHADOW),
    )
    .drawBehind {
        val ring = 3.dp.toPx()
        drawRoundRect(
            color = accent.copy(alpha = RING_ALPHA),
            topLeft = Offset(-ring / 2, -ring / 2),
            size = Size(size.width + ring, size.height + ring),
            cornerRadius = CornerRadius(14.dp.toPx() + ring / 2),
            style = Stroke(ring),
        )
    }

@Composable
private fun StripIcon(icon: ImageVector, on: Boolean, fill: Color, glyph: Color = Color.White) {
    val colors = analyticsColors
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .size(32.dp)
            .then(if (on) Modifier.shadow(4.dp, shape) else Modifier)
            .background(if (on) fill else colors.track, shape)
            .then(if (on) Modifier else Modifier.border(1.dp, colors.line, shape)),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon, tint = if (on) glyph else colors.ink4, size = 16.dp)
    }
}

@Composable
private fun StripLabel(text: String, on: Boolean) {
    val colors = analyticsColors
    ZillitText(
        text,
        style = AnalyticsType.text(12.5f, if (on) FontWeight.ExtraBold else FontWeight.SemiBold, -0.01f),
        color = if (on) colors.ink else colors.ink2,
        maxLines = 2,
    )
}

// -- content -----------------------------------------------------------------------------------

@Composable
private fun PageContent(state: AnalyticsUiState, onEvent: (AnalyticsEvent) -> Unit) {
    val context = remember(state.currency, state.modules, onEvent) {
        BlockContext(
            currency = state.currency,
            modules = state.modules,
            onSelect = { onEvent(AnalyticsEvent.Select(it)) },
            onLink = { onEvent(AnalyticsEvent.OpenLink(it)) },
        )
    }
    when (state.status) {
        AnalyticsStatus.Loading -> LoadingSkeleton()
        AnalyticsStatus.Error -> ErrorCard(state.error, onRetry = { onEvent(AnalyticsEvent.Retry) })
        AnalyticsStatus.Empty -> EmptyCard()
        AnalyticsStatus.Ready -> state.page?.let { page ->
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (page.blocks.isNotEmpty()) BlockList(page.blocks, context)
                if (page.tabs.isNotEmpty()) {
                    Segmented(
                        page.tabs.map { it.id to it.label },
                        state.activeTab,
                    ) { onEvent(AnalyticsEvent.SelectTab(it)) }
                    when (state.tabStatus) {
                        TabStatus.Loading -> LoadingSkeleton()
                        TabStatus.Error -> ErrorCard("Couldn’t load this tab.", onRetry = null)
                        TabStatus.Idle -> state.activeTabBlocks?.let { BlockList(it, context) }
                    }
                }
            }
        }
    }
}

/** The page's shape while it loads — KPI row, a wide chart, then a chart beside a donut. */
@Composable
private fun LoadingSkeleton() {
    val colors = analyticsColors
    val shift by rememberInfiniteTransition().animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing), RepeatMode.Restart),
    )
    val brush = Brush.horizontalGradient(
        listOf(colors.skeleton, colors.skeletonHighlight, colors.skeleton),
        startX = shift * SHIMMER_SPAN,
        endX = shift * SHIMMER_SPAN + SHIMMER_SPAN,
    )
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TemplateGrid(tracks = List(4) { GridTrack.Weight(1f) }, gap = 16.dp) {
            repeat(4) {
                SkeletonPanel(Modifier.defaultMinSize(minHeight = 128.dp), radius = 14.dp, padding = 16.dp) {
                    Bone(brush, 88.dp, 9.dp)
                    Bone(brush, 128.dp, 26.dp, Modifier.padding(top = 10.dp))
                    Bone(brush, null, 18.dp, Modifier.padding(top = 30.dp))
                }
            }
        }
        SkeletonPanel {
            Bone(brush, 210.dp, 13.dp, Modifier.padding(bottom = 16.dp))
            Bone(brush, null, 250.dp)
        }
        TemplateGrid(tracks = listOf(GridTrack.Weight(1.5f), GridTrack.Weight(1f)), gap = 20.dp) {
            SkeletonPanel {
                Bone(brush, 180.dp, 13.dp, Modifier.padding(bottom = 16.dp))
                Bone(brush, null, 230.dp)
            }
            SkeletonPanel {
                Bone(brush, 130.dp, 13.dp, Modifier.padding(bottom = 16.dp))
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) { Bone(brush, 176.dp, 176.dp, radius = 999.dp) }
                repeat(3) { Bone(brush, null, 12.dp, Modifier.padding(top = 10.dp)) }
            }
        }
    }
}

@Composable
private fun SkeletonPanel(
    modifier: Modifier = Modifier,
    radius: Dp = 14.dp,
    padding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val colors = analyticsColors
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, colors.line, shape)
            .then(
                if (padding > 0.dp) Modifier.padding(
                    padding,
                ) else Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            ),
    ) {
        content()
    }
}

@Composable
private fun Bone(brush: Brush, width: Dp?, height: Dp, modifier: Modifier = Modifier, radius: Dp = 8.dp) {
    val sized = if (width == null) modifier.fillMaxWidth() else modifier.size(width, height)
    Box(sized.height(height).clip(RoundedCornerShape(radius)).background(brush))
}

@Composable
private fun StateCard(content: @Composable () -> Unit) {
    val colors = analyticsColors
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ErrorCard(message: String?, onRetry: (() -> Unit)?) {
    val colors = analyticsColors
    val red = colors.tone("red")
    StateCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToneIcon(ZillitIcons.Warning, red, box = 40.dp, glyph = 18.dp, radius = 12.dp, tint = red.ink)
            ZillitText("Couldn’t load analytics", style = AnalyticsType.text(14f, FontWeight.Bold), color = colors.ink)
            message?.takeIf { it.isNotBlank() }?.let {
                ZillitText(
                    it,
                    style = AnalyticsType.text(12.5f).copy(lineHeight = AnalyticsType.text(18.75f).fontSize),
                    color = colors.ink3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 440.dp),
                )
            }
            onRetry?.let { retry ->
                ZillitText(
                    "Retry",
                    style = AnalyticsType.text(13f, FontWeight.Bold),
                    color = colors.ink2,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.line, RoundedCornerShape(10.dp))
                        .clickable(onClick = retry)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyCard() {
    val colors = analyticsColors
    StateCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(40.dp)
                    .background(colors.bg2, RoundedCornerShape(12.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(ZillitIcons.Info, tint = colors.ink3, size = 18.dp)
            }
            ZillitText("No analytics yet", style = AnalyticsType.text(14f, FontWeight.Bold), color = colors.ink)
            ZillitText(
                "There’s nothing to show for this selection.",
                style = AnalyticsType.text(12.5f),
                color = colors.ink3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val RING_ALPHA = 0.10f
private const val RING_SHADOW = 0.45f
private const val SHIMMER_MS = 1350
private const val SHIMMER_SPAN = 840f
