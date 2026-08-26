package com.zillit.desktop.feature.costreport

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.costreport.domain.CostReportTab
import com.zillit.desktop.feature.costreport.domain.CostReportViewer
import com.zillit.desktop.feature.costreport.ui.CostReportScreen
import com.zillit.desktop.feature.costreport.ui.CostReportUiState
import kotlin.test.Test

/** Composes the real cost-report screen on each tab, in both themes. */
@OptIn(ExperimentalTestApi::class)
class CostReportScreenRenderTest {

    @Test
    fun `each tab composes in both themes`() {
        CostReportTab.entries.forEach { tab ->
            listOf(false, true).forEach { dark ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            CostReportScreen(
                                state = CostReportUiState(
                                    viewer = CostReportViewer(canView = true),
                                    tab = tab,
                                    projectName = "Night Shift",
                                ),
                                onEvent = {},
                                resolveUser = { "Aisha Khan" },
                            )
                        }
                    }
                }
            }
        }
    }
}
