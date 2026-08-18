package com.zillit.desktop.feature.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.ui.CallSheetPrompt
import com.zillit.desktop.feature.home.ui.HomeFeedScreen
import com.zillit.desktop.feature.home.ui.HomeFeedUiState
import kotlin.test.Test

/**
 * Composes the real board — the card menu on a long-press, the call sheet's
 * question, the call sheet's composer — so the phones' rules are seen on the
 * screen they were written for, not only in the model. Same rationale as
 * `ChatScreenRenderTest`: a screen that throws while composing, or a menu
 * that never opens, is invisible to a unit test.
 */
@OptIn(ExperimentalTestApi::class)
class HomeBoardRenderTest {

    private val now = System.currentTimeMillis()

    private val notices = HomeUnit(
        id = "u1",
        identifier = "general_tool",
        unitName = "general_label",
        canView = true,
        canPost = true,
        canDownload = true,
    )
    private val callSheet = notices.copy(id = "cs", identifier = "call_sheet_tool", unitName = "call_sheet_label")

    private val myOldPost = Notice(
        id = "n1",
        body = "the crew call moved to seven",
        authorName = "You",
        authorId = "me",
        createdAtMillis = now - FORTY_MINUTES,
    )
    private val theirPhoto = Notice(
        id = "n2",
        body = "the door we need",
        authorName = "Sam",
        authorId = "them",
        createdAtMillis = now,
        kind = NoticeKind.Image,
        attachment = NoticeAttachment(media = "home/door.jpg", fileName = "door.jpg", bucket = "b", region = "r"),
    )

    private fun board(unit: HomeUnit, vararg posts: Notice) = HomeFeedUiState(
        units = listOf(unit),
        selectedUnitId = unit.id,
        notices = posts.toList(),
        currentUserId = "me",
        nowMillis = now,
    )

    @Test
    fun `a long-press opens the phones' menu, Edit and Delete stay past the window`() = runComposeUiTest {
        setContent {
            ZillitTheme { HomeFeedScreen(state = board(notices, myOldPost, theirPhoto), onEvent = {}) }
        }

        onNodeWithText("the crew call moved to seven").performTouchInput { longClick() }

        // Two inline reply links (one per card) plus the menu's own item.
        onAllNodesWithText("Reply").assertCountEquals(3)
        onNodeWithText("Copy").assertExists()
        onNodeWithText("Forward…").assertExists()
        onNodeWithText("Read by…").assertExists()
        // Own post, forty minutes old: the items stay; the click will explain.
        onNodeWithText("Edit").assertExists()
        onNodeWithText("Delete").assertExists()
        // A text post has nothing to Image Reply to or Download.
        onNodeWithText("Image Reply").assertDoesNotExist()
        onNodeWithText("Download").assertDoesNotExist()
    }

    @Test
    fun `someone else's picture offers Image Reply and Download, not Edit`() = runComposeUiTest {
        setContent {
            ZillitTheme { HomeFeedScreen(state = board(notices, myOldPost, theirPhoto), onEvent = {}) }
        }

        onNodeWithText("the door we need").performTouchInput { longClick() }

        onNodeWithText("Image Reply").assertExists()
        onNodeWithText("Download").assertExists()
        onNodeWithText("Edit").assertDoesNotExist()
        onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun `the call sheet keeps Forward out of its menu`() = runComposeUiTest {
        setContent {
            ZillitTheme { HomeFeedScreen(state = board(callSheet, myOldPost), onEvent = {}) }
        }

        onNodeWithText("the crew call moved to seven").performTouchInput { longClick() }

        onNodeWithText("Read by…").assertExists()
        onNodeWithText("Forward…").assertDoesNotExist()
    }

    @Test
    fun `the call sheet asks continuation or new, then confirms the replace`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HomeFeedScreen(
                    state = board(callSheet, myOldPost).copy(callSheetPrompt = CallSheetPrompt()),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Alert").assertExists()
        onNodeWithText("Continuation").assertExists()
        onNodeWithText("New").assertExists()
        onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `the second question warns about History and takes only Yes`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                HomeFeedScreen(
                    state = board(callSheet, myOldPost)
                        .copy(callSheetPrompt = CallSheetPrompt(confirmingReplace = true)),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Yes").assertExists()
        onNodeWithText("No").assertExists()
        onNodeWithText("Continuation").assertDoesNotExist()
    }

    @Test
    fun `the call sheet's composer takes documents only`() = runComposeUiTest {
        setContent {
            ZillitTheme { HomeFeedScreen(state = board(callSheet, myOldPost), onEvent = {}) }
        }

        onNodeWithContentDescription("Attach a document").assertExists()
        onNodeWithContentDescription("Record a voice message").assertDoesNotExist()
    }

    @Test
    fun `an ordinary unit's composer keeps the microphone`() = runComposeUiTest {
        setContent {
            ZillitTheme { HomeFeedScreen(state = board(notices, myOldPost), onEvent = {}) }
        }

        onNodeWithContentDescription("Attach a file").assertExists()
        onNodeWithContentDescription("Record a voice message").assertExists()
    }

    @Test
    fun `hovering a card shows the kebab, and clicking it opens the same menu`() = runComposeUiTest {
        setContent {
            ZillitTheme { HomeFeedScreen(state = board(notices, myOldPost, theirPhoto), onEvent = {}) }
        }

        onNodeWithText("the crew call moved to seven").performMouseInput { moveTo(center) }
        // One kebab per card, always composed (faded until hover); the first
        // card is the older post.
        onAllNodesWithContentDescription("Message actions").onFirst().performClick()

        onNodeWithText("Copy").assertExists()
        onNodeWithText("Read by…").assertExists()
    }

    private companion object {
        const val FORTY_MINUTES = 40 * 60 * 1000L
    }
}
