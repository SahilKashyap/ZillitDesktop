package com.zillit.desktop.feature.purchaseorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.LocalCopy
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder

/**
 * The Purchase Orders tool.
 *
 * Four surfaces on a tab strip — the production's commitments, everyone's
 * orders, your own, and the queue of decisions waiting on you — plus the form
 * that raises one. A sidebar would be too much furniture for five places.
 */
@Composable
fun PurchaseOrderScreen(
    state: PoUiState,
    onEvent: (PoEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ZillitTheme.colors.surface)
                    .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                ZillitPageHeader(
                    eyebrow = "Finance",
                    title = "Purchase Orders",
                    description = "Commit spend with a vendor before it happens, and follow it to the ledger.",
                    actions = {
                        ZillitButton(
                            text = "Refresh",
                            onClick = { onEvent(PoEvent.Refresh) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                            leadingIcon = ZillitIcons.Reload,
                            loading = state.loading,
                        )
                    },
                )
                ZillitTabStrip(
                    tabs = state.visibleDestinations.map { ZillitTab(it.slug, it.label) },
                    activeId = state.destination.slug,
                    onSelect = { slug ->
                        PoDestination.entries.firstOrNull { it.slug == slug }
                            ?.let { onEvent(PoEvent.Open(it)) }
                    },
                )
            }
            ZillitDivider()
            OfflineBanner(state)

            val error = state.error
            when {
                error != null -> ZillitErrorState(
                    message = error.userMessage,
                    onRetry = { onEvent(PoEvent.Refresh) },
                )

                state.destination == PoDestination.Overview -> OverviewPage(state, onEvent)
                state.destination == PoDestination.Raise -> RaisePage(state, onEvent)
                else -> OrdersPage(state, onEvent)
            }
        }

        PoPromptDialog(state.prompt, onEvent)
        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(PoEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

@Suppress("LongMethod") // A dashboard: tiles and a table, read as one screen.
@Composable
private fun OverviewPage(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val awaiting = state.orders.count { it.status == PoStatus.AwaitingApproval }
    val committed = state.committedByCurrency
    val open = state.orders.count { !it.status.isFinished }

    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitStatTile(
                label = "Open orders",
                value = open.toString(),
                sub = "Not yet closed or cancelled",
                icon = ZillitIcons.File,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = "Awaiting approval",
                value = awaiting.toString(),
                sub = "Held up before commitment",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Shield,
                onClick = { onEvent(PoEvent.Open(PoDestination.ApprovalQueue)) },
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = "Committed",
                // One currency's worth in the tile; the rest are listed below,
                // because summing across currencies would be wrong.
                value = committed.entries.firstOrNull()
                    ?.let { Money.format(it.value, it.key) } ?: "—",
                sub = if (committed.size > 1) "${committed.size} currencies" else "Approved and posted",
                tone = StatusTone.Done,
                icon = ZillitIcons.Ledger,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = "Vendors",
                value = state.vendors.size.toString(),
                sub = "Available to order from",
                icon = ZillitIcons.Users,
                modifier = Modifier.weight(1f),
            )
        }

        if (committed.size > 1) {
            ZillitSectionCard(title = "Committed by currency", icon = ZillitIcons.Bank) {
                committed.forEach { (currency, amount) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitText(
                            text = currency.ifBlank { "Unstated currency" },
                            style = ZillitTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        ZillitText(
                            text = Money.format(amount, currency),
                            style = ZillitTheme.typography.numeric,
                        )
                    }
                }
            }
        }

        ZillitSectionCard(title = "Recent orders", icon = ZillitIcons.File, padded = false) {
            ZillitDataTable(
                rows = state.orders.take(RECENT_ROWS),
                columns = orderColumns(),
                key = { it.id },
                loading = state.loading,
                emptyTitle = "No purchase orders yet",
                emptyMessage = "Raise one to commit spend with a vendor.",
                onRowClick = { onEvent(PoEvent.Open(PoDestination.AllOrders)) },
                virtualised = false,
            )
        }
    }
}

@Suppress("LongMethod") // Filters, list and detail: one screen read together.
@Composable
private fun OrdersPage(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val rows = state.rows
    val selected = state.selected

    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(PoEvent.Search(it)) },
                placeholder = "Search by number, vendor or description",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            ZillitSelect(
                value = state.statusFilter,
                options = listOf(null) + PoStatus.entries.filter { it != PoStatus.Unknown },
                onSelect = { onEvent(PoEvent.Filter(it)) },
                label = { it?.label ?: "Every status" },
            )
            ZillitText(
                text = "${rows.size} order${if (rows.size == 1) "" else "s"}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (state.selection.isNotEmpty() && state.viewer.isAccountant) {
                ZillitButton(
                    text = "Close ${state.selection.size} selected",
                    onClick = {
                        onEvent(
                            PoEvent.Ask(
                                PoPrompt.Confirm(
                                    PoConfirmAction.CloseSelected,
                                    "",
                                    "Close ${state.selection.size} order(s)",
                                    "Closing releases the remaining commitment. It cannot be undone here.",
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(
                title = state.destination.label,
                icon = ZillitIcons.File,
                padded = false,
                modifier = Modifier.weight(LIST_WEIGHT).fillMaxHeight(),
            ) {
                ZillitDataTable(
                    rows = rows,
                    columns = selectableColumns(state, onEvent),
                    key = { it.id },
                    loading = state.loading,
                    onRowClick = { onEvent(PoEvent.Select(it.id)) },
                    isSelected = { it.id == state.selectedId },
                    emptyTitle = if (state.search.isBlank()) "Nothing here" else "Nothing matches that search",
                    emptyMessage = when (state.destination) {
                        PoDestination.ApprovalQueue -> "Orders routed to you for a decision appear here."
                        PoDestination.MyOrders -> "Orders you raise appear here with their progress."
                        else -> "Purchase orders on this production appear here."
                    },
                )
            }

            ZillitSectionCard(
                title = "Order detail",
                icon = ZillitIcons.Eye,
                padded = false,
                modifier = Modifier.weight(DETAIL_WEIGHT).fillMaxHeight(),
            ) {
                if (selected == null) {
                    ZillitEmptyState(
                        title = "Pick an order",
                        message = "Its lines, approvals and history show here.",
                        icon = ZillitIcons.Eye,
                    )
                } else {
                    OrderDetail(state, selected, onEvent)
                }
            }
        }
    }
}

@Suppress("LongMethod") // One order, top to bottom; the order is the reading order.
@Composable
private fun OrderDetail(state: PoUiState, order: PurchaseOrder, onEvent: (PoEvent) -> Unit) {
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = order.number.ifBlank {
                        if (order.isLocalOnly) "New order" else "Order ${order.id.take(ID_FALLBACK)}"
                    },
                    style = ZillitTheme.typography.titleMedium,
                )
                ZillitText(
                    text = order.vendorName.ifBlank { "No vendor" },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            OrderStatusPill(order)
        }

        order.local?.let { LocalOrderNotice(it) }

        ZillitText(
            text = Money.format(order.total, order.currency),
            style = ZillitTheme.typography.displayLarge,
        )

        // A header total that disagrees with its lines is usually a line edited
        // after the fact. Named rather than silently corrected: which figure is
        // right is not this client's call.
        if (order.totalsDisagree) {
            ZillitNotice(
                text = "The header total and the lines disagree — lines come to " +
                    "${Money.format(order.lineTotal, order.currency)}.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }

        if (order.description.isNotBlank()) {
            ZillitText(text = order.description, style = ZillitTheme.typography.bodyMedium)
        }

        if (order.lines.isNotEmpty()) {
            ZillitDivider()
            ZillitText(text = "Lines", style = ZillitTheme.typography.titleSmall)
            order.lines.forEach { line ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        ZillitText(
                            text = line.description.ifBlank { "Line" },
                            style = ZillitTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                        ZillitText(
                            text = "${line.quantity} × ${Money.format(line.unitPrice, order.currency)}" +
                                (line.nominalCode?.let { " · $it" } ?: ""),
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textMuted,
                            maxLines = 1,
                        )
                    }
                    ZillitText(
                        text = Money.format(line.total, order.currency),
                        style = ZillitTheme.typography.numeric,
                    )
                }
            }
        }

        if (order.approvals.isNotEmpty()) {
            ZillitDivider()
            ZillitText(text = "Approval chain", style = ZillitTheme.typography.titleSmall)
            order.approvals.forEach { step ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = "${step.level}. ${step.name.ifBlank { "Unnamed approver" }}",
                        style = ZillitTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    ZillitStatusPill(
                        label = step.decision?.replaceFirstChar { it.uppercase() } ?: "Waiting",
                        tone = if (step.decided) StatusTone.Done else StatusTone.Pending,
                    )
                }
            }
        }

        ZillitDivider()
        OrderActions(state, order, onEvent)
    }
}

@Suppress("LongMethod") // A rights table; flattening it is what makes it readable.
@Composable
private fun OrderActions(state: PoUiState, order: PurchaseOrder, onEvent: (PoEvent) -> Unit) {
    // Nothing on the server to act on yet; the outbox owns retry and discard.
    if (order.isLocalOnly) return
    val actions = buildList {
        // Approvals belong to whoever the chain routed it to; the queue is that
        // routing, so the buttons are offered there rather than everywhere.
        if (state.destination == PoDestination.ApprovalQueue && order.status == PoStatus.AwaitingApproval) {
            add(
                Triple("Approve", ButtonVariant.Primary) {
                    PoPrompt.Confirm(
                        PoConfirmAction.Approve,
                        order.id,
                        "Approve this order",
                        "${Money.format(order.total, order.currency)} is committed with " +
                            "${order.vendorName.ifBlank { "the vendor" }}.",
                    ) as PoPrompt
                },
            )
            add(
                Triple("Reject", ButtonVariant.Danger) {
                    PoPrompt.WithReason(
                        PoReasonAction.Reject,
                        order.id,
                        "Reject this order",
                        "Why it is being refused",
                    ) as PoPrompt
                },
            )
        }
        if (state.viewer.isAccountant && order.status == PoStatus.Approved) {
            add(
                Triple("Post to ledger", ButtonVariant.Primary) {
                    PoPrompt.Confirm(
                        PoConfirmAction.Post,
                        order.id,
                        "Post this order",
                        "The commitment reaches the ledger. This cannot be undone here.",
                    ) as PoPrompt
                },
            )
        }
        if (state.viewer.isAccountant && order.status.isCommitted) {
            add(
                Triple("Close", ButtonVariant.Secondary) {
                    PoPrompt.Confirm(
                        PoConfirmAction.Close,
                        order.id,
                        "Close this order",
                        "Any remaining commitment is released.",
                    ) as PoPrompt
                },
            )
        }
        if (order.status == PoStatus.Draft && state.viewer.owns(order)) {
            add(
                Triple("Delete", ButtonVariant.Danger) {
                    PoPrompt.Confirm(
                        PoConfirmAction.Delete,
                        order.id,
                        "Delete this draft",
                        "The order is removed. This cannot be undone.",
                    ) as PoPrompt
                },
            )
        }
    }

    if (actions.isEmpty()) {
        ZillitText(
            text = "Nothing to do on this order from here.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        actions.forEach { (label, variant, prompt) ->
            ZillitButton(
                text = label,
                onClick = { onEvent(PoEvent.Ask(prompt())) },
                variant = variant,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
    }
}

@Suppress("LongMethod") // A form: header fields then its lines, read as one.
@Composable
private fun RaisePage(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val draft = state.draft

    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ZillitSectionCard(title = "Who and what", icon = ZillitIcons.File) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = "Vendor",
                        style = ZillitTheme.typography.label,
                        color = ZillitTheme.colors.textSecondary,
                    )
                    ZillitSelect(
                        value = state.vendors.firstOrNull { it.id == draft.vendorId },
                        options = state.vendors,
                        onSelect = { vendor ->
                            onEvent(
                                PoEvent.EditDraft(
                                    draft.copy(
                                        vendorId = vendor?.id,
                                        vendorName = vendor?.name.orEmpty(),
                                        // The vendor's own currency and default
                                        // code, so the common case needs no
                                        // further typing.
                                        currency = vendor?.currency ?: draft.currency,
                                        nominalCode = vendor?.defaultNominalCode ?: draft.nominalCode,
                                    ),
                                ),
                            )
                        },
                        label = { it?.name ?: "Choose a vendor" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ZillitTextField(
                    value = draft.nominalCode,
                    onValueChange = { onEvent(PoEvent.EditDraft(draft.copy(nominalCode = it))) },
                    label = "Nominal code",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = draft.episode,
                    onValueChange = { onEvent(PoEvent.EditDraft(draft.copy(episode = it))) },
                    label = "Episode",
                    modifier = Modifier.weight(1f),
                )
            }
            ZillitTextField(
                value = draft.description,
                onValueChange = { onEvent(PoEvent.EditDraft(draft.copy(description = it))) },
                label = "What is being ordered",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ZillitSectionCard(
            title = "Lines",
            icon = ZillitIcons.Ledger,
            meta = Money.format(draft.total, draft.currency),
            action = {
                ZillitButton(
                    text = "Add line",
                    onClick = { onEvent(PoEvent.AddLine) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            },
        ) {
            draft.lines.forEachIndexed { index, line ->
                LineRow(
                    index = index,
                    line = line,
                    removable = draft.lines.size > 1,
                    onChange = { updated ->
                        onEvent(
                            PoEvent.EditDraft(
                                draft.copy(
                                    lines = draft.lines.mapIndexed { i, existing ->
                                        if (i == index) updated else existing
                                    },
                                ),
                            ),
                        )
                    },
                    onRemove = { onEvent(PoEvent.RemoveLine(index)) },
                )
            }
        }

        ZillitSectionCard(title = "Anything else", icon = ZillitIcons.Info) {
            ZillitTextField(
                value = draft.notes,
                onValueChange = { onEvent(PoEvent.EditDraft(draft.copy(notes = it))) },
                label = "Note for the approver (optional)",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(ZillitTheme.spacing.xs))
            if (state.offline) {
                ZillitNotice(
                    text = "You're offline. The order will be saved on this computer and raised automatically " +
                        "when you're back — you'll see it under My Orders as \"Waiting to send\".",
                    tone = StatusTone.InTransit,
                    icon = ZillitIcons.Info,
                )
            }
            ZillitButton(
                text = if (state.offline) "Save and raise when online" else "Raise this order",
                onClick = { onEvent(PoEvent.SubmitDraft) },
                leadingIcon = ZillitIcons.Send,
                loading = state.busy,
            )
        }
    }
}

@Composable
private fun LineRow(
    index: Int,
    line: PoLine,
    removable: Boolean,
    onChange: (PoLine) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        ZillitTextField(
            value = line.description,
            onValueChange = { onChange(line.copy(description = it)) },
            label = if (index == 0) "Description" else null,
            modifier = Modifier.weight(LINE_DESCRIPTION_WEIGHT),
        )
        ZillitTextField(
            value = if (line.quantity == 0.0) "" else line.quantity.toString(),
            onValueChange = { onChange(line.copy(quantity = it.trim().toDoubleOrNull() ?: 0.0)) },
            label = if (index == 0) "Qty" else null,
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(LINE_SMALL_WEIGHT),
        )
        ZillitTextField(
            value = if (line.unitPrice == 0.0) "" else line.unitPrice.toString(),
            onValueChange = { onChange(line.copy(unitPrice = it.trim().toDoubleOrNull() ?: 0.0)) },
            label = if (index == 0) "Unit price" else null,
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = line.nominalCode.orEmpty(),
            onValueChange = { onChange(line.copy(nominalCode = it.takeIf(String::isNotBlank))) },
            label = if (index == 0) "Code" else null,
            modifier = Modifier.weight(LINE_SMALL_WEIGHT),
        )
        if (removable) {
            ZillitButton(
                text = "",
                onClick = onRemove,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Trash,
            )
        }
    }
}

@Composable
private fun PoPromptDialog(prompt: PoPrompt?, onEvent: (PoEvent) -> Unit) {
    val shown = remember(prompt) { prompt }
    ZillitDialogShell(
        title = when (shown) {
            is PoPrompt.Confirm -> shown.title
            is PoPrompt.WithReason -> shown.title
            null -> ""
        },
        icon = ZillitIcons.Info,
        visible = prompt != null,
        onDismiss = { onEvent(PoEvent.DismissPrompt) },
    ) {
        when (shown) {
            is PoPrompt.Confirm -> ZillitText(
                text = shown.message,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )

            is PoPrompt.WithReason -> ZillitTextField(
                value = shown.reason,
                onValueChange = { onEvent(PoEvent.UpdatePrompt(shown.copy(reason = it))) },
                label = shown.label,
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )

            null -> Unit
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(PoEvent.DismissPrompt) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (shown is PoPrompt.WithReason) "Reject" else "Confirm",
                onClick = { onEvent(PoEvent.ConfirmPrompt) },
                variant = if (shown.isDestructive()) ButtonVariant.Danger else ButtonVariant.Primary,
            )
        }
    }
}

private fun PoPrompt?.isDestructive(): Boolean = when (this) {
    is PoPrompt.WithReason -> true
    is PoPrompt.Confirm -> action == PoConfirmAction.Delete ||
        action == PoConfirmAction.Post ||
        action == PoConfirmAction.Close ||
        action == PoConfirmAction.CloseSelected

    null -> false
}

internal val PoStatus.tone: StatusTone
    get() = when (this) {
        PoStatus.Draft, PoStatus.Unknown -> StatusTone.Neutral
        PoStatus.AwaitingApproval -> StatusTone.Pending
        PoStatus.Approved, PoStatus.AccountsEntered -> StatusTone.Ready
        PoStatus.Queued -> StatusTone.Progress
        PoStatus.Posted -> StatusTone.Done
        PoStatus.Rejected, PoStatus.Cancelled -> StatusTone.Rejected
        PoStatus.Closed -> StatusTone.Neutral
    }

/**
 * The line under the tabs when the list is a saved copy: the network is gone
 * and these are the orders as of the last time it answered.
 */
@Composable
private fun OfflineBanner(state: PoUiState) {
    val since = state.staleSince ?: return
    ZillitNotice(
        text = "You're offline — showing orders saved ${EpochDate.dateTime(since)}. " +
            "They'll refresh when the connection is back.",
        tone = StatusTone.Pending,
        icon = ZillitIcons.Info,
        modifier = Modifier.padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.sm),
    )
}

/** Why a local-only order has no number, and what to do if it could not be sent. */
@Composable
private fun LocalOrderNotice(local: LocalCopy) {
    ZillitNotice(
        text = if (local.failed) {
            "This order could not be sent: ${local.error ?: "the server refused it"}. " +
                "Retry or discard it from Pending changes in the status bar."
        } else {
            "This order is saved on this computer and will be raised on the server " +
                "as soon as you're back online. It has no number until then."
        },
        tone = if (local.failed) StatusTone.Rejected else StatusTone.InTransit,
        icon = ZillitIcons.Info,
    )
}

/**
 * The status pill, with one exception: an order that exists only on this
 * computer is not "Draft" — it is waiting to be sent, or could not be, and
 * the pill says which.
 */
@Composable
private fun OrderStatusPill(order: PurchaseOrder) {
    val local = order.local
    when {
        local == null -> ZillitStatusPill(order.status.label, tone = order.status.tone, dot = true)
        local.failed -> ZillitStatusPill("Not sent", tone = StatusTone.Rejected, dot = true)
        else -> ZillitStatusPill("Waiting to send", tone = StatusTone.InTransit, dot = true)
    }
}

@Suppress("MagicNumber") // Column proportions.
private fun orderColumns(): List<TableColumn<PurchaseOrder>> = listOf(
    textColumn("Number", ColumnWidth.Weight(1f)) { it.number.ifBlank { it.numberFallback() } },
    textColumn("Vendor", ColumnWidth.Weight(1.4f)) { it.vendorName.ifBlank { "—" } },
    textColumn("Description", ColumnWidth.Weight(1.6f), muted = true) { it.description.ifBlank { "—" } },
    textColumn("Total", ColumnWidth.Weight(1f), numeric = true) { Money.format(it.total, it.currency) },
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(STATUS_COLUMN),
        cell = { OrderStatusPill(it) },
    ),
)

/** What to show where the number would be: a local row has none yet. */
private fun PurchaseOrder.numberFallback(): String =
    if (isLocalOnly) "Not sent yet" else id.take(ID_FALLBACK)

private fun selectableColumns(
    state: PoUiState,
    onEvent: (PoEvent) -> Unit,
): List<TableColumn<PurchaseOrder>> = buildList {
    if (state.viewer.isAccountant) {
        add(
            TableColumn(
                header = "",
                width = ColumnWidth.Fixed(CHECK_COLUMN),
                cell = { row ->
                    ZillitCheckbox(
                        checked = row.id in state.selection,
                        onCheckedChange = { onEvent(PoEvent.ToggleSelection(row.id)) },
                    )
                },
            ),
        )
    }
    addAll(orderColumns())
}

private const val RECENT_ROWS = 8
private const val ID_FALLBACK = 8
private const val LIST_WEIGHT = 1.5f
private const val DETAIL_WEIGHT = 1f
private const val LINE_DESCRIPTION_WEIGHT = 2f
private const val LINE_SMALL_WEIGHT = 0.6f
private val SEARCH_WIDTH = 320.dp
private val STATUS_COLUMN = 150.dp
private val CHECK_COLUMN = 40.dp
