package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
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
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.feature.accounthub.ui.VendorFilter
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.VendorAddress
import com.zillit.desktop.feature.accounthub.domain.validationError
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.HubPage

/**
 * The vendor register.
 *
 * ## Verification is a status, not a checkbox
 *
 * The service sends a status string and no boolean, so "verified" is derived at
 * the data boundary. Reading the raw field is how the web's register once
 * showed every vendor as unverified at the same time — and this is the screen
 * where that would be believed.
 *
 * ## The detail docks, it does not cover
 *
 * Checking a vendor's history is something an accountant does while scanning
 * the list, and a modal makes them close it to look at the next row.
 */
@Suppress("LongMethod") // Header, search, tiles, register and detail: one screen.
@Composable
fun VendorsPage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val vendors = state.vendors

    HubPage {
        ZillitPageHeader(
            eyebrow = "Management",
            title = "Vendors",
            description = "Suppliers this project buys from. Purchase orders and invoices " +
                "pick from this register.",
            actions = {
                if (state.viewer.canEdit) {
                    ZillitButton(
                        text = "New vendor",
                        onClick = { onEvent(AccountHubEvent.ComposeVendor()) },
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                    )
                }
                ZillitButton(
                    text = "Refresh",
                    onClick = { onEvent(AccountHubEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    loading = vendors.loading,
                )
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = vendors.search,
                onValueChange = { onEvent(AccountHubEvent.SearchVendors(it)) },
                placeholder = "Search name, email or contact",
                modifier = Modifier.width(SEARCH_WIDTH.dp),
            )
            ZillitStatTile(
                label = "Registered",
                value = vendors.rows.size.toString(),
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = "Verified",
                value = "${vendors.verifiedCount}",
                sub = "of ${vendors.rows.size}",
                tone = if (vendors.verifiedCount == vendors.rows.size && vendors.rows.isNotEmpty()) {
                    StatusTone.Done
                } else {
                    StatusTone.Pending
                },
                modifier = Modifier.weight(1f),
            )
        }

        // The web's three tabs, with the same labels and the same counts
        // (`VendorsModule.TABS` / `tabCounts`).
        ZillitTabStrip(
            tabs = VendorFilter.entries.map {
                ZillitTab(it.slug, "${it.label} (${vendors.countFor(it)})")
            },
            activeId = vendors.filter.slug,
            onSelect = { slug ->
                VendorFilter.entries.firstOrNull { it.slug == slug }
                    ?.let { onEvent(AccountHubEvent.FilterVendors(it)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ZillitSectionCard(
                title = "Register",
                icon = ZillitIcons.Users,
                padded = false,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                ZillitDataTable(
                    rows = vendors.visibleRows,
                    key = { it.id },
                    loading = vendors.loading,
                    columns = vendorColumns(state, onEvent),
                    onRowClick = { row ->
                        // Click toggles: clicking the open row closes it, which
                        // is the only way back to the full width without a
                        // second control nobody would look for.
                        val next = if (vendors.selectedId == row.id) null else row.id
                        onEvent(AccountHubEvent.SelectVendor(next))
                    },
                    isSelected = { it.id == vendors.selectedId },
                    emptyTitle = when {
                        vendors.search.isNotBlank() -> "Nothing matched"
                        vendors.filter != VendorFilter.All -> "No ${vendors.filter.label.lowercase()}"
                        else -> "No vendors yet"
                    },
                    emptyMessage = "Vendors added here appear in every purchase order and " +
                        "invoice picker.",
                )
            }

            vendors.selected?.let { vendor ->
                VendorDetail(state, vendor, onEvent)
            }
        }
    }

    VendorFormDialog(state, onEvent)
}

@Suppress("LongMethod") // A table of columns; splitting it separates each from its width.
private fun vendorColumns(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
): List<TableColumn<Vendor>> = listOf(
    TableColumn(
        header = "Vendor",
        width = ColumnWidth.Weight(NAME_WEIGHT),
        cell = { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitAvatar(name = row.display)
                Column {
                    ZillitText(text = row.display, maxLines = 1)
                    if (row.email.isNotBlank()) {
                        ZillitText(
                            text = row.email,
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
    ),
    textColumn("Contact", ColumnWidth.Weight(1f), muted = true) {
        it.contactPerson.ifBlank { "—" }
    },
    textColumn("Phone", ColumnWidth.Fixed(PHONE_COLUMN.dp), muted = true) {
        it.phone?.display?.ifBlank { "—" } ?: "—"
    },
    TableColumn(
        header = "Status",
        width = ColumnWidth.Fixed(STATUS_COLUMN.dp),
        cell = { row ->
            ZillitStatusPill(
                label = if (row.verified) "Verified" else "Not verified",
                tone = if (row.verified) StatusTone.Done else StatusTone.Pending,
            )
        },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(ACTION_COLUMN.dp),
        cell = { row ->
            if (!state.viewer.canEdit) return@TableColumn
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                // Verification is accountant-only on the service — an admin
                // outside accounts gets `accountant_access_only`, so the button
                // is withheld rather than offered and refused.
                if (!row.verified && state.viewer.canActAsAccountant) {
                    ZillitButton(
                        text = "Verify",
                        onClick = { onEvent(AccountHubEvent.VerifyVendor(row.id)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
                ZillitButton(
                    text = "Edit",
                    onClick = { onEvent(AccountHubEvent.ComposeVendor(row)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        },
    ),
)

@Suppress("LongMethod") // One vendor, top to bottom; the order is the reading order.
@Composable
private fun VendorDetail(
    state: AccountHubUiState,
    vendor: Vendor,
    onEvent: (AccountHubEvent) -> Unit,
) {
    ZillitScrollColumn(
        modifier = Modifier
            .width(DETAIL_WIDTH.dp)
            .fillMaxHeight(),
        contentPadding = PaddingValues(start = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitAvatar(name = vendor.display)
            ZillitText(
                text = vendor.display,
                style = ZillitTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
        }
        ZillitDivider()

        DetailLine("Email", vendor.email)
        DetailLine("Contact", vendor.contactPerson)
        DetailLine("Phone", vendor.phone?.display.orEmpty())
        DetailLine("Address", vendor.address.oneLine)
        DetailLine("VAT number", vendor.vatNumber)
        DetailLine("Currency", vendor.currencyCode)
        ZillitDivider()

        ZillitSectionLabel("History")
        if (state.vendors.historyLoading) ZillitSpinner()
        if (state.vendors.history.isEmpty() && !state.vendors.historyLoading) {
            ZillitText(
                text = "No recorded changes.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        state.vendors.history.forEach { change ->
            Column(modifier = Modifier.padding(vertical = ZillitTheme.spacing.xxs)) {
                ZillitText(text = change.summary.ifBlank { "Changed" }, maxLines = 2)
                ZillitText(
                    text = listOfNotNull(
                        change.byName.takeIf { it.isNotBlank() },
                        EpochDate.dateTime(change.at).takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }

        if (state.viewer.canEdit) {
            ZillitButton(
                text = "Remove vendor",
                onClick = { onEvent(AccountHubEvent.DeleteVendor(vendor.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Trash,
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.width(LABEL_WIDTH.dp),
        )
        ZillitText(text = value.ifBlank { "—" }, style = ZillitTheme.typography.bodySmall)
    }
}

@Suppress("LongMethod") // A form, read top to bottom; the order is the reading order.
@Composable
private fun VendorFormDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val form = state.vendors.form
    val problem = form?.draft?.validationError()
    // Save & Verify is offered on an existing, not-yet-verified vendor, to the
    // people who may verify — the web's own condition
    // (`VendorsModule`: `onSaveAndVerify` is passed only for an edit).
    val editing = form?.editingId?.let { id -> state.vendors.rows.firstOrNull { it.id == id } }
    val offerVerify = editing != null && !editing.verified && state.viewer.canActAsAccountant

    ZillitDialogShell(
        title = form?.title.orEmpty(),
        visible = form != null,
        onDismiss = { onEvent(AccountHubEvent.DismissVendorForm) },
        icon = ZillitIcons.Users,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissVendorForm) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (form?.editingId == null) "Create vendor" else "Save",
                onClick = { onEvent(AccountHubEvent.SaveVendor) },
                // The same rule the view model applies, evaluated in both
                // places, so what the form refuses and what the save refuses
                // cannot drift apart.
                enabled = problem == null && form?.saving != true,
                loading = form?.saving == true,
                variant = if (offerVerify) ButtonVariant.Secondary else ButtonVariant.Primary,
            )
            if (offerVerify) {
                ZillitButton(
                    text = "Save & Verify",
                    onClick = { onEvent(AccountHubEvent.SaveAndVerifyVendor) },
                    enabled = problem == null && form?.saving != true,
                    loading = form?.saving == true,
                )
            }
        },
    ) {
        if (form == null) return@ZillitDialogShell
        val draft = form.draft

        ZillitTextField(
            value = draft.name,
            onValueChange = { onEvent(AccountHubEvent.UpdateVendorDraft(draft.copy(name = it))) },
            label = "Vendor name",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = draft.email,
                onValueChange = {
                    onEvent(AccountHubEvent.UpdateVendorDraft(draft.copy(email = it)))
                },
                label = "Email",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.contactPerson,
                onValueChange = {
                    onEvent(AccountHubEvent.UpdateVendorDraft(draft.copy(contactPerson = it)))
                },
                label = "Contact person",
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = draft.phoneCountryCode,
                onValueChange = {
                    onEvent(AccountHubEvent.UpdateVendorDraft(draft.copy(phoneCountryCode = it)))
                },
                label = "Code",
                modifier = Modifier.width(DIAL_CODE_WIDTH.dp),
            )
            ZillitTextField(
                value = draft.phoneNumber,
                onValueChange = {
                    onEvent(AccountHubEvent.UpdateVendorDraft(draft.copy(phoneNumber = it)))
                },
                label = "Phone",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.vatNumber,
                onValueChange = {
                    onEvent(AccountHubEvent.UpdateVendorDraft(draft.copy(vatNumber = it)))
                },
                label = "VAT number",
                modifier = Modifier.weight(1f),
            )
        }

        // Structured, because the service types this field: a flattened string
        // is refused with `"address" must be of type object`.
        ZillitSectionLabel("Address")
        ZillitTextField(
            value = draft.address.line1,
            onValueChange = { onEvent(vendorAddress(draft) { copy(line1 = it) }) },
            label = "Street address",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = draft.address.city,
                onValueChange = { onEvent(vendorAddress(draft) { copy(city = it) }) },
                label = "City",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.address.postalCode,
                onValueChange = { onEvent(vendorAddress(draft) { copy(postalCode = it) }) },
                label = "Postcode",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.address.country,
                onValueChange = { onEvent(vendorAddress(draft) { copy(country = it) }) },
                label = "Country",
                modifier = Modifier.weight(1f),
            )
        }

        problem?.let {
            ZillitNotice(text = it, tone = StatusTone.Pending, icon = ZillitIcons.Info)
        }
        if (editing?.verified == true) {
            ZillitNotice(
                text = "This vendor is verified — editing it does not re-open verification.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }
    }
}

private const val SEARCH_WIDTH = 300
private const val DETAIL_WIDTH = 340
private const val PHONE_COLUMN = 150
private const val STATUS_COLUMN = 130
private const val ACTION_COLUMN = 160
private const val LABEL_WIDTH = 96
private const val DIAL_CODE_WIDTH = 90
private const val NAME_WEIGHT = 2f

/** Edits one field of the draft's address without restating the rest. */
private fun vendorAddress(
    draft: NewVendor,
    edit: VendorAddress.() -> VendorAddress,
): AccountHubEvent = AccountHubEvent.UpdateVendorDraft(draft.copy(address = draft.address.edit()))
