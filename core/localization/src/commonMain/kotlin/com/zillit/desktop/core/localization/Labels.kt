package com.zillit.desktop.core.localization

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * ## Reading in composition redraws on a language change
 *
 * [current] touches [LabelRevision] — snapshot state the store bumps on every
 * publish — so a `.localised()` called while composing subscribes its caller
 * to the next dictionary that lands, exactly as `str(S.key)` does for the
 * app's own words. Without that, a language change repainted every `str()`
 * on screen and left every server-named thing beside it — tool names, unit
 * names, departments, dropdown options — in the language before it, until the
 * screen was closed and reopened. Read from a mapper or a ViewModel it is
 * still a plain sample; a string already copied into a state refreshes on
 * that state's next emission.
 *
 * Collecting [dictionary] remains the way to react *outside* composition, and
 * the way to key a `remember`/`LaunchedEffect` on the arrival itself.
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

    /**
     * Whatever is loaded right now; [LabelDictionary.Empty] before install.
     *
     * The revision is read before the value, and discarded: in composition
     * that read is the subscription that makes the caller repaint when the
     * next dictionary arrives. Everywhere else it costs one snapshot read.
     */
    val current: LabelDictionary
        get() {
            LabelRevision.observe()
            return dictionary.value
        }

    /** Called once, from the graph, with [LabelStore.dictionary]. */
    fun install(dictionary: StateFlow<LabelDictionary>) {
        installed.value = dictionary
        LabelRevision.bump()
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
        LabelRevision.bump()
    }

    fun translate(key: String, preferring: LabelKind = LabelKind.Labels): String =
        current.translate(key, preferring)
}

/**
 * How many dictionaries have been published, as snapshot state.
 *
 * A counter rather than the dictionary itself, deliberately: [Labels.current]
 * keeps answering from the installed flow, so a mapper, a test that installs
 * nothing, and a store read before any publish all behave exactly as they did.
 * The counter adds only the one thing a flow cannot give a composable without
 * a `collectAsState` at 700 call sites — an invalidation.
 *
 * Bumped by [LabelStore] on each publish, which happens on whichever thread
 * the fetch returned on. That is safe: snapshot state is written from
 * background threads throughout Compose, and the recomposer's global write
 * observer is what turns the write into a frame.
 */
internal object LabelRevision {

    private var count by mutableStateOf(0)

    /** Reads the revision. In composition, that subscribes the caller. */
    fun observe(): Int = count

    /** Called after a dictionary is published. */
    fun bump() {
        count++
    }
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
