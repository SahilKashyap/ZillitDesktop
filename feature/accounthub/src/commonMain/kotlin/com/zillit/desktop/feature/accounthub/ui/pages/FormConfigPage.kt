package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalRule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.domain.HubUsers
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.FormConfigState
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.asAmountText
import com.zillit.desktop.feature.accounthub.ui.components.CalcField
import com.zillit.desktop.feature.accounthub.ui.components.Chip
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.GhostAddButton
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.RadioCard
import com.zillit.desktop.feature.accounthub.ui.components.StatCard
import com.zillit.desktop.feature.accounthub.ui.components.SubCard
import com.zillit.desktop.feature.accounthub.ui.components.TipBanner
import com.zillit.desktop.feature.accounthub.ui.components.UserPickerDialog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Which fields a module's form shows, in what order, and which are required —
 * the web's `FormConfigModule`.
 *
 * ## Three views behind one rail
 *
 * The preview — hero, the green default card, the sections as the form will
 * render them; the editor — a sticky top bar, the tips, every section with its
 * fields clickable and an insert rail between them, a rearrange panel on the
 * right; and the approver builder the default card's "Set Approver Level"
 * opens, which saves through the same route the Approvers page uses.
 *
 * ## A system field is taken off the form, never deleted
 *
 * The module's schema owns it. Removing one sets it hidden and the add-a-field
 * panel offers it back under "System Fields"; a custom field is deleted
 * outright. One button, two meanings, and the difference is the whole design.
 *
 * ## Nothing is addressed by position
 *
 * Every surface here filters something — hidden fields, the terms section — so
 * an index taken from what is on screen names a different row in the document.
 * Sections go by key and fields by their form key.
 */
@Composable
fun FormConfigPage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val config = state.formConfig
    Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        ModuleRail(config, onEvent)
        Box(Modifier.width(1.dp).fillMaxHeight().background(ZillitTheme.colors.border))
        when {
            config.approverBuilder != null -> ApproverBuilderView(state, config.approverBuilder.config, onEvent)
            config.editing -> EditView(state, onEvent)
            else -> PreviewView(state, onEvent)
        }
    }
    FormFieldInspector(config, onEvent)
    FormSectionDialogs(config, onEvent)
    ScopeDialog(state, onEvent)
}

/** The web's `MODULE_SECTIONS`: only the modules whose forms are configurable, under their sidebar group. */
@Composable
private fun ModuleRail(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val shown = FormModule.entries.filter {
        config.moduleSearch.isBlank() || it.label.contains(config.moduleSearch, ignoreCase = true)
    }
    Column(
        modifier = Modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        MonoLabel("Modules", modifier = Modifier.padding(start = ZillitTheme.spacing.sm))
        ZillitSearchField(
            value = config.moduleSearch,
            onValueChange = { onEvent(AccountHubEvent.SearchFormModules(it)) },
            placeholder = "Search modules…",
            modifier = Modifier.fillMaxWidth(),
        )
        if (shown.isNotEmpty()) MonoLabel(
            "Transactions",
            modifier = Modifier.padding(start = ZillitTheme.spacing.sm, top = ZillitTheme.spacing.xs),
        )
        shown.forEach { module ->
            val active = module == config.module
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.medium)
                    .background(if (active) colors.accentSoft else colors.surface)
                    .border(1.dp, if (active) colors.accent else colors.border, ZillitTheme.shapes.medium)
                    .clickable { onEvent(AccountHubEvent.OpenFormModule(module)) }
                    .padding(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitIcon(
                    icon = if (module == FormModule.PurchaseOrders) ZillitIcons.Receipt else ZillitIcons.Wallet,
                    tint = if (active) colors.accentText else colors.textMuted,
                    size = 16.dp,
                )
                ZillitText(
                    text = module.label,
                    style =
                        ZillitTheme.typography.bodyMedium.copy(
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (shown.isEmpty()) FieldHint("No module matches.", Modifier.padding(ZillitTheme.spacing.sm))
    }
}

// -- preview -----------------------------------------------------------------------------

@Composable
private fun RowScope.PreviewView(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val config = state.formConfig
    val module = config.module
    Box(Modifier.weight(1f).fillMaxHeight()) {
        HubPage {
            ZillitScrollColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                Hero(module)
                config.message?.let {
                    ZillitNotice(
                        text = it.message,
                        tone = if (it.ok) StatusTone.Done else StatusTone.Rejected,
                        icon = ZillitIcons.Info,
                    )
                }
                if (!state.viewer.canEdit) {
                    ZillitNotice(
                        text = "Form configuration is read-only for you.",
                        tone = StatusTone.Neutral,
                        icon = ZillitIcons.Info,
                    )
                }
                DefaultFormConfigCard(state, onEvent)
                SectionsDivider(config.sectionCount, config.fieldCount)
                when {
                    config.loading -> ZillitSpinner()
                    config.template.sections.isEmpty() -> ZillitEmptyState(
                        title = "No form for ${module.label}",
                        message = "The server builds a module's form from its own defaults the first time this " +
                            "page is opened. Nothing came back for this one.",
                        icon = ZillitIcons.File,
                    )
                    else -> config.template.configurable.forEach { section ->
                        val terms = section.terms()
                        val visible = section.visible
                        val isTerms = section.key == FormTemplate.TERMS_SECTION
                        val nothingToShow = if (isTerms) terms.isEmpty() else visible.isEmpty()
                        if (nothingToShow) return@forEach
                        SectionCard(
                            section = section,
                            meta = if (isTerms) "${terms.size} terms" else "${visible.size} " +
                                "field${if (visible.size == 1) "" else "s"}",
                        ) {
                            SectionBody(section, module, editing = false, focusedId = null, onField = {})
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(module: FormModule) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(HERO_ICON).clip(ZillitTheme.shapes.medium).background(colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitIcon(icon = ZillitIcons.File, tint = colors.accentText, size = 18.dp)
                }
                Column {
                    MonoLabel("Transactions", color = colors.accentText)
                    ZillitText(text = "Forms Configuration", style = ZillitTheme.typography.displayLarge)
                }
            }
            ZillitText(
                text = "Configure form fields and validation rules for ${module.label}. Defaults apply unless a " +
                    "field is overridden.",
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
        StatCard("Module", module.label, accent = colors.accentText)
    }
}

/** The green summary card — "Default Form Configuration · all submitters · Default | N Custom". */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun DefaultFormConfigCard(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val config = state.formConfig
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.successSoft)
            .border(1.dp, colors.success, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(CARD_ICON).clip(ZillitTheme.shapes.medium).background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.Shield, tint = colors.success, size = 15.dp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(text = "Default Form Configuration", style = ZillitTheme.typography.titleSmall)
                MonoLabel("all submitters")
                Pill(
                    if (config.customCount == 0) "Default" else "${config.customCount} Custom",
                    tone = StatusTone.Done,
                    dot = true,
                )
            }
            FieldHint("Baseline fields shown for every ${config.module.label} submission. Add custom fields per " +
                "section in Edit mode.")
        }
        if (state.viewer.canEdit) {
            ZillitButton(
                text = if (config.saving) "Resetting…" else "Reset to Defaults",
                onClick = { onEvent(AccountHubEvent.AskResetFormTemplate) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !config.saving,
            )
            if (state.viewer.canActAsAccountant) {
                ZillitButton(
                    text = "Set Approver Level",
                    onClick = { onEvent(AccountHubEvent.OpenApproverScope(true)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Shield,
                    loading = config.approverSaving,
                )
            }
            ZillitButton(
                text = "Edit",
                onClick = { onEvent(AccountHubEvent.EditForm(true)) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
        }
    }
}

/** "SECTIONS ———— N · M fields". */
@Composable
private fun SectionsDivider(sections: Int, fields: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        MonoLabel("Sections")
        Box(Modifier.weight(1f).height(1.dp).background(ZillitTheme.colors.border))
        MonoLabel("$sections · $fields fields")
    }
}

// -- edit view -------------------------------------------------------------------------------

@Suppress("LongMethod") // The editor's chrome, then its two columns.
@Composable
private fun RowScope.EditView(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val config = state.formConfig
    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
        TopBar(
            crumb = listOf("Forms", config.module.label, "Edit Fields"),
            onBack = { onEvent(AccountHubEvent.EditForm(false)) },
        ) {
            ZillitButton(
                text = "Rearrange",
                onClick = { onEvent(AccountHubEvent.ToggleRearrange(!config.rearrange)) },
                variant = if (config.rearrange) ButtonVariant.Secondary else ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.LayoutCascade,
            )
            ZillitButton(
                text = "Reset to defaults",
                onClick = { onEvent(AccountHubEvent.AskResetFormTemplate) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !config.saving,
            )
            ZillitButton(
                text = if (config.saving) "Saving…" else "Save",
                onClick = { onEvent(AccountHubEvent.SaveFormTemplate) },
                size = ButtonSize.Small,
                loading = config.saving,
                enabled = !config.saving,
            )
        }
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                TipBanner(
                    "Click a field to edit its properties · + Add Custom Field to create new fields · + between " +
                        "sections to insert a new section · " +
                        "the pencil on a section name renames it · Rearrange to reorder sections and fields",
                )
                config.message?.let {
                    ZillitNotice(
                        text = it.message,
                        tone = if (it.ok) StatusTone.Done else StatusTone.Rejected,
                        icon = ZillitIcons.Info,
                    )
                }
                if (config.dirty) ZillitNotice(
                    text = "Unsaved changes. Nobody else sees them until this form is saved.",
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Info,
                )
                ZillitScrollColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    InsertRail("Insert section here") { onEvent(AccountHubEvent.ComposeFormSection(null)) }
                    config.template.configurable.forEach { section ->
                        EditableSectionCard(section, config, onEvent)
                        InsertRail("Insert section here") { onEvent(AccountHubEvent.ComposeFormSection(section.key)) }
                    }
                }
            }
            if (config.rearrange) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(ZillitTheme.colors.border))
                RearrangePanel(config, onEvent)
            }
        }
    }
}

/** The sticky header of the editor and the builder: back chip, breadcrumb, actions. */
@Composable
private fun TopBar(crumb: List<String>, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIconButton(icon = ZillitIcons.ArrowLeft, contentDescription = "Back", onClick = onBack)
        crumb.forEachIndexed { index, part ->
            if (index > 0) ZillitText(
                text = if (index == crumb.lastIndex) "•" else "/",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            ZillitText(
                text = part,
                style = if (index == crumb.lastIndex) ZillitTheme.typography.bodyMedium
                    .copy(fontWeight = FontWeight.Bold) else ZillitTheme.typography.bodySmall
                    .copy(fontWeight = FontWeight.SemiBold),
                color = if (index == 0) colors.textSecondary else colors.textPrimary,
            )
        }
        Box(Modifier.weight(1f))
        actions()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

/** The dashed rail with a "+" between sections — the web's `SectionInsertRail`. */
@Composable
private fun InsertRail(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(ZillitTheme.colors.border))
        ZillitButton(
            text = label,
            onClick = onClick,
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
        )
        Box(Modifier.weight(1f).height(1.dp).background(ZillitTheme.colors.border))
    }
}

@Suppress("LongMethod") // One section: its header's actions, then its body.
@Composable
private fun EditableSectionCard(section: FormSection, config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    val isTerms = section.key == FormTemplate.TERMS_SECTION
    val isLineItems = section.key == LINE_ITEMS
    val terms = section.terms()
    val focusedId = config.focus?.takeIf { it.sectionKey == section.key }?.fieldId
    SectionCard(
        section = section,
        meta =
            if (isTerms) "${terms.size} term${if (terms.size == 1) "" else "s"}"
            else "${section.visible.size}/${section.fields.size} visible",
        headerActions = {
            if (!section.systemDefault) {
                ZillitIconButton(
                    icon = ZillitIcons.Edit,
                    contentDescription = "Rename ${section.label}",
                    onClick = { onEvent(AccountHubEvent.RenameFormSection(section.key, section.label)) },
                )
                ZillitButton(
                    text = "Remove",
                    onClick = { onEvent(AccountHubEvent.AskRemoveFormSection(section)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        },
        trailing = {
            if (isTerms) {
                ZillitButton(
                    text = "Add Term",
                    onClick = { onEvent(AccountHubEvent.AddTerm); onEvent(AccountHubEvent.ToggleTermsEditor(true)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            } else {
                ZillitButton(
                    text = if (isLineItems) "Add Custom Column" else "Add Custom Field",
                    onClick = { onEvent(AccountHubEvent.FocusFormField(section.key, null)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        },
    ) {
        if (isTerms) {
            TermsBody(terms, config.termsEditing, onEvent)
        } else {
            SectionBody(section, config.module, editing = true, focusedId = focusedId) {
                field -> onEvent(AccountHubEvent.FocusFormField(section.key, field.id))
            }
        }
    }
}

/** The clauses: a numbered list, or one text field per clause while the editor is open. */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun ColumnScope.TermsBody(terms: List<String>, editing: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    if (terms.isEmpty()) {
        ZillitText(
            text = "No terms added yet.",
            style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = ZillitTheme.colors.textMuted,
        )
    }
    if (editing) {
        terms.forEachIndexed { index, term ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                MonoLabel("${index + 1}.")
                ZillitTextField(
                    value = term,
                    onValueChange = { onEvent(AccountHubEvent.SetTerm(index, it)) },
                    placeholder = "Enter term...",
                    modifier = Modifier.weight(1f),
                )
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Remove term",
                    onClick = { onEvent(AccountHubEvent.RemoveTerm(index)) },
                    tint = ZillitTheme.colors.danger,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            GhostAddButton("Add Term", onClick = { onEvent(AccountHubEvent.AddTerm) })
            ZillitButton(
                text = "Done",
                onClick = { onEvent(AccountHubEvent.ToggleTermsEditor(false)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth().clickable { onEvent(AccountHubEvent.ToggleTermsEditor(true)) },
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            terms.forEachIndexed { index, term ->
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    ZillitText(
                        text = "${index + 1}.",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                    ZillitText(
                        text = term.ifBlank { "…" },
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
            }
            if (terms.isNotEmpty()) FieldHint("Click to edit the terms.")
        }
    }
}

/** A section card: the amber eyebrow header, then the body the caller draws. */
@Composable
private fun SectionCard(
    section: FormSection,
    meta: String,
    headerActions: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    SubCard(padded = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(icon = ZillitIcons.File, tint = ZillitTheme.colors.accentText, size = 13.dp)
            MonoLabel(section.label, color = ZillitTheme.colors.accentText)
            headerActions()
            Box(Modifier.weight(1f))
            FieldHint(meta)
            trailing()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
        Column(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            content = content,
        )
    }
}

/**
 * The section as the form renders it — the web's preview: a one-row table for
 * line items, a numbered list for terms, and a grid of placeholder inputs for
 * everything else (two columns on Petty Cash, three elsewhere).
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun ColumnScope.SectionBody(
    section: FormSection,
    module: FormModule,
    editing: Boolean,
    focusedId: String?,
    onField: (FormField) -> Unit,
) {
    val visible = section.visible
    if (section.key == FormTemplate.TERMS_SECTION) {
        section.terms().forEachIndexed { index, term ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitText(
                    text = "${index + 1}.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitText(
                    text = term,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }
        return
    }
    if (visible.isEmpty()) {
        FieldHint("No fields on the form in this section.")
        return
    }
    if (section.key == LINE_ITEMS) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            visible.forEach {
                field -> Box(Modifier.weight(1f)) {
                    FieldLabelText(field, focused = field.id == focusedId, editing = editing) { onField(field) }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            visible.forEach {
                field -> Box(Modifier.weight(1f)) {
                    Placeholder(placeholderFor(field), focused = field.id == focusedId, editing = editing) {
                        onField(field)
                    }
                }
            }
        }
        return
    }
    val columns = if (module == FormModule.CashExpenses) 2 else GRID_COLUMNS
    visible.chunked(columns).forEach { rowFields ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            rowFields.forEach { field ->
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    FieldLabelText(field, focused = field.id == focusedId, editing = editing) { onField(field) }
                    Placeholder(
                        placeholderFor(field),
                        focused = field.id == focusedId,
                        editing = editing,
                        tall = field.type == "textarea",
                    ) {
                        onField(field)
                    }
                }
            }
            repeat(columns - rowFields.size) { Box(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun FieldLabelText(field: FormField, focused: Boolean, editing: Boolean, onClick: () -> Unit) {
    Row(
        modifier = if (editing) Modifier.clickable(onClick = onClick) else Modifier,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoLabel(field.name, color = if (focused) ZillitTheme.colors.accentText else null)
        if (field.required) MonoLabel("*", color = ZillitTheme.colors.accentText) else FieldHint("(optional)")
    }
}

@Composable
private fun Placeholder(text: String, focused: Boolean, editing: Boolean, tall: Boolean = false, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (tall) PLACEHOLDER_TALL else PLACEHOLDER)
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(1.dp, if (focused) colors.accent else colors.border, ZillitTheme.shapes.medium)
            .then(if (editing) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = ZillitTheme.spacing.sm),
        contentAlignment = if (tall) Alignment.TopStart else Alignment.CenterStart,
    ) {
        ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
    }
}

/** The web's placeholder per type: "Select …", "0", "dd/mm/yyyy", "email@example.com", "+44...", "https://...". */
private fun placeholderFor(field: FormField): String = when (field.type) {
    "select" -> "Select ${field.name.lowercase()}..."
    "number" -> "0"
    "date" -> "dd/mm/yyyy"
    "email" -> "email@example.com"
    "phone" -> "+44..."
    "url" -> "https://..."
    else -> "${field.name}..."
}

/** The clause list the terms section keeps in its `values` extra. */
internal fun FormSection.terms(): List<String> =
    (extras[TERMS_VALUES] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

// -- rearrange panel ---------------------------------------------------------------------------

/**
 * The right-hand panel — sections, then one section's fields.
 *
 * The web drags; here each row carries up and down arrows, which reach the
 * same reorder events. Terms move by swapping two clauses' text.
 */
@Suppress("LongMethod") // Two lists in one panel.
@Composable
private fun RearrangePanel(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val picked = config.rearrangeSection?.let { config.template.section(it) }
    Column(modifier = Modifier.width(PANEL_WIDTH).fillMaxHeight().background(colors.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                if (picked != null) {
                    ZillitButton(
                        text = "Sections",
                        onClick = { onEvent(AccountHubEvent.PickRearrangeSection(null)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.ChevronLeft,
                    )
                    ZillitText(text = picked.label, style = ZillitTheme.typography.titleSmall)
                    FieldHint("Move fields to reorder")
                } else {
                    ZillitText(text = "Rearrange", style = ZillitTheme.typography.titleSmall)
                    FieldHint("Move sections to reorder, click to see fields")
                }
            }
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = "Close rearrange",
                onClick = { onEvent(AccountHubEvent.ToggleRearrange(false)) },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            when {
                picked == null -> {
                    val sections = config.template.configurable
                    sections.forEachIndexed { index, section ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ZillitTheme.shapes.medium)
                                .clickable { onEvent(AccountHubEvent.PickRearrangeSection(section.key)) }
                                .padding(ZillitTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            ZillitIcon(icon = ZillitIcons.File, tint = colors.textMuted, size = 12.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                ZillitText(
                                    text = section.label,
                                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                )
                                FieldHint("${section.fields.size} fields")
                            }
                            Pill(
                                if (section.systemDefault) "System" else "Custom",
                                tone = if (section.systemDefault) StatusTone.Pending else StatusTone.Neutral,
                            )
                            ZillitIconButton(
                                icon = ZillitIcons.ChevronUp,
                                contentDescription = "Move ${section.label} up",
                                onClick = { onEvent(AccountHubEvent.NudgeFormSection(section.key, -1)) },
                                enabled = index > 0,
                            )
                            ZillitIconButton(
                                icon = ZillitIcons.ChevronDown,
                                contentDescription = "Move ${section.label} down",
                                onClick = { onEvent(AccountHubEvent.NudgeFormSection(section.key, 1)) },
                                enabled = index < sections.lastIndex,
                            )
                        }
                    }
                }
                picked.key == FormTemplate.TERMS_SECTION -> {
                    val terms = picked.terms()
                    terms.forEachIndexed { index, term ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            ZillitText(
                                text = term.ifBlank { "…" },
                                style = ZillitTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                            )
                            ZillitIconButton(
                                icon = ZillitIcons.ChevronUp,
                                contentDescription = "Move term up",
                                onClick = { swapTerms(terms, index, index - 1, onEvent) },
                                enabled = index > 0,
                            )
                            ZillitIconButton(
                                icon = ZillitIcons.ChevronDown,
                                contentDescription = "Move term down",
                                onClick = { swapTerms(terms, index, index + 1, onEvent) },
                                enabled = index < terms.lastIndex,
                            )
                        }
                    }
                    if (terms.isEmpty()) FieldHint("No terms yet.", Modifier.padding(ZillitTheme.spacing.sm))
                }
                else -> {
                    val fields = picked.visible
                    fields.forEachIndexed { index, field ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                ZillitText(
                                    text = field.name,
                                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                )
                                FieldHint(field.typeLabel + if (field.required) " · required" else "")
                            }
                            ZillitIconButton(
                                icon = ZillitIcons.ChevronUp,
                                contentDescription = "Move ${field.name} up",
                                onClick = { onEvent(AccountHubEvent.NudgeFormField(picked.key, field.id, -1)) },
                                enabled = index > 0,
                            )
                            ZillitIconButton(
                                icon = ZillitIcons.ChevronDown,
                                contentDescription = "Move ${field.name} down",
                                onClick = { onEvent(AccountHubEvent.NudgeFormField(picked.key, field.id, 1)) },
                                enabled = index < fields.lastIndex,
                            )
                        }
                    }
                    if (fields.isEmpty()) FieldHint("No visible fields.", Modifier.padding(ZillitTheme.spacing.sm))
                }
            }
        }
    }
}

private fun swapTerms(terms: List<String>, from: Int, to: Int, onEvent: (AccountHubEvent) -> Unit) {
    if (to !in terms.indices) return
    onEvent(AccountHubEvent.SetTerm(from, terms[to]))
    onEvent(AccountHubEvent.SetTerm(to, terms[from]))
}

// -- set approver level -------------------------------------------------------------------------

/** "Set Approver Level": all departments, or one — the web's `ScopeModal`. */
@Composable
private fun ScopeDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val scope = state.formConfig.scopeModal
    ZillitDialogShell(
        title = "Set Approver Level",
        icon = ZillitIcons.Shield,
        visible = scope != null,
        onDismiss = { onEvent(AccountHubEvent.OpenApproverScope(false)) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.OpenApproverScope(false)) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Continue",
                onClick = { onEvent(AccountHubEvent.ContinueApproverScope) },
                enabled = scope?.canContinue == true,
            )
        },
    ) {
        if (scope == null) return@ZillitDialogShell
        ZillitText(
            text = "How would you like to configure approvers?",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        RadioCard(
            label = "For All Departments",
            sample = "Same approval levels applied to every department",
            active = scope.mode == ApprovalScope.All,
            onClick = { onEvent(AccountHubEvent.PickApproverScope(ApprovalScope.All)) },
        )
        RadioCard(
            label = "For One Department",
            sample = "Configure levels for a specific department",
            active = scope.mode == ApprovalScope.Department,
            onClick = { onEvent(AccountHubEvent.PickApproverScope(ApprovalScope.Department, scope.departmentId)) },
        )
        if (scope.mode == ApprovalScope.Department) {
            // The accounts department approves everyone else's documents and never configures its own.
            val options = state.departmentList.filterNot { it.identifier == ACCOUNTS_IDENTIFIER }
            HubSelect<HubDepartment>(
                value = options.firstOrNull { it.id == scope.departmentId },
                options = options,
                label = { it.name },
                onSelect = { onEvent(AccountHubEvent.PickApproverScope(ApprovalScope.Department, it?.id)) },
                placeholder = "Choose a department...",
                fieldLabel = "Select Department",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The tier builder the default card opens — the Approvers page's, saved
 * through the same route. Picker and search are the screen's own state: the
 * builder is one document and the picker never outlives it.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // The builder's chrome, then a card per level.
@Composable
private fun RowScope.ApproverBuilderView(
    state: AccountHubUiState,
    config: ApprovalConfig,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val formConfig = state.formConfig
    var pickerTier by remember { mutableStateOf<Int?>(null) }
    var pickerSearch by remember { mutableStateOf("") }
    fun update(next: ApprovalConfig) = onEvent(AccountHubEvent.UpdateFormApprovers(next))
    fun renumbered(tiers: List<ApprovalTier>) = tiers.mapIndexed { index, tier -> tier.copy(order = index + 1) }
    val scopeLabel = if (config.scope == ApprovalScope.All) "All Departments" else config.departmentName.ifBlank {
        state.departmentName(config.departmentId)
    }

    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
        TopBar(
            crumb = listOf("Forms", formConfig.module.label, "Approval Levels · $scopeLabel"),
            onBack = { onEvent(AccountHubEvent.DismissFormApprovers) },
        ) {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissFormApprovers) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !formConfig.approverSaving,
            )
            ZillitButton(
                text = if (formConfig.approverSaving) "Saving…" else "Save changes",
                onClick = { onEvent(AccountHubEvent.SaveFormApprovers) },
                size = ButtonSize.Small,
                loading = formConfig.approverSaving,
                enabled = !formConfig.approverSaving,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            TipBanner(
                if (config.scope == ApprovalScope.All) {
                    "Configuring approval levels for all departments. Changes will apply uniformly."
                } else {
                    "Configuring approval levels for $scopeLabel. These replace the default levels for that department."
                },
            )
            ZillitScrollColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                InsertRail("Insert level here") {
                    update(config.copy(tiers = renumbered(listOf(ApprovalTier(
                        1,
                        listOf(ApprovalRule(type = "default")),
                    )) + config.tiers)))
                }
                config.tiers.forEachIndexed { index, tier ->
                    val rule = tier.rules.firstOrNull() ?: ApprovalRule(type = "default")
                    fun patchTier(next: ApprovalTier) =
                        update(config.copy(tiers = config.tiers.map { if (it.order == tier.order) next else it }))
                    SubCard(
                        title = "Level ${tier.order}",
                        action = {
                            if (config.tiers.size > 1) {
                                ZillitButton(
                                    text = "Remove level",
                                    onClick = {
                                        update(
                                            config.copy(
                                                tiers = renumbered(config.tiers.filterNot { it.order == tier.order }),
                                            ),
                                        )
                                    },
                                    variant = ButtonVariant.Tertiary,
                                    size = ButtonSize.Small,
                                    leadingIcon = ZillitIcons.Trash,
                                )
                            }
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                            ) {
                                FieldLabel("Rule")
                                ZillitSelect(
                                    value = rule.type.ifBlank { "default" },
                                    options = listOf("default", "amount"),
                                    onSelect = { type ->
                                        patchTier(
                                            tier.copy(rules = listOf(rule.copy(type = type)) + tier.rules.drop(1)),
                                        )
                                    },
                                    label = { if (it == "amount") "Amount greater than" else "Default" },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (rule.type == "amount") {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                                ) {
                                    FieldLabel("Amount", required = true)
                                    CalcField(
                                        value = rule.amountThreshold?.asAmountText().orEmpty(),
                                        onValueChange = { text ->
                                            patchTier(
                                                tier.copy(
                                                    rules = listOf(
                                                        rule.copy(amountThreshold = text.toDoubleOrNull()),
                                                    ) + tier.rules.drop(1),
                                                ),
                                            )
                                        },
                                        placeholder = "0.00",
                                    )
                                    FieldHint("Applicable for all currencies. No exchange rates applied.")
                                }
                            }
                        }
                        FieldLabel("Approvers")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                            ) {
                                if (rule.userIds.isEmpty()) FieldHint("Nobody yet — this level will be dropped on " +
                                    "save.")
                                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                                    rule.userIds.forEach { id ->
                                        Chip(
                                            text = state.userName(id),
                                            onRemove = {
                                                patchTier(
                                                    tier.copy(
                                                        rules = listOf(
                                                            rule.copy(userIds = rule.userIds - id),
                                                        ) + tier.rules.drop(1),
                                                    ),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                            ZillitButton(
                                text = "Add Users",
                                onClick = { pickerTier = tier.order; pickerSearch = "" },
                                variant = ButtonVariant.Secondary,
                                size = ButtonSize.Small,
                                leadingIcon = ZillitIcons.UserPlus,
                            )
                        }
                    }
                    InsertRail("Insert level here") {
                        val next = config.tiers.toMutableList()
                        next.add(index + 1, ApprovalTier(0, listOf(ApprovalRule(type = "default"))))
                        update(config.copy(tiers = renumbered(next)))
                    }
                }
                ZillitButton(
                    text = "Add more",
                    onClick = { update(config.copy(tiers = renumbered(config.tiers + ApprovalTier(
                        0,
                        listOf(ApprovalRule(type = "default")),
                    )))) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        }
    }

    val tier = config.tiers.firstOrNull { it.order == pickerTier }
    val rule = tier?.rules?.firstOrNull() ?: ApprovalRule(type = "default")
    UserPickerDialog(
        visible = tier != null,
        title = "Add Users — Level ${pickerTier ?: ""}",
        users = HubUsers.available(state.users),
        selected = rule.userIds,
        search = pickerSearch,
        onSearch = { pickerSearch = it },
        onToggle = { id ->
            tier?.let {
                val users = if (id in rule.userIds) rule.userIds - id else rule.userIds + id
                update(
                    config.copy(
                        tiers = config.tiers.map { t ->
                            if (t.order == it.order) it.copy(
                                rules = listOf(rule.copy(userIds = users)) + it.rules.drop(1),
                            ) else t
                        },
                    ),
                )
            }
        },
        onApply = { pickerTier = null },
        onDismiss = { pickerTier = null },
    )
}

private const val LINE_ITEMS = "line_items"
private const val TERMS_VALUES = "values"
private const val ACCOUNTS_IDENTIFIER = "department_accounts"
private const val GRID_COLUMNS = 3
private val RAIL_WIDTH = 240.dp
private val PANEL_WIDTH = 300.dp
private val HERO_ICON = 36.dp
private val CARD_ICON = 34.dp
private val PLACEHOLDER = 38.dp
private val PLACEHOLDER_TALL = 60.dp
