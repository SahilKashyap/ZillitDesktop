package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.feature.email.domain.ComposeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How an open composer is showing. */
enum class ComposerWindow {
    /** A card standing on the bottom edge of the mailbox. */
    Docked,

    /** Collapsed to its title bar, still holding everything typed into it. */
    Minimised,

    /** Filling the mailbox, for a message long enough to want the room. */
    Expanded,
}

/**
 * One composer the user has open.
 *
 * Carries what the composer needs to *build* itself rather than its contents:
 * the draft being written lives in that composer's own view model, keyed by
 * [id], and survives being minimised because the view model outlives the
 * chrome around it.
 */
data class OpenComposer(
    val id: String,
    val mode: ComposeMode = ComposeMode.New,
    val replyToId: String? = null,
    val draftId: String? = null,
    val window: ComposerWindow = ComposerWindow.Docked,
    /** Prefilled for a message the app offered to start. See [ComposeViewModel]. */
    val addressedTo: String = "",
    val about: String = "",
)

/**
 * The composers standing on the bottom edge of the mailbox.
 *
 * ## Why a satellite rather than mailbox state
 *
 * The same shape as [FolderEditor] and [MailSearch]: a small holder the mailbox
 * exposes, so opening a composer does not have to travel through the mailbox's
 * own reducer. What is being written is none of the mailbox's business.
 *
 * ## Newest on the right
 *
 * The list is in the order they were opened and laid out from the right, so a
 * new composer takes the corner and pushes the others left — which is where
 * Gmail puts it, and it means the one just opened is always the one under the
 * cursor that opened it.
 */
class ComposerDeck(private val newId: () -> String = countingIds()) {

    private val _state = MutableStateFlow<List<OpenComposer>>(emptyList())
    val state: StateFlow<List<OpenComposer>> = _state.asStateFlow()

    /**
     * Starts a new message, a reply or a forward.
     *
     * Always a new composer, even replying twice to the same message: two
     * replies to one mail is a thing people genuinely do, and silently focusing
     * the first would look like the second click did nothing.
     */
    fun open(mode: ComposeMode, replyToId: String?, addressedTo: String = "", about: String = "") {
        _state.value = _state.value.restored() + OpenComposer(
            id = newId(),
            mode = mode,
            replyToId = replyToId,
            addressedTo = addressedTo,
            about = about,
        )
    }

    /**
     * Reopens a saved draft, or brings its composer forward if it is already up.
     *
     * Unlike [open] this *is* de-duplicated. A draft is a single thing on the
     * server, and two composers editing it would race each other's autosaves —
     * the last one to lose the race quietly discards the other's typing.
     */
    fun openDraft(draftId: String) {
        val existing = _state.value.firstOrNull { it.draftId == draftId }
        if (existing != null) {
            // Already open: un-minimise it rather than adding a second.
            setWindow(existing.id, ComposerWindow.Docked)
            return
        }
        _state.value = _state.value.restored() + OpenComposer(
            id = newId(),
            draftId = draftId,
        )
    }

    fun close(id: String) {
        _state.value = _state.value.filterNot { it.id == id }
    }

    /**
     * Moves one composer between docked, minimised and expanded.
     *
     * Expanding is exclusive: it fills the mailbox, so anything else still
     * standing would be behind it with no way to be reached. Those are
     * minimised rather than closed — they are unfinished mail, not clutter.
     */
    fun setWindow(id: String, window: ComposerWindow) {
        _state.value = _state.value.map { composer ->
            when {
                composer.id == id -> composer.copy(window = window)
                window == ComposerWindow.Expanded -> composer.copy(window = ComposerWindow.Minimised)
                else -> composer
            }
        }
    }

    /** Closes everything. For leaving the production, where the drafts are saved anyway. */
    fun clear() {
        _state.value = emptyList()
    }

    /**
     * Brings any expanded composer back down to the dock.
     *
     * Called before adding one: opening a second composer while the first fills
     * the window would put the new one behind it.
     */
    private fun List<OpenComposer>.restored(): List<OpenComposer> = map { composer ->
        if (composer.window == ComposerWindow.Expanded) {
            composer.copy(window = ComposerWindow.Docked)
        } else {
            composer
        }
    }
}

/**
 * Ids for one deck's composers.
 *
 * A counter rather than a random handle: these live only as long as the mailbox
 * is open and never reach the server, and a counter makes a test's expectations
 * readable. Each deck gets its own, so two decks cannot collide.
 */
private fun countingIds(): () -> String {
    var next = 0
    return { "composer-${next++}" }
}
