package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.EngineBridge
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.EngineConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Kotlin ⇄ call-page contract — these shapes ARE what call.js sends. */
class EngineBridgeTest {

    @Test
    fun `joined, peers, mutes and speakers parse`() {
        assertEquals(
            CallEngineEvent.Joined("chan", 12),
            EngineBridge.parse("""{"type":"joined","channel":"chan","uid":12}"""),
        )
        assertEquals(
            CallEngineEvent.PeerJoined(7),
            EngineBridge.parse("""{"type":"peer-joined","uid":7}"""),
        )
        assertEquals(
            CallEngineEvent.PeerAudioMuted(7, true),
            EngineBridge.parse("""{"type":"peer-audio","uid":7,"muted":true}"""),
        )
        assertEquals(
            CallEngineEvent.ActiveSpeakers(listOf(7, 12)),
            EngineBridge.parse("""{"type":"speakers","uids":[7,12]}"""),
        )
    }

    @Test
    fun `agora uid arrives as string on some payloads`() {
        assertEquals(
            CallEngineEvent.PeerJoined(42),
            EngineBridge.parse("""{"type":"peer-joined","uid":"42"}"""),
        )
    }

    @Test
    fun `connection states fold, unknown reads as Connecting`() {
        assertEquals(
            CallEngineEvent.ConnectionChanged(EngineConnection.Connected, ""),
            EngineBridge.parse("""{"type":"connection","state":"CONNECTED","reason":""}"""),
        )
        assertEquals(
            CallEngineEvent.ConnectionChanged(EngineConnection.Connecting, null),
            EngineBridge.parse("""{"type":"connection","state":"SOMETHING_NEW"}"""),
        )
    }

    @Test
    fun `ready and unknown types are transport noise, not events`() {
        assertNull(EngineBridge.parse("""{"type":"ready","sdk":"4.24.2"}"""))
        assertNull(EngineBridge.parse("""{"type":"future-thing"}"""))
        assertNull(EngineBridge.parse("not json"))
        assertTrue(EngineBridge.isReady("""{"type":"ready","sdk":"4.24.2"}"""))
    }

    @Test
    fun `join script escapes hostile tokens instead of executing them`() {
        val script = EngineBridge.joinScript(
            appId = "app",
            channel = "chan",
            token = """to"k\en');alert(1);//""",
            uid = 5,
            withVideo = false,
        )
        // The token stays one string literal: its quote arrives escaped and
        // the payload never terminates the argument list.
        assertTrue(script.startsWith("""zillitCall.join("app", "chan", "to\"k\\en');alert(1);//", 5, false)"""))
    }

    @Test
    fun `error carries its message`() {
        assertEquals(
            CallEngineEvent.Failed("join: bad appid"),
            EngineBridge.parse("""{"type":"error","message":"join: bad appid"}"""),
        )
    }

    @Test
    fun `device lists cross the bridge with their labels and current choice`() {
        val event = EngineBridge.parse(
            """{"type":"devices","microphones":[{"id":"m1","label":"Built-in"},{"id":"m2","label":"Headset"}],""" +
                """"speakers":[{"id":"s1","label":"Display"}],"cameras":[],""" +
                """"microphoneId":"m2","speakerId":""}""",
        ) as CallEngineEvent.Devices

        assertEquals(listOf("m1", "m2"), event.microphones.map { it.id })
        assertEquals("Headset", event.microphones[1].label)
        assertEquals(listOf("s1"), event.speakers.map { it.id })
        assertTrue(event.cameras.isEmpty())
        assertEquals("m2", event.microphoneId)
        // Blank is the OS default, not a missing field.
        assertEquals("", event.speakerId)
    }

    @Test
    fun `a device with no id is dropped and an unlabelled one still shows`() {
        val event = EngineBridge.parse(
            """{"type":"devices","microphones":[{"id":"","label":"ghost"},{"id":"m1"}],""" +
                """"speakers":[],"cameras":[],"microphoneId":"","speakerId":""}""",
        ) as CallEngineEvent.Devices

        // An id is the only thing that can be selected; a row without one
        // could be offered but never chosen.
        assertEquals(listOf("m1"), event.microphones.map { it.id })
        // Labels are withheld until media permission is granted, so a blank
        // one is a normal state rather than a broken device.
        assertEquals("Unnamed device", event.microphones.single().displayName)
    }

    @Test
    fun `a finished recording names its container and its length`() {
        val done = EngineBridge.recordingDone(
            """{"type":"recording-done","mime":"audio/mp4;codecs=mp4a.40.2","ext":"m4a","duration":91500}""",
        )

        assertEquals("m4a", done?.extension)
        assertEquals(91_500L, done?.durationMillis)
        // The codec parameters are the recorder's business; `content_type` on
        // the chat wire is what receivers match, and they match the type alone.
        assertEquals("audio/mp4", done?.contentType)
    }

    @Test
    fun `a bare done frame is read as the WebM it would have been`() {
        // Pages older than the MP4 negotiation sent the frame with no fields.
        // Guessing MP4 there would name a WebM file `.m4a` and post it as one.
        val done = EngineBridge.recordingDone("""{"type":"recording-done"}""")

        assertEquals("audio/webm", done?.contentType)
        assertEquals("webm", done?.extension)
        assertEquals(0L, done?.durationMillis)
    }

    @Test
    fun `a chunk is not a completion`() {
        assertNull(EngineBridge.recordingDone("""{"type":"recording-chunk","data":"AAAA"}"""))
        assertEquals("AAAA", EngineBridge.recordingChunk("""{"type":"recording-chunk","data":"AAAA"}"""))
    }

    @Test
    fun `a chosen share source rides across as a quoted string`() {
        assertEquals(
            """zillitCall.startScreenShare("window:187:0")""",
            EngineBridge.startScreenShareScript("window:187:0"),
        )
        // No choice is the whole desktop, which is what the page does with a
        // null — not the string "null", which would be a source id that
        // matches nothing.
        assertEquals("zillitCall.startScreenShare(null)", EngineBridge.startScreenShareScript(null))
    }

    @Test
    fun `a source id cannot break out of its own string`() {
        // Source ids come from outside this process. One containing a quote
        // must arrive as that text rather than becoming an injection into our
        // own page — so the argument has to survive a round trip unchanged.
        val hostile = """screen:1:0"); alert("x"""
        val script = EngineBridge.startScreenShareScript(hostile)

        val argument = script.removePrefix("zillitCall.startScreenShare(").removeSuffix(")")
        assertEquals(hostile, Json.decodeFromString(String.serializer(), argument))
    }

    @Test
    fun `a page warning names the step it came from`() {
        // Kotlin decides which warnings are worth a banner by matching this
        // field. call.js used to send only prose, so every Agora warning was
        // unattributable and a failed screen share on the default line told
        // the user nothing at all.
        val frame = """{"type":"warning","where":"startScreenShare","message":"boom"}"""

        assertEquals("startScreenShare", EngineBridge.warningWhere(frame))
        assertEquals("boom", EngineBridge.warning(frame))
    }

    @Test
    fun `a warning with no step is still a warning`() {
        val frame = """{"type":"warning","message":"boom"}"""

        assertEquals("boom", EngineBridge.warning(frame))
        assertNull(EngineBridge.warningWhere(frame))
    }

    @Test
    fun `a face crosses as two quoted strings`() {
        val script = EngineBridge.avatarScript("u2", "data:image/png;base64,AAAA")

        assertEquals("""zillitCall.setAvatar("u2", "data:image/png;base64,AAAA")""", script)
    }

    @Test
    fun `a face cannot break out of its own string`() {
        // Both halves come from outside: the id from the roster, the URI from
        // storage. Either one carrying a quote must stay data.
        val script = EngineBridge.avatarScript("""u2"); alert("x""", "data:image/png;base64,AA")
        val arguments = script.removePrefix("zillitCall.setAvatar(").removeSuffix(")")
        val id = arguments.substringBefore(", ")

        assertEquals("""u2"); alert("x""", Json.decodeFromString(String.serializer(), id))
    }

    /** Line 3's chat arrives on the room's data channel, attributed by the SFU. */
    @Test
    fun `a line of Line 3 chat parses with its sender and time`() {
        val event = EngineBridge.parse(
            """{"type":"lk-chat","from":"u2","name":"Aisha","id":"m1","text":"on my way","ts":1700000000000}""",
        )
        assertEquals(
            CallEngineEvent.ChatReceived("u2", "Aisha", "m1", "on my way", 1_700_000_000_000L, deleted = false),
            event,
        )
    }
}
