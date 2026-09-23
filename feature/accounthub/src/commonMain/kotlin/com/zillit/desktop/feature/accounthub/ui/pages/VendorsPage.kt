package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.BankAccounts
import com.zillit.desktop.feature.accounthub.domain.IsdCountries
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.VendorAddress
import com.zillit.desktop.feature.accounthub.domain.VendorChange
import com.zillit.desktop.feature.accounthub.domain.VendorTerms
import com.zillit.desktop.feature.accounthub.domain.sanitisePhone
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.VendorsState
import com.zillit.desktop.feature.accounthub.ui.UNKNOWN_PERSON
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.VendorFilter
import com.zillit.desktop.feature.accounthub.ui.VendorFormPage
import com.zillit.desktop.feature.accounthub.ui.components.HubPageHeader
import com.zillit.desktop.feature.accounthub.ui.components.CoaCodeField
import com.zillit.desktop.feature.accounthub.ui.components.quickCreateHandler
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.HubConfirmDialog
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.SubCard
import com.zillit.desktop.feature.accounthub.ui.components.TipBanner
import com.zillit.desktop.feature.accounthub.ui.components.TypedDetailsEditor

/**
 * The vendor register — the web's `VendorsModule`.
 *
 * ## Verification is a status, not a checkbox
 *
 * The service sends a status string and no boolean, so "verified" is derived at
 * the data boundary. Reading the raw field is how the web's register once
 * showed every vendor as unverified at the same time — and this is the screen
 * where that would be believed.
 *
 * ## Three surfaces
 *
 * The table, a detail modal with the bank block masked until revealed, and a
 * full-page form — the web's shapes, kept because a vendor record is long
 * enough that a docked panel truncated it.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
fun VendorsPage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val vendors = state.vendors
    if (vendors.page != null) {
        VendorFormScreen(state, vendors.page, onEvent)
        return
    }

    HubPage {
        HubPageHeader(
            eyebrow = str(S.desktop_management),
            title = str(S.ah_vendors),
            description = str(S.desktop_hub_manage_supplier_records_verify_vendor_details_and_maintain_your_approved),
            actions = {
                // Anyone who may post, as the web: a department user's "Added by
                // Me" tab would otherwise list vendors they had no way to add.
                if (state.viewer.mayAddVendor) {
                    ZillitButton(
                        text = str(S.ah_add_vendor),
                        onClick = { onEvent(AccountHubEvent.ComposeVendor()) },
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                    )
                }
            },
        )

        ZillitSearchField(
            value = vendors.search,
            onValueChange = { onEvent(AccountHubEvent.SearchVendors(it)) },
            placeholder = str(S.desktop_hub_search_vendors_by_name_contact_email_tax_number),
            modifier = Modifier.fillMaxWidth(),
        )

        // The web's tabs, with "Added by Me" for a department user.
        val tabs = VendorFilter.entries.filter { it != VendorFilter.Mine || !state.viewer.isAccountant }
        ZillitTabStrip(
            // A department user's first tab is plain "All", beside "Added by Me" (the web's).
            tabs = tabs.map { filter ->
                val label = if (filter == VendorFilter.All && !state.viewer.isAccountant) str(S.all) else filter.label
                ZillitTab(filter.slug, label, count = vendors.countFor(filter))
            },
            activeId = vendors.filter.slug,
            onSelect = { slug ->
                VendorFilter.entries.firstOrNull { it.slug == slug }?.let { onEvent(AccountHubEvent.FilterVendors(it)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            SubCard(padded = false, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f)) {
                    ZillitDataTable(
                        rows = state.visibleVendors,
                        key = { it.id },
                        loading = vendors.loading,
                        columns = vendorColumns(state, onEvent),
                        onRowClick = { row -> onEvent(AccountHubEvent.OpenVendorDetail(row.id)) },
                        isSelected = { it.id == vendors.detailId },
                        emptyTitle =
                            if (vendors.isFiltered) str(S.desktop_hub_no_vendors_match_your_filter)
                            else str(S.desktop_no_vendors_yet),
                        emptyMessage =
                            if (vendors.isFiltered) null
                            else str(S.desktop_hub_vendors_added_here_appear_in_every_purchase_order_and_invoice),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth().padding(
                    horizontal = ZillitTheme.spacing.lg,
                    vertical = ZillitTheme.spacing.sm,
                )) {
                    FieldHint("${state.visibleVendors.size} vendor${if (state.visibleVendors.size == 1) "" else "s"}")
                }
            }
            if (vendors.historyFor != null) HistoryPanel(state, onEvent)
        }
    }

    VendorDetailDialog(state, onEvent)
    HubConfirmDialog(
        visible = vendors.confirmDelete != null,
        title = str(S.ah_dialog_delete_vendor_title),
        message = "Delete \"${vendors.confirmDelete?.display.orEmpty()}\"? A vendor still named on a purchase " +
            "order is refused by the server.",
        confirmLabel = str(S.delete),
        onConfirm = { vendors.confirmDelete?.let { onEvent(AccountHubEvent.DeleteVendor(it.id)) } },
        onDismiss = { onEvent(AccountHubEvent.AskDeleteVendor(null)) },
    )
}

@Suppress("LongMethod") // A table of columns; splitting it separates each from its width.
@OptIn(ExperimentalLayoutApi::class)
private fun vendorColumns(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
): List<TableColumn<Vendor>> = listOf(
    TableColumn(
        header = str(S.cash_receipt_vendor_hint),
        width = ColumnWidth.Weight(NAME_WEIGHT),
        cell = { row ->
            Row(
                // Greyed while its delete is in flight, as the web's row.
                modifier = Modifier.alpha(if (row.id in state.vendors.deletingIds) DELETING_ALPHA else 1f),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitAvatar(name = row.display)
                Column {
                    // The web's `flex-wrap`: when the column is narrow — the history
                    // panel open, a small window — the badge drops under the name
                    // instead of the name collapsing to "Ba…" beside it.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitText(
                            text = row.display,
                            maxLines = 2,
                            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        VerificationPill(row.verified)
                    }
                    // City and postcode, the web's `formatAddressShort`.
                    val short = listOf(row.address.city, row.address.postalCode)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                    if (short.isNotBlank()) FieldHint(short)
                }
            }
        },
    ),
    TableColumn(
        header = str(S.contact_person_value),
        width = ColumnWidth.Weight(1f),
        cell = { row ->
            Column {
                ZillitText(text = row.contactPerson.ifBlank { "—" }, maxLines = 1)
                FieldHint(listOfNotNull(
                    row.email.takeIf { it.isNotBlank() },
                    row.phone?.display?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "—" })
            }
        },
    ),
    TableColumn(
        header = str(S.department),
        width = ColumnWidth.Fixed(DEPT_COLUMN),
        cell = { row ->
            // A department nobody can name is a dash, not its id.
            val department = row.departmentId?.let { state.departmentName(it) }.orEmpty()
            if (department.isNotBlank()) Pill(department) else FieldHint("—")
        },
    ),
    TableColumn(
        header = str(S.ah_lbl_added_by),
        width = ColumnWidth.Fixed(ADDED_COLUMN),
        cell = { row ->
            val user = row.addedBy?.let { state.user(it) }
            if (user != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitAvatar(name = user.name, userId = user.id, size = 22.dp)
                    Column {
                        ZillitText(text = user.name, style = ZillitTheme.typography.bodySmall, maxLines = 1)
                        // A job title arrives as a key too (`director_label`).
                        if (user.designation.isNotBlank()) FieldHint(user.designation.localised())
                    }
                }
            } else {
                // Never the id: a person nobody can name reads as a dash.
                FieldHint("—")
            }
        },
    ),
    TableColumn(
        header = str(S.dd_actions),
        width = ColumnWidth.Fixed(ACTION_COLUMN),
        cell = { row ->
            if (row.id in state.vendors.deletingIds) {
                ZillitSpinner(size = 16.dp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    // Per row, as the web: the accounts team may change any vendor,
                    // and whoever added one may change theirs.
                    if (state.viewer.mayModifyVendor(row)) {
                        ZillitIconButton(
                            icon = ZillitIcons.Edit,
                            contentDescription = str(S.edit),
                            onClick = { onEvent(AccountHubEvent.ComposeVendor(row)) },
                        )
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = str(S.delete),
                            onClick = { onEvent(AccountHubEvent.AskDeleteVendor(row)) },
                            tint = ZillitTheme.colors.danger,
                        )
                    }
                    ZillitIconButton(
                        icon = ZillitIcons.Clock,
                        contentDescription = str(S.history),
                        onClick = { onEvent(AccountHubEvent.OpenVendorHistory(row.id)) },
                    )
                    if (!state.viewer.isAccountant && state.viewer.mayList("purchase_order_tool")) {
                        ZillitButton(
                            text = str(S.ah_create_po),
                            onClick = { onEvent(AccountHubEvent.CreatePurchaseOrder(row.id)) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    }
                }
            }
        },
    ),
)

// -- the detail modal ---------------------------------------------------------------

/** The web's `VendorDetailModal`: contact grid, address, details, masked bank block, audit row. */
@Suppress("CyclomaticComplexMethod", "LongMethod") // One vendor, top to bottom; the order is the reading order.
@Composable
private fun VendorDetailDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val vendors = state.vendors
    val vendor = vendors.detail
    ZillitDialogShell(
        title = vendor?.display.orEmpty(),
        icon = ZillitIcons.Users,
        visible = vendor != null,
        onDismiss = { onEvent(AccountHubEvent.OpenVendorDetail(null)) },
        width = DETAIL_WIDTH,
        actions = {
            if (vendor != null) {
                ZillitButton(
                    text = str(S.desktop_vendor_history),
                    onClick = {
                        onEvent(
                            AccountHubEvent.OpenVendorDetail(null),
                        ); onEvent(AccountHubEvent.OpenVendorHistory(vendor.id))
                    },
                    variant = ButtonVariant.Tertiary,
                    leadingIcon = ZillitIcons.Clock,
                )
                if (state.viewer.mayModifyVendor(vendor)) {
                    ZillitButton(
                        text = str(S.desktop_edit_vendor_details),
                        onClick = { onEvent(AccountHubEvent.ComposeVendor(vendor)) },
                        variant = ButtonVariant.Secondary,
                        leadingIcon = ZillitIcons.Edit,
                    )
                }
                if (!vendor.verified && state.viewer.canActAsAccountant) {
                    ZillitButton(
                        text =
                            if (vendors.verifyingId == vendor.id) str(S.desktop_verifying)
                            else str(S.desktop_mark_verified),
                        onClick = { onEvent(AccountHubEvent.VerifyVendor(vendor.id)) },
                        loading = vendors.verifyingId == vendor.id,
                        leadingIcon = ZillitIcons.Tick,
                    )
                }
            }
        },
    ) {
        if (vendor == null) return@ZillitDialogShell
        // The web's header badge, large, where a subtitle only said it in words.
        VerificationPill(vendor.verified)
        ZillitSectionLabel(str(S.contact))
        DetailGrid(
            listOf(
                str(S.contact_person_value) to vendor.contactPerson,
                str(S.email) to vendor.email,
                str(S.department) to state.departmentName(vendor.departmentId),
                str(S.phone) to vendor.phone?.display.orEmpty(),
                str(S.dm_loanout_vat) to vendor.vatNumber,
                str(S.desktop_default_code) to vendor.defaultCode,
            ),
        )
        // No address, no section — the web's, where a dash under a heading
        // read as an address that failed to load.
        if (vendor.address.oneLine.isNotBlank()) {
            ZillitSectionLabel(str(S.address))
            ZillitText(text = vendor.address.oneLine, style = ZillitTheme.typography.bodyMedium)
        }
        if (vendor.hasClassification) {
            ZillitSectionLabel(str(S.details))
            DetailGrid(
                listOfNotNull(
                    vendor.vendorType.takeIf { it.isNotBlank() }?.let { str(S.type) to it },
                    vendor.companyType.takeIf { it.isNotBlank() }?.let { str(S.desktop_company_type) to it },
                    vendor.terms.takeIf { it.isNotBlank() }?.let { str(S.desktop_terms) to VendorTerms.labelFor(it) },
                    vendor.defaultCode.takeIf { it.isNotBlank() }?.let { str(S.desktop_default_code) to it },
                    vendor.compliance.takeIf { it.isNotBlank() }?.let { str(S.dm_section_compliance) to it },
                ),
            )
        }
        VendorBankBlock(vendors, onEvent)
        ZillitSectionLabel(str(S.desktop_audit))
        // Two columns, as the web's audit grid. "Verified by" whenever someone
        // is recorded, as there — not only while the vendor is still verified.
        val audits = listOfNotNull(
            AuditEntry(str(S.ah_lbl_added_by), vendor.addedBy, vendor.createdAtMillis),
            vendor.verifiedBy?.takeIf { it.isNotBlank() }
                ?.let { AuditEntry(str(S.ah_lbl_verified_by), it, vendor.verifiedAtMillis, verified = true) },
            vendor.updatedBy?.let { AuditEntry(str(S.dm_nda_col_last_updated), it, vendor.updatedAtMillis) },
        )
        audits.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
            ) {
                pair.forEach { AuditRow(it, state, Modifier.weight(1f)) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

private class AuditEntry(val label: String, val userId: String?, val at: Long?, val verified: Boolean = false)

/**
 * The detail's bank block, read from wherever the vendor's details live.
 *
 * The linked bank record first, the row's legacy copy after — see
 * `VendorBank`. Waits for the record rather than drawing the row's empty copy
 * and then replacing it, which made a vendor with details on file flash "no
 * bank details" every time it was opened.
 */
@Composable
private fun VendorBankBlock(vendors: VendorsState, onEvent: (AccountHubEvent) -> Unit) {
    if (vendors.bankRecordLoading) {
        ZillitSectionLabel(str(S.ah_section_bank))
        FieldHint(str(S.desktop_loading_bank_details))
        return
    }
    val bank = vendors.detailBank?.takeUnless { it.isEmpty } ?: return
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitSectionLabel(str(S.ah_section_bank), modifier = Modifier.weight(1f))
        ZillitButton(
            text = if (vendors.bankRevealed) str(S.hide) else str(S.desktop_reveal),
            onClick = { onEvent(AccountHubEvent.RevealVendorBank(!vendors.bankRevealed)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Eye,
        )
    }
    val mask: (String) -> String = { if (vendors.bankRevealed) it else BankAccounts.masked(it) }
    DetailGrid(
        listOfNotNull(
            bank.bankName.takeIf { it.isNotBlank() }?.let { str(S.bank_name_label) to it },
            bank.accountHolderName.takeIf { it.isNotBlank() }?.let { str(S.account_holder) to it },
            bank.accountNumber.takeIf { it.isNotBlank() }?.let { str(S.account_number) to mask(it) },
            bank.sortCode.takeIf { it.isNotBlank() }?.let { str(S.ah_lbl_sort_code) to mask(it) },
            bank.ibanCode.takeIf { it.isNotBlank() }?.let { "IBAN" to mask(it) },
            bank.swiftCode.takeIf { it.isNotBlank() }?.let { "SWIFT" to mask(it) },
        ) + bank.additionalInfo.filter { it.isTitled }.map { it.title to mask(it.value) },
    )
    if (vendors.bankRevealed) FieldHint(str(S.desktop_hub_re_masks_in_5_seconds))
}

@Composable
private fun DetailGrid(fields: List<Pair<String, String>>) {
    fields.chunked(DETAIL_COLUMNS).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            row.forEach { (label, value) ->
                Column(modifier = Modifier.weight(1f)) {
                    MonoLabel(label)
                    ZillitText(text = value.ifBlank { "—" }, style = ZillitTheme.typography.bodyMedium, maxLines = 2)
                }
            }
            repeat(DETAIL_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
        }
    }
}

/** The web's audit row: a 32px face (ticked when it verified), the name, their job title, the time. */
@Composable
private fun AuditRow(entry: AuditEntry, state: AccountHubUiState, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        MonoLabel(entry.label)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            val name = entry.userId?.let { state.userName(it) } ?: UNKNOWN_PERSON
            // Initials for a dash would invent a person.
            if (name != UNKNOWN_PERSON) {
                Box {
                    ZillitAvatar(name = name, userId = entry.userId, size = AUDIT_FACE)
                    if (entry.verified) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(VERIFIED_TICK)
                                .clip(CircleShape)
                                .background(colors.surface)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(colors.info),
                            contentAlignment = Alignment.Center,
                        ) {
                            ZillitIcon(icon = ZillitIcons.Tick, tint = colors.textOnAccent, size = 8.dp)
                        }
                    }
                }
            }
            Column {
                ZillitText(
                    text = name,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                )
                val role = entry.userId?.let { state.user(it)?.designation?.localised() }.orEmpty()
                if (role.isNotBlank()) FieldHint(role)
                EpochDate.dateTime(entry.at).takeIf { it.isNotBlank() }?.let { MonoLabel(it) }
            }
        }
    }
}

// -- the history panel -------------------------------------------------------------------

/**
 * The web's `HistoryPanel`: "Vendor History", then a timeline — an orange dot
 * on a rule per action, the action title-cased, "by user (designation)" and
 * the time under it. A failed read says so here rather than reading as a
 * vendor with no history.
 */
@Suppress("LongMethod") // A panel, read top to bottom; the order is the reading order.
@Composable
private fun HistoryPanel(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val vendors = state.vendors
    val vendor = vendors.rows.firstOrNull { it.id == vendors.historyFor }
    val colors = ZillitTheme.colors
    SubCard(
        title = str(S.desktop_vendor_history),
        hint = vendor?.display,
        action = { ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.desktop_close_history),
            onClick = { onEvent(AccountHubEvent.OpenVendorHistory(null)) },
        ) },
        modifier = Modifier.width(HISTORY_WIDTH).fillMaxHeight(),
    ) {
        val error = vendors.historyError
        when {
            vendors.historyLoading -> ZillitSpinner()
            error != null -> ZillitText(text = error, style = ZillitTheme.typography.bodySmall, color = colors.danger)
            vendors.history.isEmpty() -> FieldHint(str(S.desktop_no_recorded_changes))
        }
        ZillitScrollColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            vendors.history.forEachIndexed { index, change ->
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    // The rail and its dot; the rail stops at the last entry.
                    Box(modifier = Modifier.width(TIMELINE_GUTTER).fillMaxHeight()) {
                        if (index < vendors.history.lastIndex) {
                            Box(
                                Modifier
                                    .padding(start = TIMELINE_RAIL_START, top = TIMELINE_DOT_TOP)
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(colors.border),
                            )
                        }
                        Box(
                            Modifier
                                .padding(top = TIMELINE_DOT_TOP)
                                .size(TIMELINE_DOT)
                                .clip(CircleShape)
                                .background(colors.accent),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f).padding(bottom = ZillitTheme.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                    ) {
                        ZillitText(
                            text = change.summary.ifBlank { str(S.history_changed) },
                            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 2,
                        )
                        FieldHint(historyActor(state, change))
                        EpochDate.dateTime(change.at).takeIf { it.isNotBlank() }?.let { FieldHint(it) }
                        if (change.note.isNotBlank()) FieldHint(change.note)
                    }
                }
            }
        }
    }
}

/**
 * "by Name (Designation)" — or "by System" for the server's own transitions,
 * which the web humanises from the `system` sentinel instead of leaking it.
 */
private fun historyActor(state: AccountHubUiState, change: VendorChange): String {
    val system = change.byId.isBlank() || change.byId.equals(SYSTEM_ACTOR, ignoreCase = true)
    val actor = change.byName.ifBlank {
        if (system) str(S.tm_system) else state.userName(change.byId)
    }
    val role = change.byId.takeIf { !system }?.let { state.user(it)?.designation?.localised() }.orEmpty()
    return if (role.isNotBlank()) "by $actor ($role)" else "by $actor"
}

// -- the full-page form ------------------------------------------------------------------

/**
 * The web's `VendorForm`: an eyebrow, the info banner, three cards — Vendor
 * Details, Address, Bank Details — and a top bar with Cancel and Save /
 * Create, plus "Save & Verify" for an accountant on an unverified vendor.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // A form, read top to bottom; the order is the reading order.
@Composable
private fun VendorFormScreen(state: AccountHubUiState, page: VendorFormPage, onEvent: (AccountHubEvent) -> Unit) {
    val draft = page.draft
    val editing = page.editingId?.let { id -> state.vendors.rows.firstOrNull { it.id == id } }
    val offerVerify = editing != null && !editing.verified && state.viewer.canActAsAccountant
    fun update(next: NewVendor) = onEvent(AccountHubEvent.UpdateVendorDraft(next))
    fun address(edit: VendorAddress.() -> VendorAddress) = update(draft.copy(address = draft.address.edit()))
    val colors = ZillitTheme.colors

    HubPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitIconButton(
                icon = ZillitIcons.ArrowLeft,
                contentDescription = str(S.desktop_hub_back_to_vendors_list),
                onClick = { onEvent(AccountHubEvent.DismissVendorForm) },
            )
            Column(modifier = Modifier.weight(1f)) {
                MonoLabel(
                    if (editing == null) str(S.desktop_vendors_new_vendor)
                    else str(S.desktop_hub_vendors_edit_vendor_details),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Shrinks before the badge does: a long name ellipsises, the badge stays.
                    ZillitText(
                        text = if (editing == null) str(S.desktop_new_vendor) else editing.display,
                        style = ZillitTheme.typography.titleLarge,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // The web's edit eyebrow carries the vendor's badge beside its name.
                    if (editing != null) VerificationPill(editing.verified)
                }
                FieldHint(if (editing == null) str(S.ah_all_fields_required) else str(S.desktop_edit_vendor_details))
            }
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(AccountHubEvent.DismissVendorForm) },
                variant = ButtonVariant.Tertiary,
                enabled = !page.saving && !page.verifying,
            )
            // One or the other, as the web: an accountant editing an
            // unverified vendor saves by verifying it.
            if (offerVerify) {
                ZillitButton(
                    text = str(S.desktop_save_verify),
                    onClick = { onEvent(AccountHubEvent.SaveAndVerifyVendor) },
                    loading = page.verifying,
                    enabled = !page.saving && !page.verifying,
                    leadingIcon = ZillitIcons.Tick,
                )
            } else {
                ZillitButton(
                    text = if (editing == null) str(S.ah_create_vendor) else str(S.ah_save_changes),
                    onClick = { onEvent(AccountHubEvent.SaveVendor) },
                    loading = page.saving,
                    enabled = !page.saving && !page.verifying,
                )
            }
        }
        TipBanner(
            when {
                editing == null && state.viewer.isAccountant -> str(S.ah_vendor_non_accountant_info)
                editing == null -> str(S.desktop_hub_vendors_created_here_will_be_marked_non_verified)
                // Who is editing decides it on the web, not the vendor's state:
                // an accountant's edit keeps the verification it has.
                state.viewer.isAccountant ->
                    str(S.desktop_hub_update_the_vendor_information_below_changes_are_saved_immediately)
                else -> str(S.desktop_hub_update_the_vendor_information_below_changes_will_reset_verification_to)
            },
        )
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            SubCard(title = str(S.desktop_vendor_details)) {
                FieldGrid {
                    FormField(
                        page,
                        "name",
                        str(S.ah_lbl_vendor_company_name),
                        required = true,
                    ) {
                        ZillitTextField(
                            value = draft.name,
                            onValueChange = { update(draft.copy(name = it)) },
                            placeholder = str(S.ah_vendor_name_hint),
                            errorText = page.errorFor("name"),
                        )
                    }
                    FormField(
                        page,
                        "contactPerson",
                        str(S.contact_person_value),
                        required = true,
                    ) {
                        ZillitTextField(
                            value = draft.contactPerson,
                            onValueChange = { update(draft.copy(contactPerson = it)) },
                            placeholder = str(S.ah_contact_name_hint),
                            errorText = page.errorFor("contactPerson"),
                        )
                    }
                    FormField(page, "email", str(S.email), required = true) {
                        ZillitTextField(
                            value = draft.email,
                            onValueChange = { update(draft.copy(email = it)) },
                            placeholder = str(S.ah_email_hint),
                            errorText = page.errorFor("email"),
                        )
                    }
                    FormField(page, "phoneNumber", str(S.phone)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                            // Unset until picked (ZL-20520): the placeholder hints at a
                            // code, the draft holds none, and a number saves without one.
                            HubSelect(
                                value = IsdCountries.forDial(
                                    state.vendors.countries,
                                    draft.phoneCountryCode,
                                    draft.address.country,
                                ),
                                options = state.vendors.countries,
                                label = { it.dialCode },
                                secondary = { it.name },
                                onSelect = { picked ->
                                    update(draft.copy(phoneCountryCode = picked?.dialCode ?: draft.phoneCountryCode))
                                },
                                placeholder = "+44",
                                modifier = Modifier.width(DIAL_WIDTH),
                            )
                            ZillitTextField(
                                value = draft.phoneNumber,
                                onValueChange = { update(draft.copy(phoneNumber = sanitisePhone(it))) },
                                placeholder = "e.g. 1753 651700",
                                errorText = page.errorFor("phoneNumber"),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    FormField(
                        page,
                        "vatNumber",
                        str(S.dm_loanout_vat),
                        optional = true,
                    ) {
                        ZillitTextField(
                            value = draft.vatNumber,
                            onValueChange = { update(draft.copy(vatNumber = it)) },
                            maxLength = TAX_NUMBER_MAX,
                            placeholder = str(S.ah_vat_hint),
                        )
                    }
                    FormField(page, "departmentId", str(S.department)) {
                        HubSelect(
                            value = state.departmentList.firstOrNull { it.id == draft.departmentId },
                            options = state.departmentList,
                            label = { it.name.localised() },
                            onSelect = { update(draft.copy(departmentId = it?.id)) },
                            placeholder = str(S.ah_select_department),
                            clearable = true,
                        )
                    }
                    FormField(
                        page,
                        "companyType",
                        str(S.desktop_company_type),
                        optional = true,
                    ) {
                        // Free text, as the web's: a fixed list could neither show
                        // nor keep a type typed there ("Ltd"), and clearing it lost it.
                        ZillitTextField(
                            value = draft.companyType,
                            onValueChange = { update(draft.copy(companyType = it)) },
                            placeholder = str(S.ah_company_type_hint),
                        )
                    }
                    FormField(page, "terms", str(S.desktop_terms), optional = true) {
                        HubSelect(
                            value = VendorTerms.entries.firstOrNull { it.wire == draft.terms },
                            options = VendorTerms.entries.toList(),
                            label = { it.label },
                            onSelect = { update(draft.copy(terms = it?.wire.orEmpty())) },
                            placeholder = str(S.ah_select_terms),
                            clearable = true,
                            searchable = false,
                        )
                    }
                    if (state.viewer.isAccountant) {
                        FormField(
                            page,
                            "defaultCode",
                            str(S.desktop_default_code),
                            optional = true,
                        ) {
                            CoaCodeField(
                                value = draft.defaultCode,
                                onValueChange = { update(draft.copy(defaultCode = it)) },
                                accounts = state.chart.accounts,
                                placeholder = "e.g. 5001",
                                onCreate = quickCreateHandler(state, onEvent),
                            )
                        }
                    }
                }
            }

            SubCard(title = str(S.address)) {
                FieldGrid {
                    FormField(page, "line1", str(S.address_line_1), required = true) {
                        ZillitTextField(
                            value = draft.address.line1,
                            onValueChange = { text -> address { copy(line1 = text) } },
                            placeholder = str(S.ah_street_hint),
                            errorText = page.errorFor("line1"),
                        )
                    }
                    FormField(page, "line2", str(S.address_line_2), optional = true) {
                        ZillitTextField(
                            value = draft.address.line2,
                            onValueChange = { text -> address { copy(line2 = text) } },
                            placeholder = str(S.ah_suite_hint),
                        )
                    }
                    FormField(page, "city", str(S.city), required = true) {
                        ZillitTextField(
                            value = draft.address.city,
                            onValueChange = { text -> address { copy(city = text) } },
                            placeholder = str(S.ah_city_hint),
                            errorText = page.errorFor("city"),
                            trailingContent = postcodeSpinner(page),
                        )
                    }
                    FormField(
                        page,
                        "state",
                        str(S.ah_lbl_state_county_row),
                        optional = true,
                    ) {
                        ZillitTextField(
                            value = draft.address.state,
                            onValueChange = { text -> address { copy(state = text) } },
                            placeholder = str(S.ah_county_hint),
                            trailingContent = postcodeSpinner(page),
                        )
                    }
                    FormField(
                        page,
                        "postalCode",
                        str(S.desktop_postal_zip_code),
                        required = true,
                    ) {
                        ZillitTextField(
                            value = draft.address.postalCode,
                            onValueChange = { text -> address { copy(postalCode = text) } },
                            placeholder = str(S.ah_postcode_hint),
                            errorText = page.errorFor("postalCode"),
                        )
                    }
                    FormField(page, "country", str(S.country), required = true) {
                        // Unset until picked (ZL-20520), so "Country is required" can
                        // catch a skipped field instead of every vendor saving as UK.
                        HubSelect(
                            value = IsdCountries.forName(state.vendors.countries, draft.address.country),
                            options = state.vendors.countries,
                            label = { it.name },
                            onSelect = { picked -> address { copy(country = picked?.name ?: draft.address.country) } },
                            placeholder = str(S.ah_select_country),
                        )
                        page.errorFor("country")?.let {
                            ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = colors.danger)
                        }
                    }
                }
            }

            SubCard(
                title = str(S.ah_section_bank),
                hint = when {
                    page.bankLoading -> str(S.desktop_hub_loading_the_bank_details_on_file)
                    else -> str(S.desktop_hub_optional_masked_on_the_detail_view_until_revealed)
                },
                action = {
                    // Only a bank record that exists can be deleted — the web's
                    // rule. A vendor with no record has nothing to delete; its
                    // fields are simply cleared and saved.
                    if (editing != null && page.bankId != null) {
                        ZillitButton(
                            text = str(S.desktop_delete_bank_details),
                            onClick = { onEvent(AccountHubEvent.AskDeleteVendorBank(true)) },
                            variant = ButtonVariant.Danger,
                            size = ButtonSize.Small,
                            loading = page.deletingBank,
                            enabled = !page.deletingBank && !page.bankLoading,
                        )
                    }
                },
            ) {
                FieldGrid {
                    FormField(page, "bankName", str(S.bank_name_label)) {
                        ZillitTextField(
                            value = draft.bankName,
                            onValueChange = { update(draft.copy(bankName = it)) },
                            placeholder = str(S.desktop_hub_e_g_barclays_bank),
                            errorText = page.errorFor("bankName"),
                        )
                    }
                    FormField(page, "accountHolderName", str(S.dm_req_account_holder)) {
                        ZillitTextField(
                            value = draft.accountHolderName,
                            onValueChange = { update(draft.copy(accountHolderName = it)) },
                            placeholder = str(S.ah_vendor_name_hint),
                            errorText = page.errorFor("accountHolderName"),
                        )
                    }
                    FormField(page, "accountNumber", str(S.account_number)) {
                        ZillitTextField(
                            value = draft.accountNumber,
                            onValueChange = { text ->
                                update(draft.copy(accountNumber = text.filter { it.isDigit() }))
                            },
                            placeholder = "e.g. 12345678",
                            errorText = page.errorFor("accountNumber"),
                        )
                    }
                    FormField(page, "sortCode", str(S.ah_lbl_sort_code)) {
                        ZillitTextField(
                            value = com.zillit.desktop.feature.accounthub.domain.SortCode.formatted(draft.sortCode),
                            onValueChange = {
                                update(
                                    draft.copy(
                                        sortCode = com.zillit.desktop.feature.accounthub.domain.SortCode.digits(it),
                                    ),
                                )
                            },
                            placeholder = "e.g. 20-48-91",
                        )
                    }
                    FormField(page, "ibanCode", str(S.ah_lbl_iban_code)) {
                        ZillitTextField(
                            value = draft.ibanCode,
                            onValueChange = { update(draft.copy(ibanCode = it)) },
                            placeholder = str(S.ah_iban_hint),
                            errorText = page.errorFor("ibanCode"),
                        )
                    }
                    FormField(page, "swiftCode", str(S.swift_code)) {
                        ZillitTextField(
                            value = draft.swiftCode,
                            onValueChange = { update(draft.copy(swiftCode = it)) },
                            placeholder = str(S.desktop_e_g_barcgb22),
                        )
                    }
                }
                FieldLabel(str(S.desktop_additional_bank_details))
                TypedDetailsEditor(
                    rows = draft.additionalInfo,
                    onChange = { update(draft.copy(additionalInfo = it)) },
                    addLabel = str(S.desktop_hub_add_additional_detail_iban_bic_routing_number_etc_paren),
                )
                page.errorFor("additionalInfo")?.let {
                    ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = colors.danger)
                }
            }

            
        }
    }

    HubConfirmDialog(
        visible = page.confirmDeleteBank,
        title = str(S.desktop_delete_bank_details),
        // Honest about what happens: the record is deleted now, not on save.
        message = str(S.desktop_hub_delete_this_vendors_bank_details_now_this_removes_the_bank),
        confirmLabel = str(S.delete),
        onConfirm = { onEvent(AccountHubEvent.ConfirmDeleteVendorBank) },
        onDismiss = { onEvent(AccountHubEvent.AskDeleteVendorBank(false)) },
    )
}

/**
 * A labelled field with the web's required asterisk or "optional" tag.
 *
 * The error shows once the field is the person's to fix.
 */
@Composable
private fun FormField(
    @Suppress("UNUSED_PARAMETER") page: VendorFormPage,
    @Suppress("UNUSED_PARAMETER") key: String,
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    optional: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldLabel(label, required = required)
            if (optional) FieldHint("optional")
        }
        content()
    }
}

/**
 * "Verified" or "Non-Verified" — the web's `VerifiedBadge` / `UnverifiedBadge`.
 *
 * One literal everywhere a vendor's status is named. The web's own copy drifted
 * to "Pending" in one place while the badge said Non-Verified (ZL-20611); the
 * desktop had "Not verified" on the row and "Pending Verification" on the
 * detail.
 */
/**
 * The web's `grid grid-cols-3` for the form's cards: fields three to a row at
 * equal widths, each row as tall as its tallest field. The form used to pair
 * fields two to a row and give each address line a row of its own, which made
 * it twice the web's height.
 */
@Composable
private fun FieldGrid(content: @Composable () -> Unit) {
    val gapX = ZillitTheme.spacing.lg
    val gapY = ZillitTheme.spacing.lg
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val gx = gapX.roundToPx()
        val gy = gapY.roundToPx()
        val cell = ((constraints.maxWidth - gx * (FORM_COLUMNS - 1)) / FORM_COLUMNS).coerceAtLeast(0)
        val rows = measurables.map { it.measure(Constraints.fixedWidth(cell)) }.chunked(FORM_COLUMNS)
        val heights = rows.map { row -> row.maxOf { it.height } }
        val total = heights.sum() + gy * (rows.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, total) {
            var y = 0
            rows.forEachIndexed { index, row ->
                row.forEachIndexed { column, placeable -> placeable.placeRelative(column * (cell + gx), y) }
                y += heights[index] + gy
            }
        }
    }
}

private fun verificationLabel(verified: Boolean): String = if (verified) str(S.ah_verified) else str(S.ah_non_verified)

@Composable
private fun VerificationPill(verified: Boolean) {
    Pill(
        verificationLabel(verified),
        tone = if (verified) StatusTone.Done else StatusTone.Pending,
        dot = true,
    )
}

/** The postcode lookup's spinner, for the two fields it fills in. */
private fun postcodeSpinner(page: VendorFormPage): (@Composable () -> Unit)? =
    if (page.postcodeLooking) {
        { ZillitSpinner(size = SPINNER_SIZE) }
    } else {
        null
    }

private val DETAIL_WIDTH = 880.dp
private val HISTORY_WIDTH = 340.dp
private val TIMELINE_GUTTER = 20.dp
private val TIMELINE_DOT = 10.dp
private val TIMELINE_DOT_TOP = 3.dp
private val TIMELINE_RAIL_START = 4.5.dp
private const val SYSTEM_ACTOR = "system"

private val DEPT_COLUMN = 150.dp
private val ADDED_COLUMN = 170.dp
private val ACTION_COLUMN = 210.dp
private val AUDIT_FACE = 32.dp
private val VERIFIED_TICK = 14.dp
private val DIAL_WIDTH = 120.dp
private val SPINNER_SIZE = 14.dp
private const val NAME_WEIGHT = 2f
private const val DETAIL_COLUMNS = 3
private const val FORM_COLUMNS = 3
private const val DELETING_ALPHA = 0.5f
private const val TAX_NUMBER_MAX = 50
