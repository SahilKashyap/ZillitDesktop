package com.zillit.desktop.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's icon set.
 *
 * Hand-authored rather than pulled from a library, for three reasons:
 *
 *  1. Compose Multiplatform stopped shipping Material icons after 1.7.3, and
 *     this project is on 1.11 — mixing those versions invites drift.
 *  2. A stroke set at a consistent weight reads better at desktop density than
 *     Material's filled icons, which were drawn for touch targets.
 *  3. No dependency, no unused megabytes.
 *
 * All icons share a 24×24 viewport and a 1.75 stroke so they sit together
 * cleanly at any size. Add new ones here — never inline a path at a call site.
 */
object ZillitIcons {

    /**
     * The app mark, for the system tray.
     *
     * A bare Z rather than a glyph borrowed from the tool set: the tray icon
     * stands for the whole application, and a calendar or a bell there would
     * claim the app is one of those things.
     */
    val Mark: ImageVector = stroked("Mark") {
        moveTo(7f, 6f); lineTo(17f, 6f); lineTo(7f, 18f); lineTo(17f, 18f)
    }

    // -- window chrome -----------------------------------------------------

    val Add: ImageVector = stroked("Add") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    val Close: ImageVector = stroked("Close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }

    /** A paper plane, nose right — the send action every chat client uses. */
    /** The web's paperclip (antd/feather line style): a bent clip. */
    val Paperclip: ImageVector = stroked("Paperclip") {
        moveTo(20.5f, 11.5f)
        lineTo(12.5f, 19.5f)
        curveTo(10.3f, 21.7f, 6.7f, 21.7f, 4.5f, 19.5f)
        curveTo(2.3f, 17.3f, 2.3f, 13.7f, 4.5f, 11.5f)
        lineTo(13.2f, 2.8f)
        curveTo(14.7f, 1.3f, 17.1f, 1.3f, 18.6f, 2.8f)
        curveTo(20.1f, 4.3f, 20.1f, 6.7f, 18.6f, 8.2f)
        lineTo(9.9f, 16.9f)
        curveTo(9.1f, 17.7f, 7.9f, 17.7f, 7.1f, 16.9f)
        curveTo(6.3f, 16.1f, 6.3f, 14.9f, 7.1f, 14.1f)
        lineTo(15f, 6.2f)
    }

    /** A file page with a folded corner — the web's document glyph. */
    val File: ImageVector = stroked("File") {
        moveTo(14f, 3f); lineTo(6f, 3f); lineTo(6f, 21f); lineTo(18f, 21f); lineTo(18f, 7f); close()
        moveTo(14f, 3f); lineTo(14f, 7f); lineTo(18f, 7f)
    }

    /** Waiting to leave this device. */
    val Clock: ImageVector = stroked("Clock") {
        moveTo(12f, 3f)
        curveTo(7f, 3f, 3f, 7f, 3f, 12f)
        curveTo(3f, 17f, 7f, 21f, 12f, 21f)
        curveTo(17f, 21f, 21f, 17f, 21f, 12f)
        curveTo(21f, 7f, 17f, 3f, 12f, 3f)
        close()
        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f)
    }

    /** Sent: the server has it. */
    val Tick: ImageVector = stroked("Tick") {
        moveTo(4.5f, 12.5f); lineTo(9.5f, 17.5f); lineTo(19.5f, 6.5f)
    }

    /** Delivered, and — tinted — read. */
    val DoubleTick: ImageVector = stroked("DoubleTick") {
        moveTo(2f, 12.5f); lineTo(6.5f, 17.5f); lineTo(15.5f, 7f)
        moveTo(9.5f, 15.2f); lineTo(12.2f, 17.5f); lineTo(22f, 7f)
    }

    val Send: ImageVector = stroked("Send") {
        moveTo(4f, 11.5f); lineTo(20f, 4f); lineTo(13f, 20f); lineTo(10.5f, 13.5f); close()
        moveTo(10.5f, 13.5f); lineTo(20f, 4f)
    }

    val Minimize: ImageVector = stroked("Minimize") {
        moveTo(6f, 12f); lineTo(18f, 12f)
    }

    val Maximize: ImageVector = stroked("Maximize") {
        moveTo(5f, 5f); lineTo(19f, 5f); lineTo(19f, 19f); lineTo(5f, 19f); close()
    }

    val Restore: ImageVector = stroked("Restore") {
        moveTo(8f, 8f); lineTo(19f, 8f); lineTo(19f, 19f); lineTo(8f, 19f); close()
        moveTo(5f, 16f); lineTo(5f, 5f); lineTo(16f, 5f)
    }

    val Pin: ImageVector = stroked("Pin") {
        moveTo(12f, 14f); lineTo(12f, 21f)
        moveTo(8f, 3f); lineTo(16f, 3f); lineTo(14.5f, 10f); lineTo(17f, 14f); lineTo(7f, 14f)
        lineTo(9.5f, 10f); close()
    }

    /** Tabs layout — one filled workspace. */
    val LayoutTabs: ImageVector = stroked("LayoutTabs") {
        moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(4f, 10f); lineTo(20f, 10f)
    }

    /** Cascade layout — overlapping free windows. */
    val LayoutCascade: ImageVector = stroked("LayoutCascade") {
        moveTo(4f, 4f); lineTo(15f, 4f); lineTo(15f, 15f); lineTo(4f, 15f); close()
        moveTo(9f, 9f); lineTo(20f, 9f); lineTo(20f, 20f); lineTo(9f, 20f); close()
    }

    val Detach: ImageVector = stroked("Detach") {
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f)
        moveTo(20f, 4f); lineTo(12f, 12f)
        moveTo(18f, 14f); lineTo(18f, 20f); lineTo(4f, 20f); lineTo(4f, 6f); lineTo(10f, 6f)
    }

    // -- navigation rail ---------------------------------------------------

    val Home: ImageVector = stroked("Home") {
        moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f)
        moveTo(6f, 10f); lineTo(6f, 20f); lineTo(18f, 20f); lineTo(18f, 10f)
    }

    val Chat: ImageVector = stroked("Chat") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(11f, 16f); lineTo(7f, 20f)
        lineTo(7f, 16f); lineTo(4f, 16f); close()
    }

    val Mail: ImageVector = stroked("Mail") {
        moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close()
        moveTo(3f, 7f); lineTo(12f, 13f); lineTo(21f, 7f)
    }

    val Calendar: ImageVector = stroked("Calendar") {
        moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        moveTo(4f, 10f); lineTo(20f, 10f)
        moveTo(8f, 3f); lineTo(8f, 7f)
        moveTo(16f, 3f); lineTo(16f, 7f)
    }

    /**
     * A wrench. The four-square grid it replaces is the *layout* idiom, and
     * having it mean "Film Tools" in the rail while also meaning "tiles" in the
     * workspace made two different things look identical.
     */
    val Tools: ImageVector = stroked("Tools") {
        moveTo(15.5f, 3.5f)
        curveTo(13.6f, 3.5f, 12f, 5.1f, 12f, 7f)
        curveTo(12f, 7.6f, 12.2f, 8.2f, 12.4f, 8.7f)
        lineTo(4.2f, 16.9f)
        curveTo(3.6f, 17.5f, 3.6f, 18.5f, 4.2f, 19.1f)
        curveTo(4.8f, 19.7f, 5.8f, 19.7f, 6.4f, 19.1f)
        lineTo(14.6f, 10.9f)
        curveTo(15.1f, 11.1f, 15.7f, 11.3f, 16.3f, 11.3f)
        curveTo(18.2f, 11.3f, 19.8f, 9.7f, 19.8f, 7.8f)
        curveTo(19.8f, 7.2f, 19.6f, 6.6f, 19.4f, 6.1f)
        lineTo(17.1f, 8.4f)
        lineTo(14.9f, 6.2f)
        lineTo(17.2f, 3.9f)
        curveTo(16.7f, 3.6f, 16.1f, 3.5f, 15.5f, 3.5f)
        close()
    }

    val Transport: ImageVector = stroked("Transport") {
        moveTo(3f, 7f); lineTo(14f, 7f); lineTo(14f, 16f); lineTo(3f, 16f); close()
        moveTo(14f, 10f); lineTo(18f, 10f); lineTo(21f, 13f); lineTo(21f, 16f); lineTo(14f, 16f)
        moveTo(7f, 16f); arcToRelative(2f, 2f, 0f, true, false, 0.1f, 0f)
        moveTo(17f, 16f); arcToRelative(2f, 2f, 0f, true, false, 0.1f, 0f)
    }

    /**
     * A gear, not a starburst.
     *
     * The old glyph — a ring with eight rays — is the universal sign for
     * *brightness*, which put a sun in the rail next to the theme switch that
     * really is one. Teeth read as settings in every toolkit; rays do not.
     */
    val Settings: ImageVector = stroked("Settings") {
        moveTo(12f, 9f); arcToRelative(3f, 3f, 0f, true, false, 0.1f, 0f)
        moveTo(19.4f, 15f)
        curveTo(19.2f, 15.5f, 19.3f, 16.1f, 19.7f, 16.5f)
        lineTo(19.8f, 16.6f)
        curveTo(20.4f, 17.2f, 20.4f, 18.1f, 19.8f, 18.7f)
        curveTo(19.2f, 19.3f, 18.3f, 19.3f, 17.7f, 18.7f)
        lineTo(17.6f, 18.6f)
        curveTo(17.2f, 18.2f, 16.6f, 18.1f, 16.1f, 18.3f)
        curveTo(15.6f, 18.5f, 15.3f, 19f, 15.3f, 19.5f)
        lineTo(15.3f, 19.8f)
        curveTo(15.3f, 20.6f, 14.6f, 21.3f, 13.8f, 21.3f)
        curveTo(13f, 21.3f, 12.3f, 20.6f, 12.3f, 19.8f)
        lineTo(12.3f, 19.7f)
        curveTo(12.3f, 19.1f, 11.9f, 18.6f, 11.4f, 18.4f)
        curveTo(10.9f, 18.2f, 10.3f, 18.3f, 9.9f, 18.7f)
        lineTo(9.8f, 18.8f)
        curveTo(9.2f, 19.4f, 8.3f, 19.4f, 7.7f, 18.8f)
        curveTo(7.1f, 18.2f, 7.1f, 17.3f, 7.7f, 16.7f)
        lineTo(7.8f, 16.6f)
        curveTo(8.2f, 16.2f, 8.3f, 15.6f, 8.1f, 15.1f)
        curveTo(7.9f, 14.6f, 7.4f, 14.3f, 6.9f, 14.3f)
        lineTo(6.6f, 14.3f)
        curveTo(5.8f, 14.3f, 5.1f, 13.6f, 5.1f, 12.8f)
        curveTo(5.1f, 12f, 5.8f, 11.3f, 6.6f, 11.3f)
        lineTo(6.7f, 11.3f)
        curveTo(7.3f, 11.3f, 7.8f, 10.9f, 8f, 10.4f)
        curveTo(8.2f, 9.9f, 8.1f, 9.3f, 7.7f, 8.9f)
        lineTo(7.6f, 8.8f)
        curveTo(7f, 8.2f, 7f, 7.3f, 7.6f, 6.7f)
        curveTo(8.2f, 6.1f, 9.1f, 6.1f, 9.7f, 6.7f)
        lineTo(9.8f, 6.8f)
        curveTo(10.2f, 7.2f, 10.8f, 7.3f, 11.3f, 7.1f)
        lineTo(11.4f, 7.1f)
        curveTo(11.9f, 6.9f, 12.2f, 6.4f, 12.2f, 5.9f)
        lineTo(12.2f, 5.6f)
        curveTo(12.2f, 4.8f, 12.9f, 4.1f, 13.7f, 4.1f)
        curveTo(14.5f, 4.1f, 15.2f, 4.8f, 15.2f, 5.6f)
        lineTo(15.2f, 5.7f)
        curveTo(15.2f, 6.3f, 15.6f, 6.8f, 16.1f, 7f)
        curveTo(16.6f, 7.2f, 17.2f, 7.1f, 17.6f, 6.7f)
        lineTo(17.7f, 6.6f)
        curveTo(18.3f, 6f, 19.2f, 6f, 19.8f, 6.6f)
        curveTo(20.4f, 7.2f, 20.4f, 8.1f, 19.8f, 8.7f)
        lineTo(19.7f, 8.8f)
        curveTo(19.3f, 9.2f, 19.2f, 9.8f, 19.4f, 10.3f)
        lineTo(19.4f, 10.4f)
        curveTo(19.6f, 10.9f, 20.1f, 11.2f, 20.6f, 11.2f)
        lineTo(20.9f, 11.2f)
        curveTo(21.7f, 11.2f, 22.4f, 11.9f, 22.4f, 12.7f)
        curveTo(22.4f, 13.5f, 21.7f, 14.2f, 20.9f, 14.2f)
        lineTo(20.8f, 14.2f)
        curveTo(20.2f, 14.2f, 19.7f, 14.6f, 19.5f, 15.1f)
    }

    /** A folder with its tab drawn where the eye expects it — top left. */
    val Drive: ImageVector = stroked("Drive") {
        moveTo(3f, 19f); lineTo(3f, 6f); lineTo(9.5f, 6f); lineTo(11.5f, 9f)
        lineTo(21f, 9f); lineTo(21f, 19f); close()
    }

    // -- general -----------------------------------------------------------

    val Smiley: ImageVector = stroked("Smiley") {
        // Face circle.
        moveTo(12f, 3f)
        curveTo(7f, 3f, 3f, 7f, 3f, 12f)
        curveTo(3f, 17f, 7f, 21f, 12f, 21f)
        curveTo(17f, 21f, 21f, 17f, 21f, 12f)
        curveTo(21f, 7f, 17f, 3f, 12f, 3f)
        close()
        // Eyes.
        moveTo(9f, 10f); lineTo(9f, 10.01f)
        moveTo(15f, 10f); lineTo(15f, 10.01f)
        // Smile.
        moveTo(8.5f, 14.5f)
        curveTo(9.5f, 16f, 10.7f, 16.5f, 12f, 16.5f)
        curveTo(13.3f, 16.5f, 14.5f, 16f, 15.5f, 14.5f)
    }

    val Play: ImageVector = filled("Play") {
        moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close()
    }

    val Pause: ImageVector = stroked("Pause") {
        moveTo(9f, 5f); lineTo(9f, 19f)
        moveTo(15f, 5f); lineTo(15f, 19f)
    }

    val Mic: ImageVector = stroked("Mic") {
        // Capsule body, stand, base — the universal microphone glyph.
        moveTo(12f, 3f)
        curveTo(10.3f, 3f, 9f, 4.3f, 9f, 6f)
        lineTo(9f, 11f)
        curveTo(9f, 12.7f, 10.3f, 14f, 12f, 14f)
        curveTo(13.7f, 14f, 15f, 12.7f, 15f, 11f)
        lineTo(15f, 6f)
        curveTo(15f, 4.3f, 13.7f, 3f, 12f, 3f)
        close()
        moveTo(6f, 11f)
        curveTo(6f, 14.3f, 8.7f, 17f, 12f, 17f)
        curveTo(15.3f, 17f, 18f, 14.3f, 18f, 11f)
        moveTo(12f, 17f); lineTo(12f, 21f)
        moveTo(9f, 21f); lineTo(15f, 21f)
    }

    val Search: ImageVector = stroked("Search") {
        moveTo(11f, 4f); arcToRelative(7f, 7f, 0f, true, false, 0.1f, 0f)
        moveTo(16f, 16f); lineTo(21f, 21f)
    }

    val User: ImageVector = stroked("User") {
        moveTo(12f, 4f); arcToRelative(4f, 4f, 0f, true, false, 0.1f, 0f)
        moveTo(4f, 21f); curveTo(4f, 16f, 8f, 14f, 12f, 14f)
        curveTo(16f, 14f, 20f, 16f, 20f, 21f)
    }

    val ChevronDown: ImageVector = stroked("ChevronDown") {
        moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
    }

    val ChevronLeft: ImageVector = stroked("ChevronLeft") {
        moveTo(15f, 5f); lineTo(9f, 12f); lineTo(15f, 19f)
    }

    val ChevronRight: ImageVector = stroked("ChevronRight") {
        moveTo(9f, 5f); lineTo(15f, 12f); lineTo(9f, 19f)
    }

    // -- theme -------------------------------------------------------------

    val Sun: ImageVector = stroked("Sun") {
        moveTo(12f, 8f); arcToRelative(4f, 4f, 0f, true, false, 0.1f, 0f)
        moveTo(12f, 2f); lineTo(12f, 4f)
        moveTo(12f, 20f); lineTo(12f, 22f)
        moveTo(2f, 12f); lineTo(4f, 12f)
        moveTo(20f, 12f); lineTo(22f, 12f)
        moveTo(5f, 5f); lineTo(6.5f, 6.5f)
        moveTo(17.5f, 17.5f); lineTo(19f, 19f)
        moveTo(19f, 5f); lineTo(17.5f, 6.5f)
        moveTo(6.5f, 17.5f); lineTo(5f, 19f)
    }

    val Moon: ImageVector = stroked("Moon") {
        moveTo(20f, 14f)
        curveTo(18.5f, 17.5f, 15f, 20f, 11.5f, 20f)
        curveTo(7f, 20f, 4f, 16.5f, 4f, 12f)
        curveTo(4f, 8f, 6.5f, 4.5f, 10f, 3.5f)
        curveTo(8.5f, 7f, 9.5f, 11.5f, 13f, 13.5f)
        curveTo(15f, 14.6f, 17.8f, 14.8f, 20f, 14f)
        close()
    }

    /** Tick — checkbox, confirmation. */
    val Check: ImageVector = stroked("Check") {
        moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 6.5f)
    }

    /** Favourite, set. Drawn filled by tinting a closed star path. */
    val StarFilled: ImageVector = filled("StarFilled") {
        moveTo(12f, 3.5f)
        lineTo(14.6f, 9f); lineTo(20.5f, 9.8f); lineTo(16.2f, 14f)
        lineTo(17.3f, 20f); lineTo(12f, 17.1f); lineTo(6.7f, 20f)
        lineTo(7.8f, 14f); lineTo(3.5f, 9.8f); lineTo(9.4f, 9f)
        close()
    }

    /** Favourite, unset. Same path, stroked. */
    val StarOutline: ImageVector = stroked("StarOutline") {
        moveTo(12f, 3.5f)
        lineTo(14.6f, 9f); lineTo(20.5f, 9.8f); lineTo(16.2f, 14f)
        lineTo(17.3f, 20f); lineTo(12f, 17.1f); lineTo(6.7f, 20f)
        lineTo(7.8f, 14f); lineTo(3.5f, 9.8f); lineTo(9.4f, 9f)
        close()
    }

    /** Details, metadata. */
    val Info: ImageVector = stroked("Info") {
        // The ring as two half-arcs, like Clock: one arc between two
        // diametrically opposite points is exactly a semicircle whichever way
        // the large-arc flag points — which is how this icon shipped as a "(".
        moveTo(12f, 3f)
        arcTo(9f, 9f, 0f, true, true, 12f, 21f)
        arcTo(9f, 9f, 0f, true, true, 12f, 3f)
        close()
        moveTo(12f, 11f); lineTo(12f, 16f)
        moveTo(12f, 7.6f); lineTo(12f, 8f)
    }

    /** Circular arrow — regenerate, retry. */
    val Reload: ImageVector = stroked("Reload") {
        // Two three-quarter arcs with arrowheads, so the direction reads at
        // 64dp as well as at 16dp.
        moveTo(20f, 12f)
        arcTo(8f, 8f, 0f, true, false, 17.66f, 17.66f)
        moveTo(20f, 6f); lineTo(20f, 12f); lineTo(14f, 12f)
    }

    val Monitor: ImageVector = stroked("Monitor") {
        moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 16f); lineTo(3f, 16f); close()
        moveTo(8f, 20f); lineTo(16f, 20f)
        moveTo(12f, 16f); lineTo(12f, 20f)
    }

    // -- construction ------------------------------------------------------

    private const val VIEWPORT = 24f
    private const val STROKE = 1.75f

    /**
     * Builds a stroke-only icon on the shared 24×24 grid.
     *
     * Stroke colour is [Color.Unspecified] so `tint` at the call site controls
     * it — an icon that hardcoded a colour would be wrong in one of the two
     * themes.
     */
    /**
     * Builds a filled icon on the same grid.
     *
     * Fill colour is [Color.Unspecified] for the same reason strokes are —
     * `tint` at the call site owns it.
     */
    private fun filled(name: String, pathBuilder: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = VIEWPORT.dp,
            defaultHeight = VIEWPORT.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = pathBuilder)
        }.build()

    val Phone: ImageVector = stroked("Phone") {
        // The classic handset: earpiece hook top-left, mouthpiece bottom-right.
        moveTo(5f, 4f)
        lineTo(8.5f, 4f)
        lineTo(10.5f, 9f)
        lineTo(8f, 10.5f)
        curveTo(9.1f, 12.9f, 11.1f, 14.9f, 13.5f, 16f)
        lineTo(15f, 13.5f)
        lineTo(20f, 15.5f)
        lineTo(20f, 19f)
        curveTo(20f, 20.1f, 19.1f, 21f, 18f, 21f)
        curveTo(10.3f, 20.4f, 3.6f, 13.7f, 3f, 6f)
        curveTo(3f, 4.9f, 3.9f, 4f, 5f, 4f)
        close()
    }

    val PhoneDown: ImageVector = stroked("PhoneDown") {
        // A handset laid horizontally: the "hang up" red-button glyph.
        moveTo(3f, 14f)
        curveTo(3f, 12f, 7f, 10f, 12f, 10f)
        curveTo(17f, 10f, 21f, 12f, 21f, 14f)
        moveTo(3f, 14f)
        lineTo(4.5f, 16.5f)
        lineTo(8.5f, 15f)
        lineTo(8.5f, 12.2f)
        moveTo(21f, 14f)
        lineTo(19.5f, 16.5f)
        lineTo(15.5f, 15f)
        lineTo(15.5f, 12.2f)
    }

    val MicOff: ImageVector = stroked("MicOff") {
        // The capsule with the diagonal bar of every mute toggle.
        moveTo(9f, 6f)
        curveTo(9f, 4.3f, 10.3f, 3f, 12f, 3f)
        curveTo(13.7f, 3f, 15f, 4.3f, 15f, 6f)
        lineTo(15f, 11f)
        moveTo(9f, 9f)
        lineTo(9f, 11f)
        curveTo(9f, 12.7f, 10.3f, 14f, 12f, 14f)
        moveTo(6f, 11f)
        curveTo(6f, 14.3f, 8.7f, 17f, 12f, 17f)
        curveTo(13.6f, 17f, 15f, 16.4f, 16.1f, 15.4f)
        moveTo(18f, 11f)
        curveTo(18f, 12f, 17.8f, 12.9f, 17.3f, 13.7f)
        moveTo(12f, 17f); lineTo(12f, 21f)
        moveTo(4f, 4f); lineTo(20f, 20f)
    }

    /**
     * A picture: frame, sun, ridge.
     *
     * Distinct from [Camera], which is a camcorder and means video. An image
     * attachment marked with a camcorder reads as a clip that failed to play —
     * the two sit next to each other on the board's file chips, so they cannot
     * share a glyph.
     */
    val Photo: ImageVector = stroked("Photo") {
        // Frame.
        moveTo(5f, 4f)
        lineTo(19f, 4f)
        curveTo(20.1f, 4f, 21f, 4.9f, 21f, 6f)
        lineTo(21f, 18f)
        curveTo(21f, 19.1f, 20.1f, 20f, 19f, 20f)
        lineTo(5f, 20f)
        curveTo(3.9f, 20f, 3f, 19.1f, 3f, 18f)
        lineTo(3f, 6f)
        curveTo(3f, 4.9f, 3.9f, 4f, 5f, 4f)
        close()
        // Sun.
        moveTo(9.4f, 8.6f)
        curveTo(9.4f, 9.37f, 8.77f, 10f, 8f, 10f)
        curveTo(7.23f, 10f, 6.6f, 9.37f, 6.6f, 8.6f)
        curveTo(6.6f, 7.83f, 7.23f, 7.2f, 8f, 7.2f)
        curveTo(8.77f, 7.2f, 9.4f, 7.83f, 9.4f, 8.6f)
        close()
        // Ridge.
        moveTo(4f, 17.5f)
        lineTo(9f, 12.5f)
        lineTo(12f, 15.5f)
        lineTo(15.5f, 12f)
        lineTo(20f, 16.5f)
    }

    val Camera: ImageVector = stroked("Camera") {
        // A video camera: body plus the lens wedge.
        moveTo(4f, 7f)
        lineTo(14f, 7f)
        curveTo(15.1f, 7f, 16f, 7.9f, 16f, 9f)
        lineTo(16f, 15f)
        curveTo(16f, 16.1f, 15.1f, 17f, 14f, 17f)
        lineTo(4f, 17f)
        curveTo(2.9f, 17f, 2f, 16.1f, 2f, 15f)
        lineTo(2f, 9f)
        curveTo(2f, 7.9f, 2.9f, 7f, 4f, 7f)
        close()
        moveTo(16f, 10.5f)
        lineTo(21f, 8f)
        lineTo(21f, 16f)
        lineTo(16f, 13.5f)
    }

    val CameraOff: ImageVector = stroked("CameraOff") {
        moveTo(16f, 10.5f)
        lineTo(21f, 8f)
        lineTo(21f, 16f)
        lineTo(16f, 13.5f)
        moveTo(16f, 9f)
        curveTo(16f, 7.9f, 15.1f, 7f, 14f, 7f)
        lineTo(8f, 7f)
        moveTo(4.5f, 7.5f)
        curveTo(3.1f, 7.7f, 2f, 8.6f, 2f, 9f)
        lineTo(2f, 15f)
        curveTo(2f, 16.1f, 2.9f, 17f, 4f, 17f)
        lineTo(13f, 17f)
        moveTo(3f, 4f); lineTo(20f, 20f)
    }

    // -- finance -----------------------------------------------------------
    //
    // The Account Hub tools (cash expenses, card expenses) are the first
    // surfaces dense enough to need a vocabulary beyond the chrome set: a
    // sidebar of fourteen destinations reads as fourteen identical rows
    // without them. Drawn at the same 24×24 / 1.75-stroke as everything above
    // so they sit beside the existing icons rather than beside each other.

    /** A billfold — floats, balances, anything holding money. */
    val Wallet: ImageVector = stroked("Wallet") {
        moveTo(3f, 7.5f)
        curveTo(3f, 6.4f, 3.9f, 5.5f, 5f, 5.5f)
        lineTo(17.5f, 5.5f)
        moveTo(3f, 7.5f)
        lineTo(3f, 17f)
        curveTo(3f, 18.1f, 3.9f, 19f, 5f, 19f)
        lineTo(19f, 19f)
        curveTo(20.1f, 19f, 21f, 18.1f, 21f, 17f)
        lineTo(21f, 10.5f)
        curveTo(21f, 9.4f, 20.1f, 8.5f, 19f, 8.5f)
        lineTo(5f, 8.5f)
        curveTo(3.9f, 8.5f, 3f, 7.6f, 3f, 7.5f)
        close()
        moveTo(17f, 13.75f)
        lineTo(17.01f, 13.75f)
    }

    /** A till receipt with a torn foot — claims, evidence, attachments. */
    val Receipt: ImageVector = stroked("Receipt") {
        moveTo(6f, 3f)
        lineTo(18f, 3f)
        lineTo(18f, 21f)
        lineTo(15.6f, 19.2f)
        lineTo(13.2f, 21f)
        lineTo(10.8f, 19.2f)
        lineTo(8.4f, 21f)
        lineTo(6f, 19.2f)
        close()
        moveTo(9f, 8f); lineTo(15f, 8f)
        moveTo(9f, 12f); lineTo(15f, 12f)
    }

    /** A classical facade — banks, companies, the ledger side of the tools. */
    val Bank: ImageVector = stroked("Bank") {
        moveTo(3.5f, 9.5f); lineTo(12f, 4f); lineTo(20.5f, 9.5f)
        moveTo(5.5f, 10.5f); lineTo(5.5f, 17.5f)
        moveTo(10f, 10.5f); lineTo(10f, 17.5f)
        moveTo(14f, 10.5f); lineTo(14f, 17.5f)
        moveTo(18.5f, 10.5f); lineTo(18.5f, 17.5f)
        moveTo(3.5f, 20f); lineTo(20.5f, 20f)
    }

    /** Three columns — analytics, spend breakdowns, reports. */
    val BarChart: ImageVector = stroked("BarChart") {
        moveTo(4f, 20f); lineTo(20f, 20f)
        moveTo(7.5f, 20f); lineTo(7.5f, 12f)
        moveTo(12f, 20f); lineTo(12f, 6.5f)
        moveTo(16.5f, 20f); lineTo(16.5f, 15f)
    }

    /** Alerts — the card module's smart-alert queue. */
    val Bell: ImageVector = stroked("Bell") {
        moveTo(6f, 10f)
        curveTo(6f, 6.7f, 8.7f, 4f, 12f, 4f)
        curveTo(15.3f, 4f, 18f, 6.7f, 18f, 10f)
        lineTo(18f, 15f)
        lineTo(20f, 17.5f)
        lineTo(4f, 17.5f)
        lineTo(6f, 15f)
        close()
        moveTo(10f, 20f)
        curveTo(10.5f, 20.9f, 11.2f, 21f, 12f, 21f)
        curveTo(12.8f, 21f, 13.5f, 20.9f, 14f, 20f)
    }

    /** A funnel — every queue in these tools filters. */
    val Filter: ImageVector = stroked("Filter") {
        moveTo(3.5f, 5f); lineTo(20.5f, 5f); lineTo(14f, 12.5f); lineTo(14f, 19.5f)
        lineTo(10f, 17.5f); lineTo(10f, 12.5f); close()
    }

    /** Export — a register leaving as PDF or spreadsheet. */
    val Download: ImageVector = stroked("Download") {
        moveTo(12f, 4f); lineTo(12f, 15f)
        moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
        moveTo(4f, 19.5f); lineTo(20f, 19.5f)
    }

    /** Import — a statement arriving. */
    val Upload: ImageVector = stroked("Upload") {
        moveTo(12f, 15f); lineTo(12f, 4f)
        moveTo(7.5f, 8.5f); lineTo(12f, 4f); lineTo(16.5f, 8.5f)
        moveTo(4f, 19.5f); lineTo(20f, 19.5f)
    }

    val Trash: ImageVector = stroked("Trash") {
        moveTo(4f, 6.5f); lineTo(20f, 6.5f)
        moveTo(9.5f, 6.5f); lineTo(9.5f, 4f); lineTo(14.5f, 4f); lineTo(14.5f, 6.5f)
        moveTo(6f, 6.5f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 6.5f)
        moveTo(10f, 10f); lineTo(10.4f, 17f)
        moveTo(14f, 10f); lineTo(13.6f, 17f)
    }

    /** A pencil — inline coding edits, card detail corrections. */
    val Edit: ImageVector = stroked("Edit") {
        moveTo(4f, 20f); lineTo(8f, 19f); lineTo(19.4f, 7.6f)
        curveTo(20.2f, 6.8f, 20.2f, 5.6f, 19.4f, 4.8f)
        curveTo(18.6f, 4f, 17.4f, 4f, 16.6f, 4.8f)
        lineTo(5f, 16f)
        close()
        moveTo(15f, 6.5f); lineTo(17.7f, 9.2f)
    }

    /** A caution triangle — overspend, exceptions, failed reconciliations. */
    val Warning: ImageVector = stroked("Warning") {
        moveTo(12f, 3.8f); lineTo(21.2f, 19.6f); lineTo(2.8f, 19.6f); close()
        moveTo(12f, 9.5f); lineTo(12f, 14f)
        moveTo(12f, 16.8f); lineTo(12.01f, 16.8f)
    }

    /** Four panes — overviews, dashboards, bulk surfaces. */
    val Grid: ImageVector = stroked("Grid") {
        moveTo(4f, 4f); lineTo(10.5f, 4f); lineTo(10.5f, 10.5f); lineTo(4f, 10.5f); close()
        moveTo(13.5f, 4f); lineTo(20f, 4f); lineTo(20f, 10.5f); lineTo(13.5f, 10.5f); close()
        moveTo(4f, 13.5f); lineTo(10.5f, 13.5f); lineTo(10.5f, 20f); lineTo(4f, 20f); close()
        moveTo(13.5f, 13.5f); lineTo(20f, 13.5f); lineTo(20f, 20f); lineTo(13.5f, 20f); close()
    }

    val ArrowRight: ImageVector = stroked("ArrowRight") {
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(14f, 6f); lineTo(20f, 12f); lineTo(14f, 18f)
    }

    val ArrowLeft: ImageVector = stroked("ArrowLeft") {
        moveTo(20f, 12f); lineTo(4f, 12f)
        moveTo(10f, 6f); lineTo(4f, 12f); lineTo(10f, 18f)
    }

    /** A payment card — the card module's own subject. */
    val CreditCard: ImageVector = stroked("CreditCard") {
        moveTo(3f, 6.5f); lineTo(21f, 6.5f); lineTo(21f, 17.5f); lineTo(3f, 17.5f); close()
        moveTo(3f, 10.5f); lineTo(21f, 10.5f)
        moveTo(6.5f, 14.5f); lineTo(10f, 14.5f)
    }

    /** A bound book — post & ledger, posting history. */
    val Ledger: ImageVector = stroked("Ledger") {
        moveTo(5f, 4f)
        lineTo(18f, 4f)
        curveTo(19.1f, 4f, 20f, 4.9f, 20f, 6f)
        lineTo(20f, 20f)
        lineTo(6.5f, 20f)
        curveTo(5.1f, 20f, 4f, 18.9f, 4f, 17.5f)
        lineTo(4f, 5f)
        curveTo(4f, 4.4f, 4.4f, 4f, 5f, 4f)
        close()
        moveTo(4f, 16.5f); lineTo(20f, 16.5f)
        moveTo(8f, 8f); lineTo(16f, 8f)
        moveTo(8f, 11.5f); lineTo(13f, 11.5f)
    }

    /** A shield — sign-off, senior authority, audit. */
    val Shield: ImageVector = stroked("Shield") {
        moveTo(12f, 3.5f); lineTo(19.5f, 6.5f); lineTo(19.5f, 12f)
        curveTo(19.5f, 16.5f, 16.2f, 19.5f, 12f, 20.5f)
        curveTo(7.8f, 19.5f, 4.5f, 16.5f, 4.5f, 12f)
        lineTo(4.5f, 6.5f)
        close()
        moveTo(9f, 12f); lineTo(11.2f, 14.2f); lineTo(15.2f, 9.8f)
    }

    /** Two figures — crew, holders, departments. */
    /** A person with a plus — the add-to-call verb. */
    val UserPlus: ImageVector = stroked("UserPlus") {
        moveTo(10f, 11.5f)
        curveTo(12f, 11.5f, 13.5f, 10f, 13.5f, 8f)
        curveTo(13.5f, 6f, 12f, 4.5f, 10f, 4.5f)
        curveTo(8f, 4.5f, 6.5f, 6f, 6.5f, 8f)
        curveTo(6.5f, 10f, 8f, 11.5f, 10f, 11.5f)
        close()
        moveTo(3.5f, 19.5f)
        curveTo(3.5f, 16f, 6.4f, 14f, 10f, 14f)
        curveTo(13.6f, 14f, 16.5f, 16f, 16.5f, 19.5f)
        moveTo(19f, 8.5f)
        lineTo(19f, 13.5f)
        moveTo(16.5f, 11f)
        lineTo(21.5f, 11f)
    }

    val Users: ImageVector = stroked("Users") {
        moveTo(9f, 11.5f)
        curveTo(11f, 11.5f, 12.5f, 10f, 12.5f, 8f)
        curveTo(12.5f, 6f, 11f, 4.5f, 9f, 4.5f)
        curveTo(7f, 4.5f, 5.5f, 6f, 5.5f, 8f)
        curveTo(5.5f, 10f, 7f, 11.5f, 9f, 11.5f)
        close()
        moveTo(2.5f, 19.5f)
        curveTo(2.5f, 16f, 5.4f, 14f, 9f, 14f)
        curveTo(12.6f, 14f, 15.5f, 16f, 15.5f, 19.5f)
        moveTo(16f, 5.2f)
        curveTo(17.7f, 5.6f, 19f, 6.6f, 19f, 8.5f)
        curveTo(19f, 10.2f, 17.8f, 11.2f, 16.3f, 11.6f)
        moveTo(17.5f, 14.6f)
        curveTo(20f, 15.3f, 21.5f, 16.9f, 21.5f, 19.5f)
    }

    /** An open eye — view-only affordances and detail drilldowns. */
    val Eye: ImageVector = stroked("Eye") {
        moveTo(2.5f, 12f)
        curveTo(5f, 7.5f, 8.3f, 5.5f, 12f, 5.5f)
        curveTo(15.7f, 5.5f, 19f, 7.5f, 21.5f, 12f)
        curveTo(19f, 16.5f, 15.7f, 18.5f, 12f, 18.5f)
        curveTo(8.3f, 18.5f, 5f, 16.5f, 2.5f, 12f)
        close()
        moveTo(12f, 15f)
        curveTo(13.7f, 15f, 15f, 13.7f, 15f, 12f)
        curveTo(15f, 10.3f, 13.7f, 9f, 12f, 9f)
        curveTo(10.3f, 9f, 9f, 10.3f, 9f, 12f)
        curveTo(9f, 13.7f, 10.3f, 15f, 12f, 15f)
        close()
    }

    /** The row overflow menu. */
    val MoreHorizontal: ImageVector = stroked("MoreHorizontal") {
        moveTo(5.5f, 12f); lineTo(5.51f, 12f)
        moveTo(12f, 12f); lineTo(12.01f, 12f)
        moveTo(18.5f, 12f); lineTo(18.51f, 12f)
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
}
