package com.zillit.desktop

import java.awt.Graphics2D
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The picture that stands in for the app while it starts.
 *
 * Worth a test for one reason: it is the only thing on screen for the first
 * seconds of a launch, and the way it fails is silent. A missing or
 * undecodable icon leaves a plain orange rectangle — which looks intentional,
 * tells nobody anything, and would survive a review by anybody who did not
 * happen to have launched the packaged build. `ZillitLogoTest` guards the
 * Compose splash behind this one against exactly the same accident.
 */
class SplashTest {

    /** The resource resolves *and* decodes. Resolving alone is not enough. */
    @Test
    fun `the mark is in this build and decodes`() {
        val mark = assertNotNull(Splash.loadMark(), "no /icons/zillit-icon.png in desktopApp's resources")

        assertTrue(mark.width > 0 && mark.height > 0, "the icon decoded to nothing")
    }

    /** The brand's orange, edge to edge, so the handover to the sign-in splash does not flash. */
    @Test
    fun `the backdrop fills the splash`() {
        val painted = paint()

        assertEquals(Splash.BACKDROP.rgb, painted.getRGB(2, 2))
        assertEquals(Splash.BACKDROP.rgb, painted.getRGB(Splash.WIDTH - 3, Splash.HEIGHT - 3))
    }

    /**
     * And the mark is actually on it. The centre is the one place the logo
     * certainly covers, so a centre still showing the backdrop means nothing
     * was drawn.
     */
    @Test
    fun `the mark is drawn over the backdrop`() {
        val painted = paint()

        val centre = painted.getRGB(Splash.WIDTH / 2, Splash.HEIGHT / 2)
        assertTrue(centre != Splash.BACKDROP.rgb, "the splash painted no logo, only its backdrop")
    }

    private fun paint(): BufferedImage {
        val mark = assertNotNull(Splash.loadMark())
        val image = BufferedImage(Splash.WIDTH, Splash.HEIGHT, BufferedImage.TYPE_INT_RGB)
        val canvas = image.graphics as Graphics2D
        try {
            Splash.draw(canvas, Splash.WIDTH, Splash.HEIGHT, mark)
        } finally {
            canvas.dispose()
        }
        return image
    }
}
