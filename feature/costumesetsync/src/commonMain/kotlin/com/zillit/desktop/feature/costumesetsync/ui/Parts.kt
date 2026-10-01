package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** A status as a coloured pill; [label] defaults to the enum's own words. */
@Composable
fun StatusBadge(status: String?, label: String = tEnum(status), modifier: Modifier = Modifier) {
    if (status.isNullOrBlank() && label.isBlank()) return
    ZillitStatusPill(label = label, tone = statusTone(status).toStatusTone(), modifier = modifier, dot = true)
}

/** A readiness traffic light (`READY`, `PARTIAL`, `MISSING`…) or a priority's severity. */
@Composable
fun ReadinessDot(level: String?, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val tone = when (level) {
        "READY", "OK", "SUCCESS" -> Tone.Ok
        "PARTIAL", "ALTERATION", "CLEANING", "WARNING" -> Tone.Warn
        "MISSING", "DAMAGED", "CRITICAL" -> Tone.Danger
        "INFO" -> Tone.Info
        else -> Tone.Muted
    }
    val colour: Color = when (tone) {
        Tone.Ok -> colors.success
        Tone.Warn -> colors.warning
        Tone.Danger -> colors.danger
        Tone.Info -> colors.info
        else -> colors.textMuted
    }
    Box(modifier.size(10.dp).clip(CircleShape).background(colour))
}

/** The heading block under the tool's tabs. */
@Composable
fun PageHead(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    ZillitPageHeader(
        title = title,
        description = sub,
        actions = actions,
        modifier = modifier.padding(bottom = ZillitTheme.spacing.md),
    )
}

/** One headline figure; [tone] tints it (`Ok`/`Info`/`Warn`/`Danger`), none for a plain count. */
@Composable
fun StatCard(label: String, value: Any?, modifier: Modifier = Modifier, tone: Tone? = null, hint: String? = null, onClick: (() -> Unit)? = null) {
    ZillitStatTile(
        label = label,
        value = value?.toString() ?: "0",
        modifier = modifier,
        sub = hint,
        tone = tone?.takeIf { it != Tone.Muted }?.toStatusTone(),
        onClick = onClick,
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    meta: String? = null,
    flush: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ZillitSectionCard(modifier = modifier.fillMaxWidth(), title = title, meta = meta, padded = !flush, action = actions, content = content)
}

@Composable
fun EmptyState(title: String, hint: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    ZillitEmptyState(title = title, message = hint, modifier = modifier, action = action)
}

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
    modifier: Modifier = Modifier,
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
    Row(
        modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(t("csync_previous"), onClick = { onPage(page - 1) }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, enabled = page > 1)
        ZillitText(t("csync_page_of", "page" to page, "pages" to pages), color = ZillitTheme.colors.textSecondary)
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
 * mark, the asset number and name, "type · colour · size · for whom", then
 * where the piece is and its status. [onClick] makes the whole row open
 * something; [end] adds a control after the status (a Remove in a form's
 * piece list); [noStatus] drops the badge where every row would say the same;
 * [extra] is appended to the detail line (" · match 3" on a replacement).
 */
@Composable
fun CostumeRow(c: Rec, onClick: (() -> Unit)? = null, end: (@Composable RowScope.() -> Unit)? = null, noStatus: Boolean = false, extra: String = "") {
    val detail = listOfNotNull(
        c.str("type").ifBlank { null },
        c.str("color").ifBlank { null },
        c.str("size").ifBlank { null }?.let { "${t("csync_size")} $it" },
        c.rec("character")?.str("name")?.ifBlank { null }?.let { t("csync_for_name", "name" to it) },
    ).joinToString(" · ") + extra
    ListRow(
        onClick = onClick,
        leading = { ZillitText(categoryIcon(c.str("category")), style = ZillitTheme.typography.titleMedium) },
        end = {
            c.str("location").takeIf { it.isNotBlank() }?.let { MutedText(it) }
            if (!noStatus && c.str("status").isNotBlank()) StatusBadge(c.str("status"))
            end?.invoke(this)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            MonoText(c.str("asset_number"))
            RowTitle(c.str("name"), Modifier.weight(1f, fill = false))
        }
        if (detail.isNotBlank()) MutedText(detail)
    }
}

/** The movement history: when · who / what / note. */
@Composable
fun Timeline(entries: List<Rec>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        entries.forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                ZillitText(
                    com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime(entry.long("at")),
                    Modifier.width(150.dp),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
                Column(Modifier.weight(1f)) {
                    ZillitText(entry.str("title"), style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                    entry.str("detail").takeIf { it.isNotBlank() }?.let { MutedText(it, maxLines = 3) }
                    entry.str("by").takeIf { it.isNotBlank() }?.let { MutedText(it) }
                }
            }
        }
    }
}

/** A row of wrapping chips / buttons. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) { content() }
}

internal val ICON_ADD = ZillitIcons.Add

private const val SKELETON_ROWS = 6
