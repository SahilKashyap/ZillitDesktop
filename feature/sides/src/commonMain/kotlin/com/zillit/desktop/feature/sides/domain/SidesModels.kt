package com.zillit.desktop.feature.sides.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * A generated sides run's state.
 *
 * The generator treats `error` as terminal; the web's list labels `failed` —
 * both exist on the wire and both are modelled, because a row showing a raw
 * status string is how the web renders the mismatch today.
 */
enum class SidesStatus(val wire: String, val label: String) {
    Ready("ready", "Ready"),
    Generating("generating", "Generating"),
    Error("error", "Error"),
    Failed("failed", "Failed"),
    Archived("archived", "Archived"),
    Unknown("", "Unknown"),
    ;

    /** Whether polling stops here — the web's `isTerminal`. */
    val terminal: Boolean get() = this == Ready || this == Error

    /** Whether View/Download make sense. */
    val viewable: Boolean get() = this == Ready || this == Archived

    companion object {
        fun fromWire(value: String?): SidesStatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) && it.wire.isNotEmpty() }
                ?: Unknown
    }
}

/** The stored-file descriptor rides S3-first uploads and generated PDFs alike. */
data class StoredAttachment(
    val media: String = "",
    val name: String = "",
    val bucket: String = "",
    val region: String = "",
    val fileSizeBytes: Long = 0,
)

data class Script(
    val id: String,
    val title: String,
    val description: String,
    val status: String,
    val format: String,
    val updatedAt: String,
    val currentVersionId: String,
    val currentVersionLabel: String,
    val pageCount: Int,
)

data class ScriptVersion(
    val id: String,
    val versionNumber: Int,
    val versionLabel: String,
    val pageCount: Int,
    val createdAt: String,
    val fileName: String,
)

/** One scene, as the parser extracted it from a script version. */
data class SceneInfo(
    val sceneNumber: String,
    val heading: String,
    val intExt: String,
    val timeOfDay: String,
    val pageStart: Int,
    val pageEnd: Int,
)

data class SidesRecord(
    val id: String,
    val title: String,
    val status: SidesStatus,
    val rawStatus: String,
    val error: String,
    val sceneNumbers: List<String>,
    val totalScenes: Int,
    val scriptTitle: String,
    val versionLabel: String,
    val generatedByName: String,
    val generatedById: String,
    val downloadCount: Int,
    val createdAt: String,
    val attachmentName: String,
)

/**
 * A manual generation request. `publish` is always false — every run lands in
 * review first, and publishing is its own explicit act.
 */
data class GeneratePlan(
    val scriptId: String,
    val title: String = "",
    val versionId: String = "",
    val sceneNumbers: List<String> = emptyList(),
    /** `crossout` prints skipped scenes struck through; `hide` drops them. */
    val displayMode: String = DISPLAY_CROSSOUT,
    /** Non-empty turns on `orderedScenes` and carries the typed order. */
    val sceneOrder: List<String> = emptyList(),
) {
    companion object {
        const val DISPLAY_CROSSOUT = "crossout"
        const val DISPLAY_HIDE = "hide"

        /**
         * The web's scene-list parse: split on commas, semicolons or
         * whitespace, trim, drop empties.
         */
        fun parseScenes(typed: String): List<String> =
            typed.split(Regex("""[,;\s]+""")).map { it.trim() }.filter { it.isNotEmpty() }
    }
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
    val canGenerate: Boolean get() = isAdmin || canPost
    val mayDownload: Boolean get() = isAdmin || canDownload

    companion object {
        const val TOOL_IDENTIFIER = "sides_tool"

        fun from(permissions: ProjectPermissions, userId: String, displayName: String): SidesViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return SidesViewer(userId = userId, displayName = displayName, ready = false)
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
