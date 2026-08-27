package com.zillit.desktop.feature.productionreport

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.productionreport.domain.ReportViewer
import com.zillit.desktop.feature.productionreport.ui.ProductionReportScreen
import com.zillit.desktop.feature.productionreport.ui.ReportDestination
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import kotlin.test.Test

/** Composes the real production-report screen at each destination, in both themes. */
@OptIn(ExperimentalTestApi::class)
class ReportScreenRenderTest {

    @Test
    fun `each destination composes in both themes`() {
        ReportDestination.entries.forEach { destination ->
            listOf(false, true).forEach { dark ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            ProductionReportScreen(
                                state = ReportUiState(
                                    viewer = ReportViewer(canView = true),
                                    destination = destination,
                                ),
                                onEvent = {},
                            )
                        }
                    }
                }
            }
        }
    }
}
