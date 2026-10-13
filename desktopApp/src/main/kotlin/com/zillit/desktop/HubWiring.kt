package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeDrilldownQuery
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.media.AwtAttachmentPicker
import com.zillit.desktop.core.media.PickRefusal
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.media.contentTypeFor
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.socket.NotificationReadDto
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.transporthub.data.HubRepositoryImpl
import com.zillit.desktop.feature.transporthub.domain.HubAttachment
import com.zillit.desktop.feature.transporthub.domain.HubBadgeBucket
import com.zillit.desktop.feature.transporthub.domain.HubBadgeUnits
import com.zillit.desktop.feature.transporthub.domain.HubHost
import com.zillit.desktop.feature.transporthub.domain.HubMediaKind
import com.zillit.desktop.feature.transporthub.domain.HubViewer
import com.zillit.desktop.feature.transporthub.domain.PickedHubMedia
import com.zillit.desktop.feature.transporthub.ui.HubToolProvider
import com.zillit.desktop.feature.transporthub.ui.HubViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The Transportation Hub's view model, with what its pages borrow from the
 * app: the object store for vehicle photographs and licences, and the badge
 * ledger split the way the hub's rail needs it.
 *
 * Separate from [buildTransport] on purpose. The hub and the older
 * Transportation page are two tools on one service, and the web has pointed
 * its tile at the hub since April 2026 — keeping the wiring apart is what
 * lets the older page stay reachable, and untouched, while the hub is
 * finished.
 */
internal fun AppGraph.Ready.buildTransportHub(
    permissions: () -> ProjectPermissions,
): HubViewModel = HubViewModel(
    repository = HubRepositoryImpl(
        apiClient,
        config,
        bus = socketEvents,
        currentProjectId = { projectContext?.context?.value?.project?.projectId },
    ),
    resolveViewer = { hubViewer(permissions()) },
    nowMillis = System::currentTimeMillis,
    onSectionViewed = { bucket -> readHubBadges(bucket) },
    host = transportHubHost(),
)

internal fun AppGraph.Ready.transportHubProvider(viewModel: HubViewModel): HubToolProvider =
    HubToolProvider(viewModel = viewModel, openLink = ::openInBrowser)

/**
 * Who is looking at the hub.
 *
 * Posting rights on `transportation_tool` are the only door to the
 * coordinator's hub — the same rule the web's `resolveHubRole` keeps, and the
 * reason a designation is deliberately not one: User Management's rights grid
 * has to be able to revoke coordinator access from anyone, and a designation
 * door would survive the revoke.
 *
 * The designation comes from the **crew list**, not the profile, which does
 * not carry one. Two more things the role needs are the server's to answer —
 * which designations count as a driver's, and whether this person is a
 * temporary driver — and the view model fills both in once its first load
 * lands. Until then a driver reads as a passenger, which is the safe way
 * round: it offers less, not more.
 */
private fun AppGraph.Ready.hubViewer(permissions: ProjectPermissions): HubViewer {
    val context = projectContext?.context?.value
    val userId = context?.profile?.userId.orEmpty()
    val crewRow = userId.takeIf { it.isNotBlank() }?.let { context?.user(it) }
    return HubViewer(
        userId = userId,
        designation = crewRow?.designation.orEmpty(),
        // An empty rights set is "not resolved yet", not a denial: treating it
        // as one shows "no access" while the tools call is still in flight.
        canView = permissions.visibleTools.isEmpty() ||
            permissions.canView(HubViewer.TOOL_IDENTIFIER),
        canPost = permissions.canPost(HubViewer.TOOL_IDENTIFIER),
    )
}

/**
 * Marks a bucket's rows read, here and on the server.
 *
 * A bucket that owns its whole unit clears the unit. One that owns a slice
 * clears its own actions, one read and one frame each — which is what keeps
 * opening Trips from also clearing Shuttles, since both arrive under
 * `transportation_trip_label`.
 */
private suspend fun AppGraph.Ready.readHubBadges(bucket: HubBadgeBucket) {
    val projectId = projectContext?.context?.value?.project?.projectId ?: return
    val actions = bucket.actions.ifEmpty { setOf(null) }
    actions.forEach { action ->
        badgeStore.markRead(
            LedgerRead.Levels(
                tool = HubBadgeUnits.MODULE,
                unit = bucket.unit,
                action = action,
            ),
        )
        socketEvents.emit(
            ZillitSocketEvents.Badges.NotificationRead,
            NotificationReadDto(
                projectId = projectId,
                // The web sends the unit as `segment` and the module beside
                // it; `reference_id` stays empty so the whole slice clears
                // rather than one notification.
                segment = bucket.unit,
                module = HubBadgeUnits.MODULE,
                action = action,
                referenceId = "",
                timestamp = System.currentTimeMillis(),
            ),
            NotificationReadDto.serializer(),
        )
    }
}

/**
 * Picking and storing a file.
 *
 * The transport service takes a pointer on every file route and accepts no
 * upload of its own, so both halves are the app's: the chooser, and the
 * object store the web reaches through `uploadFile`.
 */
private fun AppGraph.Ready.transportHubHost(): HubHost {
    val picker = AwtAttachmentPicker()
    val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = {
            awsKeyPair(remoteConfigRepository)?.let { (access, secret) -> AwsCredentials(access, secret) }
        },
        storage = storageTarget,
        newKey = { fileName -> "transportation/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )
    return object : HubHost {
        override suspend fun pickAndStore(
            kind: HubMediaKind,
            multiple: Boolean,
        ): ZillitResult<List<PickedHubMedia>> {
            var refusal: String? = null
            val chosen = picker.pick(
                kind = if (kind == HubMediaKind.Image) PreviewKind.Image else PreviewKind.Document,
                multiple = multiple,
                maxBytes = HUB_MEDIA_MAX_BYTES,
                onRefused = { why ->
                    refusal = when (why) {
                        is PickRefusal.TooLarge -> "${why.name} is over the 10 MB limit."
                        is PickRefusal.WrongKind -> "${why.name} is not ${kind.noun}."
                    }
                },
                // The OS filter is advisory, so the extension is checked here
                // too — that is the check that actually holds.
            ).filter { kind.accepts(it.name) }
            if (chosen.isEmpty()) {
                return refusal?.let { ZillitResult.Failure(ZillitError.Unknown(it)) }
                    ?: ZillitResult.Success(emptyList())
            }
            val stored = mutableListOf<PickedHubMedia>()
            for (file in chosen) {
                val type = contentTypeFor(file.name, null)
                when (val result = uploader.upload(file.name, type, file.bytes)) {
                    is ZillitResult.Failure -> return result
                    is ZillitResult.Success -> stored += PickedHubMedia(
                        attachment = HubAttachment(
                            media = result.data.media,
                            name = file.name,
                            thumbnail = result.data.media,
                            // The family, not the full media type: the route's
                            // own example sends "image" with the extension
                            // beside it in `content_subtype`.
                            contentType = type.substringBefore('/').ifBlank { "image" },
                            contentSubtype = file.name.substringAfterLast('.', "").lowercase(),
                            bucket = result.data.bucket,
                            region = result.data.region,
                            fileSize = file.bytes.size.toString(),
                        ),
                        bytes = file.bytes,
                    )
                }
            }
            return ZillitResult.Success(stored)
        }

        /**
         * The rail's counts, one per bucket.
         *
         * Two of the hub's units carry several pages' notifications, told
         * apart by `action` — so the ledger is split by action within each
         * unit and the actions a bucket owns are summed. A bucket that owns
         * its whole unit takes the unit's own total instead, which also keeps
         * actions this build has not met yet inside somebody's count.
         */
        override val badges: Flow<Map<String, Int>> = badgeStore.counts
            .map { hubBadgeCounts() }
            .distinctUntilChanged()
    }
}

private fun AppGraph.Ready.hubBadgeCounts(): Map<String, Int> {
    val perUnit = badgeStore.split(
        BadgeDrilldownQuery(groupBy = "unit", tool = HubBadgeUnits.MODULE),
    )
    return HubBadgeBucket.entries.associate { bucket ->
        val count = if (bucket.actions.isEmpty() && bucket.silentActions.isEmpty()) {
            perUnit[bucket.unit] ?: 0
        } else {
            val perAction = badgeStore.split(
                BadgeDrilldownQuery(
                    groupBy = "action",
                    tool = HubBadgeUnits.MODULE,
                    unit = bucket.unit,
                ),
            )
            if (bucket.actions.isEmpty()) {
                // The whole unit less the actions that must stay quiet.
                (perUnit[bucket.unit] ?: 0) -
                    bucket.silentActions.sumOf { perAction[it] ?: 0 }
            } else {
                bucket.actions.sumOf { perAction[it] ?: 0 }
            }
        }
        bucket.name to count.coerceAtLeast(0)
    }
}

private val HubMediaKind.noun: String
    get() = when (this) {
        HubMediaKind.Image -> "a picture"
        HubMediaKind.ImageOrPdf -> "a picture or a PDF"
    }

private fun HubMediaKind.accepts(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (this) {
        HubMediaKind.Image -> extension in HUB_IMAGE_EXTENSIONS
        HubMediaKind.ImageOrPdf -> extension in HUB_IMAGE_EXTENSIONS || extension == "pdf"
    }
}

private val HUB_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp")
private const val HUB_MEDIA_MAX_BYTES = 10L * 1024 * 1024
