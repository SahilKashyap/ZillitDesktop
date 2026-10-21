package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.addashboard.data.AdRepositoryImpl
import com.zillit.desktop.feature.addashboard.domain.AdCrewMember
import com.zillit.desktop.feature.addashboard.domain.AdCurrencies
import com.zillit.desktop.feature.addashboard.domain.AdHost
import com.zillit.desktop.feature.addashboard.domain.AdTransfer
import com.zillit.desktop.feature.addashboard.domain.AdViewer
import com.zillit.desktop.feature.addashboard.domain.PickedFile
import com.zillit.desktop.feature.addashboard.domain.StoredAttachment
import com.zillit.desktop.feature.addashboard.ui.AdToolProvider
import com.zillit.desktop.feature.addashboard.ui.AdViewModel
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * The AD dashboard — the production's side of supporting artistes.
 *
 * Three seams, each for something that is not the AD service's: [adHost] is
 * the machine and the open production (the file dialog, the Downloads folder,
 * the crew, the currencies), [adTransfer] is the byte-level I/O the envelope
 * client cannot do, and the name resolver turns an internal artiste's
 * `user_id` into a crew name — an internal artiste *is* a project user, and
 * their name lives on that user rather than on the artiste record. The web
 * resolves it the same way, off the same list.
 */
internal fun AppGraph.Ready.buildAdDashboard(permissions: () -> ProjectPermissions) = AdViewModel(
    repository = AdRepositoryImpl(
        apiClient = apiClient,
        config = config,
        resolveName = { userId -> crewName(userId) },
        transfer = adTransfer(),
        host = adHost(),
        now = { System.currentTimeMillis() },
    ),
    events = socketEvents,
    host = adHost(),
    rights = rightsRequests,
    // Every AD event files under the tool with the dashboard tab as its unit
    // (`constants.js`, "AD Dashboard module"), the register's naming its
    // artiste in `level_3`.
    badges = unitEntityBadges(tool = "ad_dashboard_label"),
    now = { System.currentTimeMillis() },
    viewer = { adViewer(permissions()) },
)

private fun AppGraph.Ready.crewName(userId: String): String? =
    projectContext?.context?.value?.users
        ?.firstOrNull { it.userId == userId }
        ?.fullName
        ?.takeIf { it.isNotBlank() }

private fun AppGraph.Ready.adViewer(permissions: ProjectPermissions): AdViewer = AdViewer.from(
    permissions = permissions,
    userId = projectContext?.context?.value?.profile?.userId.orEmpty(),
)

/**
 * What the dashboard asks the machine and the open production for.
 *
 * The crew comes from the project context the application already holds —
 * never a request of its own — and is filtered inside the module to the three
 * artiste groups. The currencies are the production's, because money on this
 * service is per row: each artiste-day carries its own code.
 */
private fun AppGraph.Ready.adHost(): AdHost = object : AdHost {

    override suspend fun pickFile(extensions: List<String>): PickedFile? =
        attachmentPicker.pick(PreviewKind.Document, multiple = false, maxBytes = MAX_IMPORT_BYTES)
            .firstOrNull { picked ->
                extensions.isEmpty() ||
                    picked.name.substringAfterLast('.', "").lowercase() in extensions
            }
            ?.let { PickedFile(name = it.name, contentType = it.contentType, bytes = it.bytes) }

    override suspend fun saveToDownloads(fileName: String, bytes: ByteArray): ZillitResult<String> =
        DownloadsAttachmentStore().save(fileName, bytes)

    override fun openFile(path: String) = openSavedFile(path)

    // Qualified: the member and the top-level function share a name, and an
    // unqualified call here would recurse into itself.
    override fun copyToClipboard(text: String) = com.zillit.desktop.copyToClipboard(text)

    override fun crew(): List<AdCrewMember> =
        projectContext?.context?.value?.users.orEmpty().map { user ->
            AdCrewMember(
                userId = user.userId,
                fullName = user.fullName,
                email = user.email.orEmpty(),
                // Label keys off the roster; the pickers print both.
                department = user.department.orEmpty().localised(),
                departmentIdentifier = user.departmentIdentifier.orEmpty(),
                designation = user.designation.orEmpty().localised(),
                // The snapshot carries no designation identifier, so the
                // Supporting Cast match runs on the name alone — which the
                // web's own regex covers, because the two spellings differ by
                // source and it searches both.
                designationIdentifier = "",
                avatarUrl = user.avatarUrl.orEmpty(),
            )
        }

    /**
     * The production's currencies, read once per open.
     *
     * A failed read leaves no symbol rather than the wrong one: the project
     * default is the only source, and an unsymboled number is honest where a
     * guessed sign is not.
     */
    override suspend fun currencies(): AdCurrencies {
        val settings = accountHubRepository.currencies().getOrNull() ?: return AdCurrencies()
        return AdCurrencies(
            defaultCode = settings.defaultCode.orEmpty(),
            symbols = settings.currencies.associate { it.code to it.symbol },
        )
    }

    /**
     * The department a raised purchase order is filed under — the AD's own.
     *
     * Off the hub roster, which is where the department's **id** lives; the
     * project context carries only its name and identifier. A failed read
     * sends no department, as the web sends null when it has none.
     */
    override suspend fun departmentId(): String? {
        val me = projectContext?.context?.value?.profile?.userId ?: return null
        return hubUsers()
            .firstOrNull { it.id == me }
            ?.departmentId
            ?.takeIf { it.isNotBlank() }
    }

    /** The web's `currentUser.isAccountant`, matched as the hub matches it. */
    override fun isAccountant(): Boolean {
        val context = projectContext?.context?.value ?: return false
        val me = context.user(context.profile?.userId)
        return AccountHubViewer.isAccountsDepartment(listOfNotNull(me?.department))
    }

    /**
     * Where an artiste's portal link points.
     *
     * The web's own origin, not this application's: the artiste opens the link
     * in a browser, and only the web deployment serves `/extras-portal/:token`.
     */
    override fun portalOrigin(): String =
        config.baseUrl(ZillitService.BudgetBuilderWeb).trimEnd('/')

    /**
     * The strokes, or a typed name, as a transparent PNG.
     *
     * Drawn at the size the module asks for and scaled from the pad's own
     * coordinates, so the signature keeps the shape the person saw. Null when
     * there is nothing to draw — the sign-off then refuses rather than
     * sending bytes that are not an image.
     */
    override suspend fun renderSignature(
        strokes: List<List<Pair<Float, Float>>>,
        typedName: String,
        widthPx: Int,
        heightPx: Int,
    ): ByteArray? = withContext(Dispatchers.IO) {
        renderSignaturePng(strokes, typedName, widthPx, heightPx)
    }
}

/**
 * The byte-level I/O: an agency spreadsheet and a signature image out, a
 * report's PDF and a list export back.
 *
 * Storage PUTs are signed with the workspace's own AWS keys, and the two raw
 * routes need the encrypted headers an envelope call carries — neither of
 * which the module's JSON client can do, which is why the host supplies them.
 */
private fun AppGraph.Ready.adTransfer(): AdTransfer = object : AdTransfer {

    private val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = {
            awsKeyPair(remoteConfigRepository)?.let { (access, secret) -> AwsCredentials(access, secret) }
        },
        storage = storageTarget,
        // The repository chose the key; it is scoped to the production so one
        // project's imports cannot be listed from another.
        newKey = { fileName ->
            val projectId = projectContext?.context?.value?.project?.projectId.orEmpty()
            val stem = fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_")
            val ext = fileName.substringAfterLast('.', "bin")
            "$projectId/film-tools/ad-dashboard/${System.currentTimeMillis()}_$stem.$ext"
        },
    )

    override suspend fun putObject(
        key: String,
        contentType: String,
        bytes: ByteArray,
    ): ZillitResult<StoredAttachment> =
        when (val stored = uploader.upload(key, contentType, bytes)) {
            is ZillitResult.Failure -> ZillitResult.Failure(stored.error)
            is ZillitResult.Success -> ZillitResult.Success(
                StoredAttachment(
                    // `media` is the object key; the name is historical.
                    key = stored.data.media,
                    bucket = stored.data.bucket,
                    region = stored.data.region,
                    fileName = stored.data.fileName,
                    contentType = stored.data.contentType,
                    size = stored.data.sizeBytes,
                ),
            )
        }

    override suspend fun fetchObject(attachment: StoredAttachment): ZillitResult<ByteArray> =
        noticeMedia.fetch(
            NoticeAttachment(
                media = attachment.key,
                bucket = attachment.bucket,
                region = attachment.region,
            ),
            preview = false,
        )

    override suspend fun getBytes(url: String): ZillitResult<ByteArray> = getForBytes(url)

    override suspend fun postBytes(url: String, body: JsonObject): ZillitResult<ByteArray> =
        postForBytes(url, body)

    override suspend fun postMultipart(
        url: String,
        fields: Map<String, String>,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
    ): ZillitResult<kotlinx.serialization.json.JsonElement> = ZillitResult.Failure(
        // Nothing in this module needs it: the import and the signature both
        // go to storage first and ride as attachments.
        ZillitError.Validation("the AD dashboard sends no multipart"),
    )
}

internal fun adDashboardProvider(viewModel: AdViewModel) = AdToolProvider(viewModel)

/** Well past what an agency list ever is; a picker that admits a 2 GB file hangs before refusing it. */
private const val MAX_IMPORT_BYTES = 50L * 1024 * 1024

/**
 * A signature as a transparent PNG.
 *
 * The ink is scaled from the pad's own pixel coordinates into the requested
 * canvas, so what is stored matches what was drawn whatever size the pad was.
 * A typed name is centred in a script-ish face instead, which is the fallback
 * for signing with a mouse.
 */
private fun renderSignaturePng(
    strokes: List<List<Pair<Float, Float>>>,
    typedName: String,
    widthPx: Int,
    heightPx: Int,
): ByteArray? {
    val inked = strokes.filter { it.size > 1 }
    if (inked.isEmpty() && typedName.isBlank()) return null

    val image = BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB)
    val canvas = image.createGraphics()
    canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    canvas.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    canvas.color = Color.BLACK

    if (inked.isNotEmpty()) {
        val xs = inked.flatten().map { it.first }
        val ys = inked.flatten().map { it.second }
        val spanX = (xs.max() - xs.min()).takeIf { it > 1f } ?: 1f
        val spanY = (ys.max() - ys.min()).takeIf { it > 1f } ?: 1f
        // One scale for both axes keeps the handwriting's proportions.
        val scale = minOf((widthPx - 2 * INK_PAD) / spanX, (heightPx - 2 * INK_PAD) / spanY)
        val offsetX = INK_PAD - xs.min() * scale
        val offsetY = INK_PAD - ys.min() * scale
        canvas.stroke = BasicStroke(INK_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        inked.forEach { stroke ->
            val path = Path2D.Float()
            stroke.forEachIndexed { index, (x, y) ->
                val px = x * scale + offsetX
                val py = y * scale + offsetY
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.draw(path)
        }
    } else {
        canvas.font = Font(Font.SERIF, Font.ITALIC, TYPED_POINT_SIZE)
        val metrics = canvas.fontMetrics
        val x = ((widthPx - metrics.stringWidth(typedName)) / 2).coerceAtLeast(INK_PAD.toInt())
        val y = (heightPx + metrics.ascent - metrics.descent) / 2
        canvas.drawString(typedName, x, y)
    }
    canvas.dispose()

    return ByteArrayOutputStream().use { sink ->
        if (!ImageIO.write(image, "png", sink)) return null
        sink.toByteArray()
    }
}

private const val INK_WIDTH = 2.5f
private const val INK_PAD = 12f
private const val TYPED_POINT_SIZE = 44
