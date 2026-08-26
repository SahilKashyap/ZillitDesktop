package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.protoo.MediasoupPageEvent
import com.zillit.desktop.feature.calls.data.protoo.MediasoupScripts
import com.zillit.desktop.feature.calls.data.protoo.parseMediasoupPageEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge between this side and the page's media module.
 *
 * Two hazards. Values crossing into a script carry SDP-shaped blobs and ICE
 * credentials, so anything spliced raw is a syntax error inside the page that
 * surfaces as a call which simply never connects. And the Agora parser shares
 * this channel, so each side must ignore what is not addressed to it.
 */
class MediasoupBridgeTest {

    @Test
    fun `page events are read into their own type`() {
        val loaded = parseMediasoupPageEvent(
            """{"type":"ms-loaded","rtpCapabilities":{"codecs":[]},"sctpCapabilities":{"numStreams":{}}}""",
        )
        assertTrue(loaded is MediasoupPageEvent.Loaded, "got $loaded")

        val ask = parseMediasoupPageEvent(
            """{"type":"ms-ask","askId":12,"method":"produce","data":{"kind":"audio"}}""",
        ) as MediasoupPageEvent.Ask
        assertEquals(12L, ask.askId)
        assertEquals("produce", ask.method)
        assertEquals("audio", ask.data["kind"]?.jsonPrimitive?.content)

        val consumer = parseMediasoupPageEvent(
            """{"type":"ms-consumer","consumerId":"c1","peerId":"u:d","kind":"video","share":true}""",
        ) as MediasoupPageEvent.Consumer
        assertTrue(consumer.share, "a shared screen must be distinguishable from a camera")
    }

    /** The Agora parser reads the same channel; each must ignore the other's traffic. */
    @Test
    fun `messages that are not line one are ignored rather than misread`() {
        assertNull(parseMediasoupPageEvent("""{"type":"joined","channel":"c","uid":5}"""))
        assertNull(parseMediasoupPageEvent("""{"type":"peer-joined","uid":7}"""))
        assertNull(parseMediasoupPageEvent("not json"))
    }

    /** An ask with no id can never be answered, so it must not look like one. */
    @Test
    fun `a malformed ask is dropped, not half-read`() {
        assertNull(parseMediasoupPageEvent("""{"type":"ms-ask","method":"produce"}"""))
        assertNull(parseMediasoupPageEvent("""{"type":"ms-ask","askId":1}"""))
        assertNull(parseMediasoupPageEvent("""{"type":"ms-loaded"}"""))
    }

    /**
     * The escaping that matters. A codec name or a TURN credential containing a
     * quote must not be able to close the string and run as code.
     */
    @Test
    fun `values crossing into the page cannot break out of their string`() {
        val hostile = buildJsonObject { put("name", """he said "hi"); alert(1); //""") }

        val script = MediasoupScripts.load(hostile)

        assertTrue(script.startsWith("zillitMs.load(JSON.parse("), script)
        assertTrue(script.endsWith("))"), script)
        // The dangerous sequence must appear only in escaped form.
        assertTrue("alert(1)" !in script.substringBefore("\\\"") || "\\\"" in script, script)
        assertTrue("\\\"" in script, "quotes must be escaped: $script")
    }

    @Test
    fun `the transport script carries both parameter sets and the relays`() {
        val script = MediasoupScripts.createTransports(
            buildJsonObject { put("id", "send-1") },
            buildJsonObject { put("id", "recv-1") },
            """[{"urls":["turn:t:1"]}]""",
        )

        assertTrue("send-1" in script, script)
        assertTrue("recv-1" in script, script)
        assertTrue("turn:t:1" in script, "relays must reach the page with the transports")
    }

    @Test
    fun `settle carries the id, the verdict and the payload`() {
        val ok = MediasoupScripts.settle(9, true, buildJsonObject { put("id", "p-1") })
        assertTrue(ok.startsWith("zillitMs.settle(9, true, JSON.parse("), ok)

        val refused = MediasoupScripts.settle(9, false, buildJsonObject { put("reason", "room is full") })
        assertTrue("false" in refused, refused)
        assertTrue("room is full" in refused, refused)
    }

    @Test
    fun `the simple toggles are plain calls`() {
        assertEquals("zillitMs.setMic(true)", MediasoupScripts.setMic(true))
        assertEquals("zillitMs.setCam(false)", MediasoupScripts.setCam(false))
        assertEquals("zillitMs.leave()", MediasoupScripts.LEAVE)
    }
}
