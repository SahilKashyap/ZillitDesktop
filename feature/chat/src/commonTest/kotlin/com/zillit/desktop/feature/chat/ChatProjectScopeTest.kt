package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.data.frameProjectId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which production a live chat frame belongs to.
 *
 * The socket is one per device, so frames for every production this person is
 * on arrive here; the repository drops the ones that name another production
 * before they can reach the open one's cache or its badge. Android gates on
 * the same field.
 */
class ChatProjectScopeTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `a live frame names its production under detail`() {
        val frame = """{"success":true,"detail":{"_id":"m1","sender":"a","project_id":"p2"}}"""
        assertEquals("p2", frameProjectId(json(frame)))
    }

    @Test
    fun `a flat frame names it at the top`() {
        assertEquals("p1", frameProjectId(json("""{"_id":"m1","project_id":"p1"}""")))
    }

    @Test
    fun `a frame that does not say is nobody's to drop`() {
        assertNull(frameProjectId(json("""{"detail":{"_id":"m1","sender":"a"}}""")))
        assertNull(frameProjectId(json("""[]""")))
    }
}
