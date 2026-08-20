package com.zillit.desktop.feature.crewlist

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.crewlist.domain.CrewDepartment
import com.zillit.desktop.feature.crewlist.domain.CrewListViewer
import com.zillit.desktop.feature.crewlist.domain.CrewMember
import com.zillit.desktop.feature.crewlist.domain.CrewUnit
import com.zillit.desktop.feature.crewlist.ui.CrewListEvent
import com.zillit.desktop.feature.crewlist.ui.CrewListScreen
import com.zillit.desktop.feature.crewlist.ui.CrewListUiState
import com.zillit.desktop.feature.crewlist.ui.GenerateDialog
import kotlin.test.Test
import kotlin.test.assertTrue

/** Composes the grouped roster and the generate dialog. */
@OptIn(ExperimentalTestApi::class)
class CrewListScreenRenderTest {

    private val units = listOf(
        CrewUnit(
            unitName = "Main Unit",
            departments = listOf(
                CrewDepartment(
                    departmentName = "Camera",
                    members = listOf(
                        CrewMember(
                            userId = "u1",
                            fullName = "Aisha Khan",
                            designationName = "First AC",
                            phone = "5550001",
                            countryCode = "+44",
                            primaryEmail = "a@crew.example",
                        ),
                        CrewMember(userId = "u2", fullName = "Ravi", isExternal = true),
                    ),
                ),
            ),
        ),
    )

    private val state = CrewListUiState(
        units = units,
        viewer = CrewListViewer(canView = true, ready = true),
    )

    @Test
    fun `the roster groups unit - department - people and generate opens`() = runComposeUiTest {
        val events = mutableListOf<CrewListEvent>()
        setContent {
            ZillitTheme {
                CrewListScreen(state = state, visibleUnits = { units }, onEvent = { events += it })
            }
        }

        onNodeWithText("Main Unit").assertExists()
        onNodeWithText("Camera").assertExists()
        onNodeWithText("Aisha Khan").assertExists()
        onNodeWithText("+445550001").assertExists()
        onNodeWithText("a@crew.example").assertExists()
        onNodeWithText("Not on Zillit").assertExists()
        onNodeWithText("Generate PDF").performClick()

        assertTrue(events.contains(CrewListEvent.OpenGenerate))
    }

    @Test
    fun `the dialog asks the one question`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                CrewListScreen(
                    state = state.copy(generating = GenerateDialog()),
                    visibleUnits = { units },
                    onEvent = {},
                )
            }
        }
        onNodeWithText("Hide the \"Not on Zillit\" label on external users").assertExists()
        onNodeWithText("Generate & view").assertExists()
    }
}
