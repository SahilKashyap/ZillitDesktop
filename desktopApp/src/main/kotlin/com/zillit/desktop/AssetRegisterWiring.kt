package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.media.AwtAttachmentPicker
import com.zillit.desktop.core.media.PickRefusal
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.assetreport.data.AssetRepositoryImpl
import com.zillit.desktop.feature.assetreport.data.exportBody
import com.zillit.desktop.feature.assetreport.domain.AssetAttachment
import com.zillit.desktop.feature.assetreport.domain.AssetCurrencies
import com.zillit.desktop.feature.assetreport.domain.AssetCurrency
import com.zillit.desktop.feature.assetreport.domain.AssetDepartment
import com.zillit.desktop.feature.assetreport.domain.AssetDirectory
import com.zillit.desktop.feature.assetreport.domain.AssetExport
import com.zillit.desktop.feature.assetreport.domain.AssetFileRules
import com.zillit.desktop.feature.assetreport.domain.AssetFiles
import com.zillit.desktop.feature.assetreport.domain.AssetFormat
import com.zillit.desktop.feature.assetreport.domain.AssetPerson
import com.zillit.desktop.feature.assetreport.domain.AssetViewer
import com.zillit.desktop.feature.assetreport.domain.PickedAssetFile
import com.zillit.desktop.feature.assetreport.domain.extensionOf
import com.zillit.desktop.feature.assetreport.ui.AssetViewModel
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import java.util.UUID
import com.zillit.desktop.core.media.contentTypeFor as mediaTypeFor

/**
 * The Asset Register (`purchase-orders/asset-register` on the PO host).
 *
 * The module owns the register's own service; everything else it shows is the
 * host's to fetch — departments from the admin service, currencies and people
 * from the account hub, files through the shared picker, the production's
 * bucket and the board's signed reader, exports through the raw byte POST.
 */
internal fun AppGraph.Ready.buildAssetRegister(
    permissions: () -> ProjectPermissions,
): AssetViewModel = AssetViewModel(
    repository = AssetRepositoryImpl(apiClient, config),
    export = assetRegisterExport(),
    resolveViewer = { AssetViewer.from(permissions(), isAccountant = viewerIsAccountant()) },
    directory = assetRegisterDirectory(),
    files = assetRegisterFiles(),
    rights = rightsRequests,
)

/**
 * `POST …/asset-register/export` → Downloads, opened. Named as the web names
 * it, `asset-register_<local stamp>.<ext>`: the server's own name never reaches
 * a browser (CORS hides it), so every client supplies one.
 */
private fun AppGraph.Ready.assetRegisterExport() = AssetExport { format, departmentIds ->
    postForBytes(
        url = "${config.apiV2(ZillitService.PurchaseOrder)}purchase-orders/asset-register/export",
        body = exportBody(format, departmentIds),
    ).flatMap { bytes ->
        val name = AssetFormat.exportFileName(format, System.currentTimeMillis())
        when (val saved = DownloadsAttachmentStore().save(name, bytes)) {
            is ZillitResult.Failure -> saved
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
        }
    }
}

/** The web's `currentUser.isAccountant`: the accounts department, matched as the hub matches it. */
private fun AppGraph.Ready.viewerIsAccountant(): Boolean {
    val context = projectContext?.context?.value ?: return false
    val me = context.user(context.profile?.userId)
    return AccountHubViewer.isAccountsDepartment(listOfNotNull(me?.department))
}

private fun AppGraph.Ready.assetRegisterDirectory() = object : AssetDirectory {

    /** In the admin listing's order, which the web keeps rather than alphabetising. */
    override suspend fun departments(): List<AssetDepartment> = when (val loaded = adminRepository.departments()) {
        is ZillitResult.Success -> loaded.data.map { AssetDepartment(id = it.id, name = it.name) }
        is ZillitResult.Failure -> emptyList()
    }

    /**
     * Production Setup's currencies with their rates. A production that has
     * picked none gets the whole catalogue, as the web's selector falls back to
     * it — a currency picker must never open empty.
     */
    override suspend fun currencies(): AssetCurrencies {
        val settings = accountHubRepository.currencies().getOrNull()
        val configured = settings?.currencies.orEmpty().filter { it.code.isNotBlank() }
        val offered = configured.ifEmpty { accountHubRepository.currencyCatalogue().getOrNull().orEmpty() }
        return AssetCurrencies(
            options = offered.map { AssetCurrency(code = it.code, name = it.name, symbol = it.symbol, rate = it.rate) },
            defaultCode = settings?.defaultCode.orEmpty(),
        )
    }

    /** Who wrote a note — the crew the hub's pickers read, name and designation. */
    override suspend fun people(): List<AssetPerson> =
        hubUsers().map { AssetPerson(id = it.id, name = it.name, designation = it.designation) }
}

private fun AppGraph.Ready.assetRegisterFiles(): AssetFiles {
    val picker = AwtAttachmentPicker()
    // Its own prefix: a photo of kit on a shelf is evidence about one asset,
    // not a document the production issues.
    val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = {
            awsKeyPair(remoteConfigRepository)?.let { (access, secret) -> AwsCredentials(access, secret) }
        },
        storage = storageTarget,
        newKey = { fileName -> "asset-register/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )
    val downloads = DownloadsAttachmentStore()

    return object : AssetFiles {
        override suspend fun pick(onTooLarge: (name: String, sizeBytes: Long) -> Unit): List<PickedAssetFile> =
            // Any file: the images-and-PDF rule is the module's, checked after the
            // choice as it is after a drop — a dialog filter is only a hint.
            picker.pick(
                kind = PreviewKind.Document,
                multiple = true,
                maxBytes = AssetFileRules.MAX_BYTES,
                onRefused = { refusal ->
                    if (refusal is PickRefusal.TooLarge) onTooLarge(refusal.name, refusal.sizeBytes)
                },
            ).map { PickedAssetFile(name = it.name, bytes = it.bytes) }

        override suspend fun upload(file: PickedAssetFile): ZillitResult<AssetAttachment> =
            uploader.upload(file.name, mediaTypeFor(file.name, null), file.bytes).map { stored ->
                // The hub's canonical model: `content_type` is the family, `content_subtype` the extension.
                AssetAttachment(
                    media = stored.media,
                    bucket = stored.bucket,
                    region = stored.region,
                    name = file.name,
                    contentType = AssetFileRules.familyOf(file.name),
                    contentSubtype = extensionOf(file.name),
                )
            }

        override suspend fun read(attachment: AssetAttachment): ZillitResult<ByteArray> =
            noticeMedia.fetch(
                NoticeAttachment(media = attachment.media, bucket = attachment.bucket, region = attachment.region),
                preview = false,
            )

        override suspend fun saveCopy(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
            when (val saved = downloads.save(fileName, bytes)) {
                is ZillitResult.Failure -> saved
                is ZillitResult.Success -> {
                    openSavedFile(saved.data)
                    ZillitResult.Success(Unit)
                }
            }
    }
}
