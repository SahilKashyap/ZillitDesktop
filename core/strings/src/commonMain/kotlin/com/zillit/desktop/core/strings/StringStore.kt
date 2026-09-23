package com.zillit.desktop.core.strings

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Loads the catalogue for whichever language is chosen, and installs it.
 *
 * ## English underneath everything
 *
 * English is loaded once and kept; every other language is laid over it per
 * key ([StringCatalog.over]). The translated files trail the English one by a
 * release or so, and the per-key fallback is what keeps that from showing.
 *
 * ## Off the UI thread
 *
 * A language file is close to a megabyte of XML, and parsing one on the
 * thread that draws frames is a visible hitch at exactly the moment someone
 * is watching the app change language. The parse runs on [io]; only the
 * install — a single state write — happens on [Dispatchers.Main], which is
 * where snapshot state is written from everywhere else in the app.
 */
class StringStore(
    private val source: CatalogSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val main: CoroutineDispatcher = Dispatchers.Main,
) {
    private var english: StringCatalog? = null

    /**
     * Loads [language] and installs it into [Strings].
     *
     * Loading the same language twice re-parses nothing: the store installs
     * what it already built. Loading English installs the base catalogue
     * itself, which is why the base is cached rather than rebuilt per call.
     */
    suspend fun load(language: AppLanguage) {
        val catalog = withContext(io) { build(language) }
        withContext(main) { Strings.install(catalog) }
        ZillitLog.i(TAG) { "ready for '${language.code}': ${catalog.size} keys" }
    }

    /**
     * Keeps [Strings] current as the chosen language changes.
     *
     * [languages] is the resolved code stream — the preference with the OS
     * language filling a blank — and it is de-duplicated here so a preference
     * rewrite that lands on the same code does not re-parse a megabyte.
     */
    suspend fun follow(languages: Flow<String>) {
        languages
            .map { code -> AppLanguage.byCode(code) ?: AppLanguage.English }
            .distinctUntilChanged()
            .collect { language -> load(language) }
    }

    private fun build(language: AppLanguage): StringCatalog {
        val base = english ?: (source.load(AppLanguage.English) ?: StringCatalog.Empty).also { english = it }
        if (language == AppLanguage.English) return base
        val translated = source.load(language)
        if (translated == null) {
            ZillitLog.w(TAG) { "nothing shipped for '${language.code}' — showing English" }
            return base
        }
        return translated.over(base)
    }

    private companion object {
        const val TAG = "Strings"
    }
}
