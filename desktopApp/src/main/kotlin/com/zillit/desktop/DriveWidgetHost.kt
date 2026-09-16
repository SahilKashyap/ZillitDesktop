package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.drive.data.DriveRepositoryImpl
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import com.zillit.desktop.feature.home.data.ToolsRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The Drive widget's session: one production's drive at a time, scoped to
 * that production without moving the main window onto it.
 *
 * Every call the widget makes carries the chosen production's id **and the
 * user's id on that production** (`CallOptions`), so browsing "Ios Developer
 * Team" in the widget while the main window is on "SG Document Distribution"
 * touches neither the app's header context nor its caches. Rights come from
 * that production's own `project/tools`, asked the same way.
 *
 * Owned by the application, not the window: closing and reopening the widget
 * keeps the listing; only [close] (sign-out) drops it.
 */
internal class DriveWidgetHost(
    private val ready: AppGraph.Ready,
    private val scope: CoroutineScope,
    /** The rights of the OPEN production, already loaded by the shell. */
    private val openPermissions: () -> ProjectPermissions,
    private val openProjectId: () -> String?,
) {

    /** One production's drive, ready or on its way. */
    data class Session(
        val project: Project,
        val viewModel: DriveViewModel? = null,
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private var building: Job? = null

    /** Shows [project]'s drive. A repeat of the current production is a no-op. */
    fun select(project: Project) {
        if (_session.value?.project?.id == project.id && _session.value?.error == null) return
        building?.cancel()
        _session.value = Session(project)
        building = scope.launch { build(project) }
    }

    /** Asks again after a failure — the same production, from the top. */
    fun retry() {
        val project = _session.value?.project ?: return
        _session.value = null
        select(project)
    }

    /** Sign-out, or the widget being put away for good: nothing of the drive is kept. */
    fun close() {
        building?.cancel()
        _session.value = null
    }

    private suspend fun build(project: Project) {
        val isOpen = project.id == openProjectId()
        // The open production rides the ambient headers — the exact calls the
        // Drive tool makes; any other names itself on every call.
        val options = if (isOpen) CallOptions() else CallOptions(projectId = project.id, userId = project.userId)

        val repository = DriveRepositoryImpl(
            apiClient = ready.apiClient,
            config = ready.config,
            // Names are known for the open production's crew only; the widget
            // shows "—" for uploaders on other productions rather than fetch
            // a crew list per switch.
            resolveUserName = { id -> ready.projectContext?.context?.value?.user(id)?.fullName },
            callOptions = { options },
        )

        val permissions = if (isOpen) {
            openPermissions()
        } else {
            val tools = ToolsRepositoryImpl(
                apiClient = ready.apiClient,
                config = ready.config,
                isAdmin = { project.isAdmin },
                callOptions = { options },
            )
            when (val loaded = tools.loadPermissions()) {
                is ZillitResult.Success -> loaded.data
                is ZillitResult.Failure -> {
                    ZillitLog.w(TAG) { "widget: rights for ${project.id} failed: ${loaded.error.technical}" }
                    _session.update { it?.copy(loading = false, error = loaded.error.userMessage) }
                    return
                }
            }
        }

        val context = ready.projectContext?.context?.value
        val viewer = DriveViewer.from(
            permissions = permissions,
            userId = (if (isOpen) context?.profile?.userId else project.userId).orEmpty(),
            displayName = if (isOpen) context?.profile?.fullName.orEmpty() else "",
        )
        val viewModel = DriveViewModel(
            repository = repository,
            viewer = { viewer },
            uploader = MultipartDriveUploader(repository, ready.httpClient),
            previewHost = AppDrivePreviewHost(ready.httpClient),
            // Names and share pickers are the open production's crew; another
            // production's people are not fetched per switch.
            crew = { if (isOpen) context?.users.orEmpty().map { it.toDrivePerson() } else emptyList() },
            newUploadId = { UUID.randomUUID().toString() },
            now = System::currentTimeMillis,
        )
        viewModel.start()
        _session.update { current ->
            if (current?.project?.id == project.id) Session(project, viewModel, loading = false) else current
        }
    }

    private companion object {
        const val TAG = "DriveWidget"
    }
}

/**
 * A crew member as the Drive's share pickers list them. The designation is a
 * label key on the wire (`payroll_label`), so it goes through the dictionary
 * first — the web's `getUserRoleName`.
 */
internal fun com.zillit.desktop.core.database.UserSnapshot.toDrivePerson() =
    com.zillit.desktop.feature.drive.domain.DrivePerson(
        id = userId,
        name = fullName,
        designation = designationText().orEmpty(),
        avatarUrl = avatarUrl,
    )
