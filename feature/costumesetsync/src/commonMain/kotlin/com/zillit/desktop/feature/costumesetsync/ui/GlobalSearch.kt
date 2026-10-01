package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.domain.NotificationsModel
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import kotlinx.coroutines.delay

private const val DEBOUNCE_MS = 200L
private const val COSTUME_MIN = 2
private const val COSTUME_PAGE = 8
private val MENU_WIDTH = 420.dp

/**
 * The header search across scenes, characters and costumes, as the web's `GlobalSearch`: typing opens a menu
 * of hits, each with its kind; Enter opens the first one. Scenes and characters are read when the menu opens
 * and matched here; costumes are asked for per query (8 at most, from 2 characters) — only the latest answer
 * is kept, so a slow reply to "co" cannot overwrite the one for "coat".
 */
@Composable
fun GlobalSearch(modifier: Modifier = Modifier) {
    val ctx = LocalSync.current
    var q by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    var scenes by remember { mutableStateOf(listOf<Rec>()) }
    var characters by remember { mutableStateOf(listOf<Rec>()) }
    var costumes by remember { mutableStateOf(listOf<Rec>()) }
    var anchorHeight by remember { mutableStateOf(0) }

    LaunchedEffect(q) {
        delay(DEBOUNCE_MS)
        debounced = q.trim()
    }
    // Read fresh each time the menu opens: a scene added a minute ago is findable.
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        scenes = (ctx.api.get("/scenes") as? ZillitResult.Success)?.data?.rows.orEmpty()
        characters = (ctx.api.get("/characters") as? ZillitResult.Success)?.data?.rows.orEmpty()
    }
    LaunchedEffect(open, debounced) {
        // A reply still in flight for an older query is dropped when this effect restarts.
        costumes = if (!open || debounced.length < COSTUME_MIN) {
            emptyList()
        } else {
            (ctx.api.get("/costumes", mapOf("q" to debounced, "pageSize" to COSTUME_PAGE)) as? ZillitResult.Success)?.data?.rows.orEmpty()
        }
    }
    val hits = if (debounced.isEmpty()) {
        emptyList()
    } else {
        NotificationsModel.sceneHits(scenes, debounced, t("csync_sc"), t("csync_scene")) +
            NotificationsModel.characterHits(characters, debounced, t("csync_character")) +
            NotificationsModel.costumeHits(costumes, ::tEnum, t("csync_costume"))
    }
    fun go(hit: NotificationsModel.Hit) {
        ctx.nav.go(hit.to)
        open = false
        q = ""
    }

    Box(
        modifier.onSizeChanged { anchorHeight = it.height }.onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && hits.isNotEmpty()) {
                go(hits.first())
                true
            } else {
                false
            }
        },
    ) {
        HeaderSearchBox(q, { q = it; open = true }, t("csync_gsearch_placeholder"))
        if (open && debounced.isNotEmpty()) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, anchorHeight),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = false),
            ) {
                SearchMenu(hits, ::go)
            }
        }
    }
}

@Composable
private fun SearchMenu(hits: List<NotificationsModel.Hit>, onPick: (NotificationsModel.Hit) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        Modifier.width(MENU_WIDTH).background(colors.surfaceRaised, RoundedCornerShape(MENU_RADIUS)).border(1.dp, colors.border, RoundedCornerShape(MENU_RADIUS)).padding(vertical = ZillitTheme.spacing.xs),
    ) {
        if (hits.isEmpty()) {
            ZillitText(t("csync_gsearch_no_matches"), Modifier.padding(ZillitTheme.spacing.md), color = colors.textSecondary)
        }
        hits.forEach { h ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(h) }.padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    ZillitText(h.label, style = ZillitTheme.typography.titleSmall, maxLines = 1)
                    if (h.sub.isNotBlank()) MutedText(h.sub)
                }
                StatusBadge("MUTED", h.kind)
            }
        }
    }
}

private val MENU_RADIUS = 8.dp

/** The web's antd input: white, hairline border, a leading magnifier, 32px high. */
@Composable
private fun HeaderSearchBox(value: String, onChange: (String) -> Unit, placeholder: String) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(colors.surface, shape).border(1.dp, if (focused) colors.accent else colors.border, shape).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(ZillitIcons.Search, tint = colors.textPrimary, size = 15.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) ZillitText(placeholder, style = ZillitTheme.typography.bodyMedium, color = colors.textMuted, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                interactionSource = interaction,
                textStyle = ZillitTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
