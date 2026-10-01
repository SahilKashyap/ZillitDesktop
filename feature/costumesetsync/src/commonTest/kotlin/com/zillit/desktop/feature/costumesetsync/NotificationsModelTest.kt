package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.domain.NotificationsModel
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun rec(json: String) = Rec(Json.parseToJsonElement(json).jsonObject)

private fun n(entityType: String?, entityId: String?, type: String = "GENERAL"): Rec =
    rec("""{"type":"$type"${entityType?.let { ""","entity_type":"$it"""" } ?: ""}${entityId?.let { ""","entity_id":"$it"""" } ?: ""}}""")

class NotificationsModelTest {
    @Test
    fun `a costume cleaning or fitting opens its own page`() {
        assertEquals("costumes/c1", NotificationsModel.target(n("COSTUME", "c1")))
        assertEquals("cleaning/k1", NotificationsModel.target(n("CLEANING", "k1")))
        assertEquals("fittings/f1", NotificationsModel.target(n("FITTING", "f1")))
    }

    @Test
    fun `list page records open their list`() {
        assertEquals("alterations", NotificationsModel.target(n("ALTERATION", "a1")))
        assertEquals("vendors", NotificationsModel.target(n("RENTAL", "r1")))
        assertEquals("budget", NotificationsModel.target(n("BUDGET", null)))
    }

    @Test
    fun `a chat message opens the record chat`() {
        assertEquals("fittings/f1?chat=f1", NotificationsModel.target(n("FITTING", "f1", "CHAT")))
        assertEquals("alterations?chat=a1", NotificationsModel.target(n("ALTERATION", "a1", "CHAT")))
        assertEquals("budget?chat=e1", NotificationsModel.target(n("EXPENSE", "e1", "CHAT")))
    }

    @Test
    fun `a kind with no page goes nowhere`() {
        assertNull(NotificationsModel.target(n("READINESS", "x")))
        assertNull(NotificationsModel.target(n(null, null)))
    }

    @Test
    fun `the timestamp reads whichever name the service used`() {
        assertEquals(5L, NotificationsModel.time(rec("""{"created":5,"created_at":6}""")))
        assertEquals(6L, NotificationsModel.time(rec("""{"created_at":6}""")))
        assertEquals(0L, NotificationsModel.time(rec("{}")))
    }

    @Test
    fun `the badge caps at 99`() {
        assertEquals("", NotificationsModel.badge(0))
        assertEquals("7", NotificationsModel.badge(7))
        assertEquals("99", NotificationsModel.badge(99))
        assertEquals("99+", NotificationsModel.badge(100))
    }

    @Test
    fun `search matches scenes by number prefix and characters by cast number`() {
        val scenes = listOf(rec("""{"_id":"s1","number":"12","name":"Kitchen"}"""), rec("""{"_id":"s2","number":"3","location":"Garden 12"}"""))
        assertEquals(listOf("ss1", "ss2"), NotificationsModel.sceneHits(scenes, "12", "Sc", "Scene").map { it.key })
        val chars = listOf(rec("""{"_id":"c1","name":"Meera","cast_number":4}"""))
        assertEquals("4. Meera", NotificationsModel.characterHits(chars, "4", "Character").single().label)
    }
}
