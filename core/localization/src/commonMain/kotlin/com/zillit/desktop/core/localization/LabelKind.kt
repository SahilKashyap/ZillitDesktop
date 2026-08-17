package com.zillit.desktop.core.localization

/**
 * The three dictionaries the backend serves under `preset`.
 *
 * They are separate endpoints because they are separately maintained, not
 * because they mean different things to a reader: all three map a key to text
 * in the requested language, and a key found in any of them is the answer. The
 * split matters only for **precedence** — see [LabelDictionary.resolve].
 *
 * | Kind | Endpoint | What lives there |
 * |---|---|---|
 * | [Labels] | `GET preset/labels?lang=` | UI chrome — `call_sheet_label`, `entertainment_industry_label` |
 * | [Messages] | `GET preset/messages?lang=` | Server-authored user messages — `permanent_trip_id_required` |
 * | [Identifiers] | `GET preset/identifiers?lang=` | Tool and unit identifiers — `transportation_tool` |
 *
 * The iOS client fetches two of these and merges them into one map; Android
 * fetches all three and keeps them apart, which is what lets a message key and
 * a label key of the same name resolve differently. This follows Android.
 */
enum class LabelKind(
    /**
     * The `preset/` path segment, and the value stored in the cache's `kind`
     * column. One constant for both so a rename cannot leave the cache keyed
     * on a name the fetcher no longer uses.
     */
    val path: String,
) {
    Labels("labels"),
    Messages("messages"),
    Identifiers("identifiers"),
    ;

    companion object {
        fun ofPath(path: String): LabelKind? = entries.firstOrNull { it.path == path }
    }
}
