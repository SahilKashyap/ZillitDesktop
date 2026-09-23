package com.zillit.desktop.feature.externalusers

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.externalusers.domain.Creator
import com.zillit.desktop.feature.externalusers.domain.ExternalUser
import com.zillit.desktop.feature.externalusers.domain.ExternalUsersViewer
import com.zillit.desktop.feature.externalusers.domain.LabeledValue
import com.zillit.desktop.feature.externalusers.ui.EditingUser
import com.zillit.desktop.feature.externalusers.ui.ExternalUsersEvent
import com.zillit.desktop.feature.externalusers.ui.ExternalUsersScreen
import com.zillit.desktop.feature.externalusers.ui.ExternalUsersUiState
import kotlin.test.Test
import kotlin.test.assertTrue

/** Composes the real roster, the form dialog, and the blocked state. */
@OptIn(ExperimentalTestApi::class)
class ExternalUsersScreenRenderTest {

    private val vendor = ExternalUser(
        id = "e1",
        fullName = "Grip Hire Ltd",
        email = "ops@griphire.example",
        userType = "vender_label",
        gender = "other",
        countryCode = "+44",
        phone = "7700900123",
        createdBy = "u-me",
        otherInfo = listOf(LabeledValue("Accounts", "billing@griphire.example")),
    )

    private val state = ExternalUsersUiState(
        users = listOf(vendor),
        viewer = ExternalUsersViewer(userId = "u-me", canView = true, canPost = true, ready = true),
        crew = mapOf("u-me" to Creator("u-me", "Sam Coordinator", "Production Coordinator")),
    )

    @Test
    fun `the card carries the web's three facts, and Add User raises its event`() = runComposeUiTest {
        val events = mutableListOf<ExternalUsersEvent>()
        setContent { ZillitTheme { ExternalUsersScreen(state = state, onEvent = { events += it }) } }

        onNodeWithText("Grip Hire Ltd").assertExists()
        // The legacy `other` reads as Non-binary, the web's card rule.
        onNodeWithText("(Non-binary)").assertExists()
        onNodeWithText("ops@griphire.example").assertExists()
        onNodeWithText("+44 7700900123").assertExists()
        // Created By names the crew member and their designation, never the id.
        onNodeWithText("Sam Coordinator (Production Coordinator)").assertExists()
        // Twice: the filter chip and the card's type tag.
        onAllNodesWithText("Vendor").assertCountEquals(2)
        onNodeWithText("Add User").performClick()

        assertTrue(events.contains(ExternalUsersEvent.New))
    }

    @Test
    fun `a clicked address asks for the composer, and the foot opens the details`() = runComposeUiTest {
        val events = mutableListOf<ExternalUsersEvent>()
        setContent { ZillitTheme { ExternalUsersScreen(state = state, onEvent = { events += it }) } }

        onNodeWithText("ops@griphire.example").performClick()
        onNodeWithText("View More Details").performClick()

        assertTrue(events.contains(ExternalUsersEvent.WriteTo("ops@griphire.example")))
        assertTrue(events.contains(ExternalUsersEvent.ShowDetails(vendor)))
    }

    @Test
    fun `the details table shows every row the record carries, other info included`() = runComposeUiTest {
        setContent {
            ZillitTheme { ExternalUsersScreen(state = state.copy(details = vendor), onEvent = {}) }
        }

        onNodeWithText("User Details").assertExists()
        onNodeWithText("User Type").assertExists()
        onNodeWithText("Other Info").assertExists()
        onNodeWithText("Accounts:").assertExists()
        onNodeWithText("billing@griphire.example").assertExists()
    }

    @Test
    fun `the form dialog carries the web's fields`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ExternalUsersScreen(
                    state = state.copy(editing = EditingUser()),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Full name *").assertExists()
        onNodeWithText("Email *").assertExists()
        onNodeWithText("Country Code").assertExists()
        onNodeWithText("Phone").assertExists()
        onNodeWithText("Select gender").assertExists()
        // A fresh draft is a crew member, so the department picker is up.
        onNodeWithText("Select department").assertExists()
        onNodeWithText("Submit").assertExists()
        onNodeWithText("Add More Information").assertExists()
    }

    @Test
    fun `a poster's own card carries live edit and delete controls`() = runComposeUiTest {
        val events = mutableListOf<ExternalUsersEvent>()
        setContent { ZillitTheme { ExternalUsersScreen(state = state, onEvent = { events += it }) } }

        // Always composed, in the card head — never behind a hover.
        onNodeWithContentDescription("Edit Grip Hire Ltd").performClick()
        onNodeWithContentDescription("Delete Grip Hire Ltd").performClick()

        assertTrue(events.contains(ExternalUsersEvent.Edit(vendor)))
        assertTrue(events.contains(ExternalUsersEvent.Delete(vendor)))
    }

    @Test
    fun `a view-only viewer keeps Add User but not another person's card controls`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ExternalUsersScreen(
                    state = state.copy(
                        viewer = ExternalUsersViewer(userId = "u-else", canView = true, ready = true),
                    ),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Grip Hire Ltd").assertExists()
        // Kept: a missing posting right is something an admin can grant, and
        // ExternalUsersViewModel.guardPost answers the press by asking for it.
        onNodeWithText("Add User").assertExists()
        // Not kept: this contact belongs to somebody else, which no rights
        // grant changes — only its author or an admin may touch the row.
        onNodeWithContentDescription("Edit Grip Hire Ltd").assertDoesNotExist()
        onNodeWithContentDescription("Delete Grip Hire Ltd").assertDoesNotExist()
    }

    @Test
    fun `no view right and no admin means a closed door`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ExternalUsersScreen(
                    state = ExternalUsersUiState(
                        viewer = ExternalUsersViewer(canView = false, ready = true),
                    ),
                    onEvent = {},
                )
            }
        }
        onNodeWithText("You don't have access to External Users.").assertExists()
    }


}
