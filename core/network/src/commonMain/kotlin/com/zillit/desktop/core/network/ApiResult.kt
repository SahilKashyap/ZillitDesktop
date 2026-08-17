package com.zillit.desktop.core.network

import com.zillit.desktop.core.common.MessageElement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The response envelope every Zillit service returns.
 *
 * `data` is [JsonElement] rather than a type parameter at this level because
 * error responses put an empty object where the success shape expects an array
 * — decoding straight into `T` fails on the error path and masks the real
 * message. Callers decode `data` once the envelope is known good.
 */
@Serializable
data class ApiEnvelope(
    @SerialName("status") val status: Int? = null,
    @SerialName("message") val message: String? = null,
    /**
     * Substitutions for the placeholders in [message]. See [MessageElement] —
     * it is `{search, replacer}`, matching what the web and Android read, not
     * the `{key, value}` this once guessed at while the field was always empty.
     */
    @SerialName("messageElements") val messageElements: List<MessageElement>? = null,
    @SerialName("data") val data: JsonElement? = null,
)

/**
 * HTTP verbs used by the client. Modelled so `ApiClient` has one entry point
 * instead of the Android app's eleven near-identical repository methods.
 */
enum class HttpVerb { Get, Post, Put, Patch, Delete }
