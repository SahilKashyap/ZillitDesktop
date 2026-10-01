package com.zillit.desktop.feature.costumesetsync.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.asRec
import com.zillit.desktop.feature.costumesetsync.domain.asRows
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** What a call answered: the envelope's `data`, and the `message` key a write toasts. */
data class Answer(val data: JsonElement?, val message: String?) {
    val rec: Rec? get() = data.asRec()
    val rows: List<Rec> get() = data.asRows()
}

/**
 * What sends a multipart/form-data upload on the app's signed machinery.
 *
 * `ApiClient` speaks JSON envelopes only, so the one thing the service needs
 * multipart for (`POST /scenes/parse-script`, `POST /schedule/parse`: a `file`
 * part and text fields) has to be supplied by the host app's wiring, which owns
 * the headers/bearer. The answer is the usual envelope, already folded to an [Answer].
 */
fun interface MultipartSender {
    suspend fun send(
        url: String,
        fileName: String,
        bytes: ByteArray,
        mime: String,
        fields: Map<String, String>,
    ): ZillitResult<Answer>
}

/**
 * The Costumes & Set Sync service (`synconsetapi`) — the web's `syncOnsetApi.js`.
 *
 * One generic project-scoped client rather than 135 typed functions: every
 * route is `{host}/api/v2/projects/{projectId}{path}`, answers the Zillit
 * envelope `{status, message, data}` with snake_case fields, and the screens
 * read the records untyped (see [Rec]). Paths are written exactly as the
 * web's helpers write them, so the two can be read side by side.
 *
 * - **No auth of its own.** The project Bearer rides [RequestModule.ProjectUser].
 * - **200 + `status:0` is a failure**, folded into [ZillitResult.Failure] with
 *   the server's message key so `error.localised()` reads it in the user's language.
 * - **Never call before the rights list has confirmed the tool** — a 403
 *   (`costume_set_sync_tool_not_enabled`) anywhere in the app reads as "no
 *   longer a member". [SyncViewer.canCall] is the gate; the screens sit behind it.
 * - Empty query values are dropped, as the web's `qs()` does.
 */
class SyncOnsetApi(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /** The production the token was minted for; null while no project is open. */
    private val projectId: () -> String?,
    /** Sends the two multipart parse calls; null until the app wires one (then [upload] fails cleanly). */
    private val multipart: MultipartSender? = null,
) {

    private val root get() = config.apiV2(ZillitService.SyncOnset).trimEnd('/')

    suspend fun get(path: String, query: Map<String, Any?> = emptyMap()) = call(HttpVerb.Get, scoped(path), null, query)

    suspend fun post(path: String, body: JsonObject? = null) = call(
        HttpVerb.Post,
        scoped(path),
        body ?: buildJsonObject { },
    )

    suspend fun patch(path: String, body: JsonObject) = call(HttpVerb.Patch, scoped(path), body)

    suspend fun put(path: String, body: JsonObject) = call(HttpVerb.Put, scoped(path), body)

    suspend fun delete(path: String, body: JsonObject? = null) = call(HttpVerb.Delete, scoped(path), body)

    /**
     * A multipart upload: one `file` part plus text [fields] (`kind`). The web's
     * `UPLOAD(...)` for `parseScript` / `parseSchedule`.
     */
    suspend fun upload(
        path: String,
        fileName: String,
        bytes: ByteArray,
        mime: String,
        fields: Map<String, String> = emptyMap(),
    ): ZillitResult<Answer> {
        val url = scoped(path) ?: return ZillitResult.Failure(
            ZillitError.Http(status = OK, serverMessage = "libs_something_went_wrong"),
        )
        val sender = multipart ?: return ZillitResult.Failure(ZillitError.Unknown("no multipart sender is wired"))
        return sender.send(url, fileName, bytes, mime, fields)
    }

    /** `GET /v2/meta` — every enum the service knows. Not project-scoped. */
    suspend fun meta() = call(HttpVerb.Get, "$root/meta", null, emptyMap())

    private fun scoped(path: String): String? = projectId()
        ?.takeIf { it.isNotBlank() }
        ?.let { "$root/projects/$it$path" }

    private suspend fun call(
        verb: HttpVerb,
        url: String?,
        body: JsonElement?,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<Answer> {
        // No project means no URL: say so rather than send one with "null" in it.
        if (url == null) return ZillitResult.Failure(
            ZillitError.Http(status = OK, serverMessage = "libs_something_went_wrong"),
        )
        val cleaned = query.filterValues { it != null && it.toString().isNotEmpty() }
        return when (
            val answer = apiClient.envelope(verb, url, RequestModule.ProjectUser, body, cleaned)
        ) {
            is ZillitResult.Failure -> ZillitResult.Failure(answer.error)
            is ZillitResult.Success ->
                if (answer.data.status == 1) {
                    ZillitResult.Success(Answer(answer.data.data, answer.data.message))
                } else {
                    ZillitResult.Failure(
                        ZillitError.Http(
                            status = OK,
                            serverMessage = answer.data.message,
                            messageElements = answer.data.messageElements.orEmpty(),
                        ),
                    )
                }
        }
    }

    private companion object {
        const val OK = 200
    }
}
