package com.zillit.desktop.feature.scriptsupervision.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.asRec
import com.zillit.desktop.feature.scriptsupervision.domain.asRows
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** What a call answered: the envelope's `data`, and the `message` key a write toasts. */
data class Answer(val data: JsonElement?, val message: String?) {
    val rec: Rec? get() = data.asRec()
    val rows: List<Rec> get() = data.asRows()
}

/**
 * The Continuity Script Supervision service — the web's `scriptSupervision/api.js`
 * and the five screen-folder `api.js` files that build on it.
 *
 * One generic client rather than a hundred typed functions: every route is
 * `{host}/api/v2{path}`, answers the Zillit envelope `{status, message, data}`
 * with snake_case fields, and the screens read the records untyped (see [Rec]),
 * exactly as the web's do. Paths are written at the call sites the way the
 * web's helpers write them, so the two can be read side by side.
 *
 * ## The project id is in the path AND in the token
 *
 * Unlike Costumes & Set Sync, this service does not scope every route the same
 * way: a list or a create carries the project (`/continuity/sound-reports/{projectId}`)
 * while an update or a delete carries the record (`/continuity/sound-reports/{id}`).
 * So nothing is prefixed here automatically — [projectPath] is what a caller
 * uses where the web appends `projectId()`, and it answers null with no
 * production open rather than sending the word "null" in a URL.
 *
 * The service answers 403 `script_supervision_project_mismatch` when the id in
 * the path is not the token's project, and scopes every record to the token's
 * project in any case.
 *
 * - **No auth of its own.** The project Bearer rides [RequestModule.ProjectUser].
 * - **200 + `status:0` is a failure**, folded into [ZillitResult.Failure] with
 *   the server's message key, so `error.localised()` reads it in the user's
 *   language. Write outcomes are toasted from that key alone — the copy is the
 *   backend's, and the web is explicit that this module adds no success or
 *   error strings of its own.
 * - Empty query values are dropped, as the web's params are.
 */
class ScriptSupervisionApi(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    /** The production the token was minted for; null while no project is open. */
    private val projectId: () -> String?,
) {

    private val root get() = config.apiV2(ZillitService.ScriptSupervision).trimEnd('/')

    /** The open production, or null — what every `{projectId}` in a path needs. */
    fun project(): String? = projectId()?.takeIf { it.isNotBlank() }

    suspend fun get(path: String, query: Map<String, Any?> = emptyMap()) =
        call(HttpVerb.Get, path, null, query)

    suspend fun post(path: String, body: JsonObject? = null) =
        call(HttpVerb.Post, path, body ?: buildJsonObject { })

    /** The service takes PUT, not PATCH, for every edit. */
    suspend fun put(path: String, body: JsonObject) = call(HttpVerb.Put, path, body)

    suspend fun delete(path: String) = call(HttpVerb.Delete, path, null)

    /** The full address of [path], for a raw file the JSON client cannot fetch (a report's PDF). */
    fun url(path: String): String = "$root$path"

    private suspend fun call(
        verb: HttpVerb,
        path: String,
        body: JsonElement?,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<Answer> {
        val cleaned = query.filterValues { it != null && it.toString().isNotEmpty() }
        return when (
            val answer = apiClient.envelope(verb, url(path), RequestModule.ProjectUser, body, cleaned)
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

/**
 * The service's continuity records, the web's `crud(path)`.
 *
 * Six screens in `shoot/` and six in `takeLogs/` are the same four calls over
 * a different folder, so they are one object here as they are one factory
 * there. The list is a plain array of the whole project, newest first, which
 * the screens filter by `scene_id` themselves.
 */
class ContinuityCrud(private val api: ScriptSupervisionApi, private val folder: String) {

    suspend fun list(query: Map<String, Any?> = emptyMap()): ZillitResult<Answer> =
        api.project()?.let { api.get("/continuity/$folder/$it", query) } ?: noProject()

    suspend fun create(body: JsonObject): ZillitResult<Answer> =
        api.project()?.let { api.post("/continuity/$folder/$it", body) } ?: noProject()

    suspend fun update(id: String, body: JsonObject): ZillitResult<Answer> =
        api.put("/continuity/$folder/$id", body)

    suspend fun remove(id: String): ZillitResult<Answer> = api.delete("/continuity/$folder/$id")
}

/**
 * No production open: say so rather than send a URL with "null" in it.
 *
 * The same shape a refused call takes, so a caller's `error.localised()` reads
 * a sentence either way.
 */
internal fun noProject(): ZillitResult<Answer> = ZillitResult.Failure(
    ZillitError.Http(status = 200, serverMessage = "libs_something_went_wrong"),
)
