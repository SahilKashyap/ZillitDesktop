package com.zillit.desktop.core.network.applog

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppLoggerTest {

    private class FakeQueue : LogQueue {
        val rows = mutableListOf<QueuedLog>()
        override suspend fun save(log: QueuedLog) { rows += log }
        override suspend fun oldest(limit: Int) = rows.take(limit)
        override suspend fun count() = rows.size.toLong()
        override suspend fun delete(ids: List<String>) { rows.removeAll { it.uniqueId in ids } }
        override suspend fun trim(keep: Int) { while (rows.size > keep) rows.removeAt(0) }
    }

    private class FakeSender(var healthy: Boolean = true) : LogSender {
        val batches = mutableListOf<List<QueuedLog>>()
        override suspend fun send(records: List<QueuedLog>): List<String>? {
            batches += records
            return if (healthy) records.map { it.uniqueId } else null
        }
    }

    private val identity = LogIdentity(
        platform = "desktop",
        appVersion = "1.0.6",
        osVersion = "Mac OS X 15",
        deviceModel = "Mac OS X aarch64",
        installId = { "install" },
        network = { "unknown" },
    )

    private fun TestScope.logger(queue: LogQueue, sender: LogSender): AppLogger {
        var next = 0
        return AppLogger(queue, sender, identity, this, newId = { "id-${next++}" }, nowMillis = { 42L })
    }

    private fun failure(path: String) = LogEvent(
        category = LogCategory.Api,
        name = apiEventName("GET", "https://api.zillit.com/api/v2/$path?x=1"),
        error = LogError("boom", "500"),
        api = LogApi(withoutQuery("https://api.zillit.com/api/v2/$path?x=1"), "GET", 500),
    )

    @Test
    fun sendsEachEventOnTheSpot() = runTest {
        val queue = FakeQueue()
        val sender = FakeSender()
        val log = logger(queue, sender)

        log.log(failure("device"))
        runCurrent()

        assertEquals(1, sender.batches.size)
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun aFailingEndpointFallsBackToBatchesOfTen() = runTest {
        val queue = FakeQueue()
        val sender = FakeSender(healthy = false)
        val log = logger(queue, sender)

        log.log(failure("a"))
        runCurrent()
        assertEquals(1, sender.batches.size)

        // Held until ten are waiting.
        repeat(8) { log.log(failure("b$it")); runCurrent() }
        assertEquals(1, sender.batches.size)

        sender.healthy = true
        log.log(failure("c"))
        runCurrent()
        assertEquals(10, sender.batches.last().size)
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun neverLogsTheLogEndpointItself() = runTest {
        val queue = FakeQueue()
        val sender = FakeSender()
        logger(queue, sender).log(failure("location/log"))
        runCurrent()

        assertTrue(sender.batches.isEmpty())
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun envelopeCarriesTheSharedV1Keys() {
        val body = failure("device").envelope(identity, eventTimeMillis = 7L)

        assertEquals("1", body["schema_version"]!!.jsonPrimitive.content)
        assertEquals("desktop", body["platform"]!!.jsonPrimitive.content)
        assertEquals("GET /v2/device", body["name"]!!.jsonPrimitive.content)
        assertEquals("api", body["category"]!!.jsonPrimitive.content)
        val api = body["api"]!!.jsonObject
        assertEquals("https://api.zillit.com/api/v2/device", api["url"]!!.jsonPrimitive.content)
        assertEquals("500", api["status"]!!.jsonPrimitive.content)
        assertEquals("500", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        // Round-trips as the JSON the queue stores.
        Json.parseToJsonElement(body.toString())
    }
}
