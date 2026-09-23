package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Makes a stepped mouse wheel move a sensible distance.
 *
 * ## What is wrong without it
 *
 * Compose's macOS default is `10.dp` per wheel line — see `MacOSCocoaConfig` in
 * `foundation-desktop`. With the usual three-lines-per-notch that is 30dp a
 * notch, and a tile row in the tools grid is about 144dp, so a notch moves a
 * fifth of a row. Reaching the bottom of a forty-tool grid takes some eighty
 * notches, which reads as a page that will not scroll rather than one that
 * scrolls slowly. [WHEEL_LINE] sets the line this uses instead.
 *
 * ## Only the axis the scrollable actually runs on
 *
 * A vertical wheel puts its rotation in `y` and a shifted one in `x`, so a
 * horizontal strip reads `x` and sees nothing from a plain wheel — which is
 * exactly today's behaviour, left untouched rather than made to slide sideways.
 *
 * ## Why only the stepped wheel
 *
 * Trackpads and free-spinning wheels report a high-precision, already
 * accelerating rotation, and the platform default is tuned for exactly that.
 * Amplifying it too would make a flick uncontrollable, so those events are left
 * alone and fall through to the default untouched — the fix is aimed at the one
 * device that is badly served.
 *
 * ## Where the override happens
 *
 * Compose keeps `ScrollConfig` and its composition local internal, so there is
 * no supported way to replace the platform's calculation. This intercepts the
 * scroll event on the way *down* (`PointerEventPass.Initial`) and consumes it,
 * which the scrollable honours — it checks `isConsumed` before doing anything.
 *
 * ## Nesting
 *
 * The interception runs on the way down, so an **ancestor** always sees the
 * event before its descendants. Put this on the scrollable the wheel should
 * actually drive — the innermost one under the pointer. A container that holds
 * another scrollable must not carry it, or it will swallow the inner one's
 * wheel and the inner list will not scroll at all.
 *
 * Returns an empty modifier on any platform without a mouse wheel to fix.
 */
@Composable
expect fun rememberWheelScroll(
    state: ScrollableState,
    orientation: Orientation = Orientation.Vertical,
    /**
     * The scrollable's own `reverseLayout`. A reversed list — the chat thread,
     * newest at the bottom — runs its content backwards, and the platform's
     * scrollable flips the wheel for it; a `scrollBy` from here does not, so
     * without this a notch scrolled a reversed list the wrong way.
     */
    reverseDirection: Boolean = false,
): Modifier

/**
 * [Modifier.verticalScroll] with the wheel fix already on it.
 *
 * The plain one is still available and is the right choice for a container that
 * holds another scrollable — see the nesting note above.
 */
@Composable
fun Modifier.zillitVerticalScroll(state: ScrollState = rememberScrollState()): Modifier =
    verticalScroll(state).then(rememberWheelScroll(state, Orientation.Vertical))

/**
 * [Modifier.horizontalScroll] with the wheel fix already on it.
 *
 * A plain wheel puts nothing on this axis, so in practice this affects
 * shift-wheel only — which is the one way a mouse can drive a strip sideways.
 */
@Composable
fun Modifier.zillitHorizontalScroll(state: ScrollState = rememberScrollState()): Modifier =
    horizontalScroll(state).then(rememberWheelScroll(state, Orientation.Horizontal))

/**
 * How far one wheel *line* scrolls.
 *
 * Multiplied by the operating system's lines-per-notch setting, so a crew member
 * who has turned their scroll speed up or down still gets what they asked for —
 * this sets the size of a line, not the size of a notch. **This is the one
 * number to change if the wheel feels wrong.**
 *
 * At `10.dp` this matches Compose's own macOS figure exactly, so the
 * interception buys nothing but the loss of its smooth-scroll animation. If
 * that is where this ends up, take the modifier out rather than leave it
 * standing as a slower no-op.
 */
val WHEEL_LINE: Dp = 14.dp

/**
 * How far [notches] of wheel rotation should scroll, in pixels.
 *
 * Pure arithmetic, split out from the platform plumbing so the sign and the
 * scaling can be tested without a mouse. Positive is towards the end of the
 * content, matching `ScrollableState.scrollBy`.
 */
internal fun wheelScrollDistance(
    notches: Float,
    linesPerNotch: Int,
    linePx: Float,
    reverseDirection: Boolean = false,
): Float {
    // At least one line: a system configured with zero lines per notch would
    // otherwise consume the event and scroll nothing, which is a dead wheel.
    val distance = notches * linePx * linesPerNotch.coerceAtLeast(1)
    return if (reverseDirection) -distance else distance
}

/**
 * Whether an event belongs to a trackpad's stream and must be left alone.
 *
 * A trackpad reports fractional rotation — but not always: now and then one
 * of its events lands on a whole number and reads exactly like a notch of a
 * stepped wheel. Judged one event at a time, that event was stepped a full
 * notch (42dp) and, in the reversed chat thread, the wrong way — the thread
 * jumped and snapped back at the start of every hard swipe (reproduced live
 * 2026-09-23, three swipes, three identical 42pt jumps). So a fractional
 * event claims the stream for [TRACKPAD_GRACE_MILLIS], whole numbers included;
 * a stepped wheel never sends a fractional one and is unaffected.
 */
internal fun isTrackpadStream(isPrecise: Boolean, atMillis: Long, lastPreciseMillis: Long): Boolean =
    isPrecise || atMillis - lastPreciseMillis < TRACKPAD_GRACE_MILLIS

/**
 * How long a trackpad keeps the stream after its last fractional event. Its
 * events arrive every few milliseconds through a swipe and its momentum, so a
 * second covers the gaps without holding a mouse wheel picked up afterwards.
 */
internal const val TRACKPAD_GRACE_MILLIS = 1_000L
