package com.zillit.desktop.feature.assetreport.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.permissions.rightsRefusalMessage
import com.zillit.desktop.feature.assetreport.domain.AssetAttachment
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetDirectory
import com.zillit.desktop.feature.assetreport.domain.AssetExport
import com.zillit.desktop.feature.assetreport.domain.AssetExportFormat
import com.zillit.desktop.feature.assetreport.domain.AssetFileRules
import com.zillit.desktop.feature.assetreport.domain.AssetFiles
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import com.zillit.desktop.feature.assetreport.domain.AssetRecord
import com.zillit.desktop.feature.assetreport.domain.AssetRepository
import com.zillit.desktop.feature.assetreport.domain.AssetViewer
import com.zillit.desktop.feature.assetreport.domain.PickedAssetFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * The Asset Register — the web's `AssetReportModule`: the eligible PO lines as
 * one table, a per-line record opened in a detail page, and the export.
 *
 * Which call a save makes is the web's rule, kept exactly:
 * - no record yet → ONE POST carrying category, attachments and the note,
 *   whichever Save was pressed;
 * - a record → the header's Save PATCHes category + attachments, the note's
 *   Save PATCHes `/comment` on its own.
 * Picked files upload only when a Save needs them, so discarding costs nothing.
 */
@Suppress("TooManyFunctions") // One function per act the web page performs; splitting them hides the save rules.
class AssetViewModel(
    private val repository: AssetRepository,
    private val export: AssetExport,
    private val resolveViewer: () -> AssetViewer,
    private val directory: AssetDirectory = AssetDirectory.None,
    private val files: AssetFiles = AssetFiles.None,
    /**
     * Where "ask an admin for this right" goes; null leaves the plain refusal.
     * The frame answers it with the admin picker and sends the request as a
     * chat message — the phones' flow, hosted once. See `RightsRequestSurface`.
     */
    private val rights: RightsRequestBus? = null,
) : ZillitViewModel<AssetUiState, AssetEvent, AssetEffect>(AssetUiState()), AssetMediaLoader {

    /** Lines whose record was read (null = none exists yet); re-opening never refetches. */
    private val hydrated = mutableMapOf<String, AssetRecord?>()

    /** Lines whose record is being read right now — claimed before the call, so a re-open cannot double it. */
    private val hydrating = mutableSetOf<String>()

    /** Picked files, by local id, until a Save uploads them or the detail lets them go. */
    private val picks = mutableMapOf<String, PickedAssetFile>()

    /** Uploads that landed before their write failed — a retry reuses them rather than uploading twice. */
    private val uploads = mutableMapOf<String, AssetAttachment>()

    private var nextPick = 0
    private var noteFlash: Job? = null
    private var askedForNote = false

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        refresh()
    }

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per act.
    override fun onEvent(event: AssetEvent) {
        when (event) {
            AssetEvent.Refresh -> refresh()
            AssetEvent.Leave -> sendEffect(AssetEffect.Leave)
            is AssetEvent.Search -> setState { copy(query = event.query) }
            is AssetEvent.FilterCategory -> setState { copy(categoryFilter = event.filter) }
            is AssetEvent.FilterDepartments -> filterDepartments(event.ids)
            is AssetEvent.PickCurrency -> setState { copy(currencyCode = event.code.orEmpty()) }
            is AssetEvent.Export -> runExport(event.format)
            AssetEvent.DismissError -> setState { copy(error = null) }
            is AssetEvent.Open -> open(event.lineItemId)
            AssetEvent.RetryRecord -> retryRecord()
            AssetEvent.RequestClose -> requestClose()
            AssetEvent.KeepEditing -> updateDetail { copy(confirmLeave = false) }
            AssetEvent.DiscardAndClose -> close()
            AssetEvent.SaveAndClose -> saveAndClose()
            is AssetEvent.PickCategory -> editDetail {
                // Clicking the chosen card clears it — the web's toggle.
                copy(categoryDraft = if (categoryDraft == event.category) AssetCategory.None else event.category)
            }
            is AssetEvent.NoteChanged -> changeNote(event.text)
            AssetEvent.SaveDetails -> save(Write.Details)
            AssetEvent.SaveNote -> save(Write.Note)
            AssetEvent.AddFiles -> pickFiles()
            is AssetEvent.DropFiles -> if (mayChangeFiles()) accept(event.files, event.tooLarge)
            is AssetEvent.RemoveFile -> removeFile(event.key)
            is AssetEvent.ViewFile -> updateDetail { copy(viewing = event.key) }
            AssetEvent.CloseViewer -> updateDetail { copy(viewing = null) }
            AssetEvent.DownloadViewed -> downloadViewed()
        }
    }

    /** A pick from memory, a stored file from the bucket — what thumbnails and the viewer draw. */
    override suspend fun load(file: DraftFile): ZillitResult<ByteArray> = when (file) {
        is DraftFile.Pending -> picks[file.localId]?.let { ZillitResult.Success(it.bytes) }
            ?: ZillitResult.Failure(ZillitError.Storage(userMessage = "${file.name} is no longer available."))
        is DraftFile.Saved -> if (file.attachment.isComplete) {
            files.read(file.attachment)
        } else {
            ZillitResult.Failure(ZillitError.Storage(userMessage = "${file.name} has no stored copy."))
        }
    }

    // -- the register --------------------------------------------------------------

    private fun refresh() {
        setState { copy(isLoading = true) }
        // A reload forgets what it read, except the record on screen: its drafts are built on it.
        val open = currentState.detail?.line?.lineItemId
        hydrated.keys.retainAll { it == open }
        launchResult(
            block = { repository.lines() },
            onSuccess = { rows ->
                setState { copy(lines = rows, isLoading = false, detail = detail?.refreshedFrom(rows)) }
            },
            onError = { error -> setState { copy(isLoading = false, error = error.localised()) } },
        )
        // The web lists without vendors rather than failing the page when they don't load.
        launchResult(block = { repository.vendors() }, onSuccess = { names -> setState { copy(vendors = names) } })
        launch {
            val departments = quietly(emptyList()) { directory.departments() }
            setState { copy(departments = departments, departmentsLoading = false) }
        }
        launch {
            val currencies = quietly(null) { directory.currencies() } ?: return@launch
            setState { copy(currencies = currencies) }
        }
        launch {
            val people = quietly(emptyList()) { directory.people() }
            setState { copy(people = people.associateBy { it.id }) }
        }
    }

    private fun filterDepartments(ids: List<String>) {
        // Only the privileged pick departments; everyone else is scoped server-side.
        if (!currentState.viewer.privileged) return
        setState { copy(departmentFilter = ids.distinct()) }
    }

    private fun runExport(format: AssetExportFormat) {
        if (currentState.exporting != null || currentState.isLoading) return
        // Privileged exports carry the departments on screen (none = the whole
        // production); a view-only export sends none and the server scopes it.
        val departmentIds = if (currentState.viewer.privileged) currentState.departmentFilter else emptyList()
        setState { copy(exporting = format) }
        launchResult(
            block = { export.export(format, departmentIds) },
            onSuccess = {
                setState { copy(exporting = null) }
                sendEffect(AssetEffect.Notice("Export saved to Downloads.", success = true))
            },
            onError = { error -> setState { copy(exporting = null, error = error.localised()) } },
        )
    }

    // -- opening and closing an asset ----------------------------------------------------

    private fun open(lineItemId: String) {
        val line = currentState.lines.firstOrNull { it.lineItemId == lineItemId } ?: return
        if (currentState.detail?.line?.lineItemId == lineItemId) return
        askedForNote = false
        if (lineItemId in hydrated) {
            setState { copy(detail = seeded(line, hydrated[lineItemId])) }
            return
        }
        setState { copy(detail = AssetDetail(line = line, isHydrating = true)) }
        // Re-opened before its first read landed: that read will seed it.
        if (lineItemId !in hydrating) hydrate(line)
    }

    private fun retryRecord() {
        val detail = currentState.detail?.takeIf { it.hydrationFailed } ?: return
        updateDetail { copy(isHydrating = true, hydrationFailed = false) }
        hydrate(detail.line)
    }

    private fun hydrate(line: AssetLine) {
        val id = line.lineItemId
        hydrating += id
        launchResult(
            block = { repository.record(line).also { hydrating -= id } },
            onSuccess = { record ->
                hydrated[id] = record
                // Patched even if the detail closed meanwhile: keyed by line,
                // landing late is still right, and the table's pill follows it.
                setState {
                    val patched = lines.map { row -> if (row.lineItemId == id) row.withRecord(record) else row }
                    val shown = detail?.takeIf { it.line.lineItemId == id }
                    copy(lines = patched, detail = shown?.let { seeded(it.line.withRecord(record), record) } ?: detail)
                }
            },
            onError = { error ->
                setState {
                    val shown = detail?.takeIf { it.line.lineItemId == id }
                    copy(
                        detail = shown?.copy(isHydrating = false, hydrationFailed = true) ?: detail,
                        error = error.localised(),
                    )
                }
            },
        )
    }

    private fun requestClose() {
        val detail = currentState.detail ?: return
        if (detail.anyDirty) updateDetail { copy(confirmLeave = true) } else close()
    }

    private fun close() {
        val detail = currentState.detail ?: return
        detail.files.filterIsInstance<DraftFile.Pending>().forEach { forget(it.localId) }
        noteFlash?.cancel()
        setState { copy(detail = null) }
    }

    // -- editing -----------------------------------------------------------------------

    private fun changeNote(text: String) {
        val detail = currentState.detail ?: return
        if (detail.isLocked) return
        if (!currentState.viewer.mayEdit) {
            // Asked once a visit: re-asking on every keystroke would bury the reader in prompts.
            if (!askedForNote) {
                askedForNote = true
                refuseAndAsk()
            }
            return
        }
        updateDetail { copy(noteDraft = text) }
    }

    private fun pickFiles() {
        if (!mayChangeFiles()) return
        launch {
            val tooLarge = mutableListOf<Pair<String, Long>>()
            val picked = files.pick { name, size -> tooLarge += name to size }
            accept(picked, tooLarge)
        }
    }

    private fun accept(picked: List<PickedAssetFile>, tooLarge: List<Pair<String, Long>>) {
        val id = currentState.detail?.takeUnless { it.isLocked }?.line?.lineItemId ?: return
        val refusals = tooLarge.mapNotNull { (name, size) -> AssetFileRules.refusal(name, size) }.toMutableList()
        val admitted = picked.mapNotNull { file ->
            AssetFileRules.refusal(file.name, file.sizeBytes)?.let { refusal ->
                refusals += refusal
                return@mapNotNull null
            }
            val localId = "pick-${++nextPick}"
            picks[localId] = file
            DraftFile.Pending(localId = localId, name = file.name, sizeBytes = file.sizeBytes)
        }
        setState {
            copy(
                detail = if (admitted.isEmpty()) detail else detail?.takeIf { it.line.lineItemId == id }
                    ?.let { it.copy(files = it.files + admitted) } ?: detail,
                error = refusals.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: error,
            )
        }
    }

    private fun removeFile(key: String) {
        if (!mayChangeFiles()) return
        val removed = currentState.detail?.files?.firstOrNull { it.key == key } ?: return
        (removed as? DraftFile.Pending)?.let { forget(it.localId) }
        updateDetail { copy(files = files.filterNot { it.key == key }, viewing = viewing?.takeIf { it != key }) }
    }

    private fun downloadViewed() {
        val file = currentState.detail?.viewingFile ?: return
        launch {
            val saved = when (val bytes = load(file)) {
                is ZillitResult.Failure -> bytes
                is ZillitResult.Success -> files.saveCopy(file.name, bytes.data)
            }
            when (saved) {
                is ZillitResult.Success ->
                    sendEffect(AssetEffect.Notice("${file.name} saved to Downloads.", success = true))
                is ZillitResult.Failure -> setState { copy(error = saved.error.localised()) }
            }
        }
    }

    // -- saving --------------------------------------------------------------------------

    private enum class Write { Details, Note }

    private fun save(target: Write) {
        val detail = currentState.detail ?: return
        if (detail.isLocked) return
        if (!currentState.viewer.mayEdit) {
            refuseAndAsk()
            return
        }
        val pending = if (target == Write.Details) detail.metaDirty else detail.noteDirty
        if (!pending) return
        launch {
            if (persist(detail, target) && target == Write.Note) flashNoteSaved()
        }
    }

    /** Save & leave: one POST when new, else only the halves that changed, then out. */
    private fun saveAndClose() {
        val detail = currentState.detail ?: return
        if (detail.isLocked) return
        if (!currentState.viewer.mayEdit) {
            refuseAndAsk()
            return
        }
        launch {
            var ok = true
            if (detail.isNew) {
                ok = persist(detail, Write.Details)
            } else {
                if (detail.metaDirty) ok = persist(detail, Write.Details)
                val afterDetails = currentState.detail?.takeIf { it.line.lineItemId == detail.line.lineItemId }
                if (ok && afterDetails != null && afterDetails.noteDirty) ok = persist(afterDetails, Write.Note)
            }
            if (ok) close()
        }
    }

    /** One write, reported through state; true when the server kept it. */
    private suspend fun persist(sent: AssetDetail, target: Write): Boolean {
        val id = sent.line.lineItemId
        setState { copy(detail = detail?.takeIf { it.line.lineItemId == id }?.copy(isSaving = true) ?: detail) }
        val recordId = sent.record?.id?.takeIf { it.isNotBlank() }
        val result = when {
            recordId == null -> withUploads(sent) { stored ->
                repository.create(sent.line, sent.categoryDraft, sent.noteDraft, stored)
            }
            target == Write.Details -> withUploads(sent) { stored ->
                repository.update(recordId, sent.categoryDraft, stored)
            }
            else -> repository.updateComment(recordId, sent.noteDraft)
        }
        return when (result) {
            is ZillitResult.Failure -> {
                setState {
                    copy(
                        detail = detail?.takeIf { it.line.lineItemId == id }?.copy(isSaving = false) ?: detail,
                        error = result.error.localised(),
                    )
                }
                false
            }
            is ZillitResult.Success -> {
                // A create carried both halves, so both are now the server's.
                val created = recordId == null
                applySaved(
                    sent = sent,
                    saved = result.data,
                    wroteDetails = created || target == Write.Details,
                    wroteNote = created || target == Write.Note,
                )
                true
            }
        }
    }

    /**
     * Re-seeds only what the write wrote. The web re-seeds every draft from any
     * echo, so saving the note there throws away an unsaved category; here the
     * other half keeps its edits.
     */
    private fun applySaved(sent: AssetDetail, saved: AssetRecord, wroteDetails: Boolean, wroteNote: Boolean) {
        val id = sent.line.lineItemId
        hydrated[id] = saved
        if (wroteDetails) sent.files.filterIsInstance<DraftFile.Pending>().forEach { forget(it.localId) }
        setState {
            val patched = lines.map { row -> if (row.lineItemId == id) row.withRecord(saved) else row }
            val shown = detail?.takeIf { it.line.lineItemId == id }
            copy(
                lines = patched,
                detail = shown?.copy(
                    line = shown.line.withRecord(saved),
                    record = saved,
                    isSaving = false,
                    categoryDraft = if (wroteDetails) saved.category else shown.categoryDraft,
                    files = if (wroteDetails) saved.attachments.map { DraftFile.Saved(it) } else shown.files,
                    noteDraft = if (wroteNote) saved.comments else shown.noteDraft,
                ) ?: detail,
            )
        }
    }

    /** Uploads the pending picks, in order, then runs [write] with the whole list. */
    private suspend fun withUploads(
        sent: AssetDetail,
        write: suspend (List<AssetAttachment>) -> ZillitResult<AssetRecord>,
    ): ZillitResult<AssetRecord> {
        val stored = mutableListOf<AssetAttachment>()
        for (file in sent.files) {
            when (file) {
                is DraftFile.Saved -> stored += file.attachment
                is DraftFile.Pending -> when (val uploaded = upload(file)) {
                    is ZillitResult.Failure -> return uploaded
                    is ZillitResult.Success -> stored += uploaded.data
                }
            }
        }
        return write(stored)
    }

    private suspend fun upload(file: DraftFile.Pending): ZillitResult<AssetAttachment> {
        uploads[file.localId]?.let { return ZillitResult.Success(it) }
        val picked = picks[file.localId] ?: return ZillitResult.Failure(
            ZillitError.Validation("${file.name} is no longer available — add it again."),
        )
        return when (val uploaded = files.upload(picked)) {
            is ZillitResult.Failure -> ZillitResult.Failure(
                (uploaded.error as? ZillitError.Storage) ?: ZillitError.Validation("Upload failed for ${file.name}."),
            )
            // A model with no key, bucket or region points at no file; never persist it.
            is ZillitResult.Success -> if (uploaded.data.isComplete) {
                uploads[file.localId] = uploaded.data
                uploaded
            } else {
                ZillitResult.Failure(ZillitError.Validation("Upload failed for ${file.name}."))
            }
        }
    }

    private fun flashNoteSaved() {
        noteFlash?.cancel()
        updateDetail { copy(noteJustSaved = true) }
        noteFlash = launch {
            delay(NOTE_SAVED_FLASH_MILLIS)
            updateDetail { copy(noteJustSaved = false) }
        }
    }

    // -- helpers -------------------------------------------------------------------------

    private fun seeded(line: AssetLine, record: AssetRecord?) = AssetDetail(
        line = line,
        isHydrating = false,
        record = record,
        categoryDraft = record?.category ?: AssetCategory.None,
        noteDraft = record?.comments.orEmpty(),
        files = record?.attachments.orEmpty().map { DraftFile.Saved(it) },
    )

    /** The open detail keeps its record's view of the line; the rest of the row comes fresh. */
    private fun AssetDetail.refreshedFrom(rows: List<AssetLine>): AssetDetail {
        val fresh = rows.firstOrNull { it.lineItemId == line.lineItemId } ?: return this
        return copy(line = fresh.copy(assetId = line.assetId, category = line.category))
    }

    private fun AssetLine.withRecord(record: AssetRecord?) = copy(
        assetId = record?.id?.takeIf { it.isNotBlank() } ?: assetId.takeIf { record != null },
        category = record?.category ?: AssetCategory.None,
    )

    private fun updateDetail(change: AssetDetail.() -> AssetDetail) {
        setState { copy(detail = detail?.change()) }
    }

    /** An edit to the drafts: refused while locked, and asked for without the right. */
    private fun editDetail(change: AssetDetail.() -> AssetDetail) {
        val detail = currentState.detail ?: return
        if (detail.isLocked) return
        if (!currentState.viewer.mayEdit) {
            refuseAndAsk()
            return
        }
        updateDetail(change)
    }

    private fun mayChangeFiles(): Boolean {
        val detail = currentState.detail ?: return false
        if (detail.isLocked) return false
        if (!currentState.viewer.mayEdit) {
            refuseAndAsk()
            return false
        }
        return true
    }

    private fun forget(localId: String) {
        picks.remove(localId)
        uploads.remove(localId)
    }

    /**
     * Refuses, and offers the way forward the phones offer on every refusal.
     * The frame answers the request with its admin picker; without one wired
     * the tool simply says what is missing.
     */
    private fun refuseAndAsk() {
        rights?.ask(MODULE_LABEL, RightsKind.Post)
        sendEffect(AssetEffect.Notice(rightsRefusalMessage(MODULE_LABEL, RightsKind.Post, asked = rights != null)))
    }

    /** A host lookup that must not take the page down with it: logged, and the page carries on without. */
    private suspend fun <T> quietly(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        ZillitLog.w(TAG) { "directory lookup failed: ${failure::class.simpleName}" }
        fallback
    }

    private companion object {
        const val TAG = "AssetRegister"
        const val MODULE_LABEL = "Asset Register"

        /** How long the note's Save reads "Saved" — the web's 1.4 s. */
        const val NOTE_SAVED_FLASH_MILLIS = 1_400L
    }
}
