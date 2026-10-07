@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.data.HeadshotOutcome
import com.zillit.desktop.feature.selectstills.data.MAX_HEADSHOTS
import com.zillit.desktop.feature.selectstills.domain.limitsFromInputs
import com.zillit.desktop.feature.selectstills.domain.usedOf

/**
 * A member's profile — the web's member card: the name on top, to be typed
 * over; their agent and allowance; their headshots, each with a ✕; and a drop
 * zone for more angles.
 *
 * What the tool keeps on top of the web's demo, in the same card: the
 * character, whether anybody has to approve, how they are recognised, erasing
 * their face data and taking them off the list.
 *
 * One Save for the card, where the demo saved each field as it changed: a new
 * agent is told at once that photos are waiting on them, so that is not sent
 * for a slip of the hand in a list.
 */
@Composable
internal fun MemberDialog(state: StillsUiState, dialog: MemberDialogState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val member = dialog.member
    val ready = !dialog.loading && member != null
    val limits = limitsFromInputs(dialog.limits)
    val valid = dialog.name.isNotBlank() && limits != null
    val room = MAX_HEADSHOTS - (member?.headshots?.size ?: 0)

    SDialog(
        title = member?.name ?: str(S.desktop_stk_member_name_label),
        onClose = { onEvent(StillsEvent.CloseMember) },
        busy = dialog.busy,
        head = if (!ready) {
            null
        } else {
            {
                SInput(
                    value = dialog.name,
                    onChange = { onEvent(StillsEvent.MemberName(it)) },
                    placeholder = str(S.desktop_stk_member_name_label),
                    maxLength = 120,
                    enabled = !dialog.busy,
                    size = 19,
                    onEnter = { if (valid) onEvent(StillsEvent.SaveMember) },
                )
            }
        },
        footer = if (!ready) {
            null
        } else {
            {
                SBtn(str(S.cancel), { onEvent(StillsEvent.CloseMember) }, kind = SBtnKind.Ghost, small = true, enabled = !dialog.busy)
                SBtn(
                    text = if (dialog.busy) str(S.ah_saving) else str(S.save),
                    onClick = { onEvent(StillsEvent.SaveMember) },
                    kind = SBtnKind.Primary,
                    small = true,
                    enabled = !dialog.busy && valid,
                )
            }
        },
    ) {
        // Spelled out rather than `!ready`: this is what smart-casts [member].
        if (member == null || dialog.loading) {
            SLoading(bare = true)
            return@SDialog
        }

        val sub = listOfNotNull(
            if (member.headshots.size == 1) str(S.desktop_stk_profile_photos_one) else str(S.desktop_stk_profile_photos_n, member.headshots.size),
            member.createdMillis.takeIf { it > 0 }?.let { str(S.desktop_stk_enrolled_on, formatDay(it)) },
        ).joinToString(" · ")
        SText(sub, 13, color = k.muted)

        SField(str(S.desktop_stk_member_character)) {
            SInput(dialog.character, { onEvent(StillsEvent.MemberCharacter(it)) }, maxLength = 120, enabled = !dialog.busy)
        }

        ApprovalChoice(dialog.needsApproval, !dialog.busy) { onEvent(StillsEvent.MemberApproval(it)) }

        if (dialog.needsApproval) {
            SField(
                label = str(S.desktop_stk_agent),
                hint = str(S.desktop_stk_agent_change_hint),
                warn = if (dialog.agent.isBlank()) str(S.desktop_stk_agent_missing_warn) else null,
            ) {
                AgentSelect(state, dialog.agent, !dialog.busy, onEvent) { onEvent(StillsEvent.MemberAgentPicked(it)) }
            }
            SField(
                label = str(S.desktop_stk_limits),
                warn = if (limits == null) str(S.desktop_stk_limits_invalid) else null,
            ) {
                SDiscardLimits(
                    value = dialog.limits,
                    onChange = { onEvent(StillsEvent.MemberLimits(it)) },
                    used = usedOf(member.allowance),
                    enabled = !dialog.busy,
                )
            }
        } else {
            SAlert(SAlertTone.Error) { SText(str(S.desktop_stk_approval_not_needed_warn), 15, color = k.errorFg) }
        }

        RecognitionField(dialog.recognition, !dialog.busy) { onEvent(StillsEvent.MemberRecognition(it)) }

        // Their headshots, each with its own ✕.
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            member.headshots.forEach { shot ->
                HeadshotThumb(
                    name = "",
                    url = shot.url,
                    armed = dialog.confirmShot == shot.id,
                    enabled = !dialog.busy,
                    onRemove = { onEvent(StillsEvent.RemoveHeadshot(shot.id)) },
                )
            }
            if (member.headshots.isEmpty()) SText(str(S.desktop_stk_headshots_none), 13, color = k.muted)
        }
        if (dialog.confirmShot != null) SText(str(S.desktop_stk_headshot_remove_line), 13, color = k.muted)

        if (room > 0) {
            SDropZone(
                label = if (dialog.busy) str(S.desktop_csync_working) else str(S.desktop_stk_headshot_more),
                hint = str(S.desktop_stk_headshot_more_hint),
                onClick = { onEvent(StillsEvent.PickMemberHeadshots) },
                modifier = Modifier.padding(top = 8.dp),
                enabled = !dialog.busy && state.me.settings.attested,
                onPaths = { paths -> onEvent(StillsEvent.DropMemberHeadshots(paths)) },
            )
        }
        if (member.learnedFaces > 0) SHint(str(S.desktop_stk_learned_faces, member.learnedFaces))

        HeadshotResults(dialog.results, dialog.busy, onEvent)

        // Erasing and removing: said in words first, done behind a second question.
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DangerRow(str(S.desktop_stk_erase_face_data_hint), str(S.desktop_stk_erase_face_data), !dialog.busy) {
                onEvent(StillsEvent.Ask(MemberAsk.Erase))
            }
            DangerRow(str(S.desktop_stk_member_remove_hint), str(S.desktop_ce_remove_member), !dialog.busy) {
                onEvent(StillsEvent.Ask(MemberAsk.Remove))
            }
        }
    }

    when (dialog.ask) {
        MemberAsk.Erase -> SConfirmDialog(
            title = str(S.desktop_stk_erase_face_data),
            message = str(S.desktop_stk_erase_face_data_confirm, member?.name.orEmpty()),
            confirmLabel = str(S.desktop_stk_erase_face_data),
            busy = dialog.busy,
            onConfirm = { onEvent(StillsEvent.EraseFaceData) },
            onClose = { onEvent(StillsEvent.Ask(MemberAsk.None)) },
        )
        MemberAsk.Remove -> SConfirmDialog(
            title = str(S.desktop_ce_remove_member),
            message = str(S.desktop_stk_member_remove_confirm, member?.name.orEmpty()),
            confirmLabel = str(S.desktop_ce_remove_member),
            busy = dialog.busy,
            onConfirm = { onEvent(StillsEvent.RemoveMember(false)) },
            onClose = { onEvent(StillsEvent.Ask(MemberAsk.None)) },
        )
        // Named in photos: say what removing them does, and ask again.
        MemberAsk.RemoveInUse -> SConfirmDialog(
            title = str(S.desktop_ce_remove_member),
            message = str(S.desktop_stk_member_remove_in_use, member?.name.orEmpty(), member?.photoCount ?: 0),
            confirmLabel = str(S.desktop_stk_member_remove_anyway),
            busy = dialog.busy,
            onConfirm = { onEvent(StillsEvent.RemoveMember(true)) },
            onClose = { onEvent(StillsEvent.Ask(MemberAsk.None)) },
        )
        MemberAsk.None -> Unit
    }
}

/** `.stk-danger-rows > div` — the words, then the button. */
@Composable
private fun DangerRow(words: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val k = StillsTheme.c
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SText(words, 13, color = k.muted, modifier = Modifier.weight(1f))
        SBtn(label, onClick, small = true, enabled = enabled)
    }
}

/** What the service said about each headshot, with "add anyway" where a person may overrule it. */
@Composable
private fun HeadshotResults(results: List<HeadshotOutcome>, busy: Boolean, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val bad = results.filter { !it.ok }
    if (bad.isEmpty()) return
    bad.forEach { result ->
        SAlert(SAlertTone.Hint) {
            SText(
                text = "${result.name}: " + if (result.reason == "still_kills_headshot_matches_other" && result.matchedName.isNotBlank()) {
                    str(S.desktop_stk_headshot_looks_like, result.matchedName)
                } else {
                    com.zillit.desktop.core.localization.Labels.translate(result.reason, com.zillit.desktop.core.localization.LabelKind.Messages)
                },
                size = 13,
                color = k.accentInk,
            )
            if (result.canForce) {
                SBtn(str(S.desktop_stk_add_anyway), { onEvent(StillsEvent.ForceHeadshot(result)) }, kind = SBtnKind.Primary, small = true, enabled = !busy)
            }
        }
    }
}

/**
 * Enrol somebody from where a face was found (the lightbox's "or type new
 * name"): the enrolment card, in a dialog. If a headshot is turned away the
 * member's own card opens on it, so it can be put right.
 */
@Composable
internal fun NewMemberDialog(state: StillsUiState, form: EnrollState, onEvent: (StillsEvent) -> Unit) {
    SDialog(
        title = str(S.desktop_stk_enroll_title),
        onClose = { onEvent(StillsEvent.CancelEnroll) },
        busy = form.busy,
    ) {
        EnrollCard(state, form, onEvent, inCard = false)
    }
}

/** A stored date, as the card prints it. */
internal expect fun formatDay(millis: Long): String
