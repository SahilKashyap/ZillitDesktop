package com.zillit.desktop.feature.invoices

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.ui.DepartmentTab
import com.zillit.desktop.feature.invoices.ui.InvoicesScreen
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import kotlin.test.Test

/** Composes the real invoices screen on each department tab, in both themes. */
@OptIn(ExperimentalTestApi::class)
class InvoicesScreenRenderTest {

    private companion object {
        const val NOW = 1_786_950_000_000
    }

    @Test
    fun `each department tab composes in both themes`() {
        DepartmentTab.entries.forEach { tab ->
            listOf(false, true).forEach { dark ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            InvoicesScreen(
                                state = InvoicesUiState(
                                    viewer = InvoiceViewer(canView = true, ready = true),
                                    departmentTab = tab,
                                ),
                                onEvent = {},
                                nowMs = NOW,
                            )
                        }
                    }
                }
            }
        }
    }

    /** An accountant sees a different screen entirely. */
    @Test
    fun `the accountant view composes`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    InvoicesScreen(
                        state = InvoicesUiState(
                            // An accountant is one by department, not by flag.
                            viewer = InvoiceViewer(
                                canView = true,
                                ready = true,
                                departmentIdentifier = "department_accounts",
                            ),
                        ),
                        onEvent = {},
                        nowMs = NOW,
                    )
                }
            }
        }
    }
}
