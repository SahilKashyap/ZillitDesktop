package com.zillit.desktop.feature.externalusers

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.externalusers.domain.ExternalUser
import com.zillit.desktop.feature.externalusers.domain.ExternalUsersViewer
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
        createdBy = "u-me",
    )

    private val state = ExternalUsersUiState(
        users = listOf(vendor),
        viewer = ExternalUsersViewer(userId = "u-me", canView = true, canPost = true, ready = true),
    )

    @Test
    fun `the roster shows the row, the chips, and Add User raises its event`() = runComposeUiTest {
        val events = mutableListOf<ExternalUsersEvent>()
        setContent { ZillitTheme { ExternalUsersScreen(state = state, onEvent = { events += it }) } }

        onNodeWithText("Grip Hire Ltd").assertExists()
        onNodeWithText("ops@griphire.example", substring = true).assertExists()
        // Twice: the filter chip and the row's type tag.
        onAllNodesWithText("Vendor").assertCountEquals(2)
        onNodeWithText("Add User").performClick()

        assertTrue(events.contains(ExternalUsersEvent.New))
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

        onNodeWithText("Full name").assertExists()
        onNodeWithText("Email").assertExists()
        onNodeWithText("Phone").assertExists()
        onNodeWithText("Submit").assertExists()
        onNodeWithText("Add more information").assertExists()
    }

    @Test
    fun `a poster's own row carries live edit and delete controls`() = runComposeUiTest {
        val events = mutableListOf<ExternalUsersEvent>()
        setContent { ZillitTheme { ExternalUsersScreen(state = state, onEvent = { events += it }) } }

        // Composed even before any hover — revealed by alpha, never by
        // conditional composition, so the click always finds them live.
        onNodeWithContentDescription("Edit Grip Hire Ltd").performClick()
        onNodeWithContentDescription("Delete Grip Hire Ltd").performClick()

        assertTrue(events.contains(ExternalUsersEvent.Edit(vendor)))
        assertTrue(events.contains(ExternalUsersEvent.Delete(vendor)))
    }

    @Test
    fun `a view-only viewer keeps Add User but not another person's row controls`() = runComposeUiTest {
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
