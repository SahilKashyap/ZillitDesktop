package com.zillit.desktop.core.workspace.forward

import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.common.ZillitResult

/**
 * Forwarding, as every surface in the app agrees to speak it.
 *
 * The web has one modal for this — `components/common/dropDownComponent/
 * forward/ForwardMsgModal.jsx` — and every surface that can forward (the
 * unit chats, C&C, the gallery drawer, Box Schedule, the map) opens that same
 * modal with a chat-message-shaped payload. Its list is six destinations plus
 * whatever film tools the message's type and the viewer's rights allow.
 *
 * This file is that contract: a neutral payload ([ForwardItem]), the list as
 * the picker draws it ([ForwardSection]), and the host's seam for everything
 * the asking surface cannot reach by itself ([ForwardReach]). A board knows
 * how to post to its own units and a thread knows how to send to its own
 * people; neither knows the other exists, and nothing in a feature module
 * should.
 */

/** What is being forwarded, in the terms every destination understands. */
data class ForwardItem(
    val text: String = "",
    val kind: ForwardKind = ForwardKind.Text,
    val file: ForwardFile? = null,
    val place: ForwardPlace? = null,
)

/**
 * The wire's `message_type`, which every destination also speaks. It decides
 * what the web offers: text may go to the notes boards, a picture to the
 * casting and wardrobe tools, a PDF to the distribution and budget tools
 * (`addToolsForForward`).
 */
enum class ForwardKind {
    Text,
    Image,
    Video,
    Audio,
    Document,
    Location,
    ;

    /** The word the wire uses. */
    val wire: String get() = name.lowercase()
}

/**
 * A file already in the production's storage.
 *
 * Forwarding never re-uploads: every unit and room of a production reads the
 * same bucket, so the keys stay valid and only the reference travels (the
 * web's `generateForwardSingleMessagePayload` does the same).
 */
data class ForwardFile(
    val media: String,
    val name: String = "",
    val contentType: String? = null,
    /** The extension, roughly — `pdf`, `jpg`. The wire's `content_subtype`. */
    val subtype: String? = null,
    val bucket: String? = null,
    val region: String? = null,
    val thumbnail: String? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val durationMillis: Long = 0,
    val sizeBytes: Long = 0,
) {
    /** A PDF, by subtype or by name — what the document tools will take. */
    val isPdf: Boolean
        get() = subtype?.trimStart('.').equals("pdf", ignoreCase = true) ||
            name.endsWith(".pdf", ignoreCase = true) ||
            media.endsWith(".pdf", ignoreCase = true)

    /** Never prints the key or the name — file names on a production are content. */
    override fun toString(): String = "ForwardFile(kindHint=$subtype, bytes=$sizeBytes)"
}

/** A shared place. */
data class ForwardPlace(val latitude: Double, val longitude: Double)

/** One destination, as the picker draws it. */
sealed interface ForwardEntry {
    /** Opaque to the picker; the surface and the reach agree on what it means. */
    val id: String
    val label: String
}

/**
 * A destination that is ticked and sent with the others — a board unit, a
 * person, a room. The web's checkbox lists (`UsersList`, `GroupChat`).
 */
data class ForwardPick(
    override val id: String,
    override val label: String,
    /** A second line: a designation, a board's name, "no posting rights". */
    val caption: String? = null,
    /** A person's id, for their face; a room or a unit has none. */
    val avatarUserId: String? = null,
    val icon: ImageVector? = null,
) : ForwardEntry

/**
 * A destination that asks something first, so it cannot be ticked alongside
 * others: the web's tool entries, each of which opens its own modal (a
 * document name, a scene, a continuation-or-new question), and Email, which
 * opens the composer. Clicking one closes the picker and hands over.
 */
data class ForwardStep(
    override val id: String,
    override val label: String,
    val caption: String? = null,
    val icon: ImageVector? = null,
) : ForwardEntry

/**
 * A destination with destinations inside it — the web's `isSubMenu` tools,
 * whose entry opens Selects / Shortlist / Final (`generateLCWSubMenu`) or a
 * tool's own tabs.
 */
data class ForwardGroup(
    override val id: String,
    override val label: String,
    val children: List<ForwardEntry>,
    val icon: ImageVector? = null,
    /** A second line on the heading — "12 people", so the list says how long it is before it opens. */
    val caption: String? = null,
) : ForwardEntry

/** A heading and its destinations — "Home Tab", "People", "Groups". */
data class ForwardSection(val title: String, val entries: List<ForwardEntry>)

/**
 * What a destination wants to know before it will take the item.
 *
 * The web asks with a modal per tool — a document name and a colour for a
 * D.O.D page, a scene and a page number for a script page, a date for a
 * budget, "continuation or new?" before a report. They are all the same
 * handful of questions, so they are described here and drawn once
 * ([ForwardFormDialog]) rather than ported one dialog at a time.
 */
data class ForwardForm(
    val targetId: String,
    val title: String,
    val fields: List<ForwardField>,
    /** A line above the fields — what the tool will do with the answers. */
    val note: String? = null,
)

/** One question on a [ForwardForm]. Answers come back as strings, keyed by [key]. */
sealed interface ForwardField {
    val key: String
    val label: String
    val required: Boolean

    /** A line of words — a document's name, a scene or page number, an episode. */
    data class Text(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        val initial: String = "",
        val hint: String? = null,
    ) : ForwardField

    /** A day, `yyyy-MM-dd` — a script's date, a budget's. */
    data class Day(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        val initial: String = "",
    ) : ForwardField

    /** One of a list — a page colour, a department, a folder that already exists. */
    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<ForwardChoice>,
        override val required: Boolean = false,
        val initial: String = "",
    ) : ForwardField
}

/** One option of a [ForwardField.Choice]; [swatch] is a `#RRGGBB` for a colour. */
data class ForwardChoice(val value: String, val label: String, val swatch: String? = null)

/** What a [ForwardStep]'s click led to. */
sealed interface ForwardStepOutcome {
    /** The host took it from here — the mailbox composer opened. */
    data object Handled : ForwardStepOutcome

    /** It cannot go there, and this is what to say. */
    data class Refused(val message: String) : ForwardStepOutcome

    /** It can go there once these are answered. */
    data class Asks(val form: ForwardForm) : ForwardStepOutcome
}

/**
 * Everything a surface cannot reach on its own: other boards, the chat
 * halves, the mailbox, the film tools.
 *
 * Wired by the host, which is the only layer that can see every tool at
 * once. Null in a feature module means the surface offers its own
 * destinations alone — which is what it did before this existed, so a host
 * that wires nothing loses nothing.
 */
interface ForwardReach {
    /**
     * What [item] may be forwarded to, with rights already applied.
     *
     * [from] names the surface asking — a board's segment (`home`, `info`),
     * or `chat` — so its own destinations are not offered twice.
     */
    suspend fun sections(item: ForwardItem, from: String?): List<ForwardSection>

    /** Sends one copy of [item] to each ticked destination. */
    suspend fun deliver(targetIds: List<String>, item: ForwardItem): ZillitResult<Unit>

    /**
     * A [ForwardStep] was clicked. The host either takes over, refuses, or
     * answers with the form that destination needs filled in.
     *
     * Suspending because some forms are built from the server's own lists —
     * the D.O.D folders a page can join, the production's departments.
     */
    suspend fun open(targetId: String, item: ForwardItem): ForwardStepOutcome

    /** The form's Send: the answers, by field key. */
    suspend fun submit(
        targetId: String,
        item: ForwardItem,
        answers: Map<String, String>,
    ): ZillitResult<Unit>
}
