package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.domain.ApprovalRequest
import com.zillit.desktop.feature.productionreport.domain.ReportDetail
import com.zillit.desktop.feature.productionreport.domain.ReportHistory
import com.zillit.desktop.feature.productionreport.domain.ReportReminder
import com.zillit.desktop.feature.productionreport.domain.ReportRevision
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReportTime
import com.zillit.desktop.feature.productionreport.domain.ReportWeather
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.SheetPayload
import com.zillit.desktop.feature.productionreport.domain.WeatherValue
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
class ReportHistoryWeatherTest {

    private val summary = ReportSummary(
        id = "r1",
        serialNo = "1",
        name = "Day 1",
        status = ReportStatus.PendingApproval,
        createdBy = "Author",
        createdById = "author",
        createdOn = 1_000,
    )

    @Test
    @Suppress("LongMethod") // One fixture, every entry kind, asserted in order.
    fun `history lists decisions, sends, reminder batches and revisions, newest first`() {
        val detail = ReportDetail(
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
                ReportReminder(
                    "m1",
                    assigneeName = "Uma",
                    sentBy = "Author",
                    message = "Please sign",
                    createdOn = 20_000,
                ),
                ReportReminder(
                    "m2",
                    assigneeName = "Vic",
                    sentBy = "Author",
                    message = "Please sign",
                    createdOn = 21_000,
                ),
                ReportReminder(
                    "m3",
                    assigneeName = "Vic",
                    sentBy = "Author",
                    message = "Please sign",
                    createdOn = 40_000,
                ),
            ),
            revisions = listOf(
                ReportRevision("v1", version = 1, createdBy = "Author", createdOn = 1_000),
                ReportRevision("v2", version = 2, createdBy = "Author", createdOn = 3_000, notes = " tidy "),
            ),
        )
        val entries = ReportHistory.entries(detail)
        // "Reminder sent" is Android's wording for the shared key, kept over the desktop's old capital S.
        assertEquals(
            listOf("Reminder sent", "Reminder sent", "Approved", "Sent for Signature", "Updated", "Created"),
            entries.map { it.action },
        )
        assertEquals("Author", entries[0].by, "a name in sent_by matches no member and shows verbatim")
        assertEquals(listOf("Uma", "Vic"), entries[1].recipients, "reminders five seconds apart are one batch")
        val byId = detail.copy(reminders = detail.reminders.map { it.copy(sentBy = "author", sentByRole = "x_label") })
        val resolved = ReportHistory.entries(byId, listOf(SheetMember("author", "Author Now", designation = "1st AD")))
        assertEquals("Author Now", resolved[0].by, "an id resolves to the member's current name")
        assertEquals("1st AD", resolved[0].role)
        assertEquals("v2", entries[2].revisionText)
        assertEquals("tidy", entries[4].message)
        assertEquals("Final Approved on 9000 • v2", entries[2].metaLine { it.toString() })
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

    private fun forecast(): JsonObject {
        val zone = TimeZone.currentSystemDefault()
        val day0 = LocalDate(2026, 9, 13).atStartOfDayIn(zone).epochSeconds + 43_200
        val day1 = LocalDate(2026, 9, 14).atStartOfDayIn(zone).epochSeconds + 43_200
        return Json.parseToJsonElement(response.replace("DAY0", "$day0").replace("DAY1", "$day1")) as JsonObject
    }

    @Test
    fun `the shoot day's forecast is stored when the window reaches it, in Celsius and km per hour`() {
        val value = WeatherValue.parse(
            assertNotNull(ReportWeather.valueFor(forecast(), "Soho", "2026-09-14", nowMillis = 7)),
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
            assertNotNull(ReportWeather.valueFor(forecast(), "Soho", "2027-01-01", nowMillis = 7)),
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
            assertNotNull(ReportWeather.valueForDay(forecast(), 1, "Soho", nowMillis = 7)),
        )
        assertEquals("Clear", tomorrow?.condition)
        assertEquals(2, ReportWeather.forecastDays(forecast()).size)
        assertNull(WeatherValue.parse("Sunny, 25°C"))
        assertNull(WeatherValue.parse(""))
    }

    @Test
    fun `the header date is a local calendar day with its ordinal`() {
        assertEquals("Sunday 13th September, 2026", ReportTime.headerDate("2026-09-13"))
        assertEquals("Tuesday 1st September, 2026", ReportTime.headerDate("2026-09-01"))
        assertEquals("Wednesday 2nd September, 2026", ReportTime.headerDate("2026-09-02"))
        assertEquals("Thursday 3rd September, 2026", ReportTime.headerDate("2026-09-03"))
        assertEquals("Friday 11th September, 2026", ReportTime.headerDate("2026-09-11"))
        assertEquals("Tuesday 22nd September, 2026", ReportTime.headerDate("2026-09-22"))
        assertEquals("", ReportTime.headerDate(""))
        assertEquals("next week", ReportTime.headerDate("next week"))
    }
}
