package com.zillit.desktop.core.strings

/**
 * A language the app can be shown in.
 *
 * The list is exactly the set of `values-<code>/strings.xml` folders the
 * Android client ships, because those files *are* the desktop's translations
 * (`scripts/sync-android-strings.py`). A language that Android does not have
 * cannot be offered here: there would be nothing to show but English.
 *
 * [code] is the bare ISO 639 code the Android resource folder is named for. It
 * is also what the label endpoints are asked for (`preset/labels?lang=`), so
 * choosing a language here switches both the bundled text and the server's
 * dictionaries in one preference.
 */
data class AppLanguage(
    val code: String,
    /** The name in English, for the log and for anyone who cannot read [nativeName]. */
    val englishName: String,
    /** The name in the language itself — what a picker shows, as every OS does. */
    val nativeName: String,
    /** Right-to-left script. The theme flips layout direction for these. */
    val rtl: Boolean = false,
) {
    companion object {
        val English = AppLanguage("en", "English", "English")

        /**
         * Every language, English first and the rest by English name, which is
         * the order a picker presents them in.
         */
        val all: List<AppLanguage> = listOf(
            English,
            AppLanguage("ar", "Arabic", "العربية", rtl = true),
            AppLanguage("zh", "Chinese", "中文"),
            AppLanguage("cs", "Czech", "Čeština"),
            AppLanguage("da", "Danish", "Dansk"),
            AppLanguage("nl", "Dutch", "Nederlands"),
            AppLanguage("fil", "Filipino", "Filipino"),
            AppLanguage("fr", "French", "Français"),
            AppLanguage("de", "German", "Deutsch"),
            AppLanguage("he", "Hebrew", "עברית", rtl = true),
            AppLanguage("id", "Indonesian", "Bahasa Indonesia"),
            AppLanguage("it", "Italian", "Italiano"),
            AppLanguage("ja", "Japanese", "日本語"),
            AppLanguage("ko", "Korean", "한국어"),
            AppLanguage("ms", "Malay", "Bahasa Melayu"),
            AppLanguage("no", "Norwegian", "Norsk"),
            AppLanguage("pl", "Polish", "Polski"),
            AppLanguage("pt", "Portuguese", "Português"),
            AppLanguage("ru", "Russian", "Русский"),
            AppLanguage("es", "Spanish", "Español"),
            AppLanguage("sv", "Swedish", "Svenska"),
            AppLanguage("th", "Thai", "ไทย"),
            AppLanguage("tr", "Turkish", "Türkçe"),
        )

        /**
         * Codes the JVM or an older OS reports that name one of ours.
         *
         * Java kept the pre-1989 ISO codes for Hebrew, Indonesian and Yiddish
         * for thirty years and only stopped in 17 (`java.locale.useOldISOCodes`),
         * Norwegian arrives as Bokmål or Nynorsk, and Filipino as Tagalog.
         */
        private val aliases = mapOf(
            "iw" to "he",
            "in" to "id",
            "tl" to "fil",
            "nb" to "no",
            "nn" to "no",
        )

        /**
         * The language for a code, tolerating region and script tags and the
         * aliases above; null when nothing is shipped for it.
         *
         * `zh-Hant-TW`, `pt_BR` and `en_GB` all resolve to their base language:
         * the Android client has one file per language and no regional
         * variants, so there is nothing finer to choose between.
         */
        fun byCode(code: String?): AppLanguage? {
            val base = code?.trim()?.lowercase()?.split('-', '_')?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: return null
            val resolved = aliases[base] ?: base
            return all.firstOrNull { it.code == resolved }
        }

        /**
         * The language to show: [preferred] when it names one we ship, else
         * [fallback] when *it* does, else English.
         *
         * Two codes rather than one because "follow the OS" is a real setting:
         * the preference is blank until someone chooses, and the OS language
         * fills the gap — see `ZillitPreferences.Language`.
         */
        fun resolve(preferred: String?, fallback: String?): AppLanguage =
            byCode(preferred) ?: byCode(fallback) ?: English
    }
}
