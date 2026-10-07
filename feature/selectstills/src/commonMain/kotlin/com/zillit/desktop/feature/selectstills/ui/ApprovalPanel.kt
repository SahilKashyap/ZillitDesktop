@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber", "CyclomaticComplexMethod", "LongParameterList")

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.domain.ApprovalRow
import com.zillit.desktop.feature.selectstills.domain.Decision
import com.zillit.desktop.feature.selectstills.domain.Photo
import com.zillit.desktop.feature.selectstills.domain.PublicState
import com.zillit.desktop.feature.selectstills.domain.allowanceWords
import com.zillit.desktop.feature.selectstills.domain.decisionKey
import com.zillit.desktop.feature.selectstills.domain.discardBlocked
import com.zillit.desktop.feature.selectstills.domain.discardIsFree
import com.zillit.desktop.feature.selectstills.domain.stateMeta

/**
 * `.stk-gate-panel` — the publication gate for one photo: one row per member
 * who needs an agent's say.
 *
 * A decision is recorded per PERSON, because in a group still each agent
 * answers only for their own actor — and one refusal is enough to keep the
 * photo down. Everybody who can see the photo sees where each row stands.
 *
 * The one difference from the web's demo, where anybody could press any row:
 * only the member's current agent gets the buttons (`can_decide`, from the
 * service). Production sees who decided and why, but cannot decide for them.
 *
 * Two looks, as there are two lightboxes:
 *   the gallery's  a pill and a line about the photo, then a row per member
 *                  with ✓ Approve · ✕ Reject · Undo on the reader's own
 *   the queue's    (`run`) the reader's actor first, then what the other
 *                  agents said, then the allowance; the buttons are in the
 *                  lightbox's footer — unless the reader has two actors in
 *                  the photo, when each row carries its own
 */
@Composable
internal fun ApprovalPanel(state: StillsUiState, photo: Photo, box: LightboxState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val rows = photo.approvals
    if (rows.isEmpty()) return
    val mine = rows.filter { it.canDecide }
    val others = rows.filterNot { it.canDecide }

    val label = str(S.cs_approvals)
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .background(Color.White.copy(alpha = 0.02f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (box.run) {
            ReviewRows(state, photo, box, mine, others, onEvent)
        } else {
            GalleryRows(state, photo, box, rows, mine, onEvent)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
}

/** The queue's panel: the reader's actor, the other agents, the allowance. */
@Composable
private fun ReviewRows(
    state: StillsUiState,
    photo: Photo,
    box: LightboxState,
    mine: List<ApprovalRow>,
    others: List<ApprovalRow>,
    onEvent: (StillsEvent) -> Unit,
) {
    val k = StillsTheme.c
    val refused = others.any { it.state == Decision.Rejected }

    if (mine.isEmpty()) SText(str(S.desktop_stk_no_actor_here), 13, color = k.muted)

    mine.forEach { row ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SText(row.name.ifBlank { str(S.desktop_stk_unknown_person) }, 15, Bold)
                SPill(str(S.desktop_stk_your_actor), SPillKind.Yours)
                if (row.state != Decision.Pending) SText(str(decisionKey(row.state)), 12, color = toneOf(row.state))
            }
            if (row.note.isNotBlank() && row.state == Decision.Rejected) {
                SText(str(S.desktop_stk_your_reason, row.note), 13, color = k.warn, italic = true)
            }
            // Two of the reader's actors in one photo: each row carries its own
            // buttons, because one pair cannot answer for two people.
            if (mine.size > 1 && photo.status.isDone) {
                val allowance = state.clients.firstOrNull { it.memberId == row.memberId }?.allowance
                SDecisionBar(
                    state = row.state,
                    where = SDecideWhere.Card,
                    blocked = discardBlocked(allowance, photo.section, row.state, discardIsFree(photo.approvals, row.memberId)),
                    busy = box.busy,
                    asking = false,
                    note = "",
                    onAsk = { onEvent(StillsEvent.DecideInPhoto(row.memberId, Decision.Rejected, "")) },
                    onCancel = {},
                    onNote = {},
                    onDecide = { next, note -> onEvent(StillsEvent.DecideInPhoto(row.memberId, next, note)) },
                )
            }
        }
    }

    if (others.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SText(str(S.desktop_stk_other_agents).uppercase(), 11, Bold, k.muted)
            others.forEach { row ->
                val agent = row.agentUserId?.let { state.crewById[it]?.fullName }
                SDecisionChip(
                    name = row.name.ifBlank { str(S.desktop_stk_unknown_person) } + if (agent != null) " · $agent" else "",
                    state = row.state,
                    note = row.note,
                )
            }
        }
    }

    if (photo.status.isDone) {
        mine.forEach { row ->
            val free = discardIsFree(photo.approvals, row.memberId)
            val allowance = state.clients.firstOrNull { it.memberId == row.memberId }?.allowance
            val section = photo.section?.let { allowance?.get(it) }
            when {
                section?.limit != null && !free ->
                    AllowanceLine(
                        prefix = if (mine.size > 1) "${row.name} · " else "",
                        section = photo.section.orEmpty(),
                        words = allowanceWords(section),
                        long = true,
                    )
                free && row.state != Decision.Rejected -> SText(str(S.desktop_stk_discard_free), 13, color = k.muted)
            }
        }
    }

    if (refused && mine.any { it.state != Decision.Rejected }) {
        SText(str(S.desktop_stk_discarded_by_other), 13, color = k.accentInk)
    }
}

/** The gallery's panel: a pill, a line about the photo, then a row per member. */
@Composable
private fun GalleryRows(
    state: StillsUiState,
    photo: Photo,
    box: LightboxState,
    rows: List<ApprovalRow>,
    mine: List<ApprovalRow>,
    onEvent: (StillsEvent) -> Unit,
) {
    val k = StillsTheme.c
    val meta = stateMeta(photo.publicState)
    val waiting = rows.count { it.state == Decision.Pending }
    val summary = when {
        photo.publicState == PublicState.Blocked -> str(S.desktop_stk_gate_blocked)
        photo.publicState == PublicState.Approved -> str(S.desktop_stk_gate_approved)
        waiting > 0 || photo.unnamed == 0 -> str(S.desktop_stk_gate_waiting, waiting)
        else -> str(S.desktop_stk_gate_unnamed)
    }

    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SStatePill(meta.tone, str(meta.key), null)
        SText(summary, 13, color = k.muted)
    }
    if (photo.unnamed > 0 && photo.publicState != PublicState.Blocked) {
        SText(str(S.desktop_stk_unidentified_gate), 13, color = k.accentInk)
    }

    rows.forEach { row ->
        val decides = row.canDecide && photo.status.isDone
        val free = discardIsFree(photo.approvals, row.memberId)
        val allowance = state.clients.firstOrNull { it.memberId == row.memberId }?.allowance
        val section = photo.section?.let { allowance?.get(it) }
        val agent = row.agentUserId?.let { state.crewById[it]?.fullName }

        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SText(row.name.ifBlank { str(S.desktop_stk_unknown_person) }, 15, Bold)
                if (row.canDecide) SPill(str(S.desktop_stk_your_actor), SPillKind.Yours)
                if (!row.hasAgent) SText(str(S.desktop_stk_no_agent_yet), 13, color = k.muted, italic = true)
                if (row.hasAgent && agent != null) SText(agent, 13, color = k.muted)
                // Whoever cannot press the chips still sees where the row stands.
                if (!decides) SText(str(decisionKey(row.state)), 12, color = toneOf(row.state))
                if (row.note.isNotBlank()) SText(str(S.desktop_stk_reason_quote, row.note), 13, color = k.warn, italic = true)
            }
            if (decides) {
                if (free && row.state != Decision.Rejected) SText(str(S.desktop_stk_discard_free), 13, color = k.muted)
                if (section?.limit != null && !free) {
                    AllowanceLine("", photo.section.orEmpty(), allowanceWords(section))
                }
                SDecisionBar(
                    state = row.state,
                    where = SDecideWhere.Gate,
                    blocked = discardBlocked(allowance, photo.section, row.state, free),
                    busy = box.busy,
                    // The D key acts only when the reader has exactly one row.
                    asking = box.asking && mine.size == 1,
                    note = box.note,
                    onAsk = { onEvent(StillsEvent.AskWhy) },
                    onCancel = { onEvent(StillsEvent.CancelWhy) },
                    onNote = { onEvent(StillsEvent.WhyTyped(it)) },
                    onDecide = { next, note -> onEvent(StillsEvent.DecideInPhoto(row.memberId, next, note)) },
                )
            }
        }
    }
}

@Composable
private fun toneOf(state: Decision): Color {
    val k = StillsTheme.c
    return when (state) {
        Decision.Rejected -> k.errorFg
        Decision.Approved -> Color(0xFFA6E3C2)
        Decision.Pending -> k.muted
    }
}
