package com.zillit.desktop.core.strings

/**
 * One language's worth of text, keyed the way Android keys it.
 *
 * Immutable: a language is loaded whole and swapped in whole, so a reader
 * holding the previous catalogue shows the previous language until the frame
 * that replaces it — which is the right thing to show.
 *
 * Three tables, mirroring the three Android resource shapes that carry text:
 * `<string>`, `<plurals>` and `<string-array>`. Nothing is humanised here; a
 * miss is a null, and [Strings] decides what a miss looks like on screen.
 */
class StringCatalog(
    val language: AppLanguage,
    private val strings: Map<String, String> = emptyMap(),
    private val plurals: Map<String, Map<String, String>> = emptyMap(),
    private val arrays: Map<String, List<String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = strings.isEmpty() && plurals.isEmpty() && arrays.isEmpty()

    /** Keys across all three tables — for the log, not for logic. */
    val size: Int get() = strings.size + plurals.size + arrays.size

    operator fun get(key: String): String? = strings[key]?.takeIf { it.isNotBlank() }

    /**
     * The plural form for [count], following Android's `quantity` names.
     *
     * The rule is deliberately simple — `zero`, `one`, `two` when the file has
     * them and the count matches, `other` for everything else — rather than
     * CLDR's per-language categories. It is exact for English and for every
     * count that the shipped files actually distinguish; the Android files
     * carry `one` and `other` only, so a fuller rule would choose between
     * forms that do not exist.
     */
    fun plural(key: String, count: Int): String? {
        val forms = plurals[key] ?: return null
        val quantity = when (count) {
            0 -> "zero"
            1 -> "one"
            2 -> "two"
            else -> "other"
        }
        return (forms[quantity] ?: forms["other"] ?: forms.values.firstOrNull())?.takeIf { it.isNotBlank() }
    }

    fun array(key: String): List<String> = arrays[key].orEmpty()

    /**
     * This catalogue's text over [base]'s: what this one lacks, [base] fills.
     *
     * How every non-English language is assembled — French over English — so
     * a key the French file has not caught up with reads in English rather
     * than as a key. Per key, not per file: the translated files trail the
     * English one by a release or so, and a whole-file fallback would show a
     * French user an English app because of a hundred missing strings.
     */
    fun over(base: StringCatalog): StringCatalog = StringCatalog(
        language = language,
        strings = base.strings + strings,
        plurals = base.plurals + plurals,
        arrays = base.arrays + arrays,
    )

    companion object {
        val Empty = StringCatalog(AppLanguage.English)
    }
}
