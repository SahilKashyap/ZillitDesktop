package com.zillit.desktop.feature.sides.ui.flows

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.sides.domain.SidesDistribution
import com.zillit.desktop.feature.sides.domain.SidesRecord
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.domain.StoredAttachment
import com.zillit.desktop.feature.sides.ui.SidesDistributeTarget
import com.zillit.desktop.feature.sides.ui.SidesEffect
import com.zillit.desktop.feature.sides.ui.SidesStore
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * "Send via Document Distribution" on a generated record — the web's
 * `handleSendToDocDist` (`SidesPage.jsx`), filed under the library's `Sides`
 * root with no sub-folder and dated by the day the side was generated (a
 * side has no shoot date).
 *
 * ## The copy that goes is the unwatermarked one
 *
 * The PDF is fetched with `for_distribution=true` and uploaded to storage as
 * a NEW file. The row's stored `attachment.media` is the *watermarked* copy,
 * and the library stamps its own watermark when a file is distributed — so
 * sending the stored key would stack two. The web's comment says the same;
 * this is the one hand-off in the app that must not reuse the tool's keys.
 *
 * ## Then: now, or later
 *
 * The web lands the user in the library with the composer open when they are
 * still on the list. The desktop asks instead, because a tool window here is
 * not a page the user can be "still on": Distribute Now opens the library at
 * the file's folder with the composer over it, Later leaves it filed.
 */
internal class DistributeFlow(private val store: SidesStore) {

    /** Names the file and opens the confirm. */
    fun ask(record: SidesRecord) {
        if (!record.status.viewable) return
        // The web's gate, in its order: the library's posting right hides the
        // action altogether, and the Sides download right is what a press
        // then asks an admin for — the file is leaving this tool.
        if (!store.docDist.canDistribute()) {
            store.failed(str(S.desktop_docdist_no_distribute_permission))
            return
        }
        if (store.refuses(RightsKind.Download)) return
        if (store.current.distribute?.sending == true) return
        store.update {
            copy(
                distribute = SidesDistributeTarget(
                    id = record.id,
                    fileName = SidesRules.docDistFileName(record.title),
                ),
            )
        }
    }

    /**
     * Uploads the unwatermarked PDF and registers it in the library.
     * `from-tool` always creates a NEW document, so a second send adds a
     * second file — re-sending is allowed, as it is on every platform.
     */
    fun confirm() {
        val target = store.current.distribute ?: return
        if (target.sending || target.filed) return
        val record = recordFor(target.id) ?: return
        store.update { copy(distribute = target.copy(sending = true)) }
        store.runTask {
            val stored = when (val file = uploadForLibrary(record, target.fileName)) {
                is ZillitResult.Failure -> return@runTask failed(file)
                is ZillitResult.Success -> file.data
            }
            val filed = store.docDist.distribute(
                SidesDistribution(
                    fileName = target.fileName,
                    media = stored.media,
                    bucket = stored.bucket,
                    region = stored.region,
                    // The server does not always say; the library needs one.
                    contentSubtype = stored.contentSubtype.ifBlank { PDF_SUBTYPE },
                    fileSizeBytes = stored.fileSizeBytes,
                    folderDate = folderDate(record),
                ),
            )
            when (filed) {
                is ZillitResult.Failure -> failed(filed)
                is ZillitResult.Success -> store.update {
                    copy(
                        distribute = distribute?.copy(
                            sending = false,
                            documentId = filed.data.documentId,
                            folderId = filed.data.folderId,
                        ),
                    )
                }
            }
        }
    }

    /** "Distribute Now": the library at the file's folder, composer over it. */
    fun now() {
        val target = store.current.distribute ?: return
        val documentId = target.documentId ?: return
        store.update { copy(distribute = null) }
        store.effect(SidesEffect.OpenDocDistCompose(documentId, target.folderId))
    }

    /** Cancel, Later, or a dismissed dialog; a send in flight is left alone. */
    fun close() {
        if (store.current.distribute?.sending == true) return
        store.update { copy(distribute = null) }
    }

    private fun failed(result: ZillitResult.Failure) {
        store.update { copy(distribute = null) }
        store.failed(result.error)
    }

    private fun recordFor(id: String): SidesRecord? {
        val list = store.current.list
        return (list.sides + list.history).firstOrNull { it.id == id }
    }

    /**
     * The unwatermarked PDF, in storage under a key of its own — the web's
     * `fetchSideDistributionPdf` then `publishPdfBytesFromTool`.
     */
    private suspend fun uploadForLibrary(
        record: SidesRecord,
        fileName: String,
    ): ZillitResult<StoredAttachment> =
        store.repository.distributionUrl(record.id).flatMap { url ->
            if (url.isBlank()) {
                // A blank URL would fetch nothing and file an empty document.
                ZillitResult.Failure(ZillitError.Validation(str(S.desktop_no_file_url)))
            } else {
                store.transfer.fetch(url).flatMap { bytes -> store.transfer.upload(fileName, bytes) }
            }
        }

    /** The generated day on this device's calendar; today when the row has no date. */
    private fun folderDate(record: SidesRecord): String {
        val zone = TimeZone.currentSystemDefault()
        val day = runCatching {
            Instant.parse(record.createdAt).toLocalDateTime(zone).date
        }.getOrNull() ?: Clock.System.now().toLocalDateTime(zone).date
        return day.toString()
    }

    private companion object {
        const val PDF_SUBTYPE = "pdf"
    }
}
