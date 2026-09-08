package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfPublisher
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfTransfer
import com.zillit.desktop.feature.documentdistribution.data.FromToolFile
import com.zillit.desktop.feature.documentdistribution.data.FromToolPublisher
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.home.domain.NoticeAttachment

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
