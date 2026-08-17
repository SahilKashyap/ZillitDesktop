package com.zillit.desktop.core.workspace

import com.zillit.desktop.core.common.ZillitLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Persists the open window set to disk, so relaunching restores the exact
 * workspace — tabs, per-window history, geometry and all.
 *
 * The web app cannot do this: a refresh drops every window and switching
 * project closes them all. This is one of the clearer reasons to have a desktop
 * client (plan §3.2).
 *
 * A corrupt or unreadable file is treated as "no session" rather than an error.
 * Losing the restored layout is a minor annoyance; refusing to start because of
 * it is not acceptable.
 */
class FileWorkspaceSessionStore(
    private val file: File = defaultFile(),
) : WorkspaceSessionStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun load(): WorkspaceState? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString(WorkspaceState.serializer(), file.readText()) }
            .onFailure { ZillitLog.w(TAG) { "Discarding unreadable session: ${it.message}" } }
            .getOrNull()
    }

    override suspend fun save(state: WorkspaceState) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            // Write via a temp file and move, so a crash mid-write cannot leave
            // a truncated session that fails to parse on next launch.
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(WorkspaceState.serializer(), state))
            temp.renameTo(file)
        }.onFailure { ZillitLog.w(TAG) { "Could not save session: ${it.message}" } }
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
        Unit
    }

    companion object {
        private const val TAG = "WorkspaceSession"

        fun defaultFile(): File =
            File(System.getProperty("user.home"), ".zillit/workspace-session.json")
    }
}

/** Non-persisting store, for tests and for the MDI-disabled fallback. */
class InMemoryWorkspaceSessionStore : WorkspaceSessionStore {
    private var stored: WorkspaceState? = null
    override suspend fun load(): WorkspaceState? = stored
    override suspend fun save(state: WorkspaceState) { stored = state }
    override suspend fun clear() { stored = null }
}
