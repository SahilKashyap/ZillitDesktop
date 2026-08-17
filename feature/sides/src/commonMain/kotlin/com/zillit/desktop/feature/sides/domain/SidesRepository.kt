package com.zillit.desktop.feature.sides.domain

import com.zillit.desktop.core.common.ZillitResult

/** The sides service (`sidesapi`), routes under `/api/v2`. */
interface SidesRepository {

    suspend fun scripts(limit: Int = SCRIPTS_LIMIT): ZillitResult<List<Script>>

    /** Title alone creates an empty script; with a file it carries the upload. */
    suspend fun createScript(
        title: String,
        attachment: StoredAttachment?,
    ): ZillitResult<Script?>

    suspend fun deleteScript(id: String): ZillitResult<Unit>

    suspend fun versions(scriptId: String): ZillitResult<List<ScriptVersion>>

    suspend fun addVersion(
        scriptId: String,
        attachment: StoredAttachment,
        versionLabel: String,
    ): ZillitResult<Unit>

    /**
     * The parsed scene list of one version. The one endpoint on this service
     * that answers RAW JSON with no envelope.
     */
    suspend fun scenes(versionId: String): ZillitResult<List<SceneInfo>>

    suspend fun sides(history: Boolean = false, limit: Int = SIDES_LIMIT): ZillitResult<List<SidesRecord>>

    suspend fun sidesById(id: String): ZillitResult<SidesRecord>

    /** Starts a generation; the returned record is the first poll tick. */
    suspend fun generate(plan: GeneratePlan): ZillitResult<SidesRecord>

    /**
     * The signed URL for the finished PDF. [countDownload] mirrors the web:
     * a Download increments the counter, a View does not.
     */
    suspend fun downloadUrl(id: String, countDownload: Boolean): ZillitResult<String>

    suspend fun publish(id: String): ZillitResult<Unit>

    suspend fun deleteSides(id: String): ZillitResult<Unit>

    companion object {
        const val SCRIPTS_LIMIT = 100
        const val SIDES_LIMIT = 50
    }
}

/** Host seams: the S3-first upload, PDF fetch/render, and a file picker. */
interface SidesTransfer {
    /** Uploads to project storage and returns the attachment descriptor. */
    suspend fun upload(fileName: String, bytes: ByteArray): ZillitResult<StoredAttachment>

    /** Fetches a signed URL's bytes — presigned, so no API headers ride along. */
    suspend fun fetch(url: String): ZillitResult<ByteArray>

    /** Renders PDF bytes to page images for the in-app viewer. */
    fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<SidesPdfPage>>
}

data class SidesPdfPage(
    val page: Int,
    val imageBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    override fun equals(other: Any?): Boolean = other is SidesPdfPage && other.page == page
    override fun hashCode(): Int = page
}
