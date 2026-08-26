package com.zillit.desktop.feature.pagedistribution

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.DistributionViewer
import com.zillit.desktop.feature.pagedistribution.ui.DistributionScreen
import com.zillit.desktop.feature.pagedistribution.ui.DistributionUiState
import kotlin.test.Test

/**
 * Composes the real distribution screen.
 *
 * The module had no render test at all, and this is the class of fault only a
 * render test finds: a layout that throws on infinite constraints, or a pane
 * that composes at zero size. Unit tests over the view model cannot see it.
 */
@OptIn(ExperimentalTestApi::class)
class DistributionScreenRenderTest {

    @Test
    fun `the schedule tool draws its documents in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = dark) {
                        DistributionScreen(
                            state = loaded(DistributionTool.ScheduleDistribution),
                            onEvent = {},
                            resolveUser = { "Aisha Khan (1st AD)" },
                        )
                    }
                }
                onNodeWithText("Day 12 schedule.pdf").assertExists()
            }
        }
    }

    /**
     * Every tool this engine serves must compose, not just the first.
     *
     * Only that each one draws: what it draws differs by tool, since D.O.D
     * opens on a folder tab where the others open on a document list. A crash
     * here is the fault worth catching — the screens differ enough in layout
     * that one can throw while its neighbours are fine.
     */
    @Test
    fun `each distribution tool composes`() {
        listOf(
            DistributionTool.ScheduleDistribution,
            DistributionTool.ScriptDistribution,
            DistributionTool.ScheduleDod,
        ).forEach { tool ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = false) {
                        DistributionScreen(
                            state = loaded(tool),
                            onEvent = {},
                            resolveUser = { null },
                        )
                    }
                }
                onNodeWithText(tool.title).assertExists()
            }
        }
    }

    /** An empty list is a state a reader meets on day one. */
    @Test
    fun `an empty tool composes`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    DistributionScreen(
                        state = DistributionUiState(tool = DistributionTool.ScheduleDistribution),
                        onEvent = {},
                        resolveUser = { null },
                    )
                }
            }
        }
    }

    private fun loaded(tool: DistributionTool) = DistributionUiState(
        tool = tool,
        viewer = DistributionViewer(
            userId = "u1",
            canView = true,
            canPost = true,
            canDownload = true,
            canPublish = true,
        ),
        documents = listOf(
            DistDocument(
                id = "d1",
                createdMs = 1_786_950_000_000,
                createdBy = "u1",
                episode = "2",
                sceneNumber = "14",
                pageNumber = "3",
                colour = "#FFD966",
                dateMs = 1_786_950_000_000,
                revisionDateMs = 0,
                userSelectedDateMs = 1_786_950_000_000,
                scheduleType = null,
                name = "Day 12 schedule.pdf",
                originalName = "Day 12 schedule.pdf",
                deleted = false,
                replaced = false,
                attachment = null,
            ),
        ),
    )
}
