package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.EngineBridge
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
}
