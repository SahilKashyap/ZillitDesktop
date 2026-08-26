package com.zillit.desktop.feature.transportation

import com.zillit.desktop.feature.transportation.data.parseLicenceRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Licence-change requests.
 *
 * The desktop heard the socket events and reloaded the crew, but had no way
 * to answer one: approving or rejecting could only be done from the web. This
 * covers the reading half of closing that gap.
 */
class LicenceRequestTest {

    private fun parse(body: String) =
        parseLicenceRequest(Json.parseToJsonElement(body) as JsonObject)

    /** A request nobody has answered has no verdict — not a rejection. */
    @Test
    fun `an unanswered request has no verdict`() {
        val request = parse(
            """{"_id":"r1","user_id":"u9","license_picture":"drivers/u9.jpg","created_at":1786950000000}""",
        )

        assertEquals("r1", request?.id)
        assertEquals("u9", request?.userId)
        assertEquals(1_786_950_000_000, request?.createdAtMs)
        assertNull(request?.verified, "absent must not read as rejected")
    }

    /** An answered one carries its verdict either way. */
    @Test
    fun `an answered request carries its verdict`() {
        assertEquals(true, parse("""{"_id":"r2","is_licence_verified":true}""")?.verified)
        assertEquals(false, parse("""{"_id":"r3","is_licence_verified":false}""")?.verified)
    }

    /** A row nothing can address is dropped rather than guessed at. */
    @Test
    fun `an id-less row is dropped`() {
        assertNull(parse("""{"user_id":"u9"}"""))
    }

    /** A thin row still reads: the queue shows the person, not a crash. */
    @Test
    fun `a thin row still reads`() {
        val request = parse("""{"_id":"r4"}""")

        assertTrue(request != null && request.licencePicture.isEmpty() && request.createdAtMs == 0L)
    }
}
