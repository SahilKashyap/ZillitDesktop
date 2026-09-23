package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.awt.event.MouseWheelEvent
import kotlin.math.abs

/** See the `expect` declaration for why this exists and what it deliberately leaves alone. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun rememberWheelScroll(state: ScrollableState, orientation: Orientation, reverseDirection: Boolean): Modifier {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val linePx = with(density) { WHEEL_LINE.toPx() }

    return remember(state, orientation, linePx, scope, reverseDirection) {
        // Far enough in the past that the first event is judged on its own.
        var lastPreciseAt = Long.MIN_VALUE / 2
        Modifier.onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
            val wheel = event.awtEventOrNull as? MouseWheelEvent ?: return@onPointerEvent

            // A precise rotation is a trackpad or a free-spinning wheel, which
            // the platform already accelerates properly. Left untouched — and
            // unconsumed, so it reaches the default calculation intact — and so
            // is the rest of that stream; see isTrackpadStream.
            val precise = wheel.isPreciseRotation
            if (precise) lastPreciseAt = wheel.`when`
            if (isTrackpadStream(precise, wheel.`when`, lastPreciseAt)) return@onPointerEvent

            // Only this scrollable's own axis. A plain wheel over a horizontal
            // strip reads zero here and falls through, exactly as before.
            val notches = event.changes.fold(0f) { total, change ->
                total + when (orientation) {
                    Orientation.Vertical -> change.scrollDelta.y
                    Orientation.Horizontal -> change.scrollDelta.x
                }
            }
            if (notches == 0f) return@onPointerEvent

            // Consumed on the Initial pass, before the scrollable's own handling
            // on Main — otherwise both would run and the grid would jump twice.
            event.changes.forEach { it.consume() }

            val distance = wheelScrollDistance(
                notches = notches,
                linesPerNotch = wheel.scrollAmount,
                linePx = linePx,
                reverseDirection = reverseDirection,
            )
            // Not animated: the OS already sends a stream of events for a flick,
            // and animating each one cancels the last, which loses distance and
            // stutters. Stepping per event is what a wheel does anyway.
            scope.launch { state.scrollBy(distance) }
        }
    }
}

/**
 * Whether this came from a trackpad rather than a stepped wheel.
 *
 * The same heuristic Compose uses: a stepped wheel reports the same rotation
 * both ways, a high-precision device does not.
 */
private val MouseWheelEvent.isPreciseRotation: Boolean
    get() = abs(preciseWheelRotation - wheelRotation.toDouble()) > PRECISION_EPSILON

private const val PRECISION_EPSILON = 0.001
