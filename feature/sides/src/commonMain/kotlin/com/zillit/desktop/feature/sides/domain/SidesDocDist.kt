package com.zillit.desktop.feature.sides.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Where a generated sides PDF goes beyond this tool: the Document
 * Distribution library.
 *
 * Its own seam rather than a method on [SidesRepository] because the library
 * is another service with its own posting rights — the host wires it, as it
 * does for Call Sheet, Crew List and Recce.
 */
interface SidesDocDist {

    /**
     * Posting rights on Document Distribution, or project admin — the web's
     * `canPost || is_admin` (`useDistributeToDocDist`). There is no separate
     * flag for a send from a tool, and the web HIDES the action without it,
     * which is why this is read into the state the list draws from.
     */
    fun canDistribute(): Boolean

    /** Registers the uploaded PDF in the library — `documents/from-tool`. */
    suspend fun distribute(file: SidesDistribution): ZillitResult<FiledSides>

    /** No library wired — the action stays hidden rather than pretending. */
    companion object {
        val None: SidesDocDist = object : SidesDocDist {
            override fun canDistribute(): Boolean = false
            override suspend fun distribute(file: SidesDistribution): ZillitResult<FiledSides> =
                ZillitResult.Failure(ZillitError.Validation(str(S.desktop_docdist_no_distribute_permission)))
        }
    }
}

/**
 * The sides PDF as Document Distribution takes it: the storage keys of the
 * copy uploaded for the library, and where it files.
 *
 * [folderPath] is always [DOC_DIST_SIDES_FOLDER] — the server resolves
 * folders BY NAME, so a different spelling on any platform grows a second
 * root folder instead of reusing the shared one.
 */
data class SidesDistribution(
    val fileName: String,
    val media: String,
    val bucket: String,
    val region: String,
    val contentSubtype: String = "pdf",
    val fileSizeBytes: Long = 0,
    /** `yyyy-MM-dd` — the day the sides were generated; a side has no shoot date. */
    val folderDate: String,
    val folderPath: List<String> = listOf(DOC_DIST_SIDES_FOLDER),
) {
    /** Never prints the file name — a production's file names are content. */
    override fun toString(): String = "SidesDistribution(path=$folderPath, bytes=$fileSizeBytes)"
}

/**
 * What the library answers: the new document and the folder it went into —
 * both needed to open the library *at* that folder with the composer over
 * the file (the web's `doc.folder_id` / `doc`).
 */
data class FiledSides(val documentId: String, val folderId: String)

/**
 * The library's root folder for sides — the web's `DOC_DIST_TOOL.SIDES`, with
 * no sub-folder under it. The exact string every client must send; do not
 * improve the casing or the spacing.
 */
const val DOC_DIST_SIDES_FOLDER: String = "Sides"
