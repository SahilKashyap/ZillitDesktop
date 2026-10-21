package com.zillit.desktop.feature.addashboard.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.Agency
import com.zillit.desktop.feature.addashboard.domain.ArtisteQuery
import com.zillit.desktop.feature.addashboard.ui.AdDestination
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdUiState
import com.zillit.desktop.feature.addashboard.ui.AgencyEvent
import com.zillit.desktop.feature.addashboard.ui.QueryEvent
import com.zillit.desktop.feature.addashboard.ui.RatesEvent

/**
 * The Agency tab: the casting agencies that supply artistes.
 *
 * Deliberately **not** gated on the AD posting right — it is the Vendors
 * module's rules here: an accountant or the row's creator may edit and delete,
 * and verify is accountant-only. Only **Add Agency** is blocked for a
 * non-posting viewer, because an agency is created from Rates & Config.
 */
@Composable
internal fun AgencyPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    AdPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.agency.search,
                onValueChange = { onEvent(AgencyEvent.Search(it)) },
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            if (state.viewer.canPost) {
                // Lands on Rates & Config, as the web's own Add Agency does —
                // which is also why a non-posting viewer cannot reach it.
                ZillitButton(
                    text = str(S.desktop_ad_add_agency),
                    onClick = {
                        onEvent(AdEvent.Open(AdDestination.Config))
                        onEvent(RatesEvent.StartVendorForm(null))
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        }

        ZillitSectionCard(
            title = str(S.desktop_dm_agency),
            meta = str(S.desktop_ad_n_agencies, state.agency.rows.size),
            padded = false,
        ) {
            val rows = state.agency.rows.filter { agency ->
                val needle = state.agency.search.trim()
                needle.isBlank() ||
                    agency.name.contains(needle, ignoreCase = true) ||
                    agency.email.contains(needle, ignoreCase = true)
            }
            ZillitDataTable(
                rows = rows,
                columns = agencyColumns(state, onEvent),
                key = { it.id },
                onRowClick = { onEvent(AgencyEvent.OpenAgency(it)) },
                emptyTitle = str(S.desktop_ad_no_agencies),
                emptyMessage = str(S.desktop_ad_no_agencies_message),
                loading = state.agency.loading,
                virtualised = false,
            )
        }
    }

    AgencyFormDialog(state, onEvent)
    AgencyDetailDialog(state, onEvent)
    AgencyHistoryDialog(state, onEvent)
    DeleteAgencyDialog(state, onEvent)
}

@Suppress("MagicNumber") // Column weights, which read better as the ratios they are.
private fun agencyColumns(
    state: AdUiState,
    onEvent: (AdEvent) -> Unit,
): List<TableColumn<Agency>> = listOf(
    textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.name },
    textColumn(str(S.contact)) { it.contactPerson },
    textColumn(str(S.email), width = ColumnWidth.Weight(1.4f)) { it.email },
    textColumn(str(S.phone)) { it.phone },
    TableColumn(str(S.status), width = ColumnWidth.Fixed(STATUS_WIDTH)) { agency ->
        ZillitStatusPill(
            label = if (agency.verified) str(S.ah_verified) else str(S.pending),
            tone = if (agency.verified) StatusTone.Done else StatusTone.Pending,
        )
    },
    TableColumn(str(S.dd_actions), width = ColumnWidth.Fixed(AGENCY_ACTION_WIDTH)) { agency ->
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            // The Vendors rules, not this tool's posting right.
            if (agency.editableBy(state.viewer.userId, isAccountant = true)) {
                ZillitIconButton(
                    icon = ZillitIcons.Edit,
                    contentDescription = str(S.edit),
                    onClick = { onEvent(AgencyEvent.StartForm(agency)) },
                )
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.delete),
                    onClick = { onEvent(AgencyEvent.AskDelete(agency)) },
                )
            }
            if (!agency.verified) {
                ZillitIconButton(
                    icon = ZillitIcons.Tick,
                    contentDescription = str(S.ah_verified),
                    onClick = { onEvent(AgencyEvent.Verify(agency.id)) },
                )
            }
            ZillitIconButton(
                icon = ZillitIcons.Clock,
                contentDescription = str(S.history),
                onClick = { onEvent(AgencyEvent.OpenHistory(agency)) },
            )
        }
    },
)

/**
 * Artiste Queries: the production's side of a thread about a voucher.
 *
 * The one page in this tool that ignores read-only, by decision: anyone who
 * can open the dashboard may reply and resolve, because a query is
 * communication rather than an operational action.
 */
@Composable
internal fun QueriesPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        QueryList(state, onEvent)
        ZillitVerticalDivider()
        QueryThread(state, onEvent)
    }
}

@Composable
private fun QueryList(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    Column(modifier = Modifier.width(QUERY_LIST_WIDTH).fillMaxHeight()) {
        Column(
            modifier = Modifier.padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSearchField(
                value = state.queries.search,
                onValueChange = { onEvent(QueryEvent.Search(it)) },
                modifier = Modifier.fillMaxWidth(),
            )
            ZillitChoiceChip(
                label = str(S.desktop_ad_show_resolved),
                selected = state.queries.showResolved,
                onClick = { onEvent(QueryEvent.ShowResolved(!state.queries.showResolved)) },
            )
        }
        ZillitDivider()
        if (state.queries.loading && state.queries.rows.isEmpty()) {
            ZillitSpinner(modifier = Modifier.padding(ZillitTheme.spacing.lg))
            return@Column
        }
        if (state.queries.visible.isEmpty()) {
            ZillitEmptyState(
                title = str(S.desktop_sa_no_queries),
                message = str(S.desktop_ad_no_queries_message),
                icon = ZillitIcons.Chat,
            )
            return@Column
        }
        ZillitScrollColumn(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            state.queries.visible.forEach { query ->
                QueryRow(
                    query = query,
                    unread = state.queryUnread(query.id),
                    selected = query.id == state.queries.openId,
                    onClick = { onEvent(QueryEvent.OpenThread(query.id)) },
                )
                ZillitDivider()
            }
        }
    }
}

@Composable
private fun QueryRow(query: ArtisteQuery, unread: Int, selected: Boolean, onClick: () -> Unit) {
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        title = query.artisteName.ifBlank { str(S.unkone_user) },
        meta = AdDates.formatInstant(query.updated),
        action = {
            // Per query, not per tab: opening a thread reads only that one.
            ZillitBadge(count = unread)
            ZillitStatusPill(
                label = if (query.resolved) str(S.ah_alert_filter_resolved) else str(S.recce_open),
                tone = if (query.resolved) StatusTone.Done else StatusTone.Pending,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = query.title.ifBlank { query.topic },
                style = ZillitTheme.typography.bodyMedium,
            )
            ZillitText(
                text = query.lastMessage,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 2,
            )
            ZillitButton(
                text = str(S.recce_open),
                onClick = onClick,
                variant = if (selected) ButtonVariant.Secondary else ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
private fun QueryThread(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val open = state.queries.open
    if (open == null) {
        ZillitEmptyState(
            title = str(S.desktop_ad_pick_a_query),
            icon = ZillitIcons.Chat,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        AdPage(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(text = open.artisteName, style = ZillitTheme.typography.titleMedium)
                ZillitText(
                    text = open.voucherCode,
                    style = ZillitTheme.typography.numeric,
                    color = ZillitTheme.colors.textMuted,
                )
                if (!open.resolved) {
                    ZillitButton(
                        text = str(S.desktop_sa_mark_resolved),
                        onClick = { onEvent(QueryEvent.Resolve(open.id)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        loading = state.queries.busy,
                    )
                }
            }

            ZillitSectionCard(title = str(S.desktop_ad_thread)) {
                Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    if (state.queries.threadLoading) ZillitSpinner()
                    open.messages.forEach { message ->
                        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                            ZillitText(
                                // "You" is decided by the author id: every
                                // production user shares `who_type`, so it
                                // cannot say which of them wrote this.
                                text = if (message.isMine(state.viewer.userId)) {
                                    str(S.you)
                                } else {
                                    message.authorName.ifBlank {
                                        if (message.fromArtiste) open.artisteName else str(S.production)
                                    }
                                },
                                style = ZillitTheme.typography.labelSmall,
                                color = ZillitTheme.colors.textMuted,
                            )
                            ZillitText(text = message.text, style = ZillitTheme.typography.bodySmall)
                            ZillitText(
                                text = AdDates.formatInstant(message.at),
                                style = ZillitTheme.typography.labelSmall,
                                color = ZillitTheme.colors.textMuted,
                            )
                        }
                        ZillitDivider()
                    }
                }
            }

            state.queries.dayContext?.let { day ->
                ZillitSectionCard(title = str(S.desktop_ad_day_breakdown)) {
                    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        AdDetailRow(
                            label = str(S.date),
                            value = AdDates.formatDayFull(day.shootDate),
                        )
                        AdDetailRow(label = str(S.status), value = day.statusLabel)
                        AdDetailRow(label = str(S.desktop_ad_time_in), value = day.callTime)
                        AdDetailRow(label = str(S.desktop_ad_time_out), value = day.wrapTime)
                        ZillitSectionLabel(text = str(S.desktop_po_lines_column))
                        day.lines.forEach { line ->
                            AdDetailRow(
                                label = line.label,
                                value = state.money(line.amount, day.currency),
                            )
                        }
                        // The engine's gross, deliberately: this pane lists the
                        // rate and allowance lines only, so it is not the
                        // voucher total.
                        AdDetailRow(
                            label = str(S.desktop_ad_engine_gross),
                            value = state.money(day.total, day.currency),
                        )
                    }
                }
            }
        }
        ZillitDivider()
        if (!open.resolved) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitTextField(
                    value = state.queries.reply,
                    onValueChange = { onEvent(QueryEvent.Reply(it)) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    placeholder = str(S.desktop_ad_write_a_reply),
                    onImeAction = { onEvent(QueryEvent.SendReply) },
                )
                ZillitButton(
                    text = str(S.send),
                    onClick = { onEvent(QueryEvent.SendReply) },
                    enabled = state.queries.reply.isNotBlank(),
                    loading = state.queries.busy,
                    leadingIcon = ZillitIcons.Send,
                )
            }
        }
    }
}

private val SEARCH_WIDTH = 240.dp
private val STATUS_WIDTH = 120.dp
private val AGENCY_ACTION_WIDTH = 160.dp
private val QUERY_LIST_WIDTH = 340.dp
