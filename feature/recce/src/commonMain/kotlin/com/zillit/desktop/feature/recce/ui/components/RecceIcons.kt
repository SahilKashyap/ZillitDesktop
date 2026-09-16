package com.zillit.desktop.feature.recce.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The web's `RIcon` glyphs the shared set lacks — the round map pin, the
 * train, the cloud, the flag, the fork and the navigation arrow — drawn at
 * the shared set's viewport and stroke so they sit beside `ZillitIcons`.
 *
 * Kept in the module for now: `ZillitIcons.kt` carries another session's
 * in-flight edit, and adding six paths to it would tangle the two changes.
 * They belong there once that lands.
 */
internal object RecceIcons {

    /** The web's `MapPin` — a rounded pin with a hole, not the shared set's push-pin. */
    val MapPin: ImageVector = stroked("RecceMapPin") {
        moveTo(20f, 10f)
        curveToRelative(0f, 7f, -8f, 13f, -8f, 13f)
        reflectiveCurveTo(4f, 17f, 4f, 10f)
        arcToRelative(8f, 8f, 0f, true, true, 16f, 0f)
        close()
        moveTo(15f, 10f)
        arcTo(3f, 3f, 0f, true, true, 9f, 10f)
        arcTo(3f, 3f, 0f, true, true, 15f, 10f)
    }

    val Train: ImageVector = stroked("RecceTrain") {
        roundedRect(5f, 3f, 19f, 16f, 3f)
        moveTo(5f, 10f); lineTo(19f, 10f)
        moveTo(9f, 16f); lineTo(7f, 20f)
        moveTo(15f, 16f); lineTo(17f, 20f)
        dot(8.5f, 13f)
        dot(15.5f, 13f)
    }

    val Cloud: ImageVector = stroked("RecceCloud") {
        moveTo(7f, 18f)
        arcToRelative(4f, 4f, 0f, false, true, -0.5f, -7.96f)
        arcToRelative(6f, 6f, 0f, false, true, 11.5f, -0.54f)
        arcToRelative(3.5f, 3.5f, 0f, false, true, -0.5f, 8.5f)
        horizontalLineTo(7f)
        close()
    }

    val Flag: ImageVector = stroked("RecceFlag") {
        moveTo(5f, 21f); verticalLineTo(4f)
        moveTo(5f, 4f); horizontalLineTo(16f); lineToRelative(-2f, 4f); lineToRelative(2f, 4f); horizontalLineTo(5f)
    }

    val Fork: ImageVector = stroked("RecceFork") {
        moveTo(8f, 3f); verticalLineToRelative(6f)
        arcToRelative(3f, 3f, 0f, false, false, 3f, 3f)
        verticalLineToRelative(9f)
        moveTo(11f, 3f); verticalLineToRelative(6f)
        moveTo(16f, 3f)
        curveToRelative(-1.2f, 1f, -2f, 2.5f, -2f, 4.5f)
        reflectiveCurveTo(15f, 12f, 16f, 12f)
        verticalLineToRelative(9f)
    }

    val Navigation: ImageVector = stroked("RecceNavigation") {
        moveTo(3f, 11f)
        lineToRelative(18f, -8f)
        lineToRelative(-8f, 18f)
        lineToRelative(-2.5f, -7.5f)
        lineTo(3f, 11f)
        close()
    }

    private fun PathBuilder.dot(x: Float, y: Float) {
        moveTo(x + DOT, y)
        arcTo(DOT, DOT, 0f, true, true, x - DOT, y)
        arcTo(DOT, DOT, 0f, true, true, x + DOT, y)
    }

    private fun PathBuilder.roundedRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        moveTo(left + radius, top)
        lineTo(right - radius, top)
        arcTo(radius, radius, 0f, false, true, right, top + radius)
        lineTo(right, bottom - radius)
        arcTo(radius, radius, 0f, false, true, right - radius, bottom)
        lineTo(left + radius, bottom)
        arcTo(radius, radius, 0f, false, true, left, bottom - radius)
        lineTo(left, top + radius)
        arcTo(radius, radius, 0f, false, true, left + radius, top)
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
    private const val STROKE = 1.75f
    private const val DOT = 0.6f
}
