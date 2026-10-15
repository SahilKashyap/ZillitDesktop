package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.chat.domain.ChatExport
import com.zillit.desktop.feature.chat.domain.ChatExportDownload
import com.zillit.desktop.feature.chat.domain.ChatExportRepository
import com.zillit.desktop.feature.chat.domain.StartedChatExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `${CHAT_BASE_URL}/v2/chat-export` — the chat export contract.
 *
 * Two things it does differently from its neighbours in this package, each
 * because the contract says so:
 *
 *  - **`status` is read, not assumed.** `request()` would hand back an empty
 *    list for `{"status":0}`; here a refusal inside a 200 has to be told
 *    apart from "no exports yet", so every call goes through [checked].
 *  - **Nothing is cached.** An export's progress is the question, and a
 *    remembered answer would show a finished export as still processing —
 *    or hand back a signed link that expired an hour ago.
 */
class ChatExportRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : ChatExportRepository {

    // `coerceInputValues` matters here: a `null` where a field is declared
    // non-null would otherwise fail the *whole* export to decode and empty
    // the dialog while an export is in fact running.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val base get() = "${config.apiV2(ZillitService.Chat)}chat-export"

    override suspend fun start(includeMedia: Boolean): ZillitResult<StartedChatExport> {
        val body = buildJsonObject { put("include_media", includeMedia) }
        return when (val answer = send(HttpVerb.Post, base, body)) {
            is ZillitResult.Failure -> ZillitResult.Failure(answer.error)
            is ZillitResult.Success -> answer.data.checked { envelope ->
                val export = envelope.data.export() ?: return@checked null
                val message = envelope.message.orEmpty()
                StartedChatExport(
                    export = export,
                    // The server says which of the two happened; the export's
                    // own status is not enough, since a brand-new one and the
                    // one already running are both `queued`.
                    alreadyRunning = message == ALREADY_IN_PROGRESS,
                    message = message,
                )
            }
        }
    }

    override suspend fun exports(): ZillitResult<List<ChatExport>> =
        read(base) { envelope ->
            envelope.data.chatExportRows()
                .mapNotNull { row -> row.decodeExport() }
                .take(ChatExportRepository.HISTORY_LIMIT)
        }

    override suspend fun export(exportId: String): ZillitResult<ChatExport> =
        read("$base/$exportId") { envelope -> envelope.data.export() }

    override suspend fun downloadLink(exportId: String): ZillitResult<ChatExportDownload> =
        read("$base/$exportId/download") { envelope ->
            val record = envelope.data.chatExportRecord() ?: return@read null
            runCatching { json.decodeFromJsonElement(ChatExportDownloadDto.serializer(), record) }
                .getOrNull()
                ?.toDomain(exportId)
        }

    override suspend fun delete(exportId: String): ZillitResult<ChatExport> =
        when (val answer = send(HttpVerb.Delete, "$base/$exportId")) {
            is ZillitResult.Failure -> ZillitResult.Failure(answer.error)
            is ZillitResult.Success -> answer.data.checked { envelope -> envelope.data.export() }
        }

    private suspend fun <T : Any> read(url: String, transform: (ApiEnvelope) -> T?): ZillitResult<T> =
        when (val answer = send(HttpVerb.Get, url)) {
            is ZillitResult.Failure -> ZillitResult.Failure(answer.error)
            is ZillitResult.Success -> answer.data.checked(transform)
        }

    private suspend fun send(
        verb: HttpVerb,
        url: String,
        body: JsonElement? = null,
    ): ZillitResult<ApiEnvelope> =
        apiClient.envelope(
            verb = verb,
            url = url,
            module = RequestModule.ProjectUser,
            body = body,
            // Never from disk: see the class comment.
            options = CallOptions(readCache = false),
        )

    /**
     * The envelope's payload, once `status` has been believed.
     *
     * A stated `status: 0` is a refusal even inside a 200 — the backend
     * reports "not ready" and "expired" that way — and the message key is
     * carried on so the screen can translate it.
     */
    private fun <T : Any> ApiEnvelope.checked(transform: (ApiEnvelope) -> T?): ZillitResult<T> =
        if (status != null && status != 1) {
            ZillitResult.Failure(
                ZillitError.Http(
                    status = HTTP_OK,
                    serverMessage = message,
                    messageElements = messageElements.orEmpty(),
                ),
            )
        } else {
            transform(this)?.let { ZillitResult.Success(it) }
                // No `serverMessage`: on a `status: 1` the envelope's message
                // is the *success* key, and passing it here would print
                // "We're preparing your chat export" inside the red error
                // notice. The generic wording is the honest one — the server
                // said yes and we could not read what it sent.
                ?: ZillitResult.Failure(
                    ZillitError.Http(status = HTTP_OK, technical = "unreadable chat export payload: $message"),
                )
        }

    private fun JsonElement?.export(): ChatExport? = chatExportRecord()?.decodeExport()

    private fun JsonElement.decodeExport(): ChatExport? =
        runCatching { json.decodeFromJsonElement(ChatExportDto.serializer(), this) }.getOrNull()?.toDomain()

    private companion object {
        /** The one message key that is an answer rather than a failure (contract §2.1). */
        const val ALREADY_IN_PROGRESS = "cnc_chat_export_already_in_progress"
        const val HTTP_OK = 200
    }
}
