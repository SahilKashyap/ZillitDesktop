package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeDrilldownQuery
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.media.AwtAttachmentPicker
import com.zillit.desktop.core.media.PickRefusal
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.media.contentTypeFor
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.network.S3Presigner
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.ui.CallEvent
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.transportation.data.TransportRepositoryImpl
import com.zillit.desktop.feature.transportation.domain.DialCountry
import com.zillit.desktop.feature.transportation.domain.PickedMedia
import com.zillit.desktop.feature.transportation.domain.StoredMedia
import com.zillit.desktop.feature.transportation.domain.TransportHost
import com.zillit.desktop.feature.transportation.domain.TransportMediaKind
import com.zillit.desktop.feature.transportation.domain.TransportViewer
import com.zillit.desktop.feature.transportation.ui.TransportSlots
import com.zillit.desktop.feature.transportation.ui.TransportToolProvider
import com.zillit.desktop.feature.transportation.ui.TransportViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The Transportation tool's view model, with everything its screens borrow
 * from the app: the object store for licences, documents and vehicle
 * photographs; the dialling-code preset; and the badge ledger's counts for
 * its tabs.
 */
internal fun AppGraph.Ready.buildTransport(permissions: () -> ProjectPermissions): TransportViewModel =
    TransportViewModel(
        repository = TransportRepositoryImpl(
            apiClient,
            config,
            bus = socketEvents,
            currentProjectId = { projectContext?.context?.value?.project?.projectId },
            nowMs = System::currentTimeMillis,
        ),
        resolveViewer = {
            TransportViewer.from(permissions(), projectContext?.context?.value?.profile?.userId.orEmpty())
        },
        nowMillis = System::currentTimeMillis,
        // The web's `notification:read` for a request segment, module `transportation_label`.
        onSegmentViewed = { segment -> emitSegmentRead(this, segment = segment, module = TRANSPORT_MODULE) },
        host = transportHost(),
    )

/**
 * The tool as a workspace window: faces from the crew's profile pictures,
 * stored pictures read back through the same presigned route Document
 * Distribution uses, a driver's row ringing them on Line 1, and the browser
 * for map links and PDFs.
 */
internal fun AppGraph.Ready.transportProvider(
    viewModel: TransportViewModel,
    viewModels: AppViewModels,
): TransportToolProvider {
    val presigner = S3Presigner(credentials = { awsKeyPair(remoteConfigRepository) })
    val faces = crewFaceLoader(this)
    return TransportToolProvider(
        viewModel = viewModel,
        slots = TransportSlots(
            loadAvatar = faces,
            loadMedia = { media -> fetchStored(media)?.let(::decodeImageBitmap) },
            call = viewModels.calls?.let { calls ->
                { user ->
                    calls.onEvent(
                        CallEvent.Place(
                            chatRoomId = "",
                            receiverDeviceId = user.deviceId.ifBlank {
                                projectContext?.context?.value?.user(user.userId)?.deviceId.orEmpty()
                            },
                            mode = CallMode.Private,
                            type = CallType.Audio,
                            displayName = user.fullName,
                            receiverUserId = user.userId,
                        ),
                    )
                }
            },
            openDocument = { media ->
                presigner.presignedGet(bucket = media.bucket, region = media.region, key = media.media)
                    ?.let(::openInBrowser)
            },
        ),
        openLink = ::openInBrowser,
    )
}

/**
 * A stored file's bytes — a transport record names its bucket and region on
 * every pointer, so the production's default target is only a fallback for
 * the older rows that carried the key alone.
 */
private suspend fun AppGraph.Ready.fetchStored(media: StoredMedia): ByteArray? {
    val target = (storageTarget.target() as? ZillitResult.Success)?.data
    val stored = NoticeAttachment(
        media = media.media,
        bucket = media.bucket.ifBlank { target?.bucket },
        region = media.region.ifBlank { target?.region },
    )
    return (noticeMedia.fetch(stored, preview = false) as? ZillitResult.Success)?.data
}

/**
 * Picking and storing, the way the card module does it: the transport
 * service takes a pointer on every file route and no upload of its own.
 */
private fun AppGraph.Ready.transportHost(): TransportHost {
    val picker = AwtAttachmentPicker()
    val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = {
            awsKeyPair(remoteConfigRepository)?.let { (access, secret) -> AwsCredentials(access, secret) }
        },
        storage = storageTarget,
        newKey = { fileName -> "transportation/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )
    return object : TransportHost {
        override suspend fun pickAndStore(
            kind: TransportMediaKind,
            multiple: Boolean,
        ): ZillitResult<List<PickedMedia>> {
            var refusal: String? = null
            val chosen = picker.pick(
                kind = if (kind == TransportMediaKind.Image) PreviewKind.Image else PreviewKind.Document,
                multiple = multiple,
                maxBytes = MEDIA_MAX_BYTES,
                onRefused = { why ->
                    refusal = when (why) {
                        is PickRefusal.TooLarge -> "${why.name} is over the 10 MB limit."
                        is PickRefusal.WrongKind -> "${why.name} is not ${kind.noun}."
                    }
                },
            ).filter { kind.accepts(it.name) }
            if (chosen.isEmpty()) {
                return refusal?.let { ZillitResult.Failure(ZillitError.Unknown(it)) }
                    ?: ZillitResult.Success(emptyList())
            }
            val stored = mutableListOf<PickedMedia>()
            for (file in chosen) {
                val type = contentTypeFor(file.name, null)
                when (val result = uploader.upload(file.name, type, file.bytes)) {
                    is ZillitResult.Failure -> return result
                    is ZillitResult.Success -> stored += PickedMedia(
                        stored = StoredMedia(
                            media = result.data.media,
                            bucket = result.data.bucket,
                            region = result.data.region,
                            contentType = type,
                            contentSubtype = file.name.substringAfterLast('.', "").lowercase(),
                            name = file.name,
                        ),
                        bytes = file.bytes,
                    )
                }
            }
            return ZillitResult.Success(stored)
        }

        override suspend fun countries(): List<DialCountry> =
            accountHubRepository.isdCodes().getOrNull().orEmpty()
                .filter { it.dialCode.isNotBlank() }
                .map { DialCountry(name = it.name, dialCode = it.dialCode) }

        /**
         * The web's `getBadgeForTile` walk, from the ledger: a request's
         * status is the row's `unit` (`transportation_trip_request_pending_label`),
         * the group it rolls into (`transportation_trip_request_label`) its
         * `level_1`, so both dimensions are answered and merged.
         */
        override val badges: Flow<Map<String, Int>> = badgeStore.counts
            .map {
                badgeStore.split(BadgeDrilldownQuery(groupBy = "unit", tool = TRANSPORT_MODULE)) +
                    badgeStore.split(BadgeDrilldownQuery(groupBy = "level_1", tool = TRANSPORT_MODULE))
            }
            .distinctUntilChanged()
    }
}

private val TransportMediaKind.noun: String
    get() = when (this) {
        TransportMediaKind.Image -> "a picture"
        TransportMediaKind.ImageOrPdf -> "a picture or a PDF"
    }

/** Checked by extension, the check that actually holds — the OS filter is advisory. */
private fun TransportMediaKind.accepts(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (this) {
        TransportMediaKind.Image -> extension in IMAGE_EXTENSIONS
        TransportMediaKind.ImageOrPdf -> extension in IMAGE_EXTENSIONS || extension == "pdf"
    }
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp")
private const val MEDIA_MAX_BYTES = 10L * 1024 * 1024
private const val TRANSPORT_MODULE = "transportation_label"
