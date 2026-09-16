package com.zillit.desktop.feature.sides

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.sides.domain.AutoPlan
import com.zillit.desktop.feature.sides.domain.CallSheetRef
import com.zillit.desktop.feature.sides.domain.ManualPlan
import com.zillit.desktop.feature.sides.domain.SceneInfo
import com.zillit.desktop.feature.sides.domain.ScenePage
import com.zillit.desktop.feature.sides.domain.ScenePageDraft
import com.zillit.desktop.feature.sides.domain.ScheduleRef
import com.zillit.desktop.feature.sides.domain.Script
import com.zillit.desktop.feature.sides.domain.ScriptVersion
import com.zillit.desktop.feature.sides.domain.SidesPdfPage
import com.zillit.desktop.feature.sides.domain.SidesRecord
import com.zillit.desktop.feature.sides.domain.SidesRepository
import com.zillit.desktop.feature.sides.domain.SidesStatus
import com.zillit.desktop.feature.sides.domain.SidesTransfer
import com.zillit.desktop.feature.sides.domain.StoredAttachment
import com.zillit.desktop.feature.sides.domain.UploadedCallSheet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * An in-memory sides service: scripts with versions and pages, a scene
 * list per version, and a generate that answers `generating` then `ready`
 * on the next poll. Every call is counted so a test can say what a flow
 * fetched.
 */
@Suppress("TooManyFunctions")
internal open class FakeSidesRepository(
    override val refreshes: Flow<Unit> = emptyFlow(),
) : SidesRepository {
    val calls = mutableListOf<String>()
    var scripts = listOf(
        Script(id = "sc1", title = "Ep 1", currentVersion = ScriptVersion("v1", 1, "v1", 12, "", "ep1.pdf")),
        Script(id = "sc2", title = "Ep 2"),
    )
    var active: Script? = scripts.first()
    var scenes = mapOf("v1" to listOf(SceneInfo("1", "INT. KITCHEN"), SceneInfo("2", "EXT. LOT"), SceneInfo("3")))
    var pages = mapOf("sc1" to listOf(ScenePage("p1", "12A", "#e53935", pageCount = 2)))
    var pageScenes = mapOf<String, List<SceneInfo>>()
    var sidesRows = listOf<SidesRecord>()
    var history = listOf<SidesRecord>()
    var callSheets = listOf(CallSheetRef("cs1", "Day 1", scenes = listOf("1", "2")))
    var schedules = listOf<ScheduleRef>()
    var generated: SidesRecord? = null
    var lastManualPlan: ManualPlan? = null
    var lastAutoPlan: AutoPlan? = null
    var published = mutableListOf<String>()
    var deleted = mutableListOf<String>()
    var failGenerate = false

    private fun <T> ok(value: T) = ZillitResult.Success(value)

    override suspend fun scripts(limit: Int) = ok(scripts).also { calls += "scripts" }
    override suspend fun activeScript() = ok(active).also { calls += "active" }
    override suspend fun scriptsHistory(limit: Int) = ok(emptyList<Script>()).also { calls += "scriptsHistory" }
    override suspend fun createScript(title: String, attachment: StoredAttachment?) =
        ok(null as Script?).also { calls += "createScript:$title" }
    override suspend fun deleteScript(id: String) = ok(Unit).also { calls += "deleteScript:$id"; deleted += id }
    override suspend fun versions(scriptId: String) =
        ok(listOfNotNull(scripts.firstOrNull { it.id == scriptId }?.currentVersion))
            .also { calls += "versions:$scriptId" }
    override suspend fun addVersion(scriptId: String, attachment: StoredAttachment, versionLabel: String) =
        ok(Unit).also { calls += "addVersion:$scriptId:$versionLabel" }
    override suspend fun versionDownloadUrl(versionId: String) = ok("https://s3/v/$versionId")
    override suspend fun scenes(versionId: String) =
        ok(scenes[versionId].orEmpty()).also { calls += "scenes:$versionId" }
    override suspend fun scenePages(scriptId: String) =
        ok(pages[scriptId].orEmpty()).also { calls += "pages:$scriptId" }
    override suspend fun createScenePage(scriptId: String, draft: ScenePageDraft) =
        ok(Unit).also { calls += "createPage:$scriptId:${draft.sceneNumber}" }
    override suspend fun updateScenePage(pageId: String, draft: ScenePageDraft) =
        ok(Unit).also { calls += "updatePage:$pageId" }
    override suspend fun deleteScenePage(pageId: String) =
        ok(Unit).also { calls += "deletePage:$pageId"; deleted += pageId }
    override suspend fun scenePageDownloadUrl(pageId: String) = ok("https://s3/p/$pageId")
    override suspend fun scenePageScenes(pageId: String) =
        ok(pageScenes[pageId].orEmpty()).also { calls += "pageScenes:$pageId" }
    override suspend fun callSheets(limit: Int) = ok(callSheets).also { calls += "callSheets" }
    override suspend fun callSheet(id: String) =
        ok(callSheets.firstOrNull { it.id == id }).also { calls += "callSheet:$id" }
    override suspend fun uploadCallSheet(scriptId: String, title: String, attachment: StoredAttachment) =
        ok(UploadedCallSheet(CallSheetRef("cs9", title, "uploaded", listOf("7")), 1))
            .also { calls += "uploadCallSheet:$title" }
    override suspend fun deleteCallSheet(id: String) = ok(Unit).also { calls += "deleteCallSheet:$id"; deleted += id }
    override suspend fun schedules(limit: Int) = ok(schedules).also { calls += "schedules" }
    override suspend fun uploadSchedule(scriptId: String, title: String, attachment: StoredAttachment) =
        ok(ScheduleRef("sh9", title, "uploaded") as ScheduleRef?).also { calls += "uploadSchedule:$title" }
    override suspend fun deleteSchedule(id: String) = ok(Unit).also { calls += "deleteSchedule:$id"; deleted += id }
    override suspend fun scheduleDownloadUrl(id: String) = ok("https://s3/s/$id")

    override suspend fun sides(history: Boolean, limit: Int) =
        ok(if (history) this.history else sidesRows).also { calls += if (history) "history" else "sides" }

    override suspend fun sidesById(id: String): ZillitResult<SidesRecord> {
        calls += "sidesById:$id"
        return ok(generated?.copy(status = SidesStatus.Ready, rawStatus = "ready") ?: SidesRecord(
            id,
            "",
            SidesStatus.Unknown,
        ))
    }

    override suspend fun generate(plan: ManualPlan): ZillitResult<SidesRecord> {
        calls += "generate"
        lastManualPlan = plan
        if (failGenerate) return ZillitResult.Failure(ZillitError.Unknown("refused"))
        return ok(SidesRecord("g1", plan.title.ifBlank { "Auto" }, SidesStatus.Generating).also { generated = it })
    }

    override suspend fun autoGenerate(plan: AutoPlan): ZillitResult<SidesRecord> {
        calls += "autoGenerate"
        lastAutoPlan = plan
        return ok(SidesRecord("g2", plan.title, SidesStatus.Generating).also { generated = it })
    }

    override suspend fun downloadUrl(id: String, countDownload: Boolean) =
        ok("https://s3/sides/$id").also { calls += "download:$id:$countDownload" }
    override suspend fun publish(id: String) = ok(Unit).also { calls += "publish:$id"; published += id }
    override suspend fun deleteSides(id: String) = ok(Unit).also { calls += "deleteSides:$id"; deleted += id }
}

internal class FakeSidesTransfer : SidesTransfer {
    val uploads = mutableListOf<String>()
    var fetched: ByteArray = "%PDF-1.4".encodeToByteArray()

    override suspend fun upload(fileName: String, bytes: ByteArray): ZillitResult<StoredAttachment> {
        uploads += fileName
        return ZillitResult.Success(StoredAttachment(media = "k/$fileName", name = fileName))
    }

    override suspend fun fetch(url: String) = ZillitResult.Success(fetched)
    override suspend fun presign(attachment: StoredAttachment) = ZillitResult.Success("https://s3/${attachment.media}")
    override fun renderPages(pdf: ByteArray, targetWidthPx: Int) =
        ZillitResult.Success(listOf(SidesPdfPage(0, ByteArray(0), 1, 1)))
}
