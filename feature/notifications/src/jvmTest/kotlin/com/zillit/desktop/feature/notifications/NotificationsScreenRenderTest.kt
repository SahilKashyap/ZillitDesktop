package com.zillit.desktop.feature.notifications

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.notifications.domain.NotificationTarget
import com.zillit.desktop.feature.notifications.domain.ProjectNotification
import com.zillit.desktop.feature.notifications.ui.NotificationsConfirm
import com.zillit.desktop.feature.notifications.ui.NotificationsEvent
import com.zillit.desktop.feature.notifications.ui.NotificationsScreen
import com.zillit.desktop.feature.notifications.ui.NotificationsUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Composes the real screen: rows with their path, text and stamp; the per-row
 * and header deletes raising their confirmations; the empty state; and the
 * confirmation dialog carrying Android's exact wording.
 */
@OptIn(ExperimentalTestApi::class)
class NotificationsScreenRenderTest {

    private val row = ProjectNotification(
        id = "n1",
        uuid = "u1",
        text = "Sam posted in Chat",
        pathLabel = "Tools : Call Sheet",
        target = NotificationTarget(tool = "call_sheet_label"),
        // 2026-08-12T14:32:00Z — the stamp is drawn in the machine's zone, so only its presence is asserted.
        createdMillis = 1_786_545_120_000L,
        updatedMillis = 1_786_545_120_000L,
        read = false,
        isGlobal = true,
    )

    @Test
    fun `rows show path, text and stamp, and the deletes ask first`() = runComposeUiTest {
        val events = mutableListOf<NotificationsEvent>()
        setContent {
            ZillitTheme {
                NotificationsScreen(
                    state = NotificationsUiState(loaded = true, rows = listOf(row), hasMore = true),
                    onEvent = { events += it },
                )
            }
        }

        onNodeWithText("Tools : Call Sheet").assertExists()
        onNodeWithText("Sam posted in Chat").assertExists()
        onNodeWithText(row.timeLabel).assertExists()

        onNodeWithContentDescription("Delete notification").performClick()
        onNodeWithText("Delete all").assertIsEnabled().performClick()
        onNodeWithText("Show older").performClick()
        onNodeWithText("Sam posted in Chat").performClick()

        assertEquals(
            listOf(
                NotificationsEvent.AskDelete("n1"),
                NotificationsEvent.AskDeleteAll,
                NotificationsEvent.LoadOlder,
                NotificationsEvent.Open("n1"),
            ),
            events,
        )
    }

    @Test
    fun `an empty list says so and cannot delete all`() = runComposeUiTest {
        setContent {
            ZillitTheme { NotificationsScreen(state = NotificationsUiState(loaded = true), onEvent = {}) }
        }
        onNodeWithText("No notifications").assertExists()
        onNodeWithText("Delete all").assertIsNotEnabled()
    }

    @Test
    fun `the confirmations carry Android's wording and answer Yes or No`() = runComposeUiTest {
        val events = mutableListOf<NotificationsEvent>()
        setContent {
            ZillitTheme {
                NotificationsScreen(
                    state = NotificationsUiState(
                        loaded = true,
                        rows = listOf(row),
                        confirm = NotificationsConfirm.DeleteAll,
                    ),
                    onEvent = { events += it },
                )
            }
        }
        onNodeWithText("Are you sure you want to delete all notification?").assertExists()
        onNodeWithText("No").performClick()
        onNodeWithText("Yes").performClick()
        assertEquals(listOf(NotificationsEvent.CancelDelete, NotificationsEvent.ConfirmDelete), events)
    }
}
