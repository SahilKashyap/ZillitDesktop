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
        // A Line 3 row with no device id: exactly the row that used to be
        // unclickable. It still redials, by person.
        val row = entry("c1", CallLine.Three, "u1").copy(peerDeviceId = "")
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
        onAllNodesWithText("Line 1").assertCountEquals(1)
        // "Line 3" is both the row's tag and the menu's entry.
        onAllNodesWithText("Line 3").assertCountEquals(2)
        onNodeWithText("Line 1").performClick()
        waitForIdle()
        assertEquals(listOf<CallLogEvent>(CallLogEvent.Redial(row, CallLine.One)), events)
    }
}
