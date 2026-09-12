package com.zillit.desktop.feature.bankrec.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The few glyphs the reconciliation needs that the app's set does not carry.
 *
 * Drawn on the same 24-point grid with the same 1.75 stroke as `ZillitIcons`,
 * so they sit beside those without looking borrowed.
 */
internal object BankRecIcons {

    /** Two opposed arrows — an FX payment, a swap of currencies. */
    val Swap: ImageVector = stroked("Swap") {
        moveTo(4f, 8f); lineTo(19f, 8f)
        moveTo(15f, 4f); lineTo(19f, 8f); lineTo(15f, 12f)
        moveTo(20f, 16f); lineTo(5f, 16f)
        moveTo(9f, 12f); lineTo(5f, 16f); lineTo(9f, 20f)
    }

    /** A padlock — a portal section the link does not include. */
    val Lock: ImageVector = stroked("Lock") {
        moveTo(6f, 11f); lineTo(18f, 11f); lineTo(18f, 20f); lineTo(6f, 20f); close()
        moveTo(8.5f, 11f); lineTo(8.5f, 8f)
        curveTo(8.5f, 6f, 10f, 4.5f, 12f, 4.5f)
        curveTo(14f, 4.5f, 15.5f, 6f, 15.5f, 8f)
        lineTo(15.5f, 11f)
    }

    /** Four corners out — the full view. */
    val Expand: ImageVector = stroked("Expand") {
        moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
        moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
        moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
        moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
    }

    /** Four corners in — back from the full view. */
    val Compress: ImageVector = stroked("Compress") {
        moveTo(9f, 4f); lineTo(9f, 9f); lineTo(4f, 9f)
        moveTo(15f, 4f); lineTo(15f, 9f); lineTo(20f, 9f)
        moveTo(20f, 15f); lineTo(15f, 15f); lineTo(15f, 20f)
        moveTo(4f, 15f); lineTo(9f, 15f); lineTo(9f, 20f)
    }

    /** Three bars — the quick-entry drawer. */
    val Menu: ImageVector = stroked("Menu") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17f); lineTo(20f, 17f)
    }

    /** Two overlapping sheets — copy a link. */
    val Copy: ImageVector = stroked("Copy") {
        moveTo(9f, 9f); lineTo(20f, 9f); lineTo(20f, 20f); lineTo(9f, 20f); close()
        moveTo(5f, 15f); lineTo(4f, 15f); lineTo(4f, 4f); lineTo(15f, 4f); lineTo(15f, 5f)
    }

    /** A rising line — interest, a gain. */
    val Rise: ImageVector = stroked("Rise") {
        moveTo(3f, 17f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(21f, 7f)
        moveTo(15f, 7f); lineTo(21f, 7f); lineTo(21f, 13f)
    }

    /** A bolt — auto-matching. */
    val Bolt: ImageVector = stroked("Bolt") {
        moveTo(13f, 3f); lineTo(5f, 13.5f); lineTo(11.5f, 13.5f); lineTo(10.5f, 21f); lineTo(19f, 10f)
        lineTo(12.5f, 10f); close()
    }

    /** A question mark — a suggested line, an unknown exception. */
    val Question: ImageVector = stroked("Question") {
        moveTo(9f, 9f)
        curveTo(9f, 7f, 10.5f, 5.5f, 12.2f, 5.5f)
        curveTo(14f, 5.5f, 15.3f, 6.8f, 15.3f, 8.5f)
        curveTo(15.3f, 11f, 12.2f, 11.5f, 12.2f, 14f)
        moveTo(12.2f, 18f); lineTo(12.2f, 18.3f)
    }

    /** An exclamation — an unmatched line. */
    val Exclaim: ImageVector = stroked("Exclaim") {
        moveTo(12f, 5f); lineTo(12f, 14f)
        moveTo(12f, 18f); lineTo(12f, 18.3f)
    }

    /** A pound-ish coin — money in or out with no ledger entry. */
    val Coin: ImageVector = stroked("Coin") {
        moveTo(12f, 3.5f)
        curveTo(16.7f, 3.5f, 20.5f, 7.3f, 20.5f, 12f)
        curveTo(20.5f, 16.7f, 16.7f, 20.5f, 12f, 20.5f)
        curveTo(7.3f, 20.5f, 3.5f, 16.7f, 3.5f, 12f)
        curveTo(3.5f, 7.3f, 7.3f, 3.5f, 12f, 3.5f)
        close()
        moveTo(14.5f, 9f)
        curveTo(14f, 8f, 13f, 7.5f, 12f, 7.5f)
        curveTo(10.5f, 7.5f, 9.5f, 8.4f, 9.5f, 9.6f)
        curveTo(9.5f, 12.5f, 14.5f, 11.3f, 14.5f, 14.3f)
        curveTo(14.5f, 15.6f, 13.4f, 16.5f, 12f, 16.5f)
        curveTo(10.8f, 16.5f, 9.8f, 15.9f, 9.3f, 15f)
        moveTo(12f, 6f); lineTo(12f, 7.5f)
        moveTo(12f, 16.5f); lineTo(12f, 18f)
    }

    /** A dot-ringed eye — a link's views. */
    val Views: ImageVector = stroked("Views") {
        moveTo(2.5f, 12f)
        curveTo(4.5f, 8f, 8f, 5.5f, 12f, 5.5f)
        curveTo(16f, 5.5f, 19.5f, 8f, 21.5f, 12f)
        curveTo(19.5f, 16f, 16f, 18.5f, 12f, 18.5f)
        curveTo(8f, 18.5f, 4.5f, 16f, 2.5f, 12f)
        close()
        moveTo(12f, 9.5f)
        curveTo(13.4f, 9.5f, 14.5f, 10.6f, 14.5f, 12f)
        curveTo(14.5f, 13.4f, 13.4f, 14.5f, 12f, 14.5f)
        curveTo(10.6f, 14.5f, 9.5f, 13.4f, 9.5f, 12f)
        curveTo(9.5f, 10.6f, 10.6f, 9.5f, 12f, 9.5f)
        close()
    }

    /** Two chain links — Open Banking's connection. */
    val Link: ImageVector = stroked("Link") {
        moveTo(10f, 14f)
        curveTo(11.5f, 15.5f, 13.8f, 15.5f, 15.3f, 14f)
        lineTo(18.5f, 10.8f)
        curveTo(20f, 9.3f, 20f, 7f, 18.5f, 5.5f)
        curveTo(17f, 4f, 14.7f, 4f, 13.2f, 5.5f)
        lineTo(12f, 6.7f)
        moveTo(14f, 10f)
        curveTo(12.5f, 8.5f, 10.2f, 8.5f, 8.7f, 10f)
        lineTo(5.5f, 13.2f)
        curveTo(4f, 14.7f, 4f, 17f, 5.5f, 18.5f)
        curveTo(7f, 20f, 9.3f, 20f, 10.8f, 18.5f)
        lineTo(12f, 17.3f)
    }

    /** An arrow out of a box — share a link. */
    val Share: ImageVector = stroked("Share") {
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f)
        moveTo(20f, 4f); lineTo(11f, 13f)
        moveTo(18f, 14f); lineTo(18f, 20f); lineTo(4f, 20f); lineTo(4f, 6f); lineTo(10f, 6f)
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
