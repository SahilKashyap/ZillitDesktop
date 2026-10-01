package com.zillit.desktop

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.tasks.data.TasksRepositoryImpl
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.TasksViewer
import com.zillit.desktop.feature.tasks.ui.TaskPeople
import com.zillit.desktop.feature.tasks.ui.TasksToolProvider
import com.zillit.desktop.feature.tasks.ui.TasksViewModel
import com.zillit.desktop.core.localization.localised
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
    repository = TasksRepositoryImpl(apiClient, config),
    viewer = { TasksViewer.from(permissions()) },
    people = tasksPeople(),
    rights = rightsRequests,
    events = socketEvents,
)

internal fun tasksProvider(viewModel: TasksViewModel) = TasksToolProvider(viewModel)

/** Statuses the service's own member check refuses; the pickers match it. */
private val INACTIVE = setOf("pending", "rejected", "removed", "left")

private fun AppGraph.Ready.tasksPeople(): Flow<TaskPeople> {
    val loader = projectContext ?: return emptyFlow()
    return loader.context.map { context ->
        TaskPeople(
            me = context.profile?.userId,
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
