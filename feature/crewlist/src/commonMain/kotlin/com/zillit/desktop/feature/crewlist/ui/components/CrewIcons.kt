package com.zillit.desktop.feature.crewlist.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The glyphs the crew list needs that the app's set does not carry — the
 * web's undo, redo, drag holder, swap, minus and PDF badge.
 *
 * Drawn on the same 24-point grid with the same 1.75 stroke as `ZillitIcons`,
 * so they sit beside those without looking borrowed.
 */
internal object CrewIcons {

    /** A hooked arrow turning back to the left. */
    val Undo: ImageVector = stroked("Undo") {
        moveTo(9f, 14.5f); lineTo(4f, 9.5f); lineTo(9f, 4.5f)
        moveTo(4f, 9.5f); lineTo(14.5f, 9.5f)
        curveTo(17.54f, 9.5f, 20f, 11.96f, 20f, 15f)
        curveTo(20f, 18.04f, 17.54f, 20.5f, 14.5f, 20.5f)
        lineTo(11f, 20.5f)
    }

    /** The same hook, turning right. */
    val Redo: ImageVector = stroked("Redo") {
        moveTo(15f, 14.5f); lineTo(20f, 9.5f); lineTo(15f, 4.5f)
        moveTo(20f, 9.5f); lineTo(9.5f, 9.5f)
        curveTo(6.46f, 9.5f, 4f, 11.96f, 4f, 15f)
        curveTo(4f, 18.04f, 6.46f, 20.5f, 9.5f, 20.5f)
        lineTo(13f, 20.5f)
    }

    /** Six filled dots in two columns — the grip (⠿) a section or a row is dragged by. */
    val Grip: ImageVector = ImageVector.Builder(
        name = "Grip",
        defaultWidth = VIEWPORT.dp,
        defaultHeight = VIEWPORT.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            listOf(9f, 15f).forEach { x ->
                listOf(6f, 12f, 18f).forEach { y ->
                    // A circle of radius 1.9 as two half arcs.
                    moveTo(x - DOT, y)
                    arcTo(DOT, DOT, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + DOT, y1 = y)
                    arcTo(DOT, DOT, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x - DOT, y1 = y)
                    close()
                }
            }
        }
    }.build()

    /** Two opposed arrows — trading places. */
    val Swap: ImageVector = stroked("Swap") {
        moveTo(16.5f, 3.5f); lineTo(20f, 7f); lineTo(16.5f, 10.5f)
        moveTo(20f, 7f); lineTo(4f, 7f)
        moveTo(7.5f, 13.5f); lineTo(4f, 17f); lineTo(7.5f, 20.5f)
        moveTo(4f, 17f); lineTo(20f, 17f)
    }

    val Minus: ImageVector = stroked("Minus") {
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    /** A dog-eared sheet with three ruled lines — a generated document. */
    val Document: ImageVector = stroked("Document") {
        moveTo(14f, 3.5f); lineTo(6.5f, 3.5f); lineTo(6.5f, 20.5f); lineTo(17.5f, 20.5f)
        lineTo(17.5f, 7f); close()
        moveTo(14f, 3.5f); lineTo(14f, 7f); lineTo(17.5f, 7f)
        moveTo(9f, 11f); lineTo(15f, 11f)
        moveTo(9f, 14f); lineTo(15f, 14f)
        moveTo(9f, 17f); lineTo(12.5f, 17f)
    }

    /** A camera body with its lens housing — a video call. */
    val Video: ImageVector = stroked("Video") {
        moveTo(3.5f, 7f); lineTo(15f, 7f); lineTo(15f, 17f); lineTo(3.5f, 17f); close()
        moveTo(15f, 10.5f); lineTo(20.5f, 7.5f); lineTo(20.5f, 16.5f); lineTo(15f, 13.5f)
    }

    /** A globe — an equator and one meridian — beside a dial-code picker. */
    val Globe: ImageVector = stroked("Globe") {
        moveTo(12f, 3.5f)
        curveTo(16.69f, 3.5f, 20.5f, 7.31f, 20.5f, 12f)
        curveTo(20.5f, 16.69f, 16.69f, 20.5f, 12f, 20.5f)
        curveTo(7.31f, 20.5f, 3.5f, 16.69f, 3.5f, 12f)
        curveTo(3.5f, 7.31f, 7.31f, 3.5f, 12f, 3.5f)
        close()
        moveTo(3.5f, 12f); lineTo(20.5f, 12f)
        moveTo(12f, 3.5f)
        curveTo(14.2f, 5.9f, 15.2f, 8.9f, 15.2f, 12f)
        curveTo(15.2f, 15.1f, 14.2f, 18.1f, 12f, 20.5f)
        curveTo(9.8f, 18.1f, 8.8f, 15.1f, 8.8f, 12f)
        curveTo(8.8f, 8.9f, 9.8f, 5.9f, 12f, 3.5f)
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
    private const val DOT = 1.9f
}
