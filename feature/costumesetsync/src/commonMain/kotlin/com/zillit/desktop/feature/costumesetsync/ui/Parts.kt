package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.Tone
import com.zillit.desktop.feature.costumesetsync.domain.statusTone

/**
 * The pieces every Costumes & Set Sync screen is assembled from — the web's
 * `components/ui.jsx`, drawn with the app's own design system. Screens use
 * these rather than reaching for raw components, so a status reads as the
 * same colour everywhere (the reference's `tone()`, value for value).
 */

internal fun Tone.toStatusTone(): StatusTone = when (this) {
    Tone.Ok -> StatusTone.Ready
    Tone.Info -> StatusTone.Progress
    Tone.Warn -> StatusTone.Pending
    Tone.Danger -> StatusTone.Rejected
    Tone.Accent -> StatusTone.Escalated
    Tone.Muted -> StatusTone.Neutral
}

/** A status as a coloured pill (the web's `.csync-badge`); [label] defaults to the enum's own words. */
@Composable
fun StatusBadge(status: String?, label: String = tEnum(status), modifier: Modifier = Modifier, large: Boolean = false) {
    if (status.isNullOrBlank() && label.isBlank()) return
    TonePill(label, statusTone(status), modifier, large)
}

/** A readiness traffic light (`READY`, `PARTIAL`, `MISSING`…) or a priority's severity; [pulse] rings a missing one. */
@Composable
fun ReadinessDot(level: String?, modifier: Modifier = Modifier, pulse: Boolean = false) {
    val tone = when (level) {
        "READY", "OK", "SUCCESS" -> Tone.Ok
        "PARTIAL", "ALTERATION", "CLEANING", "WARNING" -> Tone.Warn
        "MISSING", "DAMAGED", "CRITICAL" -> Tone.Danger
        "INFO" -> Tone.Info
        else -> Tone.Muted
    }
    ToneDot(tone, modifier, pulse)
}

/**
 * The heading block of every page, as the web draws it: a square back arrow, the
 * title large with its sub-line under it, and the page's actions at the right.
 */
@Composable
fun PageHead(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    note: String? = null,
    bottomPadding: androidx.compose.ui.unit.Dp = ZillitTheme.spacing.md,
    /** The web's `crumbs`: a 12sp muted line over the title ("Fittings / Anna"). */
    crumbs: String? = null,
    /** The web lets `title` be markup (avatar, name, badge); when given it replaces the plain [title] text. */
    titleContent: (@Composable () -> Unit)? = null,
) {
    val ctx = LocalSync.current
    val colors = ZillitTheme.colors
    Row(
        modifier.fillMaxWidth().padding(bottom = bottomPadding),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            Modifier
                .size(BACK_SIZE)
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surface)
                .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                .clickable { ctx.nav.backOr("dashboard") },
            contentAlignment = Alignment.Center,
        ) { ZillitIcon(ZillitIcons.ArrowLeft, tint = colors.textPrimary, size = BACK_ICON) }
        Column(Modifier.weight(1f).widthIn(min = TITLE_MIN_WIDTH)) {
            crumbs?.takeIf { it.isNotBlank() }?.let { ZillitText(it, Modifier.padding(bottom = 2.dp), style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp), color = colors.textMuted, maxLines = 1) }
            if (titleContent != null) {
                titleContent()
            } else {
                ZillitText(title, style = ZillitTheme.typography.titleLarge.copy(fontSize = TITLE_SIZE, lineHeight = TITLE_LINE, fontWeight = FontWeight.Bold), maxLines = 2)
            }
            sub?.takeIf { it.isNotBlank() }?.let { ZillitText(it, style = ZillitTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 2) }
            // The web's `.csync-pagehead__note`: a red helper line under the sub-line.
            note?.takeIf { it.isNotBlank() }?.let { ZillitText(it, Modifier.padding(top = 4.dp), style = ZillitTheme.typography.bodyMedium, color = colors.danger, maxLines = 2) }
        }
        actions?.let {
            Row(
                Modifier,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
                verticalAlignment = Alignment.Top,
                content = it,
            )
        }
    }
}

private val TITLE_MIN_WIDTH = 220.dp
private val FILTER_WIDTH = 170.dp
// `.csync-pagehead__title`: 24px bold.
private val TITLE_SIZE = 24.sp
private val TITLE_LINE = 30.sp
private val BACK_SIZE = 38.dp
private val BACK_ICON = 18.dp

/**
 * One headline figure, as the web draws it: a plain white tile with a small
 * upper-case label over a large number, which alone takes the tone's colour.
 */
@Composable
fun StatCard(
    label: String,
    value: Any?,
    modifier: Modifier = Modifier,
    tone: Tone? = null,
    hint: String? = null,
    compact: Boolean = false,
    /** A tile that is the current filter: ink border, doubled (the web's `.csync-stat--active`). */
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    val ink = when (tone) {
        Tone.Info -> colors.info
        Tone.Ok -> colors.success
        Tone.Warn -> colors.warning
        Tone.Danger -> colors.danger
        else -> colors.textPrimary
    }
    val shape = ZillitTheme.shapes.large
    // `.csync-stats--compact`: 10/12 padding, a 10.5 label, a 20 figure; the Dashboard's tiles.
    val labelStyle = if (compact) ZillitTheme.typography.labelSmall.copy(fontSize = COMPACT_LABEL, letterSpacing = COMPACT_TRACK) else ZillitTheme.typography.labelSmall
    val valueStyle = if (compact) {
        ZillitTheme.typography.titleLarge.copy(fontSize = COMPACT_VALUE, lineHeight = COMPACT_VALUE_LINE, fontWeight = FontWeight.Bold)
    } else {
        ZillitTheme.typography.titleLarge.copy(fontSize = TITLE_SIZE, lineHeight = TITLE_LINE, fontWeight = FontWeight.Bold)
    }
    Column(
        modifier
            .clip(shape)
            .background(colors.surface)
            .statBorder(active, colors.textPrimary, colors.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (compact) 12.dp else ZillitTheme.spacing.lg, vertical = if (compact) 10.dp else ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(label.uppercase(), style = labelStyle, color = colors.textSecondary, maxLines = 2)
        ZillitText(value?.toString() ?: "0", style = valueStyle, color = ink)
        hint?.let { ZillitText(it, style = ZillitTheme.typography.bodySmall.copy(fontSize = 11.sp), color = colors.textSecondary, maxLines = 2) }
    }
}

private val COMPACT_LABEL = 10.5.sp
private val COMPACT_TRACK = 0.06.em
/** A tile's edge: hairline normally, a doubled ink edge when it is the current filter. */
private fun Modifier.statBorder(active: Boolean, ink: androidx.compose.ui.graphics.Color, edge: androidx.compose.ui.graphics.Color, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (active) border(2.dp, ink, shape) else border(1.dp, edge, shape)

private val COMPACT_VALUE = 20.sp
private val COMPACT_VALUE_LINE = 24.sp

/**
 * The web's `.csync-card`: 12dp corners, a hairline edge and a soft shadow; the title sits in the card with no rule
 * under it (16dp of padding, then 12 to the content). A [flush] card (a list) keeps its rows edge to edge.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    meta: String? = null,
    flush: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(CARD_RADIUS)
    val head = title != null || actions != null
    val edge = if (flush) 14.dp else ZillitTheme.spacing.lg
    Column(
        modifier.fillMaxWidth().shadow(CARD_SHADOW, shape).clip(shape).background(colors.surface).border(1.dp, colors.border, shape),
    ) {
        if (head) {
            Row(
                Modifier.fillMaxWidth().padding(start = edge, end = edge, top = edge, bottom = if (flush) ZillitTheme.spacing.md else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZillitText(
                    title.orEmpty(),
                    Modifier.weight(1f),
                    style = ZillitTheme.typography.titleSmall.copy(fontSize = CARD_TITLE, lineHeight = CARD_TITLE_LINE, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                meta?.let { ZillitText(it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1) }
                actions?.invoke(this)
            }
        }
        val body = if (flush) Modifier else Modifier.padding(start = ZillitTheme.spacing.lg, end = ZillitTheme.spacing.lg, bottom = ZillitTheme.spacing.lg, top = if (head) ZillitTheme.spacing.md else ZillitTheme.spacing.lg)
        Column(body, content = content)
    }
}

private val CARD_RADIUS = 12.dp
private val CARD_SHADOW = 1.dp
private val CARD_TITLE = 15.sp
private val CARD_TITLE_LINE = 22.sp

@Composable
fun EmptyState(title: String, hint: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val emoji = emojiFor(title)
    if (emoji == null) {
        ZillitEmptyState(title = title, message = hint, modifier = modifier, action = action)
        return
    }
    // The reference's own empty state: an emoji over the title, hint and action.
    Column(
        modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // `.csync-empty--icon`: a 32 emoji, a 15 title, a 13 muted hint, then the action 16 below.
        ZillitText(emoji, Modifier.padding(bottom = 10.dp), style = ZillitTheme.typography.titleLarge.copy(fontSize = EMOJI_SIZE, lineHeight = EMOJI_SIZE))
        ZillitText(title, style = ZillitTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        hint?.let { ZillitText(it, Modifier.padding(top = 6.dp), style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textMuted, maxLines = 3, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        action?.let { Box(Modifier.padding(top = ZillitTheme.spacing.lg)) { it() } }
    }
}

private val EMOJI_SIZE = 32.sp

/** The emoji the web gives an empty state, found by its title's key: the web passes `icon` per call site. */
private fun emojiFor(title: String): String? = EMPTY_EMOJI.entries.firstOrNull { t(it.key) == title }?.value

private val EMPTY_EMOJI: Map<String, String> = mapOf(
    "csync_actors_empty_title" to "🎭",
    "csync_actors_no_match" to "🔍",
    "csync_budget_empty" to "💸",
    "csync_budget_finance_only" to "🔒",
    "csync_budget_no_match" to "🔍",
    "csync_change_not_found" to "👗",
    "csync_character_not_found" to "🧍",
    "csync_characters_empty_title" to "🧍",
    "csync_characters_no_match" to "🔍",
    "csync_cleaning_empty_title" to "🧼",
    "csync_cleaning_none_match" to "🧼",
    "csync_click_scene_above" to "🎬",
    "csync_costumes_empty_title" to "👗",
    "csync_dash_scenes_empty_title" to "🎬",
    "csync_fittings_empty_title" to "📏",
    "csync_fittings_none_match" to "📏",
    "csync_gallery_empty_title" to "🖼",
    "csync_no_changes_yet" to "👗",
    "csync_no_characters_in_scene" to "🧍",
    "csync_no_shoot_days" to "🎞️",
    "csync_no_takes_recorded" to "📖",
    "csync_no_takes_yet" to "📖",
    "csync_not_in_any_scene_yet" to "🎬",
    "csync_nothing_scheduled_day" to "📋",
    "csync_nothing_to_display" to "★",
    "csync_notifications_empty_title" to "🔔",
    "csync_rentals_no_match" to "🔍",
    "csync_rentals_none" to "🏷",
    "csync_scan_empty_title" to "📷",
    "csync_scene_not_found" to "🎬",
    "csync_scenes_empty_title" to "🎬",
    "csync_vendors_no_match" to "🔍",
    "csync_vendors_none" to "🏬",
    "csync_view_only_book" to "🎬",
)

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        repeat(SKELETON_ROWS) { ZillitSkeletonBar(Modifier.fillMaxWidth()) }
    }
}

/**
 * Renders [resource]: a skeleton while it loads, the error with a retry when
 * it failed with nothing to show, else [content]. A silent reload keeps the
 * content on screen.
 */
@Composable
fun <T> Await(resource: Resource<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (val state = resource.state) {
        Load.Loading -> LoadingView(modifier)
        is Load.Failed -> ZillitErrorState(message = state.message, onRetry = { resource.reload() }, modifier = modifier)
        is Load.Ready -> content(state.value)
    }
}

/** A filter dropdown: the placeholder is the "any" row, so clearing is one pick. */
@Composable
fun FilterSelect(
    value: String,
    options: List<Pair<String, String>>,
    placeholder: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.width(FILTER_WIDTH),
) {
    val any = "" to placeholder
    ZillitSelect(
        value = (options.firstOrNull { it.first == value } ?: any),
        options = listOf(any) + options,
        onSelect = { onChange(it.first) },
        label = { it.second },
        modifier = modifier,
    )
}

/** The enum values of a `/meta` list as filter options (value, its words). */
fun enumOptions(values: List<String>): List<Pair<String, String>> = values.map { it to tEnum(it) }

/** "Previous · Page X of Y · Next". Nothing when there is one page. */
@Composable
fun Pager(page: Int, pages: Int, onPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (pages <= 1) return
    // `.csync-pager`: a hairline over it, 12 of padding and 12 between.
    ZillitDivider()
    Row(
        modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(t("csync_previous"), onClick = { onPage(page - 1) }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, enabled = page > 1)
        ZillitText(t("csync_page_of", "page" to page, "pages" to pages), style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
        ZillitButton(t("csync_next"), onClick = { onPage(page + 1) }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, enabled = page < pages)
    }
}

/** A label over a value, the web's `dl.csync-fields`. Blank values draw "—". */
@Composable
fun FieldRow(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Column(modifier.padding(vertical = ZillitTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ZillitText(label.uppercase(), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
        ZillitText(
            value.ifBlank { "—" },
            style = if (mono) ZillitTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else ZillitTheme.typography.bodyMedium,
        )
    }
}

/** Fields laid out in a wrapping grid. */
@Composable
fun FieldGrid(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) { content() }
}

/** A clickable list row: [main] fills the row, [end] sits after it. */
@Composable
fun ListRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    end: (@Composable RowScope.() -> Unit)? = null,
    main: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Row(
            modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = main)
            end?.invoke(this)
        }
        ZillitDivider()
    }
}

/** A thin bold line of text — a row's title. */
@Composable
fun RowTitle(text: String, modifier: Modifier = Modifier) {
    ZillitText(text, modifier, style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    ZillitText(text, modifier, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary, maxLines = maxLines)
}

/** Monospace asset numbers (`CST-000245`). */
@Composable
fun MonoText(text: String, modifier: Modifier = Modifier) {
    ZillitText(text, modifier, style = ZillitTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold), maxLines = 1)
}

/** The reference's category marks: shoes, accessories and jewellery, and a shirt for everything else. */
fun categoryIcon(category: String?): String = when (category) {
    "FOOTWEAR" -> "👞"
    "ACCESSORY" -> "⌚"
    "JEWELLERY" -> "💍"
    else -> "👕"
}

/**
 * One costume piece as a list row, as the reference's `CostumeRow`: a category
 * mark in a rounded square, the asset number and name, "type · colour · size · for whom",
 * then where the piece is and its status. [onClick] makes the whole row open
 * something; [end] adds a control after the status (a Remove in a form's
 * piece list); [noStatus] drops the badge where every row would say the same;
 * [extra] is appended to the detail line (" · match 3" on a replacement).
 */
@Composable
fun CostumeRow(c: Rec, onClick: (() -> Unit)? = null, end: (@Composable RowScope.() -> Unit)? = null, noStatus: Boolean = false, extra: String = "", last: Boolean = false) {
    val colors = ZillitTheme.colors
    val detail = listOfNotNull(
        c.str("type").ifBlank { null },
        c.str("color").ifBlank { null },
        c.str("size").ifBlank { null }?.let { "${t("csync_size")} $it" },
        c.rec("character")?.str("name")?.ifBlank { null }?.let { t("csync_for_name", "name" to it) },
    ).joinToString(" · ") + extra
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (onClick != null && hovered) colors.surfaceHover else Color.Transparent)
                .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlyphAvatar(categoryIcon(c.str("category")))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ZillitText(c.str("asset_number"), style = ZillitTheme.typography.bodyLarge.copy(fontSize = 12.9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                    ZillitText(c.str("name"), Modifier.weight(1f, fill = false), style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp), maxLines = 1)
                }
                if (detail.isNotBlank()) ZillitText(detail, style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp), color = colors.textMuted, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                c.str("location").takeIf { it.isNotBlank() }?.let { ZillitText(it, style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp), color = colors.textMuted, maxLines = 1) }
                if (!noStatus && c.str("status").isNotBlank()) StatusBadge(c.str("status"))
                end?.invoke(this)
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    }
}

/**
 * A record's history, as the web's Timeline: each entry has a 2dp rule at its left, the monospace "when · who" line
 * over the title and any note. Capped at 42% of the window so the panel beside it stays in view.
 */
@Composable
fun Timeline(entries: List<Rec>, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val windowHeight = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() }
    val cap = (windowHeight * TIMELINE_MAX_VIEWPORT).coerceAtLeast(MIN_TIMELINE_HEIGHT)
    Column(
        modifier.fillMaxWidth().heightIn(max = cap).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        entries.forEach { entry ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(colors.border))
                Column(Modifier.padding(start = 12.dp)) {
                    val when_ = listOf(com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime(entry.long("at")), entry.str("by")).filter { it.isNotBlank() }.joinToString(" · ")
                    ZillitText(when_, style = ZillitTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace), color = colors.textMuted)
                    ZillitText(entry.str("title"), style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                    entry.str("detail").takeIf { it.isNotBlank() }?.let { MutedText(it, maxLines = 3) }
                }
            }
        }
    }
}

private const val TIMELINE_MAX_VIEWPORT = 0.42f
private val MIN_TIMELINE_HEIGHT = 200.dp

/** A row of wrapping chips / buttons. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, gap: androidx.compose.ui.unit.Dp = ZillitTheme.spacing.sm, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) { content() }
}

internal val ICON_ADD = ZillitIcons.Add

private const val SKELETON_ROWS = 6
