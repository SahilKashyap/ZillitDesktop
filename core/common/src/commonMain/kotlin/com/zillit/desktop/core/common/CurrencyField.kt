package com.zillit.desktop.core.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `currency` on the Zillit wire is two shapes, and which one a route sends is
 * not something a client can predict.
 *
 * Older records carry the plain code — `"GBP"` — and newer ones the whole
 * currency: `{"code":"GBP","name":"British Pound Sterling","symbol":"£"}`.
 * A DTO that types the field `String` does not merely miss the name: the
 * *entire response* fails to decode, so one changed record empties a list.
 * That is what took the payroll bank accounts out on 2026-09-12.
 *
 * So type the field `JsonElement?` and read it through here.
 */
fun JsonElement?.currencyCode(): String? = when (this) {
    is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    is JsonObject -> (this["code"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    else -> null
}

/**
 * The symbol the server sent, when it sent one.
 *
 * Preferred over [Money.symbol] for a currency the table knows nothing about:
 * the server's own symbol is right for currencies the symbol table has never
 * heard of, and [Money.symbol] is the fallback when the wire sent only a code.
 */
fun JsonElement?.currencySymbol(): String? =
    (this as? JsonObject)?.let { (it["symbol"] as? JsonPrimitive)?.contentOrNull?.trim() }?.takeIf { it.isNotEmpty() }

/**
 * Reads a `currency` field that may be a code or the whole currency object,
 * and yields the code either way.
 *
 * Use it on a DTO field that is already typed `String`, so no call site
 * changes: `@Serializable(with = CurrencyCodeSerializer::class)`. Writing
 * sends the plain code, which every route accepts.
 */
object CurrencyCodeSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CurrencyCode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return json.decodeJsonElement().currencyCode().orEmpty()
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
