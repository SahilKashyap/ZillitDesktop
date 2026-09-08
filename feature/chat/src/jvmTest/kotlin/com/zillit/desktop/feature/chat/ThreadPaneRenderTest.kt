package com.zillit.desktop.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.ChatReplyRef
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatSeams
import com.zillit.desktop.feature.chat.ui.ChatUiState
import com.zillit.desktop.feature.chat.ui.ClipboardImage
import com.zillit.desktop.feature.chat.ui.ClipboardMediaSource
import com.zillit.desktop.feature.chat.ui.DOWNLOAD_REFUSED
import com.zillit.desktop.feature.chat.ui.LocalChatSeams
import com.zillit.desktop.feature.chat.ui.ThreadPane
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Composes the thread pane's new affordances for real: the message menu on
 * BOTH bubble sides (QA #5 — the popup used to open at the pane's far left
 * for self messages, and only an opening test catches a popup that cannot
 * place itself), the pager, the reply bar and quoted bubble, and the gated
 * media viewer.
 */
@OptIn(ExperimentalTestApi::class)
class ThreadPaneRenderTest {

    private val aisha = CrewContact(userId = "u1", fullName = "Aisha Khan")
    private val now = System.currentTimeMillis()

    private fun theirs(body: String = "Rolling at 8?") = ChatMessage(
        id = "srv-1",
        uniqueId = "w-1",
        senderId = "u1",
        receiverId = "me",
        body = body,
        timestampMillis = now - 60_000L,
        isMine = false,
    )

    private fun mine(body: String = "Copy. On my way.") = ChatMessage(
        id = "srv-2",
        uniqueId = "w-2",
        senderId = "me",
        receiverId = "u1",
        body = body,
        timestampMillis = now - 30_000L,
        isMine = true,
    )

    @Test
    fun `the paperclip opens the phones' attach sheet`() = runComposeUiTest {
        // Android's PickerDialog and iOS's action sheet both put Photo /
        // Video / Document / Audio between the button and the OS dialog.
        var fired: ChatEvent? = null
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(peer = aisha, messages = listOf(theirs())),
                    onEvent = { fired = it },
                )
            }
        }

        onNodeWithContentDescription("Attach a file").performClick()
        waitForIdle()
        listOf("Photo", "Video", "Document", "Audio").forEach { onNodeWithText(it).assertExists() }

        onNodeWithText("Document").performClick()
        waitForIdle()
        assertTrue(fired is ChatEvent.AttachKind, "$fired")
        assertTrue((fired as ChatEvent.AttachKind).kind == com.zillit.desktop.core.media.PreviewKind.Document)
    }

    @Test
    fun `the menu opens on a received bubble`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(peer = aisha, messages = listOf(theirs(), mine())),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Rolling at 8?").performMouseInput { rightClick() }
        waitForIdle()
        onNodeWithText("Reply").assertExists()
        onNodeWithText("Copy").assertExists()
    }

    @Test
    fun `the menu opens on a self bubble too`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(peer = aisha, messages = listOf(theirs(), mine())),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Copy. On my way.").performMouseInput { rightClick() }
        waitForIdle()
        onNodeWithText("Reply").assertExists()
        onNodeWithText("Delete for everyone").assertExists()
    }

    @Test
    fun `Show older sits atop a full window and asks for the page`() = runComposeUiTest {
        val events = mutableListOf<ChatEvent>()
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(
                        peer = aisha,
                        messages = listOf(theirs(), mine()),
                        hasOlder = true,
                    ),
                    onEvent = { events += it },
                )
            }
        }

        onNodeWithText("Show older").assertExists().performClick()
        waitForIdle()
        assertTrue(events.contains(ChatEvent.ShowOlder), "the click asked for the older page")
    }

    @Test
    fun `the reply bar and the quoted bubble both render`() = runComposeUiTest {
        val quoted = mine().copy(
            replyTo = ChatReplyRef(
                messageId = "srv-1",
                senderId = "u1",
                body = "Rolling at 8?",
            ),
        )
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(
                        peer = aisha,
                        messages = listOf(theirs("Original line"), quoted),
                        replyTo = theirs("Original line"),
                    ),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Replying to Aisha Khan").assertExists()
        // The quote block inside the sent bubble names its parent's words.
        onNodeWithText("Rolling at 8?").assertExists()
    }

    @Test
    fun `an image bubble opens the viewer, whose Download refuses without the right`() =
        runComposeUiTest {
            val picture = theirs("").copy(
                attachment = ChatAttachment(
                    media = "s3/key",
                    name = "set.jpg",
                    contentType = "image/jpeg",
                ),
            )
            setContent {
                ZillitTheme {
                    CompositionLocalProvider(
                        LocalChatSeams provides ChatSeams(canDownload = { false }),
                    ) {
                        ThreadPane(
                            state = ChatUiState(peer = aisha, messages = listOf(picture)),
                            onEvent = {},
                            loadThumbnail = { ImageBitmap(4, 4) },
                        )
                    }
                }
            }

            waitForIdle()
            onNodeWithText(DOWNLOAD_REFUSED).assertDoesNotExist()
            onNodeWithContentDescription("set.jpg").performClick()
            waitForIdle()
            onNodeWithText("Download").assertExists("the lightbox opened")
            onNodeWithText("Download").performClick()
            waitForIdle()
            onNodeWithText(DOWNLOAD_REFUSED).assertExists()
        }

    @Test
    fun `Ctrl+V with a picture on the clipboard attaches it through the seam`() = runComposeUiTest {
        val events = mutableListOf<ChatEvent>()
        val clipboard = object : ClipboardMediaSource {
            override fun readImage() = ClipboardImage("shot.png", byteArrayOf(1))
            override fun writeImage(image: ImageBitmap) = true
        }
        setContent {
            ZillitTheme {
                CompositionLocalProvider(
                    LocalChatSeams provides ChatSeams(clipboard = clipboard),
                ) {
                    ThreadPane(
                        state = ChatUiState(peer = aisha, messages = listOf(theirs())),
                        onEvent = { events += it },
                    )
                }
            }
        }

        onNodeWithText("Message Aisha Khan…").performClick()
        waitForIdle()
        onNodeWithText("Message Aisha Khan…").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.V) }
        }
        waitForIdle()
        val pasted = events.filterIsInstance<ChatEvent.ImagePasted>()
        assertTrue(pasted.any { it.name == "shot.png" }, "the paste reached the view model")
    }

    @Test
    fun `a document chip refuses to save without the right`() = runComposeUiTest {
        val doc = theirs("").copy(
            attachment = ChatAttachment(
                media = "s3/key",
                name = "callsheet.pdf",
                contentType = "application/pdf",
            ),
        )
        var opened = false
        setContent {
            ZillitTheme {
                CompositionLocalProvider(
                    LocalChatSeams provides ChatSeams(canDownload = { false }),
                ) {
                    ThreadPane(
                        state = ChatUiState(peer = aisha, messages = listOf(doc)),
                        onEvent = {},
                        onOpenAttachment = { opened = true },
                    )
                }
            }
        }

        onNodeWithText("callsheet.pdf").performClick()
        waitForIdle()
        onNodeWithText(DOWNLOAD_REFUSED).assertExists()
        assertTrue(!opened, "the save-and-open never ran")
    }
}
