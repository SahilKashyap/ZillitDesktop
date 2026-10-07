package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.HubUser
import com.zillit.desktop.feature.accounthub.domain.HybridDefault
import com.zillit.desktop.feature.accounthub.domain.TimecardApprovalSummary
import com.zillit.desktop.feature.accounthub.domain.TimecardControlModel
import com.zillit.desktop.feature.accounthub.domain.TimecardDepartmentSummary
import com.zillit.desktop.feature.accounthub.domain.TimecardSetup
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.SubCard

/**
 * Time Card Entry Setup — the web's `TimecardSetupDetail`: the control model
 * that decides who completes time cards, plus read-only summaries of the
 * department setup and the approval chain, which are configured elsewhere.
 */
@Composable
internal fun ColumnScope.TimecardModalBody(
    sectionId: String,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val value = state.setup.timecardSetup.edited
    when (sectionId) {
        SECTION_CONTROL ->
            ControlModelPane(value, state.viewer.canEdit) { onEvent(AccountHubEvent.EditTimecardSetup(it)) }
        SECTION_DEPARTMENTS -> DepartmentSummaryPane(value.departments, state.users)
        else -> ApprovalSummaryPane(value.approvals, state.setup.departments.size, state.users)
    }
}

internal const val SECTION_CONTROL = "control"
internal const val SECTION_DEPARTMENTS = "departments"
internal const val SECTION_APPROVALS = "approvals"

// -- control model ----------------------------------------------------------------

private class ModelCopy(val title: String, val body: String, val bestFor: String, val tone: Color)

@Composable
private fun modelCopy(model: TimecardControlModel): ModelCopy {
    val colors = ZillitTheme.colors
    return when (model) {
        TimecardControlModel.Production -> ModelCopy(
            str(S.desktop_hub_tc_a_title), str(S.desktop_hub_tc_a_body), str(S.desktop_hub_tc_a_best), colors.warning,
        )
        TimecardControlModel.Department -> ModelCopy(
            str(S.desktop_hub_tc_b_title), str(S.desktop_hub_tc_b_body), str(S.desktop_hub_tc_b_best), colors.success,
        )
        TimecardControlModel.Crew -> ModelCopy(
            str(S.desktop_hub_tc_c_title), str(S.desktop_hub_tc_c_body), str(S.desktop_hub_tc_c_best), colors.info,
        )
        TimecardControlModel.Hybrid -> ModelCopy(
            str(S.desktop_hub_tc_d_title), str(S.desktop_hub_tc_d_body), str(S.desktop_hub_tc_d_best), colors.accent,
        )
    }
}

@Composable
private fun ColumnScope.ControlModelPane(value: TimecardSetup, editable: Boolean, update: (TimecardSetup) -> Unit) {
    FieldHint(str(S.desktop_hub_tc_intro))
    TimecardControlModel.entries.chunked(2).forEach { pair ->
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            pair.forEach { model ->
                ModelCard(
                    model = model,
                    active = value.model == model,
                    onSelect = { if (editable) update(value.copy(model = model)) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
    if (value.model == TimecardControlModel.Hybrid) {
        SubCard {
            FieldHint(str(S.desktop_hub_tc_hybrid_note))
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    ZillitText(
                        text = str(S.desktop_hub_tc_hybrid_default),
                        style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    FieldHint(str(S.desktop_hub_tc_hybrid_default_hint))
                }
                HubSelect(
                    value = value.hybridDefault,
                    options = HybridDefault.entries,
                    label = { hybridLabel(it) },
                    onSelect = { choice -> if (choice != null) update(value.copy(hybridDefault = choice)) },
                    modifier = Modifier.weight(2f),
                    enabled = editable,
                    searchable = false,
                    showInitials = false,
                )
            }
        }
    }
}

private fun hybridLabel(choice: HybridDefault): String = when (choice) {
    HybridDefault.Production -> str(S.desktop_hub_tc_opt_a)
    HybridDefault.Department -> str(S.desktop_hub_tc_opt_b)
    HybridDefault.Crew -> str(S.desktop_hub_tc_opt_c)
}

/** One of the four models: a tinted letter, the title, what it means, and who it suits. */
@Composable
private fun ModelCard(
    model: TimecardControlModel,
    active: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val copy = modelCopy(model)
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(if (active) colors.accentSoft else colors.surface)
            .border(if (active) 2.dp else 1.dp, if (active) colors.accent else colors.border, ZillitTheme.shapes.large)
            .clickable(onClick = onSelect)
            .padding(ZillitTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(LETTER_BLOCK)
                .clip(ZillitTheme.shapes.medium)
                .background(copy.tone.copy(alpha = TONE_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = model.letter,
                style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = copy.tone,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = copy.title,
                    style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (model == TimecardControlModel.Hybrid) {
                    Pill(str(S.desktop_recommended), tone = StatusTone.Done)
                }
            }
            ZillitText(text = copy.body, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
            FieldHint("${str(S.desktop_hub_tc_best_for)}: ${copy.bestFor}")
        }
    }
}

// -- summaries --------------------------------------------------------------------

@Composable
private fun ColumnScope.DepartmentSummaryPane(summary: TimecardDepartmentSummary, users: List<HubUser>) {
    if (!summary.isConfigured) {
        EmptySummary(str(S.desktop_hub_tc_dept_empty_title), str(S.desktop_hub_tc_dept_empty_body))
        return
    }
    val colors = ZillitTheme.colors
    SubCard(padded = false) {
        StatRow(
            listOf(
                summary.configured to str(S.desktop_hub_tc_dept_configured),
                summary.completers to str(S.desktop_hub_tc_completers),
            ),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(str(S.desktop_hub_tc_split), modifier = Modifier.weight(1f))
                FieldHint(str(S.desktop_hub_tc_split_counts, summary.controlled, summary.crewControlled))
            }
            SplitBar(
                listOf(
                    summary.departmentControlled to colors.success,
                    summary.crewControlled to colors.info,
                    summary.productionControlled to colors.warning,
                ),
                total = summary.configured,
            )
            Legend(
                listOf(
                    Triple(str(S.desktop_hub_tc_dept_controlled), summary.departmentControlled, colors.success),
                    Triple(str(S.desktop_hub_tc_crew_controlled), summary.crewControlled, colors.info),
                    Triple(str(S.desktop_hub_tc_prod_controlled), summary.productionControlled, colors.warning),
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Initials(summary.completerIds, summary.completers, users)
            FieldHint(
                if (summary.allControlledHaveCompleter) {
                    str(S.desktop_hub_tc_all_have_completer)
                } else {
                    str(S.desktop_hub_tc_some_have_completer, summary.completers, summary.controlled)
                },
            )
        }
    }
}

@Composable
private fun ColumnScope.ApprovalSummaryPane(
    summary: TimecardApprovalSummary,
    totalDepartments: Int,
    users: List<HubUser>,
) {
    if (!summary.isConfigured) {
        EmptySummary(str(S.desktop_hub_tc_appr_empty_title), str(S.desktop_hub_tc_appr_empty_body))
        return
    }
    val colors = ZillitTheme.colors
    val onDefault = summary.onDefault(totalDepartments)
    SubCard(padded = false) {
        StatRow(
            listOf(
                totalDepartments to str(S.departments),
                summary.defaultLevels to str(
                    if (summary.defaultLevels == 1) S.desktop_hub_tc_level_one else S.desktop_hub_tc_level_many,
                ),
                summary.approverCount to str(S.desktop_hub_tc_approvers_rotation),
            ),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(str(S.desktop_hub_tc_appr_setup), modifier = Modifier.weight(1f))
                FieldHint(str(S.desktop_hub_tc_default_custom, onDefault, summary.customOverrides))
            }
            SplitBar(
                listOf(onDefault to colors.info, summary.customOverrides to colors.warning),
                total = totalDepartments,
            )
            Legend(
                listOf(
                    Triple(str(S.desktop_hub_tc_on_default), onDefault, colors.info),
                    Triple(str(S.desktop_hub_tc_custom_override), summary.customOverrides, colors.warning),
                ),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg)) {
            Initials(summary.approverIds, summary.approverCount, users)
        }
    }
}

@Composable
private fun EmptySummary(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(text = title, style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        FieldHint(body)
    }
}

/** The big-number cells across the top of a summary card. */
@Composable
private fun StatRow(stats: List<Pair<Int, String>>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        stats.forEachIndexed { index, (number, label) ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(ZillitTheme.colors.border))
            Column(modifier = Modifier.weight(1f).padding(ZillitTheme.spacing.lg)) {
                ZillitText(
                    text = number.toString(),
                    style = ZillitTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                )
                FieldHint(label)
            }
        }
    }
}

/** A proportional bar: each part's share of [total], drawn in its colour. */
@Composable
private fun SplitBar(parts: List<Pair<Int, Color>>, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clip(CircleShape)
            .background(ZillitTheme.colors.surfaceSunken),
    ) {
        parts.filter { it.first > 0 }.forEach { (count, color) ->
            Box(Modifier.weight(count.toFloat() / total.coerceAtLeast(1)).fillMaxHeight().background(color))
        }
        val used = parts.sumOf { it.first }
        if (total > used) Box(Modifier.weight((total - used).toFloat() / total.coerceAtLeast(1)))
    }
}

@Composable
private fun Legend(entries: List<Triple<String, Int, Color>>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { (label, count, color) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(DOT).clip(CircleShape).background(color))
                ZillitText(
                    text = count.toString(),
                    style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                )
                FieldHint(label)
            }
        }
    }
}

/** Up to five initials from the roster, then "+N" for the rest. */
@Composable
private fun Initials(ids: List<String>, total: Int, users: List<HubUser>) {
    if (ids.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(-AVATAR_OVERLAP), verticalAlignment = Alignment.CenterVertically) {
        ids.take(MAX_AVATARS).forEach { id -> InitialsDot(initialsOf(users.firstOrNull { it.id == id }?.name ?: id)) }
        if (total > MAX_AVATARS) InitialsDot("+${total - MAX_AVATARS}")
    }
}

@Composable
private fun InitialsDot(text: String) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .size(AVATAR)
            .clip(CircleShape)
            .background(colors.surfaceSunken)
            .border(2.dp, colors.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(text = text, style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
    }
}

/** First and last initial of a name — "Sam Lee" → "SL" — the web's `avatarInitialsFrom`. */
internal fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val first = parts.firstOrNull()?.first()?.toString().orEmpty()
    val last = if (parts.size > 1) parts.last().first().toString() else ""
    return (first + last).uppercase().ifEmpty { "?" }
}

private val LETTER_BLOCK = 36.dp
private const val TONE_ALPHA = 0.16f
private val BAR_HEIGHT = 8.dp
private val DOT = 8.dp
private val AVATAR = 28.dp
private val AVATAR_OVERLAP = 8.dp
private const val MAX_AVATARS = 5
