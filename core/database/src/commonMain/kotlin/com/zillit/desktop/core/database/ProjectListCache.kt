package com.zillit.desktop.core.database

/** One card of the picker, as the server last described it. */
data class ProjectListSnapshot(
    val projectId: String,
    val name: String,
    val code: String,
    val type: String?,
    val region: String?,
    val isFavourite: Boolean,
    val userId: String?,
    val parentName: String?,
    val subType: String?,
    val isAdmin: Boolean,
    val isPending: Boolean,
    val createdOnMillis: Long?,
)

/**
 * The device's production list, kept so the picker has something to show
 * when the network does not answer.
 *
 * Device-scoped, not project-scoped: it outlives a project switch (see
 * ProjectListCache.sq) and is dropped on sign-out, when the list stops being
 * this person's.
 */
class ProjectListCache(database: ZillitDatabase, private val nowMillis: () -> Long) {

    private val queries = database.projectListCacheQueries

    /** Replaces the whole list: a production the device was removed from must disappear. */
    fun save(projects: List<ProjectListSnapshot>) {
        queries.transaction {
            queries.deleteProjectList()
            projects.forEachIndexed { index, project ->
                queries.upsertProjectListEntry(
                    projectId = project.projectId,
                    name = project.name,
                    code = project.code,
                    type = project.type,
                    region = project.region,
                    isFavourite = project.isFavourite.toDb(),
                    userId = project.userId,
                    parentName = project.parentName,
                    subType = project.subType,
                    isAdmin = project.isAdmin.toDb(),
                    isPending = project.isPending.toDb(),
                    createdOn = project.createdOnMillis,
                    ordinal = index.toLong(),
                    cachedAt = nowMillis(),
                )
            }
        }
    }

    fun list(): List<ProjectListSnapshot> =
        queries.selectProjectList().executeAsList().map {
            ProjectListSnapshot(
                projectId = it.projectId,
                name = it.name,
                code = it.code,
                type = it.type,
                region = it.region,
                isFavourite = it.isFavourite.toBool(),
                userId = it.userId,
                parentName = it.parentName,
                subType = it.subType,
                isAdmin = it.isAdmin.toBool(),
                isPending = it.isPending.toBool(),
                createdOnMillis = it.createdOn,
            )
        }

    /** Sign-out: the list was this account's, and the next account gets none of it. */
    fun clear() {
        queries.deleteProjectList()
    }
}

private fun Boolean.toDb(): Long = if (this) 1 else 0
private fun Long.toBool(): Boolean = this != 0L
