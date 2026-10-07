package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A tap on the dark around a surface laid over the app — the gesture that
 * closes it.
 *
 * Every full-screen viewer in the app fills the window with a scrim and puts
 * the picture, the clip or the card in the middle of it, so "done looking" is a
 * click on the dark part, the way every lightbox on the web behaves. The cross
 * stays beside it: an exit that is only an undrawn gesture is one people back
 * out of by guessing.
 *
 * `clickable` would do the same job and merges everything inside into one
 * accessibility node — a whole viewer read out as a single run-on label — so
 * this is a plain tap detector instead. Pairs with [swallowPresses] on whatever
 * belongs *to* the surface rather than around it.
 *
 * The same pair is spelled locally in `assetreport`, `crewlist` and
 * `pagedistribution`; this is the copy shared code can reach.
 */
fun Modifier.onBackdropTap(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    detectTapGestures { onTap() }
}

/**
 * Takes the presses that reach it, after its children have had theirs — so the
 * backdrop behind does not read them as "outside".
 *
 * For a viewer's own chrome: the header carrying a name and a Download, a
 * player's transport. [onBackdropTap] sits on an ancestor and takes every press
 * its children do not, and the strip of header beside a button is exactly where
 * a click lands when someone aims at the button and misses.
 *
 * Only presses: consuming the moves as well would cancel every button inside,
 * whose tap gives up on a consumed move.
 */
fun Modifier.swallowPresses(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press) event.changes.forEach { it.consume() }
        }
    }
}
