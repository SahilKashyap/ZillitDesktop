package com.zillit.desktop.core.strings

/**
 * Where a language's text comes from.
 *
 * One implementation in the app — the XML bundled in the jar — and whatever a
 * test wants to hand in. Returns null when nothing is shipped for the
 * language, which the store treats as "English, then".
 */
fun interface CatalogSource {
    fun load(language: AppLanguage): StringCatalog?
}
