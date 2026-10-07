@file:Suppress("LongMethod", "MaxLineLength", "MagicNumber", "CyclomaticComplexMethod", "LongParameterList")

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.onBackdropTap
import com.zillit.desktop.core.designsystem.component.swallowPresses
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.domain.ApprovalRow
import com.zillit.desktop.feature.selectstills.domain.Decision
import com.zillit.desktop.feature.selectstills.domain.Face
import com.zillit.desktop.feature.selectstills.domain.FaceEdit
import com.zillit.desktop.feature.selectstills.domain.FaceState
import com.zillit.desktop.feature.selectstills.domain.Photo
import com.zillit.desktop.feature.selectstills.domain.PublicState
import com.zillit.desktop.feature.selectstills.domain.allowanceWords
import com.zillit.desktop.feature.selectstills.domain.decisionKey
import com.zillit.desktop.feature.selectstills.domain.discardBlocked
import com.zillit.desktop.feature.selectstills.domain.discardIsFree
import com.zillit.desktop.feature.selectstills.domain.errorKey
import com.zillit.desktop.feature.selectstills.domain.stateMeta

/**
 * One photo, full size — the web's lightbox: the picture on the left with face
 * boxes drawn to scale over it, and a side panel with where it stands, who is
 * in it and what the reader may do.
 *
 * The web has two of these and so does this, in one composable:
 *   from the gallery   the approval panel (✓ Approve · ✕ Reject on the
 *                      reader's own rows), the face rows — which production
 *                      names — and production's tools in the footer;
 *   from the queue     (`run`) the wider panel: the reader's actor, what the
 *                      other agents said, read-only faces with the reader's
 *                      own marked, and ✓ Keep · ✕ Discard… pinned in the
 *                      footer. Naming a face is the crew's job, in the gallery.
 *
 * The service decides what each reader is shown and which rows they may
 * decide, and this only draws that.
 *
 * Its keys — ← → to move, K keep, D discard, Esc close — act only while the
 * focus is inside it: a keystroke meant for another window can never decide a
 * photo. K and D act only when the reader has exactly one row to decide.
 */
@Composable
internal fun PhotoLightbox(state: StillsUiState, box: LightboxState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val photo = box.photo
    val focus = remember { FocusRequester() }
    LaunchedEffect(box.photoId) { runCatching { focus.requestFocus() } }

    val mineRows = photo?.approvals.orEmpty().filter { it.canDecide }
    val decides = mineRows.size == 1 && photo?.status?.isDone == true

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xDB08080A))
            .focusRequester(focus)
            .focusable()
            // `onKeyEvent`, not `onPreviewKeyEvent`: a focused text field answers
            // first and consumes what it uses, which is the web's `isTyping`
            // guard — typing "k" into "or type new name" must not keep the photo.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // A panel of its own owns its keys.
                if (box.similar != null || state.enroll?.inDialog == true || state.memberDialog != null) return@onKeyEvent false
                if (event.isMetaPressed || event.isCtrlPressed || event.isAltPressed) return@onKeyEvent false
                when (event.key) {
                    Key.Escape -> { onEvent(StillsEvent.ClosePhoto); true }
                    Key.DirectionLeft -> { onEvent(StillsEvent.StepPhoto(-1)); true }
                    Key.DirectionRight -> { onEvent(StillsEvent.StepPhoto(1)); true }
                    Key.K -> if (decides && !box.busy && !box.asking) {
                        onEvent(StillsEvent.DecideInPhoto(mineRows.first().memberId, Decision.Approved))
                        true
                    } else {
                        false
                    }
                    Key.D -> if (decides && !box.busy && !box.asking) {
                        onEvent(StillsEvent.AskWhy)
                        true
                    } else {
                        false
                    }
                    else -> false
                }
            }
            .onBackdropTap { onEvent(StillsEvent.ClosePhoto) },
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            // Under 900px of tool the panel drops under the picture, as the
            // web's container query does it.
            val stacked = maxWidth <= STACK_AT
            val shape = RoundedCornerShape(14.dp)
            val frame = Modifier
                .widthIn(max = if (box.run) 1400.dp else 1200.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(k.panel)
                .border(BorderStroke(1.dp, k.line), shape)
                // A press on the card — including the chrome beside a button —
                // is not a press on the backdrop behind it.
                .swallowPresses()

            if (stacked) {
                Column(frame.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Box(Modifier.fillMaxWidth().weight(if (box.run) 0.38f else 0.6f)) { Stage(box, onEvent) }
                    Box(Modifier.fillMaxWidth().weight(1f)) { SidePanel(state, box, onEvent, mineRows, decides) }
                }
            } else {
                Row(frame.fillMaxHeight(0.92f)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { Stage(box, onEvent) }
                    Box(Modifier.width(if (box.run) 380.dp else 320.dp).fillMaxHeight()) {
                        SidePanel(state, box, onEvent, mineRows, decides)
                    }
                }
            }
        }

        box.similar?.let { panel -> SimilarPanel(state, panel, onEvent) }
    }
}

/** `.stk-lb-img-wrap` — the picture, its face boxes, the arrows and the counter. */
@Composable
private fun Stage(box: LightboxState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val photo = box.photo
    Box(Modifier.fillMaxSize().background(k.stageBg).padding(14.dp), contentAlignment = Alignment.Center) {
        if (photo != null && photo.previewUrl.isNotBlank()) {
            // The box is the picture's own frame: the ratios a face box carries
            // are of the preview, so they only land correctly over the picture
            // itself, never over the padding around it.
            val ratio = if (photo.width > 0 && photo.height > 0) photo.width.toFloat() / photo.height else 3f / 2f
            Box(
                Modifier
                    .then(if (ratio >= 1f) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
                    .aspectRatio(ratio),
            ) {
                SImage(photo.previewUrl, Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)), ContentScale.Fit, photo.originalName)
                FaceBoxes(photo, box, onEvent)
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    box.loading && photo == null -> SLoading(bare = true)
                    box.failed && photo == null -> {
                        SText(str(S.desktop_stk_photo_load_failed), 15, color = k.muted)
                        SBtn(str(S.desktop_csync_try_again), { onEvent(StillsEvent.ReloadPhoto) }, small = true)
                    }
                    photo != null -> SText(photoStatusWords(photo.status, photo.errorCode), 15, color = k.muted)
                }
            }
        }

        // `.stk-rl-nav`
        NavArrow("‹", str(S.desktop_stk_previous_photo), Alignment.CenterStart, box.index > 0) {
            onEvent(StillsEvent.StepPhoto(-1))
        }
        NavArrow("›", str(S.desktop_stk_next_photo), Alignment.CenterEnd, box.index in 0 until box.ids.size - 1) {
            onEvent(StillsEvent.StepPhoto(1))
        }
        if (box.index >= 0) {
            SText(
                text = str(S.desktop_card_spent_of_limit, box.index + 1, box.ids.size),
                size = 12,
                color = k.muted,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 12.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * One of the two arrows over the picture.
 *
 * [alignment] is applied to a wrapper, not passed in as a modifier: `align` is
 * a BoxScope modifier and only works on a DIRECT child of the Box, and the
 * tooltip around an enabled arrow is exactly such a layer in between. Passing
 * it inward left both arrows stacked in the middle of the photo (seen live
 * 2026-10-07).
 */
@Composable
private fun BoxScope.NavArrow(
    glyph: String,
    label: String,
    alignment: Alignment,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val k = StillsTheme.c
    val shape = RoundedCornerShape(10.dp)
    val body: @Composable () -> Unit = {
        Box(
            Modifier
                .padding(horizontal = 10.dp)
                .size(width = 44.dp, height = 62.dp)
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.6f))
                .border(BorderStroke(1.dp, k.line), shape)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier.alpha(DIMMED)),
            contentAlignment = Alignment.Center,
        ) {
            SText(glyph, 28, color = k.ink)
        }
    }
    Box(Modifier.align(alignment)) {
        if (enabled) ZillitTooltip(text = label) { body() } else body()
    }
}

/**
 * `.stk-lb-box` — face boxes drawn to scale over the photo: amber and solid
 * for a named face, white and dashed for an unknown one, the name in the
 * corner — and green for the reader's own actor, so a group shot needs no
 * guessing.
 *
 * A box is given as ratios of the preview, so it is placed in fractions and
 * follows the image at any size. A person added by hand has no box (there was
 * no face to point at).
 */
@Composable
private fun FaceBoxes(photo: Photo, box: LightboxState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val mineIds = remember(photo) { minedFaceIds(photo) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        photo.faces.filter { it.box != null }.forEach { face ->
            val b = face.box ?: return@forEach
            val dismissed = face.state == FaceState.Dismissed
            val mine = face.id in mineIds
            val edge = when {
                mine -> k.ok
                dismissed -> Color.White.copy(alpha = 0.4f)
                face.name.isNotBlank() -> k.accent
                else -> Color.White.copy(alpha = 0.9f)
            }
            Box(
                Modifier
                    .offset(x = width * b.left, y = height * b.top)
                    .size(width = width * b.width, height = height * b.height)
                    .border(BorderStroke(if (mine) 3.dp else 2.dp, edge))
                    .then(if (box.activeFace == face.id) Modifier.border(BorderStroke(4.dp, Color.White.copy(alpha = 0.55f))) else Modifier)
                    .clickable { onEvent(StillsEvent.PickFace(face.id)) },
            ) {
                SText(
                    text = if (dismissed) str(S.desktop_stk_face_not_cast) else face.name.ifBlank { str(S.desktop_unknown_lower) },
                    size = 11,
                    weight = Bold,
                    color = if (mine) Color(0xFF06240F) else if (face.name.isNotBlank() && !dismissed) Color(0xFF221503) else Color(0xFFD6DADF),
                    modifier = Modifier
                        .background(if (mine) k.ok else if (face.name.isNotBlank() && !dismissed) k.accent else Color(0xF2323438))
                        .padding(horizontal = 6.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

/** The faces that belong to a member the reader answers for. */
private fun minedFaceIds(photo: Photo): Set<String> {
    val members = photo.approvals.filter { it.canDecide }.map { it.memberId }.toSet()
    return photo.faces.filter { it.memberId != null && it.memberId in members }.map { it.id }.toSet()
}

/** `.stk-lb-side` — the title, one scrolling region, and the pinned footer. */
@Composable
private fun SidePanel(
    state: StillsUiState,
    box: LightboxState,
    onEvent: (StillsEvent) -> Unit,
    mineRows: List<ApprovalRow>,
    decides: Boolean,
) {
    val k = StillsTheme.c
    val photo = box.photo
    val done = photo?.status?.isDone == true
    val meta = photo?.let { stateMeta(it.publicState) }
    // As on a tile: "public" is not stamped on a photo nobody had to clear.
    val showState = done && meta != null && (photo.publicState != PublicState.Approved || photo.approvals.isNotEmpty())
    val edits = state.canPost && !box.run
    val tip = str(if (decides) (if (box.run) S.desktop_stk_keys_decide else S.desktop_stk_keys_gate) else S.desktop_stk_keys_move)

    Column(Modifier.fillMaxSize()) {
        // `.stk-lb-head`
        Row(
            Modifier.fillMaxWidth().background(k.panel).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The name, or the web's "…" while it is on its way; the tooltip
            // carries the whole of a name the column had to cut.
            //
            // `weight` goes on the Box, not the text: it is a RowScope modifier
            // and the tooltip between the two would swallow it, leaving the
            // name to take the whole row and push the pills and the ✕ out of
            // the header (seen live 2026-10-07).
            val name = photo?.originalName?.takeIf { it.isNotBlank() }
            Box(Modifier.weight(1f)) {
                ZillitTooltip(text = name ?: str(S.photo)) {
                    SText(name ?: "…", 14, Bold, k.muted, maxLines = 1)
                }
            }
            if (showState) SStatePill(meta.tone, str(meta.key), str(meta.hint))
            if (done) SPill(peopleWords(photo.people), SPillKind.Ghost)
            SBtn("✕", { onEvent(StillsEvent.ClosePhoto) }, small = true, tooltip = str(S.close))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))

        // Everything between the title and the buttons scrolls as one region.
        // The buttons are pinned: a six-hander's face list is long enough to
        // push them off the bottom, and a button you have to go looking for is
        // a button that gets missed.
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        ) {
            if (done) ApprovalPanel(state, photo, box, onEvent)

            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (photo != null && !done) {
                    SAlert(if (photo.status.isFailed) SAlertTone.Error else SAlertTone.Plain) {
                        SText(photoStatusWords(photo.status, photo.errorCode), 13, color = alertInk(if (photo.status.isFailed) SAlertTone.Error else SAlertTone.Plain))
                    }
                }

                if (done) {
                    if (photo.faces.isEmpty()) SText(str(S.desktop_stk_no_faces_found), 13, color = k.muted)
                    // With agents to wait for, the approval panel says it above.
                    if (photo.unnamed > 0 && (box.run || photo.approvals.isEmpty())) {
                        SText(str(S.desktop_stk_unidentified), 13, color = k.accentInk)
                    }
                    if (photo.truncated) SText(str(S.desktop_stk_photo_truncated), 13, color = k.accentInk)

                    val taken = photo.faces.filter { it.isNamed }.mapNotNull { it.memberId }
                    photo.faces.forEach { face ->
                        FaceRow(state, photo, face, taken, box, readOnly = box.run, onEvent = onEvent)
                    }

                    if (edits) {
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SText(str(S.desktop_stk_add_missed).uppercase(), 11, Bold, k.muted)
                            SSelect(
                                options = state.members.filter { it.id !in taken }.map { SOption(it.id, it.name, it.characterName) },
                                value = "",
                                onChange = { memberId -> if (memberId.isNotBlank()) onEvent(StillsEvent.AddPerson(memberId)) },
                                placeholder = str(S.desktop_stk_face_pick),
                                searchLabel = str(S.desktop_stk_search_members, state.members.size),
                                enabled = !box.busy,
                            )
                            SHint(str(S.desktop_stk_add_missed_hint))
                            if (photo.unnamed > 0) {
                                SConfirmBtn(
                                    label = str(S.desktop_stk_dismiss_unnamed),
                                    confirmLabel = str(S.desktop_stk_click_again),
                                    onConfirm = { onEvent(StillsEvent.DismissUnknown) },
                                    enabled = !box.busy,
                                )
                            }
                        }
                    }
                }

                photo?.let { PhotoMeta(state, it) }
            }
        }

        // `.stk-lb-foot`
        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
        Column(Modifier.fillMaxWidth().background(k.panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (box.run) {
                // In the review lightbox the one row the reader decides is
                // answered from the footer.
                val footRow = mineRows.singleOrNull()?.takeIf { decides }
                if (footRow != null && photo != null) {
                    val allowance = state.clients.firstOrNull { it.memberId == footRow.memberId }?.allowance
                    SDecisionBar(
                        state = footRow.state,
                        where = SDecideWhere.Foot,
                        blocked = discardBlocked(allowance, photo.section, footRow.state, discardIsFree(photo.approvals, footRow.memberId)),
                        busy = box.busy,
                        asking = box.asking,
                        note = box.note,
                        onAsk = { onEvent(StillsEvent.AskWhy) },
                        onCancel = { onEvent(StillsEvent.CancelWhy) },
                        onNote = { onEvent(StillsEvent.WhyTyped(it)) },
                        onDecide = { next, note -> onEvent(StillsEvent.DecideInPhoto(footRow.memberId, next, note)) },
                        tip = tip,
                    )
                } else {
                    SText(tip, 12, color = k.muted)
                }
            } else {
                if (state.canPost && photo != null) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SBtn(str(S.desktop_stk_download_original), { onEvent(StillsEvent.DownloadOriginal) }, small = true, enabled = !box.busy)
                        SBtn(
                            text = str(S.desktop_stk_process_again),
                            onClick = { onEvent(StillsEvent.RetryPhoto) },
                            small = true,
                            enabled = !box.busy && photo.status != com.zillit.desktop.feature.selectstills.domain.PhotoStatus.Queued &&
                                photo.status != com.zillit.desktop.feature.selectstills.domain.PhotoStatus.Processing,
                        )
                        SConfirmBtn(
                            label = if (box.deleting) str(S.ah_deleting) else str(S.desktop_stk_delete_photo),
                            confirmLabel = str(S.desktop_stk_delete_photo_confirm),
                            onConfirm = { onEvent(StillsEvent.DeletePhoto) },
                            enabled = !box.busy,
                        )
                    }
                }
                SText(tip, 12, color = k.muted)
            }
        }
    }
}

/** `.stk-rl-meta` — the shoot, who uploaded it and when, and its size. */
@Composable
private fun PhotoMeta(state: StillsUiState, photo: Photo) {
    val k = StillsTheme.c
    val uploader = photo.uploadedBy?.let { state.crewById[it]?.fullName }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
        MetaRow(str(S.dm_ds_phase_shoot), photo.shootLabel.ifBlank { "—" })
        MetaRow(
            label = str(S.sides_uploaded),
            value = buildString {
                append(formatStamp(photo.createdMillis))
                if (uploader != null && state.canPost) append(" · $uploader")
            },
        )
        MetaRow(str(S.drive_sort_size), if (photo.width > 0) "${photo.width}×${photo.height}" else "—")
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    val k = StillsTheme.c
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SText(label, 12, color = k.muted, modifier = Modifier.width(86.dp))
        SText(value, 13, modifier = Modifier.weight(1f))
    }
}

/** A stamp with its time, as the lightbox prints it. */
internal expect fun formatStamp(millis: Long): String

private val STACK_AT: Dp = 900.dp
private const val DIMMED = 0.25f
