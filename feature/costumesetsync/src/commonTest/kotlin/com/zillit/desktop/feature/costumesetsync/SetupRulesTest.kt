package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.domain.DayKeys
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SetupRules
import com.zillit.desktop.feature.costumesetsync.domain.SyncProject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun rec(json: String) = Rec(Json.parseToJsonElement(json).jsonObject)

class SetupRulesTest {
    @Test
    fun `a known type skips the type step`() {
        assertEquals(listOf("dates", "script"), SetupRules.steps("FEATURE"))
        assertEquals(listOf("dates", "script"), SetupRules.steps("EPISODIC"))
        assertEquals(listOf("type", "dates", "script"), SetupRules.steps(null))
        assertEquals(listOf("type", "dates", "script"), SetupRules.steps("OTHER"))
    }

    @Test
    fun `dates are sent as local midnight with 0 for not set`() {
        val body = SetupRules.datesBody(mapOf("start_date" to "2026-10-01", "end_date" to ""))
        assertEquals(DayKeys.toMs("2026-10-01"), body.getValue("start_date"))
        assertEquals(0L, body.getValue("end_date"))
        assertEquals(0L, body.getValue("wrap_date"))
        assertEquals(6, body.size)
    }

    @Test
    fun `saved dates come back as day keys`() {
        val saved = SetupRules.savedDates(rec("""{"start_date":${DayKeys.toMs("2026-10-01")},"wrap_date":0}"""))
        assertEquals("2026-10-01", saved.getValue("start_date"))
        assertEquals("", saved.getValue("wrap_date"))
    }

    @Test
    fun `not set up means the counts are all zero, never an unanswered record`() {
        assertFalse(SyncProject(null, emptySet()).notSetUp)
        assertTrue(
            SyncProject(
                rec("""{"counts":{"scenes":0,"characters":0,"costumes":0,"actors":0,"members":3}}"""),
                emptySet(),
            ).notSetUp,
        )
        assertFalse(
            SyncProject(rec("""{"counts":{"scenes":2,"characters":0,"costumes":0,"actors":0}}"""), emptySet()).notSetUp,
        )
    }

    @Test
    fun `setup needs posting rights and a setup role`() {
        val project = SyncProject(rec("""{"my_role":"PRODUCTION_MANAGER"}"""), emptySet())
        assertTrue(project.canSetUp(true))
        assertFalse(project.canSetUp(false))
        assertFalse(SyncProject(rec("""{"my_role":"VIEWER"}"""), emptySet()).canSetUp(true))
    }
}
