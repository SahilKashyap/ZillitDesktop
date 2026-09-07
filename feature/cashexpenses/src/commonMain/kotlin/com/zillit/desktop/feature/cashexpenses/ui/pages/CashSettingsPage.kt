package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.cashexpenses.domain.CashTeamMember
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState

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
    textColumn("Name", ColumnWidth.Weight(1.5f)) { it.name.ifBlank { it.userId } },
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
