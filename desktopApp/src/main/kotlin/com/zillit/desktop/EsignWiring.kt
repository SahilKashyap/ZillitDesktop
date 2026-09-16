package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.esignature.domain.EsignBadgeLeaf
import com.zillit.desktop.feature.esignature.domain.EsignBadges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.esignature.ui.EsignPickKind
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import java.awt.FileDialog
import java.awt.Font
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
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

        /** A finished file lands in Downloads and opens, as every other export does. */
        override suspend fun land(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
            when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
                is ZillitResult.Failure -> saved
                is ZillitResult.Success -> {
                    openSavedFile(saved.data)
                    ZillitResult.Success(Unit)
                }
            }
    }
}

/**
 * A signed GET read as bytes — the audit-trail PDF answers `application/pdf`,
 * which the envelope client cannot read. A JSON body is the service
 * declining; its message is surfaced.
 */
internal fun AppGraph.Ready.esignRawGet(): suspend (String) -> ZillitResult<ByteArray> = { url ->
    runCatching {
        val headers = headerProvider.headersFor(RequestModule.ProjectUser, null, null, null)
        val response = httpClient.get(url) {
            headers.forEach { (name, value) -> this.headers.append(name, value) }
            accept(ContentType.Application.Pdf)
        }
        val bytes = response.readRawBytes()
        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        if (isJson || !response.status.isSuccess()) {
            error(esignDeclineMessage(bytes) ?: "The audit trail could not be fetched (${response.status.value}).")
        }
        bytes
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = {
            ZillitResult.Failure(ZillitError.Validation(it.message ?: "The audit trail could not be fetched."))
        },
    )
}

private fun esignDeclineMessage(bytes: ByteArray): String? = runCatching {
    HttpClientFactory.json.parseToJsonElement(bytes.decodeToString()).jsonObject["message"]?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.isNotBlank() }

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

    /**
     * A typed name in a script face on a transparent canvas — the web's
     * "Type" signature style. The faces are the ones macOS ships; each web
     * key maps to the closest installed one, falling back to a serif italic
     * so a missing font never blocks a signature.
     */
    override fun rasterizeText(text: String, fontKey: String, width: Int, height: Int): ZillitResult<ByteArray> =
        runCatching {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            val installed = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
            val family = SCRIPT_FACES[fontKey].orEmpty().firstOrNull { it in installed } ?: Font.SERIF
            val style = if (family == Font.SERIF) Font.ITALIC else Font.PLAIN
            var size = height * TYPED_SIZE_RATIO
            var font = Font(family, style, size.toInt())
            var metrics = g.getFontMetrics(font)
            val maxWidth = width * TYPED_WIDTH_FRACTION
            while (metrics.stringWidth(text) > maxWidth && size > MIN_TYPED_SIZE) {
                size *= TYPED_SHRINK
                font = Font(family, style, size.toInt())
                metrics = g.getFontMetrics(font)
            }
            g.font = font
            g.color = TYPED_INK
            val x = (width - metrics.stringWidth(text)) / 2f
            val y = height / 2f + (metrics.ascent - metrics.descent) / 2f
            g.drawString(text, x, y)
            g.dispose()
            val out = ByteArrayOutputStream()
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }.fold(
            onSuccess = { ZillitResult.Success(it) },
            onFailure = { ZillitResult.Failure(ZillitError.Validation("The typed signature could not be rendered.")) },
        )
}

/** The web's `SIGNATURE_FONTS`, each mapped to the nearest faces macOS installs. */
private val SCRIPT_FACES: Map<String, List<String>> = mapOf(
    "formal" to listOf("Snell Roundhand", "Apple Chancery", "Zapfino"),
    "flowing" to listOf("Savoye LET", "Snell Roundhand", "Apple Chancery"),
    "casual" to listOf("Bradley Hand", "Noteworthy", "Marker Felt"),
    "slim" to listOf("Apple Chancery", "Snell Roundhand"),
    "bold" to listOf("Brush Script MT", "Zapfino", "Snell Roundhand"),
    "natural" to listOf("Noteworthy", "Bradley Hand", "Chalkboard"),
)
/** The same navy the drawn pad uses, so a typed mark matches a drawn one. */
private val TYPED_INK = Color(0x16, 0x2A, 0x60)
private const val TYPED_SIZE_RATIO = 0.42f
private const val TYPED_WIDTH_FRACTION = 0.86f
private const val TYPED_SHRINK = 0.92f
private const val MIN_TYPED_SIZE = 18f

/** The OS chooser for what E-Signature asks for: a PDF, a CSV, or an image. */
internal suspend fun pickEsignFile(kind: EsignPickKind): Pair<String, ByteArray>? = when (kind) {
    EsignPickKind.Pdf -> pickPdf()
    EsignPickKind.Csv -> pickOne("Choose a CSV", listOf("csv", "txt", "tsv"))
    EsignPickKind.Image -> pickOne("Choose an image", listOf("png", "jpg", "jpeg"))
}

private suspend fun pickOne(title: String, extensions: List<String>): Pair<String, ByteArray>? =
    withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
        dialog.isVisible = true
        val file = dialog.files.orEmpty().firstOrNull { it.isFile } ?: return@withContext null
        runCatching { file.name to file.readBytes() }.getOrNull()
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

/**
 * The tool's ledger rows as leaves, and its read.
 *
 * Every unread `e_signature_label` row becomes one leaf keyed by unit,
 * bucket (`level_1`) and envelope (`level_3`) — the web's
 * `getESignatureBadgesFromDB` split. The read is the web's
 * `markBucketAsRead` / per-envelope open read: `notification:level:read`
 * scoped to the unit and the envelope (`DocuSignPanel.jsx:250-275`).
 */
internal fun AppGraph.Ready.esignBadges(): EsignBadges = object : EsignBadges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val leaves: Flow<List<EsignBadgeLeaf>> = badgeStore.counts.map { leavesNow() }.distinctUntilChanged()

    private fun leavesNow(): List<EsignBadgeLeaf> =
        badgeStore.unreadRows(BadgeSections.TOOLS)
            .filter { it.tool == EsignBadges.TOOL }
            .groupingBy { Triple(it.unit, it.level1, it.level3) }
            .eachCount()
            .map { (key, count) -> EsignBadgeLeaf(key.first, key.second, key.third, count) }

    override fun readEnvelope(unit: String, envelopeId: String) {
        scope.launch { emitLevelRead(tool = EsignBadges.TOOL, unit = unit, level3 = envelopeId) }
    }
}
