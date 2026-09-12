package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.DeductionRule
import com.zillit.desktop.feature.cashexpenses.domain.QuickCode
import com.zillit.desktop.feature.cashexpenses.domain.RuleProcess
import com.zillit.desktop.feature.cashexpenses.domain.RuleThreshold
import com.zillit.desktop.feature.cashexpenses.domain.CashTeamMember
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.personColumn

/**
 * The production's cash configuration.
 *
 * Senior accountants only — every switch here changes what other people are
 * allowed to do, so the page is gated in [com.zillit.desktop.feature.cashexpenses.ui.CashDestination]
 * rather than shown read-only. A settings screen you can see but not use
 * invites someone to ask why, and the answer is never satisfying.
 */
@Suppress("LongMethod") // A settings catalogue: splitting it hides what the page offers.
@Composable
fun CashSettingsPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val draft = state.settingsDraft ?: state.settings
    if (draft == null) {
        ScrollingPage { ZillitNotice(text = "Loading the project's cash settings…") }
        return
    }

    val dirty = draft != state.settings

    ScrollingPage {
        ZillitNotice(
            text = "These settings apply to everyone on this project. " +
                "Changes take effect as soon as they are saved.",
            tone = StatusTone.Progress,
            icon = ZillitIcons.Info,
        )

        ZillitSectionCard(
            title = "Custodian account",
            icon = ZillitIcons.Bank,
            meta = "Where petty cash is drawn from",
        ) {
            ZillitTextField(
                value = draft.custodianAccount,
                onValueChange = { onEvent(CashEvent.EditSettings(draft.copy(custodianAccount = it))) },
                label = "Float custodian account",
                placeholder = "1200",
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitTextField(
                    value = draft.bsCodeFrom,
                    onValueChange = { onEvent(CashEvent.EditSettings(draft.copy(bsCodeFrom = it))) },
                    label = "Balance sheet codes from",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = draft.bsCodeTo,
                    onValueChange = { onEvent(CashEvent.EditSettings(draft.copy(bsCodeTo = it))) },
                    label = "to",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ZillitSectionCard(title = "Workflow", icon = ZillitIcons.Shield) {
            SettingSwitch(
                checked = draft.requireCoordinatorCoding,
                label = "Coordinators code batches before accounts see them",
                detail = "Turns on the Coding Queue. Without it, batches go straight from submission to audit.",
                onChange = { onEvent(CashEvent.EditSettings(draft.copy(requireCoordinatorCoding = it))) },
            )
            SettingSwitch(
                checked = draft.requireSeniorSignOff,
                label = "Senior sign-off before posting",
                detail = "Adds the Sign-off queue. Batches cannot be posted until a senior has cleared them.",
                onChange = { onEvent(CashEvent.EditSettings(draft.copy(requireSeniorSignOff = it))) },
            )
            SettingSwitch(
                checked = draft.overrideFloatRequest,
                label = "Accountants may override float approvals",
                detail = "Lets an accountant with the override right push a float past its approval chain.",
                onChange = { onEvent(CashEvent.EditSettings(draft.copy(overrideFloatRequest = it))) },
            )
            SettingSwitch(
                checked = draft.overrideReceiptBatch,
                label = "Accountants may override batch approvals",
                detail = "The same, for receipt batches. Every override is recorded against the person who made it.",
                onChange = { onEvent(CashEvent.EditSettings(draft.copy(overrideReceiptBatch = it))) },
            )
        }

        ZillitSectionCard(title = "Reimbursement", icon = ZillitIcons.Wallet) {
            SettingSwitch(
                checked = draft.reimburseToPayroll,
                label = "Reimburse out-of-pocket claims through payroll",
                detail = "Approved claims are settled in the next pay run rather than queued for BACS.",
                onChange = { onEvent(CashEvent.EditSettings(draft.copy(reimburseToPayroll = it))) },
            )
        }

        DeductionRulesCard(draft, onEvent)
        QuickCodesCard(draft, onEvent)

        ZillitSectionCard(
            title = "Team & posting rights",
            icon = ZillitIcons.Users,
            meta = "${draft.teamMembers.size} member(s)",
            padded = false,
        ) {
            ZillitDataTable(
                rows = draft.teamMembers,
                columns = teamColumns(),
                key = { it.userId },
                emptyTitle = "No cash team configured",
                emptyMessage = "Production Accountants and Financial Controllers are senior by role " +
                    "whether or not they are listed here.",
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = "Save settings",
                onClick = { onEvent(CashEvent.SaveSettings) },
                enabled = dirty && !state.busy,
                loading = state.busy,
            )
            if (dirty) {
                ZillitButton(
                    text = "Discard changes",
                    onClick = { state.settings?.let { onEvent(CashEvent.EditSettings(it)) } },
                    variant = ButtonVariant.Tertiary,
                )
                ZillitText(
                    text = "Unsaved changes",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.warning,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    checked: Boolean,
    label: String,
    detail: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitCheckbox(checked = checked, onCheckedChange = onChange)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = label, style = ZillitTheme.typography.bodyMedium)
            ZillitText(
                text = detail,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

@Suppress("MagicNumber") // Column proportions; naming each would not clarify them.
private fun teamColumns(): List<TableColumn<CashTeamMember>> = listOf(
    personColumn("Name", ColumnWidth.Weight(1.8f), userId = { it.userId }) { it.name.ifBlank { it.userId } },
    TableColumn(
        header = "Seniority",
        width = ColumnWidth.Weight(1f),
        cell = { row ->
            ZillitStatusPill(
                label = if (row.isSenior) "Senior" else "Team",
                tone = if (row.isSenior) StatusTone.Done else StatusTone.Neutral,
            )
        },
    ),
    TableColumn(
        header = "Override",
        width = ColumnWidth.Weight(1f),
        cell = { row ->
            ZillitStatusPill(
                label = if (row.canOverride) "Allowed" else "No",
                tone = if (row.canOverride) StatusTone.Pending else StatusTone.Neutral,
            )
        },
    ),
    textColumn("Posting limit", ColumnWidth.Weight(1f), numeric = true) {
        // No limit is a real answer, and printing it as "0.00" would read as
        // "may post nothing" — the opposite of what it means.
        it.postingLimit?.let { limit -> Money.format(limit, null) } ?: "No limit"
    },
)


/**
 * The rules that fire on a receipt's words and its value.
 *
 * Four ship switched off; a production turns on the ones it runs. Each says
 * what it does and at what threshold, and the words that trigger it are shown
 * rather than hidden behind an editor — an accountant deciding whether to
 * enable "Fuel Deduction" needs to know it fires on the word "diesel".
 */
@Composable
private fun DeductionRulesCard(draft: CashSettings, onEvent: (CashEvent) -> Unit) {
    ZillitSectionCard(
        title = "Deduction & processing rules",
        icon = ZillitIcons.Ledger,
        meta = "${draft.deductionRules.count { it.enabled }} of ${draft.deductionRules.size} on",
    ) {
        if (draft.deductionRules.isEmpty()) {
            ZillitText(
                text = "No rules configured for this production.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            return@ZillitSectionCard
        }
        draft.deductionRules.forEachIndexed { index, rule ->
            if (index > 0) ZillitDivider()
            DeductionRuleRow(draft, rule, onEvent)
        }
    }
}

@Composable
private fun DeductionRuleRow(draft: CashSettings, rule: DeductionRule, onEvent: (CashEvent) -> Unit) {
    val colors = ZillitTheme.colors
    fun replace(next: DeductionRule) = onEvent(
        CashEvent.EditSettings(
            draft.copy(deductionRules = draft.deductionRules.map { if (it.id == rule.id) next else it }),
        ),
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = rule.title.ifBlank { rule.id },
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                if (rule.description.isNotBlank()) {
                    ZillitText(
                        text = rule.description,
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
            ZillitStatusPill(
                label = rule.processType.label,
                tone = when (rule.processType) {
                    RuleProcess.DeductAmount -> StatusTone.Rejected
                    RuleProcess.SeniorReview -> StatusTone.Pending
                    RuleProcess.NeedQuery -> StatusTone.Progress
                },
            )
            ZillitSwitch(checked = rule.enabled, onCheckedChange = { replace(rule.copy(enabled = it)) })
        }
        RuleThresholdRow(rule, replace = ::replace)
    }
}

/** How much, and on what — inert until the rule above it is switched on. */
@Composable
private fun RuleThresholdRow(rule: DeductionRule, replace: (DeductionRule) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitSelect(
            value = rule.thresholdType,
            options = RuleThreshold.entries,
            onSelect = { replace(rule.copy(thresholdType = it)) },
            label = { it.label },
            enabled = rule.enabled,
            modifier = Modifier.width(THRESHOLD_WIDTH),
        )
        ZillitTextField(
            value = rule.thresholdValue.trimmedText(),
            onValueChange = { typed -> replace(rule.copy(thresholdValue = typed.toDoubleOrNull() ?: 0.0)) },
            keyboardType = KeyboardType.Number,
            enabled = rule.enabled,
            modifier = Modifier.width(VALUE_WIDTH),
        )
        ZillitText(
            text = if (rule.triggerCodes.isEmpty()) {
                "on every receipt"
            } else {
                "on " + rule.triggerCodes.joinToString(", ")
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Whole numbers without a trailing `.0`, which is how a threshold is typed. */
private fun Double.trimmedText(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

/**
 * The coding shortcuts offered when a receipt is coded.
 *
 * Name, nominal code, VAT and the words that pick it automatically — the four
 * fields the web stores. Rows are added and removed here; the codes themselves
 * are free text because the chart is a different service.
 */
@Composable
private fun QuickCodesCard(draft: CashSettings, onEvent: (CashEvent) -> Unit) {
    fun update(codes: List<QuickCode>) = onEvent(CashEvent.EditSettings(draft.copy(quickCodes = codes)))
    ZillitSectionCard(
        title = "Quick codes",
        icon = ZillitIcons.Receipt,
        meta = "${draft.quickCodes.size} code(s)",
        action = {
            ZillitButton(
                text = "Add code",
                onClick = { update(draft.quickCodes + QuickCode(name = "")) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        if (draft.quickCodes.isEmpty()) {
            ZillitText(
                text = "No quick codes yet. They appear as options when a receipt is coded.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            return@ZillitSectionCard
        }
        draft.quickCodes.forEachIndexed { index, code ->
            if (index > 0) ZillitDivider()
            QuickCodeRow(
                code = code,
                onChange = { next ->
                    update(draft.quickCodes.mapIndexed { i, existing -> if (i == index) next else existing })
                },
                onRemove = { update(draft.quickCodes.filterIndexed { i, _ -> i != index }) },
            )
        }
    }
}

/** One shortcut: what it is called, what it codes to, its VAT, and the words that pick it. */
@Composable
private fun QuickCodeRow(code: QuickCode, onChange: (QuickCode) -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitTextField(
            value = code.name,
            onValueChange = { onChange(code.copy(name = it)) },
            placeholder = "Category",
            modifier = Modifier.weight(NAME_WEIGHT),
        )
        ZillitTextField(
            value = code.nominalCode,
            onValueChange = { onChange(code.copy(nominalCode = it)) },
            placeholder = "Nominal",
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = code.vat?.trimmedText().orEmpty(),
            onValueChange = { onChange(code.copy(vat = it.toDoubleOrNull())) },
            placeholder = "VAT %",
            keyboardType = KeyboardType.Number,
            modifier = Modifier.width(VAT_WIDTH),
        )
        ZillitTextField(
            value = code.keywords.joinToString(", "),
            onValueChange = { typed ->
                onChange(code.copy(keywords = typed.split(",").map { it.trim() }.filter { it.isNotBlank() }))
            },
            placeholder = "Words that pick it",
            modifier = Modifier.weight(KEYWORD_WEIGHT),
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Remove ${code.name.ifBlank { "this code" }}",
            onClick = onRemove,
            tint = ZillitTheme.colors.danger,
        )
    }
}

private val THRESHOLD_WIDTH = 180.dp
private val VALUE_WIDTH = 110.dp
private val VAT_WIDTH = 90.dp
private const val NAME_WEIGHT = 1.2f
private const val KEYWORD_WEIGHT = 1.6f
