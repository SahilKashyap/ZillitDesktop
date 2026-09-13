package com.zillit.desktop.feature.assetreport

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.RightsRequest
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.feature.assetreport.domain.AssetAttachment
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetCurrencies
import com.zillit.desktop.feature.assetreport.domain.AssetCurrency
import com.zillit.desktop.feature.assetreport.domain.AssetDepartment
import com.zillit.desktop.feature.assetreport.domain.AssetDirectory
import com.zillit.desktop.feature.assetreport.domain.AssetExport
import com.zillit.desktop.feature.assetreport.domain.AssetExportFormat
import com.zillit.desktop.feature.assetreport.domain.AssetFileRules
import com.zillit.desktop.feature.assetreport.domain.AssetFiles
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import com.zillit.desktop.feature.assetreport.domain.AssetPerson
import com.zillit.desktop.feature.assetreport.domain.AssetRecord
import com.zillit.desktop.feature.assetreport.domain.AssetRepository
import com.zillit.desktop.feature.assetreport.domain.AssetViewer
import com.zillit.desktop.feature.assetreport.domain.PickedAssetFile
import com.zillit.desktop.feature.assetreport.domain.extensionOf
import com.zillit.desktop.feature.assetreport.ui.AssetEffect
import com.zillit.desktop.feature.assetreport.ui.AssetEvent
import com.zillit.desktop.feature.assetreport.ui.AssetViewModel
import com.zillit.desktop.feature.assetreport.ui.CategoryFilter
import com.zillit.desktop.feature.assetreport.ui.DraftFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The register driven through its view model — the web's `AssetReportModule`
 * from the table to a saved asset. What matters is which call each Save makes
 * and what it carries, because that is where the server silently drops data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetRegisterFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val dolly = AssetLine(
        lineItemId = "l1",
        description = "Camera dolly",
        quantity = 2.0,
        unitPrice = 100.0,
        total = 200.0,
        account = "4200",
        vendorId = "v1",
        departmentId = "d1",
        currency = "GBP",
        poNumber = "PO-7",
        poId = "p1",
    )
    private val lens = dolly.copy(
        lineItemId = "l2",
        description = "Lens set",
        vendorId = "v2",
        departmentId = "d2",
        currency = "USD",
        total = 125.0,
        poNumber = "PO-9",
        assetId = "a2",
        category = AssetCategory.Sell,
    )
    private val tax = dolly.copy(lineItemId = "l3", description = "VAT", total = 40.0, isTax = true)

    private val photo get() = PickedAssetFile("shelf.jpg", ByteArray(64) { 1 })

    private val repository = FakeRepository(listOf(dolly, lens, tax))
    private val files = FakeFiles()
    private val exports = mutableListOf<Pair<AssetExportFormat, List<String>>>()
    private val bus = RightsRequestBus()

    private val poster = AssetViewer(canView = true, canPost = true, ready = true)

    private fun TestScope.model(viewer: AssetViewer = poster): AssetViewModel {
        val model = AssetViewModel(
            repository = repository,
            export = AssetExport { format, departments ->
                exports += format to departments
                ZillitResult.Success(Unit)
            },
            resolveViewer = { viewer },
            directory = Directory,
            files = files,
            rights = bus,
        )
        model.start()
        advanceUntilIdle()
        return model
    }

    // -- opening ---------------------------------------------------------------------------

    @Test
    fun `an asset stays locked until its record lands`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        repository.recordGate = gate
        repository.records["l2"] = AssetRecord(
            id = "a2",
            lineItemId = "l2",
            category = AssetCategory.Sell,
            comments = "Back to Panavision",
            commentBy = "u7",
            attachments = listOf(storedPhoto),
        )
        val model = model()

        model.onEvent(AssetEvent.Open("l2"))
        runCurrent()
        val opening = assertNotNull(model.currentState.detail)
        assertTrue(opening.isHydrating)
        model.onEvent(AssetEvent.PickCategory(AssetCategory.Keep))
        model.onEvent(AssetEvent.NoteChanged("typed over a note nobody has read yet"))
        assertEquals(AssetCategory.Sell, model.currentState.detail?.categoryDraft, "no edit before the record")
        assertEquals("", model.currentState.detail?.noteDraft)

        gate.complete(Unit)
        advanceUntilIdle()

        val open = assertNotNull(model.currentState.detail)
        assertFalse(open.isHydrating)
        assertEquals("Back to Panavision", open.noteDraft)
        assertEquals(listOf("saved:${storedPhoto.media}"), open.files.map { it.key })
        assertFalse(open.anyDirty)
        assertEquals(1, repository.recordReads)

        // Re-opening a line already read never reads it again.
        model.onEvent(AssetEvent.RequestClose)
        model.onEvent(AssetEvent.Open("l2"))
        advanceUntilIdle()
        assertEquals(1, repository.recordReads)
    }

    @Test
    fun `a failed record read keeps the editors shut until a retry lands`() = runTest(dispatcher) {
        repository.failRecord = true
        val model = model()

        model.onEvent(AssetEvent.Open("l1"))
        advanceUntilIdle()
        val failed = assertNotNull(model.currentState.detail)
        assertTrue(failed.hydrationFailed)
        assertTrue(failed.isLocked)
        assertNotNull(model.currentState.error)

        repository.failRecord = false
        model.onEvent(AssetEvent.RetryRecord)
        advanceUntilIdle()
        assertFalse(assertNotNull(model.currentState.detail).isLocked)
    }

    // -- saving --------------------------------------------------------------------------

    @Test
    fun `the first save is one POST carrying the category, the note and the files`() = runTest(dispatcher) {
        val model = model()
        model.onEvent(AssetEvent.Open("l1"))
        advanceUntilIdle()

        model.onEvent(AssetEvent.PickCategory(AssetCategory.Keep))
        model.onEvent(AssetEvent.NoteChanged("In store B"))
        model.onEvent(AssetEvent.DropFiles(listOf(photo)))
        assertEquals(1, model.currentState.detail?.pendingCount)
        assertTrue(files.uploaded.isEmpty(), "picks wait for Save")

        // The note's own Save on a new row still has to create the whole row.
        model.onEvent(AssetEvent.SaveNote)
        runCurrent()

        val create = repository.writes.single()
        assertEquals("create", create.route)
        assertEquals(AssetCategory.Keep, create.category)
        assertEquals("In store B", create.comments)
        assertEquals(listOf("asset-register/shelf.jpg"), create.attachments.map { it.media })
        assertEquals(listOf("shelf.jpg"), files.uploaded)

        val saved = assertNotNull(model.currentState.detail)
        assertFalse(saved.anyDirty, "every draft is now the server's")
        assertTrue(saved.noteJustSaved)
        assertEquals("a-new", model.currentState.lines.first { it.lineItemId == "l1" }.assetId)
        assertEquals(AssetCategory.Keep, model.currentState.lines.first { it.lineItemId == "l1" }.category)

        advanceTimeBy(1_500)
        runCurrent()
        assertFalse(assertNotNull(model.currentState.detail).noteJustSaved)
    }

    @Test
    fun `a saved record writes each half on its own route`() = runTest(dispatcher) {
        repository.records["l2"] =
            AssetRecord(id = "a2", lineItemId = "l2", category = AssetCategory.Sell, comments = "Old")
        val model = model()
        model.onEvent(AssetEvent.Open("l2"))
        advanceUntilIdle()

        model.onEvent(AssetEvent.PickCategory(AssetCategory.Keep))
        model.onEvent(AssetEvent.DropFiles(listOf(photo)))
        model.onEvent(AssetEvent.SaveDetails)
        advanceUntilIdle()
        val update = repository.writes.single()
        assertEquals("update", update.route)
        assertEquals(AssetCategory.Keep, update.category)
        assertEquals(1, update.attachments.size)

        // A category picked and not saved survives the note's save.
        model.onEvent(AssetEvent.PickCategory(AssetCategory.Sell))
        model.onEvent(AssetEvent.NoteChanged("New note"))
        model.onEvent(AssetEvent.SaveNote)
        advanceUntilIdle()
        assertEquals(listOf("update", "comment"), repository.writes.map { it.route })
        assertEquals("New note", repository.writes.last().comments)
        val after = assertNotNull(model.currentState.detail)
        assertEquals(AssetCategory.Sell, after.categoryDraft)
        assertTrue(after.metaDirty)
        assertFalse(after.noteDirty)
    }

    @Test
    fun `clicking the chosen category clears it`() = runTest(dispatcher) {
        val model = model()
        model.onEvent(AssetEvent.Open("l1"))
        advanceUntilIdle()
        model.onEvent(AssetEvent.PickCategory(AssetCategory.Keep))
        model.onEvent(AssetEvent.PickCategory(AssetCategory.Keep))
        assertEquals(AssetCategory.None, model.currentState.detail?.categoryDraft)
    }

    @Test
    fun `a failed write keeps the drafts and never uploads a file twice`() = runTest(dispatcher) {
        repository.failWrites = 1
        val model = model()
        model.onEvent(AssetEvent.Open("l1"))
        advanceUntilIdle()
        model.onEvent(AssetEvent.DropFiles(listOf(photo)))

        model.onEvent(AssetEvent.SaveDetails)
        advanceUntilIdle()
        assertNotNull(model.currentState.error)
        assertEquals(1, model.currentState.detail?.pendingCount, "the pick is still there to retry")

        model.onEvent(AssetEvent.SaveDetails)
        advanceUntilIdle()
        assertEquals(listOf("shelf.jpg"), files.uploaded, "the retry reuses the stored copy")
        assertEquals(0, model.currentState.detail?.pendingCount)
    }

    @Test
    fun `a half upload is never saved`() = runTest(dispatcher) {
        files.halfUpload = true
        val model = model()
        model.onEvent(AssetEvent.Open("l1"))
        advanceUntilIdle()
        model.onEvent(AssetEvent.DropFiles(listOf(photo)))

        model.onEvent(AssetEvent.SaveDetails)
        advanceUntilIdle()

        assertEquals("Upload failed for shelf.jpg.", model.currentState.error)
        assertTrue(repository.writes.isEmpty())
    }

    @Test
    fun `files the web refuses are named and never added`() = runTest(dispatcher) {
        val model = model()
        model.onEvent(AssetEvent.Open("l1"))
        advanceUntilIdle()

        model.onEvent(
            AssetEvent.DropFiles(
                files = listOf(PickedAssetFile("notes.docx", ByteArray(8)), photo),
                tooLarge = listOf("scan.pdf" to AssetFileRules.MAX_BYTES + 1),
            ),
        )

        assertEquals(listOf("shelf.jpg"), model.currentState.detail?.files?.map { it.name })
        val error = assertNotNull(model.currentState.error)
        assertTrue(error.contains("scan.pdf: exceeds the 10MB per-file limit."))
        assertTrue(error.contains("notes.docx: unsupported file type."))
    }

    @Test
    fun `removing a pick forgets it, and the picker adds through the same rules`() = runTest(dispatcher) {
        val model = model()
        model.onEvent(AssetEvent.Open("l1"))
        advanceUntilIdle()
        files.nextPick = listOf(photo)
        model.onEvent(AssetEvent.AddFiles)
        advanceUntilIdle()
        val pick = assertNotNull(model.currentState.detail?.files?.single())
        assertTrue(pick is DraftFile.Pending)
        assertTrue(model.load(pick) is ZillitResult.Success)

        model.onEvent(AssetEvent.RemoveFile(pick.key))
        assertTrue(model.currentState.detail?.files.orEmpty().isEmpty())
        assertTrue(model.load(pick) is ZillitResult.Failure, "its bytes are let go")
    }

    // -- leaving ---------------------------------------------------------------------------

    @Test
    fun `leaving with unsaved changes asks, and Save & leave saves only what changed`() = runTest(dispatcher) {
        repository.records["l2"] = AssetRecord(id = "a2", lineItemId = "l2", category = AssetCategory.Sell)
        val model = model()
        model.onEvent(AssetEvent.Open("l2"))
        advanceUntilIdle()
        model.onEvent(AssetEvent.NoteChanged("Collected by the rental house"))

        model.onEvent(AssetEvent.RequestClose)
        assertTrue(assertNotNull(model.currentState.detail).confirmLeave)
        model.onEvent(AssetEvent.KeepEditing)
        assertFalse(assertNotNull(model.currentState.detail).confirmLeave)

        model.onEvent(AssetEvent.RequestClose)
        model.onEvent(AssetEvent.SaveAndClose)
        advanceUntilIdle()

        assertEquals(listOf("comment"), repository.writes.map { it.route }, "the untouched category is not re-sent")
        assertNull(model.currentState.detail)
    }

    @Test
    fun `discard throws the drafts away`() = runTest(dispatcher) {
        val model = model()
        model.onEvent(AssetEvent.Open("l1"))
        advanceUntilIdle()
        model.onEvent(AssetEvent.DropFiles(listOf(photo)))
        model.onEvent(AssetEvent.RequestClose)
        model.onEvent(AssetEvent.DiscardAndClose)
        assertNull(model.currentState.detail)

        model.onEvent(AssetEvent.Open("l1"))
        advanceUntilIdle()
        assertTrue(assertNotNull(model.currentState.detail).files.isEmpty())
        assertTrue(repository.writes.isEmpty())
    }

    @Test
    fun `a clean asset closes without asking`() = runTest(dispatcher) {
        val model = model()
        model.onEvent(AssetEvent.Open("l1"))
        advanceUntilIdle()
        model.onEvent(AssetEvent.RequestClose)
        assertNull(model.currentState.detail)
    }

    // -- rights ------------------------------------------------------------------------------

    @Test
    fun `without posting rights every edit asks an administrator, the note only once`() = runTest(dispatcher) {
        val asked = mutableListOf<RightsRequest>()
        val notices = mutableListOf<AssetEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bus.requests.collect { asked += it } }
        val model = model(viewer = AssetViewer(canView = true, canPost = false, ready = true))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.collect { notices += it } }
        model.onEvent(AssetEvent.Open("l1"))
        advanceUntilIdle()

        model.onEvent(AssetEvent.PickCategory(AssetCategory.Keep))
        model.onEvent(AssetEvent.NoteChanged("a"))
        model.onEvent(AssetEvent.NoteChanged("ab"))
        model.onEvent(AssetEvent.DropFiles(listOf(photo)))
        advanceUntilIdle()

        val detail = assertNotNull(model.currentState.detail)
        assertEquals(AssetCategory.None, detail.categoryDraft)
        assertEquals("", detail.noteDraft)
        assertTrue(detail.files.isEmpty())
        assertEquals(3, asked.size, "category, the note once, and the files")
        assertTrue(notices.all { it is AssetEffect.Notice && !it.success })
    }

    // -- the table and the export --------------------------------------------------------------

    @Test
    fun `filters, search and the total follow the web`() = runTest(dispatcher) {
        val model = model(viewer = poster.copy(isAccountant = true))
        val state = { model.currentState }

        assertEquals(3, state().visible.size)
        // GBP 200 + USD 125 at exr 1.25 → GBP 100; the tax line never counts.
        assertEquals(300.0, state().total, 1e-9)

        model.onEvent(AssetEvent.PickCurrency("USD"))
        assertEquals(375.0, state().total, 1e-9)
        assertEquals("$", state().currencySymbol)
        model.onEvent(AssetEvent.PickCurrency(null))
        assertEquals("GBP", state().activeCurrency)

        model.onEvent(AssetEvent.FilterCategory(CategoryFilter.Sell))
        assertEquals(listOf("l2"), state().visible.map { it.lineItemId })
        model.onEvent(AssetEvent.FilterCategory(CategoryFilter.All))

        // The haystack includes the vendor's *name* and the department's.
        model.onEvent(AssetEvent.Search("grip hire"))
        assertEquals(listOf("l1", "l3"), state().visible.map { it.lineItemId })
        model.onEvent(AssetEvent.Search("lighting"))
        assertEquals(listOf("l2"), state().visible.map { it.lineItemId })
        model.onEvent(AssetEvent.Search(""))

        model.onEvent(AssetEvent.FilterDepartments(listOf("d2")))
        assertEquals(listOf("l2"), state().visible.map { it.lineItemId })
        assertEquals("—", state().vendorName("nobody"))
        assertEquals("—", state().departmentName("65f0c3a1b2c3d4e5f6a7b8c9"), "an id nobody can name is a dash")
        assertEquals("Alex Reed · Grip", state().author("u7"))
    }

    @Test
    fun `the export carries departments only for the privileged`() = runTest(dispatcher) {
        val accountant = model(viewer = AssetViewer(canView = true, isAccountant = true, ready = true))
        accountant.onEvent(AssetEvent.FilterDepartments(listOf("d1")))
        accountant.onEvent(AssetEvent.Export(AssetExportFormat.Excel))
        advanceUntilIdle()
        assertEquals(AssetExportFormat.Excel to listOf("d1"), exports.single())

        val viewOnly = model(viewer = AssetViewer(canView = true, ready = true))
        viewOnly.onEvent(AssetEvent.FilterDepartments(listOf("d1")))
        assertTrue(viewOnly.currentState.departmentFilter.isEmpty(), "no picker, no filter")
        viewOnly.onEvent(AssetEvent.Export(AssetExportFormat.Pdf))
        advanceUntilIdle()
        assertEquals(AssetExportFormat.Pdf to emptyList(), exports.last())
    }

    // -- fakes ----------------------------------------------------------------------------------

    private val storedPhoto = AssetAttachment(
        media = "p/actual/dolly.jpg",
        bucket = "zillit-eu",
        region = "eu-west-2",
        name = "dolly.jpg",
        contentType = "image",
        contentSubtype = "jpg",
    )

    private data class Write(
        val route: String,
        val category: AssetCategory? = null,
        val comments: String? = null,
        val attachments: List<AssetAttachment> = emptyList(),
    )

    private class FakeRepository(private val feed: List<AssetLine>) : AssetRepository {
        val records = mutableMapOf<String, AssetRecord?>()
        val writes = mutableListOf<Write>()
        var recordGate: CompletableDeferred<Unit>? = null
        var recordReads = 0
        var failRecord = false
        var failWrites = 0

        override suspend fun lines(): ZillitResult<List<AssetLine>> = ZillitResult.Success(feed)

        override suspend fun record(line: AssetLine): ZillitResult<AssetRecord?> {
            recordGate?.await()
            recordReads++
            if (failRecord) return ZillitResult.Failure(ZillitError.NoConnection("offline"))
            return ZillitResult.Success(records[line.lineItemId])
        }

        override suspend fun create(
            line: AssetLine,
            category: AssetCategory,
            comments: String,
            attachments: List<AssetAttachment>,
        ): ZillitResult<AssetRecord> = written(Write("create", category, comments, attachments)) {
            AssetRecord("a-new", line.lineItemId, category, comments, "u7", 1_787_140_800_000L, attachments)
        }

        override suspend fun update(
            assetId: String,
            category: AssetCategory,
            attachments: List<AssetAttachment>,
        ): ZillitResult<AssetRecord> = written(Write("update", category, attachments = attachments)) {
            current(assetId).copy(category = category, attachments = attachments)
        }

        override suspend fun updateComment(assetId: String, comments: String): ZillitResult<AssetRecord> =
            written(Write("comment", comments = comments)) { current(assetId).copy(comments = comments) }

        override suspend fun vendors(): ZillitResult<Map<String, String>> =
            ZillitResult.Success(mapOf("v1" to "Grip Hire Ltd", "v2" to "Lighting Co"))

        private fun current(assetId: String): AssetRecord =
            records.values.filterNotNull().firstOrNull { it.id == assetId } ?: AssetRecord(id = assetId)

        private fun written(write: Write, saved: () -> AssetRecord): ZillitResult<AssetRecord> {
            if (failWrites > 0) {
                failWrites--
                return ZillitResult.Failure(ZillitError.Http(500, "The register is unavailable"))
            }
            writes += write
            return ZillitResult.Success(saved().also { records[it.lineItemId.ifBlank { "?" }] = it })
        }
    }

    private class FakeFiles : AssetFiles {
        val uploaded = mutableListOf<String>()
        var halfUpload = false
        var nextPick: List<PickedAssetFile> = emptyList()

        override suspend fun pick(onTooLarge: (String, Long) -> Unit): List<PickedAssetFile> = nextPick

        override suspend fun upload(file: PickedAssetFile): ZillitResult<AssetAttachment> {
            uploaded += file.name
            return ZillitResult.Success(
                AssetAttachment(
                    media = "asset-register/${file.name}",
                    bucket = if (halfUpload) "" else "zillit-eu",
                    region = "eu-west-2",
                    name = file.name,
                    contentType = AssetFileRules.familyOf(file.name),
                    contentSubtype = extensionOf(file.name),
                ),
            )
        }

        override suspend fun read(attachment: AssetAttachment): ZillitResult<ByteArray> =
            ZillitResult.Success(byteArrayOf(1))

        override suspend fun saveCopy(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
            ZillitResult.Success(Unit)
    }

    private object Directory : AssetDirectory {
        override suspend fun departments() = listOf(AssetDepartment("d1", "Grip"), AssetDepartment("d2", "Camera"))

        override suspend fun currencies() = AssetCurrencies(
            options = listOf(
                AssetCurrency("GBP", "Pound Sterling", "£", 1.0),
                AssetCurrency("USD", "US Dollar", "$", 1.25),
            ),
            defaultCode = "GBP",
        )

        override suspend fun people() = listOf(AssetPerson("u7", "Alex Reed", "Grip"))
    }
}
