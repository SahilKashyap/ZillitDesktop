@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber")

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.data.MAX_HEADSHOTS
import com.zillit.desktop.feature.selectstills.domain.Recognition
import com.zillit.desktop.feature.selectstills.domain.limitsFromInputs

/**
 * The web's enrolment card: a headshot (or a few angles), the actor's name,
 * who their agent is and how many photos that agent may discard — the moment
 * production decides who answers for this person is the moment it knows what
 * their contract allows.
 *
 * What the tool asks on top of the web's demo, in the same fields: the
 * character, whether anybody has to approve at all (it must be chosen, and
 * says what it means), how the person is recognised, and that they agreed to
 * it.
 *
 * Headshots are checked by the service after the member exists. One it turns
 * away comes back in the member's own card, for the reader to put right
 * ("add anyway", or another picture).
 */
@Composable
internal fun EnrollCard(
    state: StillsUiState,
    form: EnrollState,
    onEvent: (StillsEvent) -> Unit,
    modifier: Modifier = Modifier,
    inCard: Boolean = true,
) {
    val body: @Composable ColumnScopeAlias.() -> Unit = {
        EnrollBody(state, form, onEvent)
    }
    if (inCard) SCard(modifier.fillMaxWidth(), body) else Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), content = body)
}

@Composable
private fun ColumnScopeAlias.EnrollBody(state: StillsUiState, form: EnrollState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val limits = limitsFromInputs(form.limits)
    val valid = form.name.isNotBlank() && limits != null && form.consent

    if (form.picks.size < MAX_HEADSHOTS) {
        SDropZone(
            label = if (form.picks.isEmpty()) str(S.desktop_stk_headshot_drop) else str(S.desktop_stk_headshot_add_another),
            hint = str(S.desktop_stk_headshot_drop_hint),
            onClick = { onEvent(StillsEvent.PickEnrollHeadshots) },
            enabled = !form.busy,
            onPaths = { paths -> onEvent(StillsEvent.DropEnrollPaths(paths)) },
        )
    }

    if (form.picks.isNotEmpty()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            form.picks.forEachIndexed { index, pick ->
                HeadshotThumb(
                    name = pick.name,
                    url = "",
                    armed = false,
                    enabled = !form.busy,
                    onRemove = { onEvent(StillsEvent.DropEnrollPick(index)) },
                )
            }
        }
    }

    SField(str(S.desktop_stk_member_name), Modifier.padding(top = 16.dp), required = true) {
        SInput(
            value = form.name,
            onChange = { onEvent(StillsEvent.EnrollName(it)) },
            placeholder = str(S.desktop_stk_member_name_placeholder),
            maxLength = 120,
            enabled = !form.busy,
        )
    }
    SField(str(S.desktop_stk_member_character)) {
        SInput(form.character, { onEvent(StillsEvent.EnrollCharacter(it)) }, maxLength = 120, enabled = !form.busy)
    }

    ApprovalChoice(form.needsApproval, !form.busy) { onEvent(StillsEvent.EnrollApproval(it)) }

    // The policy is part of naming the agent, not a separate admin screen.
    if (form.needsApproval) {
        SField(
            label = str(S.desktop_stk_agent),
            hint = str(S.desktop_stk_agent_hint),
            warn = if (form.agent.isBlank()) str(S.desktop_stk_agent_missing_warn) else null,
        ) {
            AgentSelect(state, form.agent, !form.busy, onEvent) { onEvent(StillsEvent.EnrollAgent(it)) }
        }
        SField(
            label = str(S.desktop_stk_limits),
            warn = if (limits == null) str(S.desktop_stk_limits_invalid) else null,
        ) {
            SDiscardLimits(form.limits, { onEvent(StillsEvent.EnrollLimits(it)) }, enabled = !form.busy)
        }
    } else {
        SAlert(SAlertTone.Error) { SText(str(S.desktop_stk_approval_not_needed_warn), 15, color = k.errorFg) }
    }

    RecognitionField(form.recognition, !form.busy) { onEvent(StillsEvent.EnrollRecognition(it)) }

    SCheck(
        checked = form.consent,
        label = str(S.desktop_stk_consent),
        onPick = { onEvent(StillsEvent.EnrollConsent(!form.consent)) },
        enabled = !form.busy,
        radio = false,
    )

    SBtn(
        text = if (form.busy) str(S.desktop_stk_enrolling) else str(S.desktop_stk_nav_cast),
        onClick = { onEvent(StillsEvent.Enrol) },
        modifier = Modifier.padding(top = 6.dp),
        kind = SBtnKind.Primary,
        enabled = !form.busy && valid && state.me.settings.attested,
    )
}

/** Who decides, as two choices: nobody is left unapproved by an empty field. */
@Composable
internal fun ApprovalChoice(needed: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val k = StillsTheme.c
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SText(str(S.desktop_stk_approval_question), 13, color = k.muted)
        SCheck(needed, str(S.desktop_stk_approval_needed), { onChange(true) }, hint = str(S.desktop_stk_approval_needed_hint), enabled = enabled)
        SCheck(!needed, str(S.desktop_stk_approval_not_needed), { onChange(false) }, hint = str(S.desktop_stk_approval_not_needed_hint), enabled = enabled)
    }
}

/** How a member is recognised: by itself, only as a proposal, or not at all. */
@Composable
internal fun RecognitionField(mode: Recognition, enabled: Boolean, onChange: (Recognition) -> Unit) {
    SField(str(S.desktop_stk_recognition), hint = str(recognitionHint(mode))) {
        SSelect(
            options = Recognition.entries.map { SOption(it.wire, str(recognitionLabel(it))) },
            value = mode.wire,
            onChange = { wire -> onChange(Recognition.of(wire)) },
            enabled = enabled,
        )
    }
}

/**
 * Pick a member's agent from the production's people — filled from Zillit.
 *
 * Only somebody who can open the tool can be one; they could never decide
 * anything otherwise. So the list is the production's users narrowed to the
 * service's own answer, asked for the first time a picker opens. The person
 * already chosen stays listed even if they have since lost their rights,
 * marked so.
 */
@Composable
internal fun AgentSelect(
    state: StillsUiState,
    value: String,
    enabled: Boolean,
    onEvent: (StillsEvent) -> Unit,
    onChange: (String) -> Unit,
) {
    val eligible = state.crew.filter { state.agentIds == null || it.id in state.agentIds }
    val options = buildList {
        if (value.isNotBlank() && eligible.none { it.id == value }) {
            add(
                SOption(
                    value = value,
                    label = state.crewById[value]?.fullName ?: str(S.desktop_stk_unknown_person),
                    sub = str(S.desktop_stk_agent_no_longer_eligible),
                ),
            )
        }
        addAll(eligible.map { SOption(it.id, it.fullName, it.subtitle) })
    }
    SSelect(
        options = options,
        value = value,
        onChange = onChange,
        emptyLabel = str(S.desktop_stk_agent_none),
        searchLabel = str(S.desktop_stk_search_agents, options.size),
        enabled = enabled,
        onOpen = { onEvent(StillsEvent.LoadAgentCandidates) },
    )
}

/** `.stk-thumb` — one headshot with the ✕ that removes it. */
@Composable
internal fun HeadshotThumb(
    name: String,
    url: String,
    armed: Boolean,
    enabled: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 92.dp,
) {
    val k = StillsTheme.c
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.size(size + 6.dp)) {
        Box(Modifier.size(size).align(Alignment.BottomStart).clip(shape).background(k.panel2).border(BorderStroke(1.dp, k.line), shape)) {
            if (url.isNotBlank()) {
                SImage(url, Modifier.fillMaxSize().clip(shape))
            } else {
                // A file picked but not yet sent: its name holds the place.
                Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    SText(name.substringAfterLast('.', "").uppercase().ifBlank { "IMG" }, 12, Bold, k.muted, maxLines = 1)
                    SText(name, 10, color = k.muted, maxLines = 1)
                }
            }
        }
        if (enabled) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = 0.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (armed) k.danger else Color.Black)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                ZillitTooltip(text = str(if (armed) S.desktop_stk_headshot_remove_again else S.desktop_stk_headshot_remove)) {
                    SText(if (armed) "✓" else "×", 12, Bold, Color.White)
                }
            }
        }
    }
}
