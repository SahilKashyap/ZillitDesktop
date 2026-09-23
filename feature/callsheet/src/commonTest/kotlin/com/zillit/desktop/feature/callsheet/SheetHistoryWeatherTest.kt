package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.domain.ApprovalRequest
import com.zillit.desktop.feature.callsheet.domain.CallSheetDetail
import com.zillit.desktop.feature.callsheet.domain.SheetHistory
import com.zillit.desktop.feature.callsheet.domain.SheetReminder
import com.zillit.desktop.feature.callsheet.domain.SheetRevision
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.SheetWeather
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.WeatherValue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The History timeline and the stored weather value. */
class SheetHistoryWeatherTest {

    private val summary = CallSheetSummary(
        id = "r1",
        serialNo = "1",
        name = "Day 1",
        status = CallSheetStatus.PendingApproval,
        createdBy = "Author",
        createdById = "author",
        createdOn = 1_000,
    )

    @Test
    fun `history lists decisions, sends, reminder batches and revisions, newest first`() {
        val detail = CallSheetDetail(
            summary = summary,
            payload = SheetPayload(),
            approvals = listOf(
                ApprovalRequest(
                    "a1",
                    "u1",
                    "Uma",
                    "Producer",
                    createdOn = 5_000,
                    actedOn = 9_000,
                    status = "APPROVED",
                    revisionId = "v2",
                ),
                ApprovalRequest("a2", "u2", "Vic", "Director", createdOn = 5_000),
            ),
            reminders = listOf(
                SheetReminder(
                    "m1",
                    assigneeName = "Uma",
                    sentBy = "Author",
                    message = "Please sign",
                    createdOn = 20_000,
                ),
                SheetReminder(
                    "m2",
                    assigneeName = "Vic",
                    sentBy = "Author",
                    message = "Please sign",
                    createdOn = 21_000,
                ),
                SheetReminder(
                    "m3",
                    assigneeName = "Vic",
                    sentBy = "Author",
                    message = "Please sign",
                    createdOn = 40_000,
                ),
            ),
            revisions = listOf(
                SheetRevision("v1", version = 1, createdBy = "Author", createdOn = 1_000),
                SheetRevision("v2", version = 2, createdBy = "Author", createdOn = 3_000, notes = " tidy "),
            ),
        )
        val entries = SheetHistory.entries(detail, members = emptyList())
        // "Reminder sent" is Android's wording for the shared key, kept over the desktop's old capital S.
        assertEquals(
            listOf("Reminder sent", "Reminder sent", "Approved", "Sent for Signature", "Updated", "Created"),
            entries.map { it.action },
        )
        assertEquals("v2", entries[2].revisionText)
        assertEquals("Final", entries[2].stage)
        assertEquals("tidy", entries[4].message)
        assertEquals(listOf(40_000L, 20_000L), entries.take(2).map { it.atMillis }, "one batch per five seconds")
    }

    private val response = """
        {"lat":51.5,"lon":-0.12,"timezone":"Europe/London",
         "current":{"temp":290.15,"feels_like":289.15,"humidity":70,"wind_speed":5,"uvi":3.5,"visibility":10000,
                    "sunrise":1757740000,"sunset":1757786000,
                    "weather":[{"main":"Clouds","description":"broken clouds","icon":"04d"}]},
         "daily":[
           {"dt":DAY0,"temp":{"day":291.15,"min":285.15,"max":293.15},"humidity":60,"wind_speed":4,
            "weather":[{"main":"Rain","description":"light rain","icon":"10d"}]},
           {"dt":DAY1,"temp":{"day":295.15,"min":288.15,"max":297.15},"humidity":50,"wind_speed":2,
            "weather":[{"main":"Clear","description":"clear sky","icon":"01d"}]}
         ]}
    """.trimIndent()

    private val zone = TimeZone.currentSystemDefault()
    private val day13 = LocalDate(2026, 9, 13).atStartOfDayIn(zone).toEpochMilliseconds()
    private val day14 = LocalDate(2026, 9, 14).atStartOfDayIn(zone).toEpochMilliseconds()

    private fun forecast(): JsonObject {
        val day0 = day13 / 1000 + 43_200
        val day1 = day14 / 1000 + 43_200
        return Json.parseToJsonElement(response.replace("DAY0", "$day0").replace("DAY1", "$day1")) as JsonObject
    }

    @Test
    fun `the shoot day's forecast is stored when the window reaches it, in Celsius and km per hour`() {
        val value = WeatherValue.parse(
            assertNotNull(SheetWeather.valueFor(forecast(), "Soho", day14, nowMillis = 7)),
        )
        assertNotNull(value)
        assertEquals("Soho", value.location)
        assertEquals("Clear", value.condition)
        assertEquals(22, value.tempC)
        assertEquals(72, value.tempF)
        assertEquals(24, value.tempHighC)
        assertEquals(15, value.tempLowC)
        assertEquals(7, value.windKmh)
        assertNotNull(value.forecastDate)
        assertEquals(7L, value.fetchedAt)
    }

    @Test
    fun `beyond the window the current conditions are stored instead`() {
        val value = WeatherValue.parse(
            assertNotNull(SheetWeather.valueFor(forecast(), "Soho", day14 + 200L * 86_400_000L, nowMillis = 7)),
        )
        assertNotNull(value)
        assertEquals("Clouds", value.condition)
        assertEquals(17, value.tempC)
        assertEquals(6, value.visibilityMiles)
        assertNull(value.forecastDate)
        assertEquals(18, value.windKmh)
    }

    @Test
    fun `the forecast strip picks a day, and free text is not a weather value`() {
        val tomorrow = WeatherValue.parse(
            assertNotNull(SheetWeather.valueForDay(forecast(), 1, "Soho", nowMillis = 7)),
        )
        assertEquals("Clear", tomorrow?.condition)
        assertEquals(2, SheetWeather.forecastDays(forecast()).size)
        assertEquals(1, SheetWeather.shootDayIndex(forecast(), day14))
        assertNull(SheetWeather.shootDayIndex(forecast(), null))
        assertNull(WeatherValue.parse("Sunny, 25°C"))
        assertNull(WeatherValue.parse(""))
    }
}
