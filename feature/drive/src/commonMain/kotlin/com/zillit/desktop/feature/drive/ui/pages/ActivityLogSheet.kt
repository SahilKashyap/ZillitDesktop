package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.ActivityCategory
import com.zillit.desktop.feature.drive.domain.DriveActivity
import com.zillit.desktop.feature.drive.domain.relativeTime
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.LocalDriveNow
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The Activity Log drawer — `ActivityLogDrawer.jsx`: filter chips, then the
 * trail grouped by day, each entry a card with the action's glyph, the item,
 * the actor and when. Scrolling to the end asks for the next page.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition") // One drawer: filters, groups, paging.
internal fun ActivityLogSheet(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val log = state.activityLog
    DriveSideSheet(
        title = str(S.desktop_drive_activity_log),
        subtitle = if (log.items.isNotEmpty() && log.total > 0) "${log.items.size} / ${log.total}" else null,
        visible = log.open,
        onDismiss = { onEvent(DriveEvent.CloseActivityLog) },
        icon = ZillitIcons.Clock,
        scrollable = false,
        headerTrailing = {
            ZillitIconButton(
                icon = ZillitIcons.Reload,
                contentDescription = str(S.refresh_text),
                onClick = { onEvent(DriveEvent.RefreshActivity) },
            )
        },
    ) {
        FilterBar(state, onEvent)
        ZillitDivider()
        val rows = log.filtered
        val now = LocalDriveNow.current()
        val grouped = remember(rows, now) { rows.groupBy { dayBucket(it.at ?: 0L, now) } }
        val listState = rememberLazyListState()
        val nearEnd by remember {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                last >= listState.layoutInfo.totalItemsCount - LOAD_MORE_AHEAD
            }
        }
        LaunchedEffect(nearEnd, log.hasMore, log.loadingMore) {
            if (nearEnd && log.hasMore && !log.loadingMore && !log.loading) onEvent(DriveEvent.LoadMoreActivity)
        }
        Box(Modifier.fillMaxSize()) {
            when {
                log.loading && log.items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
                rows.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(ZillitTheme.spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        text = if (log.items.isEmpty()) {
                            str(S.drive_no_activity_yet)
                        } else {
                            str(S.desktop_drive_nothing_in_filter)
                        },
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textMuted,
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    grouped.forEach { (bucket, entries) ->
                        item(key = "bucket-${bucket.key}") { BucketHeader(bucket.label) }
                        items(entries, key = { it.id }) { entry -> ActivityCard(entry) }
                    }
                    item(key = "tail") {
                        Box(
                            Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                log.loadingMore -> ZillitSpinner()
                                log.hasMore -> ZillitText(
                                    text = str(S.desktop_drive_scroll_for_more),
                                    style = ZillitTheme.typography.labelSmall,
                                    color = ZillitTheme.colors.textMuted,
                                )

                                else -> ZillitText(
                                    text = str(S.desktop_drive_no_more_activity),
                                    style = ZillitTheme.typography.labelSmall,
                                    color = ZillitTheme.colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
            ZillitScrollRail(listState, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun FilterBar(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val log = state.activityLog
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        FILTERS.forEach { (category, labelKey) ->
            val count = log.count(category)
            val label = str(labelKey)
            ZillitChoiceChip(
                label = if (count > 0) "$label · $count" else label,
                selected = log.filter == category,
                onClick = { onEvent(DriveEvent.FilterActivity(category)) },
            )
        }
    }
}

@Composable
private fun BucketHeader(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm, bottom = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        Box(Modifier.weight(1f)) { ZillitDivider() }
    }
}

@Composable
@Suppress("LongMethod") // One card; its glyph, its two lines and its meta row.
private fun ActivityCard(entry: DriveActivity) {
    val colors = ZillitTheme.colors
    val (icon, accent) = entry.glyph()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(GLYPH_DISC)
                .clip(ZillitTheme.shapes.medium)
                .background(accent.copy(alpha = GLYPH_WASH)),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = icon, tint = accent, size = ZillitTheme.spacing.lg)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ZillitText(text = entry.label, style = ZillitTheme.typography.label, modifier = Modifier.weight(1f))
                if (entry.itemType.isNotBlank()) {
                    ZillitStatusPill(
                        label = entry.itemType,
                        tone = when (entry.itemType) {
                            "folder" -> StatusTone.Pending
                            "file" -> StatusTone.Progress
                            else -> StatusTone.Neutral
                        },
                    )
                }
            }
            ZillitTooltip(text = entry.itemName.ifBlank { entry.detail }) {
                ZillitText(
                    text = entry.itemName.ifBlank { if (entry.detail.isNotBlank()) entry.detail else "—" },
                    style = ZillitTheme.typography.bodySmall,
                    color = if (entry.itemName.isBlank()) colors.textMuted else colors.textPrimary,
                    maxLines = 1,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitAvatar(name = entry.displayName, userId = entry.userId, size = META_AVATAR)
                ZillitText(
                    text = entry.displayName,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
                ZillitText(text = "•", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                ZillitTooltip(text = EpochDate.dateTime(entry.at)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitIcon(icon = ZillitIcons.Clock, tint = colors.textMuted, size = ZillitTheme.spacing.md)
                        val now = LocalDriveNow.current()
                        ZillitText(
                            text = entry.at?.let { stampLabel(it, now) } ?: "—",
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/** The action's glyph and accent — the web's `ACTION_META`. */
@Composable
private fun DriveActivity.glyph(): Pair<ImageVector, Color> {
    val c = ZillitTheme.colors
    return when {
        action.endsWith("created") && category == ActivityCategory.Files -> ZillitIcons.Upload to c.info
        action.endsWith("created") -> ZillitIcons.FolderPlus to c.success
        action.endsWith("updated") -> ZillitIcons.Edit to c.accent
        action.endsWith("deleted") -> ZillitIcons.Trash to c.danger
        action.endsWith("moved") -> ZillitIcons.ArrowRight to c.violet
        action.endsWith("restored") -> ZillitIcons.Reload to c.success
        category == ActivityCategory.Access -> ZillitIcons.Lock to c.gold
        else -> ZillitIcons.Grid to c.textSecondary
    }
}

private data class DayBucket(val key: String, val label: String)

/** Today / Yesterday / This week / This month / a month name — the web's `dayBucket`. */
private fun dayBucket(millis: Long, now: Long): DayBucket {
    if (millis <= 0 || now <= 0) return DayBucket("undated", str(S.desktop_drive_bucket_earlier))
    val age = now - millis
    return when {
        EpochDate.date(millis) == EpochDate.date(now) -> DayBucket("today", str(S.today))
        EpochDate.date(millis) == EpochDate.date(now - DAY_MS) -> DayBucket("yesterday", str(S.yesterday))
        age < WEEK_MS -> DayBucket("this_week", str(S.desktop_drive_bucket_this_week))
        age < 2 * WEEK_MS -> DayBucket("last_week", str(S.desktop_drive_bucket_last_week))
        age < MONTH_MS -> DayBucket("this_month", str(S.desktop_drive_bucket_this_month))
        // `04 Aug, 2026` → "Aug 2026": one bucket per older month.
        else -> EpochDate.isoDate(millis).take(MONTH_KEY).let { key ->
            DayBucket(key, EpochDate.date(millis).substringAfter(' ').replace(",", ""))
        }
    }
}

private val FILTERS = listOf<Pair<ActivityCategory?, String>>(
    null to S.all,
    ActivityCategory.Files to S.drive_kind_files,
    ActivityCategory.Folders to S.folders,
)
private val GLYPH_DISC = 32.dp
private val META_AVATAR = 20.dp
private const val GLYPH_WASH = 0.14f
private const val LOAD_MORE_AHEAD = 4
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val WEEK_MS = 7 * DAY_MS
private const val MONTH_MS = 30 * DAY_MS
private const val MONTH_KEY = 7
