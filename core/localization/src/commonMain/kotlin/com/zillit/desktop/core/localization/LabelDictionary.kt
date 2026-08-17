package com.zillit.desktop.core.localization

import com.zillit.desktop.core.common.toDisplayLabel

/**
 * The loaded translations — an immutable snapshot, safe to read from anywhere.
 *
 * Immutable rather than a mutable map behind a lock: a dictionary is replaced
 * wholesale when a fetch lands, and a reader holding the previous one is
 * showing the previous language's words, which is exactly right until the
 * frame that swaps it.
 *
 * ## Resolution order
 *
 * A key is looked for in every dictionary, starting with the one the caller
 * says it came from. Android does the same, and it matters: the backend reuses
 * names across the three tables, and a caller asking for a *message* wants the
 * message table's wording of `access_denied`, not the button label's.
 */
data class LabelDictionary(
    private val entries: Map<LabelKind, Map<String, String>> = emptyMap(),
) {

    val isEmpty: Boolean get() = entries.values.all { it.isEmpty() }

    /** Total keys across all three dictionaries — for logging, not logic. */
    val size: Int get() = entries.values.sumOf { it.size }

    /**
     * Keys per dictionary, as `labels=4210 messages=1802 identifiers=945`.
     *
     * Worth logging rather than just the total: a dictionary that silently
     * failed to load leaves the app working, in English, with no error — the
     * total alone looks healthy, and only the breakdown shows a zero. That is
     * exactly how the startup race in `labelRefreshTrigger` went unnoticed.
     */
    val breakdown: String
        get() = LabelKind.entries.joinToString(" ") { "${it.path}=${entries[it]?.size ?: 0}" }

    /** Returns a copy with one dictionary replaced. */
    fun with(kind: LabelKind, values: Map<String, String>): LabelDictionary =
        copy(entries = entries + (kind to values))

    /**
     * The translation, or null when no dictionary has this key.
     *
     * Null rather than the key itself, so callers can tell a real translation
     * apart from a fallback — [translate] is the one that decides what a miss
     * should look like.
     */
    fun exact(key: String, preferring: LabelKind = LabelKind.Labels): String? =
        lookup(LabelKey.normalise(key), preferring)

    /**
     * The translation, or the best rendering of the input that can be made
     * without one.
     *
     * A miss is humanised where that can only help, so a key the dictionary has
     * not caught up with reads as `Call Sheet` rather than `call_sheet_label`.
     * The web does the same in `useLabelTranslate`; Android returns the raw key
     * and it shows. What "can only help" means depends on [preferring] — see
     * [shouldHumanise], and prefer passing the kind you actually meant.
     *
     * This is why a missing dictionary degrades quietly instead of filling the
     * UI with identifiers, and also why nobody should read a translation *back*
     * into a key — see [keyFor] for the one place that does.
     */
    fun translate(key: String, preferring: LabelKind = LabelKind.Labels): String {
        val normalised = LabelKey.normalise(key)
        if (normalised.isEmpty()) return ""
        return lookup(normalised, preferring)
            ?: if (normalised.shouldHumanise(preferring)) normalised.toDisplayLabel() else normalised
    }

    /**
     * A notification path — `{notices/general_label}` — as `Notices : General`.
     *
     * The backend addresses a notification by the trail that leads to it, and
     * every segment is its own key. Ported from the web's
     * `getLocalStaticDataforNotifiaction`, which is the only place any client
     * renders these.
     */
    fun translatePath(path: String, preferring: LabelKind = LabelKind.Labels): String =
        path.trim()
            .removePrefix("{")
            .removeSuffix("}")
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString(PATH_SEPARATOR) { translate(it, preferring) }

    /**
     * The key a translation came from, or the text itself.
     *
     * A reverse lookup, which is as unpleasant as it sounds — it is here
     * because the search screens filter a list the server sent as keys against
     * text the user can see. iOS carries the same function for the same reason
     * (`Translator.getKey(forValue:)`). Prefer keeping the key beside the text.
     */
    fun keyFor(translation: String): String =
        entries.values.firstNotNullOfOrNull { table ->
            table.entries.firstOrNull { it.value == translation }?.key
        } ?: translation

    /**
     * The already-normalised lookup — the one place that reads [entries].
     *
     * Takes a normalised key so the public entry points can normalise once
     * each; [LabelKey.normalise] is idempotent, but doing it twice per label
     * means parsing every candidate envelope twice per frame.
     *
     * Blank values are treated as absent: the server ships empty strings for
     * keys awaiting translation, and an empty label is indistinguishable from a
     * missing control.
     */
    private fun lookup(normalisedKey: String, preferred: LabelKind): String? {
        if (normalisedKey.isEmpty()) return null
        return orderFor(preferred).firstNotNullOfOrNull { kind ->
            entries[kind]?.get(normalisedKey)?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * The dictionary named first, then the others.
     *
     * Built rather than hardcoded per kind so adding a fourth preset table is
     * one enum entry, not three more lists to keep in agreement.
     */
    private fun orderFor(preferred: LabelKind): List<LabelKind> =
        listOf(preferred) + LabelKind.entries.filter { it != preferred }

    companion object {
        val Empty = LabelDictionary()

        /** What the web joins notification path segments with. */
        private const val PATH_SEPARATOR = " : "

        /**
         * Whether an unresolved string should be humanised or left as it is.
         *
         * Three rules, in order:
         *
         *  1. **Whitespace, never.** Either the backend answered in a sentence
         *     rather than a code, or a person typed the value — an admin naming
         *     a unit. Title-casing someone's deliberate text is a corruption,
         *     and unlike a raw key it is invisible in review.
         *  2. **A `_` or `-` separator, always.** Nothing else produces
         *     `supporting_artistes_tool`, and the separator is what humanising
         *     has to work with in the first place.
         *  3. **A bare token, only when asked for as a label.** `other` is a
         *     dropdown option and should read `Other`; `nope` is a server
         *     message and should read exactly as it was sent. The two are
         *     indistinguishable as strings — the caller's [LabelKind] is the
         *     only thing that tells them apart, which is the argument for
         *     passing the kind you meant rather than taking the default.
         */
        private fun String.shouldHumanise(preferred: LabelKind): Boolean = when {
            any { it.isWhitespace() } -> false
            contains('_') || contains('-') -> true
            else -> preferred != LabelKind.Messages
        }
    }
}
