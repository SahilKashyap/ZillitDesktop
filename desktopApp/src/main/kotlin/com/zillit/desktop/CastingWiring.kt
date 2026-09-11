package com.zillit.desktop

import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.castboard.data.CastingRepositoryImpl
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.CastingMedia
import com.zillit.desktop.feature.castboard.ui.CastingToolProvider
import com.zillit.desktop.feature.castboard.ui.CastingViewModel
import com.zillit.desktop.feature.home.domain.NoticeAttachment

/**
 * The Casting and Wardrobe boards' host wiring.
 *
 * Both are the same engine on different services (see `BoardTool`), so this
 * builds one view model per board. Stills come through the same routed store
 * the notice boards read — a casting or costume photograph is an attachment
 * like any other, and its URL has to be signed before it can be fetched.
 */
internal fun AppGraph.Ready.buildCastBoard(
    board: BoardTool,
    permissions: () -> ProjectPermissions,
) = CastingViewModel(
    repository = CastingRepositoryImpl(
        apiClient,
        config,
        board,
        // A board discussion is encrypted like every other body in this app.
        encrypt = { plain -> (noticeDecryptor.encryptToHex(plain) as? ZillitResult.Success)?.data },
        decrypt = { cipher -> (noticeDecryptor.decryptFromHex(cipher) as? ZillitResult.Success)?.data },
        myUserId = { projectContext?.context?.value?.profile?.userId },
    ),
    nowMillis = System::currentTimeMillis,
    board = board,
    permissions = permissions,
    rights = rightsRequests,
    // A record added by another department should not wait for a reopen.
    events = socketEvents,
)

internal fun AppGraph.Ready.castBoardProvider(viewModel: CastingViewModel, path: String) = CastingToolProvider(
    viewModel = viewModel,
    path = path,
    loadPhoto = { media -> castingPhoto(this, media) },
)

/** One candidate's still, as a bitmap — null when it cannot be fetched. */
private suspend fun castingPhoto(ready: AppGraph.Ready, media: CastingMedia): ImageBitmap? {
    val fetched = ready.noticeMedia.fetch(
        NoticeAttachment(
            media = media.media,
            fileName = media.fileName,
            bucket = media.bucket,
            region = media.region,
        ),
        preview = true,
    )
    val bytes = (fetched as? ZillitResult.Success)?.data ?: return null
    return decodeImageBitmap(bytes)
}
