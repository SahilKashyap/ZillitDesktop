package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.documentdistribution.domain.DeliveryStatus
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.RecipientKind
import com.zillit.desktop.feature.documentdistribution.domain.RecipientStatus
import com.zillit.desktop.feature.documentdistribution.domain.SendStatus
import com.zillit.desktop.feature.documentdistribution.domain.formatBytes
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState

/**
 * What has been sent, and who has opened it — the web's `HistoryDrawer`
 * laid out as a page: the searchable, filterable card list on the left
 * and the open send's analytics and per-recipient delivery on the right.
 */
@Composable
fun HistoryPage(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    FixedPage {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            Column(
                Modifier.weight(1f).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitIcon(icon = ZillitIcons.Clock, tint = c.accent)
                    ZillitText(text = "Distribution History", style = ZillitTheme.typography.titleMedium)
                    ZillitBadge(count = state.historyTotal, background = c.accent, cap = null)
                }
                ZillitSearchField(
                    value = state.historySearch,
                    onValueChange = { onEvent(DocDistEvent.SearchHistory(it)) },
                    placeholder = "Search by subject or recipient name / email",
                )
                if (state.historySenders.isNotEmpty()) SentByMenu(state, onEvent)
                HistoryList(state, onEvent, Modifier.weight(1f))
            }
            Box(Modifier.weight(DETAIL_WEIGHT).fillMaxSize()) {
                val detail = state.historyDetail
                when {
                    detail == null -> ZillitEmptyState(
                        title = "Select a send",
                        message = "Click a row to see its delivery, who opened it, and the files it carried.",
                        icon = ZillitIcons.Mail,
                    )
                    detail.distribution == null && detail.loading -> CentredSpinner()
                    detail.distribution == null -> ZillitEmptyState(
                        title = "Couldn’t load this send",
                        icon = ZillitIcons.Warning,
                    )
                    else -> DistributionDetail(detail.distribution, detail.loading, state, onEvent)
                }
            }
        }
    }
    SaveAsListDialog(state, onEvent)
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // One screen section, one branch per state.
@Composable
private fun HistoryList(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit, modifier: Modifier) {
    val c = ZillitTheme.colors
    val listState = rememberLazyListState()
    val filtered = state.historySearch.isNotBlank() || state.historySenderIds.isNotEmpty()
    LaunchedEffect(listState, state.historyHasMore) {
        if (!state.historyHasMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.index?.let { it >= info.totalItemsCount - 3 } ?: false
        }.collect { if (it) onEvent(DocDistEvent.LoadMoreHistory) }
    }
    Box(
        modifier.fillMaxWidth().clip(ZillitTheme.shapes.large).background(c.surface).border(
            0.5.dp,
            c.border,
            ZillitTheme.shapes.large,
        ),
    ) {
        when {
            state.loading && state.history.isEmpty() -> CentredSpinner()
            state.history.isEmpty() && !filtered -> ZillitEmptyState(
                title = "No distributions yet",
                message = "Emails you send will appear here with per-recipient delivery and open status.",
                icon = ZillitIcons.Send,
            )
            state.history.isEmpty() -> ZillitEmptyState(
                title = if (state.historySenderIds.isNotEmpty()) {
                    "No distributions match your filters."
                } else {
                    "No distributions match your search."
                },
                icon = ZillitIcons.Search,
            )
            else -> ZillitLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                items(state.history, key = { it.id }) { row -> HistoryCard(
                    row,
                    row.id == state.expandedDistributionId,
                    onEvent,
                ) }
                if (state.historyHasMore) {
                    item("more") {
                        Box(
                            Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.historyLoadingMore) ZillitSpinner(size = 16.dp)
                            else ZillitText(
                                text = "Loading more…",
                                style = ZillitTheme.typography.bodySmall,
                                color = c.textMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun HistoryCard(row: Distribution, active: Boolean, onEvent: (DocDistEvent) -> Unit) {
    val c = ZillitTheme.colors
    val to = row.recipientsOf(RecipientKind.To).map { it.recipient.email }
    val preview = if (to.size <= 2) to.joinToString(", ") else "${to.take(2).joinToString(", ")} +${to.size - 2} more"
    HoverRow(selected = active, onClick = { onEvent(DocDistEvent.ExpandDistribution(if (active) null else row.id)) }) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = row.subject,
                    style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (row.status != SendStatus.Unknown) ZillitStatusPill(
                    label = row.status.label,
                    tone = row.status.tone(),
                )
            }
            ZillitText(
                text = "To: $preview",
                style = ZillitTheme.typography.bodySmall,
                color = c.textSecondary,
                maxLines = 1,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitStatusPill(label = "${row.deliveredCount} delivered", tone = StatusTone.Progress)
                if (row.openedCount > 0) ZillitStatusPill(label = "${row.openedCount} opened", tone = StatusTone.Done)
                if (row.failedCount > 0) ZillitStatusPill(
                    label = "${row.failedCount} failed",
                    tone = StatusTone.Rejected,
                )
                ZillitText(
                    text = "${row.recipients.size} recipient" + if (row.recipients.size == 1) "" else "s",
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = EpochDate.dateTime(row.sentAt).ifBlank { "—" },
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textMuted,
                    modifier = Modifier.weight(1f),
                )
                if (row.sentByName.isNotBlank()) ZillitText(
                    text = row.sentByName,
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
        }
        ZillitIcon(icon = ZillitIcons.ChevronRight, tint = c.textMuted, size = 14.dp)
    }
}

// -- detail ------------------------------------------------------------------------

@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun DistributionDetail(
    dist: Distribution,
    refreshing: Boolean,
    state: DocDistUiState,
    onEvent: (DocDistEvent) -> Unit,
) {
    val c = ZillitTheme.colors
    val canPost = state.viewer.canPost
    Column(
        Modifier.fillMaxSize().clip(ZillitTheme.shapes.large).background(c.surface).border(
            0.5.dp,
            c.border,
            ZillitTheme.shapes.large,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = ZillitTheme.spacing.lg,
                vertical = ZillitTheme.spacing.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            Column(Modifier.weight(1f)) {
                ZillitText(text = dist.subject, style = ZillitTheme.typography.titleMedium, maxLines = 2)
                ZillitText(
                    text = EpochDate.dateTime(dist.sentAt).ifBlank { "—" } +
                        dist.sentByName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                if (dist.listsUsed.isNotEmpty()) {
                    ZillitText(
                        text = "Distribution list" + (if (dist.listsUsed.size > 1) "s" else "") + ": " +
                            dist.listsUsed.joinToString(", ") { it.name },
                        style = ZillitTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }
            }
            ZillitTooltip(if (canPost) "Duplicate" else "No posting rights") {
                ZillitIconButton(
                    ZillitIcons.Send,
                    "Duplicate",
                    { onEvent(DocDistEvent.DuplicateDistribution(dist.id)) },
                    enabled = canPost,
                )
            }
            ZillitTooltip("Save recipients as a distribution list") {
                ZillitIconButton(
                    ZillitIcons.UserPlus,
                    "Save recipients as a list",
                    { onEvent(DocDistEvent.OpenSaveRecipientsAsList(true)) },
                    enabled = canPost,
                )
            }
            ZillitTooltip("Export recipients as CSV") {
                ZillitIconButton(
                    ZillitIcons.Download,
                    "Export recipients as CSV",
                    { onEvent(DocDistEvent.ExportRecipientsCsv) },
                )
            }
            ZillitTooltip("Refresh") {
                if (refreshing) ZillitSpinner(size = 16.dp) else ZillitIconButton(
                    ZillitIcons.Reload,
                    "Refresh",
                    { onEvent(DocDistEvent.RefreshDistribution) },
                )
            }
            ZillitIconButton(ZillitIcons.Close, "Close", { onEvent(DocDistEvent.ExpandDistribution(null)) })
        }
        ZillitDivider()
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Analytics(dist)
            if (dist.attachments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    FieldLabel("${dist.attachments.size} attachment" + if (dist.attachments.size == 1) "" else "s")
                    dist.attachments.forEach { a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            FileGlyph(a.name, a.contentType, size = 26.dp)
                            ZillitText(text = a.name, maxLines = 1, modifier = Modifier.weight(1f))
                            if (a.watermarked) ZillitStatusPill(label = "Watermarked", tone = StatusTone.Progress)
                            if (a.sizeBytes > 0) ZillitText(
                                text = formatBytes(a.sizeBytes),
                                style = ZillitTheme.typography.bodySmall,
                                color = c.textMuted,
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldLabel("Recipients · ${dist.recipients.size}")
                val listNames = dist.listNameByEmail
                dist.recipients.forEach { row -> RecipientRow(row, listNames[row.recipient.email.lowercase()]) }
            }
            dist.error?.let { ZillitNotice(text = it, tone = StatusTone.Rejected, icon = ZillitIcons.Warning) }
        }
    }
}

/** The donut and its legend — "Delivered" counts opened copies too. */
@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun Analytics(dist: Distribution) {
    val c = ZillitTheme.colors
    val tally = dist.tally()
    val segments = listOf(
        RecipientStatus.Opened to c.success,
        RecipientStatus.Accepted to c.info,
        RecipientStatus.Pending to c.borderStrong,
        RecipientStatus.Rejected to c.danger,
        RecipientStatus.Bounced to c.warning,
        RecipientStatus.Failed to c.danger,
    ).filter { (tally[it.first] ?: 0) > 0 }
    val total = segments.sumOf { tally[it.first] ?: 0 }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
    ) {
        Box(Modifier.size(DONUT_SIZE.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = DONUT_STROKE)
                val inset = DONUT_STROKE / 2
                val arcSize = Size(size.width - DONUT_STROKE, size.height - DONUT_STROKE)
                if (total == 0) {
                    drawArc(
                        Color(0xFFE2E8F0),
                        0f,
                        360f,
                        false,
                        androidx.compose.ui.geometry.Offset(inset, inset),
                        arcSize,
                        style = stroke,
                    )
                } else {
                    var start = -90f
                    segments.forEach { (status, color) ->
                        val sweep = 360f * (tally[status] ?: 0) / total
                        drawArc(
                            color,
                            start,
                            sweep,
                            false,
                            androidx.compose.ui.geometry.Offset(inset, inset),
                            arcSize,
                            style = stroke,
                        )
                        start += sweep
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ZillitText(text = "$total", style = ZillitTheme.typography.titleLarge)
                ZillitText(text = "recipients", style = ZillitTheme.typography.labelSmall, color = c.textMuted)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            RecipientStatus.entries.forEach { status ->
                // Opening implies delivery, so "Delivered" counts opened copies too.
                val count = if (status == RecipientStatus.Accepted) {
                    (tally[RecipientStatus.Accepted] ?: 0) + (tally[RecipientStatus.Opened] ?: 0)
                } else {
                    tally[status] ?: 0
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(status.tone().let { StatusToneColor(it) }))
                    ZillitText(
                        text = status.label,
                        style = ZillitTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(
                        text = "$count",
                        style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusToneColor(tone: StatusTone): Color {
    val c = ZillitTheme.colors
    return when (tone) {
        StatusTone.Done -> c.success
        StatusTone.Progress -> c.info
        StatusTone.Rejected -> c.danger
        StatusTone.Pending -> c.warning
        else -> c.borderStrong
    }
}

@Composable
private fun RecipientRow(row: DeliveryStatus, listName: String?) {
    val c = ZillitTheme.colors
    HoverRow(padding = ZillitTheme.spacing.sm) {
        ZillitAvatar(name = row.recipient.name.ifBlank { row.recipient.email }, size = 30.dp)
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = row.recipient.name.ifBlank { row.recipient.email },
                    style = ZillitTheme.typography.label,
                    maxLines = 1,
                )
                if (row.kind != RecipientKind.To) ZillitStatusPill(label = row.kind.label, tone = StatusTone.Neutral)
                if (listName != null) ZillitStatusPill(label = listName, tone = StatusTone.Pending)
            }
            if (row.recipient.name.isNotBlank()) ZillitText(
                text = row.recipient.email,
                style = ZillitTheme.typography.bodySmall,
                color = c.textMuted,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            ZillitStatusPill(label = row.status.label, tone = row.status.tone(), dot = true)
            row.openedAt?.let {
                ZillitText(
                    text = "Opened ${EpochDate.dateTime(it)}" + if (row.openCount > 1) " · ${row.openCount}×" else "",
                    style = ZillitTheme.typography.labelSmall,
                    color = c.textMuted,
                )
            }
        }
    }
}

@Composable
private fun SaveAsListDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val detail = state.historyDetail
    val name = detail?.saveListName
    val count = detail?.distribution?.uniqueRecipients?.size ?: 0
    ZillitDialogShell(
        title = "Create distribution list from recipients",
        visible = name != null,
        onDismiss = { onEvent(DocDistEvent.OpenSaveRecipientsAsList(false)) },
        icon = ZillitIcons.UserPlus,
        width = 480.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.OpenSaveRecipientsAsList(false)) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Create list",
                onClick = { onEvent(DocDistEvent.ConfirmSaveRecipientsAsList) },
                loading = detail?.savingList == true,
                enabled = !name.isNullOrBlank(),
            )
        },
    ) {
        ZillitText(
            text = "$count unique recipient" + (if (count == 1) "" else "s") +
                " from the To / Cc / Bcc of this email will be saved as a new list.",
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitTextField(
            value = name.orEmpty(),
            onValueChange = { onEvent(DocDistEvent.EditSaveListName(it)) },
            placeholder = "List name",
            onImeAction = { onEvent(DocDistEvent.ConfirmSaveRecipientsAsList) },
        )
    }
}

/**
 * The "Sent by" filter (ZL-21138): a button that says All or how many are
 * ticked, opening a checkbox list of every sender; a search box appears once
 * the list is long enough to need one. Multi-select, OR semantics.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // One screen section, one branch per state.
@Composable
private fun SentByMenu(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val picked = state.historySenderIds.size
    val label = when (picked) {
        0 -> "Sent by: All"
        1 -> {
            val only = state.historySenderIds.first()
            "Sent by: " + (state.historySenders.firstOrNull { it.id == only }?.name?.ifBlank { null } ?: "1 person")
        }
        else -> "Sent by: $picked people"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box {
            ZillitButton(
                text = label,
                onClick = { onEvent(DocDistEvent.HistorySenderMenu(!state.historySenderMenuOpen)) },
                variant = if (picked > 0) ButtonVariant.Primary else ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.User,
                trailingIcon = ZillitIcons.ChevronDown,
            )
            DropdownMenu(
                expanded = state.historySenderMenuOpen,
                onDismissRequest = { onEvent(DocDistEvent.HistorySenderMenu(false)) },
            ) {
                Column(
                    Modifier
                        .width(SENDER_MENU_WIDTH)
                        .heightIn(max = SENDER_MENU_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .padding(ZillitTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    if (state.historySenders.size > SENDER_SEARCH_THRESHOLD) {
                        ZillitSearchField(
                            value = state.historySenderQuery,
                            onValueChange = { onEvent(DocDistEvent.SearchHistorySenders(it)) },
                            placeholder = "Search people",
                        )
                    }
                    ZillitCheckbox(
                        checked = picked == 0,
                        onCheckedChange = { onEvent(DocDistEvent.ClearHistorySenders) },
                        label = "All · everyone who has sent",
                    )
                    ZillitDivider()
                    val query = state.historySenderQuery.trim()
                    val shown = state.historySenders.filter { query.isBlank() || it.name.contains(
                        query,
                        ignoreCase = true,
                    ) }
                    shown.forEach { sender ->
                        HoverRow(
                            onClick = { onEvent(DocDistEvent.ToggleHistorySender(sender.id)) },
                            padding = ZillitTheme.spacing.sm,
                        ) {
                            // "All" means everyone — every person shows checked under it.
                            ZillitCheckbox(
                                checked = picked == 0 || sender.id in state.historySenderIds,
                                onCheckedChange = { onEvent(DocDistEvent.ToggleHistorySender(sender.id)) },
                            )
                            ZillitAvatar(name = sender.name.ifBlank { sender.id }, userId = sender.id, size = 24.dp)
                            Column {
                                ZillitText(
                                    text = sender.name.ifBlank { sender.id },
                                    style = ZillitTheme.typography.label,
                                    maxLines = 1,
                                )
                                if (sender.designation.isNotBlank()) ZillitText(
                                    text = sender.designation.localised(),
                                    style = ZillitTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (shown.isEmpty()) ZillitText(
                        text = if (query.isBlank()) "No senders found" else "No one matches that search",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitText(
                            text = if (picked > 0) "$picked selected" else "Showing all",
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textMuted,
                            modifier = Modifier.weight(1f),
                        )
                        if (picked > 0) ZillitButton(
                            text = "Clear all",
                            onClick = { onEvent(DocDistEvent.ClearHistorySenders) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                        ZillitButton(
                            text = "Done",
                            onClick = { onEvent(DocDistEvent.HistorySenderMenu(false)) },
                            size = ButtonSize.Small,
                        )
                    }
                }
            }
        }
        if (picked > 0) ZillitButton(
            text = "Clear",
            onClick = { onEvent(DocDistEvent.ClearHistorySenders) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
    }
}

@Composable
private fun CentredSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
}

private const val DETAIL_WEIGHT = 1.15f
private const val DONUT_SIZE = 120
private const val DONUT_STROKE = 18f
private val SENDER_MENU_WIDTH = 320.dp
private val SENDER_MENU_HEIGHT = 380.dp
private const val SENDER_SEARCH_THRESHOLD = 6
