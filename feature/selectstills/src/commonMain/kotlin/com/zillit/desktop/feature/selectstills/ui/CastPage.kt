@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.domain.Member
import com.zillit.desktop.feature.selectstills.domain.Recognition
import com.zillit.desktop.feature.selectstills.domain.limitsSummary

/**
 * The web's Enroll page: on the left the card that enrols an actor —
 * headshots, name, agent, allowance — and on the right everybody already
 * enrolled; a click on one opens their card.
 *
 * Production enrols and edits; everybody else who can open the tool reads the
 * list. Somebody who needs approval but has no agent yet holds every photo
 * they are in, so that is marked; and "no approval needed" is marked loudly,
 * because photos of that person go public without anybody being asked.
 */
@Composable
internal fun CastPage(state: StillsUiState, onEvent: (StillsEvent) -> Unit) {
    val canPost = state.canPost
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 1280.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SPageHead(
                title = if (canPost) str(S.desktop_stk_enroll_title) else str(S.desktop_stk_enrolled_title),
                lede = if (canPost) str(S.desktop_stk_enroll_lede) else str(S.desktop_stk_enrolled_lede),
            )

            when {
                !state.membersLoaded -> SLoading()
                canPost && !state.me.usable -> SStatePanel(
                    title = str(S.desktop_stk_unusable_title),
                    hint = if (state.me.storageSupported) str(S.desktop_stk_unusable_region) else str(S.desktop_stk_unusable_storage),
                    bad = true,
                )
                canPost && !state.me.settings.attested -> FaceDataNotice(state.cast.accepting, onEvent)
                else -> SNarrow(NARROW) { narrow ->
                    if (narrow || !canPost) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (canPost) state.enroll?.takeIf { !it.inDialog }?.let { EnrollCard(state, it, onEvent) }
                            MemberList(state, onEvent)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Box(Modifier.weight(1f)) {
                                state.enroll?.takeIf { !it.inDialog }?.let { EnrollCard(state, it, onEvent) }
                            }
                            Box(Modifier.width(SIDE)) { MemberList(state, onEvent) }
                        }
                    }
                }
            }
        }
    }
}

/** `.stk-member-list` — the people this production recognises in photos. */
@Composable
private fun MemberList(state: StillsUiState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val canPost = state.canPost
    val query = state.cast.query
    val shown = remember(state.members, query) {
        val needle = query.trim().lowercase()
        state.members
            .filter { needle.isEmpty() || "${it.name} ${it.characterName}".lowercase().contains(needle) }
            .sortedBy { it.name.lowercase() }
    }

    SCard(Modifier.fillMaxWidth()) {
        SText(str(S.desktop_stk_enrolled_count, state.members.size), 19, Bold)
        if (state.members.isEmpty()) {
            SText(str(S.desktop_stk_enrolled_none), 13, color = k.muted, modifier = Modifier.padding(vertical = 13.dp))
        }
        if (state.members.isNotEmpty() && canPost) {
            SRich(str(S.desktop_stk_enrolled_tip), 13, k.muted, k.accentInk)
        }
        if (state.members.size > SEARCHABLE_AT) {
            SSearch(
                value = query,
                onChange = { onEvent(StillsEvent.CastSearch(it)) },
                placeholder = str(S.desktop_stk_search_members, state.members.size),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            shown.forEach { member -> MemberRow(state, member, onEvent) }
            if (state.members.isNotEmpty() && shown.isEmpty()) {
                SText(str(S.desktop_stk_no_one_matches, query), 13, color = k.muted)
            }
        }
    }
}

/** One row: their headshot (or initials), their name, and the line under it. */
@Composable
private fun MemberRow(state: StillsUiState, member: Member, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val canPost = state.canPost
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    // Their own agent and allowance are the reader's business only if they are
    // production, or this is their own client.
    val mine = canPost || member.isMyClient
    val agent = member.agentUserId?.let { state.crewById[it]?.fullName ?: str(S.desktop_stk_unknown_person) }
    val limits = if (mine) limitsSummary(member.discardLimits) { key -> str(key) } else null
    val noAgent = mine && member.approvalRequired && agent == null

    // The line under their name: character · headshots · agent · where they appear.
    val parts = listOfNotNull(
        member.characterName.takeIf { it.isNotBlank() },
        if (member.headshots.size == 1) str(S.av_option_photo_count_one) else str(S.desktop_stk_photos_n, member.headshots.size),
        if (member.approvalRequired && mine) agent else null,
        if (canPost && member.photoCount > 0) str(S.desktop_stk_tagged_in, member.photoCount) else null,
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(if (canPost && hovered) k.overlay.copy(alpha = ROW_HOVER) else Color.Transparent)
            .then(
                if (canPost) {
                    Modifier.hoverable(source).clickable(interactionSource = source, indication = null) { onEvent(StillsEvent.OpenMember(member.id)) }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).background(k.line).size(width = 10000.dp, height = 1.dp))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (member.headshotUrl.isNotBlank()) {
                SImage(member.headshotUrl, Modifier.size(44.dp).clip(CircleShape), ContentScale.Crop)
            } else {
                Box(Modifier.size(44.dp).clip(CircleShape).background(k.panel2), contentAlignment = Alignment.Center) {
                    SText(initialsOf(member.name), 14, Bold, k.muted)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SText(member.name, 15, androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                Row {
                    SText(parts.joinToString(" · "), 13, color = k.muted, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                    if (noAgent) {
                        SText(
                            text = (if (parts.isEmpty()) "" else " · ") + str(S.desktop_stk_no_agent_yet),
                            size = 13,
                            color = k.muted,
                            italic = true,
                        )
                    }
                }
                limits?.let { SText("${str(S.desktop_stk_limits)}: $it", 13, color = k.muted) }

                val marks = mine && !member.approvalRequired || member.isMyClient ||
                    canPost && member.recognition != Recognition.Auto
                if (marks) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (mine && !member.approvalRequired) {
                            SPill(str(S.desktop_stk_no_approval_pill), SPillKind.GateNo, hint = str(S.desktop_stk_approval_not_needed_warn))
                        }
                        if (member.isMyClient) SPill(str(S.desktop_stk_your_actor), SPillKind.Yours)
                        if (canPost && member.recognition != Recognition.Auto) {
                            SPill(str(recognitionLabel(member.recognition)), SPillKind.Ghost, onPhoto = false)
                        }
                    }
                }
            }
            if (canPost) {
                ZillitTooltip(text = str(S.drive_cd_open_profile)) {
                SText(
                    text = str(S.desktop_stk_member_edit),
                    size = 11,
                    weight = Bold,
                    color = if (hovered) k.accent else k.muted,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                        .background(k.overlay.copy(alpha = CHIP_WASH))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
                }
            }
        }
    }
}

/** Their initials, when they have no headshot yet. */
internal fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> ""
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "${parts.first().first()}${parts.last().first()}".uppercase()
    }
}

internal fun recognitionLabel(mode: Recognition) = when (mode) {
    Recognition.Auto -> S.desktop_ce_automatic
    Recognition.SuggestOnly -> S.desktop_stk_recognition_suggest_only
    Recognition.Off -> S.desktop_off
}

internal fun recognitionHint(mode: Recognition) = when (mode) {
    Recognition.Auto -> S.desktop_stk_recognition_auto_hint
    Recognition.SuggestOnly -> S.desktop_stk_recognition_suggest_only_hint
    Recognition.Off -> S.desktop_stk_recognition_off_hint
}

private const val ROW_HOVER = 0.06f
private const val CHIP_WASH = 0.07f

private val NARROW = 760.dp
private val SIDE = 360.dp
