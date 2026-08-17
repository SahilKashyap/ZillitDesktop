package com.zillit.desktop.core.localization

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app-wide reader for the loaded dictionary.
 *
 * ## Why a global, in a codebase that injects everything else
 *
 * Because label lookup is the one thing that genuinely reaches everywhere. It
 * happens inside `data class` properties (`HomeUnit.label`), inside DTO mappers
 * and inside `object` catalogues — places with no constructor to inject into
 * and no business being made suspending or `@Composable` to get a word out.
 * Threading a translator to all of them would put a parameter on half the
 * domain layer to serve one concern.
 *
 * Every reference client reached the same conclusion by a different route:
 * Android has file-level `projectLabels` behind `String.getDataFromLabelKey()`,
 * iOS has `Translator.shared`, and the web has one Redux slice every component
 * reads through `getLocalStaticData`.
 *
 * The cost is contained deliberately:
 *
 *  - it holds **a reader, not the data** — [LabelStore] owns the dictionary,
 *    is constructed in the graph like everything else, and is what tests use;
 *  - it is **written once**, by `AppGraph.build`;
 *  - **uninstalled is a valid state**, not a crash: reads fall back to
 *    humanising the key, so a unit test that never wired it still gets
 *    `Call Sheet` out of `call_sheet_label`.
 *
 * Anything that must *redraw* when a language arrives should collect
 * [LabelStore.dictionary] instead. This answers "what does this key say now".
 */
object Labels {

    /** What reads see before anything is installed, and after [reset]. */
    private val nothingLoaded: StateFlow<LabelDictionary> = MutableStateFlow(LabelDictionary.Empty)

    /**
     * The install slot. [MutableStateFlow] rather than a plain `var` because
     * the install happens on the startup thread and the reads happen on every
     * other one — this is the module's existing dependency that gives that
     * write safe publication.
     */
    private val installed = MutableStateFlow(nothingLoaded)

    /**
     * The live dictionary, for UI that must repaint when a language lands.
     *
     * A one-shot read cannot do that job: [translate] and [current] sample the
     * value, and Compose has no way to know the sample went stale. A screen
     * that composed before the fetch returned therefore kept its fallbacks
     * forever — which is exactly what the production picker did, showing
     * `feature_label` on every card until it was reopened.
     *
     * Collect this instead wherever the text is on screen at startup:
     *
     *     val labels by Labels.dictionary.collectAsState()
     *     Text(labels.translate(key))
     *
     * Read at composition, so it must be installed before the first frame —
     * `AppGraph.build` does that, well before any window exists.
     */
    val dictionary: StateFlow<LabelDictionary> get() = installed.value

    /** Whatever is loaded right now; [LabelDictionary.Empty] before install. */
    val current: LabelDictionary get() = dictionary.value

    /** Called once, from the graph, with [LabelStore.dictionary]. */
    fun install(dictionary: StateFlow<LabelDictionary>) {
        installed.value = dictionary
    }

    /**
     * Uninstalls the reader. Tests only.
     *
     * Not called on sign-out: the dictionary holds no trace of who was signed
     * in, and dropping it would cost the next person a sign-in screen of
     * humanised keys to protect nothing. See [LabelStore.clear].
     */
    fun reset() {
        installed.value = nothingLoaded
    }

    fun translate(key: String, preferring: LabelKind = LabelKind.Labels): String =
        current.translate(key, preferring)
}

/**
 * The display text for a label key: `call_sheet_label` → `Call Sheet`.
 *
 * The workhorse. Use it wherever a server-supplied identifier is about to be
 * shown to someone — unit names, tool names, group headings, dropdown options.
 *
 * Equivalent to Android's `String.getDataFromLabelKey()` with `isFromLabel`
 * true, and to `getLocalStaticData(key, localTranslationData)` on the web.
 */
fun String.localised(): String = Labels.translate(this, LabelKind.Labels)

/**
 * The display text for a server *message* key.
 *
 * Separate from [localised] only in precedence — the message table is consulted
 * first — but the distinction is the point: `access_denied` has one wording as
 * a heading and another as the reason a request failed, and the caller is the
 * only one who knows which it wanted.
 *
 * This is what an error path should call. It also unwraps the failure envelopes
 * that some backends put in `message` verbatim (see [LabelKey]), so a toast
 * shows a sentence rather than a JSON dump.
 */
fun String.localisedMessage(): String = Labels.translate(this, LabelKind.Messages)
