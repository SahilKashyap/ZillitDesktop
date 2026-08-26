package com.zillit.desktop.feature.continuity

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.ContinuityDepartment
import com.zillit.desktop.feature.continuity.domain.ContinuityRepository
import com.zillit.desktop.feature.continuity.domain.ContinuityScene
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.ContinuityTransfer
import com.zillit.desktop.feature.continuity.domain.ContinuityViewer
import com.zillit.desktop.feature.continuity.domain.PickedContinuityFile
import com.zillit.desktop.feature.continuity.domain.SceneDraft
import com.zillit.desktop.feature.continuity.ui.ContinuityEvent
import com.zillit.desktop.feature.continuity.ui.ContinuityViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A continuity socket pulse refetches the folder grid — and the open
 * folder's cards — once. The web's own refetch on the same four events
 * (`ContinuityModal.jsx:242-295`, `IntraDepartment.jsx:576-658`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContinuitySyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeRepo(override val refreshes: Flow<Unit>) : ContinuityRepository {
        var folderLoads = 0
        var sceneLoads = 0
        override suspend fun folders(tab: ContinuityTab): ZillitResult<List<String>> {
            folderLoads++
            return ZillitResult.Success(listOf("12"))
        }
        override suspend fun scenes(
            tab: ContinuityTab,
            sceneFolder: String,
            departmentId: String?,
            beforeMs: Long,
        ): ZillitResult<List<ContinuityScene>> {
            sceneLoads++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun departments(sceneFolder: String) =
            ZillitResult.Success(emptyList<ContinuityDepartment>())
        override suspend fun create(draft: SceneDraft, attachment: ContinuityAttachment, uniqueId: String) =
            ZillitResult.Success(Unit)
        override suspend fun update(id: String, draft: SceneDraft) =
            ZillitResult.Success(null as ContinuityScene?)
        override suspend fun share(ids: List<String>, sceneFolder: String) = ZillitResult.Success(Unit)
        override suspend fun delete(tab: ContinuityTab, id: String) = ZillitResult.Success(Unit)
    }

    private object NoTransfer : ContinuityTransfer {
        override suspend fun upload(file: PickedContinuityFile) =
            ZillitResult.Success(ContinuityAttachment("k", "k", "image", "jpg", "n", "b", "r"))
        override suspend fun fetch(attachment: ContinuityAttachment, preview: Boolean) =
            ZillitResult.Success(ByteArray(0))
        override suspend fun saveAndOpen(fileName: String, bytes: ByteArray) = ZillitResult.Success(Unit)
    }

    @Test
    fun `a pulse reloads the folder grid and the open folder once`() = runTest(dispatcher) {
        val events = MutableSharedFlow<Unit>()
        val repo = FakeRepo(refreshes = events)
        val model = ContinuityViewModel(
            repository = repo,
            transfer = NoTransfer,
            resolveViewer = {
                ContinuityViewer(userId = "u1", departmentId = "d1", canPost = true, ready = true)
            },
            departmentName = { null },
            newUniqueId = { "uid" },
            nowMillis = { 1_000L },
        )

        model.start()
        runCurrent()
        assertEquals(1, repo.folderLoads, "start loads the folder grid once")

        events.emit(Unit)
        runCurrent()
        assertEquals(2, repo.folderLoads, "the pulse re-runs exactly one folder load")
        assertEquals(0, repo.sceneLoads, "no folder open, no card fetch")

        model.onEvent(ContinuityEvent.OpenFolder("12"))
        runCurrent()
        assertEquals(1, repo.sceneLoads)

        events.emit(Unit)
        runCurrent()
        assertEquals(2, repo.sceneLoads, "the open folder's cards refetch once per pulse")

        model.start()
        runCurrent()
        events.emit(Unit)
        runCurrent()
        assertEquals(3, repo.sceneLoads, "a second start must not stack a second collector")
    }
}
