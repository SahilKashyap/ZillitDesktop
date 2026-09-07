package com.zillit.desktop.feature.home

import kotlin.test.assertTrue
import androidx.compose.runtime.mutableStateOf
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
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedScreen
import com.zillit.desktop.feature.home.ui.HomeFeedUiState
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `a notice board's paperclip opens the phones' attach sheet`() = runComposeUiTest {
        var fired: HomeFeedEvent? = null
        setContent {
            ZillitTheme { HomeFeedScreen(state = board(notices), onEvent = { fired = it }) }
        }

        onNodeWithContentDescription("Attach a file").performClick()
        waitForIdle()
        listOf("Photo", "Video", "Document", "Audio").forEach { onNodeWithText(it).assertExists() }

        onNodeWithText("Photo").performClick()
        waitForIdle()
        assertEquals(
            HomeFeedEvent.AttachKind(com.zillit.desktop.core.media.PreviewKind.Image),
            fired,
        )
    }

    @Test
    fun `the call sheet takes documents only, with no sheet to open`() = runComposeUiTest {
        // Both phones hide everything but the document picker there
        // (Android Home.kt:908-911, iOS ProductionVC.swift:1149); a sheet of
        // one collapses to a plain button.
        var fired: HomeFeedEvent? = null
        setContent {
            ZillitTheme { HomeFeedScreen(state = board(callSheet), onEvent = { fired = it }) }
        }

        onNodeWithContentDescription("Attach a document").performClick()
        waitForIdle()

        assertEquals(
            HomeFeedEvent.AttachKind(com.zillit.desktop.core.media.PreviewKind.Document),
            fired,
        )
        onNodeWithText("Photo").assertDoesNotExist()
    }

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

    /**
     * Someone else's captioned picture: Image Reply and Download with posting
     * rights, Gallery for everyone; neither Edit nor Pin (the server's edit
     * route is the author's alone) nor Delete (not the author, not an admin).
     */
    @Test
    fun `someone else's picture offers Image Reply, Download and Gallery, not Edit, Pin or Delete`() =
        runComposeUiTest {
            setContent {
                ZillitTheme { HomeFeedScreen(state = board(notices, myOldPost, theirPhoto), onEvent = {}) }
            }

            onNodeWithText("the door we need").performTouchInput { longClick() }

            onNodeWithText("Image Reply").assertExists()
            onNodeWithText("Download").assertExists()
            onNodeWithText("Gallery").assertExists()
            onNodeWithText("Edit").assertDoesNotExist()
            onNodeWithText("Pin").assertDoesNotExist()
            onNodeWithText("Delete").assertDoesNotExist()
        }

    /**
     * A viewer without posting rights (Android: Reply / Image Reply hidden,
     * Forward and Edit refused on the click): only the read-side items.
     */
    @Test
    fun `without posting rights the menu keeps only Copy, Download, Read by and Gallery`() = runComposeUiTest {
        val viewer = notices.copy(canPost = false)
        setContent {
            ZillitTheme { HomeFeedScreen(state = board(viewer, theirPhoto), onEvent = {}) }
        }

        onNodeWithText("the door we need").performTouchInput { longClick() }

        onNodeWithText("Copy").assertExists()
        onNodeWithText("Download").assertExists()
        onNodeWithText("Read by…").assertExists()
        onNodeWithText("Gallery").assertExists()
        onNodeWithText("Image Reply").assertDoesNotExist()
        onNodeWithText("Forward…").assertDoesNotExist()
        onNodeWithText("Edit").assertDoesNotExist()
        onNodeWithText("Delete").assertDoesNotExist()
        onAllNodesWithText("Reply").assertCountEquals(0)
    }

    /** Gallery — Android's `HomeChatLibraryActivity` — opens over the board with its three tabs. */
    @Test
    fun `Gallery opens the unit's Media Docs Links library`() = runComposeUiTest {
        val state = board(notices, myOldPost, theirPhoto)
        val opened = mutableStateOf(false)
        setContent {
            ZillitTheme {
                HomeFeedScreen(
                    state = if (opened.value) state.copy(libraryOpen = true) else state,
                    onEvent = { if (it is HomeFeedEvent.ShowLibrary) opened.value = true },
                )
            }
        }

        onNodeWithText("the door we need").performTouchInput { longClick() }
        onNodeWithText("Gallery").performClick()

        onNodeWithText("Media").assertExists()
        onNodeWithText("Docs").assertExists()
        onNodeWithText("Links").assertExists()
        onNodeWithText("What has been shared on this board.").assertExists()
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


    /**
     * The flip, on the board: the sentence under the feed now carries a button.
     *
     * It used to end at "ask a production admin", which named no admin and
     * offered no way to reach one. `HomeFeedEvent.RequestPostingRights` picks
     * the admin and writes the message — see `RightsRequestSurface`.
     */
    @Test
    fun `a board with no posting rights offers to ask an admin`() = runComposeUiTest {
        val raised = mutableListOf<HomeFeedEvent>()
        val viewer = notices.copy(canPost = false)
        setContent {
            ZillitTheme { HomeFeedScreen(state = board(viewer, theirPhoto), onEvent = { raised += it }) }
        }

        onNodeWithText("You do not have posting rights for ${viewer.label}.").assertExists()
        onNodeWithText("Ask an admin").performClick()

        assertTrue(
            raised.contains(HomeFeedEvent.RequestPostingRights),
            "the button raised $raised instead of a rights request",
        )
    }
}
