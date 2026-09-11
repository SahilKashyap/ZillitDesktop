package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.recce.data.RecceRepositoryImpl
import com.zillit.desktop.feature.recce.domain.RecceReport
import com.zillit.desktop.feature.recce.domain.RecceTransfer
import com.zillit.desktop.feature.recce.domain.RecceViewer
import com.zillit.desktop.feature.recce.ui.RecceViewModel
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import java.util.TimeZone
import java.util.UUID

/**
 * Recce's host seams: the report the server renders is a stored file, fetched
 * through the same signed-storage path the notice boards use, saved to
 * Downloads, and handed to the OS — the desktop's equivalent of the web's
 * "open the PDF in a new tab".
 */
internal fun AppGraph.Ready.recceTransfer(): RecceTransfer = object : RecceTransfer {
    override suspend fun openReport(report: RecceReport): ZillitResult<Unit> {
        val bytes: ByteArray = when {
            // A directly served URL — fetched bare; it is not an API route.
            report.url != null -> runCatching {
                val response = httpClient.get(report.url!!)
                check(response.status.isSuccess()) { "report fetch answered ${response.status}" }
                response.readRawBytes()
            }.getOrElse { return ZillitResult.Failure(ZillitError.Unknown(it.message ?: "report fetch failed")) }
            report.media.isNotBlank() -> {
                val stored = NoticeAttachment(
                    media = report.media,
                    fileName = report.name,
                    bucket = report.bucket,
                    region = report.region,
                )
                when (val fetched = noticeMedia.fetch(stored, preview = false)) {
                    is ZillitResult.Failure -> return fetched
                    is ZillitResult.Success -> fetched.data
                }
            }
            else -> return ZillitResult.Failure(ZillitError.Unknown("the server answered no report file"))
        }
        val name = report.name.ifBlank { "recce.pdf" }.let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        return when (val saved = DownloadsAttachmentStore().save(name, bytes)) {
            is ZillitResult.Failure -> saved
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
        }
    }
}

internal fun AppGraph.Ready.buildRecce(permissions: () -> ProjectPermissions) = RecceViewModel(
    repository = RecceRepositoryImpl(apiClient, config),
    transfer = recceTransfer(),
    units = { unitRepository.joinUnits() },
    resolveViewer = {
        RecceViewer.from(permissions(), projectContext?.context?.value?.profile?.userId.orEmpty())
    },
    newUniqueId = { UUID.randomUUID().toString() },
    // The IANA zone the server's PDF prints wall-clocks in — the web sends
    // `Intl.DateTimeFormat().resolvedOptions().timeZone`.
    timezone = { TimeZone.getDefault().id },
    // A scout day added by somebody else should not wait for a reopen.
    events = socketEvents,
)
