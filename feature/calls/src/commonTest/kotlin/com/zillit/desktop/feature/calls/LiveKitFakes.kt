package com.zillit.desktop.feature.calls

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.feature.calls.data.livekit.LiveKitSocket
import com.zillit.desktop.feature.calls.data.livekit.LiveKitSocketFactory
import com.zillit.desktop.feature.calls.data.livekit.SignedJsonHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Line 3's scripted backend, shared by the line's own tests and the
 * coordinator's Line 3 tests: the presence socket as a script, and the REST
 * side with answers keyed by path suffix.
 */

/** The presence socket, as a script: what the line sent, and a way to answer or push events. */
internal class LiveKitFakeSocket : LiveKitSocketFactory, LiveKitSocket {
    val connects = mutableListOf<Pair<String, Map<String, String>>>()
    val frames = mutableListOf<JsonObject>()
    var onFrame: (String) -> Unit = {}
    var onClosed: (String) -> Unit = {}
    var refuse = false

    override suspend fun connect(
        url: String,
        headers: Map<String, String>,
        onFrame: (String) -> Unit,
        onClosed: (reason: String) -> Unit,
    ): LiveKitSocket {
        if (refuse) error("upgrade refused")
        connects += url to headers
        this.onFrame = onFrame
        this.onClosed = onClosed
        return this
    }

    override fun send(frame: String): Boolean {
        frames += Json.parseToJsonElement(frame).jsonObject
        return true
    }

    override fun close(reason: String) = onClosed(reason)

    /** Answers the last request of [type] with [data]. */
    fun answer(type: String, data: String = "{}", ok: Boolean = true) {
        val req = frames.last { it["type"]!!.jsonPrimitive.content == type }
        val id = req["reqId"]!!.jsonPrimitive.content
        val field = if (ok) "\"data\":$data" else "\"error\":$data"
        onFrame("""{"reqId":"$id","ok":$ok,$field}""")
    }

    fun push(event: String) = onFrame(event)

    fun sentTypes() = frames.map { it["type"]!!.jsonPrimitive.content }
}

/** The REST side: every call recorded, answers scripted per path suffix. */
internal class LiveKitFakeHttp : SignedJsonHttp {
    val calls = mutableListOf<Triple<String, JsonObject?, Pair<String?, String?>>>()
    val answers = mutableMapOf<String, String>()

    override suspend fun call(
        verb: HttpVerb,
        url: String,
        body: JsonObject?,
        projectId: String?,
        userId: String?,
    ): ZillitResult<JsonElement?> {
        calls += Triple(url, body, projectId to userId)
        val answer = answers.entries.firstOrNull { url.endsWith(it.key) }?.value ?: "{}"
        // A scripted refusal: `!status:word` answers as the backend's error.
        if (answer.startsWith("!")) {
            val (status, word) = answer.drop(1).split(':', limit = 2)
            return ZillitResult.Failure(
                ZillitError.Http(status = status.toInt(), serverMessage = word, technical = word),
            )
        }
        return ZillitResult.Success(Json.parseToJsonElement(answer))
    }

    fun paths() = calls.map { it.first.substringAfter("/api") }
}

