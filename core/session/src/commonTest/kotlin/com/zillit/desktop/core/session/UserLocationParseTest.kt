package com.zillit.desktop.core.session

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `last_location` and the sharing schedule on `project/users` — what the
 * chat's contact panel opens Maps from (Android `JoinProjectResponse.kt:75-78,
 * 148`).
 */
class UserLocationParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun user(field: String) =
        json.decodeFromString(ProjectUserDto.serializer(), """{"user_id":"u1","name":"Aisha Khan"$field}""")
            .toSnapshot()!!

    @Test
    fun `a row with none of the sharing fields carries no location at all`() {
        assertNull(user("").location)
    }

    @Test
    fun `the point and the flag are read`() {
        val place = user(""","show_location":true,"last_location":{"lat":19.076,"long":72.8777}""").location!!
        assertEquals(19.076, place.latitude)
        assertEquals(72.8777, place.longitude)
        assertTrue(place.shared)
    }

    @Test
    fun `an everyday window arrives as its stamps`() {
        val place = user(
            ""","show_location":true,"location_tracking_mode":"Everyday"""" +
                ""","everyday_timing":{"start_time":1772607600000,"end_time":1772636400000}""",
        ).location!!
        assertEquals("Everyday", place.mode)
        assertEquals(1_772_607_600_000L, place.everydayStart)
        assertEquals(1_772_636_400_000L, place.everydayEnd)
    }

    @Test
    fun `show_location_timing is still read where everyday_timing is absent`() {
        // Android falls back to the older spelling in `isLocationAllowedNow`.
        val place = user(
            ""","show_location":true,"show_location_timing":{"start_time":111,"end_time":222}""",
        ).location!!
        assertEquals(111L, place.everydayStart)
        assertEquals(222L, place.everydayEnd)
    }

    @Test
    fun `select_days keeps the server's day numbers`() {
        val place = user(
            ""","show_location":true,"location_tracking_mode":"SelectDays","select_days":[""" +
                """{"day_of_week":1,"start_time":111,"end_time":222},{"day_of_week":5,"start_time":333}]""",
        ).location!!
        assertEquals(listOf(1, 5), place.days.map { it.dayOfWeek })
        assertEquals(222L, place.days.first().endMillis)
        // A day missing an end is zero here; the gate reads that as unset.
        assertEquals(0L, place.days.last().endMillis)
    }

    @Test
    fun `show_location false still produces a block, so the panel can offer to ask`() {
        val place = user(""","show_location":false""").location!!
        assertEquals(false, place.shared)
    }
}
