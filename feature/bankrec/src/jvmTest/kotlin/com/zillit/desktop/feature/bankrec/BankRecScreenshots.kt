package com.zillit.desktop.feature.bankrec

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.bankrec.ui.BankRecScreen
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.BankTab
import com.zillit.desktop.feature.bankrec.ui.LocalBankRecPeople
import java.io.File
import kotlin.test.Test

/**
 * Every tab and dialog as a PNG, for looking at — not asserting on.
 *
 * Opt-in: runs only when `BANKREC_SHOTS` names a directory, so the normal test
 * pass stays fast and writes nothing. Sized to the pane the Account Hub gives
 * the tool, at the Mac's density.
 */
class BankRecScreenshots {

    @Test
    fun `render every tab and dialog`() {
        val dir = System.getenv("BANKREC_SHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        dir.mkdirs()
        listOf(false, true).forEach { dark ->
            val theme = if (dark) "dark" else "light"
            BankTab.entries.forEach { tab ->
                shoot(
                    File(dir, "tab-${tab.slug}-$theme.png"),
                    RenderFixtures.state(tab),
                    dark,
                    tall = tab != BankTab.Workspace,
                )
            }
            BankRecScreenRenderTest.dialogStates().forEachIndexed { index, (title, state) ->
                val slug = title.lowercase().replace(Regex("[^a-z]+"), "-").trim('-')
                shoot(
                    File(dir, "dialog-${index.toString().padStart(2, '0')}-$slug-$theme.png"),
                    state,
                    dark,
                    tall = false,
                )
            }
        }
    }

    private fun shoot(file: File, state: BankRecUiState, dark: Boolean, tall: Boolean) {
        val scene = ImageComposeScene(
            width = WIDTH_DP * DENSITY,
            height = (if (tall) TALL_DP else HEIGHT_DP) * DENSITY,
            density = Density(DENSITY.toFloat()),
        ) {
            ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                CompositionLocalProvider(LocalBankRecPeople provides RenderFixtures.people) {
                    BankRecScreen(state) {}
                }
            }
        }
        // A late frame: dialogs scale and fade in, and frame 0 catches them mid-way.
        scene.render(0L)
        val image = scene.render(SETTLED_NANOS)
        file.writeBytes(requireNotNull(image.encodeToData()).bytes)
        scene.close()
    }

    private companion object {
        const val DENSITY = 2
        const val WIDTH_DP = 1180
        const val HEIGHT_DP = 900
        const val TALL_DP = 2400
        const val SETTLED_NANOS = 2_000_000_000L
    }
}
