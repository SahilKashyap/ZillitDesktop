package com.zillit.desktop.feature.continuity

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.ContinuityViewer
import com.zillit.desktop.feature.continuity.ui.ContinuityScreen
import com.zillit.desktop.feature.continuity.ui.ContinuityUiState
import kotlin.test.Test

/** Composes the real continuity board on each tab, in both themes. */
@OptIn(ExperimentalTestApi::class)
class ContinuityScreenRenderTest {

    @Test
    fun `each tab composes in both themes`() {
        ContinuityTab.entries.forEach { tab ->
            listOf(false, true).forEach { dark ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            ContinuityScreen(
                                state = ContinuityUiState(
                                    viewer = ContinuityViewer(canView = true),
                                    tab = tab,
                                ),
                                onEvent = {},
                                loadImage = { _, _ -> null },
                                resolveUser = { "Aisha Khan" },
                                formatDate = { "26 Aug 2026" },
                            )
                        }
                    }
                }
            }
        }
    }
}
