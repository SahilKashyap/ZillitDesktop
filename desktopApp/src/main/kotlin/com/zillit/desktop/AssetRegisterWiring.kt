package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.assetreport.data.AssetRepositoryImpl
import com.zillit.desktop.feature.assetreport.data.exportBody
import com.zillit.desktop.feature.assetreport.data.normaliseFormat
import com.zillit.desktop.feature.assetreport.domain.AssetExport
import com.zillit.desktop.feature.assetreport.domain.AssetViewer
import com.zillit.desktop.feature.assetreport.ui.AssetViewModel
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore

/**
 * The Asset Register (`purchase-orders/asset-register` on the PO host).
 * Export streams bytes from the authenticated export route and lands in
 * Downloads; department names ride the admin repository's existing read.
 */
internal fun AppGraph.Ready.buildAssetRegister(
    permissions: () -> ProjectPermissions,
): AssetViewModel = AssetViewModel(
    repository = AssetRepositoryImpl(apiClient, config),
    export = AssetExport { format ->
        val cleaned = normaliseFormat(format)
        postForBytes(
            url = "${config.apiV2(ZillitService.PurchaseOrder)}purchase-orders/asset-register/export",
            // View-scoped users send no departments; the server scopes them.
            body = exportBody(cleaned, emptyList()),
        ).flatMap { bytes ->
            when (val saved = DownloadsAttachmentStore().save("asset-register.$cleaned", bytes)) {
                is ZillitResult.Failure -> saved
                is ZillitResult.Success -> {
                    openSavedFile(saved.data)
                    ZillitResult.Success(Unit)
                }
            }
        }
    },
    resolveViewer = { AssetViewer.from(permissions()) },
    loadDepartments = {
        when (val got = adminRepository.departments()) {
            is ZillitResult.Success -> got.data.associate { it.id to it.name }
            is ZillitResult.Failure -> emptyMap()
        }
    },
)
