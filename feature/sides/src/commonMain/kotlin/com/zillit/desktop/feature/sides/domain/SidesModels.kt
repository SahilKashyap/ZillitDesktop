package com.zillit.desktop.feature.sides.domain

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A generated sides run's state.
 *
 * The generator treats `error` as terminal; the web's list labels `failed` —
 * both exist on the wire and both are modelled, because a row showing a raw
 * status string is how the web renders the mismatch today.
 */
enum class SidesStatus(val wire: String, private val labelKey: String) {
    Ready("ready", S.dd_csv_status_ready),
    Generating("generating", S.desktop_generating),
    Error("error", S.docusign_error_title),
    Failed("failed", S.dd_legend_failed),
    Archived("archived", S.desktop_archived),
    Unknown("", S.desktop_unknown),
    ;

    val label: String get() = str(labelKey)

    /** Whether polling stops here — the web's `isTerminal`. */
    val terminal: Boolean get() = this == Ready || this == Error

    /** Whether View/Download make sense — the web's `isViewable`. */
    val viewable: Boolean get() = this == Ready || this == Archived

    companion object {
        fun fromWire(value: String?): SidesStatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) && it.wire.isNotEmpty() }
                ?: Unknown
    }
}

/**
 * The stored-file descriptor that rides S3-first uploads and generated PDFs
 * alike. [contentSubtype] is the extension the backend converts on — `fdx`
 * scripts are parsed server-side, so it has to say so.
 */
data class StoredAttachment(
    val media: String = "",
    val name: String = "",
    val bucket: String = "",
    val region: String = "",
    val fileSizeBytes: Long = 0,
    val contentSubtype: String = "pdf",
) {
    val isBlank: Boolean get() = media.isBlank()

    /** The original file name, or the S3 key's basename when it is absent. */
    val displayName: String get() = name.ifBlank { media.substringAfterLast('/') }
}

data class ScriptVersion(
    val id: String,
    val versionNumber: Int,
    val versionLabel: String,
    val pageCount: Int,
    val createdAt: String,
    val fileName: String,
) {
    /** The web's `labelOf`: the label, else `v<number>`. */
    val label: String get() = versionLabel.ifBlank { "v$versionNumber" }
}

/**
 * A script is a folder: it may exist by title alone (pages added later) or
 * carry a current file version.
 */
data class Script(
    val id: String,
    val title: String,
    val description: String = "",
    val status: String = "",
    val format: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val currentVersion: ScriptVersion? = null,
) {
    val hasFile: Boolean get() = currentVersion != null
}

/** One scene, as the parser extracted it from a script version or a page. */
data class SceneInfo(
    val sceneNumber: String,
    val heading: String = "",
    val intExt: String = "",
    val timeOfDay: String = "",
    val pageStart: Int = 0,
    val pageEnd: Int = 0,
)

/**
 * A "page" — a scene folder under a script: a colour, a scene number used as
 * its title, an optional note and an uploaded PDF or `.fdx`.
 */
data class ScenePage(
    val id: String,
    val sceneNumber: String,
    val color: String,
    val description: String = "",
    val pageCount: Int = 0,
    val attachment: StoredAttachment = StoredAttachment(),
) {
    /** The uploaded document's name, else the S3 key's basename. */
    val fileName: String get() = attachment.displayName
}

/** A page folder pulled into a sides record, as the list echoes it. */
data class SidesPageRef(
    val sceneNumber: String,
    val color: String = "",
    val sceneNumbers: List<String> = emptyList(),
)

data class SidesRecord(
    val id: String,
    val title: String,
    val status: SidesStatus,
    val rawStatus: String = status.wire,
    val error: String = "",
    val sceneNumbers: List<String> = emptyList(),
    val totalScenes: Int = 0,
    val scriptId: String = "",
    val scriptTitle: String = "",
    val versionLabel: String = "",
    val versionNumber: Int = 0,
    val callSheetTitle: String = "",
    val generatedByName: String = "",
    val generatedById: String = "",
    val downloadCount: Int = 0,
    val createdAt: String = "",
    val attachmentName: String = "",
    val attachmentSize: Long = 0,
    val sceneFolders: List<SidesPageRef> = emptyList(),
) {
    /** The web's version text: the label, else `v<number>`, else nothing. */
    val versionText: String
        get() = versionLabel.ifBlank { if (versionNumber > 0) "v$versionNumber" else "" }

    /** `Script_v2` — the card's folder line. */
    val scriptLabel: String
        get() = when {
            scriptTitle.isBlank() -> ""
            versionText.isBlank() -> scriptTitle
            else -> "${scriptTitle}_$versionText"
        }

    /** The card's file line: the attachment, else the call sheet, else the title. */
    val fileLabel: String get() = attachmentName.ifBlank { callSheetTitle.ifBlank { title } }

    val sceneCount: Int get() = totalScenes.takeIf { it > 0 } ?: sceneNumbers.size

    /** Page folders the list knows a number for. */
    val pageRefs: List<SidesPageRef> get() = sceneFolders.filter { it.sceneNumber.isNotBlank() }
}

/**
 * A call sheet the sides service knows — published from the call-sheet tool
 * or uploaded here. Only uploaded ones may be deleted.
 */
data class CallSheetRef(
    val id: String,
    val title: String,
    val source: String = "",
    val scenes: List<String> = emptyList(),
    val attachment: StoredAttachment = StoredAttachment(),
) {
    val uploaded: Boolean get() = source == SOURCE_UPLOADED

    companion object {
        const val SOURCE_UPLOADED = "uploaded"
    }
}

/** A shooting schedule, optionally attached to an autogenerate run. */
data class ScheduleRef(
    val id: String,
    val title: String,
    val source: String = "",
    val totalDays: Int = 0,
    val totalScenes: Int = 0,
) {
    val uploaded: Boolean get() = source == CallSheetRef.SOURCE_UPLOADED
}

/** Who is looking at the sides tool — the `sides_tool` grid row. */
data class SidesViewer(
    val userId: String = "",
    val displayName: String = "",
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val canDownload: Boolean = false,
    val isAdmin: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView && !isAdmin

    /** Posting actions: generate, upload, replace, delete. */
    val mayPost: Boolean get() = isAdmin || canPost

    /** The save-to-disk download; in-app View is open to every viewer. */
    val mayDownload: Boolean get() = isAdmin || canDownload

    companion object {
        const val TOOL_IDENTIFIER = "sides_tool"

        /** What an admin reads in a rights request — the web's `sides_label`. */
        const val MODULE_LABEL = "Sides"

        fun from(permissions: ProjectPermissions, userId: String, displayName: String): SidesViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            // An empty grid is "not loaded yet", not "no access": the web's
            // SOFT_DEFAULT permits everything until the rights payload lands.
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return SidesViewer(
                    userId = userId,
                    displayName = displayName,
                    canPost = true,
                    canDownload = true,
                    ready = false,
                )
            }
            return SidesViewer(
                userId = userId,
                displayName = displayName,
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                canDownload = permissions.canDownload(TOOL_IDENTIFIER),
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}
