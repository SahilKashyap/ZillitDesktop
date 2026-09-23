package com.zillit.desktop.core.strings

import com.zillit.desktop.core.common.ZillitLog

/**
 * The catalogues shipped in the jar: `i18n/strings-<code>.xml`, one per
 * Android language, plus `i18n/desktop-<code>.xml` for text the desktop has
 * and the phones do not.
 *
 * `desktop-en.xml` is the source of truth for that text (every key on `S`
 * comes from it or from Android); a `desktop-<code>.xml` holds translations
 * of those keys for one language, and may cover any subset of them. Each
 * desktop file is laid *under* the Android file for the same language: where
 * both name a key, Android's wording wins, so a desktop-only key that later
 * gains an Android translation is picked up by the next sync with no code
 * change. What neither has falls back to English per key in `StringStore`.
 */
class BundledCatalogSource : CatalogSource {

    override fun load(language: AppLanguage): StringCatalog? {
        val android = read(language, "i18n/strings-${language.code}.xml")
        val desktop = read(language, "i18n/desktop-${language.code}.xml")
        return when {
            android == null -> desktop
            desktop == null -> android
            else -> android.over(desktop)
        }
    }

    private fun read(language: AppLanguage, path: String): StringCatalog? {
        val stream = BundledCatalogSource::class.java.classLoader.getResourceAsStream(path) ?: return null
        return try {
            stream.use { AndroidStringsXml.parse(language, it) }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            // A malformed file for one language must cost that language, not
            // the app: the store falls back to English and the log names it.
            ZillitLog.w("Strings") { "could not read $path: ${failure.message}" }
            null
        }
    }
}

/**
 * English from the bundle, parsed once. The first read of [Strings] pays
 * for it — roughly a tenth of a second — which the app spends during graph
 * construction and a test spends in its first assertion.
 */
private val bundledEnglish: StringCatalog by lazy {
    BundledCatalogSource().load(AppLanguage.English) ?: StringCatalog.Empty
}

internal actual fun defaultCatalog(): StringCatalog = bundledEnglish
