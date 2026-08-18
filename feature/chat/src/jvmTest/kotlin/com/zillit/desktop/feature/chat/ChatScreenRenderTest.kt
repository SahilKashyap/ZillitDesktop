package com.zillit.desktop.feature.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatScreen
import com.zillit.desktop.feature.chat.ui.ChatUiState
import com.zillit.desktop.feature.chat.ui.ThreadPane
import androidx.compose.ui.test.performClick
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.data.ReadReceipt
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Composes the Chat & Calls surfaces for real.
 *
 * ## Why render rather than screenshot
 *
 * Screenshot verification needs macOS screen-recording permission, which CI
 * does not have. Composing the actual screens exercises the same layout code
 * and catches the two failures unit tests cannot: a screen that throws while
 * composing, and a nested-scroll arrangement that crashes on an unbounded
 * constraint. Both are how a pane ships looking fine in review and blank in
 * use.
 */
@OptIn(ExperimentalTestApi::class)
class ChatScreenRenderTest {

    private val crew = listOf(
        CrewContact(
            userId = "u1",
            fullName = "Aisha Khan",
            designation = "focus_puller_label",
            department = "camera_label",
            email = "aisha@example.com",
            isAdmin = true,
            deviceId = "d1",
        ),
        CrewContact(userId = "u2", fullName = "Vivek Mishra", department = "camera_label"),
    )

    /**
     * The real clock, deliberately.
     *
     * The day chips inside [ThreadPane] are computed against
     * `Clock.System.now()` — the pane takes no clock of its own — so a message
     * pinned to a hard-coded instant is "Yesterday" only while the calendar
     * agrees. This was a fixed constant standing for 12 August, and the test
     * began failing the morning the date rolled past it, for no reason a
     * reader would connect to chat.
     *
     * Anchoring to the same clock the pane reads makes "a day ago" mean it on
     * every day. The label functions themselves stay deterministic: they take
     * `now` as a parameter and are unit-tested that way.
     */
    private val now = System.currentTimeMillis()

    private fun message(id: String, at: Long, mine: Boolean, body: String) = ChatMessage(
        id = id,
        uniqueId = id,
        senderId = if (mine) "me" else "u1",
        receiverId = if (mine) "u1" else "me",
        body = body,
        timestampMillis = at,
        isMine = mine,
    )

    @Test
    fun `the directory composes with tabs, search and grouped crew`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ChatScreen(crew = crew, loadAvatar = { null })
            }
        }

        onNodeWithText("Chat & Calls").assertExists()
        onNodeWithText("Contacts").assertExists()
        onNodeWithText("Aisha Khan").assertExists()
        onNodeWithText("Vivek Mishra").assertExists()
    }

    /**
     * The header answers "who is this" completely.
     *
     * The email used to live only on the contact card, which sat between the
     * crew list and the conversation. That card is no longer on the way, so
     * the address belongs here or nowhere.
     */
    @Test
    fun `the thread header carries name, role and email`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(peer = crew.first()),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Aisha Khan").assertExists()
        onNodeWithText("aisha@example.com").assertExists()
    }

    /** A peer with no address gets no blank line where one would be. */
    @Test
    fun `a peer without an email shows no address line`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(peer = crew[1]),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Vivek Mishra").assertExists()
        onAllNodesWithText("@", substring = true).assertCountEquals(0)
    }

    @Test
    fun `an open thread composes with day chips and both bubble sides`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(
                        peer = crew.first(),
                        messages = listOf(
                            message("m1", now - 86_400_000L, mine = false, body = "Rolling at 8?"),
                            message("m2", now - 60_000L, mine = true, body = "Copy. On my way."),
                        ),
                    ),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Rolling at 8?").assertExists()
        onNodeWithText("Copy. On my way.").assertExists()
        onNodeWithText("Yesterday").assertExists()
        // The header names the peer; the row list may too, so first is enough.
        onAllNodesWithText("Aisha Khan").onFirst().assertExists()
    }

    @Test
    fun `an empty thread invites rather than blanks`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(peer = crew.first()),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("No messages yet.").assertExists()
        onNodeWithText("Say hello to Aisha", substring = true).assertExists()
    }

    @Test
    fun `a typing peer gets the live indicator`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(
                        peer = crew.first(),
                        messages = listOf(
                            message("m1", now - 60_000L, mine = false, body = "hold on"),
                        ),
                        peerTyping = true,
                    ),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Aisha is typing…").assertExists()
    }
}

/**
 * The crew row opens the conversation.
 *
 * Kept apart from the composition tests above because it needs a real view
 * model: the behaviour under test is the wiring between the row and
 * [ChatEvent.OpenThread], and a screen with no view model cannot show it.
 *
 * The regression it guards is a click that stops at a card. That card's only
 * action was "Message", so reaching a conversation from the Contacts tab cost two
 * clicks while the Chats tab beside it cost one.
 */
@OptIn(ExperimentalTestApi::class)
class CrewRowOpensThreadTest {

    private val crew = listOf(
        CrewContact(userId = "u1", fullName = "Aisha Khan", department = "camera_label"),
        CrewContact(userId = "u2", fullName = "Vivek Mishra", department = "camera_label"),
    )

    private fun viewModel(repository: ChatRepository) = ChatViewModel(
        repository = repository,
        nowMillis = { 1_786_507_000_000L },
        newUniqueId = { "uid" },
    )

    @Test
    fun `one click on a crew row opens that person's thread`() = runComposeUiTest {
        val repository = StubChatRepository()
        val model = viewModel(repository)

        setContent {
            ZillitTheme {
                ChatScreen(crew = crew, loadAvatar = { null }, viewModel = model)
            }
        }

        // Before the click the pane invites a choice rather than showing one.
        onNodeWithText("Pick a contact to see their card.").assertExists()

        onNodeWithText("Aisha Khan").performClick()
        waitForIdle()

        // The thread is open on that person: the composer is the proof, since
        // it exists only inside a thread.
        assertEquals("u1", model.state.value.peer?.userId)
        assertTrue(repository.historyFor.contains("u1"), "the thread's history was not read")
    }

    @Test
    fun `clicking a second person switches the thread rather than stacking`() = runComposeUiTest {
        val repository = StubChatRepository()
        val model = viewModel(repository)

        setContent {
            ZillitTheme {
                ChatScreen(crew = crew, loadAvatar = { null }, viewModel = model)
            }
        }

        onNodeWithText("Aisha Khan").performClick()
        waitForIdle()
        onNodeWithText("Vivek Mishra").performClick()
        waitForIdle()

        assertEquals("u2", model.state.value.peer?.userId)
    }

    @Test
    fun `the signed-in user is not offered as a contact`() = runComposeUiTest {
        val repository = StubChatRepository()
        val model = viewModel(repository)

        setContent {
            ZillitTheme {
                ChatScreen(crew = crew, selfId = "u2", loadAvatar = { null }, viewModel = model)
            }
        }

        onNodeWithText("Aisha Khan").assertExists()
        // Vivek is signed in: still in the crew for name resolution, not a row.
        onNodeWithText("Vivek Mishra").assertDoesNotExist()
    }
}

/** The repository reduced to what opening a thread touches. */
private class StubChatRepository : ChatRepository {

    val historyFor = mutableListOf<String>()

    override val incoming: Flow<ChatMessage> = emptyFlow()
    override val deletions: Flow<List<String>> = emptyFlow()
    override val connections: Flow<Unit> = emptyFlow()
    override val typing: Flow<Pair<String, Boolean>> = emptyFlow()
    override val receipts: Flow<ReadReceipt> = emptyFlow()
    override val edits: Flow<ChatMessage> = emptyFlow()
    override val selfReads: Flow<String> = emptyFlow()

    override fun selfId(): String? = "me"
    override suspend fun sendReaction(messageId: String, emoji: String): ChatMessage? = null
    override suspend fun join() = Unit
    override suspend fun recentPeers(): ZillitResult<List<String>> = ZillitResult.Success(emptyList())
    override fun cached(otherUserId: String): List<ChatMessage>? = null
    override fun lastMessageOf(otherUserId: String): ChatMessage? = null
    override suspend fun markRead(peerId: String, messageId: String, isGroup: Boolean) = Unit
    override fun markThreadRead(peerId: String, uptoMillis: Long) = Unit
    override fun unreadCounts(): Map<String, Int> = emptyMap()
    override fun newestActivity(): Map<String, Long> = emptyMap()
    override suspend fun sendTyping(receiverId: String, started: Boolean) = Unit

    override suspend fun deleteMessages(
        messageIds: List<String>,
        isGroup: Boolean,
    ): ZillitResult<List<String>> = ZillitResult.Success(emptyList())

    override suspend fun history(
        otherUserId: String,
        nowMillis: Long,
        isGroup: Boolean,
    ): ZillitResult<List<ChatMessage>> {
        historyFor += otherUserId
        return ZillitResult.Success(emptyList())
    }

    override suspend fun send(
        receiverId: String,
        body: String,
        uniqueId: String,
        nowMillis: Long,
        isGroup: Boolean,
        attachment: ChatAttachment?,
    ): ZillitResult<Unit> = ZillitResult.Success(Unit)

    override suspend fun rooms(): ZillitResult<List<GroupRoom>> = ZillitResult.Success(emptyList())
}
