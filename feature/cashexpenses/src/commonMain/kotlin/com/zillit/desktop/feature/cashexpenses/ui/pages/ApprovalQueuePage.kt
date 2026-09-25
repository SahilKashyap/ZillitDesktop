package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.ApprovalTiers
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.ui.CashBadges
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPerson
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.ReasonedAction

/**
 * The shared Approval Queue — the web's `PCApprovalPage`.
 *
 * Two tab cards, as the web draws them: Float Requests and Receipt Batches &
 * OOP. Floats list with their decision on the row; batches keep the list and
 * the batch side by side (the master–detail split this module uses for every
 * queue), with the web's approval progress above the batch's receipts.
 *
 * Approve fires at once, as the web's does; Reject asks for the reason;
 * Override is only for an override-holder who is not the next approver; an
 * accountant looking at a record no chain covers is offered Set Approval Level.
 */
@Suppress("LongMethod") // Notice, cards and the two tabs: one page, read together.
@Composable
fun ApprovalQueuePage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val people = LocalCashPeople.current
    var tab by rememberSaveable { mutableStateOf(if (state.selectedBatchId != null) TAB_BATCHES else TAB_FLOATS) }
    var openFloatId by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        FixedPage {
            PcNotice(
                title = str(S.desktop_pc_approval_queue_for, people.nameOf(state.viewer.userId)),
                body = str(S.desktop_pc_approval_queue_notice),
                tone = NoticeTone.Accent,
                icon = ZillitIcons.Check,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                CategoryCard(
                    title = str(S.desktop_pc_float_requests_title),
                    sub = str(S.desktop_pc_pending_approval_count, state.floatApprovals.size),
                    icon = ZillitIcons.Wallet,
                    active = tab == TAB_FLOATS,
                    unread = state.unread[CashBadges.FLOAT_APPROVAL] ?: 0,
                    info = false,
                    onClick = { tab = TAB_FLOATS },
                    modifier = Modifier.weight(1f),
                )
                CategoryCard(
                    title = str(S.desktop_pc_receipt_batches_oop),
                    sub = str(S.desktop_pc_items_awaiting_count, state.queueBatches.size),
                    icon = ZillitIcons.Receipt,
                    active = tab == TAB_BATCHES,
                    unread = state.unread[CashBadges.RECEIPT_APPROVAL] ?: 0,
                    info = true,
                    onClick = { tab = TAB_BATCHES },
                    modifier = Modifier.weight(1f),
                )
            }
            if (tab == TAB_FLOATS) {
                FloatRequestsTab(state, onEvent, onOpen = { openFloatId = it })
            } else {
                BatchesTab(state, onEvent)
            }
        }

        FloatApprovalDialog(
            state = state,
            float = state.floatApprovals.firstOrNull { it.id == openFloatId },
            onEvent = onEvent,
            onClose = { openFloatId = null },
        )
    }
}

// -- float requests -------------------------------------------------------------------

@Suppress("LongMethod") // Header, search and the list's three states.
@Composable
private fun ColumnScope.FloatRequestsTab(
    state: CashUiState,
    onEvent: (CashEvent) -> Unit,
    onOpen: (String) -> Unit,
) {
    val people = LocalCashPeople.current
    var query by rememberSaveable { mutableStateOf("") }
    val all = state.floatApprovals
    val rows = all.filter { float ->
        val needle = query.trim().lowercase()
        needle.isEmpty() ||
            "${people.nameOf(float.userId, float.holderName)} ${float.requestNumber}".lowercase().contains(needle)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = str(S.desktop_ce_float_requests).uppercase(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitStatusPill(label = str(S.desktop_card_pending_count, all.size), tone = StatusTone.Pending)
        Spacer(Modifier.weight(1f))
        ZillitSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = str(S.desktop_pc_search_requests),
            modifier = Modifier.width(SEARCH_WIDTH),
        )
    }
    when {
        state.loading && all.isEmpty() -> LoadingCard()
        rows.isEmpty() -> EmptyCard(
            if (all.isEmpty()) str(S.ah_no_float_requests_pending) else str(S.desktop_pc_no_requests_match),
        )

        else -> PcCard(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            ZillitScrollColumn(modifier = Modifier.fillMaxWidth()) {
                rows.forEachIndexed { index, float ->
                    if (index > 0) ZillitDivider()
                    FloatApprovalRow(state, float, onEvent, onOpen)
                }
            }
        }
    }
}

@Suppress("LongMethod") // One row: who, what, how far along, and the decision.
@Composable
private fun FloatApprovalRow(
    state: CashUiState,
    float: CashFloat,
    onEvent: (CashEvent) -> Unit,
    onOpen: (String) -> Unit,
) {
    val viewer = state.viewer
    val canAct = CashRules.mayApprove(viewer, float)
    val total = ApprovalTiers.totalFor(viewer, float.departmentId, float.requestedAmount)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(float.id) }
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                CashPerson(userId = float.userId, recordedName = float.holderName)
                ZillitStatusPill(label = str(S.desktop_pc_float_request_pill), tone = StatusTone.Pending)
                ZillitBadge(count = state.unreadFor(CashBadges.FLOAT_APPROVAL, float.id))
            }
            RoleLine(state, float.userId, float.departmentId)
            ZillitText(
                text = listOfNotNull(
                    "#${float.requestNumber}",
                    EpochDate.dateTime(float.createdAt).takeIf { it.isNotBlank() },
                    float.purpose?.takeIf { it.isNotBlank() }?.let { "“$it”" },
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = state.formatMoney(float.requestedAmount, float.currency),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
            )
            ZillitStatusPill(label = pendingLabel(float.approvals.size, total), tone = StatusTone.Pending, dot = true)
        }
        when {
            canAct -> {
                ZillitButton(
                    text = str(S.reject),
                    onClick = { onOpen(float.id) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = str(S.approve),
                    onClick = { onEvent(CashEvent.ActNow(confirm(ConfirmAction.ApproveFloat, float.id))) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Check,
                    enabled = !state.busy,
                )
            }

            viewer.canOverrideFloat() -> ZillitButton(
                text = str(S.dm_nom_table_override),
                onClick = { onEvent(CashEvent.ActNow(confirm(ConfirmAction.OverrideFloat, float.id))) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
        if (ApprovalTiers.needsApprovalLevel(viewer, float.departmentId, float.requestedAmount)) {
            ZillitButton(
                text = str(S.desktop_pc_set_approval_level),
                onClick = { onEvent(CashEvent.OpenApprovalLevels) },
                size = ButtonSize.Small,
            )
        }
    }
}

/**
 * The float request, in full — the web's `FloatDetailModal`
 * (`PCApprovalPage.jsx:227-551`). Closes itself once the request leaves the
 * queue, which is what a decision does.
 */
@Suppress("LongMethod") // The request's fields, its progress and its decision.
@Composable
private fun FloatApprovalDialog(
    state: CashUiState,
    float: CashFloat?,
    onEvent: (CashEvent) -> Unit,
    onClose: () -> Unit,
) {
    val shown = float
    val viewer = state.viewer
    ZillitDialogShell(
        title = shown?.let { str(S.desktop_pc_float_request_title, it.requestNumber) }.orEmpty(),
        visible = float != null,
        onDismiss = onClose,
        icon = ZillitIcons.Wallet,
        width = DIALOG_WIDTH,
        actions = { if (shown != null) FloatApprovalActions(state, shown, onEvent) },
    ) {
        if (shown == null) return@ZillitDialogShell
        val total = ApprovalTiers.totalFor(viewer, shown.departmentId, shown.requestedAmount)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                CashPerson(userId = shown.userId, recordedName = shown.holderName)
                RoleLine(state, shown.userId, shown.departmentId)
                ZillitText(
                    text = EpochDate.dateTime(shown.createdAt),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            if (total > 0) {
                ZillitStatusPill(label = pendingLabel(shown.approvals.size, total), tone = StatusTone.Pending)
            }
        }
        FieldGrid(
            listOf(
                str(S.desktop_pc_requested_amount) to state.formatMoney(shown.requestedAmount, shown.currency),
                str(S.desktop_pc_how_long) to (durationLabel(shown) ?: "—"),
                str(S.desktop_pc_collection_method) to collectionMethodLabel(shown.collectionMethod),
                str(S.department) to (state.departmentName(shown.departmentId) ?: "—"),
                str(S.desktop_pc_collect_date_time) to collectWhen(shown),
                str(S.desktop_pc_bs_code_label) to (shown.bsCode?.takeIf { it.isNotBlank() } ?: "—"),
            ),
        )
        shown.purpose?.takeIf { it.isNotBlank() }?.let { purpose ->
            ZillitText(
                text = str(S.desktop_pc_purpose_justification).uppercase(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitText(
                text = purpose,
                style = ZillitTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ZillitTheme.colors.surfaceSunken, ZillitTheme.shapes.medium)
                    .padding(ZillitTheme.spacing.md),
            )
        }
        when {
            ApprovalTiers.needsApprovalLevel(viewer, shown.departmentId, shown.requestedAmount) ->
                NoTiersBanner { onEvent(CashEvent.OpenApprovalLevels) }

            total > 0 -> ApprovalProgress(shown.approvals, total)
        }
    }
}

/** History on the left; the decision this viewer may take on the right. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.FloatApprovalActions(
    state: CashUiState,
    request: CashFloat,
    onEvent: (CashEvent) -> Unit,
) {
    val viewer = state.viewer
    ZillitButton(
        text = str(S.history),
        onClick = { onEvent(CashEvent.ShowFloatHistory(request.id, request.requestNumber)) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Clock,
    )
    Spacer(Modifier.weight(1f))
    if (CashRules.mayApprove(viewer, request)) {
        ZillitButton(
            text = str(S.reject),
            onClick = { onEvent(CashEvent.Ask(rejectFloat(request))) },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
        )
        ZillitButton(
            text = str(S.approve),
            onClick = { onEvent(CashEvent.ActNow(confirm(ConfirmAction.ApproveFloat, request.id))) },
            size = ButtonSize.Small,
            loading = state.busy,
        )
    } else if (viewer.canOverrideFloat()) {
        ZillitButton(
            text = str(S.dm_nom_table_override),
            onClick = { onEvent(CashEvent.ActNow(confirm(ConfirmAction.OverrideFloat, request.id))) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            loading = state.busy,
        )
    }
}

/** Two columns of labelled values. */
@Composable
internal fun FieldGrid(fields: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        fields.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                pair.forEach { (label, value) -> PcField(label, value, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// -- receipt batches -------------------------------------------------------------------

@Suppress("LongMethod") // Filters, the list and the detail pane.
@Composable
private fun ColumnScope.BatchesTab(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val people = LocalCashPeople.current
    var type by rememberSaveable { mutableStateOf(TYPE_ALL) }
    val all = state.queueBatches
    val rows = all.filter { batch ->
        val typed = when (type) {
            TYPE_PC -> batch.expenseType != ExpenseType.OutOfPocket
            TYPE_OOP -> batch.expenseType == ExpenseType.OutOfPocket
            else -> true
        }
        val needle = state.search.trim().lowercase()
        typed && (
            needle.isEmpty() ||
                "${people.nameOf(batch.userId, batch.holderName)} ${batch.reference}".lowercase().contains(needle)
            )
    }
    val selected = rows.firstOrNull { it.id == state.selectedBatchId }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        listOf(
            TYPE_ALL to str(S.all),
            TYPE_PC to str(S.desktop_petty_cash),
            TYPE_OOP to str(S.desktop_ce_out_of_pocket),
        ).forEach { (key, label) ->
            ZillitChoiceChip(label = label, selected = type == key, onClick = { type = key })
        }
        Spacer(Modifier.weight(1f))
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(CashEvent.Search(it)) },
            placeholder = str(S.desktop_pc_search_batches),
            modifier = Modifier.width(SEARCH_WIDTH),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Column(modifier = Modifier.weight(LIST_WEIGHT).fillMaxHeight()) {
            when {
                state.loading && all.isEmpty() -> LoadingCard()
                rows.isEmpty() -> EmptyCard(
                    if (all.isEmpty()) {
                        str(S.desktop_pc_no_batches_awaiting)
                    } else {
                        str(S.desktop_pc_no_batches_match_filter)
                    },
                )

                else -> PcCard(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    ZillitScrollColumn(modifier = Modifier.fillMaxWidth()) {
                        rows.forEachIndexed { index, batch ->
                            if (index > 0) ZillitDivider()
                            BatchApprovalRow(state, batch, batch.id == state.selectedBatchId, onEvent)
                        }
                    }
                }
            }
        }
        ZillitSectionCard(
            title = str(S.desktop_ce_batch_detail),
            icon = ZillitIcons.Ledger,
            modifier = Modifier.weight(DETAIL_WEIGHT).fillMaxHeight(),
            padded = false,
        ) {
            if (selected == null) {
                ZillitEmptyState(
                    title = str(S.desktop_ce_pick_a_batch),
                    message = str(S.desktop_ce_pick_batch_hint),
                    icon = ZillitIcons.Eye,
                )
            } else {
                ApprovalBatchPane(state, selected, onEvent)
            }
        }
    }
}

@Suppress("LongMethod") // One row: who, which pipeline, what, and the decision.
@Composable
private fun BatchApprovalRow(
    state: CashUiState,
    batch: ClaimBatch,
    selected: Boolean,
    onEvent: (CashEvent) -> Unit,
) {
    val viewer = state.viewer
    val canAct = CashRules.mayApprove(viewer, batch)
    val oop = batch.expenseType == ExpenseType.OutOfPocket
    val open = { onEvent(CashEvent.SelectBatch(batch.id)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) ZillitTheme.colors.surfaceSelected else ZillitTheme.colors.surface)
            .clickable(onClick = open)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                CashPerson(userId = batch.userId, recordedName = batch.holderName)
                ZillitStatusPill(
                    label = if (oop) str(S.desktop_payroll_tag_oop) else str(S.desktop_pc_pc),
                    tone = if (oop) StatusTone.Progress else StatusTone.Pending,
                )
                ZillitBadge(count = state.unreadFor(CashBadges.RECEIPT_APPROVAL, batch.id))
            }
            RoleLine(state, batch.userId, batch.departmentId)
            ZillitText(
                text = listOfNotNull(
                    "#${batch.reference}",
                    EpochDate.dateTime(batch.createdAt).takeIf { it.isNotBlank() },
                    receiptsLabel(batch.claimCount),
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
            batch.notes?.takeIf { it.isNotBlank() }?.let {
                ZillitText(
                    text = "“$it”",
                    style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = state.formatMoney(batch.totalGross, batch.currency),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
            )
            ZillitStatusPill(label = str(S.dm_filter_status_pending), tone = StatusTone.Pending, dot = true)
        }
        when {
            // Both open the batch, where the receipts to tick are — as the
            // web's row buttons open its modal.
            canAct -> {
                ZillitButton(
                    text = str(S.reject),
                    onClick = open,
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = str(S.approve),
                    onClick = open,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Check,
                )
            }

            viewer.canOverrideBatch() -> ZillitButton(
                text = str(S.dm_nom_table_override),
                onClick = { onEvent(CashEvent.ActNow(confirm(ConfirmAction.OverrideBatch, batch.id))) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
        if (ApprovalTiers.needsApprovalLevel(viewer, batch.departmentId, batch.totalGross)) {
            ZillitButton(
                text = str(S.desktop_pc_set_approval_level),
                onClick = { onEvent(CashEvent.OpenApprovalLevels) },
                size = ButtonSize.Small,
            )
        }
    }
}

/** The approval chain above the batch's own detail — the web's modal header (`PCApprovalPage.jsx:716-767`). */
@Composable
private fun ApprovalBatchPane(state: CashUiState, batch: ClaimBatch, onEvent: (CashEvent) -> Unit) {
    val viewer = state.viewer
    val total = ApprovalTiers.totalFor(viewer, batch.departmentId, batch.totalGross)
    val needsLevel = ApprovalTiers.needsApprovalLevel(viewer, batch.departmentId, batch.totalGross)
    Column(modifier = Modifier.fillMaxSize()) {
        if (needsLevel || total > 0) {
            Column(modifier = Modifier.fillMaxWidth().padding(PaddingValues(ZillitTheme.spacing.lg))) {
                if (needsLevel) {
                    NoTiersBanner { onEvent(CashEvent.OpenApprovalLevels) }
                } else {
                    ApprovalProgress(batch.approvals, total)
                }
            }
            ZillitDivider()
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            BatchDetail(state, batch, onEvent)
        }
    }
}

// -- pieces -----------------------------------------------------------------------------

/** "Designation · Department", from the crew list and the production's departments. */
@Composable
internal fun RoleLine(state: CashUiState, userId: String?, departmentId: String?) {
    val designation = state.assignees.firstOrNull { it.userId == userId }?.designation?.takeIf { it.isNotBlank() }
    val line = listOfNotNull(designation, state.departmentName(departmentId)).joinToString(" · ")
    if (line.isBlank()) return
    ZillitText(
        text = line,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
        maxLines = 1,
    )
}

@Composable
private fun ColumnScope.EmptyCard(text: String) {
    PcCard(modifier = Modifier.fillMaxWidth()) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.fillMaxWidth().padding(EMPTY_PADDING),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun LoadingCard() {
    PcCard(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().padding(EMPTY_PADDING), contentAlignment = Alignment.Center) {
            ZillitSpinner(size = SPINNER)
        }
    }
}

/** "Pending 1/2", or "Pending" when no chain covers the record. */
private fun pendingLabel(signed: Int, total: Int): String =
    if (total > 0) str(S.desktop_inv_pending_of, signed, total) else str(S.pending)

internal fun receiptsLabel(count: Int): String =
    if (count == 1) str(S.desktop_card_receipt_count_one, count) else str(S.desktop_card_receipt_count_other, count)

private fun confirm(action: ConfirmAction, id: String) = CashPrompt.Confirm(action, id, "", "")

private fun rejectFloat(float: CashFloat) = CashPrompt.WithReason(
    action = ReasonedAction.RejectFloat,
    targetId = float.id,
    title = str(S.desktop_pc_reject_float_title, float.requestNumber),
    label = str(S.cs_rejection_reason),
)

/** The web's `VALUE_LABELS` for how the cash is collected. */
private fun collectionMethodLabel(method: String?): String = when (method) {
    null, "" -> "—"
    "production_office" -> str(S.desktop_pc_collect_production_office)
    "arrange_accountant" -> str(S.desktop_pc_arrange_with_accountant)
    else -> method
}

/** "12 Sep 2026 | 09:30 AM", either half alone, or "—". */
private fun collectWhen(float: CashFloat): String {
    val day = EpochDate.date(float.collectDate).takeIf { it.isNotBlank() }
    val time = float.collectTime?.trim()?.takeIf { it.isNotBlank() }?.let(::twelveHour)
    return listOfNotNull(day, time).joinToString(" | ").ifBlank { "—" }
}

/** `14:05` → `02:05 PM`; anything else as it came. */
private fun twelveHour(raw: String): String {
    val match = Regex("^(\\d{1,2}):(\\d{2})").find(raw) ?: return raw
    val hour = match.groupValues[1].toInt()
    val period = if (hour >= NOON) "PM" else "AM"
    val twelve = (hour % NOON).takeIf { it != 0 } ?: NOON
    return "${twelve.toString().padStart(2, '0')}:${match.groupValues[2]} $period"
}

private const val TAB_FLOATS = "floats"
private const val TAB_BATCHES = "batches"
private const val TYPE_ALL = "all"
private const val TYPE_PC = "pc"
private const val TYPE_OOP = "oop"
private const val NOON = 12
private const val LIST_WEIGHT = 1.3f
private const val DETAIL_WEIGHT = 1f
private val SEARCH_WIDTH = 240.dp
private val DIALOG_WIDTH = 640.dp
private val EMPTY_PADDING = 40.dp
private val SPINNER = 16.dp
