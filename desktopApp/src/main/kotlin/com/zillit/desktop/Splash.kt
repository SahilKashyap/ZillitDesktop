package com.zillit.desktop

import java.awt.Color
import java.awt.EventQueue
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Window
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

/**
 * The mark on screen while the app is still coming up.
 *
 * ## Why this exists
 *
 * Nothing Zillit draws is visible until Compose composes its first frame, and
 * that is a long way in: the JVM loads 195 jars, then [AppGraph.build] wires
 * the whole application — two SQLCipher opens through the credential store
 * among them — on the main thread, *before* the first `Window`. Measured on a
 * 1.2.2 install, the process starts and the first line of app code logs 1.7
 * seconds later; the window follows that.
 *
 * For all of it the desktop looks like nothing happened. So people double-click
 * the launcher again, and the second copy — correctly — refuses to start and
 * says so in a dialog, which is the screenshot that prompted this. The app was
 * working the whole time and had simply not said so yet.
 *
 * ## Why an AWT window rather than `-splash:`
 *
 * The JVM's own `-splash:` paints before a single class loads, which is
 * earlier than anything reachable from Kotlin. It is also a flag of the `java`
 * launcher, and a packaged build has no `java` launcher: jpackage ships a
 * native stub that creates the VM through JNI and passes `--java-options`
 * straight to it, where an unrecognised option is a failure to start rather
 * than a missing picture. Trading a few hundred milliseconds for a launcher
 * that certainly starts is the right way round.
 *
 * AWT comes up ahead of Compose on this path either way — `installDockIcon`
 * asks for the `Taskbar` before `runZillit` — so this brings that cost forward
 * rather than adding one.
 *
 * ## Why it is drawn rather than loaded
 *
 * The artwork is the app icon on the brand's orange, which is what the sign-in
 * splash behind it already shows (`signInBackdrop`, `ZillitLogo`). Composing
 * those two here rather than shipping a third PNG keeps one mark in the repo:
 * a splash image that drifted from the icon would be visible precisely at the
 * handover, when both are on screen within a second of each other.
 */
internal object Splash {

    private val window = AtomicReference<Window?>(null)

    /**
     * Puts the mark up. Call first, before anything slow.
     *
     * Best-effort throughout: a headless run, a missing icon or a display that
     * refuses an undecorated window must not be the reason the app does not
     * start. There is nothing here the app needs, only something the person
     * needs to see.
     */
    fun show() {
        if (GraphicsEnvironment.isHeadless()) return
        runCatching {
            val mark = loadMark() ?: return
            val panel = object : Window(null) {
                override fun paint(g: Graphics) = draw(g as Graphics2D, width, height, mark)
            }
            panel.setSize(WIDTH, HEIGHT)
            panel.setLocationRelativeTo(null)
            // Never takes focus: the main window arrives seconds later and must
            // come up focused, and a splash that has to hand focus back is how
            // a launch ends with the person typing into nothing.
            panel.focusableWindowState = false
            panel.isAlwaysOnTop = true
            // Rounded to match the window it precedes. Shaped windows need
            // per-pixel transparency; where the platform has none, a rectangle
            // is a perfectly good splash.
            runCatching {
                panel.shape = RoundRectangle2D.Double(
                    0.0,
                    0.0,
                    WIDTH.toDouble(),
                    HEIGHT.toDouble(),
                    RADIUS,
                    RADIUS,
                )
            }
            panel.isVisible = true
            window.set(panel)
        }
    }

    /**
     * Takes it down. Safe to call more than once, and from any thread.
     *
     * Every way out of startup goes through this — the window appearing, a
     * launch that stays in the tray, and the early returns a second copy takes
     * — because a splash nobody closes is a borderless always-on-top rectangle
     * with no way to dismiss it.
     */
    fun close() {
        window.getAndSet(null)?.let { panel ->
            EventQueue.invokeLater {
                panel.isVisible = false
                panel.dispose()
            }
        }
    }

    /**
     * The mark on the brand's orange, filling the given area.
     *
     * Its own function, and `internal` rather than private, so a test can paint
     * it into an image and look at the result. That is not ceremony: the thing
     * that actually goes wrong here is a resource that resolves but decodes to
     * nothing, which leaves an orange rectangle with no logo on it and looks
     * for all the world like a deliberate design. `ZillitLogoTest` guards the
     * Compose splash for the same reason.
     */
    internal fun draw(canvas: Graphics2D, width: Int, height: Int, mark: BufferedImage) {
        canvas.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        canvas.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON,
        )
        canvas.color = BACKDROP
        canvas.fillRect(0, 0, width, height)
        canvas.drawImage(mark, (width - MARK) / 2, (height - MARK) / 2, MARK, MARK, null)
    }

    /** The app icon, or null if this build somehow ships without one. */
    internal fun loadMark(): BufferedImage? =
        Splash::class.java.getResourceAsStream(ICON)?.use(ImageIO::read)

    private const val ICON = "/icons/zillit-icon.png"

    /** `ZillitPalette.OrangeDeep`, which `core:designsystem` cannot be loaded for yet. */
    internal val BACKDROP = Color(0xFF, 0x6D, 0x00)

    internal const val WIDTH = 360
    internal const val HEIGHT = 240
    private const val MARK = 112
    private const val RADIUS = 20.0
}
