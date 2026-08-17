package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.esignature.domain.EsignFileTransfer
import com.zillit.desktop.feature.esignature.domain.EsignPage
import com.zillit.desktop.feature.esignature.domain.EsignPdf
import com.zillit.desktop.feature.esignature.domain.SignerOptionLike
import com.zillit.desktop.feature.esignature.domain.StoredFile
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.formsignature.domain.StrokePoint
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import java.util.UUID

/**
 * E-Signature's host seams: storage on the app's S3 machinery, PDF work on
 * the same PDFBox implementation the documents tool uses, and the crew list
 * as the signer pool — all adapted here so the feature stays free of both
 * the email module's types and PDFBox.
 */
internal fun AppGraph.Ready.esignTransfer(): EsignFileTransfer {
    val credentials: suspend () -> AwsCredentials? = {
        val remote = remoteConfigRepository.current()
        val access = remote?.awsAccessKey?.takeIf { it.isNotBlank() }
        val secret = remote?.awsSecretKey?.takeIf { it.isNotBlank() }
        if (access != null && secret != null) AwsCredentials(access, secret) else null
    }
    val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = credentials,
        storage = storageTarget,
        newKey = { fileName ->
            "esignature/${UUID.randomUUID()}/${fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        },
    )
    return object : EsignFileTransfer {
        override suspend fun store(
            fileName: String,
            contentType: String,
            bytes: ByteArray,
        ): ZillitResult<StoredFile> =
            when (val stored = uploader.upload(fileName, contentType, bytes)) {
                is ZillitResult.Failure -> stored
                is ZillitResult.Success -> ZillitResult.Success(
                    StoredFile(
                        media = stored.data.media,
                        thumbnail = stored.data.media,
                        bucket = stored.data.bucket,
                        region = stored.data.region,
                        name = fileName,
                    ),
                )
            }

        override suspend fun fetch(file: StoredFile): ZillitResult<ByteArray> =
            noticeMedia.fetch(
                NoticeAttachment(
                    media = file.media,
                    fileName = file.name,
                    bucket = file.bucket.takeIf { it.isNotBlank() },
                    region = file.region.takeIf { it.isNotBlank() },
                ),
                preview = false,
            )
    }
}

/** The documents tool's PDF work, behind E-Signature's narrower interface. */
internal fun esignPdf(): EsignPdf = object : EsignPdf {
    private val work = PdfBoxWork()

    override fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<EsignPage>> =
        when (val pages = work.renderPages(pdf, targetWidthPx)) {
            is ZillitResult.Failure -> pages
            is ZillitResult.Success -> ZillitResult.Success(
                pages.data.map { page ->
                    EsignPage(
                        page = page.page,
                        imageBytes = page.imageBytes,
                        widthPx = page.widthPx,
                        heightPx = page.heightPx,
                        widthPt = page.widthPt,
                        heightPt = page.heightPt,
                    )
                },
            )
        }

    override fun rasterizeStrokes(
        strokes: List<List<Pair<Float, Float>>>,
        width: Int,
        height: Int,
    ): ZillitResult<ByteArray> = work.rasterizeStrokes(
        strokes = strokes.map { stroke -> stroke.map { (x, y) -> StrokePoint(x, y) } },
        canvasWidth = width,
        canvasHeight = height,
    )
}

/**
 * The signer pool: the whole crew, plus the current user's own profile row.
 *
 * No email filter — an internal recipient's identity is their `user_id`,
 * and the crew list withholds addresses for members who keep theirs
 * private (the current user's own row often has none). The profile is
 * merged in because signing-to-yourself is both legitimate and the first
 * thing anyone tests.
 */
internal fun AppGraph.Ready.esignSignerOptions(): List<SignerOptionLike> {
    val context = projectContext?.context?.value
    val crew = context?.users.orEmpty().map { user ->
        SignerOptionLike(
            userId = user.userId,
            fullName = user.fullName,
            email = user.email.orEmpty(),
        )
    }
    val me = context?.profile?.let { profile ->
        SignerOptionLike(
            userId = profile.userId,
            fullName = profile.fullName.ifBlank { "Me" },
            email = profile.email.orEmpty(),
        )
    }
    // The profile's address overlays the crew row: the crew list withholds
    // emails for members who keep theirs private — the current user's own
    // row included — while the backend refuses a recipient without one
    // (`"recipients[0].email" is not allowed to be empty`, verified live).
    // Members without a known address stay in the pool; the compose dialog
    // collects an address for them before anything is sent.
    return (crew + listOfNotNull(me))
        .groupBy { it.userId }
        .map { (_, rows) ->
            rows.reduce { a, b ->
                SignerOptionLike(
                    userId = a.userId,
                    fullName = a.fullName.ifBlank { b.fullName },
                    email = a.email.ifBlank { b.email },
                )
            }
        }
        .map { option ->
            // Tagged by id, after the merge: the crew can carry an older
            // registration of the same person under the same name — two
            // identical rows — and only this one routes an envelope back to
            // this session. Seen live after a device re-link.
            if (option.userId == me?.userId) {
                option.copy(fullName = "${option.fullName} (you)")
            } else {
                option
            }
        }
        .sortedBy { it.fullName.lowercase() }
}

/** Today as the sign builder's `DD/MM/YYYY` — the web's default label. */
internal fun esignToday(): String = java.time.LocalDate.now()
    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
