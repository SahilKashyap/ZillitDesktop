package com.zillit.desktop.core.localization

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.database.LabelCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live label dictionary: loaded from disk, refreshed from the server.
 *
 * ## Cache-first, network-second
 *
 * The cached dictionary is published before the first request goes out, because
 * the alternative is a first frame of raw `call_sheet_label` keys that flip to
 * words a second later. A failed refresh leaves the cached translations
 * standing — last week's wording is not worse than an identifier.
 *
 * ## One store, one language
 *
 * [refresh] takes the language rather than the store owning one, so a language
 * change is a call rather than a rebuild. Each of the three dictionaries is
 * fetched and stored independently: `messages` failing must not cost the
 * `labels` that already arrived, which is the failure mode that makes a
 * half-translated screen instead of a fully stale one.
 */
class LabelStore(
    private val source: LabelSource,
    /**
     * Null when the local database could not open — no keychain yet, or a first
     * run on a new machine. The store's real job is the fetch; running without
     * the cache costs a cold first frame, never the words themselves. Same
     * reasoning as `ProjectContextLoader`'s cache parameter.
     */
    private val cache: LabelCache?,
) {

    private val state = MutableStateFlow(LabelDictionary.Empty)

    /** For anything that should redraw when a language finishes loading. */
    val dictionary: StateFlow<LabelDictionary> = state.asStateFlow()

    /** The language currently published, so a repeat [refresh] can be skipped. */
    private var loadedLanguage: String? = null

    /**
     * Publishes the cached dictionaries for [language], then refreshes all three.
     *
     * Returns once the network round-trips are done, but the dictionary is
     * usable well before that — callers who only need the cached copy on screen
     * should not await this.
     *
     * Repeating a [language] already loaded still refreshes: the caller asked,
     * and the whole point of a server-owned label table is that it changes
     * without a client release. Only the *cache read* is skipped, since
     * re-reading rows already in memory would flash the stale copy back.
     */
    suspend fun refresh(language: String) {
        val code = language.takeIf { it.isNotBlank() } ?: DEFAULT_LANGUAGE

        if (loadedLanguage != code) {
            state.value = cachedDictionary(code)
            loadedLanguage = code
        }

        LabelKind.entries.forEach { kind -> refresh(kind, code) }

        ZillitLog.i(TAG) { "ready for '$code': ${state.value.breakdown} (${state.value.size} keys)" }
    }

    /**
     * Reads a key without holding the flow.
     *
     * The synchronous shape every reference client settled on — Android's
     * `String.getDataFromLabelKey()`, iOS's `Translator.translate(key:)` — and
     * the one that lets a repository map a DTO without becoming suspending.
     * Reads the current snapshot; a caller that must react to a *later* arrival
     * should collect [dictionary] instead.
     */
    fun translate(key: String, preferring: LabelKind = LabelKind.Labels): String =
        state.value.translate(key, preferring)

    /**
     * Drops the dictionaries, on disk and in memory.
     *
     * **Deliberately not wired to sign-out**, unlike every other cache in the
     * app. Labels are public reference data — the same dictionary every install
     * downloads, holding no trace of who was signed in — and clearing them
     * would cost the next person a sign-in screen of raw keys to protect
     * nothing. Here for tests and for an explicit "reload translations".
     */
    fun clear() {
        cache?.clear()
        state.value = LabelDictionary.Empty
        loadedLanguage = null
    }

    private fun cachedDictionary(language: String): LabelDictionary {
        val stored = cache?.load(language).orEmpty()
        if (stored.isEmpty()) return LabelDictionary.Empty
        return stored.entries.fold(LabelDictionary.Empty) { dictionary, (path, values) ->
            LabelKind.ofPath(path)?.let { dictionary.with(it, values) } ?: dictionary
        }
    }

    private suspend fun refresh(kind: LabelKind, language: String) {
        when (val result = source.fetch(kind, language)) {
            is ZillitResult.Success -> {
                // An empty dictionary is a bad response far more often than a
                // real emptying, and honouring it would blank the whole UI.
                if (result.data.isEmpty()) {
                    ZillitLog.w(TAG) { "${kind.path} came back empty for '$language' — keeping what we have" }
                    return
                }
                cache?.save(language, kind.path, result.data)
                state.value = state.value.with(kind, result.data)
            }
            // Not fatal, and said out loud: a screen full of humanised
            // identifiers with nothing in the log is how "why is this in
            // English" becomes unanswerable.
            is ZillitResult.Failure -> ZillitLog.w(TAG) {
                "could not refresh ${kind.path} for '$language': " +
                    (result.error.technical ?: result.error.userMessage)
            }
        }
    }

    private companion object {
        const val TAG = "Labels"

        /** What the backend falls back to, so asking for it is never wrong. */
        const val DEFAULT_LANGUAGE = "en"
    }
}
