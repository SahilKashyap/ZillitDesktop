package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.domain.CastProblem
import com.zillit.desktop.feature.costumesetsync.domain.DayParts
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SceneDraft
import com.zillit.desktop.feature.costumesetsync.domain.castLabel
import com.zillit.desktop.feature.costumesetsync.domain.castMembersOf
import com.zillit.desktop.feature.costumesetsync.domain.castNumberProblem
import com.zillit.desktop.feature.costumesetsync.domain.castNumbersOf
import com.zillit.desktop.feature.costumesetsync.domain.changeLabel
import com.zillit.desktop.feature.costumesetsync.domain.changedFields
import com.zillit.desktop.feature.costumesetsync.domain.dateKey
import com.zillit.desktop.feature.costumesetsync.domain.dateMs
import com.zillit.desktop.feature.costumesetsync.domain.editedName
import com.zillit.desktop.feature.costumesetsync.domain.emptyDraft
import com.zillit.desktop.feature.costumesetsync.domain.forceImport
import com.zillit.desktop.feature.costumesetsync.domain.joinScriptDay
import com.zillit.desktop.feature.costumesetsync.domain.namesOf
import com.zillit.desktop.feature.costumesetsync.domain.parseScriptDay
import com.zillit.desktop.feature.costumesetsync.domain.planSaveOrder
import com.zillit.desktop.feature.costumesetsync.domain.readinessOf
import com.zillit.desktop.feature.costumesetsync.domain.resolveCast
import com.zillit.desktop.feature.costumesetsync.domain.sceneName
import com.zillit.desktop.feature.costumesetsync.domain.scriptLoc
import com.zillit.desktop.feature.costumesetsync.domain.slugOf
import com.zillit.desktop.feature.costumesetsync.domain.sortByCast
import com.zillit.desktop.feature.costumesetsync.domain.toDraft
import com.zillit.desktop.feature.costumesetsync.domain.truncate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun sceneRec(json: String): Rec = Rec(Json.parseToJsonElement(json) as JsonObject)

class SceneDraftTest {

    @Test
    fun scriptDaySplitsPrefixFromNumber() {
        assertEquals(DayParts("Day", "3"), parseScriptDay("Day 3"))
        assertEquals(DayParts("Night", "12"), parseScriptDay("NIGHT 12"))
    }

    @Test
    fun scriptDayReadsTheShortForms() {
        assertEquals(DayParts("Day", "3"), parseScriptDay("D3"))
        assertEquals(DayParts("Night", "12"), parseScriptDay("n12"))
        assertEquals(DayParts("Day", "4"), parseScriptDay("Day-4"))
    }

    @Test
    fun scriptDayKeepsUnknownTextWithBlankPrefix() {
        assertEquals(DayParts("", "Flashback A"), parseScriptDay("Flashback A"))
        assertEquals(DayParts("", ""), parseScriptDay(""))
    }

    @Test
    fun scriptDayRoundTrips() {
        assertEquals("Day 3", joinScriptDay("Day", "3"))
        assertEquals("Flashback A", joinScriptDay("", "Flashback A"))
        assertEquals("", joinScriptDay("Night", ""))
    }

    @Test
    fun zeroIsNoDateNot1970() {
        assertEquals("", dateKey(0))
        assertEquals("", dateKey(null))
        assertEquals(0L, dateMs(""))
        assertEquals(0L, dateMs("garbage"))
    }

    @Test
    fun dateRoundTripsThroughLocalMidnight() {
        assertEquals("2026-09-30", dateKey(dateMs("2026-09-30")))
    }

    @Test
    fun castNumberAcceptsBlankAndWholeNumbers() {
        assertNull(castNumberProblem(""))
        assertNull(castNumberProblem("12"))
        assertNull(castNumberProblem("0"))
    }

    @Test
    fun castNumberRejectsAnythingElse() {
        assertEquals(CastProblem.NotWhole, castNumberProblem("1.5"))
        assertEquals(CastProblem.NotWhole, castNumberProblem("-2"))
        assertEquals(CastProblem.NotWhole, castNumberProblem("abc"))
        assertEquals(CastProblem.TooBig, castNumberProblem("2147483648"))
        assertEquals(CastProblem.TooBig, castNumberProblem("99999999999999999999"))
    }

    private fun numbers(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    @Test
    fun saveOrderSavesARowAfterTheRowHoldingItsNewNumber() {
        val drafts = mapOf("a" to SceneDraft(number = "6"), "b" to SceneDraft(number = "7"))
        val order = planSaveOrder(listOf("a", "b"), drafts, numbers("a" to "5", "b" to "6")).map { it.key }
        assertTrue(order.indexOf("b") < order.indexOf("a"))
    }

    @Test
    fun saveOrderBreaksASwapWithOneTemporaryNumber() {
        val drafts = mapOf("a" to SceneDraft(number = "6"), "b" to SceneDraft(number = "5"))
        val steps = planSaveOrder(listOf("a", "b"), drafts, numbers("a" to "5", "b" to "6"))
        assertEquals(1, steps.count { it.tempNumber != null })
        assertEquals(listOf("a", "b"), steps.filter { it.tempNumber == null }.map { it.key }.sorted())
    }

    @Test
    fun saveOrderLeavesUntangledRowsInOnePass() {
        val drafts = mapOf("a" to SceneDraft(number = "1"), "b" to SceneDraft(number = "2"))
        val steps = planSaveOrder(listOf("a", "b"), drafts, numbers("a" to "1", "b" to "2"))
        assertEquals(2, steps.size)
        assertTrue(steps.all { it.tempNumber == null })
    }

    @Test
    fun toDraftFlattensASceneAndStartsWithNoCastEdits() {
        val d = toDraft(
            sceneRec(
                """{"number":"12A","episode":"","script_day":"Day 2","int_ext":"INT","location":"Kitchen",
                "synopsis":"Tea.",
                |"shoot_date":0,"characters":[{"character_id":"c1"},{"character_id":"c2"}]}""".trimMargin(),
            ),
        )
        assertEquals("12A", d.number)
        assertEquals("Day", d.dayPrefix)
        assertEquals("2", d.dayN)
        assertEquals("", d.shootDate)
        assertEquals(listOf("c1", "c2"), d.principals)
        assertTrue(d.cast.isEmpty())
        assertTrue(emptyDraft().principals.isEmpty())
    }

    private val byId = mapOf(
        "c1" to sceneRec("""{"_id":"c1","name":"Raj","cast_number":1,"actor":{"name":"Arjun"}}"""),
        "c2" to sceneRec("""{"_id":"c2","name":"Priya","cast_number":null}"""),
    )
    private val chars = listOf(
        sceneRec("""{"character_id":"c2","character":{"name":"Priya","cast_number":null}}"""),
        sceneRec("""{"character_id":"c1","character":{"name":"Raj","cast_number":1}}"""),
    )

    @Test
    fun sortsByCastNumberUnnumberedLast() {
        val sorted = sortByCast(listOf(sceneRec("""{"name":"B"}"""), sceneRec("""{"name":"A","cast_number":2}""")))
        assertEquals(listOf("A", "B"), sorted.map { it.str("name") })
    }

    @Test
    fun readsCharactersInCastOrder() {
        assertEquals("Raj, Priya", namesOf(resolveCast(chars, byId)).text)
        assertEquals("1", castNumbersOf(resolveCast(chars, byId)).text)
        assertEquals("Arjun", castMembersOf(resolveCast(chars, byId)).text)
    }

    @Test
    fun buildsScriptLocationAndChangeLabel() {
        assertEquals("INT. KITCHEN", scriptLoc(sceneRec("""{"int_ext":"INT","location":"kitchen"}""")))
        assertEquals("#3 Rain coat", changeLabel(sceneRec("""{"change_number":3,"name":"Rain coat"}""")))
        assertEquals("", changeLabel(null))
        assertEquals("3. Priya", castLabel(sceneRec("""{"cast_number":3,"name":"Priya"}""")))
        assertEquals("Priya", castLabel(sceneRec("""{"cast_number":null,"name":"Priya"}""")))
    }

    @Test
    fun truncatesWithAnEllipsis() {
        assertEquals(60, truncate("x".repeat(80)).length)
        assertEquals("short", truncate("short"))
    }

    @Test
    fun readinessIsUnassignedUntilALookIsOnTheRow() {
        assertEquals("NOT_ASSIGNED", readinessOf(sceneRec("""{"change":null}""")))
    }

    @Test
    fun readinessReportsTheWorstOfTheFourProblemStatuses() {
        val worse = sceneRec(
            """{"change":{"items":[{"costume":{"status":"CLEANING"}},{"costume":{"status":"MISSING"}}]}}""",
        )
        assertEquals("MISSING", readinessOf(worse))
        val ready = sceneRec("""{"change":{"items":[{"costume":{"status":"READY"}}]}}""")
        assertEquals("READY", readinessOf(ready))
    }

    @Test
    fun aLookWithNoPiecesReadsReady() {
        assertEquals("READY", readinessOf(sceneRec("""{"change":{"items":[]}}""")))
        val retired = sceneRec(
            """{"change":{"items":[{"costume":{"status":"RETIRED"}},{"costume":{"status":"RETURNED_TO_VENDOR"}}]}}""",
        )
        assertEquals("READY", readinessOf(retired))
    }

    @Test
    fun editedLocationRenamesTheSceneLocationDashTime() {
        val original = sceneRec("""{"time_of_day":"NIGHT","name":"Old"}""")
        assertEquals("Kitchen - Night", sceneName(SceneDraft(location = " Kitchen "), original))
        assertEquals("Flashback", sceneName(SceneDraft(location = ""), sceneRec("""{"name":"Flashback"}""")))
        assertEquals("Roof", sceneName(SceneDraft(location = "Roof"), null))
    }

    private val reviewed = sceneRec(
        """{"number":"4","name":"Kitchen - Day","int_ext":"INT","location":"Kitchen","time_of_day":"DAY",
        "script_day":"Day 1",
        |"pages":"1","synopsis":"Tea.","previous":{"int_ext":"INT","location":"kitchen ","time_of_day":"DAY",
        "script_day":"Day 1",
        |"pages":"1","synopsis":"Tea."}}""".trimMargin(),
    )

    @Test
    fun scriptReviewBuildsTheSlugline() {
        assertEquals("INT. Kitchen", slugOf(reviewed))
        assertEquals("Roof", slugOf(sceneRec("""{"location":"Roof"}""")))
        assertEquals("", slugOf(null))
    }

    @Test
    fun scriptReviewIgnoresCaseAndSpacing() {
        assertTrue(changedFields(reviewed).isEmpty())
    }

    @Test
    fun scriptReviewFlagsWhatTheScriptChanges() {
        val moved = sceneRec("""{"int_ext":"EXT","previous":{"int_ext":"INT"}}""")
        assertEquals(listOf("int_ext"), changedFields(moved))
        assertTrue(changedFields(sceneRec("""{"int_ext":"INT"}""")).isEmpty())
    }

    @Test
    fun scriptReviewForcesARowThatIsEditedOrDiffers() {
        assertFalse(forceImport(reviewed, false))
        assertTrue(forceImport(reviewed, true))
        assertTrue(forceImport(sceneRec("""{"pages":"2","previous":{"pages":"1"}}"""), false))
    }

    @Test
    fun scriptReviewRenamesASceneWhoseLocationOrTimeWasCorrected() {
        assertEquals("Kitchen - Day", editedName(reviewed, sceneRec("""{"location":"Kitchen","time_of_day":"DAY"}""")))
        assertEquals(
            "Garden - Night",
            editedName(reviewed, sceneRec("""{"location":"Garden","time_of_day":"NIGHT"}""")),
        )
        assertEquals(
            "Tea time",
            editedName(
                sceneRec("""{"name":"Tea time","location":"Kitchen","time_of_day":"DAY"}"""),
                sceneRec("""{"location":"kitchen","time_of_day":"DAY"}"""),
            ),
        )
        assertNotNull(editedName(reviewed, null))
    }
}
