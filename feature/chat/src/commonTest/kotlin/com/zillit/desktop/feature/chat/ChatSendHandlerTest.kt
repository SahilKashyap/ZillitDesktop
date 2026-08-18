package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.sync.SyncContext
import com.zillit.desktop.core.sync.SyncOperation
import com.zillit.desktop.core.sync.SyncOutcome
import com.zillit.desktop.core.sync.SyncScope
import com.zillit.desktop.core.sync.SyncState
import com.zillit.desktop.feature.chat.data.CHAT_SEND_KIND
import com.zillit.desktop.feature.chat.data.ChatSendHandler
import com.zillit.desktop.feature.chat.data.QueuedChatSend
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * What the outbox does with each way a send can fail: only the server saying
 * no is final. The engine runs the moment a production's scope exists —
 * seconds before the socket is up and the chat key has arrived — so "not
 * ready yet" must be a retry, or every message queued offline dies on the
 * first pass after reconnecting.
 */
class ChatSendHandlerTest {

    private val op = SyncOperation(
        id = "m1",
        scope = SyncScope("me", "p1"),
        kind = CHAT_SEND_KIND,
        label = "Message",
        payload = Json.encodeToString(
            QueuedChatSend.serializer(),
            QueuedChatSend("u-aisha", "hi", "m1", 1_000L, isGroup = false),
        ),
        state = SyncState.Pending,
        nextAttemptAt = 0L,
        createdAt = 0L,
        updatedAt = 0L,
    )
    private val context = object : SyncContext {
        override suspend fun dependencyResult(operation: SyncOperation): String? = null
        override suspend fun updatePayload(operation: SyncOperation, payload: String) = Unit
        override fun nowMillis(): Long = 0L
    }

    private suspend fun outcomeFor(error: ZillitError?): SyncOutcome {
        val repository = FakeChatRepository().apply { sendError = error }
        return ChatSendHandler(repository).execute(op, context)
    }

    @Test
    fun `a production still opening is a retry, not a failure`() = runTest {
        assertIs<SyncOutcome.RetryLater>(outcomeFor(ZillitError.Storage("message encryption failed")))
        assertIs<SyncOutcome.RetryLater>(outcomeFor(ZillitError.Unauthorized("no open project")))
        assertIs<SyncOutcome.RetryLater>(outcomeFor(ZillitError.NoConnection("socket is not connected")))
        assertIs<SyncOutcome.RetryLater>(outcomeFor(ZillitError.Unknown("ack failed: timed out")))
    }

    @Test
    fun `the server saying no is final, and a good send is done`() = runTest {
        assertIs<SyncOutcome.Failed>(outcomeFor(ZillitError.Unknown("receiver not on project")))
        assertIs<SyncOutcome.Done>(outcomeFor(null))
    }
}
