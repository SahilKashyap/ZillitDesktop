package com.zillit.desktop.feature.costumesetsync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asSkiaBitmap
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.SyncFrame
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test

/** Opt-in: `SYNC_SHOTS=1` writes one PNG per route to `build/shots`. Not an assertion — a way to look. */
@OptIn(ExperimentalTestApi::class)
class ShotsTest {
    private fun shot(route: String) {
        if (System.getenv("SYNC_SHOTS") == null) return
        runComposeUiTest {
            val context = RoutesSmokeTest().ctx(route)
            setContent {
                ZillitTheme(animateThemeChange = false) {
                    CompositionLocalProvider(LocalSync provides context) {
                        Column(Modifier.size(1240.dp, 800.dp)) { SyncFrame(null, 0, true, {}, {}) }
                    }
                }
            }
            waitForIdle()
            val image = onRoot().captureToImage()
            val dir = File("build/shots").apply { mkdirs() }
            File(dir, route.replace('/', '_').substringBefore('?') + ".png")
                .writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData()!!.bytes)
        }
    }

    @Test fun dashboard() = shot("dashboard")
    @Test fun costumes() = shot("costumes")
    @Test fun costumeDetail() = shot("costumes/c1")
    @Test fun breakdown() = shot("breakdown")
    @Test fun characters() = shot("characters")
    @Test fun fittings() = shot("fittings")
    @Test fun cleaning() = shot("cleaning")
    @Test fun vendors() = shot("vendors")
    @Test fun reports() = shot("reports")
    @Test fun continuity() = shot("continuity")
}
