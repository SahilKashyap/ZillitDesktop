package com.zillit.desktop.core.strings

/**
 * "Line N", in the user's language.
 *
 * The phones ship Line 1–3 as translated strings, and every translation spells
 * the number as the digit 1, 2 or 3 — so a number past those three is written
 * into Line 1's wording rather than left untranslated.
 */
fun callLineLabel(number: Int): String =
    TRANSLATED.getOrNull(number - 1)?.let { str(it) } ?: str(S.txt_line_one).replace("1", number.toString())

/** Line 1, 2 and 3, in that order. */
private val TRANSLATED = listOf(S.txt_line_one, S.txt_line_two, S.txt_line_three)
