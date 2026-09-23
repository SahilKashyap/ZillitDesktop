package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.domain.AssignmentRule
import com.zillit.desktop.feature.accounthub.domain.AssignmentRules
import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.HubUsers
import com.zillit.desktop.feature.accounthub.domain.InvoiceAlert
import com.zillit.desktop.feature.accounthub.domain.InvoiceTeamMember
import com.zillit.desktop.feature.accounthub.domain.InvoicesSetup
import com.zillit.desktop.feature.accounthub.domain.JournalDescriptionFormat
import com.zillit.desktop.feature.accounthub.domain.PayrollAccountRow
import com.zillit.desktop.feature.accounthub.domain.PayrollSettings
import com.zillit.desktop.feature.accounthub.domain.PoDescriptionFormat
import com.zillit.desktop.feature.accounthub.domain.PoSplitType
import com.zillit.desktop.feature.accounthub.domain.PurchaseOrderSetup
import com.zillit.desktop.feature.accounthub.domain.RunAuthorisationTier
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupModal
import com.zillit.desktop.feature.accounthub.ui.SetupModalSection
import com.zillit.desktop.feature.accounthub.ui.SetupRemoval
import com.zillit.desktop.feature.accounthub.ui.UserPickerPurpose
import com.zillit.desktop.feature.accounthub.ui.pickerExcluded
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.domain.LocalIds
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.core.common.orDash
import com.zillit.desktop.feature.accounthub.ui.components.AlwaysRow
import com.zillit.desktop.feature.accounthub.ui.components.CalcField
import com.zillit.desktop.feature.accounthub.ui.components.Chip
import com.zillit.desktop.feature.accounthub.ui.components.DashedInsertRail
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.GhostAddButton
import com.zillit.desktop.feature.accounthub.ui.components.HairLine
import com.zillit.desktop.feature.accounthub.ui.components.HoverRow
import com.zillit.desktop.feature.accounthub.ui.components.HubMultiSelect
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.PersonChip
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.RadioCard
import com.zillit.desktop.feature.accounthub.ui.components.SetupModalShell
import com.zillit.desktop.feature.accounthub.ui.components.SubCard
import com.zillit.desktop.feature.accounthub.ui.components.ToggleRow
import com.zillit.desktop.feature.accounthub.ui.components.UserPickerDialog
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.input.KeyboardType
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.feature.accounthub.domain.AssetExpenditureType
import com.zillit.desktop.feature.accounthub.domain.AssetFilters

/**
 * The three drill-down modals — the web's `POSetupDetail`, `InvoicesSetupDetail`
 * and `PayrollSettingsDetail` — plus the dialogs they open.
 */
@Composable
internal fun SetupModals(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    canAttach: Boolean,
    canOpen: Boolean,
) {
    val modal = state.setup.modal
    if (modal != null) {
        val sections = sectionsFor(modal.modal, state)
        SetupModalShell(
            eyebrow = modal.modal.eyebrow,
            title = modal.modal.title,
            description = modal.modal.description,
            sections = sections,
            activeId = modal.section,
            onSection = { onEvent(AccountHubEvent.SwitchModalSection(it)) },
            dirty = state.setup.modalDirty,
            saving = state.setup.modalSaving,
            loading = modal.loading,
            loadError = modal.loadError,
            onSave = { onEvent(AccountHubEvent.SaveSetupModal) },
            onClose = { onEvent(AccountHubEvent.CloseSetupModal) },
            onRetry = { onEvent(AccountHubEvent.RetrySetupModal) },
        ) { sectionId ->
            when (modal.modal) {
                SetupModal.PurchaseOrders -> PoModalBody(sectionId, state, onEvent, canAttach, canOpen)
                SetupModal.Invoices -> InvoicesModalBody(sectionId, state, onEvent)
                SetupModal.Payroll -> PayrollModalBody(sectionId, state, onEvent)
            }
        }
    }
    InvoiceMemberDialog(state, onEvent)
    PayrollGroupDialog(state, onEvent)
    PayrollAccountsDialog(state, onEvent)
    SharedUserPicker(state, onEvent)
}

private fun sectionsFor(modal: SetupModal, state: AccountHubUiState): List<SetupModalSection> = when (modal) {
    // The web's five (`POSetupDetail`), with its chips. Form Configuration
    // left that modal in the web's 8c4f2b0cc (the forms editor has its own
    // area); the asset register rule and the assignment rules came back with
    // 03f047d47, mirroring the PO module's own Settings page.
    SetupModal.PurchaseOrders -> state.setup.poSetup.edited.let { po ->
        listOf(
            SetupModalSection("format", str(S.desktop_description_format), 1),
            SetupModalSection("rental", str(S.desktop_rental_split), po.rentalCount),
            SetupModalSection("issuance", str(S.desktop_issuance), po.issuanceCount),
            SetupModalSection("assets", str(S.desktop_asset_register_rules), po.assetCount),
            SetupModalSection("rules", str(S.desktop_auto_assignment_rules), state.setup.poRules.edited.size),
        )
    }
    SetupModal.Invoices -> listOf(
        SetupModalSection("team", str(S.ah_settings_team_posting), state.setup.invoicesSetup.edited.teamMembers.size),
        SetupModalSection("alerts", str(S.desktop_alert_preferences), state.setup.invoicesSetup.edited.alerts.size),
        SetupModalSection(
            "runauth",
            str(S.desktop_run_authorization),
            state.setup.invoicesSetup.edited.runAuthorisation.size,
        ),
        SetupModalSection("rules", str(S.desktop_auto_assignment_rules), state.setup.invoiceRules.edited.size),
    )
    SetupModal.Payroll -> listOf(
        SetupModalSection("approvers", str(S.desktop_approvers), state.setup.payrollSettings.edited.approverIds.size),
        // One pane since the web's 03f047d47: the pay period, then the two
        // journal choices under dashed dividers.
        SetupModalSection("pay_period", str(S.desktop_pay_period_journal)),
        SetupModalSection(
            "payroll_accounts",
            str(S.desktop_payroll_accounts),
            state.setup.payrollSettings.edited.payrollAccounts.size,
        ),
        SetupModalSection("payroll_groups", str(S.desktop_payroll_groups), state.setup.payrollGroups.size),
    )
}

// -- purchase orders --------------------------------------------------------------

@Suppress("CyclomaticComplexMethod", "LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun ColumnScope.PoModalBody(
    sectionId: String,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    canAttach: Boolean,
    canOpen: Boolean,
) {
    val value = state.setup.poSetup.edited
    val editable = state.viewer.canEdit
    fun update(next: PurchaseOrderSetup) = onEvent(AccountHubEvent.EditPoSetup(next))
    when (sectionId) {
        "format" -> SubCard(
            hint = str(S.desktop_hub_controls_how_ledger_entry_descriptions_are_auto_formatted_from_the),
        ) {
            // The three the web offers. A stored CUSTOM still decodes; it
            // simply lights none of them, as on the web.
            PoDescriptionFormat.offered.forEach { format ->
                RadioCard(
                    label = format.label,
                    sample = "e.g. ${format.sample}",
                    active = value.descriptionFormat == format,
                    onClick = { update(value.copy(descriptionFormat = format)) },
                    enabled = editable,
                )
            }
        }
        "rental" -> SubCard(hint = str(S.desktop_hub_how_rental_pos_are_handled_when_posting_to_the_ledger)) {
            ToggleRow(
                label = str(S.desktop_po_auto_split_rentals),
                hint = str(S.desktop_hub_automatically_detect_and_split_rental_hire_pos_by_period),
                checked = value.autoSplitRentals,
                onCheckedChange = { update(value.copy(autoSplitRentals = it)) },
                enabled = editable,
            )
            HairLine()
            // Offered whether or not auto-split is on, as the web does: the
            // cadence is remembered across the switch.
            FieldLabel(str(S.desktop_default_split_type))
            ZillitSegmented(
                options = PoSplitType.entries.map { ZillitTab(it.wire, it.label) },
                activeId = value.splitType.wire,
                onSelect = { id -> if (editable) update(value.copy(splitType = PoSplitType.from(id))) },
            )
            HairLine()
            // Always-on: the service forces both whatever is stored, so the web
            // shows a static "Always" where a switch would lie.
            AlwaysRow(
                str(S.desktop_require_effective_date),
                str(S.desktop_hub_block_po_posting_without_a_confirmed_effective_date),
            )
            HairLine()
            AlwaysRow(
                str(S.desktop_enforce_period_close),
                str(S.desktop_hub_prevent_back_dating_entries_to_closed_accounting_periods),
            )
        }
        "issuance" -> SubCard(hint = str(S.desktop_hub_the_document_issued_with_a_purchase_order_and_the_prefix)) {
            FieldLabel(str(S.desktop_po_number_prefix))
            ZillitTextField(
                value = value.numberPrefix,
                onValueChange = { update(value.copy(numberPrefix = PurchaseOrderSetup.normalisePrefix(it))) },
                placeholder = str(S.desktop_e_g_qw),
                // Capped by normalisePrefix rather than maxLength: the field's
                // counter is not on the web's input.
                enabled = editable,
                modifier = Modifier.width(PREFIX_WIDTH),
            )
            FieldHint(PurchaseOrderSetup.PREFIX_HINT)
            HairLine()
            FieldLabel(str(S.desktop_hub_terms_and_conditions_document))
            FieldHint(str(S.desktop_hub_issued_with_every_purchase_order_replaces_the_old_terms_of))
            TermsDocumentBlock(state, canChange = editable && canAttach, canOpen = canOpen, onEvent = onEvent)
            if (editable && !canAttach) {
                ZillitNotice(
                    text = str(S.desktop_hub_attaching_is_unavailable_this_window_has_no_file_storage_wired),
                    tone = StatusTone.Neutral,
                    icon = ZillitIcons.Info,
                )
            }
            // Amendments are built but paused behind the web's `AMENDMENTS_ENABLED = false`;
            // the stored flag round-trips untouched and no row is shown.
        }
        "assets" -> AssetRegisterRulesSection(state, value, editable, ::update)
        "rules" -> AssignmentRulesSection(
            rules = state.setup.poRules.edited,
            module = "purchase_orders",
            showVendors = true,
            state = state,
            editable = editable,
            onChange = { onEvent(AccountHubEvent.EditPoRules(it)) },
        )
    }
}

/**
 * The asset register rule — the web's "Asset Register Rules" pane, the same
 * `asset_filters` the PO module's Settings page edits: one expenditure type
 * (or all), an inclusive price range on the line total, and tags.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod") // A form, read top to bottom; the order is the reading order.
@Composable
private fun ColumnScope.AssetRegisterRulesSection(
    state: AccountHubUiState,
    value: PurchaseOrderSetup,
    editable: Boolean,
    update: (PurchaseOrderSetup) -> Unit,
) {
    val filters = value.assetFilters
    val symbol = state.setup.currencies.saved.default?.symbol.orEmpty()
    fun patch(next: AssetFilters) = update(value.copy(assetFilters = next))
    SubCard(
        hint = str(S.desktop_hub_which_line_items_on_posted_closed_pos_qualify_as_assets),
    ) {
        FieldLabel(str(S.expenditure_type))
        // Single choice: All (every type) · Purchase · Consumables.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitChoiceChip(
                label = "All",
                selected = filters.expTypes.isEmpty(),
                onClick = { if (editable) patch(filters.copy(expTypes = emptyList())) },
            )
            AssetExpenditureType.entries.forEach { type ->
                ZillitChoiceChip(
                    label = type.label,
                    selected = filters.expTypes.firstOrNull() == type.wire,
                    onClick = { if (editable) patch(filters.copy(expTypes = listOf(type.wire))) },
                )
            }
        }
        FieldHint(str(S.desktop_hub_all_every_expenditure_type_qualifies))
        HairLine()
        FieldLabel(str(S.desktop_price_range))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = filters.priceLow,
                onValueChange = { patch(filters.copy(priceLow = it)) },
                placeholder = "${symbol}0",
                keyboardType = KeyboardType.Number,
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
            FieldHint("to")
            ZillitTextField(
                value = filters.priceHigh,
                onValueChange = { patch(filters.copy(priceHigh = it)) },
                placeholder = str(S.desktop_no_max),
                keyboardType = KeyboardType.Number,
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
        }
        FieldHint(str(S.desktop_hub_inclusive_on_the_line_total_either_bound_can_be_left))
        HairLine()
        // The catalogue is the Asset Tags section's; a stored tag the catalogue
        // no longer lists stays offered so a save cannot silently drop it.
        HubMultiSelect(
            selected = filters.tags,
            options = (state.setup.assetTags.saved + filters.tags).distinct(),
            label = { it },
            onChange = { patch(filters.copy(tags = it)) },
            placeholder = str(S.desktop_any_tag),
            fieldLabel = str(S.drive_tags),
            enabled = editable,
        )
        FieldHint(str(S.desktop_hub_matches_a_line_carrying_any_of_these_select_none_for))
        val error = filters.error
        if (error != null) {
            ZillitNotice(text = error, tone = StatusTone.Rejected, icon = ZillitIcons.Warning)
        } else {
            // Spelled out, so "no constraint" cannot be mistaken for "nothing saved yet".
            ZillitNotice(
                text = "Qualifies: ${filters.summary(symbol)}",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }
    }
}

/**
 * The one terms document — the web's `TermsDocumentSection`.
 *
 * Attached: the file, "Attached", View and Change. Empty: "Add attachment".
 * There is no remove — the web has none either; a document is only ever
 * replaced. View is not gated on editing: reading it is exactly what a
 * read-only user needs.
 */
@Composable
private fun TermsDocumentBlock(
    state: AccountHubUiState,
    canChange: Boolean,
    canOpen: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val terms: AgreementDocument? = state.setup.poSetup.edited.termsDocument
    val uploading = state.setup.poTermsUploading
    if (terms != null) {
        TermsAttachedRow(
            terms = terms,
            uploading = uploading,
            opening = state.setup.poTermsOpening,
            canOpen = canOpen,
            canChange = canChange,
            onEvent = onEvent,
        )
    } else {
        ZillitButton(
            text = if (uploading) str(S.ah_uploading) else str(S.desktop_add_attachment),
            onClick = { onEvent(AccountHubEvent.PickPoTerms) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Paperclip,
            loading = uploading,
            enabled = canChange && !uploading,
        )
    }
    state.setup.poTermsError?.let { error ->
        ZillitText(text = error, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
    }
    FieldHint(str(S.desktop_hub_pdf_doc_or_docx_max_10mb))
}

/** The attached file: icon, name, "Attached", then View and Change. */
@Composable
private fun TermsAttachedRow(
    terms: AgreementDocument,
    uploading: Boolean,
    opening: Boolean,
    canOpen: Boolean,
    canChange: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier.size(FILE_BLOCK).clip(ZillitTheme.shapes.medium).background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.File, tint = colors.accentText, size = 16.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = terms.title.ifBlank { terms.name },
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FieldHint(str(S.dm_docs_attached))
        }
        ZillitButton(
            text = if (opening) str(S.dm_nda_opening) else str(S.view),
            onClick = { onEvent(AccountHubEvent.OpenPoTerms) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Eye,
            loading = opening,
            enabled = canOpen && !opening && !uploading,
        )
        ZillitButton(
            text = if (uploading) str(S.ah_uploading) else str(S.change),
            onClick = { onEvent(AccountHubEvent.PickPoTerms) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Paperclip,
            loading = uploading,
            enabled = canChange && !uploading,
        )
    }
}

// -- invoices ------------------------------------------------------------------------

@Suppress("CyclomaticComplexMethod", "LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun ColumnScope.InvoicesModalBody(
    sectionId: String,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val value = state.setup.invoicesSetup.edited
    val editable = state.viewer.canEdit
    fun update(next: InvoicesSetup) = onEvent(AccountHubEvent.EditInvoicesSetup(next))
    when (sectionId) {
        "team" -> SubCard(
            hint = str(S.desktop_hub_who_may_post_invoices_and_up_to_what_value),
            action = { if (editable) GhostAddButton(
                str(S.cs_add_member),
                onClick = { onEvent(AccountHubEvent.ComposeInvoiceMember(null)) },
            ) },
            padded = false,
        ) {
            if (value.teamMembers.isEmpty()) {
                ZillitEmptyState(
                    title = str(S.desktop_hub_no_team_members_configured),
                    message = str(S.desktop_hub_add_the_accounts_payable_team_so_invoices_can_be_posted),
                    icon = ZillitIcons.Users,
                    action = if (editable) {
                        {
                            ZillitButton(
                                text = str(S.desktop_hub_add_first_member),
                                onClick = { onEvent(AccountHubEvent.ComposeInvoiceMember(null)) },
                                size = ButtonSize.Small,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            value.teamMembers.forEachIndexed { index, member ->
                HoverRow(
                    modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
                    onClick = if (editable) ({ onEvent(AccountHubEvent.ComposeInvoiceMember(index)) }) else null,
                    actions = { _ ->
                        if (editable) {
                            ZillitIconButton(
                                icon = ZillitIcons.Edit,
                                contentDescription = str(S.edit),
                                onClick = { onEvent(AccountHubEvent.ComposeInvoiceMember(index)) },
                            )
                            ZillitIconButton(
                                icon = ZillitIcons.Trash,
                                contentDescription = str(S.remove),
                                onClick = {
                                    update(
                                        value.copy(
                                            teamMembers = value.teamMembers.filterIndexed { i, _ -> i != index },
                                        ),
                                    )
                                },
                                tint = ZillitTheme.colors.danger,
                            )
                        }
                    },
                ) {
                    val user = state.user(member.userId)
                    PersonChip(
                        name = user?.name ?: str(S.unkone_user),
                        userId = member.userId,
                        role = user?.roleLabel,
                        modifier = Modifier.weight(1f),
                    )
                    FieldHint(postingLimitLabel(member, state))
                    if (member.runAccess) Pill(str(S.desktop_runs), tone = StatusTone.Done)
                    if (member.overrideAccess) Pill(str(S.dm_nom_table_override), tone = StatusTone.Pending)
                    if (member.isSenior) Pill(str(S.desktop_senior), tone = StatusTone.Progress)
                }
            }
        }
        "alerts" -> SubCard(hint = str(S.desktop_hub_which_events_accounts_payable_is_told_about)) {
            InvoiceAlert.entries.forEach { alert ->
                ToggleRow(
                    label = alert.label,
                    hint = alert.hint,
                    checked = alert in value.alerts,
                    onCheckedChange = { on ->
                        update(value.copy(alerts = if (on) value.alerts + alert else value.alerts - alert))
                    },
                    enabled = editable,
                )
            }
        }
        "runauth" -> RunAuthorisationPane(value, state, editable, onEvent, ::update)
        "rules" -> AssignmentRulesSection(
            rules = state.setup.invoiceRules.edited,
            module = "invoices",
            showVendors = true,
            state = state,
            editable = editable,
            onChange = { onEvent(AccountHubEvent.EditInvoiceRules(it)) },
        )
    }
}

/** The web's `limitLabel`: null is Unlimited, zero is Submit only, anything else is the cap. */
/**
 * The payment-run sign-off chain — the web's `RunAuthSection`: a level per
 * step, "THEN" between them, an insert rail above, between and below every
 * level, and a × on each person so one can leave a level without re-picking it.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun ColumnScope.RunAuthorisationPane(
    value: InvoicesSetup,
    state: AccountHubUiState,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
    update: (InvoicesSetup) -> Unit,
) {
    val levels = value.runAuthorisation
    fun insertAt(position: Int) = update(
        value.copy(runAuthorisation = levels.toMutableList().apply { add(position, RunAuthorisationTier()) })
            .renumbered(),
    )
    fun withLevel(index: Int, next: RunAuthorisationTier?) = update(
        value.copy(
            runAuthorisation = levels.mapIndexedNotNull { i, tier -> if (i == index) next else tier },
        ).renumbered(),
    )
    SubCard(hint = str(S.desktop_hub_the_sign_off_chain_that_gates_payment_runs_level_by)) {
        if (levels.isEmpty()) {
            ZillitEmptyState(
                title = str(S.desktop_hub_no_authorization_levels_yet),
                message = str(S.desktop_hub_add_a_level_and_pick_who_signs_it_off),
                icon = ZillitIcons.Shield,
                action = if (editable) {
                    {
                        ZillitButton(
                            text = str(S.desktop_hub_add_first_level),
                            onClick = { insertAt(0) },
                            size = ButtonSize.Small,
                        )
                    }
                } else {
                    null
                },
            )
            return@SubCard
        }
        if (editable) DashedInsertRail(str(S.desktop_insert_a_level_here)) { insertAt(0) }
        levels.forEachIndexed { index, tier ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Pill(str(S.ah_tier_label, tier.tier), tone = StatusTone.Progress)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    if (tier.userIds.isEmpty()) FieldHint(str(S.desktop_no_approvers_yet))
                    tier.userIds.forEach { id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PersonChip(name = state.userName(id), userId = id, role = state.user(id)?.roleLabel)
                            if (editable) {
                                ZillitIconButton(
                                    icon = ZillitIcons.Close,
                                    contentDescription = str(S.desktop_remove_approver),
                                    onClick = { withLevel(index, tier.copy(userIds = tier.userIds - id)) },
                                )
                            }
                        }
                    }
                    if (editable) {
                        ZillitButton(
                            text = str(S.desktop_hub_add_approvers),
                            onClick = {
                                onEvent(AccountHubEvent.OpenUserPicker(UserPickerPurpose.RunAuthorisation, index))
                            },
                            variant = ButtonVariant.Secondary,
                            size = ButtonSize.Small,
                            leadingIcon = ZillitIcons.UserPlus,
                        )
                    }
                }
                if (editable) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.desktop_remove_level),
                        onClick = { withLevel(index, null) },
                        tint = ZillitTheme.colors.danger,
                    )
                }
            }
            if (index < levels.lastIndex) Pill(str(S.desktop_email_rule_then))
            if (editable) DashedInsertRail(str(S.desktop_insert_a_level_here)) { insertAt(index + 1) }
        }
    }
}

private fun postingLimitLabel(member: InvoiceTeamMember, state: AccountHubUiState): String {
    val symbol = state.setup.currencies.saved.default?.symbol.orEmpty()
    return when {
        member.isSenior || member.isUnlimited -> str(S.dm_rates_buyout_covers_unlimited)
        member.isSubmitOnly -> str(S.desktop_submit_only)
        else -> str(
            S.desktop_hub_posting_limit_x,
            "$symbol${com.zillit.desktop.feature.accounthub.ui.components.groupAmount(member.postingLimit)}",
        )
    }
}

/** The invoices team-member dialog — the web's `InvoiceTeamMemberModal`. */
@Suppress("LongMethod", "CyclomaticComplexMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun InvoiceMemberDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val draft = state.setup.invoiceMemberDraft
    val member = draft?.member
    fun update(next: InvoiceTeamMember) = onEvent(AccountHubEvent.EditInvoiceMember(next))
    ZillitDialogShell(
        title = if (draft?.index == null) str(S.desktop_add_team_member) else str(S.desktop_edit_team_member),
        icon = ZillitIcons.Users,
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissInvoiceMember) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(AccountHubEvent.DismissInvoiceMember) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (draft?.index == null) str(S.add) else str(S.save),
                onClick = { onEvent(AccountHubEvent.CommitInvoiceMember) },
                enabled = !member?.userId.isNullOrBlank(),
            )
        },
    ) {
        if (member == null) return@ZillitDialogShell
        FieldLabel(str(S.desktop_team_member), required = true)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            if (member.userId.isBlank()) FieldHint(str(S.desktop_nobody_picked_yet)) else PersonChip(
                name = state.userName(member.userId),
                userId = member.userId,
                role = state.user(member.userId)?.roleLabel,
            )
            // The person is fixed once added, as on the web: an edit changes
            // what they may do, not who they are.
            if (draft.index == null) {
                ZillitButton(
                    text = if (member.userId.isBlank()) str(S.desktop_pick) else str(S.change),
                    onClick = { onEvent(AccountHubEvent.OpenUserPicker(UserPickerPurpose.InvoiceTeamMember)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
            }
        }
        val symbol = state.setup.currencies.saved.default?.symbol.orEmpty()
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(
                "${str(S.desktop_posting_limit)}${if (symbol.isBlank()) "" else " ($symbol)"}",
                modifier = Modifier.weight(1f),
            )
            // Blank is Unlimited, zero is submit-only (the web's contract);
            // a senior is always unlimited, so the tick is locked for one.
            ZillitCheckbox(
                checked = member.isSenior || member.isUnlimited,
                onCheckedChange = { update(member.withUnlimited(it)) },
                label = str(S.dm_rates_buyout_covers_unlimited),
                enabled = !member.isSenior,
            )
        }
        CalcField(
            value = if (member.isSenior || member.isUnlimited) "" else member.postingLimit,
            onValueChange = { update(member.copy(postingLimit = it.ifBlank { InvoiceTeamMember.SUBMIT_ONLY })) },
            placeholder = if (member.isSenior || member.isUnlimited) str(S.dm_rates_buyout_covers_unlimited) else "0",
            enabled = !member.isSenior && !member.isUnlimited,
        )
        ToggleRow(
            label = str(S.desktop_can_authorise_payment_runs),
            hint = str(S.desktop_inv_run_access_hint),
            checked = member.isSenior || member.runAccess,
            onCheckedChange = { update(member.copy(runAccess = it)) },
            enabled = !member.isSenior,
        )
        ToggleRow(
            label = str(S.desktop_can_override_approvals),
            hint = str(S.desktop_inv_override_access_hint),
            checked = member.isSenior || member.overrideAccess,
            onCheckedChange = { update(member.copy(overrideAccess = it)) },
            enabled = !member.isSenior,
        )
        ToggleRow(
            label = str(S.desktop_is_senior),
            hint = str(S.desktop_hub_is_senior_hint),
            checked = member.isSenior,
            onCheckedChange = { update(member.withSenior(it)) },
        )
    }
}

/**
 * The pay-period pair — the web's `PayPeriodBody`: "Week starts on" and "Week
 * ends on", picking either end moves the other, the window spelled out as a
 * pill, the date it locked on once the first timecard froze it, and a warning
 * for a stored window that is not seven days.
 */
@Suppress("LongMethod") // A form, read top to bottom; the order is the reading order.
@Composable
private fun ColumnScope.PayPeriodFields(
    value: PayrollSettings,
    editable: Boolean,
    update: (PayrollSettings) -> Unit,
) {
    val days = (PayrollSettings.MONDAY..PayrollSettings.SUNDAY).toList()
    val enabled = editable && !value.payPeriodLocked
    value.payPeriodLockedAt?.let { lockedAt ->
        ZillitNotice(
            text = str(S.desktop_hub_pay_period_locked_on, EpochDate.date(lockedAt)),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Lock,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            FieldLabel(str(S.desktop_hub_week_starts_on))
            ZillitSelect(
                value = value.payPeriodStartDay,
                options = days,
                onSelect = { day ->
                    update(value.copy(payPeriodStartDay = day, payPeriodEndDay = PayrollSettings.endFor(day)))
                },
                label = { PayrollSettings.dayName(it) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            FieldLabel(str(S.desktop_hub_week_ends_on))
            ZillitSelect(
                value = value.payPeriodEndDay,
                options = days,
                onSelect = { day ->
                    update(value.copy(payPeriodEndDay = day, payPeriodStartDay = PayrollSettings.startFor(day)))
                },
                label = { PayrollSettings.dayName(it) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Pill(value.payPeriodLabel.uppercase(), tone = StatusTone.Pending)
    if (value.payPeriodEndDay != PayrollSettings.endFor(value.payPeriodStartDay)) {
        ZillitNotice(
            text = str(S.desktop_hub_pay_period_not_seven_days),
            tone = StatusTone.Rejected,
            icon = ZillitIcons.Warning,
        )
    }
    if (!value.payPeriodLocked) FieldHint(str(S.desktop_hub_pay_period_unlocked_hint))
}

// -- assignment rules --------------------------------------------------------------

/**
 * The auto-assignment rules — the web's `AssignmentRulesSection`, shared by
 * every module setup. Conditions OR: any department, vendor or nominal, or an
 * amount at or over the minimum, sends the document to the assignee.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun ColumnScope.AssignmentRulesSection(
    rules: List<AssignmentRule>,
    module: String,
    showVendors: Boolean,
    state: AccountHubUiState,
    editable: Boolean,
    onChange: (List<AssignmentRule>) -> Unit,
) {
    // The accounts team, as the web's `AVAILABLE_USERS` — who a document can
    // be routed to — not every accepted crew member.
    val team = HubUsers.accountsTeam(state.users)
    val departments = state.departmentList
    val leaves = ChartOfAccounts.leaves(state.chart.accounts)
    // Read by the modal itself; the Vendors page's list when that is all there is.
    val vendors = state.setup.ruleVendors.ifEmpty { state.vendors.rows }
    SubCard(
        hint = str(S.desktop_hub_if_any_condition_matches_the_document_is_assigned_to_the),
        action = {
            if (editable) {
                GhostAddButton(str(S.desktop_add_rule), onClick = {
                    onChange(rules + AssignmentRules.newRule(
                        LocalIds.next("rule-new", rules.map { it.id }),
                        module,
                        team.firstOrNull()?.id.orEmpty(),
                    ))
                })
            }
        },
    ) {
        if (state.setup.rulesLoading) ZillitSpinner()
        if (rules.isEmpty() && !state.setup.rulesLoading) {
            FieldHint(str(S.desktop_hub_no_rules_yet_every_document_lands_with_its_raisers_department))
        }
        rules.forEachIndexed { index, rule ->
            fun patch(next: AssignmentRule) = onChange(rules.mapIndexed { i, r -> if (i == index) next else r })
            SubCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = str(S.desktop_hub_rule_n_assign_to, index + 1),
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    ZillitCheckbox(
                        checked = rule.isActive,
                        onCheckedChange = { patch(rule.copy(isActive = it)) },
                        label = str(S.active),
                        enabled = editable,
                    )
                    if (editable) {
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = str(S.desktop_remove_rule),
                            onClick = { onChange(rules.filterIndexed { i, _ -> i != index }) },
                            tint = ZillitTheme.colors.danger,
                        )
                    }
                }
                // A saved assignee who has since left the accounts team is
                // still shown, so the rule does not read as unassigned.
                val assignable = team + listOfNotNull(
                    state.user(rule.assignTo)?.takeIf { user -> team.none { it.id == user.id } },
                )
                HubSelect(
                    value = assignable.firstOrNull { it.id == rule.assignTo },
                    options = assignable,
                    label = { "${it.name} (${it.roleLabel.ifBlank { "—" }})" },
                    onSelect = { patch(rule.copy(assignTo = it?.id.orEmpty())) },
                    placeholder = str(S.desktop_pick_assignee),
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldLabel(str(S.desktop_hub_if_any_condition_matches))
                // Every multi-select keeps a saved value its list does not
                // hold (the web's atoms keep unlisted values): dropped from
                // the options, it was dropped from the rule on the next edit.
                val departmentOptions = withUnlisted(departments, rule.departments, { it.id }) {
                    HubDepartment(id = it, name = it.orDash())
                }
                HubMultiSelect(
                    selected = departmentOptions.filter { it.id in rule.departments },
                    options = departmentOptions,
                    label = { it.name },
                    onChange = { picked -> patch(rule.copy(departments = picked.map { it.id })) },
                    placeholder = str(S.desktop_any_department),
                    fieldLabel = str(S.departments),
                    enabled = editable,
                )
                if (showVendors) {
                    val vendorOptions = withUnlisted(vendors, rule.vendors, { it.id }) {
                        Vendor(id = it, name = it.orDash())
                    }
                    HubMultiSelect(
                        selected = vendorOptions.filter { it.id in rule.vendors },
                        options = vendorOptions,
                        label = { it.display },
                        onChange = { picked -> patch(rule.copy(vendors = picked.map { it.id })) },
                        placeholder = str(S.desktop_any_vendor),
                        fieldLabel = str(S.ah_vendors),
                        enabled = editable,
                    )
                }
                val nominalOptions = withUnlisted(leaves, rule.nominalCodes, { it.code }) {
                    CoaAccount(id = "unlisted-$it", code = it)
                }
                HubMultiSelect(
                    selected = nominalOptions.filter { it.code in rule.nominalCodes },
                    options = nominalOptions,
                    label = { it.display },
                    onChange = { picked -> patch(rule.copy(nominalCodes = picked.map { it.code })) },
                    placeholder = str(S.desktop_any_nominal),
                    fieldLabel = str(S.dm_step7_title),
                    enabled = editable,
                )
                CalcField(
                    value = rule.amountMin,
                    onValueChange = { patch(rule.copy(amountMin = it)) },
                    label = str(S.desktop_hub_amount_at_or_above),
                    placeholder = str(S.desktop_any_amount),
                    enabled = editable,
                )
            }
        }
    }
}

/**
 * [options] and a stand-in for every chosen id they do not hold — shown as
 * chosen and removable, never silently dropped.
 */
private fun <T> withUnlisted(
    options: List<T>,
    chosen: List<String>,
    key: (T) -> String,
    standIn: (String) -> T,
): List<T> = options + chosen.distinct().filter { id -> options.none { key(it) == id } }.map(standIn)

// -- payroll ------------------------------------------------------------------------

@Suppress("CyclomaticComplexMethod", "LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun ColumnScope.PayrollModalBody(
    sectionId: String,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val value = state.setup.payrollSettings.edited
    val editable = state.viewer.canEdit
    fun update(next: PayrollSettings) = onEvent(AccountHubEvent.EditPayrollSettings(next))
    when (sectionId) {
        "approvers" -> SubCard(
            hint = str(S.desktop_hub_who_may_sign_off_a_payroll_run_reaches_the_timecard),
            action = {
                if (editable) {
                    ZillitButton(
                        text = str(S.add_members),
                        onClick = { onEvent(AccountHubEvent.OpenUserPicker(UserPickerPurpose.PayrollApprovers)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.UserPlus,
                    )
                }
            },
        ) {
            if (value.approverIds.isEmpty()) FieldHint(str(S.desktop_no_approvers_yet))
            value.approverIds.forEach { id ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PersonChip(
                        name = state.userName(id),
                        userId = id,
                        role = state.user(id)?.roleLabel,
                        modifier = Modifier.weight(1f),
                    )
                    if (editable) {
                        ZillitIconButton(
                            icon = ZillitIcons.Close,
                            contentDescription = str(S.desktop_remove_approver),
                            onClick = { update(value.copy(approverIds = value.approverIds - id)) },
                        )
                    }
                }
            }
        }
        "pay_period" -> {
            SubCard(hint = str(S.desktop_hub_the_seven_days_a_pay_period_covers_picking_either_end)) {
                PayPeriodFields(value, editable, ::update)
            }
            // The journal choices ride the same pane, as the web's
            // "Pay Period & Journal" does — each under its own heading.
            SubCard(
                title = str(S.desktop_description_format),
                hint = str(S.desktop_hub_how_a_payroll_journal_lines_description_is_cased),
            ) {
                JournalDescriptionFormat.entries.forEach { format ->
                    RadioCard(
                        label = format.label,
                        sample = "e.g. ${format.sample}",
                        active = value.journalDescriptionFormat == format,
                        onClick = { update(value.copy(journalDescriptionFormat = format)) },
                        enabled = editable,
                    )
                }
            }
            SubCard(title = str(S.desktop_journal_grouping)) {
                ToggleRow(
                    label = str(S.desktop_hub_group_by_pay_category),
                    hint = str(S.desktop_hub_group_the_journal_ledger_rows_into_ots_penalties_premiums_and),
                    checked = value.journalGroupByCategory,
                    onCheckedChange = { update(value.copy(journalGroupByCategory = it)) },
                    enabled = editable,
                )
            }
        }
        "payroll_accounts" -> SubCard(
            hint = str(S.desktop_hub_the_balance_sheet_codes_payroll_posts_through_real_chart_entries),
            action = { if (editable) GhostAddButton(
                str(S.desktop_add_edit),
                onClick = { onEvent(AccountHubEvent.OpenPayrollAccounts) },
            ) },
        ) {
            if (value.payrollAccounts.isEmpty()) {
                FieldHint(str(S.desktop_hub_no_payroll_accounts_yet_use_add_edit_to_create_one))
            }
            value.payrollAccounts.forEach { code ->
                val account = state.chart.accounts.firstOrNull { it.code.equals(code, ignoreCase = true) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(text = code, style = ZillitTheme.typography.numeric)
                    ZillitText(
                        text = account?.name ?: str(S.desktop_hub_not_in_the_chart),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    account?.let { Pill(it.lineType.tagLabel) }
                    if (editable) {
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = str(S.remove),
                            onClick = { onEvent(AccountHubEvent.AskRemove(SetupRemoval.PayrollAccountCode(
                                code,
                                account?.id,
                            ))) },
                            tint = ZillitTheme.colors.danger,
                        )
                    }
                }
            }
        }
        "payroll_groups" -> SubCard(
            hint = str(S.desktop_hub_assign_crew_to_a_specific_accountant_by_department_role_or),
            action = { if (editable) GhostAddButton(
                str(S.mtg_add_group),
                onClick = { onEvent(AccountHubEvent.ComposePayrollGroup(null)) },
            ) },
        ) {
            if (state.setup.payrollGroupsLoading) ZillitSpinner()
            if (state.setup.payrollGroups.isEmpty() && !state.setup.payrollGroupsLoading) FieldHint(
                str(S.desktop_hub_no_payroll_groups_yet),
            )
            state.setup.payrollGroups.forEach { group ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitAvatar(name = state.userName(group.assigneeId), userId = group.assigneeId, size = 28.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        ZillitText(
                            text = state.userName(group.assigneeId),
                            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        // What is routed, by name — the web's chips — not a count.
                        GroupRouting(group, state)
                    }
                    if (editable) {
                        ZillitIconButton(
                            icon = ZillitIcons.Edit,
                            contentDescription = str(S.edit),
                            onClick = { onEvent(AccountHubEvent.ComposePayrollGroup(group)) },
                        )
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = str(S.delete),
                            onClick = { onEvent(AccountHubEvent.AskRemove(SetupRemoval.PayrollGroupRow(group))) },
                            tint = ZillitTheme.colors.danger,
                        )
                    }
                }
            }
        }
    }
}

/** A payroll group's departments, designations and crew as chips — the web's `PayrollGroupsBody` row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupRouting(group: com.zillit.desktop.feature.accounthub.domain.PayrollGroup, state: AccountHubUiState) {
    val departments = state.departmentList
    val designations = departments.flatMap { it.designations }
    val names = group.departmentIds.map { id -> departments.firstOrNull { it.id == id }?.name ?: id.orDash() } +
        group.designationIds.map { id -> designations.firstOrNull { it.id == id }?.name ?: id.orDash() } +
        group.userIds.map { state.userName(it) }
    if (names.isEmpty()) {
        FieldHint(str(S.desktop_nothing_routed_yet))
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        names.forEach { Chip(text = it) }
    }
}

/** "Add Payroll Group" / "Edit Payroll Group" — the web's modal. */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun PayrollGroupDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val draft = state.setup.payrollGroupDraft
    val team = HubUsers.available(state.users)
    val departments = state.departmentList
    val designations = departments.flatMap { d -> d.designations.map { it to d } }
    ZillitDialogShell(
        title = if (draft?.id.isNullOrBlank()) str(S.desktop_add_payroll_group) else str(S.desktop_edit_payroll_group),
        icon = ZillitIcons.Users,
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissPayrollGroup) },
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(AccountHubEvent.DismissPayrollGroup) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(AccountHubEvent.SavePayrollGroup) },
                enabled = draft?.canSave == true && !state.setup.payrollGroupSaving,
                loading = state.setup.payrollGroupSaving,
            )
        },
    ) {
        if (draft == null) return@ZillitDialogShell
        fun update(next: com.zillit.desktop.feature.accounthub.domain.PayrollGroup) =
            onEvent(AccountHubEvent.EditPayrollGroup(next))
        HubSelect(
            value = team.firstOrNull { it.id == draft.assigneeId },
            options = team,
            label = { it.name },
            onSelect = { update(draft.copy(assigneeId = it?.id.orEmpty())) },
            placeholder = str(S.desktop_select_accountant),
            fieldLabel = str(S.desktop_accountant),
            secondary = { it.roleLabel },
            modifier = Modifier.fillMaxWidth(),
        )
        FieldHint(str(S.desktop_hub_the_accounts_team_member_this_groups_payroll_routes_to))
        HubMultiSelect(
            selected = departments.filter { it.id in draft.departmentIds },
            options = departments,
            label = { it.name },
            onChange = { picked -> update(draft.copy(departmentIds = picked.map { it.id })) },
            placeholder = str(S.desktop_select_departments_2),
            fieldLabel = str(S.departments),
        )
        FieldHint(str(S.desktop_hub_all_crew_in_these_departments_route_here))
        HubMultiSelect(
            selected = designations.filter { it.first.id in draft.designationIds },
            options = designations,
            label = { "${it.first.name} · ${it.second.name}" },
            onChange = { picked -> update(draft.copy(designationIds = picked.map { it.first.id })) },
            placeholder = str(S.desktop_select_designations),
            fieldLabel = str(S.designations),
        )
        FieldHint(str(S.desktop_hub_crew_with_these_roles_route_here))
        HubMultiSelect(
            selected = team.filter { it.id in draft.userIds },
            options = team,
            label = { it.name },
            onChange = { picked -> update(draft.copy(userIds = picked.map { it.id })) },
            placeholder = str(S.desktop_select_crew_members),
            fieldLabel = str(S.desktop_specific_crew),
        )
        FieldHint(str(S.desktop_hub_pin_individual_crew_members))
    }
}

/** The payroll-accounts grid — the web's `PayrollAccountsPage`: code, name, level, per row. */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun PayrollAccountsDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val draft = state.setup.payrollAccounts
    ZillitDialogShell(
        title = str(S.desktop_payroll_accounts),
        subtitle = str(S.desktop_hub_rows_without_an_id_are_created_in_the_chart_a),
        icon = ZillitIcons.Ledger,
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissPayrollAccounts) },
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(AccountHubEvent.DismissPayrollAccounts) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(AccountHubEvent.SavePayrollAccounts) },
                loading = draft?.saving == true,
                enabled = draft?.saving != true,
            )
        },
    ) {
        if (draft == null) return@ZillitDialogShell
        fun update(rows: List<PayrollAccountRow>) = onEvent(AccountHubEvent.EditPayrollAccounts(rows))
        if (draft.unmatched.isNotEmpty()) {
            ZillitNotice(
                text = str(S.desktop_hub_payroll_codes_not_in_chart, draft.unmatched.joinToString(", ")),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }
        draft.rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitTextField(
                    value = row.code,
                    onValueChange = { text ->
                        update(draft.rows.mapIndexed { i, r -> if (i == index) r.copy(code = text) else r })
                    },
                    placeholder = str(S.code),
                    modifier = Modifier.width(CODE_WIDTH),
                )
                ZillitTextField(
                    value = row.name,
                    onValueChange = { text ->
                        update(draft.rows.mapIndexed { i, r -> if (i == index) r.copy(name = text) else r })
                    },
                    placeholder = str(S.av_display_name),
                    modifier = Modifier.weight(1f),
                )
                ZillitSelect(
                    value = row.lineType,
                    options = CoaLineType.entries,
                    onSelect = { type ->
                        update(draft.rows.mapIndexed { i, r -> if (i == index) r.copy(lineType = type) else r })
                    },
                    label = { it.tagLabel },
                    enabled = row.id == null,
                    modifier = Modifier.width(CODE_WIDTH),
                )
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.desktop_remove_row),
                    onClick = { update(draft.rows.filterIndexed { i, _ -> i != index }) },
                )
            }
        }
        GhostAddButton(str(S.cs_add_row), onClick = { update(draft.rows + PayrollAccountRow()) })
        FieldHint(str(S.desktop_hub_removing_a_saved_code_here_only_drops_it_from_the))
    }
}

/** The one user picker every user field on this page opens. */
@Composable
private fun SharedUserPicker(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val picker = state.setup.userPicker
    // Payroll approvers and the AP team come from the accounts team (the
    // web's ACCOUNTS_TEAM_USERS / AVAILABLE_USERS); a run level may be signed
    // by anyone accepted, but not by somebody already on another level.
    val team = HubUsers.accountsTeam(state.users)
    val onTeam = state.setup.invoicesSetup.edited.teamMembers
        .filterIndexed { i, _ -> i != state.setup.invoiceMemberDraft?.index }
        .map { it.userId }
    val excluded = picker?.let { state.setup.pickerExcluded(it) }.orEmpty()
    val (title, pool) = when (picker?.purpose) {
        UserPickerPurpose.PayrollApprovers -> str(S.desktop_payroll_approvers) to team
        UserPickerPurpose.InvoiceTeamMember -> str(S.desktop_team_member) to team.filter { it.id !in onTeam }
        UserPickerPurpose.RunAuthorisation ->
            str(S.ah_tier_label, picker.index + 1) to HubUsers.available(state.users).filter { it.id !in excluded }
        UserPickerPurpose.PayrollGroupAssignee ->
            str(S.desktop_accountant) to HubUsers.accountsTeam(state.users).ifEmpty {
                HubUsers.available(state.users)
            }
        UserPickerPurpose.PayrollGroupCrew -> str(S.crew) to HubUsers.available(state.users)
        UserPickerPurpose.ClosingRecipients -> str(S.recipients) to HubUsers.available(state.users)
        null -> "" to emptyList()
    }
    UserPickerDialog(
        visible = picker != null,
        title = title,
        users = pool,
        selected = picker?.selected.orEmpty(),
        search = picker?.search.orEmpty(),
        onSearch = { onEvent(AccountHubEvent.SearchUserPicker(it)) },
        onToggle = { onEvent(AccountHubEvent.ToggleUserPick(it)) },
        onApply = { onEvent(AccountHubEvent.ApplyUserPicker) },
        onDismiss = { onEvent(AccountHubEvent.DismissUserPicker) },
    )
}

private val DIALOG_WIDTH = 620.dp
private val PREFIX_WIDTH = 140.dp
private val FILE_BLOCK = 32.dp
private val CODE_WIDTH = 140.dp
