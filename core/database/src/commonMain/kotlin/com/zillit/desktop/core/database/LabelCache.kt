package com.zillit.desktop.core.database

/**
 * The on-disk copy of the translated label dictionaries.
 *
 * Separate from [ProjectCache] and [EmailCache] because its lifetime is
 * different from both: labels belong to a *language*, not to a production or a
 * mailbox, so nothing here is cleared on project switch. [clear] exists for the
 * one case that does invalidate them — a sign-out on a shared machine, where
 * the next user may not read the same language.
 *
 * Keyed by language as well as kind so switching language and switching back
 * does not mean two cold fetches: the previous dictionary is still on disk.
 */
class LabelCache(database: ZillitDatabase, private val nowMillis: () -> Long) {

    private val queries = database.labelCacheQueries

    /**
     * Replaces one dictionary for one language.
     *
     * A wholesale replace of that (language, kind) pair rather than a merge: a
     * key the server stopped sending has been retired, and merging would leave
     * the old translation answering for it forever.
     *
     * An empty [entries] is ignored rather than written. A dictionary that came
     * back empty is a server or parse failure far more often than it is a real
     * emptying, and honouring it would blank every label in the app on the
     * strength of one bad response.
     */
    fun save(language: String, kind: String, entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val at = nowMillis()
        queries.transaction {
            queries.deleteKind(language, kind)
            entries.forEach { (key, value) ->
                queries.upsertLabel(
                    language = language,
                    kind = kind,
                    key = key,
                    // `value_`, not `value`: SQLDelight escapes the column name
                    // away from Kotlin's soft keyword when it generates.
                    value_ = value,
                    cachedAt = at,
                )
            }
        }
    }

    /**
     * Every dictionary held for a language, as `kind -> (key -> value)`.
     *
     * One query for all three kinds rather than three: this runs on the way to
     * the first frame, and the whole point is that the UI does not wait.
     */
    fun load(language: String): Map<String, Map<String, String>> =
        queries.selectForLanguage(language)
            .executeAsList()
            .groupBy { it.kind }
            .mapValues { (_, rows) -> rows.associate { it.key to it.value_ } }

    /** Sign-out. The next user may not read this language. */
    fun clear() {
        queries.deleteAllLabels()
    }
}
