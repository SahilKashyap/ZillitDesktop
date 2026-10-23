package com.zillit.desktop

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.scriptsupervision.data.ScriptSupervisionApi
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptSupervisionViewer
import com.zillit.desktop.feature.scriptsupervision.ui.ScriptSupervisionToolProvider
import com.zillit.desktop.feature.scriptsupervision.ui.ScriptSupervisionViewModel

/**
 * Continuity Script Supervision's host wiring.
 *
 * The service has no login of its own: the project Bearer rides every call,
 * and the production it is scoped to is the one the app has open — which the
 * service also checks against the token (it answers 403
 * `script_supervision_project_mismatch` when the id in the path is another
 * production's).
 *
 * The viewer is a lambda, not a value: view models are built once per graph,
 * before any production is open, so the rights row has to be re-read on every
 * start — see [ScriptSupervisionViewModel].
 */
internal fun AppGraph.Ready.buildScriptSupervision(
    permissions: () -> ProjectPermissions,
): ScriptSupervisionViewModel {
    val projectId = { projectContext?.context?.value?.project?.projectId }
    return ScriptSupervisionViewModel(
        api = ScriptSupervisionApi(apiClient, config, projectId),
        viewer = { ScriptSupervisionViewer.from(permissions()) },
        rights = rightsRequests,
        projectId = projectId,
    )
}

/** The tool's window. */
internal fun scriptSupervisionProvider(viewModel: ScriptSupervisionViewModel): ScriptSupervisionToolProvider =
    ScriptSupervisionToolProvider(viewModel)
