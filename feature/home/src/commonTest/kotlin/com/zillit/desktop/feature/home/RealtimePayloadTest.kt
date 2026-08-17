package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.data.unwrapData
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Digging the post out of a socket envelope.
 *
 * The server wraps it differently depending on the event, including as a JSON
 * **string** — Android re-parses for the same reason. Getting this wrong is a
 * silent no-op: the board simply never updates, with nothing in the log.
 */
class RealtimePayloadTest {

    private fun unwrap(json: String) = Json.parseToJsonElement(json).unwrapData()

    @Test
    fun `a bare object is taken as-is`() {
        assertEquals("n1", unwrap("""{"_id":"n1","message":"x"}""")?.get("_id")?.toString()?.trim('"'))
    }

    @Test
    fun `an object nested under data is found`() {
        assertEquals("n1", unwrap("""{"data":{"_id":"n1"}}""")?.get("_id")?.toString()?.trim('"'))
    }

    @Test
    fun `data sent as a JSON string is parsed`() {
        // The wire really does this.
        val payload = """{"data":"{\"_id\":\"n1\"}"}"""

        assertEquals("n1", unwrap(payload)?.get("_id")?.toString()?.trim('"'))
    }

    @Test
    fun `data sent as an array takes the first element`() {
        assertEquals("n1", unwrap("""{"data":[{"_id":"n1"},{"_id":"n2"}]}""")?.get("_id")?.toString()?.trim('"'))
    }

    @Test
    fun `a doubly nested envelope is unwrapped`() {
        assertEquals("n1", unwrap("""{"data":{"data":{"_id":"n1"}}}""")?.get("_id")?.toString()?.trim('"'))
    }

    @Test
    fun `an envelope with no post yields null rather than throwing`() {
        assertNull(unwrap("""{"status":1}"""))
        assertNull(unwrap("""{"data":null}"""))
        assertNull(unwrap("""{"data":[]}"""))
        assertNull(unwrap("""{"data":"not json"}"""))
        assertNull(unwrap("""42"""))
    }
}
