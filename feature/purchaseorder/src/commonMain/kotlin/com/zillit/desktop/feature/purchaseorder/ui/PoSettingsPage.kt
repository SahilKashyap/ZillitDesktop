package com.zillit.desktop.feature.purchaseorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.purchaseorder.domain.AssetExpenditureType
import com.zillit.desktop.feature.purchaseorder.domain.AssetFilters
import com.zillit.desktop.feature.purchaseorder.domain.PoAssignmentRule
import com.zillit.desktop.feature.purchaseorder.domain.PoDescriptionFormat
import com.zillit.desktop.feature.purchaseorder.domain.PoSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoSplitType
import com.zillit.desktop.feature.purchaseorder.domain.PoTeamMember

/**
 * The Settings tab — the web's `POSettings.jsx`, card for card.
 *
 * Issuance; then description formatting and rental split side by side; the
 * asset register rule; the Form Configuration hand-off; and the
 * auto-assignment rules. Every card carries its own Save, which appears only
 * once something in it changed and reads "Saved" for a moment after.
 */
@Composable
internal fun PoSettingsPage(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val settings = state.settings
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ZillitNotice(
            text = "Settings · Description formatting, rental split policies, auto-assignment rules, and the " +
                "asset register rule for purchase orders.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Settings,
        )
        val loadError = settings.loadError
        when {
            settings.loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = LOADING_PAD),
                contentAlignment = Alignment.Center,
            ) {
                ZillitSpinner()
            }

            loadError != null -> ZillitErrorState(
                message = loadError.localised(),
                onRetry = { onEvent(PoEvent.Refresh) },
                title = "Failed to load PO settings",
            )

            else -> {
                IssuanceCard(state, onEvent)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
                ) {
                    DescriptionCard(state, onEvent, Modifier.weight(1f))
                    RentalCard(state, onEvent, Modifier.weight(1f))
                }
                AssetRulesCard(state, onEvent)
                FormConfigurationCard(onEvent)
                AssignmentRulesCard(state, onEvent)
            }
        }
    }
}

// -- issuance -------------------------------------------------------------------

/**
 * Everything about how an order goes out: the prefix on its number and the
 * document issued with it. Mixed save semantics, as on the web: the card's
 * Save covers the prefix; the document persists the moment it is uploaded,
 * and the pill says so.
 */
@Composable
private fun IssuanceCard(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val settings = state.settings
    val edited = settings.edited
    val editable = state.viewer.isSeniorAccountant
    ZillitSectionCard(
        title = "PO Issuance",
        icon = ZillitIcons.Send,
        action = { SectionSaveButton(settings, PoSettingsSection.Numbering, onEvent) },
    ) {
        CardIntro("The document issued with a purchase order and the prefix on its number.")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                RowTitle("PO number prefix")
                Hint(PoSettings.PREFIX_HINT)
            }
            ZillitTextField(
                value = edited.numberPrefix,
                onValueChange = {
                    onEvent(PoEvent.EditSettings(edited.copy(numberPrefix = PoSettings.normalisePrefix(it))))
                },
                placeholder = "e.g. QW",
                enabled = editable,
                modifier = Modifier.width(PREFIX_WIDTH),
            )
        }
        // Amendments are paused behind the web's `AMENDMENTS_ENABLED = false`;
        // the stored flag round-trips untouched and no row is shown.
        HairRule()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            RowTitle("Terms and Conditions document")
            ZillitStatusPill(label = "Saves on upload", tone = StatusTone.Done)
        }
        Hint("Issued with every purchase order.")
        TermsDocumentBlock(state, editable, onEvent)
    }
}

/** The one terms document — the web's `TermsDocumentSection`: viewed or replaced, never removed. */
@Composable
private fun TermsDocumentBlock(state: PoUiState, editable: Boolean, onEvent: (PoEvent) -> Unit) {
    val settings = state.settings
    val terms = settings.edited.termsDocument
    val colors = ZillitTheme.colors
    val canChange = editable && settings.canAttachTerms && !settings.termsUploading
    if (terms != null) {
        TermsAttachedRow(name = terms.displayName, uploading = settings.termsUploading, canChange = canChange, onEvent)
    } else {
        ZillitButton(
            text = if (settings.termsUploading) "Uploading…" else "Add attachment",
            onClick = { onEvent(PoEvent.PickTermsDocument) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Paperclip,
            loading = settings.termsUploading,
            enabled = canChange,
        )
    }
    settings.termsError?.let { error ->
        ZillitText(text = error, style = ZillitTheme.typography.bodySmall, color = colors.danger)
    }
    Hint("PDF, DOC or DOCX · max 10MB")
}

/** The attached file: icon, name, "Attached", then View and Change. */
@Composable
private fun TermsAttachedRow(name: String, uploading: Boolean, canChange: Boolean, onEvent: (PoEvent) -> Unit) {
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
            ZillitIcon(icon = ZillitIcons.File, tint = colors.accentText, size = FILE_ICON)
        }
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = name,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Hint("Attached")
        }
        ZillitButton(
            text = "View",
            onClick = { onEvent(PoEvent.OpenTermsDocument) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Eye,
            enabled = !uploading,
        )
        ZillitButton(
            text = if (uploading) "Uploading…" else "Change",
            onClick = { onEvent(PoEvent.PickTermsDocument) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Paperclip,
            loading = uploading,
            enabled = canChange,
        )
    }
}

// -- description formatting, rental split -----------------------------------

@Composable
private fun DescriptionCard(state: PoUiState, onEvent: (PoEvent) -> Unit, modifier: Modifier = Modifier) {
    val settings = state.settings
    val edited = settings.edited
    val editable = state.viewer.isSeniorAccountant
    ZillitSectionCard(
        modifier = modifier,
        title = "Description Formatting",
        icon = ZillitIcons.Edit,
        action = { SectionSaveButton(settings, PoSettingsSection.Description, onEvent) },
    ) {
        CardIntro("Controls how ledger entry descriptions are auto-formatted.")
        PoDescriptionFormat.offered.forEach { format ->
            FormatOption(
                format = format,
                selected = edited.descriptionFormat == format,
                enabled = editable,
                onClick = { onEvent(PoEvent.EditSettings(edited.copy(descriptionFormat = format))) },
            )
        }
    }
}

/** One format: its label, an example, and a filled tick when chosen — the web's option button. */
@Composable
private fun FormatOption(format: PoDescriptionFormat, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(1.dp, if (selected) colors.accent else colors.border, ZillitTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = format.label,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = if (selected) colors.accentText else colors.textPrimary,
            )
            ZillitText(
                text = "e.g. ${format.sample}",
                style = ZillitTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.textSecondary,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier.size(CHECK_SIZE).clip(CircleShape).background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = ZillitIcons.Check, tint = colors.surface, size = CHECK_ICON)
            }
        } else {
            Box(Modifier.size(CHECK_SIZE).clip(CircleShape).border(1.dp, colors.borderStrong, CircleShape))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RentalCard(state: PoUiState, onEvent: (PoEvent) -> Unit, modifier: Modifier = Modifier) {
    val settings = state.settings
    val edited = settings.edited
    val editable = state.viewer.isSeniorAccountant
    ZillitSectionCard(
        modifier = modifier,
        title = "Rental & Split Settings",
        icon = ZillitIcons.Ledger,
        action = { SectionSaveButton(settings, PoSettingsSection.Rental, onEvent) },
    ) {
        CardIntro("How rental POs are handled when posting to the ledger.")
        SettingRow("Auto-split rental POs", "Automatically detect and split rental/hire POs by period") {
            ZillitSwitch(
                checked = edited.autoSplitRentals,
                onCheckedChange = { onEvent(PoEvent.EditSettings(edited.copy(autoSplitRentals = it))) },
                enabled = editable,
            )
        }
        if (edited.autoSplitRentals) {
            HairRule()
            FieldCaption("Default split type")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                PoSplitType.entries.forEach { type ->
                    ZillitChoiceChip(
                        label = type.label,
                        selected = edited.splitType == type,
                        onClick = { if (editable) onEvent(PoEvent.EditSettings(edited.copy(splitType = type))) },
                    )
                }
            }
        }
        // Always-on: the service forces both whatever is stored, so a switch would lie.
        HairRule()
        SettingRow("Require effective date", "Block PO posting without a confirmed effective date") {
            ZillitStatusPill(label = "Always", tone = StatusTone.Done)
        }
        HairRule()
        SettingRow("Enforce period close", "Prevent back-dating entries to closed accounting periods") {
            ZillitStatusPill(label = "Always", tone = StatusTone.Done)
        }
    }
}

// -- the asset register rule ------------------------------------------------

@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssetRulesCard(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val settings = state.settings
    val edited = settings.edited
    val filters = edited.assetFilters
    val editable = state.viewer.isSeniorAccountant
    val symbol = Money.symbol(PoSettings.DEFAULT_CURRENCY)
    val update = { next: AssetFilters -> onEvent(PoEvent.EditSettings(edited.copy(assetFilters = next))) }
    ZillitSectionCard(
        title = "Asset Register Rules",
        icon = ZillitIcons.Receipt,
        action = {
            SectionSaveButton(settings, PoSettingsSection.Asset, onEvent, blocked = filters.error != null)
        },
    ) {
        CardIntro(
            "Which line items on posted & closed POs qualify as assets. Drives the Asset Register — change " +
                "this and the register follows.",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldCaption("Expenditure type")
                // Single choice: All (every type) · Purchase · Consumables.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    ZillitChoiceChip(
                        label = "All",
                        selected = filters.expTypes.isEmpty(),
                        onClick = { if (editable) update(filters.copy(expTypes = emptyList())) },
                    )
                    AssetExpenditureType.entries.forEach { type ->
                        ZillitChoiceChip(
                            label = type.label,
                            selected = filters.expTypes.firstOrNull() == type.wire,
                            onClick = { if (editable) update(filters.copy(expTypes = listOf(type.wire))) },
                        )
                    }
                }
                Hint("All = every expenditure type qualifies.")
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldCaption("Price range")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitTextField(
                        value = filters.priceLow,
                        onValueChange = { update(filters.copy(priceLow = it)) },
                        placeholder = "${symbol}0",
                        keyboardType = KeyboardType.Number,
                        enabled = editable,
                        modifier = Modifier.weight(1f),
                    )
                    Hint("to")
                    ZillitTextField(
                        value = filters.priceHigh,
                        onValueChange = { update(filters.copy(priceHigh = it)) },
                        placeholder = "No max",
                        keyboardType = KeyboardType.Number,
                        enabled = editable,
                        modifier = Modifier.weight(1f),
                    )
                }
                Hint("Inclusive, on the line total. Either bound can be left empty.")
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldCaption("Tags")
                ZillitMultiSelect(
                    selected = filters.tags,
                    options = (settings.tags + filters.tags).distinct(),
                    label = { it },
                    onChange = { update(filters.copy(tags = it)) },
                    placeholder = "Any tag",
                    enabled = editable,
                    emptyText = "No tags — add them in Production Setup",
                )
                Hint("Matches a line carrying any of these. Select none for any tag.")
            }
        }
        val error = filters.error
        if (error != null) {
            ZillitNotice(text = error, tone = StatusTone.Rejected, icon = ZillitIcons.Warning)
        } else {
            // Spelled out, so "no constraint" cannot be mistaken for "nothing saved yet".
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                FieldCaption("Qualifies")
                ZillitText(
                    text = filters.summary(symbol),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }
    }
}

// -- form configuration -------------------------------------------------------

/** The PO form's fields live in the Account Hub's Forms Configuration; this card opens it there. */
@Composable
private fun FormConfigurationCard(onEvent: (PoEvent) -> Unit) {
    ZillitSectionCard(
        title = "Form Configuration",
        icon = ZillitIcons.Edit,
        action = {
            ZillitButton(
                text = "Edit",
                onClick = { onEvent(PoEvent.OpenFormConfiguration) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
        },
    ) {
        CardIntro(
            "Fields and sections shown on the form. Add custom fields per section, reorder them, or reset to " +
                "the system defaults.",
        )
    }
}

// -- auto-assignment rules ----------------------------------------------------

@Composable
private fun AssignmentRulesCard(state: PoUiState, onEvent: (PoEvent) -> Unit) {
    val settings = state.settings
    val editable = state.viewer.isSeniorAccountant
    ZillitSectionCard(
        title = "Auto-Assignment Rules",
        icon = ZillitIcons.Users,
        action = {
            SectionSaveButton(settings, PoSettingsSection.Rules, onEvent)
            ZillitButton(
                text = "Add Rule",
                onClick = { onEvent(PoEvent.AddRule) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
                enabled = editable && PoSettingsSection.Rules !in settings.saving,
            )
        },
    ) {
        CardIntro("Auto-assign POs to team members based on department, vendor, nominal code, or amount.")
        when {
            settings.rulesLoading -> Hint("Loading rules…")
            settings.rules.isEmpty() -> Hint("No rules configured. Click \"Add Rule\" to auto-assign POs.")
            else -> settings.rules.forEach { rule -> RuleCard(state, rule, editable, onEvent) }
        }
    }
}

/**
 * One rule — the web's `RuleRow`: who it assigns to across the top, then the
 * four conditions, any of which sends the order that way. An inactive rule
 * keeps its place, greyed.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun RuleCard(state: PoUiState, rule: PoAssignmentRule, editable: Boolean, onEvent: (PoEvent) -> Unit) {
    val settings = state.settings
    val colors = ZillitTheme.colors
    val symbol = Money.symbol(PoSettings.DEFAULT_CURRENCY)
    val update = { next: PoAssignmentRule -> onEvent(PoEvent.EditRule(next)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(ZillitTheme.shapes.large)
            .background(if (rule.isActive) colors.surface else colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .alpha(if (rule.isActive) 1f else INACTIVE_ALPHA),
    ) {
        Box(
            Modifier
                .width(RULE_BAR)
                .fillMaxHeight()
                .background(if (rule.isActive) colors.accent else colors.borderStrong),
        )
        Column(
            modifier = Modifier.weight(1f).padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitSwitch(
                    checked = rule.isActive,
                    onCheckedChange = { update(rule.copy(isActive = it)) },
                    enabled = editable,
                )
                FieldCaption("Assign to")
                ZillitSelect<PoTeamMember?>(
                    value = settings.team.firstOrNull { it.id == rule.assignTo },
                    options = settings.team,
                    onSelect = { member -> member?.let { update(rule.copy(assignTo = it.id)) } },
                    label = { it?.label ?: "Pick assignee…" },
                    enabled = editable,
                    modifier = Modifier.width(ASSIGNEE_WIDTH),
                )
                Spacer(Modifier.weight(1f))
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = "Remove rule",
                    onClick = { onEvent(PoEvent.RemoveRule(rule.id)) },
                    tint = colors.danger,
                    enabled = editable,
                )
            }
            FieldCaption("If any condition matches (or)")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                ConditionColumn("Departments", Modifier.weight(1f)) {
                    ZillitMultiSelect(
                        selected = rule.departments,
                        options = (settings.departments.map { it.id } + rule.departments).distinct(),
                        label = { id -> settings.departments.firstOrNull { it.id == id }?.name ?: id },
                        onChange = { update(rule.copy(departments = it)) },
                        placeholder = "Any department",
                        enabled = editable,
                    )
                }
                ConditionColumn("Vendors", Modifier.weight(1f)) {
                    ZillitMultiSelect(
                        selected = rule.vendors,
                        options = (state.vendors.map { it.id } + rule.vendors).distinct(),
                        label = { id -> state.vendors.firstOrNull { it.id == id }?.name ?: id },
                        onChange = { update(rule.copy(vendors = it)) },
                        placeholder = "Any vendor",
                        enabled = editable,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                ConditionColumn("Nominal codes", Modifier.weight(1f)) {
                    ZillitMultiSelect(
                        selected = rule.nominalCodes,
                        options = (settings.nominals.map { it.code } + rule.nominalCodes).distinct(),
                        label = { code -> settings.nominals.firstOrNull { it.code == code }?.label ?: code },
                        onChange = { update(rule.copy(nominalCodes = it)) },
                        placeholder = "Any nominal",
                        enabled = editable,
                    )
                }
                ConditionColumn("Amount min", Modifier.weight(1f)) {
                    ZillitTextField(
                        value = rule.amountMin,
                        onValueChange = { update(rule.copy(amountMin = it)) },
                        placeholder = "${symbol}0",
                        keyboardType = KeyboardType.Number,
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// -- the small parts ----------------------------------------------------------

/**
 * A card's Save — the web's `SectionSaveButton`: absent until something
 * changed, "Saving…" while it goes, "Saved" for a moment after. [blocked]
 * keeps it visible but inert when the card is dirty yet invalid: hidden would
 * read as "nothing to save" instead of "fix this".
 */
@Composable
private fun SectionSaveButton(
    settings: PoSettingsState,
    section: PoSettingsSection,
    onEvent: (PoEvent) -> Unit,
    blocked: Boolean = false,
) {
    val dirty = settings.isDirty(section)
    val saving = section in settings.saving
    val saved = section in settings.justSaved
    if (!dirty && !saved) return
    ZillitButton(
        text = when {
            saving -> "Saving…"
            saved -> "Saved"
            else -> "Save"
        },
        onClick = { onEvent(PoEvent.SaveSettings(section)) },
        variant = if (saved) ButtonVariant.Secondary else ButtonVariant.Primary,
        size = ButtonSize.Small,
        leadingIcon = if (saved) ZillitIcons.Check else null,
        loading = saving,
        enabled = dirty && !saving && !saved && !blocked,
    )
}

@Composable
private fun ConditionColumn(caption: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        FieldCaption(caption)
        content()
    }
}

/** A setting's name and its one-line explanation, with the control on the right. */
@Composable
private fun SettingRow(title: String, hint: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            RowTitle(title)
            Hint(hint)
        }
        trailing()
    }
}

@Composable
private fun CardIntro(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
}

@Composable
private fun RowTitle(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
}

@Composable
private fun Hint(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
}

/** The small tracked-out caption the web puts over a control. */
@Composable
private fun FieldCaption(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

@Composable
private fun HairRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
}

private val PREFIX_WIDTH = 120.dp
private val ASSIGNEE_WIDTH = 320.dp
private val RULE_BAR = 4.dp
private val FILE_BLOCK = 32.dp
private val FILE_ICON = 16.dp
private val CHECK_SIZE = 22.dp
private val CHECK_ICON = 12.dp
private val LOADING_PAD = 96.dp
private const val INACTIVE_ALPHA = 0.7f
