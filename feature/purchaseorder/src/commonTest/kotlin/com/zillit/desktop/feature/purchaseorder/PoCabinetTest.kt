package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.purchaseorder.cabinet.PoCabinetModel
import com.zillit.desktop.feature.purchaseorder.cabinet.PurchaseOrderCabinetViewer
import com.zillit.desktop.feature.purchaseorder.data.PurchaseOrderRepositoryImpl
import com.zillit.desktop.feature.purchaseorder.domain.PoQuickFilter
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The File Cabinet's purchase-order list: the one flag it adds to the wire, the
 * rows it keeps, and the fact that it can only read.
 */
class PoCabinetTest {

    private class Sent(val method: String, val path: String, val query: String)

    private val sent = mutableListOf<Sent>()

    private fun repository(): PurchaseOrderRepositoryImpl {
        val engine = MockEngine { request: HttpRequestData ->
            sent += Sent(request.method.value, request.url.encodedPath, request.url.encodedQuery)
            respond(
                """{"status":1,"data":[]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return PurchaseOrderRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ CabinetMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
    }

    @Test
    fun `the cabinet list is the order list with isFileCabinet=true`() = runTest {
        repository().cabinetOrders()

        val call = sent.single()
        assertEquals("GET", call.method)
        assertTrue(call.path.endsWith("/api/v2/purchase-orders"), call.path)
        assertEquals("isFileCabinet=true", call.query)
    }

    @Test
    fun `the live list never carries the flag`() = runTest {
        repository().orders(null)

        assertEquals("", sent.single().query, "a live list asked for the archive would hide every open order")
    }

    /** The cabinet's own fake: the rows it archives, and a record of anything that writes. */
    private class CabinetFake(
        private val archive: ZillitResult<List<PurchaseOrder>>,
        private val inner: FlowFake = FlowFake(emptyList()),
    ) : PurchaseOrderRepository by inner {
        var cabinetCalls = 0
        override suspend fun cabinetOrders(): ZillitResult<List<PurchaseOrder>> {
            cabinetCalls++
            return archive
        }

        val writes: Int get() = inner.approved.size + inner.rejected.size + inner.posted.size + inner.saved.size
    }

    private fun TestScope.model(fake: CabinetFake) =
        PoCabinetModel(fake, people = null, projectSettings = null, openAttachment = {}, scope = this)

    private val archive = listOf(
        parityOrder("draft", PoStatus.Draft),
        parityOrder("pending", PoStatus.AwaitingApproval),
        parityOrder("approved", PoStatus.Approved),
        parityOrder("entered", PoStatus.AccountsEntered),
        parityOrder("queued", PoStatus.Queued),
        parityOrder("rejected", PoStatus.Rejected),
        parityOrder("posted", PoStatus.Posted),
        parityOrder("closed", PoStatus.Closed),
    )

    @Test
    fun `drafts and pending orders never reach the list`() = runTest {
        val model = model(CabinetFake(ZillitResult.Success(archive)))
        model.start()
        advanceUntilIdle()

        val ids = model.state.value.rows.map { it.id }.toSet()
        assertEquals(setOf("approved", "entered", "queued", "rejected", "posted", "closed"), ids)
        assertTrue(model.state.value.readOnly)
    }

    @Test
    fun `the Approved chip folds in queued and acct entered, and there is no Pending chip`() = runTest {
        val model = model(CabinetFake(ZillitResult.Success(archive)))
        model.start()
        advanceUntilIdle()
        assertEquals(
            listOf(
                PoQuickFilter.All,
                PoQuickFilter.Approved,
                PoQuickFilter.Rejected,
                PoQuickFilter.Posted,
                PoQuickFilter.Closed,
            ),
            model.state.value.quickFilters,
        )

        model.onEvent(PoEvent.Filter(PoQuickFilter.Approved))
        assertEquals(setOf("approved", "entered", "queued"), model.state.value.rows.map { it.id }.toSet())
    }

    @Test
    fun `a department filter narrows the archive`() = runTest {
        val rows = listOf(
            parityOrder("cam", PoStatus.Posted, departmentId = "dept-cam"),
            parityOrder("art", PoStatus.Posted, departmentId = "dept-art"),
        )
        val model = model(CabinetFake(ZillitResult.Success(rows)))
        model.start()
        advanceUntilIdle()

        model.onEvent(PoEvent.FilterDepartment("dept-art"))
        assertEquals(listOf("art"), model.state.value.rows.map { it.id })
    }

    @Test
    fun `a failed read is an error, and Refresh asks again`() = runTest {
        val fake = CabinetFake(ZillitResult.Failure(ZillitError.Unknown("down")))
        val model = model(fake)
        model.start()
        advanceUntilIdle()
        assertNotNull(model.state.value.error)
        assertTrue(model.state.value.rows.isEmpty())

        model.onEvent(PoEvent.Refresh)
        advanceUntilIdle()
        assertEquals(2, fake.cabinetCalls)
    }

    /** Every write the tool has is ignored: the model answers a handful of events and nothing else. */
    @Test
    fun `no write event reaches the service`() = runTest {
        val fake = CabinetFake(ZillitResult.Success(archive))
        val model = model(fake)
        model.start()
        advanceUntilIdle()

        model.onEvent(PoEvent.OpenOrder("posted"))
        advanceUntilIdle()
        listOf(
            PoEvent.EditOrder("posted"),
            PoEvent.ProcessOrder("posted"),
            PoEvent.SendVendorEmail("posted"),
            PoEvent.AskReassign("posted"),
            PoEvent.AskClose("posted"),
            PoEvent.CreateOrder,
        ).forEach(model::onEvent)
        advanceUntilIdle()

        assertEquals(0, fake.writes)
        assertNull(model.state.value.form)
        assertNull(model.state.value.entry)
        assertNull(model.state.value.reassign)
        assertNull(model.state.value.closePo)
    }

    @Test
    fun `the viewer serves the purchase_order row with its one entry`() = runTest {
        val viewer = PurchaseOrderCabinetViewer(CabinetFake(ZillitResult.Success(archive)))

        assertEquals("purchase_order", viewer.identifier)
        assertTrue(viewer.expands)
        val entry = viewer.entries().single()
        assertEquals("report", entry.key)
        assertEquals("All Po's In The Project", entry.name)
    }
}

private class CabinetMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
