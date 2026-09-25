package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryPage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryQuery
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkLine
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSettings
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSettingsPatch
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSize
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.domain.sameAppearanceAs
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
 * starting point of every new stamp, followed by every stamp nobody changed
 * by hand, and saved only from the Watermark settings dialog.
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
     * A look chosen in the wizard is this email's only (web 9df4778ee): the
     * project's settings are saved only from the Watermark settings dialog.
     */
    @Test
    fun `the wizard's Save never writes the project's settings`() = runTest(dispatcher) {
        val repo = Repo(WatermarkSettings.BuiltIn, pdf)
        val vm = model(repo)
        runCurrent()
        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.Compose)
        vm.onEvent(DocDistEvent.OpenWatermarkWizard)
        val draft = assertNotNull(vm.state.value.composer.wizardDraft)

        vm.onEvent(DocDistEvent.EditWizard(draft.copy(opacity = 0.25)))
        vm.onEvent(DocDistEvent.SaveWizard)
        runCurrent()

        assertTrue(repo.saved.isEmpty())
        assertEquals(WatermarkSettings.BuiltIn, vm.state.value.watermarkDefaults)
        assertEquals(0.25, vm.state.value.composer.watermark.opacity, "the send's own stamp took the edit")
        assertTrue(vm.state.value.composer.watermarkEdited)
    }

    /** Someone else's save reaches every stamp nobody has changed by hand — and only those. */
    @Test
    fun `a socket update moves an unchanged stamp and leaves a changed one`() = runTest(dispatcher) {
        val repo = Repo(WatermarkSettings.BuiltIn, pdf)
        val vm = model(repo)
        runCurrent()
        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.Compose)
        vm.onEvent(DocDistEvent.OpenWatermarkWizard)

        repo.updates.emit(shared)
        runCurrent()

        assertEquals(shared, vm.state.value.watermarkDefaults)
        assertEquals(WatermarkSize.Small, vm.state.value.composer.watermark.size, "an untouched send follows")
        val draft = assertNotNull(vm.state.value.composer.wizardDraft)
        assertEquals(WatermarkSize.Large, draft.size, "an open draft is its user's")

        vm.onEvent(DocDistEvent.EditWizard(draft.copy(size = WatermarkSize.Medium)))
        vm.onEvent(DocDistEvent.SaveWizard)
        repo.updates.emit(shared.copy(size = WatermarkSize.Large))
        runCurrent()
        assertEquals(WatermarkSize.Medium, vm.state.value.composer.watermark.size, "a send's own look stays put")
    }

    @Test
    fun `a wizard Save that matches the project keeps following it`() = runTest(dispatcher) {
        val repo = Repo(shared, pdf)
        val vm = model(repo)
        runCurrent()
        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.Compose)
        vm.onEvent(DocDistEvent.OpenWatermarkWizard)
        val draft = assertNotNull(vm.state.value.composer.wizardDraft)

        vm.onEvent(DocDistEvent.EditWizard(draft.copy(line2 = WatermarkLine.Custom, line2Custom = "DRAFT")))
        vm.onEvent(DocDistEvent.SaveWizard)
        runCurrent()

        assertEquals(false, vm.state.value.composer.watermarkEdited, "only the words changed")
    }

    @Test
    fun `the download dialog follows until its appearance is changed`() = runTest(dispatcher) {
        val repo = Repo(WatermarkSettings.BuiltIn, pdf)
        val vm = model(repo)
        runCurrent()
        vm.onEvent(DocDistEvent.OpenWatermarkDownload("d1"))
        runCurrent()
        repo.updates.emit(shared)
        runCurrent()
        val open = assertNotNull(vm.state.value.watermarkDownload)
        assertEquals(WatermarkSize.Small, open.style.size)

        vm.onEvent(DocDistEvent.EditWatermarkDownloadStyle(open.style.copy(opacity = 0.6)))
        repo.updates.emit(WatermarkSettings.BuiltIn)
        runCurrent()
        assertEquals(0.6, assertNotNull(vm.state.value.watermarkDownload).style.opacity)
        assertEquals(WatermarkSize.Small, assertNotNull(vm.state.value.watermarkDownload).style.size)
    }

    /** The dialog saves all three appearance fields and becomes the new start for everyone. */
    @Test
    fun `the Watermark settings dialog saves the project's look`() = runTest(dispatcher) {
        val repo = Repo(WatermarkSettings.BuiltIn, pdf)
        val vm = model(repo)
        runCurrent()
        vm.onEvent(DocDistEvent.OpenWatermarkSettings)
        val draft = assertNotNull(vm.state.value.watermarkSettings).draft

        vm.onEvent(DocDistEvent.SaveWatermarkSettings)
        runCurrent()
        assertTrue(repo.saved.isEmpty(), "nothing changed, nothing to save")

        vm.onEvent(DocDistEvent.EditWatermarkSettings(draft.copy(color = "#DC2626", opacity = 0.25)))
        vm.onEvent(DocDistEvent.SaveWatermarkSettings)
        runCurrent()

        assertEquals(
            WatermarkSettingsPatch(size = WatermarkSize.Large, color = "#dc2626", opacity = 0.25),
            repo.saved.single(),
        )
        assertEquals(null, vm.state.value.watermarkSettings, "closed on success")
        assertEquals(0.25, vm.state.value.watermarkDefaults.opacity)

        vm.onEvent(DocDistEvent.ToggleDocument("d1"))
        vm.onEvent(DocDistEvent.Compose)
        runCurrent()
        assertEquals(0.25, vm.state.value.composer.watermark.opacity, "the next send starts from it")
    }

    @Test
    fun `a refused settings save is reported and the dialog stays open`() = runTest(dispatcher) {
        val repo = Repo(shared, pdf).apply { refuse = true }
        val vm = model(repo)
        val failures = mutableListOf<String>()
        val effects = launch { vm.effects.collect { if (it is DocDistEffect.Failed) failures += it.message } }
        runCurrent()
        vm.onEvent(DocDistEvent.OpenWatermarkSettings)
        vm.onEvent(DocDistEvent.ResetWatermarkSettings)
        vm.onEvent(DocDistEvent.SaveWatermarkSettings)
        runCurrent()

        val open = assertNotNull(vm.state.value.watermarkSettings)
        assertEquals(false, open.saving)
        assertEquals(WatermarkSize.Large, open.draft.size, "reset to the standard look")
        assertEquals(shared, vm.state.value.watermarkDefaults, "the cache is not guessed at")
        assertEquals(listOf("Watermark settings required"), failures)
        effects.cancel()
    }

    @Test
    fun `appearance compares colour without case and opacity to the percent`() {
        val style = WatermarkStyle(color = "#DC2626", size = WatermarkSize.Small, opacity = 0.2500001)

        assertTrue(style.sameAppearanceAs(shared))
        assertEquals(false, style.copy(size = WatermarkSize.Medium).sameAppearanceAs(shared))
    }
}
