package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.accounthub.domain.ApprovalCandidates
import com.zillit.desktop.feature.accounthub.domain.ApprovalConfig
import com.zillit.desktop.feature.accounthub.domain.ApprovalRule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.ApprovalSequence
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier
import com.zillit.desktop.feature.accounthub.domain.HubUser
import com.zillit.desktop.feature.accounthub.domain.HubUsers
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.ApprovalBuilder
import com.zillit.desktop.feature.accounthub.ui.BuilderConfirm
import com.zillit.desktop.feature.accounthub.ui.UNKNOWN_PERSON
import com.zillit.desktop.feature.accounthub.ui.asAmountText
import com.zillit.desktop.feature.accounthub.ui.components.CalcField
import com.zillit.desktop.feature.accounthub.ui.components.DashedInsertRail
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.HubConfirmDialog
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.TipBanner
import com.zillit.desktop.feature.accounthub.ui.components.rememberHubFace

/**
 * The chain editor — the builder view of the web's `ApproversModule`.
 *
 * A breadcrumb bar with Cancel and Save, the chain's name, a banner saying
 * what is being configured, then the levels. Each level holds one or more
 * rules — Default, or "Amount greater than" a threshold — and each rule its
 * own approvers, shown with their photo and role. The dashed rail above,
 * between and below the cards inserts a level at that point.
 *
 * Why a save did not go through is shown above the levels rather than in a
 * toast: it names something on this page to fix.
 */
@Composable
internal fun ApprovalBuilderView(
    state: AccountHubUiState,
    builder: ApprovalBuilder,
    onEvent: (AccountHubEvent) -> Unit,
    chrome: BuilderChrome = BuilderChrome.Approvers,
) {
    val colors = ZillitTheme.colors
    val config = builder.config
    val department = if (config.scope == ApprovalScope.All) {
        null
    } else {
        config.departmentName.ifBlank { state.departmentName(config.departmentId) }.ifBlank { "Department" }
    }
    Column(modifier = Modifier.fillMaxSize().background(colors.canvas)) {
        BuilderTopBar(config, department, state.approvals.saving, chrome, onEvent)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(ZillitTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = BUILDER_MAX_WIDTH).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                if (chrome.heading) BuilderHeading(config, department)
                TipBanner(chrome.tip(department))
                builder.error?.let { ZillitNotice(text = it, tone = StatusTone.Rejected, icon = ZillitIcons.Warning) }
                Column {
                    InsertRail(0, onEvent)
                    config.tiers.forEachIndexed { index, tier ->
                        TierCard(tier, levels = config.tiers.size, state = state, onEvent = onEvent)
                        InsertRail(index + 1, onEvent)
                    }
                }
            }
        }
    }
}

/**
 * The words around a chain builder, which differ by the page that opened it:
 * the breadcrumb's first word (which also closes the builder), the module's
 * name, what the production-wide chain is called, whether the compact hero
 * shows, and the banner over the levels.
 */
internal data class BuilderChrome(
    val root: String,
    val moduleLabel: String?,
    val allLabel: String,
    val heading: Boolean,
    val tip: (department: String?) -> String,
) {
    companion object {
        val Approvers = BuilderChrome(
            root = "Approvers",
            moduleLabel = null,
            allLabel = "Default Approval Levels",
            heading = true,
            tip = { department ->
                if (department == null) {
                    "Configuring default approval levels for all departments. This baseline applies to every " +
                        "department without a custom override."
                } else {
                    "Configuring approval levels for $department. This overrides the default configuration."
                }
            },
        )

        /** Forms Configuration's builder — the web's own crumb and banner, no hero. */
        fun forms(moduleLabel: String) = BuilderChrome(
            root = "Forms",
            moduleLabel = moduleLabel,
            allLabel = "All Departments",
            heading = false,
            tip = { department ->
                if (department == null) {
                    "Configuring approval levels for all departments. Changes will apply uniformly."
                } else {
                    "Configuring approval levels for $department."
                }
            },
        )
    }
}

/** Back, "APPROVERS / Purchase Orders • Camera", Cancel and Save changes. */
@Composable
private fun BuilderTopBar(
    config: ApprovalConfig,
    department: String?,
    saving: Boolean,
    chrome: BuilderChrome,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val close = { onEvent(AccountHubEvent.DismissApprovalConfig) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIconButton(icon = ZillitIcons.ArrowLeft, contentDescription = "Back to approvers list", onClick = close)
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            MonoLabel(chrome.root, modifier = Modifier.clickable(onClick = close), color = colors.accentText)
            ZillitText(text = "/", style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
            ZillitText(
                text = chrome.moduleLabel ?: config.module.label,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textSecondary,
                maxLines = 1,
            )
            ZillitText(text = "•", style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
            ZillitText(
                text = department ?: chrome.allLabel,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
        ZillitButton(text = "Cancel", onClick = close, variant = ButtonVariant.Secondary, enabled = !saving)
        ZillitButton(
            text = if (saving) "Saving…" else "Save changes",
            onClick = { onEvent(AccountHubEvent.SaveApprovalConfig) },
            leadingIcon = ZillitIcons.Check,
            loading = saving,
            enabled = !saving,
        )
    }
}

/** The chain's name beside its module — the web's compact hero. */
@Composable
private fun BuilderHeading(config: ApprovalConfig, department: String?) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ApprovalIconTile(
            icon = ZillitIcons.Shield,
            tint = colors.accent,
            background = colors.accentSoft,
            ring = colors.accent.copy(alpha = APPROVAL_RING_ALPHA),
            size = HEADING_TILE,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            MonoLabel("Management", color = colors.accentText)
            ZillitText(
                text = department?.let { "$it — Approval Levels" } ?: "Default Approval Levels",
                style = ZillitTheme.typography.titleLarge,
                maxLines = 1,
            )
        }
        ApprovalHeroStat(
            label = "Module",
            value = config.module.label,
            icon = config.module.icon,
            content = colors.accentText,
            background = colors.accentSoft,
            ring = colors.accent.copy(alpha = APPROVAL_RING_ALPHA),
        )
    }
}

/** The dashed line with a round "+" — inserts a level at [position] (0-based). */
@Composable
private fun InsertRail(position: Int, onEvent: (AccountHubEvent) -> Unit) {
    DashedInsertRail("Insert level here") { onEvent(AccountHubEvent.InsertApprovalLevel(position)) }
}

/** One level: its number, its rules, "Add more", and "Remove level" while there are others. */
@Composable
private fun TierCard(tier: ApprovalTier, levels: Int, state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            LevelBadge(tier.order)
            ZillitText(text = "Level ${tier.order}", style = ZillitTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            if (levels > 1) {
                TextAction(text = "Remove level", icon = ZillitIcons.Close, tint = colors.danger, bordered = true) {
                    onEvent(AccountHubEvent.RemoveApprovalLevel(tier.order))
                }
            }
        }
        tier.rules.forEachIndexed { index, rule ->
            if (index > 0) ApprovalDashedRule()
            RuleRow(tier, index, rule, state, onEvent)
        }
        TextAction(text = "Add more", icon = ZillitIcons.Add, tint = colors.accentText, bordered = false) {
            onEvent(AccountHubEvent.AddApprovalRule(tier.order))
        }
    }
}

@Composable
private fun LevelBadge(order: Int) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .size(LEVEL_BADGE)
            .clip(CircleShape)
            .background(colors.accentSoft)
            .border(1.dp, colors.accent.copy(alpha = APPROVAL_RING_ALPHA), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = order.toString(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = colors.accentText,
        )
    }
}

/** A small word-and-icon action — "Add more", "Remove level". */
@Composable
private fun TextAction(text: String, icon: ImageVector, tint: Color, bordered: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .then(if (bordered) Modifier.border(1.dp, colors.border, ZillitTheme.shapes.small) else Modifier)
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (bordered) ZillitTheme.spacing.sm else ZillitTheme.spacing.xxs,
                vertical = ZillitTheme.spacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(icon = icon, tint = tint, size = ACTION_ICON)
        ZillitText(text = text, style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold), color = tint)
    }
}

/**
 * One rule — the web's `RuleRow`: its kind, an amount when it is "Amount
 * greater than", then Add Users and a chip per approver once it has a kind.
 * An amount rule on a level with a Default cannot change kind.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleRow(
    tier: ApprovalTier,
    index: Int,
    rule: ApprovalRule,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val order = tier.order
    val amount = rule.type == ApprovalRule.AMOUNT
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                RuleKindSelect(
                    rule = rule,
                    locked = tier.locks(rule),
                    onSelect = { onEvent(AccountHubEvent.SetApprovalRuleType(order, index, it)) },
                )
                if (amount) {
                    CalcField(
                        value = rule.amountThreshold.asAmountText(),
                        onValueChange = { text ->
                            onEvent(AccountHubEvent.SetApprovalRuleAmount(order, index, text.toDoubleOrNull()))
                        },
                        placeholder = "0.00",
                        modifier = Modifier.width(AMOUNT_FIELD),
                    )
                }
                if (rule.isTyped) {
                    ZillitButton(
                        text = "Add Users",
                        onClick = { onEvent(AccountHubEvent.OpenApproverPicker(order, index)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.UserPlus,
                    )
                    rule.userIds.forEach { id ->
                        ApproverChip(id, state) { onEvent(AccountHubEvent.RemoveApprover(order, index, id)) }
                    }
                }
            }
            if (tier.rules.size > 1) {
                Box(modifier = Modifier.height(RULE_ROW_HEIGHT), contentAlignment = Alignment.Center) {
                    ZillitIconButton(
                        icon = ZillitIcons.Close,
                        contentDescription = "Remove this rule",
                        onClick = { onEvent(AccountHubEvent.RemoveApprovalRule(order, index)) },
                    )
                }
            }
        }
        if (amount) FieldHint("Applicable for all currencies. No exchange rates applied.")
    }
}

/**
 * The rule's kind. Locked, it says why on hover — the web only greys it out,
 * which reads as a placeholder rather than a rule.
 */
@Composable
private fun RuleKindSelect(rule: ApprovalRule, locked: Boolean, onSelect: (String) -> Unit) {
    val select = @Composable {
        ZillitSelect(
            value = rule.type,
            options = RULE_TYPES.map { it.first },
            onSelect = onSelect,
            label = { wire -> RULE_TYPES.firstOrNull { it.first == wire }?.second ?: "Select a rule..." },
            modifier = Modifier.width(RULE_SELECT),
            enabled = !locked,
        )
    }
    if (locked) {
        ZillitTooltip("This level has a Default rule, so its other rules are \"Amount greater than\".", select)
    } else {
        select()
    }
}

/** An approver inside a rule: photo, name, role, and a remove — the web's `EditableApproverChip`. */
@Composable
private fun ApproverChip(userId: String, state: AccountHubUiState, onRemove: () -> Unit) {
    val colors = ZillitTheme.colors
    val name = state.userName(userId)
    val role = state.user(userId)?.roleLabel.orEmpty()
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(colors.accentSoft)
            .border(1.dp, colors.accent.copy(alpha = APPROVAL_RING_ALPHA), ZillitTheme.shapes.pill)
            .padding(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = name, image = rememberHubFace(userId), userId = userId, size = CHIP_AVATAR)
        ZillitText(
            text = name,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
        if (role.isNotBlank()) {
            ZillitText(
                text = role.localised(),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .size(CHIP_REMOVE)
                .clip(CircleShape)
                .background(colors.surface)
                .clickable(onClick = onRemove)
                .semantics { contentDescription = "Remove $name" },
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.Close, tint = colors.textMuted, size = CHIP_REMOVE_ICON)
        }
    }
}

/** The user picker and the two confirmations the builder can open. */
@Composable
internal fun ApprovalBuilderDialogs(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val approvals = state.approvals
    val builder = approvals.builder
    ApproverPicker(state, builder, onEvent)
    // The last question asked, so the dialog keeps its words while it fades out.
    val confirm = rememberLatest(builder?.confirm)
    HubConfirmDialog(
        visible = builder?.confirm != null,
        title = if (confirm is BuilderConfirm.RevertToGlobal) "Remove all approvers?" else "Empty approval levels",
        message = when (confirm) {
            is BuilderConfirm.EmptyLevels -> ApprovalSequence.compactionMessage(confirm.levels)
            is BuilderConfirm.RevertToGlobal ->
                "You've removed all approvers from every level. Saving will delete this department's approval " +
                    "levels, so it will use the global (default) approvers. Do you want to continue?"
            null -> ""
        },
        confirmLabel = "Save",
        danger = confirm is BuilderConfirm.RevertToGlobal,
        loading = approvals.saving,
        onConfirm = { onEvent(AccountHubEvent.ConfirmApprovalSave) },
        onDismiss = { if (!approvals.saving) onEvent(AccountHubEvent.DismissApprovalConfirm) },
    )
}

/**
 * "Add Approvers — Level N" — the web's `UserPickerModal`.
 *
 * Ticking stages a person; nobody joins the rule until "Add N users". Anyone
 * already on the level, under any of its rules, is shown as Added and cannot
 * be ticked. Who is offered at all is [ApprovalCandidates].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApproverPicker(state: AccountHubUiState, builder: ApprovalBuilder?, onEvent: (AccountHubEvent) -> Unit) {
    val approvals = state.approvals
    val level = builder?.config?.level(builder.pickerTier)
    val order = rememberLatest(builder?.pickerTier)
    val taken = level?.userIds.orEmpty().toSet()
    val picked = builder?.picked.orEmpty()
    val search = builder?.pickerSearch.orEmpty()
    val shown = HubUsers.search(ApprovalCandidates.pick(state.users, approvals.candidateIds), search)
    val close = { onEvent(AccountHubEvent.CloseApproverPicker) }
    ZillitDialogShell(
        title = "Add Approvers — Level ${order ?: ""}",
        subtitle = "People with view access on ${approvals.module.label}, plus the accounts team.",
        icon = ZillitIcons.UserPlus,
        visible = level != null,
        onDismiss = close,
        width = PICKER_WIDTH,
        scrollable = false,
        actions = {
            ZillitButton(text = "Cancel", onClick = close, variant = ButtonVariant.Tertiary)
            ZillitButton(
                text = when (picked.size) {
                    0 -> "Add users"
                    1 -> "Add 1 user"
                    else -> "Add ${picked.size} users"
                },
                onClick = { onEvent(AccountHubEvent.AddPickedApprovers) },
                enabled = picked.isNotEmpty(),
            )
        },
    ) {
        ZillitSearchField(
            value = search,
            onValueChange = { onEvent(AccountHubEvent.SearchApproverPicker(it)) },
            placeholder = "Search users...",
            modifier = Modifier.fillMaxWidth(),
        )
        if (picked.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                picked.forEach { id -> PickedChip(id, state) { onEvent(AccountHubEvent.ToggleApproverPick(id)) } }
            }
        }
        if (approvals.candidateIds == null) {
            FieldHint("Checking who has view access — the accounts team is shown meanwhile.")
        }
        PickerList(shown, taken, picked) { id -> onEvent(AccountHubEvent.ToggleApproverPick(id)) }
    }
}

@Composable
private fun PickerList(shown: List<HubUser>, taken: Set<String>, picked: List<String>, onToggle: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = PICKER_LIST).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        if (shown.isEmpty()) FieldHint("No users found", Modifier.padding(ZillitTheme.spacing.lg))
        shown.forEach { user ->
            PickerRow(
                user = user,
                added = user.id in taken,
                picked = user.id in picked,
                onToggle = { onToggle(user.id) },
            )
        }
    }
}

@Composable
private fun PickerRow(user: HubUser, added: Boolean, picked: Boolean, onToggle: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val name = user.name.ifBlank { UNKNOWN_PERSON }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (added) ADDED_ALPHA else 1f)
            .clip(ZillitTheme.shapes.medium)
            .background(
                when {
                    picked -> colors.accentSoft
                    hovered && !added -> colors.surfaceHover
                    else -> Color.Transparent
                },
            )
            .border(
                1.dp,
                if (picked) colors.accent.copy(alpha = APPROVAL_RING_ALPHA) else Color.Transparent,
                ZillitTheme.shapes.medium,
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = !added, onClick = onToggle)
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitAvatar(name = name, image = rememberHubFace(user.id), userId = user.id, size = PICKER_AVATAR)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = name,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (user.isAdmin) Pill("Admin", tone = StatusTone.Pending)
            }
            if (user.roleLabel.isNotBlank()) {
                ZillitText(
                    text = user.roleLabel.localised(),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        when {
            picked -> ZillitIcon(icon = ZillitIcons.Check, tint = colors.accent, size = PICK_ICON)
            added -> ZillitText(text = "Added", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
    }
}

/** A staged person above the list, with an untick. */
@Composable
private fun PickedChip(userId: String, state: AccountHubUiState, onRemove: () -> Unit) {
    val colors = ZillitTheme.colors
    val name = state.userName(userId)
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(colors.accentSoft)
            .border(1.dp, colors.accent.copy(alpha = APPROVAL_RING_ALPHA), ZillitTheme.shapes.pill)
            .padding(
                start = ZillitTheme.spacing.xxs,
                end = ZillitTheme.spacing.sm,
                top = ZillitTheme.spacing.xxs,
                bottom = ZillitTheme.spacing.xxs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ApprovalMiniFace(name = name, userId = userId, size = STAGED_AVATAR)
        ZillitText(
            text = name,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .size(CHIP_REMOVE)
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .semantics { contentDescription = "Untick $name" },
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.Close, tint = colors.textMuted, size = CHIP_REMOVE_ICON)
        }
    }
}

/** The last non-null [value], kept past the moment it goes null so a closing dialog does not rewrite itself. */
@Composable
private fun <T : Any> rememberLatest(value: T?): T? {
    val latest = remember { Latest<T>() }
    if (value != null) latest.value = value
    return value ?: latest.value
}

/** A plain holder: written during composition on purpose, and never read as state. */
private class Latest<T : Any> {
    var value: T? = null
}

/** The validator's whole vocabulary, said the web's way. */
private val RULE_TYPES = listOf(ApprovalRule.DEFAULT to "Default", ApprovalRule.AMOUNT to "Amount greater than")

private const val ADDED_ALPHA = 0.45f
private val BUILDER_MAX_WIDTH = 1040.dp
private val HEADING_TILE = 40.dp
private val LEVEL_BADGE = 22.dp
private val ACTION_ICON = 12.dp
private val RULE_SELECT = 200.dp
private val AMOUNT_FIELD = 130.dp
private val RULE_ROW_HEIGHT = 36.dp
private val CHIP_AVATAR = 22.dp
private val CHIP_REMOVE = 18.dp
private val CHIP_REMOVE_ICON = 10.dp
private val STAGED_AVATAR = 20.dp
private val PICKER_WIDTH = 460.dp
private val PICKER_LIST = 340.dp
private val PICKER_AVATAR = 32.dp
private val PICK_ICON = 16.dp
