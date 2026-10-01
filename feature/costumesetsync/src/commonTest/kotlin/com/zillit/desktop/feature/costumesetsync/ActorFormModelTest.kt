package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.screens.ActorFormState
import com.zillit.desktop.feature.costumesetsync.ui.screens.CastChange
import com.zillit.desktop.feature.costumesetsync.ui.screens.LabelValue
import com.zillit.desktop.feature.costumesetsync.ui.screens.actorDateMs
import com.zillit.desktop.feature.costumesetsync.ui.screens.actorDateText
import com.zillit.desktop.feature.costumesetsync.ui.screens.actorTimeText
import com.zillit.desktop.feature.costumesetsync.ui.screens.charLabel
import com.zillit.desktop.feature.costumesetsync.ui.screens.newActorForm
import com.zillit.desktop.feature.costumesetsync.ui.screens.toActorBody
import com.zillit.desktop.feature.costumesetsync.ui.screens.toActorForm
import com.zillit.desktop.feature.costumesetsync.ui.screens.typedCastNumber
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActorFormModelTest {
    private val arjun = Rec(
        Json.parseToJsonElement(
            """{"_id":"a1","name":"Arjun Kapoor","gender":"","age":null,"phone":"+91 98200 11223","phone2":"",
            "email":"arjun@talent.in","email2":"","agency":"Yash Talent","talent_rep":"","talent_rep_email":"",
            "talent_rep_phone":"","talent_rep_details":[],"start_work_date":0,"next_fitting_at":0,"fitting_comment":"",
            "notes":"","measurements":{"chest":"40","height":"5'11","thigh":"22"},
            "characters":[{"_id":"c1","name":"Raj Malhotra","cast_number":1}]}""",
        ).jsonObject,
    )

    @Test
    fun nameSplitsOnFirstSpace() {
        val f = toActorForm(Rec(Json.parseToJsonElement("""{"name":"Ana de Armas"}""").jsonObject))
        assertEquals("Ana", f.first)
        assertEquals("de Armas", f.last)
    }

    @Test
    fun measurementsSplitStandardFromNamed() {
        val f = toActorForm(arjun)
        assertEquals(mapOf("chest" to "40", "height" to "5'11"), f.measurements)
        assertEquals(listOf(LabelValue("thigh", "22")), f.extraMeasures)
        assertEquals(listOf("c1"), f.characterIds)
    }

    @Test
    fun blankFormForNoActor() {
        assertEquals(ActorFormState(), toActorForm(null))
        assertEquals(listOf("c1"), newActorForm("c1").characterIds)
        assertEquals(ActorFormState(), newActorForm())
    }

    @Test
    fun untouchedSaveRoundTrips() {
        val body = toActorBody(toActorForm(arjun))
        assertEquals("Arjun Kapoor", body["name"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, body["age"])
        assertEquals("", body["email2"]?.jsonPrimitive?.content)
        assertEquals(0L, body["next_fitting_at"]?.jsonPrimitive?.content?.toLong())
        assertEquals(JsonArray(listOf(JsonPrimitive("c1"))), body["character_ids"])
        assertEquals("""{"chest":"40","height":"5'11","thigh":"22"}""", body["measurements"].toString())
    }

    @Test
    fun blankRowsAreDropped() {
        val f = ActorFormState(
            first = "Anya",
            measurements = mapOf("chest" to "34", "waist" to ""),
            extraMeasures = listOf(LabelValue("", "9"), LabelValue("Neck", " ")),
            talentRepDetails = listOf(LabelValue(" Assistant ", " Kavya "), LabelValue("", "x")),
        )
        val body = toActorBody(f)
        assertEquals("""{"chest":"34"}""", body["measurements"].toString())
        assertEquals("""[{"label":"Assistant","value":"Kavya"}]""", body["talent_rep_details"].toString())
    }

    @Test
    fun ageIsANumberOrNull() {
        assertEquals(31L, toActorBody(ActorFormState(first = "A", age = "31"))["age"]?.jsonPrimitive?.content?.toLong())
        assertEquals(JsonNull, toActorBody(ActorFormState(first = "A", age = ""))["age"])
    }

    @Test
    fun castNumbers() {
        assertEquals(CastChange.Unchanged, typedCastNumber("3", 3))
        assertEquals(CastChange.Unchanged, typedCastNumber("", null))
        assertEquals(CastChange.To(null), typedCastNumber("", 3))
        assertEquals(CastChange.To(0), typedCastNumber("0", null))
        assertEquals(CastChange.To(12), typedCastNumber("12", 3))
        assertEquals(CastChange.Invalid, typedCastNumber("1.5", null))
        assertEquals(CastChange.Invalid, typedCastNumber("-1", null))
        assertEquals(CastChange.Invalid, typedCastNumber("x", null))
    }

    @Test
    fun charLabelPrefixesCastNumber() {
        assertEquals(
            "(2) Priya",
            charLabel(Rec(Json.parseToJsonElement("""{"name":"Priya","cast_number":2}""").jsonObject)),
        )
        assertEquals(
            "Extra",
            charLabel(Rec(Json.parseToJsonElement("""{"name":"Extra","cast_number":null}""").jsonObject)),
        )
    }

    @Test
    fun dateRoundTrip() {
        val utc = TimeZone.UTC
        val ms = actorDateMs("2026-03-12", "14:30", utc)
        assertEquals("2026-03-12", actorDateText(ms, utc))
        assertEquals("14:30", actorTimeText(ms, utc))
        assertEquals(0L, actorDateMs("", "14:30", utc))
        assertTrue(actorDateMs("2026-03-12", "", utc) < ms)
    }
}
