package com.zillit.desktop.core.permissions

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which right a person is asking their admins for.
 *
 * Only the two the permission grid actually issues per tool. Viewing is not
 * here: a tool whose view right is missing never appears in the grid or the
 * rail, so there is no screen to ask from.
 */
enum class RightsKind {
    Post,
    Download,
    ;

    /** The verb the request message uses — Android's two string resources. */
    val verb: String get() = if (this == Post) "post" else "download"
}

/**
 * Where in the rights grid the admin has to look.
 *
 * The grid is split by area, and the message tells the admin which column to
 * open: a tool's rights and a home unit's rights sit on different tabs, and an
 * admin sent to the wrong one reasonably reports the request as impossible.
 * Android passes the same word (`R.string.tools` / `R.string.home`).
 */
enum class RightsArea(val label: String) {
    Tools("Tools"),
    Home("Home"),
}

/**
 * One person asking for one right on one module.
 *
 * [moduleLabel] is what the reader calls the module — "Document Distribution",
 * not `document_distribution_tool`. It reaches an admin's chat verbatim and is
 * the only thing telling them which row to change.
 */
data class RightsRequest(
    val moduleLabel: String,
    val kind: RightsKind,
    val area: RightsArea = RightsArea.Tools,
)

/**
 * The message an admin receives, transcribed from Android's
 * `Context.requestPermissionMessage` (`utils/Extensions.kt:282`) and the four
 * string resources it joins.
 *
 * It is a plain chat message — there is no permission-request endpoint on any
 * client. That is why the wording carries the whole route through the grid:
 * the admin has nothing to tap, so the message has to be the instructions.
 * Kept identical to the phones so a coordinator seeing requests from a mixed
 * crew reads the same sentence every time.
 */
fun rightsRequestMessage(request: RightsRequest): String =
    "Please grant me permission to ${request.kind.verb} in ${request.moduleLabel}. " +
        "Go to ‘Settings’ > ‘Admin Settings’ > " +
        "‘Viewing & Posting Rights Grid’ > Select User > ${request.area.label} > " +
        "Select ${request.moduleLabel} to grant this request."

/**
 * One admin the request can be sent to.
 *
 * The designation rides along because a production runs several admins and
 * "Aisha Khan" alone does not say which one to trouble; the picker shows it
 * under the name, as Android's does.
 */
data class RightsApprover(
    val userId: String,
    val name: String,
    val designation: String? = null,
)

/**
 * What a refused press is told, in one sentence.
 *
 * One function rather than the same string typed into a dozen view models: the
 * two halves have to agree — "asking an administrator" is a promise, and it is
 * only true when a bus was there to carry the request. A module that wrote its
 * own could promise an ask that never went out.
 */
fun rightsRefusalMessage(module: String, kind: RightsKind, asked: Boolean): String =
    "You do not have ${kind.verb} rights on $module" +
        if (asked) " — asking an administrator." else "."

/**
 * A control that stays on screen without the right to use it.
 *
 * ## Why the button is not hidden
 *
 * Hiding it is what every one of these modules did first, and it is the
 * failure QA reported on the phones: someone who cannot find a Send button
 * has no way to learn whether they lack a right or the app is broken, so they
 * report a bug and wait. Keeping it and answering the press with "you do not
 * have this right — ask an admin?" turns a dead end into the one action that
 * actually helps.
 *
 * The press never reaches [action] without [granted]: this swaps the whole
 * handler rather than checking inside it, so a flipped control cannot fall
 * through to a write the server would refuse anyway.
 */
fun gatedClick(granted: Boolean, onDenied: () -> Unit, action: () -> Unit): () -> Unit =
    if (granted) action else onDenied

/**
 * Raised by a module, answered by the app frame.
 *
 * ## Why a bus rather than a parameter per screen
 *
 * The dialog needs three things no feature module has: the production's admin
 * list, a chat socket to send on, and somewhere to float a dialog above a tool
 * window. Threading those into thirty modules would put the same wiring — and
 * the same chance of drift — in each one. A module instead states what it
 * wants, in its own words, and the frame decides what asking looks like.
 *
 * Buffered and conflating on overflow: a request is a click, and a queue of
 * them would open one dialog after another at somebody leaning on a button.
 */
class RightsRequestBus {

    private val pending = MutableSharedFlow<RightsRequest>(
        extraBufferCapacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val requests: Flow<RightsRequest> = pending.asSharedFlow()

    private val _showing = MutableStateFlow(false)

    /**
     * Whether the frame has a rights dialog (or its outcome) on screen.
     *
     * A tool that hosts a heavyweight surface — an embedded Chromium map — hides
     * it while this is true: that view paints above every Compose pixel, so the
     * frame's dialog floated over the tool would simply not be seen, and the
     * person who pressed the control would be left with nothing happening.
     */
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    /** Set by the frame's rights surface as its dialogs open and close. */
    fun setShowing(value: Boolean) {
        _showing.value = value
    }

    fun ask(request: RightsRequest) {
        pending.tryEmit(request)
    }

    /** The module's own words, for the two-argument call sites. */
    fun ask(moduleLabel: String, kind: RightsKind, area: RightsArea = RightsArea.Tools) {
        ask(RightsRequest(moduleLabel, kind, area))
    }

    private companion object {
        const val BUFFER = 4
    }
}
