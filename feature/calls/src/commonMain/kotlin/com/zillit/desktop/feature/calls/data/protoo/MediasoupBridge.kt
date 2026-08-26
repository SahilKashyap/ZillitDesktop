package com.zillit.desktop.feature.calls.data.protoo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What the media page says back, once it is doing Line 1 work.
 *
 * A type of its own rather than more [com.zillit.desktop.feature.calls.domain.CallEngineEvent]
 * cases: these are a conversation between the session and its page, not facts
 * about the call, and most of them mean nothing to anything above the engine.
 */
sealed interface MediasoupPageEvent {

    /** The page loaded its device and is reporting what it can do. */
    data class Loaded(
        val rtpCapabilities: JsonObject,
        val sctpCapabilities: JsonObject?,
    ) : MediasoupPageEvent

    /**
     * The page needs the SFU consulted and is blocked until it is answered.
     *
     * This is mediasoup-client's `connect` or `produce` callback: it cannot
     * proceed without a server round-trip, and only this side can make one.
     */
    data class Ask(val askId: Long, val method: String, val data: JsonObject) : MediasoupPageEvent

    /** A remote track is up. [share] distinguishes a shared screen from a camera. */
    data class Consumer(
        val consumerId: String,
        val peerId: String,
        val kind: String,
        val share: Boolean,
    ) : MediasoupPageEvent

    /** Something failed inside the page. [where] names the step. */
    data class Failed(val where: String, val message: String) : MediasoupPageEvent
}

/**
 * Reads one page message, or null if it is not a Line 1 one.
 *
 * Returning null for everything else is what lets the Agora parser and this one
 * share a single message channel: each ignores what is not addressed to it.
 */
fun parseMediasoupPageEvent(message: String): MediasoupPageEvent? {
    val obj = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return null
    return when (obj.text("type")) {
        "ms-loaded" -> MediasoupPageEvent.Loaded(
            rtpCapabilities = obj["rtpCapabilities"] as? JsonObject ?: return null,
            sctpCapabilities = obj["sctpCapabilities"] as? JsonObject,
        )

        // An ask with no id can never be answered, and the page is blocked on
        // it, so a half-read one is worse than none.
        "ms-ask" -> obj.readAsk()

        "ms-consumer" -> MediasoupPageEvent.Consumer(
            consumerId = obj.text("consumerId").orEmpty(),
            peerId = obj.text("peerId").orEmpty(),
            kind = obj.text("kind").orEmpty(),
            share = (obj["share"] as? JsonPrimitive)?.content?.toBoolean() ?: false,
        )

        "failed" -> MediasoupPageEvent.Failed(
            where = obj.text("where").orEmpty(),
            message = obj.text("message").orEmpty(),
        )

        else -> null
    }
}

/**
 * The scripts that drive the page's media module.
 *
 * Every value crosses as JSON rather than being spliced into the source: these
 * carry ICE credentials and SDP-shaped blobs, and one unescaped quote in a
 * codec name would be a syntax error inside the page that reports as a call
 * that simply never connects.
 */
object MediasoupScripts {

    fun load(routerRtpCapabilities: JsonObject): String =
        "zillitMs.load(JSON.parse(${quote(routerRtpCapabilities.toString())}))"

    fun createTransports(send: JsonObject, recv: JsonObject, iceServersJson: String): String =
        "zillitMs.createTransports(" +
            "JSON.parse(${quote(send.toString())})," +
            "JSON.parse(${quote(recv.toString())})," +
            "JSON.parse(${quote(iceServersJson)}))"

    fun produceMic(deviceId: String): String = "zillitMs.produceMic(${quote(deviceId)})"

    fun produceCam(deviceId: String): String = "zillitMs.produceCam(${quote(deviceId)})"

    fun consume(params: JsonObject): String =
        "zillitMs.consume(JSON.parse(${quote(params.toString())}))"

    fun closeConsumer(consumerId: String): String =
        "zillitMs.closeConsumer(${quote(consumerId)})"

    /** Answers one page ask. `ok` false carries a reason the page turns into a rejection. */
    fun settle(askId: Long, ok: Boolean, payload: JsonObject): String =
        "zillitMs.settle($askId, $ok, JSON.parse(${quote(payload.toString())}))"

    fun setMic(muted: Boolean): String = "zillitMs.setMic($muted)"

    fun setCam(enabled: Boolean): String = "zillitMs.setCam($enabled)"

    /**
     * Publishes the screen as a second producer, marked in its appData.
     *
     * On [sourceId] when the app's picker chose one; the whole desktop
     * otherwise. Quoted like every other opaque string that crosses into the
     * page.
     */
    fun produceScreenScript(sourceId: String?): String =
        "zillitMs.produceScreen(${sourceId?.let(::quote) ?: "null"})"

    const val STOP_SCREEN = "zillitMs.stopScreen()"

    /** Tears the whole media side down. Safe to send twice. */
    const val LEAVE = "zillitMs.leave()"

    private fun quote(value: String): String =
        Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))
}

private fun JsonObject.readAsk(): MediasoupPageEvent.Ask? {
    val id = (this["askId"] as? JsonPrimitive)?.longOrNull ?: return null
    val method = text("method") ?: return null
    return MediasoupPageEvent.Ask(id, method, this["data"] as? JsonObject ?: JsonObject(emptyMap()))
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
