package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.avatarHue
import com.zillit.desktop.core.designsystem.component.rememberAvatar
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalRule
import com.zillit.desktop.feature.accounthub.domain.ApprovalTier
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.BuilderOrigin
import com.zillit.desktop.feature.accounthub.ui.DepartmentFilter
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.TipBanner
import com.zillit.desktop.feature.accounthub.ui.components.groupAmount
import com.zillit.desktop.feature.accounthub.ui.components.levelSummary
import com.zillit.desktop.feature.accounthub.ui.components.rememberHubFace

/**
 * Approval chains, per module — the web's `ApproversModule`.
 *
 * ## A module rail, a hero, the default chain, then the departments
 *
 * Six modules down the left, each saying Configured or Not started. The page
 * for one shows the production-wide chain first — "Configure Default Levels
 * first to set a baseline" — and every department beneath, marked Custom,
 * Default or Not configured, expandable to its levels and the people on them.
 *
 * ## The builder replaces the page
 *
 * Editing opens a full view, rail and all out of the way, as on the web:
 * level cards holding rules and their approvers, an insert rail between them,
 * and two confirmations before a save — see [ApprovalBuilderView].
 */
@Composable
fun ApproversPage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    // A chain opened from Forms Configuration is shown there, not here.
    val builder = state.approvals.builder?.takeIf { it.origin == BuilderOrigin.Approvers }
    if (builder != null) {
        ApprovalBuilderView(state, builder, onEvent)
    } else {
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            ModuleRail(state, onEvent)
            Box(Modifier.width(1.dp).fillMaxHeight().background(ZillitTheme.colors.border))
            ModuleView(state, onEvent)
        }
    }
    ApprovalBuilderDialogs(state, onEvent)
}

/** The module sidebar — a title, search, two sections, Configured / Not started with a dot. */
@Composable
private fun ModuleRail(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val approvals = state.approvals
    val colors = ZillitTheme.colors
    val needle = approvals.moduleSearch.trim()
    val groups = MODULE_GROUPS
        .map { (title, modules) ->
            title to modules.filter { needle.isEmpty() || it.label.contains(needle, ignoreCase = true) }
        }
        .filter { (_, modules) -> modules.isNotEmpty() }
    ZillitScrollColumn(
        modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight().background(colors.surfaceSunken),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = str(S.desktop_modules), style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = str(S.desktop_approver_configuration),
                style = ZillitTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.textMuted,
            )
        }
        ZillitSearchField(
            value = approvals.moduleSearch,
            onValueChange = { onEvent(AccountHubEvent.SearchApprovalModules(it)) },
            placeholder = str(S.desktop_search_modules),
            modifier = Modifier.fillMaxWidth(),
        )
        groups.forEach { (title, modules) ->
            MonoLabel(
                title,
                modifier = Modifier.padding(start = ZillitTheme.spacing.xs, top = ZillitTheme.spacing.sm),
            )
            modules.forEach { module ->
                ModuleCard(
                    module = module,
                    active = module == approvals.module,
                    configured = approvals.configured[module],
                    onClick = { onEvent(AccountHubEvent.SwitchApprovalModule(module)) },
                )
            }
        }
        if (groups.isEmpty()) FieldHint("No modules match “$needle”.")
    }
}

@Composable
private fun ModuleCard(module: ApprovalModule, active: Boolean, configured: Boolean?, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(1.dp, if (active) colors.accent else colors.border, ZillitTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ApprovalIconTile(
            icon = module.icon,
            tint = if (active) colors.accent else colors.textMuted,
            background = if (active) colors.accentSoft else colors.surfaceSunken,
            ring = if (active) colors.accent.copy(alpha = APPROVAL_RING_ALPHA) else colors.border,
            size = MODULE_TILE,
        )
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = module.label,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (active) colors.accentText else colors.textPrimary,
                maxLines = 1,
            )
            // A module the summary does not answer for carries no word at
            // all: unknown is not "Not started".
            configured?.let {
                ZillitText(
                    text = if (it) str(S.desktop_configured) else str(S.desktop_not_started),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        if (configured == true) Box(Modifier.size(STATUS_DOT).clip(CircleShape).background(colors.success))
    }
}

@Composable
private fun ModuleView(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val approvals = state.approvals
    HubPage {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            // Clear of the scroll rail, which otherwise sits over the cards' right edge.
            contentPadding = PaddingValues(end = ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ApproversHero(state)
            TipBanner(
                str(S.desktop_hub_tip_configure_default_levels_first_to_set_a_baseline_then),
            )
            if (!state.viewer.canActAsAccountant) {
                ZillitNotice(
                    text = str(S.desktop_hub_approval_chains_are_read_only_for_you_the_service_restricts),
                    tone = StatusTone.Neutral,
                    icon = ZillitIcons.Info,
                )
            }
            val failure = approvals.loadError
            when {
                failure != null -> ZillitNotice(
                    text = failure,
                    tone = StatusTone.Rejected,
                    icon = ZillitIcons.Warning,
                    action = {
                        ZillitButton(
                            text = str(S.retry),
                            onClick = { onEvent(AccountHubEvent.ReloadApprovalConfigs) },
                            size = ButtonSize.Small,
                        )
                    },
                )
                // This module's chains are on their way. Showing the cards now
                // would say "No default levels set" about a chain not yet read.
                approvals.loading && approvals.loadedModule != approvals.module ->
                    LoadingLine(str(S.desktop_loading_approval_configs))
                else -> {
                    DefaultLevelsCard(state, onEvent)
                    DepartmentsSection(state, onEvent)
                }
            }
        }
    }
}

/**
 * "Management / Approvers" with the module, department and override counts
 * beside it — or beneath it when the pane is too narrow for both, as the
 * web's hero wraps.
 */
@Composable
private fun ApproversHero(state: AccountHubUiState) {
    val colors = ZillitTheme.colors
    val approvals = state.approvals
    val departments = state.departmentList
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(Brush.verticalGradient(listOf(colors.surface, colors.canvas)))
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
    ) {
        val counters = @Composable {
            HeroCounters(approvals.module, departments.size, approvals.customCount(departments))
        }
        if (maxWidth < HERO_SIDE_BY_SIDE) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                HeroTitle(approvals.module)
                counters()
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                HeroTitle(approvals.module, Modifier.weight(1f))
                counters()
            }
        }
    }
}

@Composable
private fun HeroTitle(module: ApprovalModule, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ApprovalIconTile(
                icon = ZillitIcons.Shield,
                tint = colors.accent,
                background = colors.accentSoft,
                ring = colors.accent.copy(alpha = APPROVAL_RING_ALPHA),
                size = HERO_TILE,
            )
            Column {
                MonoLabel(str(S.desktop_management), color = colors.accentText)
                ZillitText(text = str(S.approvers_empty), style = ZillitTheme.typography.displayLarge)
            }
        }
        ZillitText(
            modifier = Modifier.padding(start = HERO_TILE + ZillitTheme.spacing.md),
            text = buildAnnotatedString {
                append("Configure approval levels for each department on ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.textPrimary)) {
                    append(module.label)
                }
                append(". Defaults apply unless a department overrides them.")
            },
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}

/** Module, Departments and Custom overrides — the hero's right-hand tiles. */
@Composable
private fun HeroCounters(module: ApprovalModule, departments: Int, custom: Int) {
    val colors = ZillitTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ApprovalHeroStat(
            label = "Module",
            value = module.label,
            icon = module.icon,
            content = colors.accentText,
            background = colors.accentSoft,
            ring = colors.accent.copy(alpha = APPROVAL_RING_ALPHA),
        )
        ApprovalHeroStat(
            label = "Departments",
            value = departments.toString(),
            content = colors.textPrimary,
            background = colors.surfaceSunken,
            ring = colors.border,
        )
        ApprovalHeroStat(
            label = "Custom overrides",
            value = custom.toString(),
            content = colors.violet,
            background = colors.violetSoft,
            ring = colors.violet.copy(alpha = APPROVAL_RING_ALPHA),
        )
    }
}

/** A rounded square holding one icon — the web's module and card badges. */
@Composable
internal fun ApprovalIconTile(icon: ImageVector, tint: Color, background: Color, ring: Color, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(ZillitTheme.shapes.medium)
            .background(background)
            .border(1.dp, ring, ZillitTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = icon, tint = tint, size = size * ICON_SHARE)
    }
}

/** One of the hero's counters — the web's `StatTile`. */
@Composable
internal fun ApprovalHeroStat(
    label: String,
    value: String,
    content: Color,
    background: Color,
    ring: Color,
    icon: ImageVector? = null,
) {
    Column(
        modifier = Modifier
            .widthIn(min = STAT_MIN_WIDTH)
            .clip(ZillitTheme.shapes.medium)
            .background(background)
            .border(1.dp, ring, ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        MonoLabel(label)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            icon?.let { ZillitIcon(icon = it, tint = content, size = STAT_ICON) }
            ZillitText(
                text = value,
                style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = content,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LoadingLine(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxl),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSpinner()
        FieldHint(text)
    }
}

/** The green-accented card for the production-wide chain — the web's `DefaultLevelsCard`. */
@Suppress("LongMethod") // A card, read top to bottom; the order is the reading order.
@Composable
private fun DefaultLevelsCard(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val config = state.approvals.defaultConfig
    val configured = config?.isConfigured == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(Brush.verticalGradient(0f to colors.successSoft, GRADIENT_STOP to colors.surface))
            .border(1.dp, colors.success.copy(alpha = APPROVAL_RING_ALPHA), ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ApprovalIconTile(
            icon = ZillitIcons.Shield,
            tint = colors.success,
            background = colors.successSoft,
            ring = colors.success.copy(alpha = APPROVAL_RING_ALPHA),
            size = CARD_TILE,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(text = str(S.desktop_default_approval_levels), style = ZillitTheme.typography.titleSmall)
                ZillitText(
                    text = "all ${state.departmentList.size} departments",
                    style = APPROVAL_MONO,
                    color = colors.textMuted,
                )
                Pill(
                    if (configured) str(S.desktop_configured) else str(S.desktop_not_configured),
                    tone = if (configured) StatusTone.Done else StatusTone.Neutral,
                    dot = true,
                )
            }
            FieldHint(
                if (configured) {
                    str(S.desktop_hub_baseline_used_by_any_department_without_a_custom_override_below)
                } else {
                    str(S.desktop_hub_no_default_levels_set_departments_without_custom_configs_will_have)
                },
            )
            if (config != null && configured) {
                Column(modifier = Modifier.padding(top = ZillitTheme.spacing.sm)) {
                    LevelLines(config.tiers, state) { tier ->
                        val count = tier.approverCount
                        "· $count approver${if (count == 1) "" else "s"}"
                    }
                }
            }
        }
        if (state.viewer.canActAsAccountant) {
            ZillitButton(
                text = if (configured) str(S.edit) else str(S.desktop_configure),
                onClick = { onEvent(AccountHubEvent.EditDefaultApprovals) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
        }
    }
}

/**
 * A chain read-only: "Level 1 · …" over the people on it, levels split by a
 * dashed rule — the web's tier lines under the default card and inside an
 * opened department. [meta] is what follows the level's name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelLines(tiers: List<ApprovalTier>, state: AccountHubUiState, meta: (ApprovalTier) -> String) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        tiers.forEachIndexed { index, tier ->
            if (index > 0) ApprovalDashedRule()
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = "Level ${tier.order}",
                        style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                    )
                    ZillitText(text = meta(tier), style = APPROVAL_MONO, color = colors.textMuted)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    tier.userIds.forEach { id -> ApproverLine(id, state) }
                    if (tier.userIds.isEmpty()) {
                        ZillitText(
                            text = str(S.desktop_no_users_assigned),
                            style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

/** A person on a chain: their photo, their name, and what they do — the web's `ApproverLine`. */
@Composable
private fun ApproverLine(userId: String, state: AccountHubUiState) {
    val name = state.userName(userId)
    val role = state.user(userId)?.roleLabel.orEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = name, image = rememberHubFace(userId), userId = userId, size = LINE_AVATAR)
        Column {
            ZillitText(
                text = name,
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            if (role.isNotBlank()) {
                ZillitText(
                    text = role.localised(),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Up to three people overlapping, each ringed in the card's colour so the
 * edges read, then "+N" — the web's `AvatarStack`.
 */
@Composable
private fun FaceStack(userIds: List<String>, state: AccountHubUiState) {
    val colors = ZillitTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(-STACK_OVERLAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        userIds.take(STACK_MAX).forEach { id ->
            ApprovalMiniFace(
                name = state.userName(id),
                userId = id,
                size = STACK_FACE,
                modifier = Modifier.border(STACK_RING, colors.surface, CircleShape),
            )
        }
        if (userIds.size > STACK_MAX) {
            Box(
                modifier = Modifier
                    .size(STACK_FACE)
                    .clip(CircleShape)
                    .background(colors.surfaceSunken)
                    .border(STACK_RING, colors.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = "+${userIds.size - STACK_MAX}",
                    style = APPROVAL_MONO.copy(fontSize = STACK_COUNT_TEXT, fontWeight = FontWeight.Bold),
                    color = colors.textMuted,
                )
            }
        }
    }
}

/**
 * A small face: the crew photo, or initials sized to the circle.
 *
 * Not [ZillitAvatar] below 24dp — its initials are a fixed 13sp, which a
 * 20dp circle clips to a blank disc.
 */
@Composable
internal fun ApprovalMiniFace(name: String, userId: String?, size: Dp, modifier: Modifier = Modifier) {
    val image = rememberHubFace(userId) ?: rememberAvatar(userId)
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(avatarHue(name)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
            )
        } else {
            val text = (size.value * INITIALS_SHARE).sp
            ZillitText(
                text = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
                style = ZillitTheme.typography.labelSmall.copy(
                    fontSize = text,
                    lineHeight = text,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/** The dashed hairline between levels. */
@Composable
internal fun ApprovalDashedRule(modifier: Modifier = Modifier) {
    val color = ZillitTheme.colors.border
    Canvas(modifier = modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, GAP)),
        )
    }
}

/** "· Default · > 5,000" — a level's rules as the department rows name them. */
private fun ruleSummary(tier: ApprovalTier): String = tier.rules.joinToString(" ") { rule ->
    when (rule.type) {
        ApprovalRule.AMOUNT -> "· > ${groupAmount((rule.amountThreshold ?: 0.0).asPlainAmount())}"
        else -> "· Default"
    }
}

private fun Double.asPlainAmount(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

@Composable
private fun DepartmentsSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val approvals = state.approvals
    val departments = state.departmentList
    val shown = approvals.visibleDepartments(departments)
    val custom = approvals.customCount(departments)
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitSearchField(
                value = approvals.departmentSearch,
                onValueChange = { onEvent(AccountHubEvent.SearchDepartments(it)) },
                placeholder = str(S.invitees_search_departments),
                modifier = Modifier.weight(1f),
            )
            ZillitSegmented(
                options = listOf(
                    ZillitTab(DepartmentFilter.All.name, "All ${departments.size}"),
                    ZillitTab(DepartmentFilter.Custom.name, "Custom $custom"),
                    ZillitTab(DepartmentFilter.Default.name, "Default ${departments.size - custom}"),
                ),
                activeId = approvals.departmentFilter.name,
                onSelect = { name ->
                    DepartmentFilter.entries.firstOrNull { it.name == name }?.let {
                        onEvent(AccountHubEvent.FilterDepartments(it))
                    }
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoLabel(str(S.departments))
            Box(Modifier.weight(1f).height(1.dp).background(colors.border))
            ZillitText(
                text = buildAnnotatedString {
                    append("showing ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.textPrimary)) {
                        append(shown.size.toString())
                    }
                    append(" of ${departments.size}")
                },
                style = APPROVAL_MONO,
                color = colors.textMuted,
            )
        }
        when {
            departments.isEmpty() -> NoDepartmentsLine(str(S.desktop_hub_no_departments_on_this_production))
            shown.isEmpty() -> NoDepartmentsLine(str(S.desktop_hub_no_departments_match_your_search))
            else -> shown.forEach { dept -> DepartmentRow(dept, state, onEvent) }
        }
    }
}

@Composable
private fun NoDepartmentsLine(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        FieldHint(text)
    }
}

/** A collapsible department — status, level counts and a stack of faces; opened, its levels. */
@Suppress("LongMethod", "CyclomaticComplexMethod") // A row, read left to right; the order is the reading order.
@Composable
private fun DepartmentRow(dept: HubDepartment, state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val approvals = state.approvals
    val own = approvals.configFor(dept.id)?.takeIf { it.isConfigured }
    val inherited = approvals.defaultConfig?.takeIf { it.isConfigured }
    val effective = own ?: inherited
    val expanded = dept.id in approvals.expanded
    val (status, tone) = when {
        own != null -> str(S.custom) to StatusTone.Pending
        inherited != null -> str(S.desktop_email_format_default) to StatusTone.Progress
        else -> str(S.desktop_not_configured) to StatusTone.Neutral
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(
                1.dp,
                if (expanded) colors.accent.copy(alpha = APPROVAL_RING_ALPHA) else colors.border,
                ZillitTheme.shapes.large,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEvent(AccountHubEvent.ToggleDepartmentExpanded(dept.id)) }
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitIcon(
                icon = if (expanded) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                contentDescription = if (expanded) str(S.desktop_collapse) else str(S.desktop_expand),
                tint = if (expanded) colors.accent else colors.textMuted,
                size = CHEVRON,
            )
            ZillitText(
                text = dept.name.localised(),
                style = ZillitTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Pill(status, tone = tone, dot = own != null)
            effective?.let { cfg ->
                ZillitText(
                    text = levelSummary(cfg.tiers.map { it.order to it.approverCount }),
                    style = APPROVAL_MONO,
                    color = colors.textMuted,
                    maxLines = 1,
                )
                // The web's stack: three overlapping faces, then "+N".
                val people = cfg.tiers.flatMap { it.userIds }.distinct()
                if (people.isNotEmpty()) FaceStack(people, state)
            }
            if (expanded && state.viewer.canActAsAccountant) {
                ZillitButton(
                    text = str(S.edit),
                    onClick = { onEvent(AccountHubEvent.EditDepartmentConfig(dept.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                )
            }
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceSunken.copy(alpha = SUNKEN_ALPHA))
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                if (effective == null) {
                    ZillitText(
                        text = str(S.desktop_hub_no_approval_levels_configured_for_this_department),
                        style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = colors.textMuted,
                    )
                } else {
                    LevelLines(effective.tiers, state, ::ruleSummary)
                    if (own == null) FieldHint(str(S.desktop_hub_inherited_from_the_default_levels))
                }
            }
        }
    }
}

/** Each module's icon, matching the hub sidebar's. */
internal val ApprovalModule.icon: ImageVector
    get() = when (this) {
        ApprovalModule.PurchaseOrders -> ZillitIcons.Receipt
        ApprovalModule.Invoices -> ZillitIcons.File
        ApprovalModule.CardExpenses -> ZillitIcons.CreditCard
        ApprovalModule.CashExpenses -> ZillitIcons.Wallet
        ApprovalModule.Timecard -> ZillitIcons.Clock
        ApprovalModule.DealMemo -> ZillitIcons.Edit
    }

/** The rail's sections, in the web's sidebar order. */
private val MODULE_GROUPS = listOf(
    str(S.desktop_transactions) to listOf(
        ApprovalModule.PurchaseOrders,
        ApprovalModule.Invoices,
        ApprovalModule.CardExpenses,
        ApprovalModule.CashExpenses,
    ),
    str(S.desktop_payroll_management) to listOf(ApprovalModule.Timecard, ApprovalModule.DealMemo),
)

/** Monospace counts and asides — "all 12 departments", "L1: 2 · L2: 1". */
internal val APPROVAL_MONO: TextStyle
    @Composable get() = ZillitTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)

internal const val APPROVAL_RING_ALPHA = 0.35f
private const val ICON_SHARE = 0.46f
private const val GRADIENT_STOP = 0.6f
private const val SUNKEN_ALPHA = 0.5f
private const val DASH = 6f
private const val GAP = 5f
private const val STACK_MAX = 3
private const val INITIALS_SHARE = 0.4f
private val STACK_FACE = 24.dp
private val STACK_OVERLAP = 8.dp
private val STACK_RING = 2.dp
private val STACK_COUNT_TEXT = 9.sp
private val RAIL_WIDTH = 248.dp
private val MODULE_TILE = 32.dp
private val HERO_TILE = 40.dp

/** Narrower than this and the title and the three counters cannot share a line. */
private val HERO_SIDE_BY_SIDE = 720.dp
private val CARD_TILE = 34.dp
private val STAT_MIN_WIDTH = 96.dp
private val STAT_ICON = 14.dp
private val STATUS_DOT = 7.dp
private val LINE_AVATAR = 26.dp
private val CHEVRON = 16.dp
