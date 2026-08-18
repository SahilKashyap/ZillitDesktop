package com.zillit.desktop.core.database

/** One script as the database keeps it; the body is the feature's own. */
data class ScreenplaySnapshot(
    val id: String,
    val projectId: String,
    val title: String,
    val body: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** Zillit Draft's scripts, per production. See ScreenplayCache.sq. */
class ScreenplayCache(database: ZillitDatabase) {

    private val queries = database.screenplayCacheQueries

    fun save(script: ScreenplaySnapshot) {
        queries.upsertScreenplay(
            id = script.id,
            projectId = script.projectId,
            title = script.title,
            body = script.body,
            createdAt = script.createdAtMillis,
            updatedAt = script.updatedAtMillis,
        )
    }

    fun list(projectId: String): List<ScreenplaySnapshot> =
        queries.selectScreenplays(projectId).executeAsList().map { it.toSnapshot() }

    fun load(id: String): ScreenplaySnapshot? = queries.selectScreenplay(id).executeAsOneOrNull()?.toSnapshot()

    fun delete(id: String) {
        queries.deleteScreenplay(id)
    }

    private fun Screenplay.toSnapshot() = ScreenplaySnapshot(
        id = id,
        projectId = projectId,
        title = title,
        body = body,
        createdAtMillis = createdAt,
        updatedAtMillis = updatedAt,
    )
}
