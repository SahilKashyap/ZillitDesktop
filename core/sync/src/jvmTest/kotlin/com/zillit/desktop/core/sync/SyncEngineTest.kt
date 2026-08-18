package com.zillit.desktop.core.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.database.sync.SyncDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine over the real SQL store: what runs, in what order, what waits,
 * what parks, and what survives a restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {

    private val dispatcher = StandardTestDispatcher()
    private val online = MutableStateFlow(true)
    private var scope: SyncScope? = SyncScope("u1", "p1")
    private val ran = mutableListOf<String>()
    private val handlers = SyncHandlerRegistry()
    private lateinit var store: SqlOutboxStore
    private var ids = 0

    private fun TestScope.engine(): SyncEngine {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SyncDatabase.Schema.create(driver)
        store = SqlOutboxStore(SyncDatabase(driver), dispatcher)
        return SyncEngine(
            store = store,
            handlers = handlers,
            online = online,
            currentScope = { scope },
            scope = backgroundScope,
            nowMillis = { testScheduler.currentTime },
            newId = { "op-${ids++}" },
            retryPolicy = RetryPolicy(baseMillis = 1_000, capMillis = 10_000),
        )
    }

    /** A handler whose answers are scripted per label. */
    private fun scripted(kind: String = "k", answer: suspend (SyncOperation, SyncContext) -> SyncOutcome) {
        handlers.register(
            object : SyncHandler {
                override val kind = kind
                override suspend fun execute(operation: SyncOperation, context: SyncContext): SyncOutcome {
                    ran += operation.label
                    return answer(operation, context)
                }
            },
        )
    }

    private fun op(label: String, group: String? = null, dependsOn: String? = null, kind: String = "k") =
        NewOperation(kind = kind, label = label, payload = "{}", groupKey = group, dependsOn = dependsOn)

    @Test
    fun `nothing runs offline, everything runs when the network returns`() = runTest(dispatcher) {
        scripted { _, _ -> SyncOutcome.Done() }
        val engine = engine().also { it.start() }
        online.value = false
        runCurrent()

        engine.enqueue(op("float request"))
        runCurrent()
        assertEquals(emptyList(), ran)
        assertEquals(1, engine.status.value.pending)
        assertTrue(!engine.status.value.online)

        online.value = true
        runCurrent()
        assertEquals(listOf("float request"), ran)
        assertEquals(0, engine.status.value.pending)
        assertEquals(SyncState.Done, store.all().single().state)
    }

    @Test
    fun `a transport failure backs off and retries by itself`() = runTest(dispatcher) {
        var failures = 2
        scripted { _, _ ->
            if (failures-- > 0) SyncOutcome.RetryLater(ZillitError.NoConnection()) else SyncOutcome.Done()
        }
        val engine = engine().also { it.start() }
        engine.enqueue(op("po"))
        runCurrent()

        // First attempt failed; the retry is in the future, not now.
        assertEquals(1, ran.size)
        val parked = store.all().single()
        assertEquals(SyncState.Pending, parked.state)
        assertTrue(parked.nextAttemptAt > testScheduler.currentTime)
        assertEquals("No internet connection.", parked.lastError)

        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(3, ran.size, "two retries later it went through")
        assertEquals(SyncState.Done, store.all().single().state)
    }

    @Test
    fun `a refusal parks the operation until the user retries or discards`() = runTest(dispatcher) {
        var refuse = true
        scripted { _, _ ->
            if (refuse) {
                SyncOutcome.Failed(ZillitError.Http(status = 422, serverMessage = "Vendor missing"))
            } else {
                SyncOutcome.Done()
            }
        }
        val engine = engine().also { it.start() }
        val queued = assertNotNull(engine.enqueue(op("po")))
        runCurrent()

        assertEquals(SyncState.Failed, store.get(queued.id)?.state)
        assertEquals(1, engine.status.value.failed)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, ran.size, "a parked operation is not retried on a timer")

        refuse = false
        engine.retry(queued.id)
        runCurrent()
        assertEquals(SyncState.Done, store.get(queued.id)?.state)
        assertEquals(0, engine.status.value.failed)
    }

    @Test
    fun `a group runs in order and a failed head holds the rest`() = runTest(dispatcher) {
        var createFails = true
        scripted { op, _ ->
            if (op.label == "create" && createFails) SyncOutcome.Failed(ZillitError.Http(400)) else SyncOutcome.Done()
        }
        val engine = engine().also { it.start() }
        val create = assertNotNull(engine.enqueue(op("create", group = "po-1")))
        engine.enqueue(op("attach", group = "po-1"))
        engine.enqueue(op("submit", group = "po-1"))
        engine.enqueue(op("elsewhere"))
        runCurrent()

        assertEquals(listOf("create", "elsewhere"), ran, "the group waits, an unrelated operation does not")

        createFails = false
        engine.retry(create.id)
        runCurrent()
        assertEquals(listOf("create", "elsewhere", "create", "attach", "submit"), ran)
    }

    @Test
    fun `a dependent reads what its dependency left behind`() = runTest(dispatcher) {
        var seen: String? = null
        scripted { op, context ->
            when (op.label) {
                "create" -> SyncOutcome.Done(result = "srv-42")
                else -> {
                    seen = context.dependencyResult(op)
                    SyncOutcome.Done()
                }
            }
        }
        val engine = engine().also { it.start() }
        val create = assertNotNull(engine.enqueue(op("create")))
        engine.enqueue(op("submit", dependsOn = create.id))
        runCurrent()

        assertEquals("srv-42", seen)
    }

    @Test
    fun `discarding takes its dependents with it`() = runTest(dispatcher) {
        scripted { _, _ -> SyncOutcome.Failed(ZillitError.Http(400)) }
        val engine = engine().also { it.start() }
        val create = assertNotNull(engine.enqueue(op("create")))
        engine.enqueue(op("submit", dependsOn = create.id))
        runCurrent()

        engine.discard(create.id)
        runCurrent()
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun `only the open production's work runs, the rest is counted`() = runTest(dispatcher) {
        scripted { _, _ -> SyncOutcome.Done() }
        val engine = engine().also { it.start() }
        engine.enqueue(op("in p1"))
        runCurrent()
        assertEquals(listOf("in p1"), ran)

        online.value = false
        engine.enqueue(op("in p1 offline"))
        runCurrent()
        scope = SyncScope("u1", "p2")
        online.value = true
        engine.wake()
        runCurrent()

        assertEquals(listOf("in p1"), ran, "p1's operation must not be sent from inside p2")
        assertEquals(1, engine.status.value.elsewhere)
        assertEquals(0, engine.status.value.pending)

        scope = SyncScope("u1", "p1")
        engine.wake()
        runCurrent()
        assertEquals(listOf("in p1", "in p1 offline"), ran)
    }

    @Test
    fun `an operation caught mid-flight by a crash runs again after restart`() = runTest(dispatcher) {
        scripted { _, _ -> SyncOutcome.Done() }
        val engine = engine()
        val now = testScheduler.currentTime
        store.insert(
            SyncOperation(
                id = "left-over", scope = scope!!, kind = "k", label = "interrupted", payload = "{}",
                state = SyncState.InFlight, attempts = 1, nextAttemptAt = now, createdAt = now, updatedAt = now,
            ),
        )
        engine.start()
        runCurrent()
        assertEquals(listOf("interrupted"), ran)
        assertEquals(SyncState.Done, store.get("left-over")?.state)
    }

    @Test
    fun `stopping mid-attempt leaves the operation pending, not lost`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        scripted { _, _ -> gate.await(); SyncOutcome.Done() }
        val engine = engine().also { it.start() }
        val queued = assertNotNull(engine.enqueue(op("slow")))
        runCurrent()
        assertEquals(SyncState.InFlight, store.get(queued.id)?.state)

        engine.stop()
        runCurrent()
        assertEquals(SyncState.Pending, store.get(queued.id)?.state)
    }

    @Test
    fun `an operation nobody can handle is parked with a reason`() = runTest(dispatcher) {
        val engine = engine().also { it.start() }
        val queued = assertNotNull(engine.enqueue(op("mystery", kind = "unknown.kind")))
        runCurrent()
        val parked = assertNotNull(store.get(queued.id))
        assertEquals(SyncState.Failed, parked.state)
        assertEquals(SyncEngine.NO_HANDLER_MESSAGE, parked.lastError)
        assertNull(parked.result)
    }
}
