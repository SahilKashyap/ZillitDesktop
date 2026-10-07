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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlin.math.roundToInt

/**
 * "Find this person in other photos." One search brings back every stored face
 * that looks like this one and that nobody has named or dismissed yet, best
 * match first. Production ticks the ones that really are the same person and
 * names them all — or marks them all "not cast" — in one go.
 *
 * Nothing is ticked to begin with: a wrong name clears a photo without the
 * right agent ever seeing it, so each face is a deliberate choice.
 */
@Composable
internal fun SimilarPanel(state: StillsUiState, panel: SimilarState, onEvent: (StillsEvent) -> Unit) {
    val k = StillsTheme.c
    val items = panel.items
    val chosen = items.orEmpty().filter { it.key in panel.picked }

    SDialog(
        title = str(S.desktop_stk_similar_title),
        onClose = { onEvent(StillsEvent.CloseSimilar) },
        wide = true,
        busy = panel.busy,
        footer = if (items.isNullOrEmpty()) {
            null
        } else {
            {
                SText(str(S.desktop_stk_similar_picked, chosen.size, items.size), 13, color = k.muted, modifier = Modifier.weight(1f))
                SBtn(
                    text = str(S.desktop_stk_face_mark_not_cast),
                    onClick = { onEvent(StillsEvent.ApplySimilar(asMember = false)) },
                    small = true,
                    enabled = !panel.busy && chosen.isNotEmpty(),
                )
                SSelect(
                    options = state.members.sortedBy { it.name.lowercase() }.map { SOption(it.id, it.name, it.characterName) },
                    value = panel.memberId,
                    onChange = { onEvent(StillsEvent.SimilarMemberChanged(it)) },
                    modifier = Modifier.width(200.dp),
                    placeholder = str(S.desktop_stk_similar_name_as),
                    searchLabel = str(S.desktop_stk_search_members, state.members.size),
                    enabled = !panel.busy,
                    small = true,
                )
                SBtn(
                    text = if (panel.busy) str(S.ah_saving) else str(S.desktop_stk_similar_apply),
                    onClick = { onEvent(StillsEvent.ApplySimilar(asMember = true)) },
                    kind = SBtnKind.Primary,
                    small = true,
                    enabled = !panel.busy && chosen.isNotEmpty() && panel.memberId.isNotBlank(),
                )
            }
        },
    ) {
        when {
            items == null -> SLoading(bare = true)
            items.isEmpty() -> SText(str(S.desktop_stk_similar_none), 13, color = k.muted)
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SBtn(str(S.dd_select_all), { onEvent(StillsEvent.PickAllSimilar(true)) }, kind = SBtnKind.Link, small = true, enabled = !panel.busy)
                    SBtn(str(S.desktop_select_none), { onEvent(StillsEvent.PickAllSimilar(false)) }, kind = SBtnKind.Link, small = true, enabled = !panel.busy && panel.picked.isNotEmpty())
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { item ->
                        val on = item.key in panel.picked
                        val shape = RoundedCornerShape(10.dp)
                        Column(
                            Modifier
                                .width(TILE)
                                .clip(shape)
                                .background(if (on) k.accent.copy(alpha = 0.12f) else k.panel2)
                                .border(BorderStroke(2.dp, if (on) k.accent else k.line), shape)
                                .clickable(enabled = !panel.busy) { onEvent(StillsEvent.ToggleSimilar(item.key)) }
                                .padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            SImage(item.cropUrl, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)))
                            SText(
                                text = str(S.desktop_stk_face_match, item.similarity.roundToInt()),
                                size = 11,
                                color = if (on) k.accentInk else k.muted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TILE = 96.dp
