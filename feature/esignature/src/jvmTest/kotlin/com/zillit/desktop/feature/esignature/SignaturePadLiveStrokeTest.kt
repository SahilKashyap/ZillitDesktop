package com.zillit.desktop.feature.esignature

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.esignature.ui.PadMode
import com.zillit.desktop.feature.esignature.ui.PadState
import com.zillit.desktop.feature.esignature.ui.components.DRAW_PAD_TAG
import com.zillit.desktop.feature.esignature.ui.components.SignaturePad
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pen's line shows as it is drawn (reported 2026-09-23): the stroke in
 * progress lived in a plain list the canvas never observed, so a signature
 * appeared only when the mouse button came up.
 */
@OptIn(ExperimentalTestApi::class)
class SignaturePadLiveStrokeTest {

    @Test
    fun `a stroke is on the pad while it is being drawn, and committed on release`() = runComposeUiTest {
        var committed: List<Pair<Float, Float>>? = null
        setContent {
            ZillitTheme(darkTheme = false, animateThemeChange = false) {
                Box(Modifier.width(800.dp)) {
                    SignaturePad(
                        pad = PadState(mode = PadMode.Draw),
                        saved = emptyList(),
                        savedImages = emptyMap(),
                        forSignature = true,
                        showSaved = false,
                        onMode = {},
                        onStroke = { committed = it },
                        onClear = {},
                        onTyped = {},
                        onFont = {},
                        onPickImage = {},
                        onUseSaved = {},
                    )
                }
            }
        }
        val pad = onNodeWithTag(DRAW_PAD_TAG)
        assertEquals(0, pad.captureToImage().inkPixels(), "the pad starts blank")

        pad.performMouseInput {
            moveTo(Offset(120f, 120f))
            press()
            listOf(180f, 240f, 300f, 360f, 420f).forEachIndexed { i, x -> moveTo(Offset(x, 120f + i * 12f)) }
        }
        waitForIdle()

        // Mid-stroke: the button is still down, nothing committed, ink showing.
        assertNull(committed, "nothing is committed until the mouse comes up")
        assertTrue(pad.captureToImage().inkPixels() > 0, "the stroke in progress is not drawn")

        pad.performMouseInput { release() }
        waitForIdle()
        assertTrue((committed?.size ?: 0) > 1, "the stroke is committed on release")
    }

    /** Pixels in the pen's navy (0x162A60), allowing for anti-aliasing. */
    private fun ImageBitmap.inkPixels(): Int {
        val map = toPixelMap()
        var count = 0
        for (x in 0 until width) {
            for (y in 0 until height) {
                val c = map[x, y]
                val close = abs(c.red * 255 - 0x16) < TOLERANCE &&
                    abs(c.green * 255 - 0x2A) < TOLERANCE &&
                    abs(c.blue * 255 - 0x60) < TOLERANCE
                if (close) count++
            }
        }
        return count
    }
}

private const val TOLERANCE = 24
