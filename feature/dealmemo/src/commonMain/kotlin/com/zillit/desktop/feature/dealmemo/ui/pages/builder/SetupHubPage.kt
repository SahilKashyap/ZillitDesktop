package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.DealTemplate
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup
import com.zillit.desktop.feature.dealmemo.ui.SetupHubEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmConfirm
import com.zillit.desktop.feature.dealmemo.ui.components.DmConfirmKind
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Deal Memo Setup (`DMSetupHubPage.jsx`): the Union and Non-Union setups as
 * cards — edit, start a deal from one, delete — and, while a group has none,
 * a new setup of that group right in its tab.
 */
@Composable
fun SetupHubPage(state: DealMemoUiState, page: DealMemoRoute.SetupHub, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    val group = page.group ?: SetupGroup.Union
    val inline = state.hub.inlineFor == group && state.builder?.mode?.embedded == true
    ProvideDealLabels(deal = false) {
        Column(Modifier.fillMaxSize().background(p.page)) {
            HubHeader(onEvent)
            Column(Modifier.fillMaxWidth().weight(1f).background(p.card)) {
                GroupTabs(group, failed = state.templates.failed, onEvent)
                Box(Modifier.fillMaxWidth().weight(1f).background(p.hubPane)) {
                    if (inline) {
                        BuilderPage(state, onEvent)
                    } else {
                        SetupList(state, group, onEvent)
                    }
                }
            }
        }
        val target = state.hub.confirmDelete
        DmConfirm(
            visible = target != null,
            title = str(S.dm_hub_delete_title),
            message = str(
                S.desktop_dm_delete_setup_message,
                target?.name?.ifEmpty { null } ?: str(S.desktop_dm_this_setup),
            ),
            confirmLabel = str(S.yes),
            cancelLabel = str(S.no),
            onConfirm = { onEvent(SetupHubEvent.ConfirmDelete) },
            onCancel = { onEvent(SetupHubEvent.CancelDelete) },
            kind = DmConfirmKind.Danger,
            loading = target != null && state.hub.deletingId == target.id,
            loadingLabel = str(S.dm_hub_deleting),
        )
    }
}

@Composable
private fun HubHeader(onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    Column(Modifier.fillMaxWidth().background(p.header)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val (source, hovered) = rememberHover()
            val shape = RoundedCornerShape(10.dp)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(shape)
                    .background(if (hovered) p.chipHover else p.chipBg)
                    .border(1.dp, p.hairline, shape)
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = null) { onEvent(SetupHubEvent.Back) }
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) { ZillitIcon(ZillitIcons.ChevronLeft, size = 13.dp, tint = p.ink2) }
            val (crumbSource, crumbHovered) = rememberHover()
            ZillitText(
                text = str(S.dm_hub_eyebrow),
                style = DmType.sans(11.sp, FontWeight.Bold, 0.08.em),
                color = p.cta,
                modifier = Modifier
                    .alpha(if (crumbHovered) HOVER_ALPHA else 1f)
                    .hoverable(crumbSource)
                    .clickable(interactionSource = crumbSource, indication = null) { onEvent(SetupHubEvent.Back) }
                    .pointerHoverIcon(PointerIcon.Hand),
            )
            ZillitText(text = "/", style = DmType.sans(12.5.sp), color = p.placeholder)
            ZillitText(text = str(S.dm_setup_title), style = DmType.sans(12.5.sp, FontWeight.SemiBold), color = p.ink2)
        }
        Rule(p.hairline)
    }
}

@Composable
private fun GroupTabs(selected: SetupGroup, failed: Boolean, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    Row(
        modifier = Modifier.fillMaxWidth().drawBehind {
            val stroke = 1.dp.toPx()
            drawLine(
                p.divider,
                Offset(0f, size.height - stroke / 2),
                Offset(size.width, size.height - stroke / 2),
                stroke,
            )
        }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SetupGroup.entries.forEach { group ->
            GroupTab(group.label, active = group == selected) { onEvent(SetupHubEvent.PickGroup(group)) }
        }
        if (failed) {
            ZillitText(
                text = str(S.desktop_dm_couldnt_load_setups),
                style = DmType.sans(11.sp),
                color = p.redHover,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun GroupTab(label: String, active: Boolean, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    val bar = p.cta
    Box(
        modifier = Modifier
            .background(
                when {
                    active -> p.hubTabActive
                    hovered -> p.chipHover
                    else -> Color.Transparent
                },
            )
            .drawBehind {
                if (active) {
                    val stroke = 3.dp.toPx()
                    drawLine(
                        bar,
                        Offset(0f, size.height - stroke / 2),
                        Offset(size.width, size.height - stroke / 2),
                        stroke,
                    )
                }
            }
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        ZillitText(
            text = label,
            style = DmType.sans(13.5.sp, FontWeight.SemiBold),
            color = if (active) p.title else p.hubTabInk,
        )
    }
}

@Composable
private fun SetupList(state: DealMemoUiState, group: SetupGroup, onEvent: (DealMemoEvent) -> Unit) {
    val rows = state.templates.rows
    val query = state.hub.query.trim()
    val visible = rows.orEmpty()
        .filter { it.nonUnion == (group == SetupGroup.NonUnion) }
        .filter { query.isEmpty() || it.name.lowercase().contains(query.lowercase()) }
        .sortedByDescending { it.createdAt ?: 0L }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 28.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.weight(1f))
            SearchBox(state.hub.query) { onEvent(SetupHubEvent.Search(it)) }
            AmberButton(str(S.dm_hub_new_setup), trailing = ZillitIcons.Add) { onEvent(SetupHubEvent.NewSetup) }
        }
        when {
            rows == null -> CardGrid(List(SKELETON_CARDS) { it }) { SkeletonCard() }
            visible.isEmpty() -> EmptyState(query, group)
            else -> CardGrid(visible) { template ->
                SetupCard(state, template, busy = state.hub.deletingId == template.id, onEvent)
            }
        }
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit) {
    val p = bp
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .width(SEARCH_WIDTH)
            .clip(shape)
            .background(p.card)
            .border(1.dp, if (focused) p.cta else p.hubBorder, shape)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(ZillitIcons.Search, size = 15.dp, tint = p.hubMuted)
        Box(Modifier.weight(1f)) {
            val style = DmType.sans(13.5.sp)
            if (query.isEmpty()) ZillitText(
                text = str(S.dm_hub_search_hint),
                style = style,
                color = p.hubMuted,
                maxLines = 1,
            )
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = style.copy(color = p.title),
                cursorBrush = SolidColor(p.cta),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
    }
}

/** `AMBER_BTN`: the amber gradient with its glow. */
@Composable
private fun AmberButton(text: String, trailing: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .shadow(if (enabled) 6.dp else 0.dp, shape, ambientColor = AMBER_GLOW, spotColor = AMBER_GLOW)
            .clip(shape)
            .background(Brush.verticalGradient(if (hovered && enabled) AMBER_HOVER else AMBER))
            .hoverable(source)
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitText(text = text, style = DmType.sans(13.sp, FontWeight.Bold), color = Color.White, maxLines = 1)
        ZillitIcon(trailing, size = 12.dp, tint = Color.White)
    }
}

/** `repeat(auto-fill, minmax(300px, 1fr))`, 16 apart. */
@Composable
private fun <T> CardGrid(items: List<T>, card: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        val columns = ((maxWidth + GRID_GAP) / (CARD_MIN + GRID_GAP)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { card(item) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun SetupCard(state: DealMemoUiState, template: DealTemplate, busy: Boolean, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    val lift by animateDpAsState(if (hovered) (-2).dp else 0.dp, tween(CARD_MILLIS))
    val glow by animateFloatAsState(if (hovered) 1f else 0f, tween(CARD_MILLIS))
    val shape = RoundedCornerShape(12.dp)
    val person = template.createdBy?.let(state.people::get)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = lift.toPx() }
            .shadow(
                (1 + glow * CARD_SHADOW).dp,
                shape,
                ambientColor = Color.Black.copy(alpha = 0.2f),
                spotColor = Color.Black.copy(alpha = 0.2f),
            )
            .clip(shape)
            .background(p.card)
            .border(1.dp, if (hovered) p.cta else p.hubBorder, shape)
            .hoverable(source)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.amberSoft)
                    .border(1.dp, p.amberRing, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { ZillitIcon(ZillitIcons.File, size = 21.dp, tint = p.cta) }
            ZillitText(
                text = template.name.ifEmpty { str(S.desktop_dm_untitled_setup) },
                style = DmType.sans(15.sp, FontWeight.Bold),
                color = p.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ZillitText(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = p.title)) {
                    append(person?.fullName?.ifEmpty { null } ?: str(S.desktop_unknown))
                }
                person?.designationName?.takeIf { it.isNotEmpty() }?.let { append(" · ${DealLabels.formatLabel(it)}") }
            },
            style = DmType.sans(12.5.sp),
            color = p.hubTabInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ZillitIcon(ZillitIcons.Calendar, size = 14.dp, tint = p.hubMuted)
            ZillitText(text = createdText(template.createdAt), style = DmType.mono(12.sp), color = p.hubTabInk)
        }
        Row(
            modifier = Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .drawBehind { drawLine(p.hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .padding(top = 14.dp)
                .alpha(if (busy) DISABLED_ALPHA else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardIcon(ZillitIcons.Trash, str(S.desktop_dm_delete_setup), danger = true, enabled = !busy) {
                onEvent(SetupHubEvent.AskDelete(template))
            }
            CardIcon(ZillitIcons.Edit, str(S.desktop_dm_edit_this_setup), danger = false, enabled = !busy) {
                onEvent(SetupHubEvent.Edit(template))
            }
            Spacer(Modifier.weight(1f))
            AmberButton(str(S.dm_wizard_title), trailing = ZillitIcons.ChevronRight, enabled = !busy) {
                onEvent(SetupHubEvent.Use(template))
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun CardIcon(
    icon: ImageVector,
    tooltip: String,
    danger: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val p = bp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(8.dp)
    val lit = hovered && enabled
    ZillitTooltip(text = tooltip) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(shape)
                .background(
                    when {
                        lit && danger -> p.redSoft
                        lit -> p.chipHover
                        else -> p.card
                    },
                )
                .border(1.dp, if (lit && danger) p.redBorder else if (lit) p.menuBorder else p.hubBorder, shape)
                .hoverable(source)
                .then(
                    if (enabled) {
                        Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon,
                size = 15.dp,
                tint = when {
                    lit && danger -> p.redHover
                    lit -> p.title
                    else -> p.hubTabInk
                },
            )
        }
    }
}

@Composable
private fun EmptyState(query: String, group: SetupGroup) {
    val p = bp
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .dashedBorder(p.dashed, 12.dp)
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (query.isNotEmpty()) {
            ZillitText(text = str(S.dm_hub_no_match, query), style = DmType.sans(13.sp), color = p.hubMuted)
        } else {
            ZillitText(
                text = str(S.dm_hub_empty, group.label),
                style = DmType.sans(13.5.sp, FontWeight.SemiBold),
                color = p.ink2,
            )
            ZillitText(
                text = str(S.desktop_dm_a_setup_holds_the_agreement_rates_allowances),
                style = DmType.sans(12.5.sp).copy(lineHeight = 20.sp),
                color = p.hubMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp).widthIn(max = 440.dp),
            )
        }
    }
}

@Composable
private fun SkeletonCard() {
    val p = bp
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(p.card).border(1.dp, p.hubBorder, shape).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(p.skeleton))
            Box(Modifier.fillMaxWidth(HALF).height(16.dp).clip(RoundedCornerShape(4.dp)).background(p.skeleton))
        }
        Box(
            Modifier
                .padding(top = 16.dp)
                .fillMaxWidth(TWO_THIRDS)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(p.skeleton),
        )
        Box(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(ONE_THIRD)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(p.skeleton),
        )
    }
}

/** `fmtDateTime`: `26 Aug 2026, 19:17` in the viewer's zone; nothing for no stamp. */
private fun createdText(createdAt: Long?): String {
    if (createdAt == null || createdAt <= 0L) return ""
    val time = Instant.fromEpochMilliseconds(createdAt).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = MONTHS[time.month.ordinal]
    return "${time.day.toString().padStart(2, '0')} $month ${time.year}, ${time.hour.toString().padStart(2, '0')}:" +
        time.minute.toString().padStart(2, '0')
}

private val MONTHS get() = listOf(
    str(S.desktop_month_short_jan),
    str(S.desktop_month_short_feb),
    str(S.desktop_month_short_mar),
    str(S.desktop_month_short_apr),
    str(S.desktop_month_short_may),
    str(S.desktop_month_short_jun),
    str(S.desktop_month_short_jul),
    str(S.desktop_month_short_aug),
    str(S.desktop_month_short_sep),
    str(S.desktop_month_short_oct),
    str(S.desktop_month_short_nov),
    str(S.desktop_month_short_dec),
)
private val AMBER = listOf(Color(0xFFFC9404), Color(0xFFEA7A0E))
private val AMBER_HOVER = listOf(Color(0xFFFFA21F), Color(0xFFF1851D))
private val AMBER_GLOW = Color(0xFFEA7A0E)
private val SEARCH_WIDTH = 240.dp
private val CARD_MIN = 300.dp
private val GRID_GAP = 16.dp
private const val SKELETON_CARDS = 3
private const val CARD_MILLIS = 150
private const val CARD_SHADOW = 9
private const val DISABLED_ALPHA = 0.5f
private const val HOVER_ALPHA = 0.8f
private const val HALF = 0.5f
private const val TWO_THIRDS = 0.66f
private const val ONE_THIRD = 0.33f
