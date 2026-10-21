package com.zillit.desktop.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatSeams
import com.zillit.desktop.feature.chat.ui.ChatUiState
import com.zillit.desktop.feature.chat.ui.LocalChatSeams
import com.zillit.desktop.feature.chat.ui.ThreadPane
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A document shared in a thread is read in the pane, as a picture is looked at
 * and a clip played there — it used to be saved to Downloads and handed to the
 * OS, so reading a shared script meant leaving the conversation about it.
 *
 * Composed rather than unit-tested for the reason the clip's viewer is: a
 * bubble wired to the wrong handler is a click that silently leaves the app,
 * and no model test can see which handler a bubble was given.
 */
@OptIn(ExperimentalTestApi::class)
class ChatDocumentViewerRenderTest {

    private val aisha = CrewContact(userId = "u1", fullName = "Aisha Khan")
    private val now = System.currentTimeMillis()

    private val sides = ChatAttachment(
        media = "s3/sides.txt",
        name = "sides.txt",
        contentType = "text/plain",
    )

    private val archive = ChatAttachment(
        media = "s3/rushes.zip",
        name = "rushes.zip",
        contentType = "application/zip",
    )

    private fun theirs(file: ChatAttachment) = ChatMessage(
        id = "srv-1",
        uniqueId = "w-1",
        senderId = "u1",
        receiverId = "me",
        body = "",
        timestampMillis = now - 60_000L,
        isMine = false,
        attachment = file,
    )

    @Test
    fun `a shared document is read in the pane, not handed to the OS`() = runComposeUiTest {
        var handedOut: ChatAttachment? = null
        var asked: ChatAttachment? = null

        setContent {
            ZillitTheme {
                CompositionLocalProvider(
                    LocalChatSeams provides ChatSeams(
                        loadDocument = { file ->
                            asked = file
                            "SCENE 12 — INT. STAGE 4".encodeToByteArray()
                        },
                    ),
                ) {
                    ThreadPane(
                        state = ChatUiState(peer = aisha, messages = listOf(theirs(sides))),
                        onEvent = {},
                        onOpenAttachment = { handedOut = it },
                    )
                }
            }
        }

        onNodeWithText("sides.txt").performClick()
        waitForIdle()

        onNodeWithText("SCENE 12 — INT. STAGE 4").assertExists("the document is drawn in the thread")
        onNodeWithContentDescription("Close viewer").assertExists()
        assertSame(sides, asked, "the viewer asked the host for this file's bytes")
        assertNull(handedOut, "clicking a document no longer leaves the app")
    }

    @Test
    fun `the Download beside it still saves, and is the way into what cannot be drawn`() =
        runComposeUiTest {
            var handedOut: ChatAttachment? = null

            setContent {
                ZillitTheme {
                    CompositionLocalProvider(
                        LocalChatSeams provides ChatSeams(loadDocument = { byteArrayOf(1, 2, 3) }),
                    ) {
                        ThreadPane(
                            state = ChatUiState(peer = aisha, messages = listOf(theirs(archive))),
                            onEvent = {},
                            onOpenAttachment = { handedOut = it },
                        )
                    }
                }
            }

            onNodeWithText("rushes.zip").performClick()
            waitForIdle()

            // An archive has no renderer here, so the viewer says so rather
            // than showing an empty stage, and offers the save-and-open that
            // is the only way into it.
            onNodeWithText("Preview not available for this file type.").assertExists()
            onNodeWithText("Download to view").performClick()
            waitForIdle()
            assertSame(archive, handedOut, "the download is what hands it over")
        }
}
