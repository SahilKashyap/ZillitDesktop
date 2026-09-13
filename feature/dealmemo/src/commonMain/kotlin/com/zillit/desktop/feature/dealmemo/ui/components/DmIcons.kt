package com.zillit.desktop.feature.dealmemo.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The web deal-memo glyphs the app's icon set has no counterpart for — the
 * antd outlines the pages lean on (`GlobalOutlined`, `HistoryOutlined`,
 * `ExportOutlined`…) — drawn in the app set's own 24 px, 1.75-stroke style so
 * they sit beside `ZillitIcons` without looking borrowed.
 */
internal object DmIcons {

    val Globe: ImageVector = stroked("DmGlobe") {
        circle(12f, 12f, 9f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(12f, 3f)
        curveTo(9.5f, 5.5f, 8.5f, 8.7f, 8.5f, 12f)
        curveTo(8.5f, 15.3f, 9.5f, 18.5f, 12f, 21f)
        moveTo(12f, 3f)
        curveTo(14.5f, 5.5f, 15.5f, 8.7f, 15.5f, 12f)
        curveTo(15.5f, 15.3f, 14.5f, 18.5f, 12f, 21f)
    }

    /** A clock with a counter-clockwise arrow — "history". */
    val History: ImageVector = stroked("DmHistory") {
        moveTo(3.5f, 12f)
        curveTo(3.5f, 7.3f, 7.3f, 3.5f, 12f, 3.5f)
        curveTo(16.7f, 3.5f, 20.5f, 7.3f, 20.5f, 12f)
        curveTo(20.5f, 16.7f, 16.7f, 20.5f, 12f, 20.5f)
        curveTo(8.9f, 20.5f, 6.2f, 18.9f, 4.7f, 16.4f)
        moveTo(3.5f, 12f); lineTo(1.8f, 10.2f)
        moveTo(3.5f, 12f); lineTo(5.3f, 10.3f)
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
    }

    /** An arrow leaving a tray. */
    val Export: ImageVector = stroked("DmExport") {
        moveTo(12f, 15f); lineTo(12f, 3.5f)
        moveTo(7.5f, 8f); lineTo(12f, 3.5f); lineTo(16.5f, 8f)
        moveTo(4f, 13f); lineTo(4f, 19f); lineTo(20f, 19f); lineTo(20f, 13f)
    }

    val Gift: ImageVector = stroked("DmGift") {
        moveTo(4f, 9f); lineTo(20f, 9f); lineTo(20f, 13f); lineTo(4f, 13f); close()
        moveTo(5.5f, 13f); lineTo(5.5f, 20.5f); lineTo(18.5f, 20.5f); lineTo(18.5f, 13f)
        moveTo(12f, 9f); lineTo(12f, 20.5f)
        moveTo(12f, 9f)
        curveTo(10f, 5f, 6.5f, 4.8f, 6.5f, 7f)
        curveTo(6.5f, 8.5f, 9f, 9f, 12f, 9f)
        curveTo(14f, 5f, 17.5f, 4.8f, 17.5f, 7f)
        curveTo(17.5f, 8.5f, 15f, 9f, 12f, 9f)
    }

    val Swap: ImageVector = stroked("DmSwap") {
        moveTo(4f, 8f); lineTo(19f, 8f)
        moveTo(15f, 4f); lineTo(19f, 8f); lineTo(15f, 12f)
        moveTo(20f, 16f); lineTo(5f, 16f)
        moveTo(9f, 12f); lineTo(5f, 16f); lineTo(9f, 20f)
    }

    /** A coin with a dollar mark — antd `DollarOutlined`. */
    val Dollar: ImageVector = stroked("DmDollar") {
        circle(12f, 12f, 9f)
        moveTo(14.8f, 9f)
        curveTo(14.4f, 8f, 13.4f, 7.4f, 12f, 7.4f)
        curveTo(10.3f, 7.4f, 9.2f, 8.3f, 9.2f, 9.6f)
        curveTo(9.2f, 12.6f, 14.9f, 11.3f, 14.9f, 14.4f)
        curveTo(14.9f, 15.7f, 13.7f, 16.6f, 12f, 16.6f)
        curveTo(10.5f, 16.6f, 9.5f, 16f, 9.1f, 15f)
        moveTo(12f, 5.8f); lineTo(12f, 7.4f)
        moveTo(12f, 16.6f); lineTo(12f, 18.2f)
    }

    val Book: ImageVector = stroked("DmBook") {
        moveTo(5f, 4.5f); lineTo(16.5f, 4.5f)
        curveTo(17.9f, 4.5f, 19f, 5.6f, 19f, 7f)
        lineTo(19f, 19.5f); lineTo(7.5f, 19.5f)
        curveTo(6.1f, 19.5f, 5f, 18.4f, 5f, 17f)
        close()
        moveTo(5f, 17f)
        curveTo(5f, 15.6f, 6.1f, 14.5f, 7.5f, 14.5f)
        lineTo(19f, 14.5f)
        moveTo(9f, 8.5f); lineTo(15f, 8.5f)
    }

    /** Stacked blocks — antd `ApartmentOutlined`, the org chart. */
    val Apartment: ImageVector = stroked("DmApartment") {
        moveTo(9.5f, 3.5f); lineTo(14.5f, 3.5f); lineTo(14.5f, 8f); lineTo(9.5f, 8f); close()
        moveTo(3f, 16f); lineTo(8f, 16f); lineTo(8f, 20.5f); lineTo(3f, 20.5f); close()
        moveTo(16f, 16f); lineTo(21f, 16f); lineTo(21f, 20.5f); lineTo(16f, 20.5f); close()
        moveTo(12f, 8f); lineTo(12f, 12f)
        moveTo(5.5f, 16f); lineTo(5.5f, 12f); lineTo(18.5f, 12f); lineTo(18.5f, 16f)
    }

    val Database: ImageVector = stroked("DmDatabase") {
        moveTo(4.5f, 6f)
        curveTo(4.5f, 4.3f, 7.9f, 3f, 12f, 3f)
        curveTo(16.1f, 3f, 19.5f, 4.3f, 19.5f, 6f)
        curveTo(19.5f, 7.7f, 16.1f, 9f, 12f, 9f)
        curveTo(7.9f, 9f, 4.5f, 7.7f, 4.5f, 6f)
        close()
        moveTo(4.5f, 6f); lineTo(4.5f, 18f)
        curveTo(4.5f, 19.7f, 7.9f, 21f, 12f, 21f)
        curveTo(16.1f, 21f, 19.5f, 19.7f, 19.5f, 18f)
        lineTo(19.5f, 6f)
        moveTo(4.5f, 12f)
        curveTo(4.5f, 13.7f, 7.9f, 15f, 12f, 15f)
        curveTo(16.1f, 15f, 19.5f, 13.7f, 19.5f, 12f)
    }

    /** A page with a magnifier — the empty queue. */
    val FileSearch: ImageVector = stroked("DmFileSearch") {
        moveTo(13f, 3f); lineTo(6f, 3f); lineTo(6f, 21f); lineTo(11f, 21f)
        moveTo(13f, 3f); lineTo(18f, 8f); lineTo(18f, 11f)
        moveTo(13f, 3f); lineTo(13f, 8f); lineTo(18f, 8f)
        circle(16f, 16f, 3f)
        moveTo(18.2f, 18.2f); lineTo(20.5f, 20.5f)
    }

    /** A page with a shield — the overview callout. */
    val FileProtect: ImageVector = stroked("DmFileProtect") {
        moveTo(13f, 3f); lineTo(6f, 3f); lineTo(6f, 21f); lineTo(11f, 21f)
        moveTo(13f, 3f); lineTo(18f, 8f); lineTo(18f, 10f)
        moveTo(13f, 3f); lineTo(13f, 8f); lineTo(18f, 8f)
        moveTo(17f, 12.5f); lineTo(20.5f, 14f); lineTo(20.5f, 16.5f)
        curveTo(20.5f, 18.7f, 19f, 20.3f, 17f, 21f)
        curveTo(15f, 20.3f, 13.5f, 18.7f, 13.5f, 16.5f)
        lineTo(13.5f, 14f); close()
    }

    /** A list with bullets — `UnorderedListOutlined`. */
    val List: ImageVector = stroked("DmList") {
        moveTo(9f, 6f); lineTo(20f, 6f)
        moveTo(9f, 12f); lineTo(20f, 12f)
        moveTo(9f, 18f); lineTo(20f, 18f)
        moveTo(4.5f, 6f); lineTo(4.6f, 6f)
        moveTo(4.5f, 12f); lineTo(4.6f, 12f)
        moveTo(4.5f, 18f); lineTo(4.6f, 18f)
    }

    /** A circle struck through — antd `StopOutlined`, the crew's Reject. */
    val Stop: ImageVector = stroked("DmStop") {
        circle(12f, 12f, 9f)
        moveTo(5.6f, 5.6f); lineTo(18.4f, 18.4f)
    }

    val Minus: ImageVector = stroked("DmMinus") {
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    /** A pen over a line — the signature. */
    val Signature: ImageVector = stroked("DmSignature") {
        moveTo(3.5f, 20.5f); lineTo(20.5f, 20.5f)
        moveTo(15.5f, 4.5f); lineTo(19f, 8f); lineTo(9f, 18f); lineTo(5f, 19f); lineTo(6f, 15f); close()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
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
}
