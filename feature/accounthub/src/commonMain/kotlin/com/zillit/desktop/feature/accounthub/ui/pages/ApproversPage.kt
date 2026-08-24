package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalRule
import com.zillit.desktop.feature.accounthub.domain.ApprovalSequence
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.HubPage

/**
 * Approval chains, per module.
 *
 * ## Levels fill from the bottom up
 *
 * A level may hold approvers only once every level before it does. Without the
 * rule a chain saves with level 1 empty and level 2 filled, and nothing routes
 * — the document waits forever at a level with nobody in it. The editor refuses
 * the save and names the offending level rather than letting the server take it.
 *
 * ## A department with no chain is not a department without approvals
 *
 * The server resolves a department's chain first and falls back to the
 * production-wide one, so the absence of a department row means "inherits",
 * which is what this screen says.
 */
@Composable
fun ApproversPage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val approvals = state.approvals

    HubPage {
        ZillitPageHeader(
            eyebrow = "Configuration",
            title = "Approvers",
            description = "Who signs off, in what order, for each module.",
            actions = {
                ZillitButton(
                    text = "Refresh",
                    onClick = { onEvent(AccountHubEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    loading = approvals.loading,
                )
            },
        )

        ZillitTabStrip(
            tabs = ApprovalModule.entries.map { ZillitTab(it.wire, it.label) },
            activeId = approvals.module.wire,
            onSelect = { wire ->
                ApprovalModule.entries.firstOrNull { it.wire == wire }
                    ?.let { onEvent(AccountHubEvent.SwitchApprovalModule(it)) }
            },
        )

        if (!state.viewer.canActAsAccountant) {
            ZillitNotice(
                text = "Approval chains are read-only for you — the service restricts changes " +
                    "to the accounts department, and an admin is not exempt.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }

        if (approvals.loading) ZillitSpinner()

        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            DefaultChainCard(state, onEvent)
            DepartmentChainsCard(state, onEvent)
        }
    }

    ApprovalEditorDialog(state, onEvent)
}

@Composable
private fun DefaultChainCard(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val config = state.approvals.defaultConfig

    ZillitSectionCard(
        title = "Production-wide chain",
        icon = ZillitIcons.Shield,
        meta = config?.let { "${it.levelCount} level(s)" },
        action = {
            if (state.viewer.canActAsAccountant) {
                ZillitButton(
                    text = if (config == null) "Set up" else "Edit",
                    onClick = { onEvent(AccountHubEvent.EditApprovalConfig(config)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                )
            }
        },
    ) {
        if (config == null || !config.isConfigured) {
            ZillitNotice(
                text = "No chain configured for ${state.approvals.module.label}. Documents " +
                    "will not route until one is.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
            return@ZillitSectionCard
        }
        ChainSummary(config)
    }
}

@Composable
private fun DepartmentChainsCard(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val rows = state.approvals.departmentConfigs

    ZillitSectionCard(
        title = "Department overrides",
        icon = ZillitIcons.Users,
        meta = "${rows.size} configured",
    ) {
        ZillitText(
            // Said plainly, because an empty list here looks like a gap.
            text = "A department with no override inherits the production-wide chain.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        rows.forEach { config ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = config.departmentName.ifBlank {
                            config.departmentId ?: "Unnamed department"
                        },
                        maxLines = 1,
                    )
                    ZillitText(
                        text = "${config.levelCount} level(s)",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                if (state.viewer.canActAsAccountant) {
                    ZillitButton(
                        text = "Edit",
                        onClick = { onEvent(AccountHubEvent.EditApprovalConfig(config)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChainSummary(config: ApprovalConfig) {
    config.tiers.forEach { tier ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitStatusPill(
                label = "Level ${tier.order}",
                tone = if (tier.isAssigned) StatusTone.Done else StatusTone.Pending,
            )
            ZillitText(
                text = if (tier.isAssigned) {
                    "${tier.approverCount} approver(s) · " +
                        tier.rules.filter { it.isAssigned }.joinToString(", ") {
                            it.type.ifBlank { "named" }
                        }
                } else {
                    "No approvers"
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * The chain editor.
 *
 * Approvers are entered as ids because this module has no crew directory of its
 * own; a picker belongs here once the session's roster is threaded through, and
 * an id field that works beats a picker that does not exist.
 */
@Composable
private fun ApprovalEditorDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val config = state.approvals.editing
    val problem = config?.let { ApprovalSequence.validationError(it.tiers) }

    ZillitDialogShell(
        title = "Approval chain",
        subtitle = config?.let { "${it.module.label} · ${it.scope.label}" },
        visible = config != null,
        onDismiss = { onEvent(AccountHubEvent.DismissApprovalConfig) },
        icon = ZillitIcons.Shield,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissApprovalConfig) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Save chain",
                onClick = { onEvent(AccountHubEvent.SaveApprovalConfig) },
                enabled = problem == null && !state.approvals.saving,
                loading = state.approvals.saving,
            )
        },
    ) {
        if (config == null) return@ZillitDialogShell

        config.tiers.forEach { tier ->
            LevelEditor(
                tier = tier,
                canRemove = config.tiers.size > 1,
                onChange = { next ->
                    onEvent(
                        AccountHubEvent.UpdateApprovalConfig(
                            config.copy(
                                tiers = config.tiers.map { if (it.order == tier.order) next else it },
                            ),
                        ),
                    )
                },
                onRemove = { onEvent(AccountHubEvent.RemoveApprovalLevel(tier.order)) },
            )
        }

        ZillitButton(
            text = "Add level",
            onClick = { onEvent(AccountHubEvent.AddApprovalLevel) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
        )

        problem?.let {
            ZillitNotice(text = it, tone = StatusTone.Pending, icon = ZillitIcons.Warning)
        }
        if (problem == null && ApprovalSequence.emptyLevels(config.tiers).isNotEmpty()) {
            // Trailing blanks are allowed through and dropped on save, so say
            // that rather than leaving the user to wonder why they vanished.
            ZillitNotice(
                text = "Empty levels at the end are dropped when this is saved.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }
    }
}

@Composable
private fun LevelEditor(
    tier: ApprovalTier,
    canRemove: Boolean,
    onChange: (ApprovalTier) -> Unit,
    onRemove: () -> Unit,
) {
    val rule = tier.rules.firstOrNull() ?: ApprovalRule()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSectionLabel("Level ${tier.order}")
            ZillitStatusPill(
                label = if (tier.isAssigned) "${tier.approverCount} approver(s)" else "Empty",
                tone = if (tier.isAssigned) StatusTone.Done else StatusTone.Neutral,
            )
            Column(modifier = Modifier.weight(1f)) {}
            if (canRemove) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Remove level ${tier.order}",
                    onClick = onRemove,
                )
            }
        }
        ZillitSelect(
            value = rule.type.ifBlank { RULE_TYPES.first() },
            options = RULE_TYPES,
            onSelect = { type -> onChange(tier.copy(rules = listOf(rule.copy(type = type)))) },
            label = { it.replace('_', ' ').replaceFirstChar(Char::uppercase) },
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = rule.userIds.joinToString(", "),
            onValueChange = { text ->
                val ids = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                onChange(tier.copy(rules = listOf(rule.copy(userIds = ids))))
            },
            label = "Approver user ids",
            placeholder = "Comma-separated",
        )
    }
}

/** The rule kinds the server accepts on a level. */
private val RULE_TYPES = listOf("user", "department_head", "any_of")
