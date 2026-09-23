package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.database.ScreenplayCache
import com.zillit.desktop.core.database.ScreenplaySnapshot
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.draft.data.PdfScreenplayRenderer
import com.zillit.desktop.feature.draft.domain.DraftHost
import com.zillit.desktop.feature.draft.domain.DraftStore
import com.zillit.desktop.feature.draft.domain.ImportedFile
import com.zillit.desktop.feature.draft.domain.StoredScript
import com.zillit.desktop.feature.draft.ui.DraftViewModel
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import com.zillit.desktop.feature.drive.ui.PickedFile
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID

/**
 * Zillit Draft's host seams: scripts in the encrypted database, files in
 * and out through the OS dialogs and Downloads, PDFs handed to the Drive
 * tool's own uploader.
 */
internal class DatabaseDraftStore(private val cache: ScreenplayCache) : DraftStore {

    override suspend fun list(projectId: String): List<StoredScript> =
        withContext(Dispatchers.IO) { cache.list(projectId).map { it.toStored() } }

    override suspend fun load(id: String): StoredScript? = withContext(Dispatchers.IO) { cache.load(id)?.toStored() }

    override suspend fun save(script: StoredScript) = withContext(Dispatchers.IO) {
        cache.save(
            ScreenplaySnapshot(
                id = script.id,
                projectId = script.projectId,
                title = script.title,
                body = script.body,
                createdAtMillis = script.createdAtMillis,
                updatedAtMillis = script.updatedAtMillis,
            ),
        )
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) { cache.delete(id) }

    private fun ScreenplaySnapshot.toStored() =
        StoredScript(id, projectId, title, body, createdAtMillis, updatedAtMillis)
}

/**
 * Nothing to save into: the database did not open. Scripts live for the
 * session only, so the tool still works — and says so.
 */
internal class MemoryDraftStore : DraftStore {
    private val scripts = linkedMapOf<String, StoredScript>()
    override suspend fun list(projectId: String) = scripts.values.filter { it.projectId == projectId }
    override suspend fun load(id: String) = scripts[id]
    override suspend fun save(script: StoredScript) {
        scripts[script.id] = script
    }
    override suspend fun delete(id: String) {
        scripts.remove(id)
    }
}

/** Import from the OS picker, export to Downloads, send to Drive through its uploader. */
internal class DesktopDraftHost(private val drive: () -> DriveViewModel?) : DraftHost {

    override suspend fun pickImport(): ImportedFile? = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, str(S.desktop_import_script_dialog), FileDialog.LOAD)
        dialog.isMultipleMode = false
        dialog.setFilenameFilter { _, name ->
            name.lowercase().let { it.endsWith(".fountain") || it.endsWith(".fdx") || it.endsWith(".txt") }
        }
        dialog.isVisible = true
        val file = dialog.files.orEmpty().firstOrNull() ?: return@withContext null
        if (!file.isFile || file.length() > MAX_IMPORT_BYTES) return@withContext null
        ImportedFile(file.name, file.readText())
    }

    override suspend fun export(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
            is ZillitResult.Failure -> saved
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
        }

    /**
     * Through the Drive tool's own upload path — the same queue, progress
     * strip and folder as a file picked in Drive — so the PDF lands where the
     * user last was in the drive, and shows up there.
     */
    override suspend fun sendToDrive(fileName: String, bytes: ByteArray): ZillitResult<Unit> {
        val viewModel = drive() ?: return ZillitResult.Failure(ZillitError.Unknown("Drive is not available"))
        val temp = withContext(Dispatchers.IO) {
            File(System.getProperty("java.io.tmpdir"), "zillit-draft-${UUID.randomUUID()}").apply { mkdirs() }
                .let { File(it, fileName) }
                .also { it.writeBytes(bytes) }
        }
        viewModel.onEvent(
            DriveEvent.AddUploadFiles(
                listOf(
                    PickedFile(
                        path = temp.absolutePath,
                        name = fileName,
                        sizeBytes = bytes.size.toLong(),
                        mimeType = "application/pdf",
                    ),
                ),
            ),
        )
        return ZillitResult.Success(Unit)
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 20L * 1024 * 1024
    }
}

internal fun AppGraph.Ready.buildDraft(drive: () -> DriveViewModel?, projectId: () -> String?) = DraftViewModel(
    store = screenplayCache?.let(::DatabaseDraftStore) ?: MemoryDraftStore(),
    host = DesktopDraftHost(drive),
    renderer = PdfScreenplayRenderer(),
    projectId = projectId,
    newId = { UUID.randomUUID().toString() },
    nowMillis = System::currentTimeMillis,
)
