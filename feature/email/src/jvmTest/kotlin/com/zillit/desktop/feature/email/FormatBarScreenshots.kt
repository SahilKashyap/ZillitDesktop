package com.zillit.desktop.feature.email

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.email.domain.BlockKind
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.indentBy
import com.zillit.desktop.feature.email.domain.toggleList
import com.zillit.desktop.feature.email.ui.RichTextEditor
import java.io.File
import kotlin.test.Test

/**
 * The composer's toolbar and body as PNGs, for looking at — not asserting on.
 *
 * Opt-in: runs only when `COMPOSER_SHOTS` names a directory, so the normal
 * test pass stays fast and writes nothing. It exists because "do the list
 * icons read as lists, and do the groups break where the web's do" is a
 * question only a picture answers.
 */
class FormatBarScreenshots {

    @Test
    fun `render the composer`() {
        val into = System.getenv("COMPOSER_SHOTS")?.let(::File) ?: return
        into.mkdirs()

        val body = RichText.plain("Shot list for Tuesday\nSteadicam\nSecond unit\nLenses\nCall time 06:30")
            .toggleList(BlockKind.Ordered, 22, 46)
            .indentBy(1, 32, 46)
            .toggleList(BlockKind.Bullet, 47, 53)

        listOf(false to "light", true to "dark").forEach { (dark, name) ->
            shoot(File(into, "composer-$name.png"), dark) {
                Box(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
                    RichTextEditor(
                        value = body,
                        onValueChange = {},
                        modifier = Modifier.width(WIDTH_DP.dp - PAD * 2).padding(PAD),
                    )
                }
            }
        }
    }

    private fun shoot(file: File, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(
            width = WIDTH_DP * DENSITY,
            height = HEIGHT_DP * DENSITY,
            density = Density(DENSITY.toFloat()),
        ) {
            ZillitTheme(darkTheme = dark, animateThemeChange = false) { content() }
        }
        repeat(SETTLING_FRAMES) { scene.render(it * FRAME_NANOS) }
        val image = scene.render(SETTLED_NANOS)
        file.writeBytes(requireNotNull(image.encodeToData()).bytes)
        scene.close()
    }

    private companion object {
        const val DENSITY = 2
        const val WIDTH_DP = 900
        const val HEIGHT_DP = 420
        val PAD = 16.dp
        const val SETTLING_FRAMES = 3
        const val FRAME_NANOS = 500_000_000L
        const val SETTLED_NANOS = 2_000_000_000L
    }
}
