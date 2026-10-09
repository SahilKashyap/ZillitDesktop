package com.zillit.desktop.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Remote Config's `calling_lines`, against the scenarios product sent with it
 * (the numbered tests) and the `platforms` addition (P1–P5).
 */
class CallingLinesTest {

    private val live = """
        {"lines": [
          {"engine": "livekit",   "order": 1, "enabled": true,  "projects": ["All"],
           "platforms": ["android", "ios", "web", "desktop"]},
          {"engine": "agora",     "order": 2, "enabled": true,  "projects": ["All"],
           "platforms": ["android", "ios", "web", "desktop"]},
          {"engine": "mediasoup", "order": 3, "enabled": false, "projects": [],
           "platforms": ["android", "ios", "web", "desktop"]}
        ]}
    """.trimIndent()

    private fun policy(raw: String?, legacy: LegacyCallingFlags = LegacyCallingFlags()) =
        CallingLinesPolicy(CallingLinesConfig.parse(raw), legacy)

    private fun line(engine: String, order: Int, extra: String = "") =
        """{"engine":"$engine","order":$order$extra}"""

    private fun lines(vararg entries: String) = """{"lines":[${entries.joinToString(",")}]}"""

    @Test
    fun `1 - the live value offers LiveKit as Line 1 and Agora as Line 2, mediasoup hidden`() {
        val policy = policy(live)
        assertEquals(listOf(CallEngines.LIVEKIT, CallEngines.AGORA), policy.available("p1"))
        assertEquals(1, policy.numberOf(CallEngines.LIVEKIT))
        assertEquals(2, policy.numberOf(CallEngines.AGORA))
    }

    @Test
    fun `2 - swapped orders reorder the picker and each line keeps its engine`() {
        val policy = policy(lines(line("livekit", 3), line("agora", 2), line("mediasoup", 1)))
        assertEquals(listOf(CallEngines.MEDIASOUP, CallEngines.AGORA, CallEngines.LIVEKIT), policy.available("p1"))
        assertEquals(1, policy.numberOf(CallEngines.MEDIASOUP))
        assertEquals(3, policy.numberOf(CallEngines.LIVEKIT))
    }

    @Test
    fun `3 - a line listing other productions is hidden here and shown there`() {
        val policy = policy(lines(line("agora", 1, ""","projects":["p2","p3"]""")))
        assertFalse(policy.isAvailable(CallEngines.AGORA, "p1"))
        assertTrue(policy.isAvailable(CallEngines.AGORA, "p2"))
        assertFalse(policy.isAvailable(CallEngines.AGORA, null))
    }

    @Test
    fun `projects matches All in any case`() {
        assertTrue(policy(lines(line("agora", 1, ""","projects":["aLL"]"""))).isAvailable(CallEngines.AGORA, "p1"))
    }

    @Test
    fun `4 - every line off leaves nothing to offer`() {
        val off = ""","enabled":false"""
        val policy = policy(lines(line("livekit", 1, off), line("agora", 2, off), line("mediasoup", 3, off)))
        assertEquals(emptyList(), policy.available("p1"))
    }

    @Test
    fun `5 - absent or broken, the key configures nothing and the older flags decide`() {
        for (raw in listOf(null, "", "  ", "not json", "{}", """{"lines":{}}""", """{"lines":[]}""", "[1,2]")) {
            assertNull(CallingLinesConfig.parse(raw), "for <$raw>")
        }
        // Today's desktop: Agora and mediasoup, LiveKit only where line_three_enabled_in names the production.
        val legacy = LegacyCallingFlags(lineThreeIn = listOf("p1"))
        assertEquals(
            listOf(CallEngines.LIVEKIT, CallEngines.AGORA, CallEngines.MEDIASOUP),
            policy("not json", legacy).available("p1"),
        )
        assertEquals(listOf(CallEngines.AGORA, CallEngines.MEDIASOUP), policy(null, legacy).available("p2"))
    }

    @Test
    fun `5 - the older flags still switch lines off when set`() {
        val entries = mapOf(
            LegacyCallingFlags.CALLING_ENABLED to "true",
            LegacyCallingFlags.LINE1_ENABLED to "\"false\"",
            LegacyCallingFlags.LINE_THREE_ENABLED_IN to """["All"]""",
        )
        val policy = policy(null, LegacyCallingFlags.from(entries))
        assertEquals(listOf(CallEngines.LIVEKIT, CallEngines.AGORA), policy.available("p1"))
        val callingOff = LegacyCallingFlags.from(mapOf(LegacyCallingFlags.CALLING_ENABLED to "false"))
        assertEquals(emptyList(), policy(null, callingOff).available("p1"))
    }

    @Test
    fun `6 - an unknown engine is ignored`() {
        val config = CallingLinesConfig.parse(lines(line("webrtc", 4), line("agora", 2)))
        assertEquals(listOf(CallEngines.AGORA), config?.lines?.map { it.engine })
        assertNull(CallingLinesConfig.parse(lines(line("webrtc", 4))))
    }

    @Test
    fun `7 - an engine listed twice takes its first entry`() {
        val policy = policy(lines(line("agora", 2), line("AGORA ", 5, ""","enabled":false""")))
        assertEquals(2, policy.numberOf(CallEngines.AGORA))
        assertTrue(policy.isAvailable(CallEngines.AGORA, "p1"))
    }

    @Test
    fun `8 - an entry with no order, or order 0 or less, is skipped`() {
        val config = CallingLinesConfig.parse(
            lines("""{"engine":"livekit"}""", line("agora", 0), line("mediasoup", -1), line("livekit", 2)),
        )
        assertNull(config?.lineFor(CallEngines.AGORA))
        assertNull(config?.lineFor(CallEngines.MEDIASOUP))
        // The first livekit entry had no order, so it never counted; the second is the one.
        assertEquals(2, config?.lineFor(CallEngines.LIVEKIT)?.order)
    }

    @Test
    fun `defaults - enabled true, projects All, every platform`() {
        val entry = CallingLinesConfig.parse(lines(line("agora", 2)))?.lineFor(CallEngines.AGORA)
        assertEquals(
            CallingLine(CallEngines.AGORA, 2, enabled = true, projects = listOf("All"), platforms = null),
            entry,
        )
    }

    @Test
    fun `9 - history numbers - untagged is mediasoup, and an off line keeps its number`() {
        assertEquals(3, CallingLinesPolicy.numberOf(null, null))
        assertEquals(3, CallingLinesPolicy.numberOf(null, ""))
        assertEquals(1, CallingLinesPolicy.numberOf(null, CallEngines.LIVEKIT))
        assertEquals(2, CallingLinesPolicy.numberOf(null, CallEngines.AGORA))
        val config = CallingLinesConfig.parse(live)
        assertEquals(3, CallingLinesPolicy.numberOf(config, CallEngines.MEDIASOUP))
        val renumbered = CallingLinesConfig.parse(lines(line("mediasoup", 4, ""","enabled":false""")))
        assertEquals(4, CallingLinesPolicy.numberOf(renumbered, null))
        // Not listed in a configured list: the built-in number.
        assertEquals(1, CallingLinesPolicy.numberOf(renumbered, CallEngines.LIVEKIT))
    }

    @Test
    fun `a configured list switches off what it does not name`() {
        val policy = policy(lines(line("agora", 1)))
        assertFalse(policy.isAvailable(CallEngines.MEDIASOUP, "p1"))
        assertFalse(policy.isAvailable(CallEngines.LIVEKIT, "p1"))
    }

    @Test
    fun `an engine this install cannot reach is never offered, and keeps its number`() {
        val policy = CallingLinesPolicy(
            CallingLinesConfig.parse(live),
            installed = CallEngines.SUPPORTED - CallEngines.LIVEKIT,
        )
        assertEquals(listOf(CallEngines.AGORA), policy.available("p1"))
        assertEquals(1, policy.numberOf(CallEngines.LIVEKIT))
    }

    @Test
    fun `P1 - a line without desktop in platforms is hidden here`() {
        val policy = policy(lines(line("agora", 2, ""","platforms":["android","ios","web"]"""), line("livekit", 1)))
        assertEquals(listOf(CallEngines.LIVEKIT), policy.available("p1"))
        assertEquals(2, policy.numberOf(CallEngines.AGORA))
    }

    @Test
    fun `P2 - no platforms field is every platform`() {
        assertTrue(policy(lines(line("agora", 2))).isAvailable(CallEngines.AGORA, "p1"))
    }

    @Test
    fun `P3 - an empty platforms list is no platform`() {
        assertFalse(policy(lines(line("agora", 2, ""","platforms":[]"""))).isAvailable(CallEngines.AGORA, "p1"))
    }

    @Test
    fun `P4 - platform names in any case`() {
        val mixedCase = policy(lines(line("agora", 2, ""","platforms":[" Desktop "]""")))
        assertTrue(mixedCase.isAvailable(CallEngines.AGORA, "p1"))
    }

    @Test
    fun `P5 - every line hidden from desktop leaves nothing`() {
        val web = ""","platforms":["web"]"""
        assertEquals(emptyList(), policy(lines(line("livekit", 1, web), line("agora", 2, web))).available("p1"))
    }

    @Test
    fun `the console's literal quotes and string-typed fields are read`() {
        val quoted = "\"" + lines(line("agora", 2)).replace("\"", "\\\"") + "\""
        assertEquals(2, CallingLinesConfig.parse(quoted)?.lineFor(CallEngines.AGORA)?.order)
        val stringly = CallingLinesConfig.parse(lines("""{"engine":"agora","order":"2","enabled":"false"}"""))
        assertEquals(CallingLine(CallEngines.AGORA, 2, enabled = false), stringly?.lineFor(CallEngines.AGORA))
    }
}
