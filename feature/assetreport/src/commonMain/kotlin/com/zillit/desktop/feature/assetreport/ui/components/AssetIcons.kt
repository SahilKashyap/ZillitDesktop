package com.zillit.desktop.feature.assetreport.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The few glyphs the register needs that the app's set does not carry — the
 * web's `archive`, `tag`, `list` and `coins`.
 *
 * Drawn on the same 24-point grid with the same 1.75 stroke as `ZillitIcons`,
 * so they sit beside those without looking borrowed.
 */
internal object AssetIcons {

    /** A lidded box — Keep: put it away. */
    val Archive: ImageVector = stroked("Archive") {
        moveTo(3.5f, 4.5f); lineTo(20.5f, 4.5f); lineTo(20.5f, 8.5f); lineTo(3.5f, 8.5f); close()
        moveTo(5f, 8.5f); lineTo(5f, 19.5f); lineTo(19f, 19.5f); lineTo(19f, 8.5f)
        moveTo(10f, 12.5f); lineTo(14f, 12.5f)
    }

    /** A price tag — Sell, and the expense type. */
    val Tag: ImageVector = stroked("Tag") {
        moveTo(3.5f, 3.5f); lineTo(11.5f, 3.5f); lineTo(20.5f, 12.5f)
        lineTo(12.5f, 20.5f); lineTo(3.5f, 11.5f); close()
        moveTo(8f, 7.2f); lineTo(8f, 8.8f)
    }

    /** Three ruled lines with bullets — a quantity. */
    val List: ImageVector = stroked("List") {
        moveTo(9f, 6f); lineTo(20f, 6f)
        moveTo(9f, 12f); lineTo(20f, 12f)
        moveTo(9f, 18f); lineTo(20f, 18f)
        moveTo(4.5f, 6f); lineTo(4.6f, 6f)
        moveTo(4.5f, 12f); lineTo(4.6f, 12f)
        moveTo(4.5f, 18f); lineTo(4.6f, 18f)
    }

    /** Two coins, one behind the other — a unit cost. */
    val Coins: ImageVector = stroked("Coins") {
        // The front coin: a circle at (9, 9), r 5.5, in four quarter curves.
        moveTo(9f, 3.5f)
        curveTo(12.04f, 3.5f, 14.5f, 5.96f, 14.5f, 9f)
        curveTo(14.5f, 12.04f, 12.04f, 14.5f, 9f, 14.5f)
        curveTo(5.96f, 14.5f, 3.5f, 12.04f, 3.5f, 9f)
        curveTo(3.5f, 5.96f, 5.96f, 3.5f, 9f, 3.5f)
        close()
        // The coin behind it shows only past the front one's edge.
        moveTo(16.88f, 9.83f)
        curveTo(19.2f, 10.8f, 20.5f, 12.8f, 20.5f, 15f)
        curveTo(20.5f, 18.04f, 18.04f, 20.5f, 15f, 20.5f)
        curveTo(12.8f, 20.5f, 10.8f, 19.2f, 9.83f, 16.88f)
        moveTo(8f, 7.5f); lineTo(9f, 7f); lineTo(9f, 11f)
    }

    /** A shopfront under its awning — the vendor. */
    val Store: ImageVector = stroked("Store") {
        moveTo(4f, 10f); lineTo(5.5f, 4.5f); lineTo(18.5f, 4.5f); lineTo(20f, 10f); close()
        moveTo(5.5f, 10f); lineTo(5.5f, 19.5f); lineTo(18.5f, 19.5f); lineTo(18.5f, 10f)
        moveTo(10f, 19.5f); lineTo(10f, 14.5f); lineTo(14f, 14.5f); lineTo(14f, 19.5f)
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
}
