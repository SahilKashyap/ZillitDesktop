package com.zillit.desktop.feature.dealmemo.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealCrewLabels
import com.zillit.desktop.feature.dealmemo.domain.DealDates
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealLabels
import com.zillit.desktop.feature.dealmemo.domain.NoticeGroup
import com.zillit.desktop.feature.dealmemo.domain.NoticeGroupKind
import com.zillit.desktop.feature.dealmemo.domain.NoticeRules
import com.zillit.desktop.feature.dealmemo.ui.DeactivateDraft
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.NoticesEvent
import com.zillit.desktop.feature.dealmemo.ui.SendNoticeDraft
import com.zillit.desktop.feature.dealmemo.ui.components.DmButton
import com.zillit.desktop.feature.dealmemo.ui.components.DmButtonStyle
import com.zillit.desktop.feature.dealmemo.ui.components.DmCard
import com.zillit.desktop.feature.dealmemo.ui.components.DmCell
import com.zillit.desktop.feature.dealmemo.ui.components.DmColumn
import com.zillit.desktop.feature.dealmemo.ui.components.DmEyebrow
import com.zillit.desktop.feature.dealmemo.ui.components.DmHoverRow
import com.zillit.desktop.feature.dealmemo.ui.components.DmModal
import com.zillit.desktop.feature.dealmemo.ui.components.DmPersonAvatar
import com.zillit.desktop.feature.dealmemo.ui.components.DmSearchPill
import com.zillit.desktop.feature.dealmemo.ui.components.DmStatusBadge
import com.zillit.desktop.feature.dealmemo.ui.components.DmTableHeader
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.dm

/** Notices — end-of-contract notices and the tool's only Deactivate (`DMNoticesPage.jsx`). */
@Composable
fun NoticesPage(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val notices = state.notices
    val labels = remember(state.people, state.catalogue) { state.labels }
    val groups = remember(notices.rows, notices.search, labels) {
        NoticeRules.groups(notices.rows, notices.search, labels)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        InfoBanner()
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DmSearchPill(
                value = notices.search,
                onValueChange = { onEvent(NoticesEvent.Search(it)) },
                placeholder = str(S.desktop_dm_search_notices_by_crew_reference_department),
                modifier = Modifier.weight(1f),
            )
            DmButton(
                text = str(S.dm_notice_template_title),
                onClick = { onEvent(DealMemoEvent.Navigate(DealMemoRoute.NoticeTemplate)) },
                style = DmButtonStyle.Ghost,
                icon = ZillitIcons.Edit,
            )
        }
        Spacer(Modifier.height(12.dp))
        when {
            !notices.loaded -> DmCard(Modifier.fillMaxWidth()) {
                TableMessage(loading = true, text = str(S.dm_loading))
            }
            groups.isEmpty() -> DmCard(Modifier.fillMaxWidth()) {
                val query = notices.search.trim()
                TableMessage(
                    loading = false,
                    text = if (query.isNotEmpty()) str(S.desktop_dm_no_notices_match_query, query) else str(
                        S.dm_notices_empty,
                    ),
                )
            }
            else -> NoticesTable(state, groups, labels, onEvent, Modifier.weight(1f))
        }
    }
}

/** Notices' dialogs, hosted over the whole window by the screen. */
@Composable
fun NoticesDialogs(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val notices = state.notices
    val labels = remember(state.people, state.catalogue) { state.labels }
    val groups = remember(notices.rows, notices.search, labels) {
        NoticeRules.groups(notices.rows, notices.search, labels)
    }
    SendNoticeModal(state, labels, onEvent)
    SendAllModal(state, groups, onEvent)
    DeactivateModal(notices.deactivate, onEvent)
}

@Composable
private fun InfoBanner() {
    val dark = ZillitTheme.colors.isDark
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dark) Color(0xFF3B82F6).copy(alpha = 0.10f) else Color(0xFFEEF4FF))
            .border(1.dp, if (dark) Color(0xFF3B82F6).copy(alpha = 0.30f) else Color(0xFFCDDCF7), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitIcon(
            ZillitIcons.Info,
            size = 14.dp,
            tint = if (dark) Color(0xFF93C5FD) else Color(0xFF2F5FBF),
            modifier = Modifier.padding(top = 2.dp),
        )
        ZillitText(
            text = str(S.desktop_dm_all_crew_deal_memos_will_show_up),
            style = DmType.sans(12.5.sp).copy(lineHeight = 19.sp),
            color = if (dark) Color(0xFFBFDBFE) else Color(0xFF284B8F),
        )
    }
}

private val COLUMNS get() = listOf(
    DmColumn(str(S.desktop_reference), width = 150.dp),
    DmColumn(str(S.crew_member), weight = 1.4f),
    DmColumn(str(S.desktop_dm_department_designation), weight = 1.3f),
    DmColumn(str(S.dm_label_status), width = 150.dp),
    DmColumn(str(S.dm_ph_contract_end_date), width = 150.dp),
    DmColumn(str(S.dm_ds_card_notice), width = 150.dp),
    DmColumn(str(S.desktop_dm_notice), weight = 1.2f, alignEnd = true),
)

@Composable
private fun NoticesTable(
    state: DealMemoUiState,
    groups: List<NoticeGroup>,
    labels: DealCrewLabels,
    onEvent: (DealMemoEvent) -> Unit,
    modifier: Modifier,
) {
    DmCard(modifier = modifier.fillMaxWidth()) {
        DmTableHeader(COLUMNS)
        ZillitLazyColumn(modifier = Modifier.fillMaxWidth()) {
            groups.forEach { group ->
                item(key = "group-${group.kind}") { GroupHeader(group, onEvent) }
                items(group.deals, key = { "${group.kind}-${it.id}" }) { deal ->
                    NoticeRow(state, group.kind, deal, labels, onEvent)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(dm.cardBorder))
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: NoticeGroup, onEvent: (DealMemoEvent) -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(dm.cardBorder))
    Row(
        modifier = Modifier.fillMaxWidth().background(dm.groupHeader).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitText(
            text = group.kind.title.uppercase(),
            style = DmType.sans(11.5.sp, FontWeight.Bold, 0.08.em),
            color = dm.ink2,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 20.dp)
                .height(20.dp)
                .clip(CircleShape)
                .background(if (ZillitTheme.colors.isDark) Color.White.copy(alpha = 0.06f) else Color(0xFFF0EFEC))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = group.deals.size.toString(),
                style = DmType.mono(10.5.sp, FontWeight.Bold),
                color = dm.ink3,
            )
        }
        Spacer(Modifier.weight(1f))
        if (group.kind != NoticeGroupKind.Deactivated) {
            val unsent = group.unsent.size
            DmButton(
                text = if (unsent > 0) str(S.desktop_dm_send_all_count, unsent) else str(S.desktop_dm_send_all),
                onClick = { onEvent(NoticesEvent.OpenSendAll(group.kind)) },
                style = DmButtonStyle.SendAll,
                icon = ZillitIcons.Send,
                enabled = unsent > 0,
                tooltip = if (unsent > 0) {
                    str(S.desktop_dm_send_notice_to_all_unsent, unsent, group.kind.title)
                } else {
                    str(S.desktop_dm_all_notices_already_sent)
                },
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(dm.cardBorder))
}

@Composable
private fun NoticeRow(
    state: DealMemoUiState,
    kind: NoticeGroupKind,
    deal: DealDoc,
    labels: DealCrewLabels,
    onEvent: (DealMemoEvent) -> Unit,
) {
    val person = labels.labels(deal)
    DmHoverRow(onClick = null, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp)) {
        DmCell(COLUMNS[0]) {
            ZillitText(
                text = deal.reference ?: "—",
                style = DmType.mono(12.5.sp, FontWeight.SemiBold),
                color = dm.ink2,
                maxLines = 1,
            )
        }
        DmCell(COLUMNS[1]) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val name = deal.crewName ?: "—"
                DmPersonAvatar(name, deal.userId, size = 40.dp, initialsSize = 13f)
                ZillitText(text = name, style = DmType.sans(13.sp, FontWeight.SemiBold), color = dm.ink, maxLines = 1)
            }
        }
        DmCell(COLUMNS[2]) {
            Column {
                ZillitText(
                    text = person.department,
                    style = DmType.sans(12.5.sp, FontWeight.Medium),
                    color = dm.ink,
                    maxLines = 1,
                )
                if (person.role != DealCrewLabels.DASH) {
                    ZillitText(text = person.role, style = DmType.sans(11.sp), color = dm.ink3, maxLines = 1)
                }
            }
        }
        DmCell(COLUMNS[3]) { DmStatusBadge(deal.status) }
        DmCell(COLUMNS[4]) {
            ZillitText(
                text = DealDates.short(deal.endDate),
                style = DmType.mono(12.5.sp, FontWeight.SemiBold),
                color = dm.ink,
                maxLines = 1,
            )
        }
        DmCell(COLUMNS[5]) {
            ZillitText(
                text = NoticeRules.noticeLabel(deal.noticePeriod),
                style = DmType.sans(12.5.sp),
                color = dm.ink2,
                maxLines = 1,
            )
        }
        DmCell(COLUMNS[6]) { NoticeCell(state, kind, deal, onEvent) }
    }
}

/** Deactivate or its stamp for offboarded crew; then the sent stamp, or Send. */
@Composable
private fun NoticeCell(state: DealMemoUiState, kind: NoticeGroupKind, deal: DealDoc, onEvent: (DealMemoEvent) -> Unit) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (kind == NoticeGroupKind.Deactivated) {
            when {
                NoticeRules.canDeactivate(deal, state.notices.deactivatedHere) ->
                    DmButton(
                        str(S.dm_notices_deactivate),
                        onClick = { onEvent(NoticesEvent.OpenDeactivate(deal)) },
                        style = DmButtonStyle.Danger,
                    )
                deal.rawStatus == "deactivated" -> ZillitText(
                    text = str(S.desktop_dm_deactivated_on, DealDates.shortUtc(deal.lastPayDate ?: deal.lastPayDay)),
                    style = DmType.mono(11.sp, FontWeight.SemiBold),
                    color = if (ZillitTheme.colors.isDark) Color(0xFFFCA5A5) else Color(0xFFC0392B),
                )
            }
        }
        when {
            deal.noticeSent -> SentStamp(state, deal)
            kind != NoticeGroupKind.Deactivated ->
                DmButton(
                    str(S.dm_nda_send),
                    onClick = { onEvent(NoticesEvent.OpenSend(deal)) },
                    style = DmButtonStyle.Notice,
                    icon = ZillitIcons.Send,
                )
        }
    }
}

@Composable
private fun SentStamp(state: DealMemoUiState, deal: DealDoc) {
    val sender = deal.noticeSentBy?.let(state.people::get)
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ZillitText(
            text = str(S.desktop_dm_notice_sent_by, sender?.fullName ?: deal.noticeSentByName ?: "—"),
            style = DmType.sans(12.sp, FontWeight.SemiBold),
            color = dm.ink,
            maxLines = 1,
        )
        sender?.designationName?.takeIf { it.isNotBlank() }?.let {
            ZillitText(text = DealLabels.formatLabel(it), style = DmType.sans(11.sp), color = dm.ink3, maxLines = 1)
        }
        deal.noticeSentAt?.let {
            ZillitText(text = DealDates.shortDateTime(it), style = DmType.mono(11.sp), color = dm.ink3, maxLines = 1)
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun SendNoticeModal(state: DealMemoUiState, labels: DealCrewLabels, onEvent: (DealMemoEvent) -> Unit) {
    val draft = state.notices.send
    val shown = remember { mutableStateOf<SendNoticeDraft?>(null) }
    if (draft != null) shown.value = draft
    val current = shown.value
    val sending = draft?.sending == true
    DmModal(
        visible = draft != null,
        title = str(S.desktop_dm_send_notice),
        onDismiss = { onEvent(NoticesEvent.CancelSend) },
        maxWidth = 680.dp,
        dismissible = !sending,
    ) {
        if (current == null) return@DmModal
        Column(modifier = Modifier.padding(20.dp)) {
            AmberStrip(str(S.desktop_dm_you_can_edit_this_notice_below_before))
            Spacer(Modifier.height(16.dp))
            val role = labels.labels(current.deal).role
            ZillitText(
                text = buildAnnotatedString {
                    append(str(S.desktop_dm_send_the_end_of_contract_notice_for) + " ")
                    withStyle(
                        SpanStyle(fontWeight = FontWeight.SemiBold, color = dm.ink),
                    ) { append(current.deal.crewName ?: str(S.desktop_this_crew_member)) }
                    if (role != DealCrewLabels.DASH) withStyle(SpanStyle(color = dm.ink3)) { append(" · $role") }
                    append(".")
                },
                style = DmType.sans(13.sp),
                color = dm.ink2,
            )
            Spacer(Modifier.height(16.dp))
            DmEyebrow(str(S.desktop_dm_notice), tracking = 0.06f)
            Spacer(Modifier.height(6.dp))
            LetterField(value = current.body, onValueChange = { onEvent(NoticesEvent.EditSendBody(it)) })
            Spacer(Modifier.height(16.dp))
            DmEyebrow(str(S.dm_ph_last_pay_day), tracking = 0.06f)
            Spacer(Modifier.height(6.dp))
            ZillitDateField(
                value = current.date,
                onValueChange = { onEvent(NoticesEvent.EditSendDate(it)) },
                modifier = Modifier.fillMaxWidth(0.5f),
            )
            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DmButton(
                    str(S.dm_cancel),
                    onClick = { onEvent(NoticesEvent.CancelSend) },
                    style = DmButtonStyle.ModalNeutral,
                    enabled = !sending,
                )
                DmButton(
                    text = if (sending) str(S.dm_nda_sending) else str(S.desktop_dm_send_notice),
                    onClick = { onEvent(NoticesEvent.ConfirmSend) },
                    style = DmButtonStyle.Notice,
                    loading = sending,
                    enabled = current.date.isNotBlank() && current.body.isNotBlank(),
                )
            }
        }
    }
}

/** The notice letter's editable body — a tall, calm text area. */
@Composable
private fun LetterField(value: String, onValueChange: (String) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = DmType.sans(12.5.sp).copy(color = dm.ink, lineHeight = 20.sp),
        cursorBrush = SolidColor(dm.noticeOrange),
        modifier = Modifier
            .fillMaxWidth()
            .height(288.dp)
            .clip(shape)
            .background(dm.control)
            .border(1.dp, if (ZillitTheme.colors.isDark) Color.White.copy(alpha = 0.12f) else Color(0xFFDDDBD6), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun AmberStrip(text: String) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (ZillitTheme.colors.isDark) dm.amberSoft else Color(0xFFFFF7ED))
            .border(1.dp, dm.amberRing, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(ZillitIcons.Info, size = 13.dp, tint = dm.accent)
        ZillitText(
            text = text,
            style = DmType.sans(12.sp),
            color = if (ZillitTheme.colors.isDark) Color(0xFFFCD9A8) else Color(0xFF8A5A1A),
        )
    }
}

@Composable
@Suppress("LongMethod") // One modal, laid out in one place.
private fun SendAllModal(state: DealMemoUiState, groups: List<NoticeGroup>, onEvent: (DealMemoEvent) -> Unit) {
    val kind = state.notices.sendAll
    val shown = remember { mutableStateOf<NoticeGroupKind?>(null) }
    if (kind != null) shown.value = kind
    val current = shown.value
    val count = groups.firstOrNull { it.kind == current }?.unsent?.size ?: 0
    val sending = state.notices.sendingAll
    DmModal(
        visible = kind != null,
        title = str(S.desktop_dm_send_all_group, current?.title.orEmpty()),
        onDismiss = { onEvent(NoticesEvent.CancelSendAll) },
        maxWidth = 520.dp,
        dismissible = !sending,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            ZillitText(
                text = buildAnnotatedString {
                    append(str(S.desktop_dm_send_an_end_of_contract_notice_to) + " ")
                    withStyle(
                        SpanStyle(fontWeight = FontWeight.SemiBold, color = dm.ink),
                    ) {
                        append(
                            if (count == 1) {
                                str(S.desktop_sos_crew_member_one)
                            } else {
                                str(S.desktop_dm_n_crew_members, count)
                            },
                        )
                    }
                    append(" in ")
                    withStyle(
                        SpanStyle(fontWeight = FontWeight.SemiBold, color = dm.ink),
                    ) { append(current?.title.orEmpty()) }
                    append("?")
                },
                style = DmType.sans(13.sp),
                color = dm.ink2,
            )
            Spacer(Modifier.height(8.dp))
            ZillitText(
                text = str(S.desktop_dm_each_person_receives_their_own_notice_using),
                style = DmType.sans(12.sp).copy(lineHeight = 18.sp),
                color = dm.ink3,
            )
            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DmButton(
                    str(S.dm_cancel),
                    onClick = { onEvent(NoticesEvent.CancelSendAll) },
                    style = DmButtonStyle.ModalNeutral,
                    enabled = !sending,
                )
                DmButton(
                    text = when {
                        sending -> str(S.dm_nda_sending)
                        count == 1 -> str(S.desktop_dm_send_one_notice)
                        else -> str(S.desktop_dm_send_n_notices, count)
                    },
                    onClick = { onEvent(NoticesEvent.ConfirmSendAll) },
                    style = DmButtonStyle.Notice,
                    loading = sending,
                    enabled = count > 0,
                )
            }
        }
    }
}

/** Deactivate a deal memo — for crew no longer employed; payroll stops at the last pay date. */
@Suppress("LongMethod")
@Composable
internal fun DeactivateModal(draft: DeactivateDraft?, onEvent: (DealMemoEvent) -> Unit) {
    val shown = remember { mutableStateOf<DeactivateDraft?>(null) }
    if (draft != null) shown.value = draft
    val current = shown.value
    val busy = draft?.busy == true
    DmModal(
        visible = draft != null,
        title = str(S.desktop_dm_deactivate_deal_memo),
        onDismiss = { onEvent(NoticesEvent.CancelDeactivate) },
        dismissible = !busy,
        footer = {
            DmButton(
                str(S.dm_cancel),
                onClick = { onEvent(NoticesEvent.CancelDeactivate) },
                style = DmButtonStyle.ModalNeutral,
                enabled = !busy,
            )
            DmButton(
                text = if (busy) str(S.desktop_deactivating) else str(S.dm_notices_deactivate),
                onClick = { onEvent(NoticesEvent.ConfirmDeactivate) },
                style = DmButtonStyle.ModalDanger,
                loading = busy,
                enabled = current?.date?.isNotBlank() == true,
            )
        },
    ) {
        if (current == null) return@DmModal
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            ZillitText(
                text = str(S.desktop_dm_for_a_crew_member_who_is_no),
                style = DmType.sans(12.sp).copy(lineHeight = 18.sp),
                color = dm.ink3,
            )
            Spacer(Modifier.height(12.dp))
            val deal = current.deal
            if (deal.crewName != null || deal.reference != null) {
                ZillitText(
                    text = buildAnnotatedString {
                        append(deal.crewName ?: "—")
                        deal.reference?.let {
                            withStyle(
                                SpanStyle(
                                    fontWeight = FontWeight.Normal,
                                    color = Color(0xFF9CA3AF),
                                    fontFamily = ZillitTheme.fonts.mono,
                                ),
                            ) { append(" · $it") }
                        }
                    },
                    style = DmType.sans(12.sp, FontWeight.SemiBold),
                    color = dm.ink2,
                )
                Spacer(Modifier.height(12.dp))
            }
            Row {
                DmEyebrow(str(S.dm_notices_last_pay_date), color = dm.ink3, tracking = 0.05f)
                ZillitText(text = " *", style = DmType.sans(12.sp, FontWeight.SemiBold), color = dm.brandText)
            }
            Spacer(Modifier.height(4.dp))
            ZillitDateField(
                value = current.date,
                onValueChange = { onEvent(NoticesEvent.EditDeactivateDate(it)) },
                enabled = !busy,
            )
        }
    }
}
