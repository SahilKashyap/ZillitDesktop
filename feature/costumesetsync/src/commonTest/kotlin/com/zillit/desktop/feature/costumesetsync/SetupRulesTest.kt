package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.domain.DayKeys
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SetupRules
import com.zillit.desktop.feature.costumesetsync.domain.SyncProject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun rec(json: String) = Rec(Json.parseToJsonElement(json).jsonObject)

/** The web's `__tests__/setup.test.js`: when the form shows, who may set up, and what Create saves. */
class SetupRulesTest {
    @Test
    fun `not set up means the counts are all zero, members ignored`() {
        assertTrue(
            SyncProject(
                rec("""{"counts":{"scenes":0,"characters":0,"costumes":0,"actors":0,"members":3}}"""),
                emptySet(),
            ).notSetUp,
        )
        assertFalse(
            SyncProject(rec("""{"counts":{"scenes":1,"characters":0,"costumes":0,"actors":0}}"""), emptySet()).notSetUp,
        )
        assertFalse(
            SyncProject(rec("""{"counts":{"scenes":0,"characters":0,"costumes":0,"actors":2}}"""), emptySet()).notSetUp,
        )
    }

    @Test
    fun `never not-set-up without a project record`() {
        assertFalse(SyncProject(null, emptySet()).notSetUp)
        assertFalse(SyncProject(rec("{}"), emptySet()).notSetUp)
    }

    @Test
    fun `setup needs posting rights and nothing else, the in-tool role plays no part`() {
        for (role in listOf("ADMIN", "VIEWER", "", "COSTUME_ASSISTANT")) {
            val project = SyncProject(rec("""{"my_role":"$role"}"""), emptySet())
            assertTrue(project.canSetUp(true), role)
            assertFalse(project.canSetUp(false), role)
        }
        assertTrue(SyncProject(null, emptySet()).canSetUp(true))
    }

    @Test
    fun `the type is asked only when Zillit does not already say it`() {
        assertFalse(SetupRules.needsType("FEATURE"))
        assertFalse(SetupRules.needsType("EPISODIC"))
        assertTrue(SetupRules.needsType(""))
        assertTrue(SetupRules.needsType(null))
        assertTrue(SetupRules.needsType("SHORT"))
    }

    @Test
    fun `nothing is saved when no type was asked and every date is blank`() {
        assertTrue(SetupRules.payload(null, emptyMap()).isEmpty())
        assertTrue(SetupRules.payload(null, mapOf("start_date" to "", "end_date" to "garbage")).isEmpty())
    }

    @Test
    fun `the asked type is saved on its own when no date was entered`() {
        val body = SetupRules.payload("EPISODIC", emptyMap())
        assertEquals(listOf("type"), body.keys.toList())
        assertEquals("EPISODIC", (body.getValue("type") as JsonPrimitive).content)
    }

    @Test
    fun `all six dates are saved once any is entered, at local midnight, 0 for the rest`() {
        val body = SetupRules.payload(null, mapOf("start_date" to "2026-11-02"))
        assertEquals(SetupRules.DATE_KEYS.sorted(), body.keys.sorted())
        assertEquals(DayKeys.toMs("2026-11-02"), (body.getValue("start_date") as JsonPrimitive).content.toLong())
        assertEquals("0", (body.getValue("end_date") as JsonPrimitive).content)
        assertEquals("0", (body.getValue("prep_wrap_date") as JsonPrimitive).content)
    }

    @Test
    fun `the type and the dates travel together`() {
        val body = SetupRules.payload("FEATURE", mapOf("wrap_date" to "2026-12-01"))
        assertEquals(7, body.size)
        assertEquals("FEATURE", (body.getValue("type") as JsonPrimitive).content)
    }
}
