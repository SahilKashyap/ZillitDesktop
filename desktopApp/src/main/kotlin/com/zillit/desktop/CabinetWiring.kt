package com.zillit.desktop

import com.zillit.desktop.core.cabinet.CabinetViewer
import com.zillit.desktop.core.cabinet.CabinetViewers
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.castboard.cabinet.castingCabinetViewers
import com.zillit.desktop.feature.chat.cabinet.CabinetChatHooks
import com.zillit.desktop.feature.chat.cabinet.GroupChatCabinetViewer
import com.zillit.desktop.feature.continuity.cabinet.ContinuityCabinetViewer
import com.zillit.desktop.feature.home.cabinet.homeCabinetViewers
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.ui.HomeBoardContext
import com.zillit.desktop.feature.location.cabinet.LocationCabinetViewer
import com.zillit.desktop.feature.location.data.LocationRepositoryImpl
import com.zillit.desktop.feature.pagedistribution.cabinet.cabinetReader
import com.zillit.desktop.feature.pagedistribution.cabinet.distributionCabinetViewers
import com.zillit.desktop.feature.pagedistribution.data.DistributionRepositoryImpl
import com.zillit.desktop.feature.purchaseorder.cabinet.PurchaseOrderCabinetViewer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

/**
 * The File Cabinet's read-only viewers, one per row a tool contributes.
 *
 * Each tool's feature module exposes a `CabinetViewer` for the rows it owns;
 * this is the one place they are gathered and handed to Admin Settings, so
 * settings never depends on a tool. A row with no viewer here is select-and-
 * download only — Account Hub, Document Distribution, Transportation and Recce,
 * whose rows do nothing on a click on the web either.
 *
 * The cabinet is an admin page, so viewers that gate on the tool's rights are
 * built as an admin reading: view and download, never post.
 */
internal fun AppGraph.Ready.cabinetViewers(permissions: () -> ProjectPermissions): CabinetViewers {
    // Outlives any one viewer: a download started in the cabinet finishes after its window closes.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    return CabinetViewers(
        homeBoardViewers(scope) +
            groupChatViewer() +
            distributionViewers() +
            listOf(purchaseOrderViewer(scope), locationViewer(), continuityViewer(permissions)) +
            budgetCabinetViewers(scope) +
            castingCabinetViewers(
                apiClient = apiClient,
                config = config,
                permissions = { (toolsRepository.loadPermissions() as? ZillitResult.Success)?.data },
                encrypt = { plain -> (noticeDecryptor.encryptToHex(plain) as? ZillitResult.Success)?.data },
                decrypt = { cipher -> (noticeDecryptor.decryptFromHex(cipher) as? ZillitResult.Success)?.data },
                myUserId = { projectContext?.context?.value?.profile?.userId },
                loadPhoto = { media -> castingPhoto(this, media) },
            ),
    )
}

private fun AppGraph.Ready.isAdmin() = projectContext?.context?.value?.isAdmin == true

private fun AppGraph.Ready.isTelevision() =
    projectContext?.context?.value?.project?.subType?.contains("television", ignoreCase = true) == true

/** Home, Info, Confidential Info, Accounts, Catering, Script Notes, Reports, Production Report. */
private fun AppGraph.Ready.homeBoardViewers(scope: CoroutineScope): List<CabinetViewer> = homeCabinetViewers(
    apiClient = apiClient,
    config = config,
    decrypt = noticeDecryptor,
    homeFeed = homeFeedRepository,
    tools = toolsRepository,
    board = HomeBoardContext(
        media = noticeMedia,
        onOpenAttachment = openNoticeAttachment(this, scope),
        onOpenLocation = { openInBrowser(it.mapsUrl) },
        onOpenLink = { openInBrowser(it) },
        loadAvatar = { fetchAvatar(this, it) },
        crewNames = { projectContext?.context?.value?.users?.map { u -> u.fullName }.orEmpty() },
        resolveAuthor = { projectContext?.context?.value?.user(it)?.authorLine() },
    ),
    nowMillis = System::currentTimeMillis,
    isAdmin = { isAdmin() },
    currentUserId = { projectContext?.context?.value?.profile?.userId },
)

private fun AppGraph.Ready.groupChatViewer(): CabinetViewer = GroupChatCabinetViewer(
    chatRepository,
    CabinetChatHooks(
        resolveName = { id -> projectContext?.context?.value?.users?.firstOrNull { it.userId == id }?.fullName },
        loadAvatar = { id -> fetchAvatar(this, id)?.let(::decodeImageBitmap) },
        loadThumbnail = { f -> fetchChatImage(this, f, preview = true) },
        loadFullImage = { f -> fetchChatImage(this, f, preview = false) },
        onOpenAttachment = { f -> openChatAttachment(this, f) },
        onOpenUrl = ::openInBrowser,
    ),
)

/** Script Distribution, Schedule Distribution and D.O.D, one engine. */
private fun AppGraph.Ready.distributionViewers(): List<CabinetViewer> = distributionCabinetViewers(
    repository = DistributionRepositoryImpl(
        apiClient = apiClient,
        config = config,
        newUniqueId = { UUID.randomUUID().toString() },
        nowMillis = System::currentTimeMillis,
    ),
    transfer = distributionTransfer(),
    reader = { cabinetReader(projectContext?.context?.value?.profile?.userId.orEmpty(), isTelevision()) },
    nowMillis = System::currentTimeMillis,
    resolveUser = { projectContext?.context?.value?.user(it)?.authorLine() },
    loadAvatar = crewFaceLoader(this),
)

private fun AppGraph.Ready.purchaseOrderViewer(scope: CoroutineScope): CabinetViewer = PurchaseOrderCabinetViewer(
    repository = purchaseOrderRepository,
    people = poSettingsPeople(),
    projectSettings = poProjectSettings(),
    onOpenAttachment = { f ->
        openNoticeAttachment(this, scope)(
            NoticeAttachment(media = f.media, fileName = f.displayName, bucket = f.bucket, region = f.region),
        )
    },
)

private fun AppGraph.Ready.locationViewer(): CabinetViewer = LocationCabinetViewer(
    repository = LocationRepositoryImpl(
        apiClient,
        config,
        newUniqueId = { UUID.randomUUID().toString() },
        decrypt = { c -> (noticeDecryptor.decryptFromHex(c) as? ZillitResult.Success)?.data },
        myUserId = { projectContext?.context?.value?.profile?.userId },
    ),
    transfer = locationTransfer(),
    isTelevision = { isTelevision() },
    nowMillis = System::currentTimeMillis,
    loadImage = { a, preview ->
        (
            noticeMedia.fetch(
                NoticeAttachment(
                    media = a.media,
                    fileName = a.name,
                    thumbnail = a.thumbnail,
                    bucket = a.bucket,
                    region = a.region,
                ),
                preview = preview,
            ) as? ZillitResult.Success
            )?.data?.let(::decodeImageBitmap)
    },
    resolveUser = { projectContext?.context?.value?.user(it)?.authorLine() },
)

private fun AppGraph.Ready.continuityViewer(permissions: () -> ProjectPermissions): CabinetViewer {
    // Its own view model: the cabinet's open folder is separate from the tool's.
    val model = buildContinuity(permissions)
    return continuityProvider(model, CoroutineScope(SupervisorJob() + Dispatchers.Default)).cabinetViewer(model)
}
