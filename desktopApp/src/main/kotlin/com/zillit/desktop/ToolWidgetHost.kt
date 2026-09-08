package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.home.data.ToolsRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A tool widget's session: one production at a time, switched from the
 * widget's own picker without moving the main window.
 *
 * The same shape as [DriveWidgetHost], for the widgets that render a
 * [ToolProvider] rather than a screen of their own.
 *
 * ## The open production is not rebuilt
 *
 * Choosing the production the app is already on hands back a provider over
 * the rail's own ViewModel — the same socket subscription, the same unread
 * badges, the same thread cache; only its layout is the compact one. Another
 * production gets a stack of its own from [scopedProvider], every call on it
 * naming that production and the user's id there.
 */
internal class ToolWidgetHost(
    private val ready: AppGraph.Ready,
    private val scope: CoroutineScope,
    private val openProjectId: () -> String?,
    private val tag: String,
    /** The compact provider over the rail's own ViewModel — shared state, not a second copy. */
    private val openProvider: () -> ToolProvider?,
    /** A provider that speaks for another production entirely. */
    private val scopedProvider: suspend (Project, CallOptions, ProjectPermissions) -> ToolProvider?,
) {

    /** One production's tool, ready or on its way. */
    data class Session(
        val project: Project,
        val provider: ToolProvider? = null,
        val loading: Boolean = true,
        val error: String? = null,
        /** False on another production: see the widget's own note about what is reduced. */
        val isOpenProject: Boolean = true,
    )

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private var building: Job? = null

    /** Shows [project]'s tool. A repeat of the current production is a no-op. */
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

    /** Sign-out: nothing of the production is kept. */
    fun close() {
        building?.cancel()
        _session.value = null
    }

    private suspend fun build(project: Project) {
        val isOpen = project.id == openProjectId()
        if (isOpen) {
            publish(project, openProvider(), isOpen = true)
            return
        }

        // Another production names itself on every call — the project id and
        // the user's id ON that production, which is not the same person's id
        // as here.
        val options = CallOptions(projectId = project.id, userId = project.userId)
        val tools = ToolsRepositoryImpl(
            apiClient = ready.apiClient,
            config = ready.config,
            isAdmin = { project.isAdmin },
            callOptions = { options },
        )
        val permissions = when (val loaded = tools.loadPermissions()) {
            is ZillitResult.Success -> loaded.data
            is ZillitResult.Failure -> {
                ZillitLog.w(tag) { "rights for ${project.id} failed: ${loaded.error.technical}" }
                _session.update { it?.copy(loading = false, error = loaded.error.userMessage) }
                return
            }
        }

        val provider = scopedProvider(project, options, permissions)
        if (provider == null) {
            _session.update { it?.copy(loading = false, error = "This project could not be opened here.") }
            return
        }
        publish(project, provider, isOpen = false)
    }

    private fun publish(project: Project, provider: ToolProvider?, isOpen: Boolean) {
        _session.update { current ->
            if (current?.project?.id != project.id) {
                current
            } else {
                Session(project, provider, loading = false, isOpenProject = isOpen)
            }
        }
    }
}
