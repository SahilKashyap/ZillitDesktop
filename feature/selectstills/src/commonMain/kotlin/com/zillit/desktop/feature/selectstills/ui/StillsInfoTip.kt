@file:Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod")
// One small control with four ways to open and three to close; the branches
// are those, kept together so they can be read side by side.

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.component.ZillitText
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * A small (i) beside something, holding what there is to know about it so the
 * page does not have to — the web's `InfoTip`. Six numbers do not go across a
 * row; they go behind one of these.
 *
 * It opens while the pointer or the keyboard's focus is on it, and a click
 * keeps it open until a click elsewhere or Escape. A moment's grace lets the
 * pointer cross from the (i) to the box. [bad] colours the (i) when what it
 * holds needs attention (an allowance that is spent). A click inside the box is
 * not a click on whatever the (i) sits in (a row that opens).
 */
@Composable
internal fun SInfoTip(
    label: String,
    title: String?,
    modifier: Modifier = Modifier,
    bad: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val k = StillsTheme.c
    var pinned by remember { mutableStateOf(false) }
    // When the box last closed on a click outside it: a click on the (i) itself
    // is one, and must not open it straight back up.
    var closedAt by remember { mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null) }
    val icon = remember { MutableInteractionSource() }
    val pop = remember { MutableInteractionSource() }
    val iconHovered by icon.collectIsHoveredAsState()
    val iconFocused by icon.collectIsFocusedAsState()
    val popHovered by pop.collectIsHoveredAsState()
    val near = iconHovered || iconFocused || popHovered
    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(near) {
        if (near) {
            lingering = true
        } else {
            delay(GRACE_MS)
            lingering = false
        }
    }
    val open = pinned || near || lingering
    val lit = pinned || iconHovered || iconFocused

    Box(modifier) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (lit) k.accent.copy(alpha = 0.12f) else k.overlay.copy(alpha = 0.06f))
                .border(BorderStroke(1.dp, if (bad) k.warn else if (lit) k.accent else k.line), CircleShape)
                .hoverable(icon)
                .focusable(interactionSource = icon)
                .clickable(interactionSource = icon, indication = null) {
                    val justClosed = closedAt?.let { it.elapsedNow().inWholeMilliseconds < REOPEN_GUARD_MS } == true
                    pinned = if (justClosed) false else !pinned
                }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && open) {
                        pinned = false
                        lingering = false
                        true
                    } else {
                        false
                    }
                }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = "i",
                style = stkText(11, FontWeight.Bold).copy(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    lineHeight = 11.sp,
                    textAlign = TextAlign.Center,
                ),
                color = if (bad) k.warn else if (lit) k.accentInk else k.muted,
            )
        }

        if (open) {
            Popup(
                popupPositionProvider = remember { UnderTheIcon() },
                onDismissRequest = {
                    if (pinned) closedAt = TimeSource.Monotonic.markNow()
                    pinned = false
                },
                properties = PopupProperties(focusable = false),
            ) {
                val shape = RoundedCornerShape(10.dp)
                Column(
                    Modifier
                        .widthIn(min = 220.dp, max = 320.dp)
                        .shadow(10.dp, shape)
                        .clip(shape)
                        .background(k.panel2)
                        .border(BorderStroke(1.dp, k.line), shape)
                        .hoverable(pop)
                        .clickable(interactionSource = pop, indication = null) { }
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                ) {
                    if (!title.isNullOrBlank()) {
                        SText(title.uppercase(), 11, Bold, k.muted, Modifier.padding(bottom = 6.dp))
                    }
                    content()
                }
            }
        }
    }
}

/** `.stk-info-rows > span` — one line of the box: what, and how many. */
@Composable
internal fun SInfoRow(label: String, value: String, bad: Boolean = false) {
    val k = StillsTheme.c
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SText(label, 13, color = k.muted, maxLines = 1)
        Spacer(Modifier.weight(1f))
        SText(value, 13, SemiBold, if (bad) k.warn else k.ink, Modifier.widthIn(max = VALUE_MAX), maxLines = 2)
    }
}

/** `.stk-info-sub` — whose lines follow, when the box holds more than one person's. */
@Composable
internal fun SInfoSub(text: String) {
    SText(text, 13, SemiBold, modifier = Modifier.padding(top = 6.dp))
}

/** `.stk-info-note` — the quiet line under a rule at the foot of the box. */
@Composable
internal fun SInfoNote(text: String) {
    val k = StillsTheme.c
    Box(Modifier.fillMaxWidth().padding(top = 7.dp).height(1.dp).background(k.line))
    SText(text, 12, color = k.muted, modifier = Modifier.padding(top = 7.dp))
}

/** The box hangs under the (i), from its left edge, and stays inside the window. */
private class UnderTheIcon : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val below = anchorBounds.bottom + GAP_PX
        val above = (anchorBounds.top - GAP_PX - popupContentSize.height).coerceAtLeast(0)
        val y = if (below + popupContentSize.height <= windowSize.height) below else above
        return IntOffset(x, y)
    }
}

private const val GRACE_MS = 140L
private const val REOPEN_GUARD_MS = 250L
private const val GAP_PX = 8
private val VALUE_MAX = 200.dp
