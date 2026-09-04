package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.crewlist.data.CrewListRepositoryImpl
import com.zillit.desktop.feature.crewlist.domain.CrewListTransfer
import com.zillit.desktop.feature.crewlist.domain.CrewListViewer
import com.zillit.desktop.feature.crewlist.ui.CrewListViewModel
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.home.domain.NoticeAttachment

/**
 * The Crew List (`crewlist` on the units host). The generated PDF is a
 * stored S3 object — fetched with the board's signed reader, saved to
 * Downloads and opened, exactly the recce report's journey.
 */
internal fun AppGraph.Ready.buildCrewList(
    permissions: () -> ProjectPermissions,
): CrewListViewModel = CrewListViewModel(
    repository = CrewListRepositoryImpl(
        apiClient,
        config,
        bus = socketEvents,
        currentProjectId = { projectContext?.context?.value?.project?.projectId },
    ),
    transfer = crewListTransfer(),
    resolveViewer = { CrewListViewer.from(permissions()) },
    translate = { key -> key.localised() },
)

/**
 * The generated PDF's journey: a stored S3 object, fetched with the board's
 * signed reader, saved to Downloads and opened — exactly the recce report's.
 *
 * Shared with the Crew List widget, which builds a roster per production but
 * saves its PDF the same way.
 */
internal fun AppGraph.Ready.crewListTransfer(): CrewListTransfer = CrewListTransfer { pdf ->
    val stored = NoticeAttachment(
        media = pdf.media,
        fileName = pdf.name,
        bucket = pdf.bucket,
        region = pdf.region,
    )
    when (val fetched = noticeMedia.fetch(stored, preview = false)) {
        is ZillitResult.Failure -> fetched
        is ZillitResult.Success -> {
            val name = pdf.name.ifBlank { "Crew List.pdf" }
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
