package com.zillit.desktop

import com.zillit.desktop.core.database.ProjectListCache
import com.zillit.desktop.core.database.ProjectListSnapshot
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.domain.ProjectListStore

/**
 * The picker's offline list, over the encrypted database.
 *
 * Unread counts are not kept: they are asked for separately and would only
 * be stale by the time they were shown.
 */
internal class CachedProjectList(private val cache: ProjectListCache) : ProjectListStore {

    override fun load(): List<Project> = cache.list().map { row ->
        Project(
            id = row.projectId,
            name = row.name,
            code = row.code,
            type = row.type,
            region = row.region,
            isFavourite = row.isFavourite,
            userId = row.userId,
            parentName = row.parentName,
            subType = row.subType,
            isAdmin = row.isAdmin,
            isPending = row.isPending,
            createdOnMillis = row.createdOnMillis,
        )
    }

    override fun save(projects: List<Project>) = cache.save(
        projects.map { project ->
            ProjectListSnapshot(
                projectId = project.id,
                name = project.name,
                code = project.code,
                type = project.type,
                region = project.region,
                isFavourite = project.isFavourite,
                userId = project.userId,
                parentName = project.parentName,
                subType = project.subType,
                isAdmin = project.isAdmin,
                isPending = project.isPending,
                createdOnMillis = project.createdOnMillis,
            )
        },
    )
}
