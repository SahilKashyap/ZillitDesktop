package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.email.domain.MailboxExport
import com.zillit.desktop.feature.email.domain.MailboxExportDownload
import com.zillit.desktop.feature.email.domain.MailboxExportRepository
import com.zillit.desktop.feature.email.domain.MailboxScope
import com.zillit.desktop.feature.email.domain.StartedMailboxExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * `${EMAIL_BASE_URL}/v2/email-export` — the mailbox export contract.
 *
 * Three things it does differently from its neighbours in this package, each
 * because the contract says so:
 *
 *  - **The flag is a query parameter only.** No route takes a request body,
 *    so the shared Accounts mailbox is named with
 *    `use_project_account_mailbox=true` on `POST` and `DELETE` as well as on
 *    the reads — [MailboxScope.query], never [flagBody].
 *  - **`status` is read, not assumed.** `request()` would hand back an empty
 *    list for `{"status":0}`; here a refusal inside a 200 has to be told
 *    apart from "no exports yet", so every call goes through [envelope] and
 *    checks it.
 *  - **Nothing is cached.** An export's progress is the question, and a
 *    remembered answer would show a finished export as still processing (and
 *    a link that has since expired).
 */
class MailboxExportRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val scope: MailboxScope = MailboxScope.Personal,
) : MailboxExportRepository {

    // `coerceInputValues` matters here: `"parts": null` on a record that has
    // not produced any yet would otherwise fail the *whole* export to decode
    // and empty the page while an export is in fact running.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val base get() = "${config.apiV2(ZillitService.Email)}email-export"

    override suspend fun start(): ZillitResult<StartedMailboxExport> =
        when (val answer = send(HttpVerb.Post, base)) {
            is ZillitResult.Failure -> ZillitResult.Failure(answer.error)
            is ZillitResult.Success -> answer.data.checked { envelope ->
                val export = envelope.data.export()
                    ?: return@checked null
                val message = envelope.message.orEmpty()
                StartedMailboxExport(
                    export = export,
                    // The server says which of the two happened; the export's
                    // own status is not enough, since a brand-new one and the
                    // one already running are both `queued`.
                    alreadyRunning = message == ALREADY_IN_PROGRESS,
                    message = message,
                )
            }
        }

    override suspend fun exports(): ZillitResult<List<MailboxExport>> =
        read(base) { envelope ->
            envelope.data.exportRows()
                .mapNotNull { row -> row.decodeExport() }
                .take(MailboxExportRepository.HISTORY_LIMIT)
        }

    override suspend fun export(exportId: String): ZillitResult<MailboxExport> =
        read("$base/$exportId") { envelope -> envelope.data.export() }

    override suspend fun downloadLinks(exportId: String): ZillitResult<MailboxExportDownload> =
        read("$base/$exportId/download") { envelope ->
            val record = envelope.data.exportRecord() ?: return@read null
            runCatching { json.decodeFromJsonElement(MailboxExportDownloadDto.serializer(), record) }
                .getOrNull()
                ?.toDomain(exportId)
                ?.takeIf { it.parts.isNotEmpty() }
        }

    override suspend fun delete(exportId: String): ZillitResult<MailboxExport> =
        when (val answer = send(HttpVerb.Delete, "$base/$exportId")) {
            is ZillitResult.Failure -> ZillitResult.Failure(answer.error)
            is ZillitResult.Success -> answer.data.checked { envelope -> envelope.data.export() }
        }

    private suspend fun <T : Any> read(url: String, transform: (ApiEnvelope) -> T?): ZillitResult<T> =
        when (val answer = send(HttpVerb.Get, url)) {
            is ZillitResult.Failure -> ZillitResult.Failure(answer.error)
            is ZillitResult.Success -> answer.data.checked(transform)
        }

    private suspend fun send(verb: HttpVerb, url: String): ZillitResult<ApiEnvelope> =
        apiClient.envelope(
            verb = verb,
            url = url,
            module = RequestModule.ProjectUser,
            queryParameters = scope.query(),
            // Never from disk: see the class comment.
            options = CallOptions(readCache = false),
        )

    /**
     * The envelope's payload, once `status` has been believed.
     *
     * A stated `status: 0` is a refusal even inside a 200 — the backend
     * reports "export not ready", "expired" and "no credentials" that way —
     * and the message key is carried on so the screen can translate it.
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
                // "Download links ready" inside the red error notice. The
                // generic wording is the honest one — the server said yes and
                // we could not read what it sent.
                ?: ZillitResult.Failure(
                    ZillitError.Http(status = HTTP_OK, technical = "unreadable export payload: $message"),
                )
        }

    private fun JsonElement?.export(): MailboxExport? = exportRecord()?.decodeExport()

    private fun JsonElement.decodeExport(): MailboxExport? =
        runCatching { json.decodeFromJsonElement(MailboxExportDto.serializer(), this) }.getOrNull()?.toDomain()

    private companion object {
        /** The one message key that is an answer rather than a failure (contract §3.1). */
        const val ALREADY_IN_PROGRESS = "email_export_already_in_progress"
        const val HTTP_OK = 200
    }
}
