package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.externalusers.data.ExternalUsersRepositoryImpl
import com.zillit.desktop.feature.externalusers.domain.ExternalUsersViewer
import com.zillit.desktop.feature.externalusers.ui.DepartmentOption
import com.zillit.desktop.feature.externalusers.ui.DesignationOption
import com.zillit.desktop.feature.externalusers.ui.ExternalUsersViewModel

/**
 * External Users: the outside-contact directory (`user/external-user` on the
 * project host). Departments ride the admin repository's existing read — the
 * form's crew pickers need names and job titles, which it already carries.
 */
internal fun AppGraph.Ready.buildExternalUsers(
    permissions: () -> ProjectPermissions,
): ExternalUsersViewModel = ExternalUsersViewModel(
    repository = ExternalUsersRepositoryImpl(apiClient, config),
    rights = rightsRequests,
    // A guest added or removed by another coordinator lands live.
    events = socketEvents,
    // Which production the rows belong to — sampled per open, so a roster
    // fetched under one production is wiped before another's window shows.
    projectId = { projectContext?.context?.value?.project?.projectId },
    resolveViewer = {
        ExternalUsersViewer.from(
            permissions(),
            projectContext?.context?.value?.profile?.userId.orEmpty(),
        )
    },
    loadDepartments = {
        when (val got = adminRepository.departments()) {
            is ZillitResult.Success -> got.data.map { department ->
                DepartmentOption(
                    id = department.id,
                    name = department.name,
                    designations = department.jobTitles.map { DesignationOption(it.id, it.name) },
                )
            }
            is ZillitResult.Failure -> emptyList()
        }
    },
    nowMillis = System::currentTimeMillis,
)
