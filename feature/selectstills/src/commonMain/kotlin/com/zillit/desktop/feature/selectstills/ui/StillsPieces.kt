@file:Suppress("LongMethod", "MaxLineLength", "TooManyFunctions", "LongParameterList", "MagicNumber", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitOptionPopup
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.externalPathDrop
import com.zillit.desktop.core.designsystem.component.popupWidth
import com.zillit.desktop.core.designsystem.component.rememberZillitSelectAnchor
import com.zillit.desktop.core.designsystem.component.zillitSelectAnchor
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.domain.Decision
import com.zillit.desktop.feature.selectstills.domain.PhotoStatus
import com.zillit.desktop.feature.selectstills.domain.PhotoTile
import com.zillit.desktop.feature.selectstills.domain.STILLS_SECTIONS
import com.zillit.desktop.feature.selectstills.domain.decisionKey
import com.zillit.desktop.feature.selectstills.domain.errorKey
import com.zillit.desktop.feature.selectstills.domain.sectionKey
import com.zillit.desktop.feature.selectstills.domain.stateMeta

/**
 * The pieces the Select Stills screens share: the gallery tile, the pickers,
 * the allowance grid, the drop zone and the decision bar.
 *
 * Every one is drawn to the web's `StillKills.css` rule for it; the comment on
 * each names the rule.
 */

/** "no faces" / "single" / "group · 4". */
@Composable
internal fun peopleWords(people: Int): String = when {
    people <= 0 -> str(S.desktop_stk_people_none)
    people == 1 -> str(S.desktop_stk_people_solo)
    else -> str(S.desktop_stk_people_group, people)
}

/**
 * `.stk-tile` — the gallery tile: a uniform square crop, member chips over a
 * gradient, and where the photo stands at the top right.
 *
 * Face boxes are not drawn here — the cropped thumbnail would misplace them,
 * and chips read better at a glance; they are in the lightbox. Unlike the
 * web's demo, a tile that is still being processed, or that failed, opens too:
 * that is where production retries or deletes it.
 */
@Composable
internal fun SPhotoTile(photo: PhotoTile, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (photo.originalName.isBlank()) {
        PhotoTileBody(photo, onOpen, modifier)
        return
    }
    // The camera's own file name, as the web's `title` gives it.
    ZillitTooltip(text = photo.originalName) { PhotoTileBody(photo, onOpen, modifier) }
}

@Composable
private fun PhotoTileBody(photo: PhotoTile, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val k = StillsTheme.c
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)
    val done = photo.status.isDone
    val failed = photo.status.isFailed
    val meta = stateMeta(photo.publicState)
    // To a reader who only ever sees cleared photos, "public" on every tile is noise.
    val showState = done && (photo.publicState != com.zillit.desktop.feature.selectstills.domain.PublicState.Approved || photo.hasApprovals)

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(k.panel2)
            .border(BorderStroke(1.dp, if (hovered) k.lineHover else k.line), shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onOpen),
    ) {
        SImage(photo.thumbUrl, Modifier.fillMaxSize(), description = photo.originalName) {
            SText(
                text = if (failed) str(errorKey(photo.errorCode)) else str(S.desktop_stk_status_processing),
                size = 13,
                color = k.muted,
                modifier = Modifier.padding(12.dp),
            )
        }
        // `.stk-shade` — so the chips read over a bright frame. Only where
        // there IS a frame: over a placeholder it is just a grey smear.
        if (photo.thumbUrl.isNotBlank()) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(SHADE)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
            )
        }

        // `.stk-flag`
        Row(
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (showState) SStatePill(meta.tone, str(meta.key), str(meta.hint))
            if (done) SPill(peopleWords(photo.people), SPillKind.Ghost)
            if (failed) SPill(str(S.desktop_stk_status_failed), SPillKind.Failed, hint = str(errorKey(photo.errorCode)))
            if (!done && !failed) SPill(str(S.desktop_stk_status_processing), SPillKind.Working)
        }

        // `.stk-chips` — who is in it.
        if (done && (photo.names.isNotEmpty() || photo.unnamed > 0)) {
            FlowRow(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                photo.names.forEach { name -> SPill(name, SPillKind.Member, Modifier.widthIn(max = 160.dp)) }
                if (photo.moreNames > 0) SPill("+${photo.moreNames}", SPillKind.Member)
                if (photo.unnamed > 0) SPill(str(S.desktop_stk_tile_unknown, photo.unnamed), SPillKind.Unknown)
            }
        }
    }
}

/**
 * `.stk-drop` — the dashed drop zone: click to choose, or drag a card onto it.
 *
 * What counts as a photo is decided by the caller, which also says what was
 * turned away.
 */
@Composable
internal fun SDropZone(
    label: String,
    hint: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    over: Boolean = false,
    secondary: Pair<String, () -> Unit>? = null,
    /** Set to take an OS drop here too; the paths arrive unread. */
    onPaths: ((List<String>) -> Unit)? = null,
) {
    val k = StillsTheme.c
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragged by remember { mutableStateOf(false) }
    val lit = over || dragged || hovered
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (onPaths == null) {
                    Modifier
                } else {
                    Modifier.externalPathDrop(enabled = enabled, onHover = { dragged = it }, onPaths = onPaths)
                },
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (lit) k.accent.copy(alpha = 0.06f) else Color.Transparent)
                .border(BorderStroke(2.dp, if (lit) k.accent else k.dashed), shape)
                .then(if (enabled) Modifier.hoverable(source).clickable(interactionSource = source, indication = null, onClick = onClick) else Modifier.alpha(0.6f))
                .padding(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SText(label, 15, color = k.muted)
            if (!hint.isNullOrBlank()) SText(hint, 13, color = k.muted)
        }
        secondary?.let { (text, click) ->
            SBtn(text, click, kind = SBtnKind.Link, small = true, enabled = enabled)
        }
    }
}

/**
 * `.stk-dl` — how many photos this person's agent may discard, section by
 * section.
 *
 * A number per section rather than one total: discarding a solo portrait costs
 * the production one frame, while discarding a six-hander costs everyone else
 * in the frame too, and a contract that allows ten discards never means ten of
 * each.
 *
 * **Blank is NOT zero.** Blank means the contract says nothing about that
 * section, so nothing is capped; 0 means nothing may be discarded there at
 * all. The two are easy to confuse, so the field says so in words.
 *
 * @param used already charged, so a limit is not set below what is spent by accident
 */
@Composable
internal fun SDiscardLimits(
    value: Map<String, String>,
    onChange: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
    used: Map<String, Int>? = null,
    enabled: Boolean = true,
    columns: Int = 3,
) {
    val k = StillsTheme.c
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        STILLS_SECTIONS.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { section ->
                    val raw = value[section].orEmpty()
                    val spent = used?.get(section) ?: 0
                    val bad = raw.isNotEmpty() && !raw.trim().all { it.isDigit() }
                    val over = !bad && raw.isNotEmpty() && spent > (raw.trim().toIntOrNull() ?: 0)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        SText(str(sectionKey(section)), 12, color = k.muted, maxLines = 1)
                        SInput(
                            value = raw,
                            onChange = { next -> onChange(value + (section to next)) },
                            placeholder = str(S.desktop_stk_limit_none_placeholder),
                            enabled = enabled,
                            size = 14,
                            background = k.bg,
                            focusBorder = if (bad || over) k.warn else k.accent,
                        )
                        if (used != null) {
                            val suffix = if (over) " — ${str(S.desktop_over_lower)}" else ""
                            SText(
                                text = (if (spent == 0) str(S.desktop_stk_limit_none_used) else str(S.desktop_stk_limit_used, spent)) + suffix,
                                size = 11,
                                color = if (over) k.warn else k.muted,
                            )
                        }
                    }
                }
                // The last row may be short; the cells keep their width.
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        SHint(str(S.desktop_stk_limits_hint))
    }
}

/** One option in a stills picker. */
internal data class SOption(val value: String, val label: String, val sub: String = "")

/**
 * `.stk-ss` — the select you can type into, for lists that run to dozens (the
 * enrolled members, the production's people).
 *
 * Its own trigger in the tool's colours, and the app's one list style for the
 * options, so a picker here behaves like a picker anywhere else.
 */
@Composable
internal fun SSelect(
    options: List<SOption>,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Offered as a choice ("— nobody yet —") when set; the placeholder otherwise. */
    emptyLabel: String? = null,
    placeholder: String = "",
    searchLabel: String = "",
    enabled: Boolean = true,
    small: Boolean = false,
    onOpen: () -> Unit = {},
) {
    val k = StillsTheme.c
    val anchor = rememberZillitSelectAnchor()
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val all = remember(options, emptyLabel) {
        if (emptyLabel != null) listOf(SOption("", emptyLabel)) + options else options
    }
    val current = options.firstOrNull { it.value == value }
    val shape = RoundedCornerShape(10.dp)

    Box(modifier.zillitSelectAnchor(anchor)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(k.panel2)
                .border(BorderStroke(1.dp, if (hovered && enabled) k.lineHover else k.line), shape)
                .then(if (enabled) Modifier.hoverable(source).clickable(interactionSource = source, indication = null) { onOpen(); open = true } else Modifier.alpha(0.6f))
                .padding(horizontal = if (small) 10.dp else 12.dp, vertical = if (small) 5.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SText(
                text = current?.label ?: emptyLabel ?: placeholder,
                size = if (small) 13 else 15,
                weight = SemiBold,
                color = if (current == null) k.muted else k.ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            SText("▾", 11, color = k.muted)
        }
        if (open) {
            ZillitOptionPopup(
                onDismiss = { open = false },
                width = maxOf(anchor.popupWidth(), 260.dp),
                options = all,
                isSelected = { it.value == value },
                onPick = { picked ->
                    open = false
                    if (picked.value != value) onChange(picked.value)
                },
                label = SOption::label,
                searchable = true,
                searchPlaceholder = searchLabel.takeIf { it.isNotBlank() },
                // The "nothing" row matches no search, so it shows only unsearched.
                searchText = { option -> if (option.value.isEmpty()) "" else "${option.label} ${option.sub}" },
                subtitle = { it.sub.takeIf { sub -> sub.isNotBlank() } },
                showInitials = false,
                emptyText = str(S.desktop_stk_nothing_matches, ""),
            )
        }
    }
}

/**
 * `.stk-members` / `.stk-mp` — filter the gallery by the person in the photo.
 *
 * A wide window keeps the vertical list, which is scannable and shows counts at
 * a glance. A narrow one gets the searchable dropdown instead: a row of chips
 * hides most of the cast off the right edge and is unusable once a production
 * has more than a few people. Search appears on the wide list too once the
 * cast is long enough to scroll.
 */
@Composable
internal fun SMemberPicker(
    members: List<SOption>,
    counts: Map<String, Int?>,
    total: Int?,
    value: String?,
    onChange: (String?) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val k = StillsTheme.c
    val search = str(S.desktop_stk_search_members, members.size)
    val matches = remember(members, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) members else members.filter { it.label.lowercase().contains(needle) }
    }

    if (compact) {
        val chosen = members.firstOrNull { it.value == value }
        SSelect(
            options = members,
            value = value.orEmpty(),
            onChange = { picked -> onChange(picked.takeIf { it.isNotBlank() }) },
            modifier = modifier,
            emptyLabel = str(S.desktop_everyone),
            searchLabel = search,
        )
        // The count the chosen row stands for, as the web's trigger shows it.
        val shown = if (value != null) counts[chosen?.value] else total
        if (shown != null) SText(shown.toString(), 12, Bold, k.muted, Modifier.padding(top = 4.dp))
        return
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (members.size > SEARCHABLE_AT) {
            SSearch(query, onQuery, search, Modifier.padding(bottom = 6.dp))
        }
        SFilterRow(str(S.desktop_everyone), total, value == null) { onChange(null) }
        if (members.isEmpty()) SText(str(S.desktop_stk_no_tagged), 13, color = k.muted)
        matches.forEach { member ->
            SFilterRow(member.label, counts[member.value], value == member.value) { onChange(member.value) }
        }
        if (members.size > SEARCHABLE_AT && matches.isEmpty()) {
            SText(str(S.desktop_stk_no_one_matches, query), 13, color = k.muted)
        }
    }
}

/** `.stk-side .stk-filter` — one row of the by-member list. */
@Composable
private fun SFilterRow(label: String, count: Int?, active: Boolean, onClick: () -> Unit) {
    val k = StillsTheme.c
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) k.accent else if (hovered) k.panel2 else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SText(label, 15, if (active) Bold else androidx.compose.ui.text.font.FontWeight.Normal, if (active) k.onAccent else k.ink, Modifier.weight(1f), maxLines = 1)
        if (count != null) SText(count.toString(), 15, color = if (active) k.onAccentMuted else k.muted, maxLines = 1)
    }
}

/**
 * `.stk-rv-stamp` / `.stk-rv-chip` — a decision as the queue prints it.
 */
@Composable
internal fun SDecisionChip(name: String, state: Decision, note: String = "", modifier: Modifier = Modifier) {
    val k = StillsTheme.c
    val (edge, ink) = when (state) {
        Decision.Rejected -> k.warn to k.badText
        Decision.Approved -> k.ok to k.okText
        Decision.Pending -> k.line to k.muted
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(k.panel2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `border-left: 3px solid` — the edge says which way the row went.
        Box(Modifier.width(3.dp).height(EDGE_HEIGHT).background(edge))
        SText(
            text = buildString {
                append(name)
                append(": ")
                append(str(decisionKey(state)))
                if (note.isNotBlank()) append(" — ${str(S.desktop_stk_reason_quote, note)}")
            },
            size = 12,
            color = ink,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}


/**
 * The three places a decision is taken, and their words — the web's
 * `DecisionBar`:
 *   Gate  the gallery lightbox's approval row   ✓ Approve · ✕ Reject · Undo
 *   Card  a card in the review queue            ✓ Keep · ✕ Discard · Undo
 *   Foot  the review lightbox's pinned footer   ✓ Keep · ✕ Discard… · undo
 *
 * The first two are chips and ask why on one line; the footer has room for
 * real buttons and a few lines of reason.
 */
internal enum class SDecideWhere { Gate, Card, Foot }

private class DecideWords(val yes: String, val no: String, val confirm: String, val why: String, val eg: String)

private fun wordsFor(where: SDecideWhere) = when (where) {
    SDecideWhere.Gate -> DecideWords(S.desktop_stk_approve, S.desktop_stk_reject, S.reject, S.desktop_stk_reject_why, S.desktop_stk_why_placeholder)
    SDecideWhere.Card -> DecideWords(S.desktop_stk_keep, S.desktop_stk_discard_start, S.ah_discard, S.desktop_stk_discard_why, S.desktop_stk_why_placeholder)
    SDecideWhere.Foot -> DecideWords(S.desktop_stk_keep, S.desktop_stk_discard_ellipsis, S.ah_discard, S.desktop_stk_discard_why_all, S.desktop_stk_why_placeholder_long)
}

/**
 * Keep, discard or undo, for one of the reader's actors in one photo.
 *
 * Discard asks why first, and the question REPLACES the buttons rather than
 * opening under them: two "Discard" controls on screen at once read as two
 * different actions. One question at a time, one way to answer it.
 *
 * @param blocked no discard allowance is left for this photo's section
 */
@Composable
internal fun SDecisionBar(
    state: Decision,
    where: SDecideWhere,
    blocked: Boolean,
    busy: Boolean,
    asking: Boolean,
    note: String,
    onAsk: () -> Unit,
    onCancel: () -> Unit,
    onNote: (String) -> Unit,
    onDecide: (Decision, String) -> Unit,
    modifier: Modifier = Modifier,
    tip: String? = null,
) {
    val k = StillsTheme.c
    val words = wordsFor(where)
    val foot = where == SDecideWhere.Foot
    val spent = blocked && state != Decision.Rejected

    if (asking) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SText(str(words.why), 13, color = k.muted)
            SInput(
                value = note,
                onChange = onNote,
                placeholder = str(words.eg),
                maxLength = NOTE_LIMIT,
                lines = if (foot) 3 else 1,
                size = if (foot) 14 else 13,
                background = k.bg,
                focusBorder = if (foot) k.accent else k.warn,
                onEnter = if (foot) null else { { onDecide(Decision.Rejected, note.trim()) } },
                onKeys = { event ->
                    when {
                        // The question's own Escape: nothing behind it should hear it.
                        event.key == Key.Escape -> {
                            onCancel()
                            true
                        }
                        // The footer has room for a few lines, so Enter is a
                        // newline and the modifier confirms — as the hint says.
                        foot && event.key == Key.Enter && (event.isMetaPressed || event.isCtrlPressed) -> {
                            onDecide(Decision.Rejected, note.trim())
                            true
                        }
                        else -> false
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (foot) {
                    SBtn(if (busy) str(S.ah_saving) else str(words.confirm), { onDecide(Decision.Rejected, note.trim()) }, kind = SBtnKind.Danger, small = true, enabled = !busy)
                    SBtn(str(S.cancel), onCancel, kind = SBtnKind.Ghost, small = true, enabled = !busy)
                    SText(str(S.desktop_stk_cmd_enter), 13, color = k.muted)
                } else {
                    SChip(str(words.confirm), active = false, onClick = { onDecide(Decision.Rejected, note.trim()) }, tone = SChipTone.Solid, enabled = !busy)
                    SChip(str(S.cancel), active = false, onClick = onCancel, tone = SChipTone.Quiet, enabled = !busy)
                }
            }
        }
        return
    }

    if (foot) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SBtn(str(words.yes), { onDecide(Decision.Approved, "") }, Modifier.weight(1f), SBtnKind.Primary, enabled = !busy, on = state == Decision.Approved)
                SBtn(
                    text = str(words.no),
                    onClick = onAsk,
                    modifier = Modifier.weight(1f),
                    kind = SBtnKind.Danger,
                    enabled = !busy && !spent,
                    on = state == Decision.Rejected,
                    tooltip = if (spent) str(S.desktop_stk_allow_spent_hint) else null,
                )
                if (state != Decision.Pending) {
                    SBtn(str(S.desktop_stk_undo_small), { onDecide(Decision.Pending, "") }, kind = SBtnKind.Link, small = true, enabled = !busy)
                }
            }
            if (!tip.isNullOrBlank()) SText(tip, 12, color = k.muted)
        }
        return
    }

    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SChip(str(words.yes), state == Decision.Approved, { onDecide(Decision.Approved, "") }, tone = SChipTone.Ok, enabled = !busy)
        SChip(
            label = str(words.no),
            active = state == Decision.Rejected,
            onClick = onAsk,
            tone = SChipTone.Bad,
            hint = if (spent) str(S.desktop_stk_allow_spent_hint) else null,
            enabled = !busy && !spent,
        )
        if (state != Decision.Pending) {
            SChip(str(S.dd_rt_undo), active = false, onClick = { onDecide(Decision.Pending, "") }, tone = SChipTone.Quiet, enabled = !busy)
        }
    }
}

/** A heading above a page's body — `.stk-page h1` plus its lede. */
@Composable
internal fun SPageHead(title: String, lede: String?, modifier: Modifier = Modifier) {
    val k = StillsTheme.c
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SText(title, 26, Bold, k.ink)
        if (!lede.isNullOrBlank()) SText(lede, 15, color = k.muted, modifier = Modifier.widthIn(max = 760.dp))
    }
}

/** `.stk-card.stk-state` — a page with nothing to show, or that cannot be used. */
@Composable
internal fun SStatePanel(
    title: String,
    hint: String?,
    modifier: Modifier = Modifier,
    bad: Boolean = false,
    content: @Composable ColumnScopeAlias.() -> Unit = {},
) {
    val k = StillsTheme.c
    if (bad) {
        SAlert(SAlertTone.Error, modifier.widthIn(max = 760.dp)) {
            SText(title, 15, Bold, k.errorFg)
            if (!hint.isNullOrBlank()) SText(hint, 15, color = k.errorFg)
            content()
        }
        return
    }
    SCard(modifier.widthIn(max = 760.dp)) {
        SText(title, 19, Bold, k.ink)
        if (!hint.isNullOrBlank()) SText(hint, 15, color = k.muted, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        content()
    }
}

/** "Loading…" as the web says it. */
@Composable
internal fun SLoading(modifier: Modifier = Modifier, bare: Boolean = false) {
    val k = StillsTheme.c
    if (bare) {
        SText(str(S.ah_loading), 13, color = k.muted, modifier = modifier)
        return
    }
    SCard(modifier.fillMaxWidth()) { SText(str(S.ah_loading), 15, color = k.muted) }
}

/** Whether the tool's own box is at most [max] wide — a tool window can be narrow on a wide screen. */
@Composable
internal fun SNarrow(max: Dp, content: @Composable (Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) { content(maxWidth <= max) }
}

/** The face crop beside a row, or the dashed blank a hand-added person has. */
@Composable
internal fun SFaceCrop(url: String, modifier: Modifier = Modifier, size: Dp = 48.dp, dimmed: Boolean = false) {
    val k = StillsTheme.c
    val shape = RoundedCornerShape(8.dp)
    if (url.isBlank()) {
        Box(modifier.size(size).clip(shape).background(k.panel2).border(BorderStroke(1.dp, k.dashed), shape))
        return
    }
    SImage(
        url = url,
        modifier = modifier.size(size).clip(shape).then(if (dimmed) Modifier.alpha(0.5f) else Modifier),
        contentScale = ContentScale.Crop,
    )
}

internal const val SEARCHABLE_AT = 8
private const val NOTE_LIMIT = 1000
private val SHADE = 90.dp
private val EDGE_HEIGHT = 22.dp

/** A photo still being worked on, or one that failed — the line both places say. */
@Composable
internal fun photoStatusWords(status: PhotoStatus, errorCode: String): String =
    if (status.isFailed) str(errorKey(errorCode)) else str(S.desktop_stk_photo_processing)
