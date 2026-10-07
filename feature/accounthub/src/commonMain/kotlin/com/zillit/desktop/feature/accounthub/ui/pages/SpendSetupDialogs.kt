package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.HubUsers
import com.zillit.desktop.feature.accounthub.domain.SpendDeductionRule
import com.zillit.desktop.feature.accounthub.domain.SpendQuickCode
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SpendDraft
import com.zillit.desktop.feature.accounthub.ui.components.CalcField
import com.zillit.desktop.feature.accounthub.ui.components.Chip
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.PersonChip
import com.zillit.desktop.feature.accounthub.ui.components.ToggleRow

/** The team-member and deduction-rule dialogs, drawn at the page root so they overlay the modal. */
@Composable
internal fun SpendDialogs(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val draft = state.setup.spendDraft
    SpendMemberDialog(state, draft as? SpendDraft.Member, onEvent)
    SpendRuleDialog(state, draft as? SpendDraft.Rule, onEvent)
}

/** The web's `SpendTeamMemberModal`. */
@Suppress("LongMethod") // A form, read top to bottom; the order is the reading order.
@Composable
private fun SpendMemberDialog(state: AccountHubUiState, draft: SpendDraft.Member?, onEvent: (AccountHubEvent) -> Unit) {
    val member = draft?.member
    fun update(next: SpendDraft.Member) = onEvent(AccountHubEvent.EditSpendDraft(next))
    ZillitDialogShell(
        title = str(
            if (draft?.index == null) S.desktop_ce_insights_add_team_member else S.desktop_ce_insights_edit_team_member,
        ),
        icon = ZillitIcons.Users,
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissSpendDraft) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(AccountHubEvent.DismissSpendDraft) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(if (draft?.index == null) S.add else S.update),
                onClick = { onEvent(AccountHubEvent.CommitSpendDraft) },
                enabled = !member?.userId.isNullOrBlank(),
            )
        },
    ) {
        if (draft == null || member == null) return@ZillitDialogShell
        FieldLabel(str(S.user_label), required = true)
        if (draft.index == null) {
            // Only accounts-team people not already on the team — the web's `userOptions`.
            val onTeam = state.setup.spendSetup.edited.team.map { it.userId }.toSet()
            val pool = HubUsers.accountsTeam(state.users).filter { it.id !in onTeam }
            HubSelect(
                value = pool.firstOrNull { it.id == member.userId },
                options = pool,
                label = { "${it.name} — ${it.roleLabel.ifBlank { "—" }}" },
                onSelect = { update(draft.copy(member = member.copy(userId = it?.id.orEmpty()))) },
                placeholder = str(S.desktop_select_a_user),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // The person is fixed once added; an edit changes what they may do, not who they are.
            PersonChip(
                name = state.userName(member.userId),
                userId = member.userId,
                role = state.user(member.userId)?.roleLabel,
            )
        }
        val symbol = state.setup.currencies.saved.default?.symbol.orEmpty()
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(
                if (symbol.isBlank()) str(S.desktop_posting_limit) else str(S.desktop_hub_sp_limit_title, symbol),
                modifier = Modifier.weight(1f),
            )
            ZillitCheckbox(
                checked = member.isUnlimited,
                onCheckedChange = { on ->
                    update(draft.copy(member = member.copy(postingLimit = if (on) null else 0.0)))
                },
                label = str(S.drive_link_views_unlimited),
                enabled = !member.isSenior,
            )
        }
        CalcField(
            value = if (member.isUnlimited) "" else draft.limitText,
            onValueChange = { update(draft.copy(limitText = it)) },
            placeholder = str(if (member.isUnlimited) S.drive_link_views_unlimited else S.desktop_hub_sp_limit_ph),
            enabled = !member.isUnlimited && !member.isSenior,
        )
        FieldHint(str(S.desktop_hub_sp_limit_hint, symbol))
        ToggleRow(
            label = str(S.desktop_hub_sp_can_override),
            hint = str(S.desktop_hub_sp_can_override_hint),
            checked = member.canOverride,
            onCheckedChange = { update(draft.copy(member = member.copy(canOverride = it))) },
            enabled = !member.isSenior,
        )
        ToggleRow(
            label = str(S.desktop_is_senior),
            hint = str(S.desktop_hub_sp_is_senior_hint),
            checked = member.isSenior,
            // Senior cascades to unlimited posting and override, and locks both.
            onCheckedChange = { on ->
                val next = if (on) {
                    member.copy(isSenior = true, postingLimit = null, canOverride = true)
                } else {
                    member.copy(isSenior = false)
                }
                update(draft.copy(member = next))
            },
        )
    }
}

/**
 * The web's `DeductionRuleModal`. A system rule keeps its title and
 * description; process, threshold, value, enabled and triggers stay editable.
 * No Cancel — the web's has none either: closing discards, the footer commits.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod", "CyclomaticComplexMethod") // A form, read top to bottom; the order is the reading order.
@Composable
private fun SpendRuleDialog(state: AccountHubUiState, draft: SpendDraft.Rule?, onEvent: (AccountHubEvent) -> Unit) {
    val rule = draft?.rule
    fun update(next: SpendDraft.Rule) = onEvent(AccountHubEvent.EditSpendDraft(next))
    ZillitDialogShell(
        title = str(
            when {
                draft?.index == null -> S.desktop_pc_add_rule
                rule?.systemDefault == true -> S.desktop_hub_sp_edit_rule_system_title
                else -> S.desktop_pc_edit_rule
            },
        ),
        icon = ZillitIcons.Calculator,
        visible = draft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissSpendDraft) },
        actions = {
            ZillitButton(
                text = str(if (draft?.index == null) S.add else S.update),
                onClick = { onEvent(AccountHubEvent.CommitSpendDraft) },
                enabled = rule?.title?.isNotBlank() == true,
            )
        },
    ) {
        if (draft == null || rule == null) return@ZillitDialogShell
        fun patch(next: SpendDeductionRule) = update(draft.copy(rule = next))
        val locked = rule.systemDefault
        val deduct = rule.processType == SpendDeductionRule.DEDUCT_AMOUNT
        val symbol = state.setup.currencies.saved.default?.symbol.orEmpty()
        ZillitTextField(
            value = rule.title,
            onValueChange = { patch(rule.copy(title = it)) },
            label = str(S.title),
            placeholder = str(S.desktop_pc_rule_title_placeholder),
            enabled = !locked,
        )
        ZillitTextField(
            value = rule.description,
            onValueChange = { patch(rule.copy(description = it)) },
            label = str(S.description),
            placeholder = str(S.desktop_pc_rule_desc_placeholder),
            singleLine = false,
            enabled = !locked,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            HubSelect(
                value = rule.processType,
                options = PROCESSES,
                label = ::processOption,
                onSelect = { picked -> if (picked != null) patch(rule.withProcess(picked)) },
                fieldLabel = str(S.ah_process),
                searchable = false,
                showInitials = false,
                modifier = Modifier.weight(PROCESS_WEIGHT),
            )
            HubSelect(
                value = rule.thresholdType,
                options = if (deduct) THRESHOLDS else listOf(SpendDeductionRule.MIN_AMOUNT),
                label = { thresholdOption(it, deduct) },
                onSelect = { picked -> if (picked != null) patch(rule.copy(thresholdType = picked)) },
                fieldLabel = str(if (deduct) S.desktop_hub_sp_deduct_by else S.desktop_threshold),
                enabled = deduct,
                searchable = false,
                showInitials = false,
                modifier = Modifier.weight(1f),
            )
            // One field, two meanings: only the currency one is money, so only it gets the calculator.
            val valueLabel =
                if (rule.isPercentage) str(S.desktop_hub_sp_value_pct) else str(S.desktop_pc_value_unit, symbol)
            if (rule.isPercentage) {
                ZillitTextField(
                    value = draft.valueText,
                    onValueChange = { update(draft.copy(valueText = it.filter { c -> c.isDigit() || c == '.' })) },
                    label = valueLabel,
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
            } else {
                CalcField(
                    value = draft.valueText,
                    onValueChange = { update(draft.copy(valueText = it)) },
                    label = valueLabel,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ToggleRow(
            label = str(S.dm_allow_enabled),
            hint = str(S.desktop_hub_sp_enabled_hint),
            checked = rule.enable,
            onCheckedChange = { patch(rule.copy(enable = it)) },
        )
        FieldLabel(str(S.desktop_hub_sp_trigger_codes))
        if (rule.triggerCodes.isEmpty()) {
            FieldHint(str(S.desktop_pc_no_triggers))
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                rule.triggerCodes.forEach { code ->
                    Chip(code, onRemove = { patch(rule.copy(triggerCodes = rule.triggerCodes - code)) })
                }
            }
        }
        fun addTrigger(raw: String) {
            val text = raw.trim()
            val known = rule.triggerCodes.any { it.equals(text, ignoreCase = true) }
            if (text.isNotEmpty() && !known) {
                update(draft.copy(rule = rule.copy(triggerCodes = rule.triggerCodes + text), triggerText = ""))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitTextField(
                value = draft.triggerText,
                onValueChange = { update(draft.copy(triggerText = it)) },
                placeholder = str(S.desktop_hub_sp_trigger_ph),
                imeAction = ImeAction.Done,
                onImeAction = { addTrigger(draft.triggerText) },
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.add),
                onClick = { addTrigger(draft.triggerText) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = draft.triggerText.isNotBlank(),
            )
        }
        val quickCodes = state.setup.spendSetup.edited.quickCodes
        if (quickCodes.isNotEmpty()) {
            FieldLabel(str(S.desktop_hub_sp_from_quick_codes))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                quickCodes.forEach { code ->
                    val covered = coveredBy(rule, code)
                    ZillitButton(
                        text = code.name.ifBlank { "—" },
                        onClick = {
                            val merged = (rule.triggerCodes + code.keywords).distinct()
                            patch(rule.copy(triggerCodes = merged))
                        },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                        enabled = !covered,
                    )
                }
            }
        }
    }
}

/** Whether every keyword of [code] is already a trigger of [rule] (or it has none) — the web's `allKeywordsInRule`. */
private fun coveredBy(rule: SpendDeductionRule, code: SpendQuickCode): Boolean {
    val have = rule.triggerCodes.map { it.lowercase() }
    return code.keywords.all { it.lowercase() in have }
}

private val PROCESSES = listOf(
    SpendDeductionRule.DEDUCT_AMOUNT,
    SpendDeductionRule.SENIOR_REVIEW,
    SpendDeductionRule.NEED_QUERY,
)

/** The process names are the longest words, so that field takes the larger share of the row. */
private const val PROCESS_WEIGHT = 1.5f

private val THRESHOLDS = listOf(SpendDeductionRule.PERCENTAGE, SpendDeductionRule.MIN_AMOUNT)

private fun processOption(process: String): String = when (process) {
    SpendDeductionRule.DEDUCT_AMOUNT -> str(S.desktop_pc_process_deduct)
    SpendDeductionRule.SENIOR_REVIEW -> str(S.desktop_ce_senior_review)
    SpendDeductionRule.NEED_QUERY -> str(S.desktop_pc_process_query)
    else -> process
}

/** Under a deduction the amount threshold reads "Amount"; under the other two it is the "Min amount" the web names. */
private fun thresholdOption(threshold: String, deduct: Boolean): String = when {
    threshold == SpendDeductionRule.PERCENTAGE -> str(S.desktop_dm_percentage)
    deduct -> str(S.amount)
    else -> str(S.desktop_pc_min_amount)
}
