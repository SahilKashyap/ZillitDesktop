package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.localization.localised

/**
 * The web's `formatLabel` (`data/utils.js:46-73`): a `_label` key through the
 * label table, anything else humanised — underscores to spaces, each word
 * capitalised, a few abbreviations kept upper-case.
 */
object InvoiceLabels {

    fun format(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if (text.endsWith(LABEL_SUFFIX)) {
            val translated = text.localised()
            if (translated.isNotBlank() && translated != text) return translated
        }
        return text.removeSuffix(LABEL_SUFFIX)
            .split('_')
            .joinToString(" ") { word ->
                when {
                    word in SPECIAL_CASE -> word
                    word in UPPERCASE_WORDS -> word.uppercase()
                    else -> word.replaceFirstChar { it.uppercase() }
                }
            }
    }

    /**
     * Hold notes can carry a raw 24-hex user id the server could not name;
     * each id [nameOf] knows is swapped for the name, the rest are left as
     * they are (`POMatchingOverlay.jsx` `resolveUserIdsInText`).
     */
    fun resolveUserIds(text: String, nameOf: (String) -> String?): String =
        USER_ID.replace(text) { match -> nameOf(match.value)?.takeIf { it.isNotBlank() } ?: match.value }

    private const val LABEL_SUFFIX = "_label"
    private val UPPERCASE_WORDS = setOf("sfx", "vfx", "epk", "dit", "covid", "hod", "pa")
    private val SPECIAL_CASE = setOf("1st", "2nd", "3rd")
    private val USER_ID = Regex("\\b[a-fA-F0-9]{24}\\b")
}
