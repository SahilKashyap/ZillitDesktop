package com.zillit.desktop.feature.sides

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.sides.domain.GeneratePlan
import com.zillit.desktop.feature.sides.domain.SceneInfo
import com.zillit.desktop.feature.sides.domain.Script
import com.zillit.desktop.feature.sides.domain.ScriptVersion
import com.zillit.desktop.feature.sides.domain.SidesPdfPage
import com.zillit.desktop.feature.sides.domain.SidesRecord
import com.zillit.desktop.feature.sides.domain.SidesRepository
import com.zillit.desktop.feature.sides.domain.SidesTransfer
import com.zillit.desktop.feature.sides.domain.SidesViewer
import com.zillit.desktop.feature.sides.domain.StoredAttachment
import com.zillit.desktop.feature.sides.ui.SidesViewModel
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
 * A `sides:generated` pulse refetches the visible list once — the web's
 * socket nudge (`SidesPage.jsx:92-109`), with the generating poll left as
 * the fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SidesSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeRepo(override val refreshes: Flow<Unit>) : SidesRepository {
        var listLoads = 0
        override suspend fun scripts(limit: Int) = ZillitResult.Success(emptyList<Script>())
        override suspend fun createScript(title: String, attachment: StoredAttachment?) =
            ZillitResult.Success(null as Script?)
        override suspend fun deleteScript(id: String) = ZillitResult.Success(Unit)
        override suspend fun versions(scriptId: String) = ZillitResult.Success(emptyList<ScriptVersion>())
        override suspend fun addVersion(scriptId: String, attachment: StoredAttachment, versionLabel: String) =
            ZillitResult.Success(Unit)
        override suspend fun scenes(versionId: String) = ZillitResult.Success(emptyList<SceneInfo>())
        override suspend fun sides(history: Boolean, limit: Int): ZillitResult<List<SidesRecord>> {
            listLoads++
            return ZillitResult.Success(emptyList())
        }
        override suspend fun sidesById(id: String): ZillitResult<SidesRecord> =
            ZillitResult.Failure(ZillitError.Unknown("unused"))
        override suspend fun generate(plan: GeneratePlan): ZillitResult<SidesRecord> =
            ZillitResult.Failure(ZillitError.Unknown("unused"))
        override suspend fun downloadUrl(id: String, countDownload: Boolean) = ZillitResult.Success("u")
        override suspend fun publish(id: String) = ZillitResult.Success(Unit)
        override suspend fun deleteSides(id: String) = ZillitResult.Success(Unit)
    }

    private object NoTransfer : SidesTransfer {
        override suspend fun upload(fileName: String, bytes: ByteArray) =
            ZillitResult.Success(StoredAttachment(media = "k"))
        override suspend fun fetch(url: String) = ZillitResult.Success(ByteArray(0))
        override fun renderPages(pdf: ByteArray, targetWidthPx: Int) =
            ZillitResult.Success(emptyList<SidesPdfPage>())
    }

    @Test
    fun `a generated pulse re-runs the visible list load once`() = runTest(dispatcher) {
        val events = MutableSharedFlow<Unit>()
        val repo = FakeRepo(refreshes = events)
        val model = SidesViewModel(
            repository = repo,
            transfer = NoTransfer,
            resolveViewer = { SidesViewer(userId = "u1", canPost = true, ready = true) },
        )

        model.start()
        runCurrent()
        assertEquals(1, repo.listLoads, "start loads the sides list once")

        events.emit(Unit)
        runCurrent()
        assertEquals(2, repo.listLoads, "the pulse re-runs exactly one load")

        model.start()
        runCurrent()
        events.emit(Unit)
        runCurrent()
        assertEquals(4, repo.listLoads, "a second start must not stack a second collector")
    }
}
