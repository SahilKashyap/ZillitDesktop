package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.tasks.data.TasksRepositoryImpl
import com.zillit.desktop.feature.tasks.domain.AttachmentBytes
import com.zillit.desktop.feature.tasks.domain.PickedTaskFile
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TasksViewer
import com.zillit.desktop.feature.tasks.ui.TaskPeople
import com.zillit.desktop.feature.tasks.ui.TasksToolProvider
import com.zillit.desktop.feature.tasks.ui.TasksViewModel
import com.zillit.desktop.core.localization.localised
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * The Tasks tool's host wiring.
 *
 * The crew comes from the project context the whole app already holds — names,
 * departments and designations are never a hard-coded list — minus anyone the
 * production no longer lists as active. A crew row's `user_id` is the
 * ProjectUser id, the same id the service stores as `assignee_id`.
 */
internal fun AppGraph.Ready.buildTasks(permissions: () -> ProjectPermissions) = TasksViewModel(
    repository = TasksRepositoryImpl(apiClient, config, attachmentBytes = taskAttachmentBytes()),
    viewer = { TasksViewer.from(permissions()) },
    people = tasksPeople(),
    rights = rightsRequests,
    events = socketEvents,
    pickFiles = { imagesOnly -> pickTaskAttachments(imagesOnly) },
)

/**
 * "Add image" and "Attach file".
 *
 * Images go through the picker's own Image kind, so the dialog is filtered to
 * pictures; "Attach file" uses Document, which is open-ended — the module
 * checks the extension itself, and a dialog that hid the wrong files would
 * only hide the message explaining why.
 *
 * [TASK_PICK_MAX_BYTES] is the largest thing worth reading: the module refuses
 * anything over 25 MB before it is shrunk, so a 2 GB file never reaches the
 * heap.
 */
private suspend fun AppGraph.Ready.pickTaskAttachments(imagesOnly: Boolean): List<PickedTaskFile> =
    attachmentPicker
        .pick(
            kind = if (imagesOnly) PreviewKind.Image else PreviewKind.Document,
            maxBytes = TASK_PICK_MAX_BYTES,
        )
        .map { PickedTaskFile(it.name, it.contentType, it.bytes) }

/**
 * The bytes of an attachment, in and out of storage.
 *
 * Deliberately the plain HTTP client: the service's signed link is made for
 * exactly the headers it hands out, and sending the Zillit `moduledata` /
 * `bodyhash` headers or a Bearer alongside makes storage refuse a PUT it
 * would otherwise have taken — the same rule the Drive's multipart uploader
 * works under.
 */
private fun AppGraph.Ready.taskAttachmentBytes(): AttachmentBytes = object : AttachmentBytes {

    override suspend fun put(
        url: String,
        headers: Map<String, String>,
        bytes: ByteArray,
        contentType: String,
    ): Boolean = runCatching {
        httpClient.put(url) {
            headers.forEach { (name, value) -> this.headers.append(name, value) }
            // Only when the service named none: it signs for its own
            // Content-Type, and a second one would not match the signature.
            if (headers.keys.none { it.equals(HttpHeaders.ContentType, ignoreCase = true) }) {
                contentType(ContentType.parse(contentType.ifBlank { OCTET_STREAM }))
            }
            setBody(ByteArrayContent(bytes))
        }.status.isSuccess()
    }.getOrElse { failure ->
        ZillitLog.i(TASKS_TAG) { "attachment bytes refused by storage: ${failure::class.simpleName}" }
        false
    }

    override suspend fun get(url: String): ByteArray? = runCatching {
        val response = httpClient.get(url)
        if (response.status.isSuccess()) response.readRawBytes() else null
    }.getOrElse { failure ->
        ZillitLog.i(TASKS_TAG) { "attachment bytes could not be read: ${failure::class.simpleName}" }
        null
    }
}

/** What the picker will read for a task: past this nothing is attachable anyway. */
private const val TASK_PICK_MAX_BYTES = 25L * 1024 * 1024
private const val OCTET_STREAM = "application/octet-stream"
private const val TASKS_TAG = "TasksWiring"

internal fun tasksProvider(viewModel: TasksViewModel) = TasksToolProvider(viewModel, openUrl = ::openInBrowser)

/** Statuses the service's own member check refuses; the pickers match it. */
private val INACTIVE = setOf("pending", "rejected", "removed", "left")

private fun AppGraph.Ready.tasksPeople(): Flow<TaskPeople> {
    val loader = projectContext ?: return emptyFlow()
    return loader.context.map { context ->
        TaskPeople(
            me = context.profile?.userId,
            projectId = context.project?.projectId,
            crew = context.users
                .filter { it.status?.lowercase() !in INACTIVE }
                .map { user ->
                    TaskPerson(
                        id = user.userId,
                        fullName = user.fullName,
                        designation = user.designation?.localised().orEmpty(),
                        department = user.department?.localised().orEmpty(),
                    )
                }
                .filter { it.fullName.isNotBlank() }
                .sortedBy { it.fullName.lowercase() },
        )
    }
}
