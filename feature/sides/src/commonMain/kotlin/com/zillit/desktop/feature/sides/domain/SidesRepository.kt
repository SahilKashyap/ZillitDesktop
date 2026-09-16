package com.zillit.desktop.feature.sides.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The sides service — the web's `api/sidesApi.js`, routes under `/api/v2`.
 * One suspend function per server operation; the interface is the whole
 * data contract, so it is deliberately not split.
 */
@Suppress("TooManyFunctions", "ComplexInterface")
interface SidesRepository {

    /**
     * A pulse per `sides:generated` on the socket — the backend's nudge
     * the moment a generation finishes, which the web answers with a list
     * refetch. Defaulted empty for tests and hosts without a socket; the
     * generating poll covers a missed push.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

    // ── Scripts ─────────────────────────────────────────────────────────

    suspend fun scripts(limit: Int = SCRIPTS_LIMIT): ZillitResult<List<Script>>

    /** The production's active script, or null when none is published. */
    suspend fun activeScript(): ZillitResult<Script?>

    /** Archived scripts still carry usable scenes and pages. */
    suspend fun scriptsHistory(limit: Int = 1): ZillitResult<List<Script>>

    /** Title alone creates an empty folder; with a file it makes the first version too. */
    suspend fun createScript(title: String, attachment: StoredAttachment?): ZillitResult<Script?>

    suspend fun deleteScript(id: String): ZillitResult<Unit>

    suspend fun versions(scriptId: String): ZillitResult<List<ScriptVersion>>

    suspend fun addVersion(
        scriptId: String,
        attachment: StoredAttachment,
        versionLabel: String,
    ): ZillitResult<Unit>

    suspend fun versionDownloadUrl(versionId: String): ZillitResult<String>

    /**
     * The parsed scene list of one version. The one endpoint on this service
     * that answers RAW JSON with no envelope.
     */
    suspend fun scenes(versionId: String): ZillitResult<List<SceneInfo>>

    // ── Pages (scene folders) ──────────────────────────────────────────

    suspend fun scenePages(scriptId: String): ZillitResult<List<ScenePage>>

    suspend fun createScenePage(scriptId: String, draft: ScenePageDraft): ZillitResult<Unit>

    /** A null attachment keeps the current file. */
    suspend fun updateScenePage(pageId: String, draft: ScenePageDraft): ZillitResult<Unit>

    suspend fun deleteScenePage(pageId: String): ZillitResult<Unit>

    suspend fun scenePageDownloadUrl(pageId: String): ZillitResult<String>

    suspend fun scenePageScenes(pageId: String): ZillitResult<List<SceneInfo>>

    // ── Call sheets and schedules ──────────────────────────────────────

    suspend fun callSheets(limit: Int = SIDES_LIMIT): ZillitResult<List<CallSheetRef>>

    suspend fun callSheet(id: String): ZillitResult<CallSheetRef?>

    /** Answers the new call sheet and how many scenes the parser found. */
    suspend fun uploadCallSheet(
        scriptId: String,
        title: String,
        attachment: StoredAttachment,
    ): ZillitResult<UploadedCallSheet>

    suspend fun deleteCallSheet(id: String): ZillitResult<Unit>

    suspend fun schedules(limit: Int = SIDES_LIMIT): ZillitResult<List<ScheduleRef>>

    suspend fun uploadSchedule(
        scriptId: String,
        title: String,
        attachment: StoredAttachment,
    ): ZillitResult<ScheduleRef?>

    suspend fun deleteSchedule(id: String): ZillitResult<Unit>

    suspend fun scheduleDownloadUrl(id: String): ZillitResult<String>

    // ── Sides ──────────────────────────────────────────────────────────

    suspend fun sides(history: Boolean = false, limit: Int = SIDES_LIMIT): ZillitResult<List<SidesRecord>>

    suspend fun sidesById(id: String): ZillitResult<SidesRecord>

    /** Starts a manual generation; the returned record is the first poll tick. */
    suspend fun generate(plan: ManualPlan): ZillitResult<SidesRecord>

    /** Starts a call-sheet-driven generation. */
    suspend fun autoGenerate(plan: AutoPlan): ZillitResult<SidesRecord>

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

/** What the page editor sends: the fields, and a file when one was chosen. */
data class ScenePageDraft(
    val sceneNumber: String,
    val color: String,
    val description: String,
    val attachment: StoredAttachment? = null,
)

data class UploadedCallSheet(val callSheet: CallSheetRef?, val sceneCount: Int)

/** Host seams: the S3-first upload, presigning, and PDF fetch/render. */
interface SidesTransfer {
    /** Uploads to project storage and returns the attachment descriptor. */
    suspend fun upload(fileName: String, bytes: ByteArray): ZillitResult<StoredAttachment>

    /** Fetches a signed URL's bytes — presigned, so no API headers ride along. */
    suspend fun fetch(url: String): ZillitResult<ByteArray>

    /**
     * A presigned URL for a stored attachment. Call sheets have no download
     * route on this service, so their PDF is reached the way the call-sheet
     * tool reaches it — signed client-side.
     */
    suspend fun presign(attachment: StoredAttachment): ZillitResult<String>

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
