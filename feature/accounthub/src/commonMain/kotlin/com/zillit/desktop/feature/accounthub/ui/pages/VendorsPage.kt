package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.BankAccounts
import com.zillit.desktop.feature.accounthub.domain.IsdCountries
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.VendorAddress
import com.zillit.desktop.feature.accounthub.domain.VendorTerms
import com.zillit.desktop.feature.accounthub.domain.sanitisePhone
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.VendorsState
import com.zillit.desktop.feature.accounthub.ui.UNKNOWN_PERSON
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.VendorFilter
import com.zillit.desktop.feature.accounthub.ui.VendorFormPage
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
        ZillitPageHeader(
            eyebrow = "Management",
            title = "Vendors",
            description = "Manage supplier records, verify vendor details, and maintain your approved vendor list.",
            actions = {
                // Anyone who may post, as the web: a department user's "Added by
                // Me" tab would otherwise list vendors they had no way to add.
                if (state.viewer.mayAddVendor) {
                    ZillitButton(
                        text = "Add Vendor",
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
            placeholder = "Search vendors — by name, contact, email, Tax number…",
            modifier = Modifier.fillMaxWidth(),
        )

        // The web's tabs, with "Added by Me" for a department user.
        val tabs = VendorFilter.entries.filter { it != VendorFilter.Mine || !state.viewer.isAccountant }
        ZillitTabStrip(
            tabs = tabs.map { ZillitTab(it.slug, it.label, count = vendors.countFor(it)) },
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
                        emptyTitle = if (vendors.isFiltered) "No vendors match your filter." else "No vendors yet",
                        emptyMessage =
                            if (vendors.isFiltered) null
                            else "Vendors added here appear in every purchase order and invoice picker.",
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
        title = "Delete Vendor",
        message = "Delete \"${vendors.confirmDelete?.display.orEmpty()}\"? A vendor still named on a purchase " +
            "order is refused by the server.",
        confirmLabel = "Delete",
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
        header = "Vendor name",
        width = ColumnWidth.Weight(NAME_WEIGHT),
        cell = { row ->
            Row(
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
                    val short = listOf(row.address.city, row.address.country)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                    if (short.isNotBlank()) FieldHint(short)
                }
            }
        },
    ),
    TableColumn(
        header = "Contact person",
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
        header = "Department",
        width = ColumnWidth.Fixed(DEPT_COLUMN),
        cell = { row ->
            // A department nobody can name is a dash, not its id.
            val department = row.departmentId?.let { state.departmentName(it) }.orEmpty()
            if (department.isNotBlank()) Pill(department) else FieldHint("—")
        },
    ),
    TableColumn(
        header = "Added by",
        width = ColumnWidth.Fixed(ADDED_COLUMN),
        cell = { row ->
            val user = row.addedBy?.let { state.user(it) }
            if (user != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitAvatar(name = user.name, size = 22.dp)
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
        header = "",
        width = ColumnWidth.Fixed(ACTION_COLUMN),
        cell = { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                // Per row, as the web: the accounts team may change any vendor,
                // and whoever added one may change theirs.
                if (state.viewer.mayModifyVendor(row)) {
                    ZillitIconButton(
                        icon = ZillitIcons.Edit,
                        contentDescription = "Edit",
                        onClick = { onEvent(AccountHubEvent.ComposeVendor(row)) },
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Delete",
                        onClick = { onEvent(AccountHubEvent.AskDeleteVendor(row)) },
                        tint = ZillitTheme.colors.danger,
                    )
                }
                ZillitIconButton(
                    icon = ZillitIcons.Clock,
                    contentDescription = "History",
                    onClick = { onEvent(AccountHubEvent.OpenVendorHistory(row.id)) },
                )
                if (!state.viewer.isAccountant && state.viewer.mayList("purchase_order_tool")) {
                    ZillitButton(
                        text = "Create PO",
                        onClick = { onEvent(AccountHubEvent.CreatePurchaseOrder(row.id)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
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
        subtitle = vendor?.let { verificationLabel(it.verified) },
        icon = ZillitIcons.Users,
        visible = vendor != null,
        onDismiss = { onEvent(AccountHubEvent.OpenVendorDetail(null)) },
        width = DETAIL_WIDTH,
        actions = {
            if (vendor != null) {
                ZillitButton(
                    text = "Vendor History",
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
                        text = "Edit Vendor Details",
                        onClick = { onEvent(AccountHubEvent.ComposeVendor(vendor)) },
                        variant = ButtonVariant.Secondary,
                        leadingIcon = ZillitIcons.Edit,
                    )
                }
                if (!vendor.verified && state.viewer.canActAsAccountant) {
                    ZillitButton(
                        text = if (vendors.verifyingId == vendor.id) "Verifying…" else "Mark Verified",
                        onClick = { onEvent(AccountHubEvent.VerifyVendor(vendor.id)) },
                        loading = vendors.verifyingId == vendor.id,
                        leadingIcon = ZillitIcons.Tick,
                    )
                }
            }
        },
    ) {
        if (vendor == null) return@ZillitDialogShell
        ZillitSectionLabel("Contact")
        DetailGrid(
            listOf(
                "Contact Person" to vendor.contactPerson,
                "Email" to vendor.email,
                "Department" to state.departmentName(vendor.departmentId),
                "Phone" to vendor.phone?.display.orEmpty(),
                "Tax Number" to vendor.vatNumber,
            ),
        )
        ZillitSectionLabel("Address")
        ZillitText(text = vendor.address.oneLine.ifBlank { "—" }, style = ZillitTheme.typography.bodyMedium)
        if (vendor.hasClassification) {
            ZillitSectionLabel("Details")
            DetailGrid(
                listOfNotNull(
                    vendor.vendorType.takeIf { it.isNotBlank() }?.let { "Type" to it },
                    vendor.companyType.takeIf { it.isNotBlank() }?.let { "Company Type" to it },
                    vendor.terms.takeIf { it.isNotBlank() }?.let { "Terms" to VendorTerms.labelFor(it) },
                    vendor.defaultCode.takeIf { it.isNotBlank() }?.let { "Default Code" to it },
                    vendor.compliance.takeIf { it.isNotBlank() }?.let { "Compliance" to it },
                ),
            )
        }
        VendorBankBlock(vendors, onEvent)
        ZillitSectionLabel("Audit")
        AuditRow("Added by", vendor.addedBy, vendor.createdAtMillis, state)
        if (vendor.verified) AuditRow("Verified by", vendor.verifiedBy, vendor.verifiedAtMillis, state)
        if (vendor.updatedBy != null) AuditRow("Last updated", vendor.updatedBy, vendor.updatedAtMillis, state)
    }
}

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
        ZillitSectionLabel("Bank Details")
        FieldHint("Loading bank details…")
        return
    }
    val bank = vendors.detailBank?.takeUnless { it.isEmpty } ?: return
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitSectionLabel("Bank Details", modifier = Modifier.weight(1f))
        ZillitButton(
            text = if (vendors.bankRevealed) "Hide" else "Reveal",
            onClick = { onEvent(AccountHubEvent.RevealVendorBank(!vendors.bankRevealed)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Eye,
        )
    }
    val mask: (String) -> String = { if (vendors.bankRevealed) it else BankAccounts.masked(it) }
    DetailGrid(
        listOfNotNull(
            bank.bankName.takeIf { it.isNotBlank() }?.let { "Bank Name" to it },
            bank.accountHolderName.takeIf { it.isNotBlank() }?.let { "Account Holder" to it },
            bank.accountNumber.takeIf { it.isNotBlank() }?.let { "Account Number" to mask(it) },
            bank.sortCode.takeIf { it.isNotBlank() }?.let { "Sort Code" to mask(it) },
            bank.ibanCode.takeIf { it.isNotBlank() }?.let { "IBAN" to mask(it) },
            bank.swiftCode.takeIf { it.isNotBlank() }?.let { "SWIFT" to mask(it) },
        ) + bank.additionalInfo.filter { it.isTitled }.map { it.title to mask(it.value) },
    )
    if (vendors.bankRevealed) FieldHint("Re-masks in 5 seconds.")
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

@Composable
private fun AuditRow(label: String, userId: String?, at: Long?, state: AccountHubUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        MonoLabel(label, modifier = Modifier.width(AUDIT_LABEL))
        val name = userId?.let { state.userName(it) } ?: UNKNOWN_PERSON
        // Initials for a dash would invent a person.
        if (name != UNKNOWN_PERSON) ZillitAvatar(name = name, size = 22.dp)
        ZillitText(text = name, style = ZillitTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        FieldHint(EpochDate.dateTime(at).ifBlank { "" })
    }
}

// -- the history panel -------------------------------------------------------------------

/** The web's `HistoryPanel`: a side panel, one row per action, "by user (designation)". */
@Composable
private fun HistoryPanel(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val vendors = state.vendors
    val vendor = vendors.rows.firstOrNull { it.id == vendors.historyFor }
    SubCard(
        title = "Activity log",
        hint = vendor?.display,
        action = { ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Close history",
            onClick = { onEvent(AccountHubEvent.OpenVendorHistory(null)) },
        ) },
        modifier = Modifier.width(HISTORY_WIDTH).fillMaxHeight(),
    ) {
        if (vendors.historyLoading) ZillitSpinner()
        if (vendors.history.isEmpty() && !vendors.historyLoading) FieldHint("No recorded changes.")
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            vendors.history.forEach { change ->
                Column {
                    ZillitText(
                        text = change.summary.ifBlank { "Changed" },
                        style = ZillitTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                    val actor = change.byName.ifBlank {
                        change.byId.takeIf { it.isNotBlank() }?.let { state.userName(it) }.orEmpty()
                    }
                    val role = change.byId.takeIf { it.isNotBlank() }
                        ?.let { state.user(it)?.designation?.localised() }
                        .orEmpty()
                    FieldHint(
                        listOfNotNull(
                            actor
                                .takeIf {
                                    it.isNotBlank() }?.let { if (role.isNotBlank()) "by $it ($role)" else "by $it"
                                },
                            EpochDate.dateTime(change.at).takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                    )
                    if (change.note.isNotBlank()) FieldHint(change.note)
                }
            }
        }
    }
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
                contentDescription = "Back to vendors list",
                onClick = { onEvent(AccountHubEvent.DismissVendorForm) },
            )
            Column(modifier = Modifier.weight(1f)) {
                MonoLabel(if (editing == null) "Vendors / New Vendor" else "Vendors / Edit Vendor Details")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Shrinks before the badge does: a long name ellipsises, the badge stays.
                    ZillitText(
                        text = if (editing == null) "New Vendor" else editing.display,
                        style = ZillitTheme.typography.titleLarge,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // The web's edit eyebrow carries the vendor's badge beside its name.
                    if (editing != null) VerificationPill(editing.verified)
                }
                FieldHint(if (editing == null) "All fields required unless noted" else "Edit Vendor Details")
            }
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissVendorForm) },
                variant = ButtonVariant.Tertiary,
                enabled = !page.saving && !page.verifying,
            )
            ZillitButton(
                text = if (editing == null) "Create Vendor" else "Save Changes",
                onClick = { onEvent(AccountHubEvent.SaveVendor) },
                variant = if (offerVerify) ButtonVariant.Secondary else ButtonVariant.Primary,
                loading = page.saving,
                enabled = !page.saving && !page.verifying,
            )
            if (offerVerify) {
                ZillitButton(
                    text = "Save & Verify",
                    onClick = { onEvent(AccountHubEvent.SaveAndVerifyVendor) },
                    loading = page.verifying,
                    enabled = !page.saving && !page.verifying,
                )
            }
        }
        TipBanner(
            when {
                editing == null && state.viewer.isAccountant -> "Vendors created here are automatically marked " +
                    "Verified and available for PO assignment immediately."
                editing == null -> "Vendors created here will be marked Non-Verified."
                // Who is editing decides it on the web, not the vendor's state:
                // an accountant's edit keeps the verification it has.
                state.viewer.isAccountant -> "Update the vendor information below. Changes are saved immediately."
                else -> "Update the vendor information below. Changes will reset verification to pending."
            },
        )
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            SubCard(title = "Vendor Details") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    FormField(page, "name", "Vendor / Company Name", required = true, modifier = Modifier.weight(1f)) {
                        ZillitTextField(
                            value = draft.name,
                            onValueChange = { update(draft.copy(name = it)) },
                            placeholder = "e.g. Pinewood Studios Ltd",
                            errorText = page.errorFor("name"),
                        )
                    }
                    FormField(
                        page,
                        "contactPerson",
                        "Contact Person",
                        required = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        ZillitTextField(
                            value = draft.contactPerson,
                            onValueChange = { update(draft.copy(contactPerson = it)) },
                            placeholder = "e.g. Margaret Thornton",
                            errorText = page.errorFor("contactPerson"),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    FormField(page, "email", "Email", required = true, modifier = Modifier.weight(1f)) {
                        ZillitTextField(
                            value = draft.email,
                            onValueChange = { update(draft.copy(email = it)) },
                            placeholder = "e.g. bookings@studio.co.uk",
                            errorText = page.errorFor("email"),
                        )
                    }
                    FormField(page, "phoneNumber", "Phone", modifier = Modifier.weight(1f)) {
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
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    FormField(page, "vatNumber", "Tax Number", optional = true, modifier = Modifier.weight(1f)) {
                        ZillitTextField(
                            value = draft.vatNumber,
                            onValueChange = { update(draft.copy(vatNumber = it)) },
                            maxLength = TAX_NUMBER_MAX,
                            placeholder = "e.g. GB 123 4567 89",
                        )
                    }
                    FormField(page, "departmentId", "Department", modifier = Modifier.weight(1f)) {
                        HubSelect(
                            value = state.departmentList.firstOrNull { it.id == draft.departmentId },
                            options = state.departmentList,
                            label = { it.name.localised() },
                            onSelect = { update(draft.copy(departmentId = it?.id)) },
                            placeholder = "Select department…",
                            clearable = true,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    FormField(page, "companyType", "Company Type", optional = true, modifier = Modifier.weight(1f)) {
                        HubSelect(
                            value = draft.companyType.takeIf { it.isNotBlank() },
                            options = COMPANY_TYPES,
                            label = { it },
                            onSelect = { update(draft.copy(companyType = it.orEmpty())) },
                            placeholder = "Select…",
                            clearable = true,
                            searchable = false,
                        )
                    }
                    FormField(page, "terms", "Terms", optional = true, modifier = Modifier.weight(1f)) {
                        HubSelect(
                            value = VendorTerms.entries.firstOrNull { it.wire == draft.terms },
                            options = VendorTerms.entries.toList(),
                            label = { it.label },
                            onSelect = { update(draft.copy(terms = it?.wire.orEmpty())) },
                            placeholder = "Select terms…",
                            clearable = true,
                            searchable = false,
                        )
                    }
                    if (state.viewer.isAccountant) {
                        FormField(
                            page,
                            "defaultCode",
                            "Default Code",
                            optional = true,
                            modifier = Modifier.weight(1f),
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

            SubCard(title = "Address") {
                FormField(page, "line1", "Address Line 1", required = true) {
                    ZillitTextField(
                        value = draft.address.line1,
                        onValueChange = { text -> address { copy(line1 = text) } },
                        placeholder = "Street address",
                        errorText = page.errorFor("line1"),
                    )
                }
                FormField(page, "line2", "Address Line 2", optional = true) {
                    ZillitTextField(
                        value = draft.address.line2,
                        onValueChange = { text -> address { copy(line2 = text) } },
                        placeholder = "Suite, unit, building…",
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    FormField(page, "city", "City", required = true, modifier = Modifier.weight(1f)) {
                        ZillitTextField(
                            value = draft.address.city,
                            onValueChange = { text -> address { copy(city = text) } },
                            placeholder = "e.g. London",
                            errorText = page.errorFor("city"),
                            trailingContent = postcodeSpinner(page),
                        )
                    }
                    FormField(page, "state", "State / County", optional = true, modifier = Modifier.weight(1f)) {
                        ZillitTextField(
                            value = draft.address.state,
                            onValueChange = { text -> address { copy(state = text) } },
                            placeholder = "e.g. Middlesex",
                            trailingContent = postcodeSpinner(page),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    FormField(
                        page,
                        "postalCode",
                        "Postal / ZIP Code",
                        required = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        ZillitTextField(
                            value = draft.address.postalCode,
                            onValueChange = { text -> address { copy(postalCode = text) } },
                            placeholder = "e.g. SL0 0NH",
                            errorText = page.errorFor("postalCode"),
                        )
                    }
                    FormField(page, "country", "Country", required = true, modifier = Modifier.weight(1f)) {
                        // Unset until picked (ZL-20520), so "Country is required" can
                        // catch a skipped field instead of every vendor saving as UK.
                        HubSelect(
                            value = IsdCountries.forName(state.vendors.countries, draft.address.country),
                            options = state.vendors.countries,
                            label = { it.name },
                            onSelect = { picked -> address { copy(country = picked?.name ?: draft.address.country) } },
                            placeholder = "Select country…",
                        )
                        page.errorFor("country")?.let {
                            ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = colors.danger)
                        }
                    }
                }
            }

            SubCard(
                title = "Bank Details",
                hint = when {
                    page.bankLoading -> "Loading the bank details on file…"
                    else -> "Optional. Masked on the detail view until revealed."
                },
                action = {
                    // Only a bank record that exists can be deleted — the web's
                    // rule. A vendor with no record has nothing to delete; its
                    // fields are simply cleared and saved.
                    if (editing != null && page.bankId != null) {
                        ZillitButton(
                            text = "Delete Bank Details",
                            onClick = { onEvent(AccountHubEvent.AskDeleteVendorBank(true)) },
                            variant = ButtonVariant.Danger,
                            size = ButtonSize.Small,
                            loading = page.deletingBank,
                            enabled = !page.deletingBank && !page.bankLoading,
                        )
                    }
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    FormField(page, "bankName", "Bank Name", modifier = Modifier.weight(1f)) {
                        ZillitTextField(
                            value = draft.bankName,
                            onValueChange = { update(draft.copy(bankName = it)) },
                            placeholder = "e.g. Barclays Bank",
                            errorText = page.errorFor("bankName"),
                        )
                    }
                    FormField(page, "accountHolderName", "Account Holder Name", modifier = Modifier.weight(1f)) {
                        ZillitTextField(
                            value = draft.accountHolderName,
                            onValueChange = { update(draft.copy(accountHolderName = it)) },
                            placeholder = "e.g. Pinewood Studios Ltd",
                            errorText = page.errorFor("accountHolderName"),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    FormField(page, "accountNumber", "Account Number", modifier = Modifier.weight(1f)) {
                        ZillitTextField(
                            value = draft.accountNumber,
                            onValueChange = { text ->
                                update(draft.copy(accountNumber = text.filter { it.isDigit() }))
                            },
                            placeholder = "e.g. 12345678",
                            errorText = page.errorFor("accountNumber"),
                        )
                    }
                    FormField(page, "sortCode", "Sort Code", modifier = Modifier.weight(1f)) {
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
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    FormField(page, "ibanCode", "IBAN Code", modifier = Modifier.weight(1f)) {
                        ZillitTextField(
                            value = draft.ibanCode,
                            onValueChange = { update(draft.copy(ibanCode = it)) },
                            placeholder = "e.g. GB29 NWBK 6016 1331 9268 19",
                            errorText = page.errorFor("ibanCode"),
                        )
                    }
                    FormField(page, "swiftCode", "SWIFT Code", modifier = Modifier.weight(1f)) {
                        ZillitTextField(
                            value = draft.swiftCode,
                            onValueChange = { update(draft.copy(swiftCode = it)) },
                            placeholder = "e.g. BARCGB22",
                        )
                    }
                }
                FieldLabel("Additional bank details")
                TypedDetailsEditor(
                    rows = draft.additionalInfo,
                    onChange = { update(draft.copy(additionalInfo = it)) },
                    addLabel = "Add additional detail (IBAN, BIC, routing number, etc.)",
                )
                page.errorFor("additionalInfo")?.let {
                    ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = colors.danger)
                }
            }

            
        }
    }

    HubConfirmDialog(
        visible = page.confirmDeleteBank,
        title = "Delete Bank Details",
        // Honest about what happens: the record is deleted now, not on save.
        message = "Delete this vendor's bank details now? This removes the bank record itself, before " +
            "you save. It is refused if an invoice, card, cash claim or timecard still uses it.",
        confirmLabel = "Delete",
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

/** The web's company-type options. */
private val COMPANY_TYPES = listOf(
    "Limited Company",
    "Sole Trader",
    "Partnership",
    "LLP",
    "PLC",
    "Charity",
    "Public Body",
    "Other",
)

/**
 * "Verified" or "Non-Verified" — the web's `VerifiedBadge` / `UnverifiedBadge`.
 *
 * One literal everywhere a vendor's status is named. The web's own copy drifted
 * to "Pending" in one place while the badge said Non-Verified (ZL-20611); the
 * desktop had "Not verified" on the row and "Pending Verification" on the
 * detail.
 */
private fun verificationLabel(verified: Boolean): String = if (verified) "Verified" else "Non-Verified"

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
private val HISTORY_WIDTH = 320.dp
private val DEPT_COLUMN = 150.dp
private val ADDED_COLUMN = 170.dp
private val ACTION_COLUMN = 210.dp
private val AUDIT_LABEL = 96.dp
private val DIAL_WIDTH = 120.dp
private val SPINNER_SIZE = 14.dp
private const val NAME_WEIGHT = 2f
private const val DETAIL_COLUMNS = 3
private const val TAX_NUMBER_MAX = 50
