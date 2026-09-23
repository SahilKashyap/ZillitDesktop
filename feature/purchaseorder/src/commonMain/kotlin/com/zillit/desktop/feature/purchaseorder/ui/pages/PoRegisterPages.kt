package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PoDeliveryAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoTemplate
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState

/**
 * The Templates tab — saved order shapes.
 *
 * Two actions per row and the difference between them matters: **Use** raises a
 * new order from the template, **Edit** changes the template itself. The web
 * puts them side by side and so does this.
 */
@Composable
internal fun PoTemplatesPage(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val rows = state.templates.filter { it.matches(state.search) }
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitSectionCard(
            title = str(S.templates),
            icon = ZillitIcons.Grid,
            meta = str(
                if (rows.size == 1) S.desktop_po_template_count_one else S.desktop_po_template_count_other,
                rows.size,
            ),
            padded = false,
            modifier = Modifier.fillMaxWidth(),
            action = {
                ZillitButton(
                    text = str(S.dm_template_new),
                    onClick = { onEvent(PoEvent.CreateTemplate) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            },
        ) {
            ZillitDataTable(
                rows = rows,
                columns = templateColumns(state, onEvent),
                key = { it.id },
                loading = state.templatesLoading,
                virtualised = false,
                emptyTitle = if (state.search.isBlank()) {
                    str(S.desktop_po_no_templates_yet)
                } else {
                    str(S.desktop_po_no_templates_match)
                },
                emptyMessage = str(S.desktop_po_templates_empty_message),
            )
        }
    }
}

private fun templateColumns(state: PoUiState, onEvent: (PoEvent) -> Unit): List<TableColumn<PoTemplate>> = listOf(
    textColumn(header = str(S.txt_template)) { it.name },
    textColumn(header = str(S.ah_lbl_vendor)) { template ->
        template.vendorName.ifBlank {
            state.vendors.firstOrNull { it.id == template.vendorId }?.name ?: str(S.desktop_po_no_vendor)
        }
    },
    textColumn(header = str(S.department), width = ColumnWidth.Fixed(DEPT_COLUMN), muted = true) {
        state.departmentName(it.departmentId).ifBlank { "—" }
    },
    textColumn(
        header = str(S.desktop_po_lines_column),
        width = ColumnWidth.Fixed(LINES_WIDTH),
        numeric = true,
    ) { it.lines.size.toString() },
    textColumn(header = str(S.amount), width = ColumnWidth.Fixed(AMOUNT_WIDTH), numeric = true) {
        Money.format(it.total, it.currency)
    },
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(REGISTER_ACTIONS),
        cell = { template ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitButton(
                    text = str(S.recce_use),
                    onClick = { onEvent(PoEvent.UseTemplate(template.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = str(S.edit),
                    onClick = { onEvent(PoEvent.EditTemplate(template.id)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    text = str(S.delete),
                    onClick = { onEvent(PoEvent.DeleteTemplate(template.id)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            }
        },
    ),
)

private fun PoTemplate.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return name.lowercase().contains(needle) ||
        vendorName.lowercase().contains(needle) ||
        description.lowercase().contains(needle)
}

/**
 * The PO Drafts tab — orders saved without submitting.
 *
 * Private to whoever wrote them, which is why they have their own tab rather
 * than a chip on All POs. Resume opens the form on the draft; Delete removes
 * it. Orders still in the outbox appear here too, because that is the same
 * situation to the person looking: something they started and have not sent.
 */
@Composable
internal fun PoDraftsPage(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val rows = state.rows
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitStatTile(
                label = str(S.desktop_po_total_drafts),
                value = rows.size.toString(),
                icon = ZillitIcons.File,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.desktop_po_draft_value),
                value = rows.totalValue(),
                sub = rows.currencyNote(),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Wallet,
                modifier = Modifier.weight(1f),
            )
        }
        ZillitSectionCard(
            title = str(S.ah_tab_po_drafts),
            icon = ZillitIcons.Edit,
            padded = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitDataTable(
                rows = rows,
                columns = draftColumns(state, onEvent),
                key = { it.id },
                loading = state.loading,
                virtualised = false,
                emptyTitle = if (state.search.isBlank()) {
                    str(S.desktop_po_no_drafts)
                } else {
                    str(S.desktop_po_no_drafts_match)
                },
                emptyMessage = str(S.desktop_po_drafts_empty_message),
            )
        }
    }
}

private fun draftColumns(state: PoUiState, onEvent: (PoEvent) -> Unit): List<TableColumn<PurchaseOrder>> = listOf(
    textColumn(header = str(S.description)) { it.description.ifBlank { str(S.untitled) } },
    textColumn(header = str(S.ah_lbl_vendor)) { state.vendorName(it).ifBlank { str(S.desktop_po_no_vendor) } },
    textColumn(header = str(S.amount), width = ColumnWidth.Fixed(AMOUNT_WIDTH), numeric = true) {
        Money.format(it.gross, it.currency)
    },
    textColumn(header = str(S.saved), width = ColumnWidth.Fixed(DATE_WIDTH), muted = true) {
        EpochDate.date(it.updatedAt ?: it.createdAt).ifBlank { "—" }
    },
    TableColumn(
        header = str(S.status),
        width = ColumnWidth.Fixed(STATUS_WIDTH),
        cell = { order ->
            ZillitStatusPill(
                label = if (order.isLocalOnly) str(S.desktop_waiting_to_send) else order.status.label,
                tone = if (order.isLocalOnly) StatusTone.Pending else order.status.tone(),
            )
        },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(REGISTER_ACTIONS),
        cell = { order ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                // An order in the outbox has no server record to open — the
                // outbox owns its retry, and offering Resume here would open a
                // form on an id that does not exist yet.
                if (!order.isLocalOnly) {
                    ZillitButton(
                        text = str(S.desktop_resume),
                        onClick = { onEvent(PoEvent.ResumeDraft(order.id)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                    ZillitButton(
                        text = str(S.delete),
                        onClick = {
                            onEvent(
                                PoEvent.Ask(
                                    com.zillit.desktop.feature.purchaseorder.ui.PoPrompt.Confirm(
                                        action = com.zillit.desktop.feature.purchaseorder.ui.PoConfirmAction.Delete,
                                        targetId = order.id,
                                        title = str(S.ah_delete_draft),
                                        message = str(S.desktop_po_draft_will_be_removed),
                                        destructive = true,
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Danger,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                }
            }
        },
    ),
)

/**
 * The Delivery Addresses tab — the production's address book.
 *
 * Saved off orders and picked back into them, so a stage address is typed once.
 * The pencil is disabled on rows this viewer did not create, with the reason on
 * it: the server refuses the same edit with a 403, and finding that out after
 * typing an address is worse than being told before.
 */
@Composable
internal fun PoAddressesPage(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val rows = state.addresses.filter { it.matches(state.search) }
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitSectionCard(
            title = str(S.desktop_delivery_addresses),
            icon = ZillitIcons.Home,
            meta = str(
                if (rows.size == 1) S.desktop_po_address_count_one else S.desktop_po_address_count_other,
                rows.size,
            ),
            padded = false,
            modifier = Modifier.fillMaxWidth(),
            action = {
                ZillitButton(
                    text = str(S.desktop_po_add_address),
                    onClick = { onEvent(PoEvent.AddAddress) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            },
        ) {
            ZillitDataTable(
                rows = rows,
                columns = addressColumns(state, onEvent),
                key = { it.id },
                loading = state.addressesLoading,
                virtualised = false,
                emptyTitle = if (state.search.isBlank()) {
                    str(S.desktop_po_no_addresses_yet)
                } else {
                    str(S.desktop_po_no_addresses_match)
                },
                emptyMessage = str(S.desktop_po_addresses_empty_message),
            )
        }
    }
}

private fun addressColumns(
    state: PoUiState,
    onEvent: (PoEvent) -> Unit,
): List<TableColumn<PoDeliveryAddress>> = listOf(
    TableColumn(
        header = str(S.contact),
        cell = { row ->
            Column {
                ZillitText(
                    text = row.address.name.ifBlank { str(S.desktop_po_no_recipient) },
                    style = ZillitTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                if (row.address.email.isNotBlank() || row.address.phone.isNotBlank()) {
                    ZillitText(
                        text = listOf(row.address.email, row.address.phone).filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        },
    ),
    textColumn(header = str(S.address)) { it.address.oneLine.ifBlank { it.label } },
    textColumn(header = str(S.drive_created), width = ColumnWidth.Fixed(DATE_WIDTH), muted = true) {
        EpochDate.date(it.createdAt).ifBlank { "—" }
    },
    textColumn(header = str(S.desktop_po_last_updated), width = ColumnWidth.Fixed(DATE_WIDTH), muted = true) {
        EpochDate.date(it.updatedAt).ifBlank { "—" }
    },
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(EDIT_WIDTH),
        cell = { row ->
            val mine = row.editableBy(state.viewer)
            ZillitButton(
                text = if (mine) {
                    str(S.desktop_po_edit_address)
                } else {
                    str(S.desktop_po_only_creator_or_accountant)
                },
                onClick = { onEvent(PoEvent.EditAddressRow(row.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = mine,
                leadingIcon = if (mine) ZillitIcons.Edit else null,
            )
        },
    ),
)

private fun PoDeliveryAddress.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return label.lowercase().contains(needle) ||
        address.name.lowercase().contains(needle) ||
        address.oneLine.lowercase().contains(needle) ||
        address.email.lowercase().contains(needle)
}

private val LINES_WIDTH = 80.dp
private val REGISTER_ACTIONS = 230.dp
private val EDIT_WIDTH = 300.dp
