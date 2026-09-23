package com.zillit.desktop

import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.datastore.PreferenceKey
import com.zillit.desktop.core.datastore.PreferenceScope
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.socket.NotificationReadDto
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.data.BoxScheduleRepositoryImpl
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleHost
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleViewer
import com.zillit.desktop.feature.boxschedule.domain.DiaryDepartment
import com.zillit.desktop.feature.boxschedule.domain.DiaryDirectory
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfPublisher
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfTransfer
import com.zillit.desktop.feature.boxschedule.domain.DiaryPerson
import com.zillit.desktop.feature.boxschedule.domain.DiaryPreferences
import com.zillit.desktop.feature.boxschedule.domain.DiaryPrinter
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleViewModel
import com.zillit.desktop.feature.documentdistribution.data.FromToolFile
import com.zillit.desktop.feature.documentdistribution.data.FromToolPublisher
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The production diary, wired: its service with the diary's socket refresh,
 * the Home calendar merge, the viewer's rights read live, and everything the
 * page needs from the app around it.
 */
internal fun AppGraph.Ready.buildBoxSchedule(permissions: () -> ProjectPermissions): BoxScheduleViewModel =
    BoxScheduleViewModel(
        repository = BoxScheduleRepositoryImpl(
            apiClient,
            config,
            bus = socketEvents,
            currentProjectId = { projectContext?.context?.value?.project?.projectId },
        ),
        calendar = diaryCalendarLookup(),
        resolveViewer = { boxScheduleViewer(permissions()) },
        nowMillis = System::currentTimeMillis,
        host = BoxScheduleHost(
            transfer = diaryPdfTransfer(),
            publisher = diaryPdfPublisher(permissions),
            canPublish = { permissions().canPost(DOC_DISTRIBUTION_TOOL) },
            watermark = { projectContext?.context?.value?.profile?.fullName.orEmpty() },
            directory = diaryDirectory(),
            preferences = diaryPreferences(),
            historyBadge = badgeStore.counts.map { it[BoxScheduleViewer.TOOL_IDENTIFIER] }.distinctUntilChanged(),
            onHistoryViewed = { readDiaryNotifications() },
            printer = DiaryPrinter(::printDiaryPage),
        ),
    )

/**
 * The crew the invitee and preset pickers list — the web's `useProjectUsers`,
 * which drops anyone who left, was removed, or has not yet accepted — and
 * the production's departments, `getProjectDepartments`.
 */
private fun AppGraph.Ready.diaryDirectory(): DiaryDirectory = object : DiaryDirectory {
    override fun people(): List<DiaryPerson> =
        projectContext?.context?.value?.users.orEmpty()
            .filter { it.status !in GONE_STATUSES }
            .map { user ->
                DiaryPerson(
                    id = user.userId,
                    fullName = user.fullName,
                    department = user.department?.takeIf { it.isNotBlank() }?.let { Labels.translate(it) }.orEmpty(),
                    designation = user.designationText().orEmpty(),
                    isAdmin = user.isAdmin,
                )
            }

    override suspend fun departments(): ZillitResult<List<DiaryDepartment>> {
        val projectId = projectContext?.context?.value?.project?.projectId
            ?: return ZillitResult.Failure(ZillitError.Validation(str(S.desktop_no_project_is_open)))
        return projectRepository.departments(projectId).map { rows ->
            rows.map { DiaryDepartment(id = it.id, name = Labels.translate(it.name)) }
        }
    }
}

/** Crew statuses the web's pickers leave out (`filterWithCurrentUser`). */
private val GONE_STATUSES = setOf("left", "pending", "removed")

/** The page's remembered views — the web keeps them in `localStorage`, so they belong to this machine. */
private fun AppGraph.Ready.diaryPreferences(): DiaryPreferences = object : DiaryPreferences {
    override suspend fun read(key: String): String? = preferences.get(diaryKey(key)).takeIf { it.isNotBlank() }

    override suspend fun write(key: String, value: String) = preferences.set(diaryKey(key), value)
}

private fun diaryKey(name: String) = PreferenceKey.StringKey(name, "", PreferenceScope.Device)

/**
 * History opened over unread diary notifications — the web's
 * `emitForNotificationRead({module, segment: 'box_schedule_label'})`. The
 * ledger's rows for the tool are read at once: no echo comes back for one's
 * own read, and the badge would otherwise wait for the next recount.
 */
private suspend fun AppGraph.Ready.readDiaryNotifications() {
    val projectId = projectContext?.context?.value?.project?.projectId ?: return
    badgeStore.markRead(LedgerRead.Tool(BOX_SCHEDULE_WIRE_TOOL))
    socketEvents.emit(
        ZillitSocketEvents.Badges.NotificationRead,
        NotificationReadDto(
            projectId = projectId,
            segment = BOX_SCHEDULE_WIRE_TOOL,
            module = BOX_SCHEDULE_WIRE_TOOL,
            timestamp = System.currentTimeMillis(),
        ),
        NotificationReadDto.serializer(),
    )
}

/**
 * Print Selected — the printable page written to a temporary file and handed
 * to the system, whose browser prints it as it loads (the page calls
 * `window.print()` itself, as the web's print window does).
 */
private suspend fun printDiaryPage(html: String): ZillitResult<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val page = File.createTempFile("box-schedule-", ".html").apply {
            deleteOnExit()
            writeText(html)
        }
        openSavedFile(page.absolutePath)
    }.fold(
        onSuccess = { ZillitResult.Success(Unit) },
        onFailure = { ZillitResult.Failure(ZillitError.Storage(it.message, str(S.desktop_print_page_not_prepared))) },
    )
}

/**
 * The diary PDF's two journeys.
 *
 * Saved: the staged S3 object, fetched with the board's signed reader,
 * written to Downloads and opened — the Crew List's. Published: registered
 * in Document Distribution under `Box Schedule` by its storage keys with
 * nothing re-uploaded — the phones' `requestDistributeToDd`, whose folder
 * convention has no episode or scene, only the day.
 */
internal fun AppGraph.Ready.diaryPdfTransfer(): DiaryPdfTransfer = DiaryPdfTransfer { pdf ->
    val stored = NoticeAttachment(media = pdf.media, fileName = pdf.name, bucket = pdf.bucket, region = pdf.region)
    when (val fetched = noticeMedia.fetch(stored, preview = false)) {
        is ZillitResult.Failure -> fetched
        is ZillitResult.Success -> {
            val name = pdf.name.ifBlank { BOX_SCHEDULE_PDF }
                .let { if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf" }
            when (val saved = DownloadsAttachmentStore().save(name, fetched.data)) {
                is ZillitResult.Failure -> saved
                is ZillitResult.Success -> {
                    openSavedFile(saved.data)
                    ZillitResult.Success(Unit)
                }
            }
        }
    }
}

internal fun AppGraph.Ready.diaryPdfPublisher(permissions: () -> ProjectPermissions): DiaryPdfPublisher =
    DiaryPdfPublisher { pdf ->
        FromToolPublisher(apiClient, config, canPost = { permissions().canPost(DOC_DISTRIBUTION_TOOL) })
            .publish(
                FromToolFile(
                    folderPath = listOf(BOX_SCHEDULE_FOLDER),
                    name = pdf.name.ifBlank { BOX_SCHEDULE_PDF },
                    media = pdf.media,
                    bucket = pdf.bucket,
                    region = pdf.region,
                    contentType = pdf.contentType,
                    contentSubtype = pdf.contentSubtype,
                    thumbnail = pdf.thumbnail,
                    caption = pdf.caption,
                    fileSizeBytes = pdf.fileSizeBytes,
                    folderDate = java.time.LocalDate.now().toString(),
                ),
            )
            .map { }
    }

/** Android's `DocDistTool.BOX_SCHEDULE` — where the library files the diary. */
private const val BOX_SCHEDULE_FOLDER = "Box Schedule"

private const val BOX_SCHEDULE_PDF = "Box Schedule.pdf"

/** The notification service's name for the diary — `BADGE_CONSTANTS.box_schedule_label`. */
private const val BOX_SCHEDULE_WIRE_TOOL = "box_schedule_label"
