package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.selectstills.data.HeadshotFlow
import com.zillit.desktop.feature.selectstills.data.JvmStillsFileReader
import com.zillit.desktop.feature.selectstills.data.StillsRepositoryImpl
import com.zillit.desktop.feature.selectstills.data.StillsUploadQueue
import com.zillit.desktop.feature.selectstills.data.KtorStillsUploader
import com.zillit.desktop.feature.selectstills.domain.StillsPick
import com.zillit.desktop.feature.selectstills.domain.StillsUrlCache
import com.zillit.desktop.feature.selectstills.domain.StillsViewer
import com.zillit.desktop.feature.selectstills.ui.StillsImageSource
import com.zillit.desktop.feature.selectstills.ui.StillsPerson
import com.zillit.desktop.feature.selectstills.ui.StillsToolProvider
import com.zillit.desktop.feature.selectstills.ui.StillsViewModel
import com.zillit.desktop.core.localization.localised as localisedLabel
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The Select Stills tool's host wiring.
 *
 * The crew comes from the project context the whole app already holds — names,
 * departments and designations are never a hard-coded list — minus anyone the
 * production no longer lists as active. A crew row's `user_id` is the
 * ProjectUser id, the same id the service stores as a cast member's
 * `agent_user_id`.
 *
 * The upload queue is built here rather than in the view model: it must outlive
 * every screen, because a card of stills takes a while and the photographer
 * carries on looking at the gallery while it goes.
 */
internal fun AppGraph.Ready.buildSelectStills(permissions: () -> ProjectPermissions): StillsViewModel {
    val repository = StillsRepositoryImpl(apiClient, config)
    val files = JvmStillsFileReader()
    val uploader = KtorStillsUploader(httpClient, files)
    val openProject = { projectContext?.context?.value?.project?.projectId }
    return StillsViewModel(
        repository = repository,
        viewer = { StillsViewer.from(permissions()) },
        people = stillsPeople(),
        openProject = openProject,
        queue = StillsUploadQueue(
            repository = repository,
            uploader = uploader,
            files = files,
            openProject = openProject,
            newId = { java.util.UUID.randomUUID().toString() },
        ),
        headshots = HeadshotFlow(repository, uploader, files),
        files = files,
        pickPhotos = { multiple -> pickStills(multiple) },
        rights = rightsRequests,
        events = socketEvents,
        now = { System.currentTimeMillis() },
        urls = StillsUrlCache { System.currentTimeMillis() },
    )
}

internal fun AppGraph.Ready.selectStillsProvider(viewModel: StillsViewModel) = StillsToolProvider(
    viewModel = viewModel,
    images = stillsImages(),
    onDownload = { url, name -> saveStill(url, name) },
)

/**
 * Fetches a signed image straight from the production's storage.
 *
 * Never the signed Zillit client: that would attach the session token to a
 * request for the storage provider, and the link already carries its own
 * authorisation.
 */
private fun AppGraph.Ready.stillsImages() = StillsImageSource { url ->
    withContext(Dispatchers.IO) {
        runCatching {
            val response = httpClient.get(url)
            if (response.status.isSuccess()) response.readRawBytes() else null
        }.getOrNull()
    }
}

/**
 * "Download original": the signed link answers "save this file", so handing it
 * to the OS browser downloads it where the reader keeps their downloads,
 * exactly as following it in the web does.
 *
 * [name] is the camera's own file name, which the service also sets on the
 * link — logged so a support question about a missing still names a file
 * rather than a request.
 */
private fun saveStill(url: String, name: String) {
    ZillitLog.i(TAG) { "opening an original for download: $name" }
    openInBrowser(url)
}

/** The OS photo chooser, as paths: a card is hundreds of forty-megabyte frames. */
private suspend fun pickStills(multiple: Boolean): List<StillsPick> =
    attachmentPicker.pickPaths(PreviewKind.Image, multiple = multiple)
        .map { picked ->
            StillsPick(
                path = picked.path,
                name = picked.name,
                size = picked.sizeBytes,
                type = picked.contentType,
            )
        }

/** Statuses the service's own member check refuses; the pickers match it. */
private val INACTIVE = setOf("pending", "rejected", "removed", "left")

private fun AppGraph.Ready.stillsPeople(): Flow<List<StillsPerson>> {
    val loader = projectContext ?: return emptyFlow()
    return loader.context.map { context ->
        context.users
            .filter { it.status?.lowercase() !in INACTIVE }
            .map { user ->
                StillsPerson(
                    id = user.userId,
                    fullName = user.fullName,
                    designation = user.designation?.localisedLabel().orEmpty(),
                    department = user.department?.localisedLabel().orEmpty(),
                )
            }
            .filter { it.fullName.isNotBlank() }
            .sortedBy { it.fullName.lowercase() }
    }
}

private const val TAG = "Stills"
