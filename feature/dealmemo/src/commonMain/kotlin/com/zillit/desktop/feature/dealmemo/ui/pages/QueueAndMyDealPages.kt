package com.zillit.desktop.feature.dealmemo.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealCrewLabels
import com.zillit.desktop.feature.dealmemo.domain.DealDates
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealHistoryEntry
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import com.zillit.desktop.feature.dealmemo.ui.BackSquare
import com.zillit.desktop.feature.dealmemo.ui.DealBadgeUnit
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.HistoryState
import com.zillit.desktop.feature.dealmemo.ui.MyDealEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmButton
import com.zillit.desktop.feature.dealmemo.ui.components.DmButtonStyle
import com.zillit.desktop.feature.dealmemo.ui.components.DmCard
import com.zillit.desktop.feature.dealmemo.ui.components.DmIconTile
import com.zillit.desktop.feature.dealmemo.ui.components.DmIcons
import com.zillit.desktop.feature.dealmemo.ui.components.DmPanelHeader
import com.zillit.desktop.feature.dealmemo.ui.components.DmPersonAvatar
import com.zillit.desktop.feature.dealmemo.ui.components.DmRoundIcon
import com.zillit.desktop.feature.dealmemo.ui.components.DmSidePanel
import com.zillit.desktop.feature.dealmemo.ui.components.DmStatusBadge
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.DmUnreadPill
import com.zillit.desktop.feature.dealmemo.ui.components.dm
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.DealPreviewBody
import com.zillit.desktop.feature.dealmemo.ui.preview.DealPreviewActions

// -- Approval Queue -----------------------------------------------------------------------

/** Approval Queue — deals at this approver's level; the only action is to open and sign (`DMApprovalQueuePage.jsx`). */
@Composable
fun ApprovalQueuePage(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val queue = state.queue
    val labels = remember(state.people, state.catalogue) { state.labels }
    when {
        !queue.loaded -> LoadingLine(str(S.dm_loading))
        queue.rows.isEmpty() -> EmptyNote(
            title = str(S.desktop_dm_nothing_waiting_on_you),
            body = str(S.desktop_dm_once_a_deal_memo_is_submitted_for),
        )
        else -> ZillitLazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            items(queue.rows, key = { it.id.ifBlank { it.hashCode().toString() } }) { deal ->
                QueueCard(state, deal, labels, onEvent)
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun QueueCard(state: DealMemoUiState, deal: DealDoc, labels: DealCrewLabels, onEvent: (DealMemoEvent) -> Unit) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(16.dp)
    val open = { onEvent(DealMemoEvent.Navigate(DealMemoRoute.Deal(deal.id, from = DealBadgeUnit.ApprovalQueue))) }
    val name = deal.crewName ?: str(S.desktop_unnamed)
    val person = labels.labels(deal)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                if (hovered) 6.dp else 0.5.dp,
                shape,
                clip = false,
                ambientColor = Color(0x1F0F1115),
                spotColor = Color(0x1F0F1115),
            )
            .clip(shape)
            .background(dm.card)
            .border(1.dp, dm.cardBorder, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = open)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.weight(2f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DmPersonAvatar(name, deal.userId, size = 36.dp)
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DmUnreadPill(state.badges.deal(DealBadgeUnit.ApprovalQueue, deal.id))
                    ZillitText(
                        text = name,
                        style = DmType.sans(16.sp, FontWeight.Bold, (-0.01).em),
                        color = dm.ink,
                        maxLines = 1,
                    )
                    ZillitText(
                        text = deal.reference ?: "—",
                        style = DmType.mono(12.sp, FontWeight.SemiBold),
                        color = dm.ink3,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(4.dp))
                ZillitText(
                    text = "${person.department} · ${person.role}",
                    style = DmType.sans(12.5.sp),
                    color = dm.ink2,
                    maxLines = 1,
                )
            }
        }
        Column(modifier = Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoPair(str(S.txt_submitted), DealDates.short(deal.issuedAt ?: deal.updatedAt))
            deal.dailyRate?.let { rate ->
                MonoPair(
                    str(S.dm_rates_day_rate),
                    str(
                        S.desktop_dm_amount_per_day,
                        "${RateFormat.currencySymbol(deal.contractCurrency ?: "GBP")}${RateFormat.groupAmount(rate)}",
                    ),
                    strong = true,
                )
            }
        }
        Row(
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DmRoundIcon(
                DmIcons.History,
                tooltip = str(S.dm_row_action_history),
                onClick = { onEvent(DealMemoEvent.OpenHistory(deal)) },
                size = 36.dp,
                radius = 8.dp,
                iconSize = 16.dp,
            )
            DmButton(str(S.dm_action_approve_sign), onClick = open, style = DmButtonStyle.Approve)
        }
    }
}

@Composable
private fun MonoPair(label: String, value: String, strong: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZillitText(
            text = label.uppercase(),
            style = DmType.mono(10.5.sp, FontWeight.Bold, 0.1.em),
            color = dm.ink3,
            maxLines = 1,
        )
        ZillitText(
            text = value,
            style = if (strong) DmType.mono(13.sp, FontWeight.Bold) else DmType.mono(12.sp),
            color = if (strong) dm.ink else dm.ink2,
            maxLines = 1,
        )
    }
}

// -- My Deal ----------------------------------------------------------------------------

/** My Deal — the viewer's own deal memo, shown through the shared preview (`DMMyDealPage.jsx`). */
@Composable
fun MyDealPage(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val mine = state.myDeal
    val deal = mine.deal
    when {
        !mine.loaded || mine.loading && deal == null -> LoadingLine(str(S.desktop_dm_loading_your_deal_memo))
        mine.failed -> Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ZillitText(
                text = str(S.desktop_dm_couldnt_load_your_deal_memo),
                style = DmType.sans(14.sp),
                color = dm.ink2,
            )
            Spacer(Modifier.height(12.dp))
            DmButton(
                str(S.retry),
                onClick = { onEvent(MyDealEvent.Retry) },
                style = DmButtonStyle.SmallSecondary,
                icon = ZillitIcons.Reload,
            )
        }
        deal == null -> EmptyNote(
            title = str(S.desktop_dm_no_deal_memo_on_file),
            body = str(S.desktop_dm_you_dont_have_a_deal_memo_for),
        )
        else -> MyDealPreview(state, deal, onEvent)
    }
}

/** The viewer's deal, embedded: the same page as `/deals/:id`, without its header. */
@Composable
private fun MyDealPreview(state: DealMemoUiState, deal: DealDoc, onEvent: (DealMemoEvent) -> Unit) {
    val preview = state.preview?.takeIf { it.embedded && it.dealId == deal.id }
    val rules = remember(preview?.deal, preview?.crewDraft, state.viewer, state.metadata) {
        DealPreviewActions.rulesFor(state)
    }
    if (preview == null || rules == null) {
        LoadingLine(str(S.desktop_dm_loading_your_deal_memo))
        return
    }
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DealPreviewBody(state, preview, rules, onEvent)
    }
}

// -- shared bits ----------------------------------------------------------------------

@Composable
internal fun LoadingLine(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitSpinner(size = 14.dp, color = dm.ink3)
            ZillitText(text = text, style = DmType.sans(12.sp), color = dm.ink3)
        }
    }
}

/** The amber-tile empty state the queue and My Deal share. */
@Composable
internal fun EmptyNote(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DmIconTile(
            DmIcons.FileSearch,
            background = dm.amberSoft,
            ring = dm.amberRing,
            tint = dm.accent,
            size = 56.dp,
            radius = 14.dp,
            iconSize = 24.dp,
        )
        Spacer(Modifier.height(14.dp))
        ZillitText(text = title, style = DmType.sans(15.sp, FontWeight.Bold, (-0.01).em), color = dm.ink)
        Spacer(Modifier.height(4.dp))
        ZillitText(
            text = body,
            style = DmType.sans(13.sp).copy(lineHeight = 19.5.sp),
            color = dm.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 448.dp),
        )
    }
}

// -- history ------------------------------------------------------------------------

/** The deal's audit trail, newest first, as the web's floating history panel. */
@Composable
fun HistoryPanel(history: HistoryState?, state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val shown = remember { mutableStateOf(history) }
    if (history != null) shown.value = history
    DmSidePanel(visible = history != null, onDismiss = { onEvent(DealMemoEvent.CloseHistory) }) {
        val current = shown.value ?: return@DmSidePanel
        DmPanelHeader(
            title = str(S.desktop_dm_deal_memo_history),
            subtitle = current.subtitle,
            onClose = { onEvent(DealMemoEvent.CloseHistory) },
        )
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        ) {
            when {
                current.loading -> Column(
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ZillitSpinner(size = 22.dp, color = dm.brand)
                    Spacer(Modifier.height(8.dp))
                    ZillitText(text = str(S.desktop_loading_history), style = DmType.sans(12.sp), color = dm.ink3)
                }
                current.error != null -> ZillitText(
                    text = current.error,
                    style = DmType.sans(12.sp),
                    color = Color(0xFFDC2626),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                current.entries.isEmpty() -> ZillitText(
                    text = str(S.desktop_dm_no_history_recorded),
                    style = DmType.sans(12.sp),
                    color = dm.ink3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> current.entries.forEach { entry -> HistoryEntryRow(entry, state) }
            }
        }
    }
}

@Composable
private fun HistoryEntryRow(
    entry: DealHistoryEntry,
    state: DealMemoUiState,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.padding(top = 3.dp).size(12.dp).clip(CircleShape).background(dm.card),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dm.brand))
        }
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = entry.action.split('_')
                    .filter { it.isNotEmpty() }
                    .joinToString(" ") { it.replaceFirstChar(Char::uppercase) },
                style = DmType.sans(12.sp, FontWeight.SemiBold),
                color = dm.ink,
            )
            ZillitText(
                text = str(S.dm_history_by_prefix, actor(entry.actionBy, state)),
                style = DmType.sans(11.sp),
                color = dm.ink3,
            )
            ZillitText(
                text = DealDates.longDateTime(entry.actionAt),
                style = DmType.mono(10.sp),
                color = Color(0xFF9CA3AF),
            )
            entry.note?.let { note ->
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(dm.soft)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    ZillitText(text = note, style = DmType.sans(11.sp), color = dm.ink2)
                }
            }
        }
    }
}

/** `System` for the server, the person with their designation when known, else the raw id. */
private fun actor(userId: String?, state: DealMemoUiState): String {
    if (userId.isNullOrBlank() || userId == "system") return str(S.desktop_language_system_short)
    val person = state.people[userId] ?: return userId
    val role = person.designationName?.takeIf { it.isNotBlank() }
        ?.let { DealLabels.formatLabel(it) }
    return if (role != null) "${person.fullName} ($role)" else person.fullName
}

// -- pages still to come --------------------------------------------------------------

/** A page this build does not show yet: says where the user is, and offers the way back. */
@Composable
fun UnbuiltPage(page: DealMemoRoute, onEvent: (DealMemoEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        BackSquare(onClick = {
            onEvent(DealMemoEvent.Navigate(DealMemoRoute.Tab(DealTab.Deals)))
        })
        EmptyNote(title = str(S.dm_nda_opening), body = page.tail)
    }
}
