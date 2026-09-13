@file:Suppress("MagicNumber") // Vector path coordinates, not logic.

package com.zillit.desktop.feature.maps.ui.screen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The glyphs the web's map module draws that the app set does not have — the
 * Feather icons `react-icons/fi` ships, on the app set's 24-unit grid and
 * stroke so they sit beside `ZillitIcons` without looking borrowed.
 *
 * Module-local on purpose: the app set is shared by every tool, and these are
 * only the map's.
 */
internal object MapIcons {

    val Map: ImageVector = stroked("Map") {
        moveTo(1f, 6f); lineTo(1f, 22f); lineTo(8f, 18f); lineTo(16f, 22f); lineTo(23f, 18f); lineTo(23f, 2f)
        lineTo(16f, 6f); lineTo(8f, 2f); close()
        moveTo(8f, 2f); lineTo(8f, 18f)
        moveTo(16f, 6f); lineTo(16f, 22f)
    }

    val MapPin: ImageVector = stroked("MapPin") {
        moveTo(21f, 10f)
        curveTo(21f, 17f, 12f, 23f, 12f, 23f)
        curveTo(12f, 23f, 3f, 17f, 3f, 10f)
        arcToRelative(9f, 9f, 0f, false, true, 18f, 0f)
        close()
        circle(12f, 10f, 3f)
    }

    val Target: ImageVector = stroked("Target") {
        circle(12f, 12f, 10f)
        circle(12f, 12f, 6f)
        circle(12f, 12f, 2f)
    }

    val List: ImageVector = stroked("List") {
        moveTo(8f, 6f); lineTo(21f, 6f)
        moveTo(8f, 12f); lineTo(21f, 12f)
        moveTo(8f, 18f); lineTo(21f, 18f)
        moveTo(3f, 6f); lineTo(3.01f, 6f)
        moveTo(3f, 12f); lineTo(3.01f, 12f)
        moveTo(3f, 18f); lineTo(3.01f, 18f)
    }

    val Layers: ImageVector = stroked("Layers") {
        moveTo(12f, 2f); lineTo(2f, 7f); lineTo(12f, 12f); lineTo(22f, 7f); close()
        moveTo(2f, 17f); lineTo(12f, 22f); lineTo(22f, 17f)
        moveTo(2f, 12f); lineTo(12f, 17f); lineTo(22f, 12f)
    }

    val Tag: ImageVector = stroked("Tag") {
        moveTo(20.59f, 13.41f)
        lineToRelative(-7.17f, 7.17f)
        arcToRelative(2f, 2f, 0f, false, true, -2.83f, 0f)
        lineTo(2f, 12f); lineTo(2f, 2f); lineTo(12f, 2f)
        lineToRelative(8.59f, 8.59f)
        arcToRelative(2f, 2f, 0f, false, true, 0f, 2.82f)
        close()
        moveTo(7f, 7f); lineTo(7.01f, 7f)
    }

    val Globe: ImageVector = stroked("Globe") {
        circle(12f, 12f, 10f)
        moveTo(2f, 12f); lineTo(22f, 12f)
        moveTo(12f, 2f)
        arcToRelative(15.3f, 15.3f, 0f, false, true, 4f, 10f)
        arcToRelative(15.3f, 15.3f, 0f, false, true, -4f, 10f)
        arcToRelative(15.3f, 15.3f, 0f, false, true, -4f, -10f)
        arcToRelative(15.3f, 15.3f, 0f, false, true, 4f, -10f)
        close()
    }

    val Crosshair: ImageVector = stroked("Crosshair") {
        circle(12f, 12f, 10f)
        moveTo(22f, 12f); lineTo(18f, 12f)
        moveTo(6f, 12f); lineTo(2f, 12f)
        moveTo(12f, 6f); lineTo(12f, 2f)
        moveTo(12f, 22f); lineTo(12f, 18f)
    }

    val Navigation: ImageVector = stroked("Navigation") {
        moveTo(3f, 11f); lineTo(22f, 2f); lineTo(13f, 21f); lineTo(11f, 13f); close()
    }

    val Share: ImageVector = stroked("Share") {
        circle(18f, 5f, 3f)
        circle(6f, 12f, 3f)
        circle(18f, 19f, 3f)
        moveTo(8.59f, 13.51f); lineTo(15.42f, 17.49f)
        moveTo(15.41f, 6.51f); lineTo(8.59f, 10.49f)
    }

    val EyeOff: ImageVector = stroked("EyeOff") {
        moveTo(17.94f, 17.94f)
        arcTo(10.07f, 10.07f, 0f, false, true, 12f, 20f)
        curveTo(5f, 20f, 1f, 12f, 1f, 12f)
        arcToRelative(18.45f, 18.45f, 0f, false, true, 5.06f, -5.94f)
        moveTo(9.9f, 4.24f)
        arcTo(9.12f, 9.12f, 0f, false, true, 12f, 4f)
        curveTo(19f, 4f, 23f, 12f, 23f, 12f)
        arcToRelative(18.5f, 18.5f, 0f, false, true, -2.16f, 3.19f)
        moveTo(14.12f, 14.12f)
        arcToRelative(3f, 3f, 0f, true, true, -4.24f, -4.24f)
        moveTo(1f, 1f); lineTo(23f, 23f)
    }

    val Copy: ImageVector = stroked("Copy") {
        roundRect(9f, 9f, 13f, 13f, 2f)
        moveTo(5f, 15f); lineTo(4f, 15f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
        lineTo(2f, 4f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
        lineTo(13f, 2f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
        lineTo(15f, 5f)
    }

    val Film: ImageVector = stroked("Film") {
        roundRect(2f, 2f, 20f, 20f, 2.18f)
        moveTo(7f, 2f); lineTo(7f, 22f)
        moveTo(17f, 2f); lineTo(17f, 22f)
        moveTo(2f, 12f); lineTo(22f, 12f)
        moveTo(2f, 7f); lineTo(7f, 7f)
        moveTo(2f, 17f); lineTo(7f, 17f)
        moveTo(17f, 17f); lineTo(22f, 17f)
        moveTo(17f, 7f); lineTo(22f, 7f)
    }

    val DragHandle: ImageVector = stroked("DragHandle") {
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(3f, 6f); lineTo(21f, 6f)
        moveTo(3f, 18f); lineTo(21f, 18f)
    }

    val UploadCloud: ImageVector = stroked("UploadCloud") {
        moveTo(16f, 16f); lineTo(12f, 12f); lineTo(8f, 16f)
        moveTo(12f, 12f); lineTo(12f, 21f)
        moveTo(20.39f, 18.39f)
        arcTo(5f, 5f, 0f, false, false, 18f, 9f)
        lineTo(16.74f, 9f)
        arcTo(8f, 8f, 0f, true, false, 3f, 16.3f)
    }

    val HelpCircle: ImageVector = stroked("HelpCircle") {
        circle(12f, 12f, 10f)
        moveTo(9.09f, 9f)
        arcToRelative(3f, 3f, 0f, false, true, 5.83f, 1f)
        curveToRelative(0f, 2f, -3f, 3f, -3f, 3f)
        moveTo(12f, 17f); lineTo(12.01f, 17f)
    }

    val Truck: ImageVector = stroked("Truck") {
        moveTo(1f, 3f); lineTo(16f, 3f); lineTo(16f, 16f); lineTo(1f, 16f); close()
        moveTo(16f, 8f); lineTo(20f, 8f); lineTo(23f, 11f); lineTo(23f, 16f); lineTo(16f, 16f); close()
        circle(5.5f, 18.5f, 2.5f)
        circle(18.5f, 18.5f, 2.5f)
    }

    val Disc: ImageVector = stroked("Disc") {
        circle(12f, 12f, 10f)
        circle(12f, 12f, 3f)
    }

    val Camera: ImageVector = stroked("Camera") {
        moveTo(23f, 19f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
        lineTo(3f, 21f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
        lineTo(1f, 8f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
        lineTo(7f, 6f)
        lineToRelative(2f, -3f)
        lineTo(15f, 3f)
        lineToRelative(2f, 3f)
        lineTo(21f, 6f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
        close()
        circle(12f, 13f, 4f)
    }

    val FileText: ImageVector = stroked("FileText") {
        moveTo(14f, 2f); lineTo(6f, 2f)
        arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
        lineTo(4f, 20f)
        arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
        lineTo(18f, 22f)
        arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
        lineTo(20f, 8f)
        close()
        moveTo(14f, 2f); lineTo(14f, 8f); lineTo(20f, 8f)
        moveTo(16f, 13f); lineTo(8f, 13f)
        moveTo(16f, 17f); lineTo(8f, 17f)
        moveTo(10f, 9f); lineTo(8f, 9f)
    }

    val TypeGlyph: ImageVector = stroked("Type") {
        moveTo(4f, 7f); lineTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 7f)
        moveTo(9f, 20f); lineTo(15f, 20f)
        moveTo(12f, 4f); lineTo(12f, 20f)
    }

    val Smile: ImageVector = stroked("Smile") {
        circle(12f, 12f, 10f)
        moveTo(8f, 14f)
        curveToRelative(0f, 0f, 1.5f, 2f, 4f, 2f)
        curveToRelative(2.5f, 0f, 4f, -2f, 4f, -2f)
        moveTo(9f, 9f); lineTo(9.01f, 9f)
        moveTo(15f, 9f); lineTo(15.01f, 9f)
    }

    val CheckCircle: ImageVector = stroked("CheckCircle") {
        moveTo(22f, 11.08f)
        lineTo(22f, 12f)
        arcToRelative(10f, 10f, 0f, true, true, -5.93f, -9.14f)
        moveTo(22f, 4f); lineTo(12f, 14.01f); lineTo(9f, 11.01f)
    }

    val AlertTriangle: ImageVector = stroked("AlertTriangle") {
        moveTo(10.29f, 3.86f)
        lineTo(1.82f, 18f)
        arcToRelative(2f, 2f, 0f, false, false, 1.71f, 3f)
        lineTo(20.47f, 21f)
        arcToRelative(2f, 2f, 0f, false, false, 1.71f, -3f)
        lineTo(13.71f, 3.86f)
        arcToRelative(2f, 2f, 0f, false, false, -3.42f, 0f)
        close()
        moveTo(12f, 9f); lineTo(12f, 13f)
        moveTo(12f, 17f); lineTo(12.01f, 17f)
    }

    /** A full circle as two half arcs — PathBuilder has no circle primitive. */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        moveTo(x + r, y)
        lineTo(x + w - r, y)
        arcToRelative(r, r, 0f, false, true, r, r)
        lineTo(x + w, y + h - r)
        arcToRelative(r, r, 0f, false, true, -r, r)
        lineTo(x + r, y + h)
        arcToRelative(r, r, 0f, false, true, -r, -r)
        lineTo(x, y + r)
        arcToRelative(r, r, 0f, false, true, r, -r)
        close()
    }

    private fun stroked(name: String, pathBuilder: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = VIEWPORT.dp,
            defaultHeight = VIEWPORT.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = pathBuilder,
            )
        }.build()

    private const val VIEWPORT = 24f
    private const val STROKE = 1.9f
}
