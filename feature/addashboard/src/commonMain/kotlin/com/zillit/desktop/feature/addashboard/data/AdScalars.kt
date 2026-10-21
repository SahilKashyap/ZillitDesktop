package com.zillit.desktop.feature.addashboard.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The scalars this service is loose about.
 *
 * Amounts arrive as a number on one route and a decimal string on another,
 * flags as `true` and as `"true"`, and an id as a number where the sibling
 * route sends a string. Each of these answers null rather than failing the
 * whole record, because one odd field should not blank a page.
 */

/** A number sent either way, or as a blank string meaning "none". */
internal object LooseDouble : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.zillit.ad.LooseDouble", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Double? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        if (element is JsonNull) return null
        return primitive.doubleOrNull ?: primitive.content.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}

/** An integer sent as a number, a string, or a decimal that is really whole. */
internal object LooseInt : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.zillit.ad.LooseInt", PrimitiveKind.STRING)

    @Suppress("ReturnCount") // One guard per form the number arrives in.
    override fun deserialize(decoder: Decoder): Int? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        if (element is JsonNull) return null
        primitive.intOrNull?.let { return it }
        val text = primitive.content.trim().takeIf { it.isNotEmpty() } ?: return null
        return text.toIntOrNull() ?: text.toDoubleOrNull()?.toInt()
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

/**
 * A flag sent as a boolean or as a quoted word.
 *
 * `booleanOrNull` reads a quoted `"true"` as true on its own, which is the
 * wrong answer for a strict flag — so the string form is read deliberately
 * here, and only the two words that mean something are honoured.
 */
internal object LooseBoolean : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.zillit.ad.LooseBoolean", PrimitiveKind.STRING)

    @Suppress("ReturnCount") // One guard per form the flag arrives in.
    override fun deserialize(decoder: Decoder): Boolean? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        if (element is JsonNull) return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.content.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) encoder.encodeNull() else encoder.encodeBoolean(value)
    }
}

/** Any scalar, read as the text it would print as — ids, codes, loose labels. */
internal object LooseString : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.zillit.ad.LooseString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.content.takeIf { it.isNotEmpty() }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}
