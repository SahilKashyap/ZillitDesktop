package com.zillit.desktop.feature.drive.domain

/**
 * The version history of one file, as `GET /versions/{id}/history` answers it.
 *
 * The web's `versionHistory/VersionHistoryPanel.jsx` reads exactly these
 * fields. Two things are worth knowing before reading the rest:
 *
 *  - **Saves are grouped into sessions.** One stretch of someone having the
 *    file open in the editor produces several saves (autosave, then a manual
 *    one, then one on close), and listing them flat reads as twelve changes
 *    when there was one sitting. A session's own row is its newest save; the
 *    older ones are nested under a disclosure.
 *  - **The permissions come with it.** `can_edit` / `can_download` are the
 *    caller's rights *on the file*, decided server-side, and they are what
 *    gates naming, restoring and downloading a version — not the listing row's
 *    permissions, which a shared file does not always carry.
 *
 * The flat [DriveVersion] list ([DriveRepository.versions]) is still what the
 * details panel shows; this is the panel beside it. A file whose history has
 * no sessions falls back to that list rather than showing an empty panel.
 */
data class DriveVersionHistory(
    val fileName: String = "",
    val canEdit: Boolean = false,
    val canDownload: Boolean = false,
    /** The server kept only the most recent versions; say so rather than imply completeness. */
    val truncated: Boolean = false,
    /** Newest first, as the server orders them. */
    val sessions: List<DriveVersionSession> = emptyList(),
) {
    val isEmpty: Boolean get() = sessions.isEmpty()

    /** Versions somebody gave a name — what the "only named" filter counts. */
    val namedCount: Int get() = sessions.sumOf { session -> session.versions.count { it.name.isNotBlank() } }
}

/** One stretch of editing, and the saves it produced (newest first). */
data class DriveVersionSession(
    val id: String,
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    /** Everyone who had the file open during it — the session row's faces. */
    val editorIds: List<String> = emptyList(),
    val versions: List<DriveFileVersion> = emptyList(),
) {
    val size: Int get() = versions.size
}

/**
 * One save inside a session.
 *
 * [editorIds] is who was editing when it was saved, not only whose browser
 * wrote it: co-editing means a save holds other people's work, and showing
 * just the writer credits one person with everyone's changes (the web's
 * `b5d27d3cd`).
 */
data class DriveFileVersion(
    val id: String,
    val versionNumber: Int = 0,
    /** A name somebody gave it — "Final draft". Blank for the great majority. */
    val name: String = "",
    val savedAt: Long = 0,
    val savedById: String = "",
    val editorIds: List<String> = emptyList(),
    val saveType: DriveSaveType = DriveSaveType.Manual,
    /** Set on a restore, when the server knows which version it came from. */
    val restoredFromNumber: Int? = null,
    val isCurrent: Boolean = false,
    /**
     * A row standing in for the file as it is now, which the server
     * manufactures when nothing has been saved through the editor yet. It has
     * no stored object, so it can be neither downloaded, named nor restored.
     */
    val synthetic: Boolean = false,
    /** The comparison summary the listing carries, so a row can say what changed. */
    val changes: DriveVersionChanges? = null,
) {
    /** Who to show as the people on this save; the writer alone when nothing else is known. */
    val people: List<String>
        get() = editorIds.filter { it.isNotBlank() }.ifEmpty { listOfNotNull(savedById.takeIf { it.isNotBlank() }) }
}

/** How a version came to exist. The server's `save_type`. */
enum class DriveSaveType(val wire: String) {
    Autosave("autosave"),
    Manual("manual"),
    Exit("exit"),
    Restore("restore"),
    Upload("upload"),

    /** A version from before the editor recorded any of this. */
    Legacy("legacy"),
    ;

    companion object {
        /**
         * An unrecognised type is a [Manual] save, which is what the web's
         * `SAVE_TYPE_LABELS[...] || 'Saved'` falls back to: a new server-side
         * type should read as "Saved", never as a blank line.
         */
        fun of(wire: String?): DriveSaveType =
            entries.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) } ?: Manual
    }
}

/**
 * What changed in one version against the one before it.
 *
 * Two independent halves, and both can be absent:
 *
 *  - the **value comparison** ([status], [cells], [sheets], [groups]), which
 *    Drive runs in the background — hence [DriveChangeStatus.Pending] and
 *    [DriveChangeStatus.Running], and hence the socket event that says it has
 *    finished;
 *  - the **activity** ([activity]), built from the live editors' own edit log,
 *    which is the only thing that knows about a version where somebody only
 *    resized two columns. Null for a version saved outside a live session,
 *    which is why "formatting isn't listed" is said for those and not for the
 *    others (the web's `0e81e3820`).
 */
data class DriveVersionChanges(
    val status: DriveChangeStatus = DriveChangeStatus.Unknown,
    /** Why it was skipped — `too_large`, `format_changed`, `unsupported_type`. */
    val reason: String = "",
    /** Changed cells or paragraphs, as the summary counts them. */
    val cells: Int = 0,
    /** Sheets, slides or sections they fall in. */
    val sheets: Int = 0,
    /** Changes in [groups], which is capped — see [truncated]. */
    val total: Int = 0,
    val truncated: Boolean = false,
    val groups: List<DriveChangeGroup> = emptyList(),
    val activity: DriveVersionActivity? = null,
) {
    /** Whether the comparison finished and found something, so its count is worth printing. */
    val hasValues: Boolean get() = status == DriveChangeStatus.Done && cells > 0

    val isWorking: Boolean
        get() = status == DriveChangeStatus.Pending || status == DriveChangeStatus.Running
}

enum class DriveChangeStatus(val wire: String) {
    /** The first version: nothing to compare it with. */
    None("none"),
    Pending("pending"),
    Running("running"),
    Done("done"),
    Failed("failed"),
    Skipped("skipped"),

    /** A status this client has not heard of. Says nothing rather than guessing. */
    Unknown(""),
    ;

    companion object {
        fun of(wire: String?): DriveChangeStatus =
            entries.firstOrNull { it.wire.isNotEmpty() && it.wire.equals(wire?.trim(), ignoreCase = true) } ?: Unknown
    }
}

/** One sheet, slide or document body, with the changes inside it. */
data class DriveChangeGroup(
    val name: String = "",
    val status: DriveGroupStatus = DriveGroupStatus.Changed,
    /** The group's own total, which can exceed [changes] when the list was capped. */
    val total: Int = 0,
    val changes: List<DriveCellChange> = emptyList(),
)

/** Whether a whole sheet appeared, went, or was edited. */
enum class DriveGroupStatus(val wire: String) {
    Added("added"),
    Removed("removed"),
    Changed("changed"),
    ;

    companion object {
        fun of(wire: String?): DriveGroupStatus =
            entries.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) } ?: Changed
    }
}

/**
 * One changed cell, or one changed paragraph.
 *
 * [by] is who wrote it — last writer first — and is present only when the
 * version's edit log could attribute it. A change with nobody on it is
 * attributed to nobody, never to the person who saved (the web's `df673e069`).
 */
data class DriveCellChange(
    /** The cell reference. Empty for a paragraph, which has no address. */
    val cell: String = "",
    val kind: DriveChangeKind = DriveChangeKind.Changed,
    val before: String = "",
    val after: String = "",
    val by: List<DriveChangeAuthor> = emptyList(),
)

enum class DriveChangeKind(val wire: String) {
    Added("added"),
    Removed("removed"),
    Changed("changed"),
    Formula("formula"),
    ;

    companion object {
        fun of(wire: String?): DriveChangeKind =
            entries.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) } ?: Changed
    }
}

/** Who wrote a change, as the edit log names them. */
data class DriveChangeAuthor(val userId: String = "", val name: String = "") {

    /**
     * What identifies this person across changes.
     *
     * The id when there is one, the name when the log carried only that — the
     * web's `personKey`. A key of neither is nobody, and is dropped.
     */
    val key: String
        get() = userId.ifBlank { if (name.isNotBlank()) "name:$name" else "" }
}

/** One person in the change-filter row, with how many changes are theirs. */
data class DriveChangeAuthorCount(
    val key: String,
    val userId: String = "",
    val name: String = "",
    val count: Int = 0,
)

/**
 * What the live editors did in a version, beyond cell values.
 *
 * Drive builds it from the edit log: column widths, formatting, inserted
 * rows, new sheets, comments. Each item carries the **server's own wording**
 * in `text`, so this client prints it rather than composing a sentence from
 * `type` and `count` — a new kind of edit then reads correctly here without a
 * desktop release.
 */
data class DriveVersionActivity(
    val ops: Int = 0,
    val truncated: Boolean = false,
    val items: List<DriveActivityItem> = emptyList(),
    val people: List<DriveActivityPerson> = emptyList(),
)

data class DriveActivityItem(val type: String = "", val count: Int = 0, val text: String = "")

data class DriveActivityPerson(
    val userId: String = "",
    val name: String = "",
    val items: List<DriveActivityItem> = emptyList(),
)

/** A day's worth of sessions under one heading. */
data class DriveVersionDay(val label: String, val sessions: List<DriveVersionSession>)

/**
 * What the editor opens, which decides what a change is *called*: a
 * spreadsheet is compared cell by cell, a document and a deck paragraph by
 * paragraph.
 */
enum class DriveFileKind {
    Spreadsheet,
    Document,
    Presentation,
    Other,
}
