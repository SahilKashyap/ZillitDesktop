package com.zillit.desktop.feature.callsheet.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The few glyphs the call sheet needs that the app's icon set does not
 * carry, drawn to the same 24-unit, 1.75-stroke grid as `ZillitIcons`.
 */
internal object SheetIcons {

    /** A clock with a turn-back arrow — View History. */
    val History: ImageVector = stroked("History") {
        moveTo(3.5f, 12f)
        curveTo(3.5f, 7.3f, 7.3f, 3.5f, 12f, 3.5f)
        curveTo(16.7f, 3.5f, 20.5f, 7.3f, 20.5f, 12f)
        curveTo(20.5f, 16.7f, 16.7f, 20.5f, 12f, 20.5f)
        curveTo(8.9f, 20.5f, 6.2f, 18.9f, 4.7f, 16.4f)
        moveTo(3.5f, 7f); lineTo(3.5f, 12f); lineTo(8f, 12f)
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
    }

    /** A speech bubble — Comment. */
    val Comment: ImageVector = stroked("Comment") {
        moveTo(20.5f, 11.5f)
        curveTo(20.5f, 15.9f, 16.7f, 19f, 12f, 19f)
        curveTo(10.8f, 19f, 9.7f, 18.8f, 8.7f, 18.4f)
        lineTo(4f, 20f); lineTo(5.2f, 16.1f)
        curveTo(4.1f, 14.8f, 3.5f, 13.2f, 3.5f, 11.5f)
        curveTo(3.5f, 7.1f, 7.3f, 4f, 12f, 4f)
        curveTo(16.7f, 4f, 20.5f, 7.1f, 20.5f, 11.5f)
        close()
    }

    /** Three bars — the drag handle. */
    val DragHandle: ImageVector = stroked("DragHandle") {
        moveTo(5f, 7f); lineTo(19f, 7f)
        moveTo(5f, 12f); lineTo(19f, 12f)
        moveTo(5f, 17f); lineTo(19f, 17f)
    }

    /** A ruled table — the Table view. */
    val Table: ImageVector = stroked("Table") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(4f, 9.5f); lineTo(20f, 9.5f)
        moveTo(4f, 14.25f); lineTo(20f, 14.25f)
        moveTo(9.5f, 9.5f); lineTo(9.5f, 19f)
    }

    val Cloud: ImageVector = stroked("Cloud") {
        moveTo(7f, 18f)
        curveTo(4.8f, 18f, 3f, 16.2f, 3f, 14f)
        curveTo(3f, 12f, 4.5f, 10.3f, 6.4f, 10f)
        curveTo(7f, 7.1f, 9.3f, 5f, 12.2f, 5f)
        curveTo(15.2f, 5f, 17.6f, 7.2f, 18f, 10.1f)
        curveTo(19.7f, 10.4f, 21f, 11.9f, 21f, 13.8f)
        curveTo(21f, 16.1f, 19.1f, 18f, 16.8f, 18f)
        close()
    }

    /** A crosshair — use my location / coordinates. */
    val Aim: ImageVector = stroked("Aim") {
        moveTo(12f, 5f)
        curveTo(15.9f, 5f, 19f, 8.1f, 19f, 12f)
        curveTo(19f, 15.9f, 15.9f, 19f, 12f, 19f)
        curveTo(8.1f, 19f, 5f, 15.9f, 5f, 12f)
        curveTo(5f, 8.1f, 8.1f, 5f, 12f, 5f)
        close()
        moveTo(12f, 2f); lineTo(12f, 5f)
        moveTo(12f, 19f); lineTo(12f, 22f)
        moveTo(2f, 12f); lineTo(5f, 12f)
        moveTo(19f, 12f); lineTo(22f, 12f)
    }

    val Undo: ImageVector = stroked("Undo") {
        moveTo(9f, 14f); lineTo(4f, 9f); lineTo(9f, 4f)
        moveTo(4f, 9f); lineTo(14.5f, 9f)
        curveTo(17.5f, 9f, 20f, 11.5f, 20f, 14.5f)
        curveTo(20f, 17.5f, 17.5f, 20f, 14.5f, 20f)
        lineTo(11f, 20f)
    }

    /** A page with a tick. */
    val FileDone: ImageVector = stroked("FileDone") {
        moveTo(14f, 3f); lineTo(6f, 3f); lineTo(6f, 21f); lineTo(18f, 21f); lineTo(18f, 7f); close()
        moveTo(14f, 3f); lineTo(14f, 7f); lineTo(18f, 7f)
        moveTo(9f, 14f); lineTo(11f, 16f); lineTo(15f, 12f)
    }

    val Minus: ImageVector = stroked("Minus") {
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    val Swap: ImageVector = stroked("Swap") {
        moveTo(4f, 8f); lineTo(19f, 8f); lineTo(15.5f, 4.5f)
        moveTo(20f, 16f); lineTo(5f, 16f); lineTo(8.5f, 19.5f)
    }

    /** A page with an arrow up — publish. */
    val FileUpload: ImageVector = stroked("FileUpload") {
        moveTo(14f, 3f); lineTo(6f, 3f); lineTo(6f, 21f); lineTo(18f, 21f); lineTo(18f, 7f); close()
        moveTo(14f, 3f); lineTo(14f, 7f); lineTo(18f, 7f)
        moveTo(12f, 18f); lineTo(12f, 11.5f)
        moveTo(9f, 14.5f); lineTo(12f, 11.5f); lineTo(15f, 14.5f)
    }

    /** A tray — the empty state. */
    val Tray: ImageVector = stroked("Tray") {
        moveTo(3f, 13f); lineTo(6.5f, 5.5f); lineTo(17.5f, 5.5f); lineTo(21f, 13f)
        lineTo(21f, 18.5f); lineTo(3f, 18.5f); close()
        moveTo(
            3f,
            13f,
        ); lineTo(8.5f, 13f); lineTo(9.5f, 15.5f); lineTo(14.5f, 15.5f); lineTo(15.5f, 13f); lineTo(21f, 13f)
    }

    /** A pencil — "No section selected". */
    val Pencil: ImageVector = stroked("Pencil") {
        moveTo(15.2f, 5.2f); lineTo(18.8f, 8.8f)
        moveTo(16.7f, 3.7f)
        curveTo(17.7f, 2.7f, 19.3f, 2.7f, 20.3f, 3.7f)
        curveTo(21.3f, 4.7f, 21.3f, 6.3f, 20.3f, 7.3f)
        lineTo(6.5f, 21f); lineTo(3f, 21f); lineTo(3f, 17.5f); close()
    }

    /** A cloud with an arrow up — Send to Document Distribution, Publish. */
    val CloudUpload: ImageVector = stroked("CloudUpload") {
        moveTo(7f, 18f)
        curveTo(4.8f, 18f, 3f, 16.2f, 3f, 14f)
        curveTo(3f, 12f, 4.5f, 10.3f, 6.4f, 10f)
        curveTo(7f, 7.1f, 9.3f, 5f, 12.2f, 5f)
        curveTo(15.2f, 5f, 17.6f, 7.2f, 18f, 10.1f)
        curveTo(19.7f, 10.4f, 21f, 11.9f, 21f, 13.8f)
        curveTo(21f, 16.1f, 19.1f, 18f, 16.8f, 18f)
        moveTo(12f, 20f); lineTo(12f, 12f)
        moveTo(9f, 15f); lineTo(12f, 12f); lineTo(15f, 15f)
    }

    /** Three dots stacked — the row kebab (`MoreOutlined`). */
    val MoreVertical: ImageVector = stroked("MoreVertical", width = 2.6f) {
        moveTo(12f, 5.5f); lineTo(12f, 5.6f)
        moveTo(12f, 12f); lineTo(12f, 12.1f)
        moveTo(12f, 18.5f); lineTo(12f, 18.6f)
    }

    /** An id card — the Published hero's serial. */
    val IdCard: ImageVector = stroked("IdCard") {
        moveTo(3.5f, 5.5f); lineTo(20.5f, 5.5f); lineTo(20.5f, 18.5f); lineTo(3.5f, 18.5f); close()
        moveTo(9f, 11f)
        curveTo(9f, 12.1f, 8.1f, 13f, 7.5f, 13f)
        moveTo(8.5f, 9.2f)
        curveTo(9.6f, 9.2f, 10.5f, 10.1f, 10.5f, 11.2f)
        curveTo(10.5f, 12.3f, 9.6f, 13.2f, 8.5f, 13.2f)
        curveTo(7.4f, 13.2f, 6.5f, 12.3f, 6.5f, 11.2f)
        curveTo(6.5f, 10.1f, 7.4f, 9.2f, 8.5f, 9.2f)
        close()
        moveTo(5.8f, 16f); curveTo(6.3f, 14.8f, 7.3f, 14.2f, 8.5f, 14.2f)
        curveTo(9.7f, 14.2f, 10.7f, 14.8f, 11.2f, 16f)
        moveTo(13.5f, 10f); lineTo(18f, 10f)
        moveTo(13.5f, 13.5f); lineTo(17f, 13.5f)
    }

    /** A plus in a ring — the history's "Created". */
    val PlusCircle: ImageVector = stroked("PlusCircle") {
        moveTo(12f, 3.5f)
        curveTo(16.7f, 3.5f, 20.5f, 7.3f, 20.5f, 12f)
        curveTo(20.5f, 16.7f, 16.7f, 20.5f, 12f, 20.5f)
        curveTo(7.3f, 20.5f, 3.5f, 16.7f, 3.5f, 12f)
        curveTo(3.5f, 7.3f, 7.3f, 3.5f, 12f, 3.5f)
        close()
        moveTo(12f, 8f); lineTo(12f, 16f)
        moveTo(8f, 12f); lineTo(16f, 12f)
    }

    /** A pen nib — the signature pad. */
    val Pen: ImageVector = stroked("Pen") {
        moveTo(14.5f, 5.5f); lineTo(18.5f, 9.5f)
        moveTo(4f, 20f); lineTo(5.2f, 15.2f); lineTo(15.8f, 4.6f)
        curveTo(16.6f, 3.8f, 17.9f, 3.8f, 18.7f, 4.6f)
        lineTo(19.4f, 5.3f)
        curveTo(20.2f, 6.1f, 20.2f, 7.4f, 19.4f, 8.2f)
        lineTo(8.8f, 18.8f); close()
    }

    /** An eraser — Clear / Change. */
    val Eraser: ImageVector = stroked("Eraser") {
        moveTo(8.5f, 19.5f); lineTo(4.5f, 15.5f)
        curveTo(3.7f, 14.7f, 3.7f, 13.4f, 4.5f, 12.6f)
        lineTo(12.6f, 4.5f)
        curveTo(13.4f, 3.7f, 14.7f, 3.7f, 15.5f, 4.5f)
        lineTo(19.5f, 8.5f)
        curveTo(20.3f, 9.3f, 20.3f, 10.6f, 19.5f, 11.4f)
        lineTo(11.4f, 19.5f)
        moveTo(8.5f, 19.5f); lineTo(20f, 19.5f)
        moveTo(9f, 8f); lineTo(16f, 15f)
    }

    /** Two people with a plus — Send for Comments (`UsergroupAddOutlined`). */
    val UsersAdd: ImageVector = stroked("UsersAdd") {
        moveTo(9f, 11f)
        curveTo(10.9f, 11f, 12.5f, 9.4f, 12.5f, 7.5f)
        curveTo(12.5f, 5.6f, 10.9f, 4f, 9f, 4f)
        curveTo(7.1f, 4f, 5.5f, 5.6f, 5.5f, 7.5f)
        curveTo(5.5f, 9.4f, 7.1f, 11f, 9f, 11f)
        close()
        moveTo(3f, 20f)
        curveTo(3f, 16.7f, 5.7f, 14f, 9f, 14f)
        curveTo(10.4f, 14f, 11.7f, 14.5f, 12.7f, 15.3f)
        moveTo(15.5f, 4.3f)
        curveTo(16.9f, 4.8f, 18f, 6f, 18f, 7.5f)
        curveTo(18f, 9f, 16.9f, 10.2f, 15.5f, 10.7f)
        moveTo(18f, 14f); lineTo(18f, 20f)
        moveTo(15f, 17f); lineTo(21f, 17f)
    }

    private fun stroked(name: String, width: Float = STROKE, pathBuilder: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = VIEWPORT.dp,
            defaultHeight = VIEWPORT.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = pathBuilder,
            )
        }.build()

    private const val VIEWPORT = 24f
    private const val STROKE = 1.75f
}
