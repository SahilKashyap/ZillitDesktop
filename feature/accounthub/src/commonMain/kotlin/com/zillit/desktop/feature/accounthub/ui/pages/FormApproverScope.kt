package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.TipBanner
import com.zillit.desktop.feature.accounthub.ui.scopeLabel

/**
 * "Set Approver Level" — the web's `ScopeModal`: every department, or one.
 *
 * The accounts department is not offered: it approves everyone else's
 * documents and never configures a chain of its own.
 */
@Composable
internal fun FormScopeDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val scope = state.formConfig.scopeModal
    val shown = rememberLatestNonNull(scope)
    val close = { onEvent(AccountHubEvent.OpenApproverScope(false)) }
    ZillitDialogShell(
        title = str(S.desktop_set_approver_level),
        icon = ZillitIcons.Shield,
        visible = scope != null,
        onDismiss = close,
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(text = str(S.cancel), onClick = close, variant = ButtonVariant.Tertiary)
            ZillitButton(
                text = str(S.continue_text),
                onClick = { onEvent(AccountHubEvent.ContinueApproverScope) },
                enabled = shown?.canContinue == true,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_hub_how_would_you_like_to_configure_approvers),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ScopeOption(
            icon = ZillitIcons.Users,
            title = str(S.desktop_for_all_departments),
            subtitle = str(S.desktop_hub_same_approval_levels_applied_to_every_department),
            active = shown?.mode == ApprovalScope.All,
        ) { onEvent(AccountHubEvent.PickApproverScope(ApprovalScope.All)) }
        ScopeOption(
            icon = ZillitIcons.Shield,
            title = str(S.desktop_for_one_department),
            subtitle = str(S.desktop_hub_configure_levels_for_a_specific_department),
            active = shown?.mode == ApprovalScope.Department,
        ) { onEvent(AccountHubEvent.PickApproverScope(ApprovalScope.Department, shown?.departmentId)) }
        if (shown?.mode == ApprovalScope.Department) {
            val options = state.departmentList.filterNot { it.identifier == ACCOUNTS_IDENTIFIER }
            HubSelect<HubDepartment>(
                value = options.firstOrNull { it.id == shown.departmentId },
                options = options,
                label = { it.name.localised() },
                onSelect = { onEvent(AccountHubEvent.PickApproverScope(ApprovalScope.Department, it?.id)) },
                placeholder = str(S.desktop_choose_a_department_prompt),
                fieldLabel = str(S.select_department),
                modifier = Modifier.fillMaxWidth(),
            )
            if (options.isEmpty()) FieldHint(str(S.desktop_hub_this_production_has_no_departments_to_configure_yet))
        }
    }
}

/** A choice card: an icon, what it means, and the accent edge when picked. */
@Composable
private fun ScopeOption(icon: ImageVector, title: String, subtitle: String, active: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val edge by animateColorAsState(if (active) colors.accent else colors.border, label = "scopeEdge")
    val shape = ZillitTheme.shapes.large
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (active) colors.accentSoft else colors.surface)
            .border(if (active) 1.5.dp else 1.dp, edge, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon = icon, tint = if (active) colors.accentText else colors.textMuted, size = 16.dp)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = title,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (active) colors.accentText else colors.textPrimary,
            )
            ZillitText(text = subtitle, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        }
    }
}

/**
 * The builder's chrome while the chosen scope's levels are read — the web
 * shows its builder at once with "Loading configuration..." under the banner.
 * Back, or Cancel, stops waiting; an answer that lands afterwards is dropped.
 */
@Composable
internal fun ApproverLoadingView(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val load = state.formConfig.approverLoad ?: return
    val cancel = { onEvent(AccountHubEvent.OpenApproverScope(false)) }
    val scopeLabel = load.scopeLabel(state)
    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            BackChip(onClick = cancel)
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                MonoLabel(
                    str(S.desktop_forms),
                    modifier = Modifier.clickable(onClick = cancel),
                    color = colors.accentText,
                )
                ZillitText(text = "/", style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
                ZillitText(
                    text = state.formConfig.module.label,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textSecondary,
                )
                ZillitText(text = "•", style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
                ZillitText(
                    text = scopeLabel,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            ZillitButton(text = str(S.cancel), onClick = cancel, variant = ButtonVariant.Secondary)
            ZillitButton(text = str(S.dm_setup_save), onClick = {}, leadingIcon = ZillitIcons.Check, enabled = false)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(ZillitTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = BUILDER_WIDTH).fillMaxWidth()) {
                TipBanner(BuilderChrome.forms(state.formConfig.module.label).tip(scopeLabel.takeIf {
                    load.scope == ApprovalScope.Department
                }))
                FormLoadingLine(str(S.desktop_loading_configuration))
            }
        }
    }
}

private const val ACCOUNTS_IDENTIFIER = "department_accounts"
private val DIALOG_WIDTH = 440.dp
private val BUILDER_WIDTH = 1040.dp
