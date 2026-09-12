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
import com.zillit.desktop.feature.accounthub.ui.components.AlwaysRow
import com.zillit.desktop.feature.accounthub.ui.components.CalcField
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
    // The web's three (`POSetupDetail`), with its chips. Assignment rules and
    // Form Configuration left that modal in the web's 8c4f2b0cc; the rules are
    // the PO module's own settings page's, the forms editor has its own area.
    SetupModal.PurchaseOrders -> state.setup.poSetup.edited.let { po ->
        listOf(
            SetupModalSection("format", "Description Format", 1),
            SetupModalSection("rental", "Rental & Split", po.rentalCount),
            SetupModalSection("issuance", "Issuance", po.issuanceCount),
        )
    }
    SetupModal.Invoices -> listOf(
        SetupModalSection("team", "Team & Posting Rights", state.setup.invoicesSetup.edited.teamMembers.size),
        SetupModalSection("alerts", "Alert Preferences", state.setup.invoicesSetup.edited.alerts.size),
        SetupModalSection("runauth", "Run Authorization", state.setup.invoicesSetup.edited.runAuthorisation.size),
        SetupModalSection("rules", "Auto-Assignment Rules", state.setup.invoiceRules.edited.size),
    )
    SetupModal.Payroll -> listOf(
        SetupModalSection("approvers", "Approvers", state.setup.payrollSettings.edited.approverIds.size),
        SetupModalSection("pay_period", "Pay Period"),
        SetupModalSection("journal_description", "Description Format"),
        SetupModalSection("journal_grouping", "Journal Grouping"),
        SetupModalSection(
            "payroll_accounts",
            "Payroll Accounts",
            state.setup.payrollSettings.edited.payrollAccounts.size,
        ),
        SetupModalSection("payroll_groups", "Payroll Groups", state.setup.payrollGroups.size),
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
            hint = "Controls how ledger entry descriptions are auto-formatted from the PO line + posting date.",
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
        "rental" -> SubCard(hint = "How rental POs are handled when posting to the ledger.") {
            ToggleRow(
                label = "Auto-split rental POs",
                hint = "Automatically detect and split rental / hire POs by period.",
                checked = value.autoSplitRentals,
                onCheckedChange = { update(value.copy(autoSplitRentals = it)) },
                enabled = editable,
            )
            HairLine()
            // Offered whether or not auto-split is on, as the web does: the
            // cadence is remembered across the switch.
            FieldLabel("Default split type")
            ZillitSegmented(
                options = PoSplitType.entries.map { ZillitTab(it.wire, it.label) },
                activeId = value.splitType.wire,
                onSelect = { id -> if (editable) update(value.copy(splitType = PoSplitType.from(id))) },
            )
            HairLine()
            // Always-on: the service forces both whatever is stored, so the web
            // shows a static "Always" where a switch would lie.
            AlwaysRow("Require effective date", "Block PO posting without a confirmed effective date.")
            HairLine()
            AlwaysRow("Enforce period close", "Prevent back-dating entries to closed accounting periods.")
        }
        "issuance" -> SubCard(hint = "The document issued with a purchase order and the prefix on its number.") {
            FieldLabel("PO number prefix")
            ZillitTextField(
                value = value.numberPrefix,
                onValueChange = { update(value.copy(numberPrefix = PurchaseOrderSetup.normalisePrefix(it))) },
                placeholder = "e.g. QW",
                // Capped by normalisePrefix rather than maxLength: the field's
                // counter is not on the web's input.
                enabled = editable,
                modifier = Modifier.width(PREFIX_WIDTH),
            )
            FieldHint(PurchaseOrderSetup.PREFIX_HINT)
            HairLine()
            FieldLabel("Terms and Conditions document")
            FieldHint("Issued with every purchase order. Replaces the old Terms of Engagement clause list.")
            TermsDocumentBlock(state, canChange = editable && canAttach, canOpen = canOpen, onEvent = onEvent)
            if (editable && !canAttach) {
                ZillitNotice(
                    text = "Attaching is unavailable — this window has no file storage wired.",
                    tone = StatusTone.Neutral,
                    icon = ZillitIcons.Info,
                )
            }
            // Amendments are built but paused behind the web's `AMENDMENTS_ENABLED = false`;
            // the stored flag round-trips untouched and no row is shown.
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
            text = if (uploading) "Uploading…" else "Add attachment",
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
    FieldHint("PDF, DOC or DOCX · max 10MB")
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
            FieldHint("Attached")
        }
        ZillitButton(
            text = if (opening) "Opening…" else "View",
            onClick = { onEvent(AccountHubEvent.OpenPoTerms) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Eye,
            loading = opening,
            enabled = canOpen && !opening && !uploading,
        )
        ZillitButton(
            text = if (uploading) "Uploading…" else "Change",
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
            hint = "Who may post invoices, and up to what value.",
            action = { if (editable) GhostAddButton(
                "Add member",
                onClick = { onEvent(AccountHubEvent.ComposeInvoiceMember(null)) },
            ) },
            padded = false,
        ) {
            if (value.teamMembers.isEmpty()) {
                ZillitEmptyState(
                    title = "No team members configured",
                    message = "Add the accounts-payable team so invoices can be posted.",
                    icon = ZillitIcons.Users,
                )
            }
            value.teamMembers.forEachIndexed { index, member ->
                HoverRow(
                    modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
                    onClick = if (editable) ({ onEvent(AccountHubEvent.ComposeInvoiceMember(index)) }) else null,
                    actions = { hovered ->
                        if (editable && hovered) {
                            ZillitIconButton(
                                icon = ZillitIcons.Edit,
                                contentDescription = "Edit",
                                onClick = { onEvent(AccountHubEvent.ComposeInvoiceMember(index)) },
                            )
                            ZillitIconButton(
                                icon = ZillitIcons.Trash,
                                contentDescription = "Remove",
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
                        name = user?.name ?: "Unknown user",
                        role = user?.roleLabel,
                        modifier = Modifier.weight(1f),
                    )
                    FieldHint(postingLimitLabel(member, state))
                    if (member.runAccess) Pill("Runs", tone = StatusTone.Done)
                    if (member.overrideAccess) Pill("Override", tone = StatusTone.Pending)
                    if (member.isSenior) Pill("Senior", tone = StatusTone.Progress)
                }
            }
        }
        "alerts" -> SubCard(hint = "Which events accounts payable is told about.") {
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
        "runauth" -> SubCard(
            hint = "The sign-off chain that gates payment runs, level by level.",
            action = {
                if (editable) {
                    GhostAddButton("Add level", onClick = {
                        update(
                            value.copy(runAuthorisation = value.runAuthorisation + RunAuthorisationTier()).renumbered(),
                        )
                    })
                }
            },
        ) {
            if (value.runAuthorisation.isEmpty()) {
                ZillitEmptyState(
                    title = "No authorization levels yet",
                    message = "Add a level and pick who signs it off.",
                    icon = ZillitIcons.Shield,
                )
            }
            value.runAuthorisation.forEachIndexed { index, tier ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    Pill("Level ${tier.tier}", tone = StatusTone.Progress)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                    ) {
                        if (tier.userIds.isEmpty()) FieldHint("Nobody yet")
                        tier.userIds.forEach {
                            id -> PersonChip(name = state.userName(id), role = state.user(id)?.roleLabel)
                        }
                    }
                    if (editable) {
                        ZillitButton(
                            text = "Add Users",
                            onClick = { onEvent(AccountHubEvent.OpenUserPicker(
                                UserPickerPurpose.RunAuthorisation,
                                index,
                            )) },
                            variant = ButtonVariant.Secondary,
                            size = ButtonSize.Small,
                            leadingIcon = ZillitIcons.UserPlus,
                        )
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = "Remove level",
                            onClick = {
                                update(
                                    value
                                        .copy(
                                            runAuthorisation = value.runAuthorisation.filterIndexed { i, _ ->
                                                i != index
                                            },
                                        )
                                        .renumbered(),
                                )
                            },
                            tint = ZillitTheme.colors.danger,
                        )
                    }
                }
            }
        }
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

private fun postingLimitLabel(member: InvoiceTeamMember, state: AccountHubUiState): String {
    val symbol = state.setup.currencies.saved.default?.symbol.orEmpty()
    return when {
        member.postingLimit.isBlank() -> "No limit set"
        member.postingLimit == "0" -> "Unlimited"
        else -> "Posting limit " +
            "$symbol${com.zillit.desktop.feature.accounthub.ui.components.groupAmount(member.postingLimit)}"
    }
}

/** The invoices team-member dialog — the web's `InvoiceTeamMemberModal`. */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun InvoiceMemberDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val draft = state.setup.invoiceMemberDraft
    val member = draft?.member
    fun update(next: InvoiceTeamMember) = onEvent(AccountHubEvent.EditInvoiceMember(next))
    ZillitDialogShell(
        title = if (draft?.index == null) "Add team member" else "Edit team member",
        icon = ZillitIcons.Users,
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissInvoiceMember) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissInvoiceMember) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (draft?.index == null) "Add" else "Save",
                onClick = { onEvent(AccountHubEvent.CommitInvoiceMember) },
                enabled = !member?.userId.isNullOrBlank(),
            )
        },
    ) {
        if (member == null) return@ZillitDialogShell
        FieldLabel("Team member", required = true)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            if (member.userId.isBlank()) FieldHint("Nobody picked yet") else PersonChip(
                name = state.userName(member.userId),
                role = state.user(member.userId)?.roleLabel,
            )
            ZillitButton(
                text = if (member.userId.isBlank()) "Pick" else "Change",
                onClick = { onEvent(AccountHubEvent.OpenUserPicker(UserPickerPurpose.InvoiceTeamMember)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
        CalcField(
            value = member.postingLimit,
            onValueChange = { update(member.copy(postingLimit = it)) },
            label = "Posting limit",
            placeholder = "0.00",
            helperText = "0 means unlimited; blank means not set.",
        )
        ToggleRow(
            label = "Run access",
            hint = "May start a payment run.",
            checked = member.runAccess,
            onCheckedChange = { update(member.copy(runAccess = it)) },
        )
        ToggleRow(
            label = "Override access",
            hint = "May post over the limit.",
            checked = member.overrideAccess,
            onCheckedChange = { update(member.copy(overrideAccess = it)) },
        )
        ToggleRow(
            label = "Senior",
            hint = "Counts as a senior sign-off.",
            checked = member.isSenior,
            onCheckedChange = { update(member.copy(isSenior = it)) },
        )
    }
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
    val team = HubUsers.available(state.users)
    val departments = state.departmentList
    val leaves = ChartOfAccounts.leaves(state.chart.accounts)
    SubCard(
        hint = "If any condition matches, the document is assigned to the chosen person.",
        action = {
            if (editable) {
                GhostAddButton("Add rule", onClick = {
                    onChange(rules + AssignmentRules.newRule(
                        "rule-new-${rules.size}-${rules.hashCode()}",
                        module,
                        team.firstOrNull()?.id.orEmpty(),
                    ))
                })
            }
        },
    ) {
        if (state.setup.rulesLoading) ZillitSpinner()
        if (rules.isEmpty() && !state.setup.rulesLoading) FieldHint("No rules yet — every document lands with its " +
            "raiser's department.")
        rules.forEachIndexed { index, rule ->
            fun patch(next: AssignmentRule) = onChange(rules.mapIndexed { i, r -> if (i == index) next else r })
            SubCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = "Rule ${index + 1} · Assign to",
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    ZillitCheckbox(
                        checked = rule.isActive,
                        onCheckedChange = { patch(rule.copy(isActive = it)) },
                        label = "Active",
                        enabled = editable,
                    )
                    if (editable) {
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = "Remove rule",
                            onClick = { onChange(rules.filterIndexed { i, _ -> i != index }) },
                            tint = ZillitTheme.colors.danger,
                        )
                    }
                }
                HubSelect(
                    value = team.firstOrNull { it.id == rule.assignTo },
                    options = team,
                    label = { "${it.name} (${it.roleLabel.ifBlank { "—" }})" },
                    onSelect = { patch(rule.copy(assignTo = it?.id.orEmpty())) },
                    placeholder = "Pick assignee…",
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldLabel("If any condition matches")
                HubMultiSelect(
                    selected = departments.filter { it.id in rule.departments },
                    options = departments,
                    label = { it.name },
                    onChange = { picked -> patch(rule.copy(departments = picked.map { it.id })) },
                    placeholder = "Any department",
                    fieldLabel = "Departments",
                    enabled = editable,
                )
                if (showVendors) {
                    val vendors = state.vendors.rows
                    HubMultiSelect(
                        selected = vendors.filter { it.id in rule.vendors },
                        options = vendors,
                        label = { it.display },
                        onChange = { picked -> patch(rule.copy(vendors = picked.map { it.id })) },
                        placeholder = if (vendors.isEmpty()) "Any vendor (open Vendors once to load the " +
                            "list)" else "Any vendor",
                        fieldLabel = "Vendors",
                        enabled = editable,
                    )
                }
                HubMultiSelect(
                    selected = leaves.filter { it.code in rule.nominalCodes },
                    options = leaves,
                    label = { it.display },
                    onChange = { picked -> patch(rule.copy(nominalCodes = picked.map { it.code })) },
                    placeholder = "Any nominal",
                    fieldLabel = "Nominal codes",
                    enabled = editable,
                )
                CalcField(
                    value = rule.amountMin,
                    onValueChange = { patch(rule.copy(amountMin = it)) },
                    label = "Amount at or above",
                    placeholder = "Any amount",
                    enabled = editable,
                )
            }
        }
    }
}

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
            hint = "Who may sign off a payroll run. Reaches the timecard and payroll-run surfaces directly.",
            action = {
                if (editable) {
                    ZillitButton(
                        text = "Add Users",
                        onClick = { onEvent(AccountHubEvent.OpenUserPicker(UserPickerPurpose.PayrollApprovers)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.UserPlus,
                    )
                }
            },
        ) {
            if (value.approverIds.isEmpty()) FieldHint("No approvers yet.")
            value.approverIds.forEach { id ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PersonChip(
                        name = state.userName(id),
                        role = state.user(id)?.roleLabel,
                        modifier = Modifier.weight(1f),
                    )
                    if (editable) {
                        ZillitIconButton(
                            icon = ZillitIcons.Close,
                            contentDescription = "Remove approver",
                            onClick = { update(value.copy(approverIds = value.approverIds - id)) },
                        )
                    }
                }
            }
        }
        "pay_period" -> SubCard(hint = "The seven days a pay period covers. Picking either end moves the other.") {
            val days = (PayrollSettings.MONDAY..PayrollSettings.SUNDAY).toList()
            val enabled = editable && !value.payPeriodLocked
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitSelect(
                    value = value.payPeriodStartDay,
                    options = days,
                    onSelect = { day -> update(value.copy(
                        payPeriodStartDay = day,
                        payPeriodEndDay = PayrollSettings.endFor(day),
                    )) },
                    label = { PayrollSettings.dayName(it) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(text = "→", style = ZillitTheme.typography.bodyMedium)
                ZillitSelect(
                    value = value.payPeriodEndDay,
                    options = days,
                    onSelect = { day -> update(value.copy(
                        payPeriodEndDay = day,
                        payPeriodStartDay = PayrollSettings.startFor(day),
                    )) },
                    label = { PayrollSettings.dayName(it) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
            if (value.payPeriodLocked) {
                ZillitNotice(
                    text = "The pay period is fixed — this production already has timecards against it, and the " +
                        "service refuses a change once that is true.",
                    tone = StatusTone.Neutral,
                    icon = ZillitIcons.Info,
                )
            }
        }
        "journal_description" -> SubCard(hint = "How a payroll journal line's description is cased.") {
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
        "journal_grouping" -> SubCard {
            ToggleRow(
                label = "Group by pay category",
                hint = "Group the Journal Ledger rows into OTs, penalties, premiums and turnarounds (under each " +
                    "company). Off = flat rows.",
                checked = value.journalGroupByCategory,
                onCheckedChange = { update(value.copy(journalGroupByCategory = it)) },
                enabled = editable,
            )
        }
        "payroll_accounts" -> SubCard(
            hint = "The balance-sheet codes payroll posts through. Real chart entries — the server keeps the two " +
                "in step.",
            action = { if (editable) GhostAddButton(
                "Add / Edit",
                onClick = { onEvent(AccountHubEvent.OpenPayrollAccounts) },
            ) },
        ) {
            if (value.payrollAccounts.isEmpty()) FieldHint("No payroll accounts yet — use Add / Edit to create one.")
            value.payrollAccounts.forEach { code ->
                val account = state.chart.accounts.firstOrNull { it.code.equals(code, ignoreCase = true) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(text = code, style = ZillitTheme.typography.numeric)
                    ZillitText(
                        text = account?.name ?: "Not in the chart",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    account?.let { Pill(it.lineType.tagLabel) }
                    if (editable) {
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = "Remove",
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
            hint = "Assign crew to a specific accountant — by department, role or name — so payroll feeds can be " +
                "scoped to them.",
            action = { if (editable) GhostAddButton(
                "Add group",
                onClick = { onEvent(AccountHubEvent.ComposePayrollGroup(null)) },
            ) },
        ) {
            if (state.setup.payrollGroupsLoading) ZillitSpinner()
            if (state.setup.payrollGroups.isEmpty() && !state.setup.payrollGroupsLoading) FieldHint(
                "No payroll groups yet.",
            )
            state.setup.payrollGroups.forEach { group ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitAvatar(name = state.userName(group.assigneeId), size = 28.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        ZillitText(
                            text = state.userName(group.assigneeId),
                            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        FieldHint(
                            listOfNotNull(
                                group.departmentIds.size
                                    .takeIf { it > 0 }?.let { "$it department${if (it == 1) "" else "s"}" },
                                group.designationIds.size
                                    .takeIf { it > 0 }?.let { "$it designation${if (it == 1) "" else "s"}" },
                                group.userIds.size.takeIf { it > 0 }?.let { "$it crew" },
                            ).joinToString(" · ").ifBlank { "Nothing routed yet" },
                        )
                    }
                    if (editable) {
                        ZillitIconButton(
                            icon = ZillitIcons.Edit,
                            contentDescription = "Edit",
                            onClick = { onEvent(AccountHubEvent.ComposePayrollGroup(group)) },
                        )
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = "Delete",
                            onClick = { onEvent(AccountHubEvent.AskRemove(SetupRemoval.PayrollGroupRow(group))) },
                            tint = ZillitTheme.colors.danger,
                        )
                    }
                }
            }
        }
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
        title = if (draft?.id.isNullOrBlank()) "Add Payroll Group" else "Edit Payroll Group",
        icon = ZillitIcons.Users,
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissPayrollGroup) },
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissPayrollGroup) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Save",
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
            placeholder = "Select accountant…",
            fieldLabel = "Accountant",
            secondary = { it.roleLabel },
            modifier = Modifier.fillMaxWidth(),
        )
        FieldHint("The accounts-team member this group's payroll routes to.")
        HubMultiSelect(
            selected = departments.filter { it.id in draft.departmentIds },
            options = departments,
            label = { it.name },
            onChange = { picked -> update(draft.copy(departmentIds = picked.map { it.id })) },
            placeholder = "Select departments…",
            fieldLabel = "Departments",
        )
        FieldHint("All crew in these departments route here.")
        HubMultiSelect(
            selected = designations.filter { it.first.id in draft.designationIds },
            options = designations,
            label = { "${it.first.name} · ${it.second.name}" },
            onChange = { picked -> update(draft.copy(designationIds = picked.map { it.first.id })) },
            placeholder = "Select designations…",
            fieldLabel = "Designations",
        )
        FieldHint("Crew with these roles route here.")
        HubMultiSelect(
            selected = team.filter { it.id in draft.userIds },
            options = team,
            label = { it.name },
            onChange = { picked -> update(draft.copy(userIds = picked.map { it.id })) },
            placeholder = "Select crew members…",
            fieldLabel = "Specific crew",
        )
        FieldHint("Pin individual crew members.")
    }
}

/** The payroll-accounts grid — the web's `PayrollAccountsPage`: code, name, level, per row. */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun PayrollAccountsDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val draft = state.setup.payrollAccounts
    ZillitDialogShell(
        title = "Payroll accounts",
        subtitle = "Rows without an id are created in the chart; a renamed row updates it.",
        icon = ZillitIcons.Ledger,
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissPayrollAccounts) },
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissPayrollAccounts) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Save",
                onClick = { onEvent(AccountHubEvent.SavePayrollAccounts) },
                loading = draft?.saving == true,
                enabled = draft?.saving != true,
            )
        },
    ) {
        if (draft == null) return@ZillitDialogShell
        fun update(rows: List<PayrollAccountRow>) = onEvent(AccountHubEvent.EditPayrollAccounts(rows))
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
                    placeholder = "Code",
                    modifier = Modifier.width(CODE_WIDTH),
                )
                ZillitTextField(
                    value = row.name,
                    onValueChange = { text ->
                        update(draft.rows.mapIndexed { i, r -> if (i == index) r.copy(name = text) else r })
                    },
                    placeholder = "Display name",
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
                    contentDescription = "Remove row",
                    onClick = { update(draft.rows.filterIndexed { i, _ -> i != index }) },
                )
            }
        }
        GhostAddButton("Add row", onClick = { update(draft.rows + PayrollAccountRow()) })
        FieldHint("Removing a saved code here only drops it from the grid; use Remove on the list to deactivate it " +
            "in the chart.")
    }
}

/** The one user picker every user field on this page opens. */
@Composable
private fun SharedUserPicker(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val picker = state.setup.userPicker
    val (title, pool) = when (picker?.purpose) {
        UserPickerPurpose.PayrollApprovers -> "Payroll approvers" to HubUsers.available(state.users)
        UserPickerPurpose.InvoiceTeamMember -> "Team member" to HubUsers.available(state.users)
        UserPickerPurpose.RunAuthorisation -> "Level ${picker.index + 1} authorisers" to HubUsers.available(state.users)
        UserPickerPurpose.PayrollGroupAssignee -> "Accountant" to HubUsers.accountsTeam(state.users).ifEmpty {
            HubUsers.available(state.users)
        }
        UserPickerPurpose.PayrollGroupCrew -> "Crew" to HubUsers.available(state.users)
        UserPickerPurpose.ClosingRecipients -> "Recipients" to HubUsers.available(state.users)
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
