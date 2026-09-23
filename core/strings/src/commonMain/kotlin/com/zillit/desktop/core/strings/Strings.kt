package com.zillit.desktop.core.strings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.toDisplayLabel
import kotlin.concurrent.Volatile

/**
 * The app-wide reader for the bundled string catalogue.
 *
 * ## The same shape as `Labels`, for the same reason
 *
 * Text reaches everywhere — composables, ViewModels building a state, a
 * `data class` computing its own title — and threading a translator into all
 * of them would put a parameter on half the codebase to serve one concern.
 * `core:localization`'s `Labels` made that call for the server's dictionaries;
 * this makes it for the app's own words.
 *
 * ## Why the catalogue is snapshot state
 *
 * [catalog] is a `mutableStateOf`, not a `StateFlow`. Read during composition
 * it subscribes the caller, so switching language recomposes every `Text`
 * that read a string — with no `collectAsState` at ten thousand call sites and
 * no root-level restart that would drop every open draft. Read from anywhere
 * else it is a plain read. One function, [str], serves both.
 *
 * What does *not* refresh on a switch is a string a ViewModel copied into its
 * state and never recomputed. Prefer holding the key and calling [str] at the
 * point of display; where that is impractical, the state refreshes on its
 * next emission, which for a language change — rare, and made in Settings —
 * is acceptable. Android recreates the activity for the same event.
 *
 * ## Uninstalled is English
 *
 * Before [install], and in a test that wired nothing, the reader holds the
 * bundled English catalogue, read once on first use. So a screen test that
 * asserts on `"Sign out?"` keeps passing after its screen moves to `str()`,
 * and the one frame before the app installs the chosen language shows
 * English rather than humanised keys. Only a platform with no bundle at all
 * falls back to humanising — `start_project` as `Start Project`.
 */
object Strings {

    var catalog: StringCatalog by mutableStateOf(defaultCatalog())
        private set

    /** The language on screen — [AppLanguage.English] until something loads. */
    val language: AppLanguage get() = catalog.language

    /** Called by the store each time a language finishes loading. */
    fun install(catalog: StringCatalog) {
        this.catalog = catalog
    }

    /** Back to the default catalogue. Tests only. */
    fun reset() {
        catalog = defaultCatalog()
    }

    /**
     * The text for [key], with Android-style `%1$s` / `%d` arguments applied.
     *
     * A miss humanises the key rather than showing it, and says so in the log
     * once per key: an English fallback nobody notices is how a translation
     * stays missing for a year.
     */
    fun of(key: String, vararg args: Any?): String {
        val template = catalog[key] ?: missing(key)
        return if (args.isEmpty()) template else formatTemplate(template, args)
    }

    /** The plural form of [key] for [count], formatted with [args] (or with [count] when none). */
    fun plural(key: String, count: Int, vararg args: Any?): String {
        val template = catalog.plural(key, count) ?: missing(key)
        val actual = if (args.isEmpty()) arrayOf<Any?>(count) else args
        return formatTemplate(template, actual)
    }

    fun array(key: String): List<String> = catalog.array(key)

    /**
     * Keys already complained about. Replaced, never mutated, so two threads
     * missing at once cost a duplicate log line rather than a corrupt set.
     */
    @Volatile
    private var reported: Set<String> = emptySet()

    private fun missing(key: String): String {
        if (!catalog.isEmpty && key !in reported) {
            reported = reported + key
            ZillitLog.w("Strings") { "no text for '$key' in ${catalog.language.code} or en — showing the key" }
        }
        return key.toDisplayLabel()
    }
}

/** The text for [key] — `str(S.start_project)`. Recomposes on a language change when called in composition. */
fun str(key: String): String = Strings.of(key)

/** The text for [key] with `%1$s`-style arguments — `str(S.use_my_name, name)`. */
fun str(key: String, vararg args: Any?): String = Strings.of(key, *args)

/** The plural of [key] for [count] — `plural(S.bs_day_count, days)`. */
fun plural(key: String, count: Int, vararg args: Any?): String = Strings.plural(key, count, *args)

/**
 * Android's `String.format(template, args)`, on this platform.
 *
 * Formatted with the root locale on purpose: the digits and separators of a
 * translated sentence come from its author, and a `%d` that turned Arabic
 * under an Arabic UI would also turn Arabic in any text that was later copied
 * into a payload.
 */
internal expect fun formatTemplate(template: String, args: Array<out Any?>): String

/**
 * What the reader holds before anything is installed: the bundled English
 * catalogue where there is one, else nothing.
 */
internal expect fun defaultCatalog(): StringCatalog
