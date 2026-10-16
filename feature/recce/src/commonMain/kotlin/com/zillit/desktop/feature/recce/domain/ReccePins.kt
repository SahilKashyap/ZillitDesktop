package com.zillit.desktop.feature.recce.domain

/**
 * Where a map picker opens when the place it is picking has no pin of its own
 * — the web's `recceFormModel.nearPinFor`.
 *
 * A recce is a run of places within a few miles of each other, so the best
 * guess at where the next one is, is where the last one was: the nearest
 * EARLIER stop with a pin, then the rendezvous, then any stop at all. Pure
 * so the rule is read off a test rather than off a map (the web unit-tests
 * the same function for the same reason).
 *
 * The picker's own pin is not considered here: a place that already has one
 * opens on it, and this is only asked when it does not.
 */
object ReccePins {

    /** [index] is the stop's position; `-1` asks for the rendezvous itself. */
    fun nearPin(stops: List<LatLng?>, rdv: LatLng?, index: Int = RDV_INDEX): LatLng? {
        for (earlier in (index - 1) downTo 0) {
            stops.getOrNull(earlier)?.let { return it }
        }
        // The rendezvous is the recce's anchor, but it is not "earlier" than
        // itself: asked for the rendezvous, only the stops are left to go on.
        if (index >= 0) rdv?.let { return it }
        return stops.firstOrNull { it != null }
    }

    const val RDV_INDEX = -1
}
