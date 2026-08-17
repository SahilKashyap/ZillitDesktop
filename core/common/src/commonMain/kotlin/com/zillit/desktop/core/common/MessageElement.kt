package com.zillit.desktop.core.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * One substitution the server wants made in its own message.
 *
 * The envelope is `{status, message, messageElements, data}`, and `message` is
 * a translation key whose text may contain placeholders:
 *
 * ```
 * "message": "timecard_cannot_edit_status",
 * "messageElements": [{ "search": "{{status}}", "replacer": "paid" }]
 * ```
 *
 * …where the `timecard_cannot_edit_status` entry in the messages dictionary
 * reads `Cannot edit this timecard because it is {{status}}`. Without the
 * substitution the reader is shown the braces.
 *
 * [search] is the **whole placeholder including its braces**, not the bare
 * name — that is what the other clients match on, and it is what makes the
 * substitution a plain string replace rather than a parse.
 *
 * Lives in `core:common` rather than `core:network` because [ZillitError.Http]
 * carries these through to the UI, and the error type cannot depend on the
 * transport that produced it.
 */
@Serializable
data class MessageElement(
    @SerialName("search") val search: String? = null,
    /**
     * The value to put in. Read leniently because the server sends numbers
     * unquoted for counts and ids — `{"search":"{{count}}","replacer":3}` — and
     * a strict String decode fails the whole envelope over one of them. The
     * Android client carries the same tolerance (`StringOrIntAsStringSerializer`).
     */
    @SerialName("replacer")
    @Serializable(with = ScalarAsStringSerializer::class)
    val replacer: String? = null,
)

/**
 * Reads a JSON string, number or boolean as a [String].
 *
 * Encoding always writes a string; nothing in this app sends `messageElements`
 * back, so the asymmetry costs nothing and keeps the decoded type honest.
 */
internal object ScalarAsStringSerializer : KSerializer<String?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ScalarAsString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        val primitive = json.decodeJsonElement() as? JsonPrimitive ?: return null
        return primitive.contentOrNullSafe()
    }

    override fun serialize(encoder: Encoder, value: String?) {
        encoder.encodeString(value.orEmpty())
    }

    /** `null` for a JSON null, the literal text for anything else. */
    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content
}

/**
 * Applies [elements] to [text], leaving it alone when there is nothing to do.
 *
 * A plain replace-all per element, which is all the other clients do — the web
 * in `applyMessageElements`, Android through its `PathElements`. Deliberately
 * not a template engine: the server owns the placeholder names and the client's
 * only job is to put the values where they were asked for.
 *
 * [translate] resolves a replacer that is itself a translation key — a status
 * of `paid` reads `Paid` when the dictionary knows it. It returns null for a
 * miss, and the raw value is then used verbatim: a replacer is a **value**
 * (a name, a count, an id), not a key, so a miss is the normal case and must
 * not be humanised into something the server did not say.
 */
fun applyMessageElements(
    text: String,
    elements: List<MessageElement>,
    translate: (String) -> String? = { null },
): String {
    if (text.isEmpty() || elements.isEmpty()) return text
    return elements.fold(text) { output, element ->
        val search = element.search?.takeIf { it.isNotEmpty() } ?: return@fold output
        // A null replacer means "there is no value" — the placeholder is
        // removed rather than left showing its own braces.
        val raw = element.replacer.orEmpty()
        val replacement = raw.takeIf { it.isNotEmpty() }?.let { translate(it) } ?: raw
        output.replace(search, replacement)
    }
}

/** `{{anything}}`, including the braces. */
private val PLACEHOLDER = Regex("""\{\{[^{}]*}}""")

/** The run of spaces a removed placeholder leaves behind — collapses to one. */
private val REPEATED_SPACE = Regex(""" {2,}""")

/** A space left stranded in front of punctuation — removed outright. */
private val SPACE_BEFORE_PUNCTUATION = Regex(""" +(?=[.,;:!?])""")

/**
 * Drops any placeholder nothing was supplied for.
 *
 * Deliberately **not** part of [applyMessageElements]: that function is a wire
 * contract shared with the other clients, and there an unmatched placeholder is
 * left exactly as it came. This is our own last line of defence, for the case
 * that contract does not cover — the server naming a slot it then sends no
 * value for, or a `messageElements` array we failed to read.
 *
 * The alternative is showing somebody `it is {{status}}`, which reads as broken
 * software rather than as a message. The gap this leaves is tidied up, so the
 * sentence loses a detail instead of gaining a hole.
 */
fun String.withoutUnfilledPlaceholders(): String {
    if (!contains("{{")) return this
    return PLACEHOLDER.replace(this, "")
        // Collapse first, then strip: a run of spaces becomes one space, and
        // only then is that one space removed if it is propping up a full stop.
        // Doing it in a single pass welds the surrounding words together.
        .replace(REPEATED_SPACE, " ")
        .replace(SPACE_BEFORE_PUNCTUATION, "")
        .trim()
}
