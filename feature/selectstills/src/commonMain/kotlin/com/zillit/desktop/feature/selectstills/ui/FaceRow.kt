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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.domain.Face
import com.zillit.desktop.feature.selectstills.domain.FaceEdit
import com.zillit.desktop.feature.selectstills.domain.FaceState
import com.zillit.desktop.feature.selectstills.domain.Photo
import kotlin.math.roundToInt

/**
 * `.stk-face-row` — one face of a photo in the side panel: a 48px crop, who it
 * is, and how sure the system is.
 *
 * Production (posting rights) names it as the web does: pick a member, or type
 * a new name and Save (which opens the enrolment card with the name filled in:
 * a new member needs their agreement and an answer about approval, so nobody
 * is enrolled by a keystroke). Choosing "— pick member —" again takes the name
 * off. On top of that: say it is not cast (an extra, crew, a passer-by — its
 * face data is deleted at once), or look for the same person in other photos.
 * A name the system only SUGGESTS holds the photo until a person says yes or
 * no.
 *
 * Everybody else — and everybody in the review lightbox, where naming is not
 * the job — sees the same row without the controls. An agent gets one thing on
 * their own actor's face: "This is not my client".
 *
 * A person production added by hand has no face: the entry is only removed,
 * never renamed. Unless their agent said it is not their client — the entry
 * then stays, with no name, and holds the photo until production says who it
 * is or takes it out. That is the one hand-added row with a name picker.
 */
@Composable
internal fun FaceRow(
    state: StillsUiState,
    photo: Photo,
    face: Face,
    taken: List<String>,
    box: LightboxState,
    readOnly: Boolean,
    onEvent: (StillsEvent) -> Unit,
) {
    val k = StillsTheme.c
    var newName by remember(face.id) { mutableStateOf("") }
    val edits = state.canPost && !readOnly
    val named = face.isNamed
    val suggested = face.state == FaceState.Suggested
    val dismissed = face.state == FaceState.Dismissed
    // Added by hand, then disowned by the agent: somebody, nobody knows who.
    val loose = face.manual && !named && !dismissed
    val mine = remember(photo, face) { face.memberId != null && photo.approvals.any { it.canDecide && it.memberId == face.memberId } }
    val active = box.activeFace == face.id

    val note = if (edits && loose) str(S.desktop_stk_face_added_disputed) else faceNote(face, state.canPost)
    val others = face.candidates.filter { it.memberId != face.memberId }.take(2)
    val couldBe = if (edits && !dismissed && others.isNotEmpty()) {
        str(S.desktop_stk_face_could_be, others.joinToString(", ") { "${it.name} (${it.similarity.roundToInt()}%)" })
    } else {
        ""
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (active) k.overlay.copy(alpha = ACTIVE_WASH) else Color.Transparent)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SFaceCrop(face.cropUrl, dimmed = dismissed)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    when {
                        dismissed -> SText(str(S.desktop_stk_face_not_cast), 15, color = k.muted)
                        named -> SText(face.name, 15, maxLines = 1)
                        else -> SText(str(S.desktop_unknown_lower), 15, color = k.muted)
                    }
                    if (mine) SPill(str(S.desktop_stk_your_actor), SPillKind.Yours)
                }
                val line = listOf(if (dismissed) "" else note, couldBe).filter { it.isNotBlank() }.joinToString(" · ")
                if (line.isNotBlank()) SText(line, 12, color = k.muted)
            }
        }

        // "Is this …?" — a name the system only proposes, for a person to settle.
        if (edits && suggested && face.name.isNotBlank()) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SText(
                    text = str(S.desktop_stk_face_is_this, face.name, face.similarity.roundToInt()),
                    size = 13,
                    color = k.accentInk,
                    modifier = Modifier.widthIn(min = 140.dp).weight(1f, fill = false),
                )
                SBtn(str(S.yes), { onEvent(StillsEvent.SetFace(face.id, FaceEdit.Name(face.memberId.orEmpty()))) }, kind = SBtnKind.Primary, small = true, enabled = !box.busy)
                SBtn(str(S.no), { onEvent(StillsEvent.SetFace(face.id, FaceEdit.Clear)) }, small = true, enabled = !box.busy)
            }
        }

        if (edits) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dismissed) {
                    SBtn(str(S.dd_rt_undo), { onEvent(StillsEvent.SetFace(face.id, FaceEdit.Clear)) }, small = true, enabled = !box.busy)
                } else {
                    if (!face.manual || loose) {
                        SSelect(
                            options = state.members
                                .filter { it.id !in taken || it.id == face.memberId }
                                .sortedBy { it.name.lowercase() }
                                .map { SOption(it.id, it.name, it.characterName) },
                            value = if (named) face.memberId.orEmpty() else "",
                            onChange = { memberId ->
                                if (memberId.isNotBlank()) {
                                    onEvent(StillsEvent.SetFace(face.id, FaceEdit.Name(memberId)))
                                } else if (named) {
                                    onEvent(StillsEvent.SetFace(face.id, FaceEdit.Clear))
                                }
                            },
                            modifier = Modifier.widthIn(min = 160.dp).weight(1f),
                            emptyLabel = str(S.desktop_stk_face_pick),
                            searchLabel = str(S.desktop_stk_search_members, state.members.size),
                            enabled = !box.busy,
                            small = true,
                        )
                    }
                    if (!named && !face.manual) {
                        SInput(
                            value = newName,
                            onChange = { newName = it },
                            modifier = Modifier.widthIn(min = 140.dp).weight(1f),
                            placeholder = str(S.desktop_stk_face_new_name),
                            maxLength = 120,
                            enabled = !box.busy,
                            size = 13,
                            onEnter = {
                                if (newName.isNotBlank()) {
                                    onEvent(StillsEvent.StartEnroll(newName.trim(), face.id))
                                    newName = ""
                                }
                            },
                        )
                        SBtn(
                            text = if (box.busy) str(S.ah_saving) else str(S.save),
                            onClick = {
                                onEvent(StillsEvent.StartEnroll(newName.trim(), face.id))
                                newName = ""
                            },
                            kind = SBtnKind.Primary,
                            small = true,
                            enabled = !box.busy && newName.isNotBlank(),
                        )
                        SBtn(
                            text = str(S.desktop_stk_face_mark_not_cast),
                            onClick = { onEvent(StillsEvent.SetFace(face.id, FaceEdit.NotCast)) },
                            small = true,
                            enabled = !box.busy,
                            tooltip = str(S.desktop_stk_face_not_cast_hint),
                        )
                    }
                    if (face.manual && (named || loose)) {
                        SBtn(str(S.remove), { onEvent(StillsEvent.SetFace(face.id, FaceEdit.Clear)) }, small = true, enabled = !box.busy)
                    }
                    if (face.hasVector) {
                        SBtn(
                            text = str(S.desktop_stk_face_find),
                            onClick = { onEvent(StillsEvent.FindSimilar(face.id, face.memberId.takeIf { named })) },
                            small = true,
                            enabled = !box.busy,
                            tooltip = str(S.desktop_stk_face_find_hint),
                        )
                    }
                }
            }
        }

        // An agent's one control on their own actor's face.
        if (mine && named) {
            SBtn(str(S.desktop_stk_not_my_client), { onEvent(StillsEvent.DisputeFace(face.id)) }, kind = SBtnKind.Link, small = true, enabled = !box.busy)
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
    }
}

/**
 * How a face got its name, for the line under it. `byCrew` is who reads it: to
 * the crew a name given by hand is "named by you", to everybody else it is
 * "named by the crew".
 */
@Composable
internal fun faceNote(face: Face, byCrew: Boolean): String = when {
    face.state == FaceState.Dismissed -> str(S.desktop_stk_face_not_cast)
    face.manual -> str(S.desktop_csync_sched_added_by_hand)
    face.state == FaceState.Tagged -> str(if (byCrew) S.desktop_stk_face_named_by_you else S.desktop_stk_face_named_by_crew)
    face.state == FaceState.Matched ->
        if (face.similarity > 0f) str(S.desktop_stk_face_match, face.similarity.roundToInt()) else str(S.desktop_stk_face_matched)
    face.state == FaceState.Suggested -> str(S.desktop_stk_face_suggested)
    else -> str(S.desktop_stk_face_not_recognised)
}

/** The row a face box points at, picked out without a border. */
private const val ACTIVE_WASH = 0.05f
