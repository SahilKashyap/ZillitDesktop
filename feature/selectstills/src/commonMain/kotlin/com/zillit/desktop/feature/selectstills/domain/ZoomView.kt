package com.zillit.desktop.feature.selectstills.domain

/**
 * Zooming into one photo: the arithmetic, with no screen in it — the web's
 * `lib/zoom.js`.
 *
 * A view is the picture drawn [z] times its fitted size and moved by ([x], [y])
 * px inside its frame. `z` 1 is the whole photo. The picture always covers the
 * frame, so there is never a gap to drag into.
 */
data class ZoomView(val z: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val zoomed: Boolean get() = z > ZOOM_MIN
}

/** The whole photo. */
val ZOOM_FIT: ZoomView = ZoomView()

const val ZOOM_MIN: Float = 1f
const val ZOOM_MAX: Float = 6f

/** One press of + or −, or one of the corner buttons. */
const val ZOOM_STEP: Float = 1.5f

/** Where a click on the whole photo goes in to. */
const val ZOOM_CLICK: Float = 2.5f

/** A view made legal for a frame of [width] × [height] px. */
fun ZoomView.settle(width: Float, height: Float): ZoomView {
    val zoom = z.coerceIn(ZOOM_MIN, ZOOM_MAX)
    if (zoom <= ZOOM_MIN) return ZOOM_FIT
    // "+ 0f": a frame with no size gives -0, which is not equal to 0.
    return ZoomView(
        z = zoom,
        x = x.coerceIn(minOf(0f, width * (1 - zoom)), 0f) + 0f,
        y = y.coerceIn(minOf(0f, height * (1 - zoom)), 0f) + 0f,
    )
}

/** Zoom to [to], keeping what is under ([px], [py]) — px in the frame — where it is. */
fun ZoomView.zoomAt(to: Float, px: Float, py: Float, width: Float, height: Float): ZoomView {
    val zoom = to.coerceIn(ZOOM_MIN, ZOOM_MAX)
    val ux = (px - x) / z
    val uy = (py - y) / z
    return ZoomView(zoom, px - ux * zoom, py - uy * zoom).settle(width, height)
}

/** Move a zoomed picture by ([dx], [dy]). */
fun ZoomView.panBy(dx: Float, dy: Float, width: Float, height: Float): ZoomView =
    copy(x = x + dx, y = y + dy).settle(width, height)
