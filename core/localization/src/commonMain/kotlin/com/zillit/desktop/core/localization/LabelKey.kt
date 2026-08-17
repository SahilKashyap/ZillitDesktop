package com.zillit.desktop.core.localization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Turns whatever a caller happened to have into the key the dictionary is
 * keyed on.
 *
 * ## Why this is not just the string
 *
 * Label keys reach the app in three shapes, and every client has had to learn
 * all three the hard way:
 *
 *  1. **Bare** — `call_sheet_label`. The common case.
 *  2. **Namespace-wrapped** — `{designation:second_ad_label}`. The user and
 *     department endpoints emit this; the web strips it in `resolveLabelKey`.
 *  3. **A whole failure envelope** — `{"status":0,"message":"trip_id_required",
 *     "messageElements":[],"data":{}}`. Call sites pass `error.message`
 *     straight in, and for some failures the network layer sets that to the
 *     entire body. Android's ZL-19288 is exactly this: the lookup missed, the
 *     raw JSON fell through, and a toast showed a user `{status:0,…}`.
 *
 * Both unwrappings are sanity-gated so a genuine key that merely looks similar
 * is left alone, and both are idempotent — normalising twice is normalising
 * once.
 */
internal object LabelKey {

    /**
     * `{namespace:key}` — one namespace, one key, no nesting.
     *
     * Restricted to identifier characters on both sides rather than "anything
     * but a brace". The loose form also matches a single-key JSON object:
     * `{"message":"hello"}` parses as namespace `"message"`, key `"hello"`, and
     * unwraps to a quoted fragment. Both sides of a real wrapper are
     * snake_case identifiers, so nothing is lost by saying so.
     */
    private val NAMESPACE_WRAPPER = Regex("""^\{[A-Za-z0-9_-]+:([A-Za-z0-9_.-]+)}$""")

    /** Tolerant on purpose: envelopes are read, never written, from here. */
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Envelope first, then wrapper: a backend that puts a wrapped key in its
     * `message` field is one unwrap away from a hit, and the reverse order
     * would leave it two.
     */
    fun normalise(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        return unwrapNamespace(unwrapEnvelope(trimmed))
    }

    /** `{designation:second_ad_label}` → `second_ad_label`. Anything else, verbatim. */
    private fun unwrapNamespace(raw: String): String =
        NAMESPACE_WRAPPER.find(raw)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: raw

    /**
     * Pulls `message` out of a `{status, message, messageElements, data}` body.
     *
     * Gated on all three of: a leading brace, a literal `"message"`, and a
     * parse that yields an object carrying `status`. A plain key, a sentence, or
     * unrelated JSON without `status` falls straight through — and so does a
     * malformed lookalike, because losing the caller's string is worse than
     * failing to improve it.
     */
    private fun unwrapEnvelope(raw: String): String {
        if (!raw.startsWith("{") || !raw.contains("\"message\"")) return raw
        return try {
            val body = lenientJson.parseToJsonElement(raw) as? JsonObject ?: return raw
            if (body["status"] == null) return raw
            (body["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: raw
        } catch (@Suppress("SwallowedException") illegalArgument: IllegalArgumentException) {
            // `parseToJsonElement` throws this for malformed input. Falling
            // through is the documented behaviour, not an oversight.
            raw
        }
    }
}
