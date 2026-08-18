package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.documentdistribution.data.FromToolFile
import com.zillit.desktop.feature.documentdistribution.data.FromToolPublisher
import com.zillit.desktop.feature.home.ui.DistributionHook

/**
 * The board's hand-off to Document Distribution: the phones' gate (posting
 * rights on the Distribution tool) and the library's from-tool route, which
 * takes the storage keys and re-uploads nothing.
 */
internal fun distributionHook(
    ready: AppGraph.Ready,
    permissions: () -> ProjectPermissions,
) = DistributionHook(
    canPublish = { permissions().canPost(DOC_DISTRIBUTION_TOOL) },
    publish = { notice, folderPath, folderDate ->
        val file = notice.attachment
        if (file == null) {
            ZillitResult.Failure(ZillitError.Storage("publish asked for a post with no file"))
        } else {
            FromToolPublisher(ready.apiClient, ready.config).publish(
                FromToolFile(
                    folderPath = folderPath,
                    name = file.fileName,
                    media = file.media,
                    bucket = file.bucket,
                    region = file.region,
                    contentType = file.contentType,
                    contentSubtype = file.contentSubtype,
                    thumbnail = file.thumbnail,
                    caption = notice.body,
                    fileSizeBytes = file.sizeBytes,
                    durationMillis = file.durationMillis,
                    widthPx = file.widthPx,
                    heightPx = file.heightPx,
                    folderDate = folderDate,
                ),
            ).map { }
        }
    },
)

/** `Constants.DOC_DISTRIBUTION_TOOL_LABEL` on Android — the rights row the gate reads. */
private const val DOC_DISTRIBUTION_TOOL = "document_distribution_tool"
