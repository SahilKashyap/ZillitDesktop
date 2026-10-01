package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.TicketBoard
import com.zillit.desktop.feature.costumesetsync.domain.cleaningChase
import com.zillit.desktop.feature.costumesetsync.domain.dateTimeMs
import com.zillit.desktop.feature.costumesetsync.domain.fill
import com.zillit.desktop.feature.costumesetsync.domain.fittingChase
import com.zillit.desktop.feature.costumesetsync.domain.measurementsOf
import com.zillit.desktop.feature.costumesetsync.domain.nextStage
import com.zillit.desktop.feature.costumesetsync.domain.splitDateTime
import com.zillit.desktop.feature.costumesetsync.domain.tidyLines
import com.zillit.desktop.feature.costumesetsync.domain.ticketChase
import com.zillit.desktop.feature.costumesetsync.domain.urlEncode
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowLogicTest {
    private fun rec(json: String) = Rec(Json.parseToJsonElement(json) as JsonObject)

    /** A translator that echoes the key, so a message's structure can be read off it. */
    private val say: (String) -> String = { it }

    @Test
    fun dateTimeRoundTripsThroughTheTwoFields() {
        val utc = TimeZone.UTC
        val ms = dateTimeMs("2026-03-05", "14:05", utc)
        assertEquals("2026-03-05" to "14:05", splitDateTime(ms, utc))
        assertNull(dateTimeMs("not a date", "14:05", utc))
        // A blank or odd time is midnight, a single-digit hour is padded.
        assertEquals("2026-03-05" to "00:00", splitDateTime(dateTimeMs("2026-03-05", "", utc), utc))
        assertEquals("2026-03-05" to "09:30", splitDateTime(dateTimeMs("2026-03-05", "9:30", utc), utc))
    }

    @Test
    fun tidyLinesKeepsABlankOnlyAfterText() {
        assertEquals(listOf("a", "", "b"), tidyLines(listOf("a", "", "", "b")))
        assertEquals(listOf("a"), tidyLines(listOf("", "a")))
    }

    @Test
    fun nextStageStopsAtTheEnd() {
        val stages = listOf("REQUESTED", "CLEANING", "READY")
        assertEquals("CLEANING", nextStage(stages, "REQUESTED"))
        assertNull(nextStage(stages, "READY"))
        assertNull(nextStage(stages, "NOPE"))
    }

    @Test
    fun fillReplacesNamedPlaceholders() {
        assertEquals("3/5 fitted", fill("{n}/{m} fitted", "n" to 3, "m" to 5))
    }

    @Test
    fun fittingChaseLeadsWithWhatIsStillToComeAndMarksTheMissed() {
        val today = 1_000_000L
        val fittings = listOf(
            rec("""{"_id":"a","status":"SCHEDULED","scheduled_at":${today - 10},"character":{"name":"Ann"}}"""),
            rec(
                """{"_id":"b","status":"IN_PROGRESS","scheduled_at":${today + 10},"character":{"name":"Bo"},""" +
                    """"actor":{"name":"Bea"}}""",
            ),
            rec("""{"_id":"c","status":"COMPLETED","scheduled_at":${today + 20},"character":{"name":"Cy"}}"""),
        )
        val draft = fittingChase(fittings, today, "Show", say)
        val lines = draft.body.lines().filter { it.startsWith("·") }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("Bea"), "the upcoming slot leads")
        assertTrue(lines[1].contains("csync_missed"), "the slot already gone is marked missed")
        assertTrue(draft.body.contains("csync_fitting_chase_lead_many"))
        assertTrue(draft.title.endsWith("Show"))
    }

    @Test
    fun fittingChaseWithNothingOpenSaysSo() {
        val draft = fittingChase(emptyList(), 0L, "Show", say)
        assertEquals("csync_fitting_chase_empty", draft.body)
    }

    @Test
    fun cleaningChasePutsEmergenciesFirst() {
        val tickets = listOf(
            rec("""{"status":"CLEANING","problem":"wine","costume":{"asset_number":"CST-1","name":"Coat"}}"""),
            rec(
                """{"status":"REQUESTED","problem":"mud","is_emergency":true,""" +
                    """"costume":{"asset_number":"CST-2","name":"Shirt"}}""",
            ),
        )
        val lines = cleaningChase(tickets, "Show", say) { it.orEmpty() }.body.lines().filter { it.startsWith("·") }
        assertTrue(lines[0].contains("🚨") && lines[0].contains("CST-2"))
        assertTrue(lines[1].contains("CST-1"))
    }

    @Test
    fun ticketChaseCapsTheListAndSaysHowManyMore() {
        val rows = (1..25).map {
            rec("""{"status":"OPEN","description":"d$it","costume":{"asset_number":"A$it","name":"N"}}""")
        }
        val body = ticketChase(TicketBoard.Damages, rows, "Show", say) { it.orEmpty() }.body
        assertEquals(20, body.lines().count { it.startsWith("· A") })
        assertTrue(body.contains("csync_and_n_more"))
    }

    @Test
    fun measurementsReadAnObjectOrAJsonString() {
        val asObject = rec("""{"measurements":{"chest":"40","waist":"","hips":32}}""")
        assertEquals(listOf("chest" to "40", "hips" to "32"), measurementsOf(asObject))
        val asString = rec("""{"measurements":"{\"chest\":\"40\"}"}""")
        assertEquals(listOf("chest" to "40"), measurementsOf(asString))
        assertTrue(measurementsOf(rec("""{"measurements":"junk"}""")).isEmpty())
        assertTrue(measurementsOf(null).isEmpty())
    }

    @Test
    fun urlEncodePercentEncodesNonAscii() {
        assertEquals("a%20b%0A%C3%A9", urlEncode("a b\né"))
        assertEquals("safe-_.~", urlEncode("safe-_.~"))
    }
}
