package com.zillit.desktop.feature.draft.domain

import com.zillit.desktop.core.common.ZillitResult

/** A script as the store keeps it: metadata in columns, the body opaque. */
data class StoredScript(
    val id: String,
    val projectId: String,
    val title: String,
    /** The screenplay body — see `ScreenplayCodec`; the store does not read it. */
    val body: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * Where scripts live. Local: there is no server for a screenplay's text —
 * the host keeps them in the encrypted database, per production. A future
 * sync would implement this same seam.
 */
interface DraftStore {
    suspend fun list(projectId: String): List<StoredScript>
    suspend fun load(id: String): StoredScript?
    suspend fun save(script: StoredScript)
    suspend fun delete(id: String)
}

/** A file the user picked to import. */
data class ImportedFile(val name: String, val text: String)

/**
 * What the host does with files: pick one to import, put one where the
 * user can find it, hand one to the production's Drive.
 */
interface DraftHost {
    suspend fun pickImport(): ImportedFile?
    suspend fun export(fileName: String, bytes: ByteArray): ZillitResult<Unit>
    suspend fun sendToDrive(fileName: String, bytes: ByteArray): ZillitResult<Unit>
}

/** Renders a script to a PDF — Courier 12 on US Letter. JVM implements it. */
fun interface ScreenplayRenderer {
    fun pdf(screenplay: Screenplay): ByteArray
}
