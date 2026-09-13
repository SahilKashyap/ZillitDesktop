package com.zillit.desktop.feature.crewlist.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Takes the presses that reach it, after its children have had theirs.
 *
 * Two uses: an editable cell inside a clickable row (a click beside an input
 * must not open the profile drawer), and a surface laid over another (a press
 * on a drawer must not reach the sheet beneath). Only presses — consuming the
 * moves too would cancel every button inside, whose tap gives up on a consumed
 * move. `clickable { }` would swallow as well, but it merges everything inside
 * into one accessibility node.
 */
internal fun Modifier.swallowPresses(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press) event.changes.forEach { it.consume() }
        }
    }
}

/** A tap on a scrim — without `clickable`'s semantics merge, for the same reason. */
internal fun Modifier.onBackdropTap(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    detectTapGestures { onTap() }
}
