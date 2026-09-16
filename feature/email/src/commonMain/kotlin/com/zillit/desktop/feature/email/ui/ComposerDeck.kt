package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.StoredFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where an open composer is showing. */
enum class ComposerWindow {
    /** In the reading pane, in place of the open message — the web's inline composer. */
    Inline,

    /** Torn off into a workspace window of its own — the web's pop-out. */
    PoppedOut,
}

/**
 * One composer the user has open.
 *
 * Carries what the composer needs to *build* itself rather than its contents:
 * the draft being written lives in that composer's own view model, keyed by
 * [id], and survives the pane being covered because the view model outlives
 * the chrome around it.
 */
data class OpenComposer(
    val id: String,
    val mode: ComposeMode = ComposeMode.New,
    /** The message being answered or forwarded, carried whole: it may have left the pane by now. */
    val replyTo: EmailMessage? = null,
    val draftId: String? = null,
    val window: ComposerWindow = ComposerWindow.Inline,
    /** Prefilled for a message the app offered to start. See [ComposeViewModel]. */
    val addressedTo: String = "",
    val about: String = "",
    /** Words and files another tool handed over — chat's Share. See [ComposeRequest]. */
    val bodyHtml: String = "",
    val attachments: List<StoredFile> = emptyList(),
) {
    /** The workspace route a popped-out composer lives at. */
    val routePath: String get() = "$COMPOSE_POPOUT_PATH/$id"

    val replyToId: String? get() = replyTo?.id

    /** Never prints the message. */
    override fun toString(): String = "OpenComposer(id=$id, mode=$mode, window=$window, draft=$draftId)"
}

/**
 * The composers the mailbox has open, and their view models.
 *
 * ## Why the deck owns the view models
 *
 * A composer moves between the reading pane and a window of its own, which
 * are different compositions: anything remembered inside either dies in the
 * move, and the first casualty was every word typed into the message. The
 * deck builds a composer's view model once, through [factory], and hands the
 * same instance to whichever surface is drawing it.
 *
 * ## One inline composer
 *
 * The web has one composer in the pane; starting another replaces it. The
 * one replaced is closed *saving* — its autosave has usually run already,
 * and closing saves whatever it had not — so nothing typed is lost. Popped
 * out composers stand apart and are not replaced.
 */
class ComposerDeck(
    private val factory: (OpenComposer) -> ComposeViewModel,
    private val newId: () -> String = countingIds(),
) {

    private val _state = MutableStateFlow<List<OpenComposer>>(emptyList())
    val state: StateFlow<List<OpenComposer>> = _state.asStateFlow()

    private val viewModels = mutableMapOf<String, ComposeViewModel>()

    /** The composer standing in the reading pane, if any. */
    val inline: OpenComposer? get() = _state.value.firstOrNull { it.window == ComposerWindow.Inline }

    fun viewModel(id: String): ComposeViewModel? = viewModels[id]

    /**
     * Starts a new message, a reply or a forward in the pane.
     *
     * Always a new composer, even replying twice to the same message: two
     * replies to one mail is a thing people genuinely do, and silently
     * focusing the first would look like the second click did nothing.
     */
    @Suppress("LongParameterList") // Every seed a composer can open with.
    fun open(
        mode: ComposeMode,
        replyTo: EmailMessage?,
        addressedTo: String = "",
        about: String = "",
        bodyHtml: String = "",
        attachments: List<StoredFile> = emptyList(),
    ): OpenComposer =
        place(
            OpenComposer(
                id = newId(),
                mode = mode,
                replyTo = replyTo,
                addressedTo = addressedTo,
                about = about,
                bodyHtml = bodyHtml,
                attachments = attachments,
            ),
        )

    /**
     * Reopens a saved draft, or brings its composer forward if it is already up.
     *
     * Unlike [open] this *is* de-duplicated. A draft is a single thing on the
     * server, and two composers editing it would race each other's autosaves —
     * the last one to lose the race quietly discards the other's typing.
     */
    fun openDraft(draftId: String): OpenComposer {
        _state.value.firstOrNull { it.draftId == draftId }?.let { return it }
        return place(OpenComposer(id = newId(), draftId = draftId))
    }

    /** Puts [composer] in the pane, closing — and saving — whatever was there. */
    private fun place(composer: OpenComposer): OpenComposer {
        inline?.let { close(it.id) }
        viewModels[composer.id] = factory(composer)
        _state.value = _state.value + composer
        return composer
    }

    /** Tears the composer off into its own window; the pane is free again. */
    fun popOut(id: String) {
        _state.value = _state.value.map { if (it.id == id) it.copy(window = ComposerWindow.PoppedOut) else it }
    }

    /** The composer at a pop-out route, for the window that serves it. */
    fun atRoute(path: String): OpenComposer? = _state.value.firstOrNull { it.routePath == path }

    /**
     * Closes a composer. Saving by default: the view model's own scope is
     * about to go, so the save is asked for here, on the way out, where it
     * can still run.
     */
    fun close(id: String, save: Boolean = true) {
        val viewModel = viewModels.remove(id)
        if (save) viewModel?.onEvent(ComposeEvent.Closing)
        _state.value = _state.value.filterNot { it.id == id }
    }

    /** Closes everything. For leaving the production, where the drafts are saved anyway. */
    fun clear() {
        _state.value.map { it.id }.forEach { close(it) }
    }
}

/**
 * A message another screen asked the mailbox to start — the crew list's
 * "write to", the help page, and chat's Share, which brings the words as
 * HTML and the line's file already in storage
 * (the web's `openCompose({ emailBody, attachments })`).
 */
data class ComposeRequest(
    val addressedTo: String,
    val about: String = "",
    val bodyHtml: String = "",
    val attachments: List<StoredFile> = emptyList(),
)

/**
 * The queue other screens post compose requests to.
 *
 * A request is held until the mailbox window collects it — so one posted
 * before the window exists is not lost, and one posted while it is open
 * raises the composer at once. Read once and cleared: reopening mail later
 * must not raise a composer the user never asked for a second time.
 */
class ComposeRequests {
    private val _pending = MutableStateFlow<ComposeRequest?>(null)
    val pending: StateFlow<ComposeRequest?> = _pending.asStateFlow()

    fun post(
        addressedTo: String,
        about: String = "",
        bodyHtml: String = "",
        attachments: List<StoredFile> = emptyList(),
    ) {
        _pending.value = ComposeRequest(addressedTo, about, bodyHtml, attachments)
    }

    /** Takes the waiting request, if any, leaving nothing behind. */
    fun claim(): ComposeRequest? = _pending.value?.also { _pending.value = null }
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

/** Where a popped-out composer lives; the id follows. */
const val COMPOSE_POPOUT_PATH = "/email/compose"
