@file:Suppress("MagicNumber", "CyclomaticComplexMethod")
// One pointer loop: each branch is one kind of event the picture answers.

package com.zillit.desktop.feature.selectstills.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.selectstills.domain.ZOOM_CLICK
import com.zillit.desktop.feature.selectstills.domain.ZOOM_FIT
import com.zillit.desktop.feature.selectstills.domain.ZOOM_MAX
import com.zillit.desktop.feature.selectstills.domain.ZOOM_STEP
import com.zillit.desktop.feature.selectstills.domain.ZoomView
import com.zillit.desktop.feature.selectstills.domain.panBy
import com.zillit.desktop.feature.selectstills.domain.zoomAt
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * How far the lightbox's picture is zoomed — the web's `useZoom`.
 *
 * The wheel zooms about the pointer, a click zooms in on that spot and a
 * second click goes back to the whole photo, a drag moves a zoomed picture,
 * and [by] / [reset] serve the corner buttons and the + − 0 keys. It starts
 * again at the whole photo with every new photo (the lightbox remembers one
 * per photo) and whenever the frame changes size.
 *
 * [smooth] says the last change was a jump (a click, a button, a key) and may
 * be eased; the wheel and a drag follow the hand and are not.
 */
@Stable
internal class StillsZoomState {
    var view: ZoomView by mutableStateOf(ZOOM_FIT)
        private set
    var smooth: Boolean by mutableStateOf(false)
        private set
    private var frame: IntSize = IntSize.Zero

    /** The fitted picture's size. A new size is a new picture: back to the whole of it. */
    fun frameIs(size: IntSize) {
        if (size == frame) return
        val had = frame != IntSize.Zero
        frame = size
        if (had) {
            smooth = false
            view = ZOOM_FIT
        }
    }

    private val width: Float get() = frame.width.toFloat()
    private val height: Float get() = frame.height.toFloat()

    /** About the frame's middle: the corner buttons and the keys. */
    fun by(factor: Float) = jump { zoomAt(z * factor, width / 2, height / 2, width, height) }

    fun reset() = jump { ZOOM_FIT }

    /** A click, not a drag: in on that spot, or back out. */
    fun clickAt(point: Offset) = jump {
        if (zoomed) ZOOM_FIT else zoomAt(ZOOM_CLICK, point.x, point.y, width, height)
    }

    fun wheel(notches: Float, point: Offset) {
        smooth = false
        view = view.zoomAt(view.z * exp(-notches * WHEEL_RATE), point.x, point.y, width, height)
    }

    fun pan(from: ZoomView, delta: Offset) {
        smooth = false
        view = from.panBy(delta.x, delta.y, width, height)
    }

    private fun jump(next: ZoomView.() -> ZoomView) {
        smooth = true
        view = view.next()
    }
}

/**
 * The picture's own pointer handling: the wheel, a click, a drag. A press
 * that moves less than a few px is a click; more, and it is a drag, which
 * moves the picture only when it is zoomed. Everything it sees it consumes, so
 * the lightbox's backdrop never reads a press on the photo as "close".
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.stillsZoomGestures(zoom: StillsZoomState): Modifier = pointerInput(zoom) {
    val slop = DRAG_SLOP.toPx()
    awaitPointerEventScope {
        var down: Offset? = null
        var start = ZOOM_FIT
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            when (event.type) {
                PointerEventType.Scroll -> {
                    val notches = event.changes.fold(0f) { total, one -> total + one.scrollDelta.y }
                    if (notches != 0f) {
                        zoom.wheel(notches, change.position)
                        event.changes.forEach { it.consume() }
                    }
                }
                PointerEventType.Press -> if (event.buttons.isPrimaryPressed) {
                    down = change.position
                    start = zoom.view
                    moved = false
                    change.consume()
                }
                PointerEventType.Move -> down?.let { from ->
                    val delta = change.position - from
                    if (abs(delta.x) + abs(delta.y) > slop) moved = true
                    if (moved && start.zoomed) zoom.pan(start, delta)
                    change.consume()
                }
                PointerEventType.Release -> down?.let {
                    down = null
                    if (!moved) zoom.clickAt(change.position)
                    change.consume()
                }
                else -> Unit
            }
        }
    }
}

/**
 * `.stk-lb-zoomer` — the corner control: − · the zoom in percent (back to the
 * whole photo) · +.
 */
@Composable
internal fun StillsZoomer(zoom: StillsZoomState, modifier: Modifier = Modifier) {
    val k = StillsTheme.c
    val view = zoom.view
    val label = str(S.desktop_stk_zoom)
    Row(
        modifier
            .height(28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xB80A0A0C))
            .border(BorderStroke(1.dp, k.line), RoundedCornerShape(999.dp))
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZoomButton("−", str(S.docusign_zoom_out), enabled = view.zoomed) { zoom.by(1 / ZOOM_STEP) }
        Box(Modifier.width(1.dp).fillMaxHeight().background(k.line))
        ZoomButton(
            text = "${(view.z * 100).roundToInt()}%",
            label = str(S.desktop_stk_zoom_reset),
            enabled = view.zoomed,
            size = 12,
            minWidth = 52,
        ) { zoom.reset() }
        Box(Modifier.width(1.dp).fillMaxHeight().background(k.line))
        ZoomButton("+", str(S.docusign_zoom_in), enabled = view.z < ZOOM_MAX) { zoom.by(ZOOM_STEP) }
    }
}

@Composable
private fun ZoomButton(
    text: String,
    label: String,
    enabled: Boolean,
    size: Int = 15,
    minWidth: Int = 30,
    onClick: () -> Unit,
) {
    val k = StillsTheme.c
    val body: @Composable () -> Unit = {
        Box(
            Modifier
                .widthIn(min = minWidth.dp)
                .fillMaxHeight()
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier.alpha(DISABLED_ALPHA))
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            SText(text, size, color = k.ink, maxLines = 1)
        }
    }
    if (enabled) ZillitTooltip(text = label) { body() } else body()
}

/** A wheel notch is one; the web's `exp(-deltaY * 0.002)` over ~100px a notch. */
private const val WHEEL_RATE = 0.2f
private val DRAG_SLOP = 4.dp
private const val DISABLED_ALPHA = 0.4f
