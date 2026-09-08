package com.zillit.desktop.feature.budget

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetMember
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetViewer
import com.zillit.desktop.feature.budget.ui.BudgetScreen
import com.zillit.desktop.feature.budget.ui.BudgetTab
import com.zillit.desktop.feature.budget.ui.BudgetUiState
import kotlin.test.Test

/**
 * Composes the real screen in every state it can reach, light and dark.
 *
 * These catch what unit tests cannot: a layout that throws on infinite
 * constraints, or a pane that composes at zero size.
 */
@OptIn(ExperimentalTestApi::class)
class BudgetScreenRenderTest {

    /** Both budgets, a file on each — the everyday case. */
    @Test
    fun `the loaded screen draws in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = dark) {
                        BudgetScreen(state = loaded(), onEvent = {})
                    }
                }
                onNodeWithText("Budget").assertExists()
                onNodeWithText("Main.pdf").assertExists()
            }
        }
    }

    /** The department half: a list beside the chosen department's file. */
    @Test
    fun `the department tab draws its list`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(
                        state = loaded().copy(tab = BudgetTab.Department, selectedId = "b2"),
                        onEvent = {},
                    )
                }
            }
            // "Camera" itself is on screen twice — the list row and the card
            // title — so match what appears once.
            onNodeWithText("Budget attached").assertExists()
            onNodeWithText("Camera.xlsx").assertExists()
        }
    }

    /** Nothing uploaded yet, and the viewer may fix that. */
    @Test
    fun `an empty budget invites an upload`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(
                        state = BudgetUiState(viewer = viewer(), mainBudget = null),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("No budget uploaded").assertExists()
        }
    }

    /** No rights at all: one honest sentence, no empty furniture. */
    @Test
    fun `a viewer with no access is told plainly`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(
                        state = BudgetUiState(
                            viewer = BudgetViewer(resolved = true),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("No budget access").assertExists()
        }
    }

    /** The members dialog composes over the screen. */
    @Test
    fun `the members dialog draws its people`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(
                        state = loaded().copy(
                            membersOpen = true,
                            members = listOf(
                                BudgetMember(userId = "u1", fullName = "Ravi Menon", departmentName = "Camera"),
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Ravi Menon").assertExists()
        }
    }

    /**
     * The conversation pane is the host's to supply; the screen must lay out
     * around it without the documents collapsing.
     */
    @Test
    fun `the conversation pane sits beside the documents`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(
                        state = loaded(),
                        onEvent = {},
                        conversation = {
                            com.zillit.desktop.core.designsystem.component.ZillitText(
                                text = "Discussion goes here",
                                style = ZillitTheme.typography.bodyMedium,
                                color = ZillitTheme.colors.textPrimary,
                            )
                        },
                    )
                }
            }
            onNodeWithText("Discussion goes here").assertExists()
            onNodeWithText("Main.pdf").assertExists()
        }
    }

    private fun viewer() = BudgetViewer(
        canViewMain = true,
        canPostMain = true,
        canViewDepartment = true,
        canPostDepartment = true,
        canDownloadMain = true,
        canDownloadDepartment = true,
        resolved = true,
    )

    private fun loaded() = BudgetUiState(
        viewer = viewer(),
        mainBudget = BudgetDocument(
            id = "b1",
            type = BudgetType.Main,
            file = BudgetFile(media = "k/main.pdf", name = "Main.pdf", sizeBytes = 2_400_000),
            uploadedByName = "Aisha Khan",
        ),
        departmentBudgets = listOf(
            BudgetDocument(
                id = "b2",
                type = BudgetType.Department,
                departmentId = "d9",
                departmentName = "Camera",
                file = BudgetFile(media = "k/cam.xlsx", name = "Camera.xlsx"),
            ),
        ),
        viewCount = 12,
        downloadCount = 3,
    )


    /**
     * The flip: Upload budget stays for a reader who cannot post to this tab.
     *
     * `BudgetViewModel.refusesPost` answers the press by offering to ask an
     * admin, and names the tab that refused — posting rights here are granted
     * per tab, main and department separately.
     */
    @Test
    fun `a reader without posting rights still sees Upload budget`() {
        val reader = viewer().copy(canPostMain = false, canPostDepartment = false)

        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetScreen(state = loaded().copy(viewer = reader), onEvent = {})
                }
            }
            onNodeWithText("Upload budget").assertExists()
        }
    }
}
