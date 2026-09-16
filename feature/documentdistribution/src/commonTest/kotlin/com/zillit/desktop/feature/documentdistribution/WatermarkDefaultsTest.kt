package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryPage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryQuery
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSettings
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSettingsPatch
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSize
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.domain.patchAgainst
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEffect
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The production's shared watermark appearance: read once per open, the
 * starting point of every new stamp, written by the wizard's Save as a
 * partial update, and replaced whole when another device saves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatermarkDefaultsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val pdf = LibraryDocument(
        id = "d1",
        name = "Call Sheet.pdf",
        contentType = "application/pdf",
        sizeBytes = 9,
    )

    private val shared = WatermarkSettings(
        size = WatermarkSize.Small,
        color = "#dc2626",
        opacity = 0.25,
        isDefault = false,
        updatedBy = "u2",
        updated = 1_757_900_000_000,
    )

    private class Repo(var settings: WatermarkSettings, val document: LibraryDocument) :
        FakeDocDistRepository(MutableSharedFlow()) {
        val updates = MutableSharedFlow<WatermarkSettings>()
        val saved = mutableListOf<WatermarkSettingsPatch>()
        var refuse = false
        override val watermarkSettingsUpdates get() = updates
        override suspend fun documents(query: LibraryQuery) = ZillitResult.Success(LibraryPage(listOf(document), 1))
        override suspend fun watermarkSettings() = ZillitResult.Success(settings)
        override suspend fun updateWatermarkSettings(patch: WatermarkSettingsPatch): ZillitResult<WatermarkSettings> {
            saved += patch
            if (refuse) return ZillitResult.Failure(ZillitError.Validation("Watermark settings required"))
            settings = settings.copy(
                size = patch.size ?: settings.size,
                color = patch.color ?: settings.color,
                opacity = patch.opacity ?: settings.opacity,
                isDefault = false,
                updatedBy = "u1",
            )
            return ZillitResult.Success(settings)
        }
    }

    private fun model(repo: Repo) = DocDistViewModel(
        repository = repo,
        viewer = { DocDistViewer(userId = "u1", ready = true) },
        today = { LocalDate(2026, 9, 15) },
    ).also { it.start() }

    @Test
    fun `every new stamp starts from the production's appearance, lines untouched`() = runTest(dispatcher) {
        val repo = Repo(shared, pdf)
        val vm = model(repo)
        runCurrent()
        assertEquals(shared, vm.state.value.watermarkDefaults, "read once on open")

        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.Compose)
        runCurrent()
        val stamp = vm.state.value.composer.watermark
        assertEquals(WatermarkSize.Small, stamp.size)
        assertEquals("#dc2626", stamp.color)
        assertEquals(0.25, stamp.opacity)
        assertEquals(WatermarkStyle().line1, stamp.line1, "what the stamp says is the sender's, not the project's")
        vm.onEvent(DocDistEvent.CloseComposer)

        vm.onEvent(DocDistEvent.OpenWatermarkDownload("d1"))
        runCurrent()
        assertEquals(WatermarkSize.Small, assertNotNull(vm.state.value.watermarkDownload).style.size)
    }

    /**
     * Only what changed goes: the server keeps the rest, so two people
     * adjusting different controls at the same moment both land. The echo
     * becomes the new cache, so the next composer starts from it.
     */
    @Test
    fun `the wizard's Save shares only the fields it changed`() = runTest(dispatcher) {
        val repo = Repo(WatermarkSettings.BuiltIn, pdf)
        val vm = model(repo)
        runCurrent()
        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.Compose)
        vm.onEvent(DocDistEvent.OpenWatermarkWizard)
        val draft = assertNotNull(vm.state.value.composer.wizardDraft)

        vm.onEvent(DocDistEvent.EditWizard(draft.copy(opacity = 0.25, color = "#6B7280")))
        vm.onEvent(DocDistEvent.SaveWizard)
        runCurrent()

        assertEquals(
            WatermarkSettingsPatch(opacity = 0.25),
            repo.saved.single(),
            "the recoloured grey is the same grey",
        )
        assertEquals(0.25, vm.state.value.watermarkDefaults.opacity)
        assertEquals(false, vm.state.value.watermarkDefaults.isDefault)
        assertEquals(0.25, vm.state.value.composer.watermark.opacity, "the send's own stamp took the edit")
    }

    @Test
    fun `a Save that changed nothing about the appearance does not write`() = runTest(dispatcher) {
        val repo = Repo(shared, pdf)
        val vm = model(repo)
        runCurrent()
        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.Compose)
        vm.onEvent(DocDistEvent.OpenWatermarkWizard)
        val draft = assertNotNull(vm.state.value.composer.wizardDraft)

        vm.onEvent(DocDistEvent.EditWizard(draft.copy(line2Custom = "DRAFT")))
        vm.onEvent(DocDistEvent.SaveWizard)
        runCurrent()

        assertTrue(repo.saved.isEmpty(), "an empty patch is refused by the server, so it is never sent")
    }

    /** The send keeps the stamp the sender drew; only the sharing failed. */
    @Test
    fun `a refused share is reported and the send's stamp stays as drawn`() = runTest(dispatcher) {
        val repo = Repo(shared, pdf).apply { refuse = true }
        val vm = model(repo)
        val failures = mutableListOf<String>()
        val effects = launch { vm.effects.collect { if (it is DocDistEffect.Failed) failures += it.message } }
        runCurrent()
        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.Compose)
        vm.onEvent(DocDistEvent.OpenWatermarkWizard)
        val draft = assertNotNull(vm.state.value.composer.wizardDraft)

        vm.onEvent(DocDistEvent.EditWizard(draft.copy(size = WatermarkSize.Large)))
        vm.onEvent(DocDistEvent.SaveWizard)
        runCurrent()

        assertEquals(WatermarkSize.Large, vm.state.value.composer.watermark.size)
        assertEquals(shared, vm.state.value.watermarkDefaults, "the cache is not guessed at")
        assertEquals(listOf("Watermark settings required"), failures)
        effects.cancel()
    }

    /** Another device's save replaces the cache and leaves an open draft alone. */
    @Test
    fun `a socket update replaces the defaults without touching an open wizard`() = runTest(dispatcher) {
        val repo = Repo(WatermarkSettings.BuiltIn, pdf)
        val vm = model(repo)
        runCurrent()
        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.Compose)
        vm.onEvent(DocDistEvent.OpenWatermarkWizard)

        repo.updates.emit(shared)
        runCurrent()

        assertEquals(shared, vm.state.value.watermarkDefaults)
        val draft = assertNotNull(vm.state.value.composer.wizardDraft)
        assertEquals(WatermarkSize.Large, draft.size, "the draft is theirs")
        assertEquals(WatermarkSize.Large, vm.state.value.composer.watermark.size, "this send predates the change")
    }

    @Test
    fun `the patch compares colour case-insensitively`() {
        val style = WatermarkStyle(color = "#DC2626", size = WatermarkSize.Medium, opacity = 0.25)

        val patch = style.patchAgainst(shared)

        assertEquals(WatermarkSettingsPatch(size = WatermarkSize.Medium), patch)
    }
}
