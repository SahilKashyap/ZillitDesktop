package com.zillit.desktop.feature.calls

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.calls.domain.CallLine
import com.zillit.desktop.feature.calls.domain.CallLogDirection
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.ui.CallLogEvent
import com.zillit.desktop.feature.calls.ui.CallLogPane
import com.zillit.desktop.feature.calls.ui.CallLogSide
import com.zillit.desktop.feature.calls.ui.callListStamp
import kotlinx.datetime.TimeZone
import com.zillit.desktop.feature.calls.ui.CallLogUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Composes the call history for real.
 *
 * What the row promises is pinned here: every entry names the line that
 * carried it, next to the name, whichever of the three it was — the reason
 * being that the lines are separate call stacks, and a list that hides which
 * one rang cannot help anyone chase a call that went wrong.
 */
@OptIn(ExperimentalTestApi::class)
class CallLogPaneRenderTest {

    private val now = 1_786_507_000_000L

    private fun entry(uuid: String, line: CallLine, peer: String) = CallLogEntry(
        callUuid = uuid,
        direction = CallLogDirection.Incoming,
        mode = CallMode.Private,
        type = CallType.Audio,
        missed = false,
        durationMillis = 61_000L,
        startedAtMillis = now - 3_600_000L,
        peerUserId = peer,
        peerDeviceId = "device-$peer",
        line = line,
    )

    private val names = mapOf("u1" to "Aisha Khan", "u2" to "Vivek Mishra", "u3" to "Priya Nair")

    @Test
    fun `each row wears the line that carried the call`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                CallLogPane(
                    state = CallLogUiState(
                        entries = listOf(
                            entry("c1", CallLine.One, "u1"),
                            entry("c2", CallLine.Two, "u2"),
                            entry("c3", CallLine.Three, "u3"),
                        ),
                    ),
                    onEvent = {},
                    nameFor = { names[it] },
                    nowMillis = now,
                )
            }
        }

        onNodeWithText("Aisha Khan").assertExists()
        onNodeWithText("Line 1").assertExists()
        onNodeWithText("Line 2").assertExists()
        onNodeWithText("Line 3").assertExists()
    }

    @Test
    fun `an older row with no line on the wire still says Line 1`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                CallLogPane(
                    state = CallLogUiState(
                        // The default — what a row read from a pre-line server
                        // record carries. See CallLine.ofWire.
                        entries = listOf(entry("c1", CallLine.ofWire(null), "u1")),
                    ),
                    onEvent = {},
                    nameFor = { names[it] },
                    nowMillis = now,
                )
            }
        }

        onAllNodesWithText("Line 1").assertCountEquals(1)
    }

    @Test
    fun `a click on a row asks which line, and the pick rings on it`() = runComposeUiTest {
        val events = mutableListOf<CallLogEvent>()
        // A LiveKit row (Line 1) with no device id: exactly the row that used
        // to be unclickable. It still redials, by person.
        val row = entry("c1", CallLine.One, "u1").copy(peerDeviceId = "")
        setContent {
            ZillitTheme {
                CallLogPane(
                    state = CallLogUiState(entries = listOf(row)),
                    onEvent = { events += it },
                    nameFor = { names[it] },
                    nowMillis = now,
                    lines = CallLine.DEFAULT + CallLine.Three,
                )
            }
        }

        onNodeWithText("Aisha Khan").performClick()
        waitForIdle()
        // The picker: the two every production has, plus the third this one does.
        onAllNodesWithText("Line 2").assertCountEquals(1)
        onAllNodesWithText("Line 3").assertCountEquals(1)
        // "Line 1" is both the row's tag and the menu's entry.
        onAllNodesWithText("Line 1").assertCountEquals(2)
        onNodeWithText("Line 3").performClick()
        waitForIdle()
        assertEquals(listOf<CallLogEvent>(CallLogEvent.Redial(row, CallLine.Three)), events)
    }

    /**
     * WhatsApp's layout, with the side pane beside the list: a row click
     * picks the call for the pane — it does not ring. Ringing is the pane's
     * Call again, or the row's hover glyph.
     */
    @Test
    fun `beside a side pane a row click picks the call instead of ringing`() = runComposeUiTest {
        val events = mutableListOf<CallLogEvent>()
        val row = entry("c1", CallLine.Two, "u1")
        setContent {
            ZillitTheme {
                CallLogPane(
                    state = CallLogUiState(entries = listOf(row)),
                    onEvent = { events += it },
                    nameFor = { names[it] },
                    nowMillis = now,
                    inlineDetail = true,
                )
            }
        }

        onNodeWithText("Aisha Khan").performClick()
        waitForIdle()
        assertEquals(listOf<CallLogEvent>(CallLogEvent.ShowDetail(row)), events)
        // No line menu opened: "Line 2" is the row's own word, once.
        onAllNodesWithText("Line 2").assertCountEquals(1)
    }

    /** Nothing picked: the invitation, and Start call hands over to the host. */
    @Test
    fun `the side pane invites until a call is picked`() = runComposeUiTest {
        var started = false
        setContent {
            ZillitTheme {
                CallLogSide(
                    state = CallLogUiState(entries = listOf(entry("c1", CallLine.Two, "u1"))),
                    onEvent = {},
                    nameFor = { names[it] },
                    nowMillis = now,
                    onStartCall = { started = true },
                )
            }
        }

        onNodeWithText("Voice and video calling").assertExists()
        onNodeWithText("Start call").performClick()
        waitForIdle()
        assertEquals(true, started)
    }

    /** A picked call: its info, and Call again asks which line before it rings. */
    @Test
    fun `a picked call shows its info and calls back on the chosen line`() = runComposeUiTest {
        val events = mutableListOf<CallLogEvent>()
        val row = entry("c1", CallLine.Two, "u1")
        setContent {
            ZillitTheme {
                CallLogSide(
                    state = CallLogUiState(entries = listOf(row), detail = row),
                    onEvent = { events += it },
                    nameFor = { names[it] },
                    nowMillis = now,
                    lines = CallLine.DEFAULT + CallLine.Three,
                )
            }
        }

        onNodeWithText("Call info").assertExists()
        onNodeWithText("Aisha Khan").assertExists()
        onNodeWithText("Call again").performClick()
        waitForIdle()
        onNodeWithText("Line 3").performClick()
        waitForIdle()
        assertEquals(listOf<CallLogEvent>(CallLogEvent.Redial(row, CallLine.Three)), events)
    }

    /** The list's stamp, WhatsApp's: clock, Yesterday, the weekday, then a date. */
    @Test
    fun `the stamp reads clock, Yesterday, weekday, then date`() {
        val zone = TimeZone.UTC
        // 2026-08-12 (a Wednesday) at 03:56 UTC.
        val day = 86_400_000L
        assertEquals("03:56", callListStamp(now, now, zone))
        assertEquals("Yesterday", callListStamp(now - day, now, zone))
        assertEquals("Sunday", callListStamp(now - 3 * day, now, zone))
        assertEquals("31 Jul", callListStamp(now - 12 * day, now, zone))
        assertEquals("12 Aug 2025", callListStamp(now - 365 * day, now, zone))
    }
}
