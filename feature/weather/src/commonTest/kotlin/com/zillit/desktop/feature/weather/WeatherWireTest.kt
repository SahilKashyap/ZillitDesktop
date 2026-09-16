package com.zillit.desktop.feature.weather

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.weather.data.WeatherRepositoryImpl
import com.zillit.desktop.feature.weather.domain.WeatherPlace
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What OpenWeather says, and what the screen is given. */
class WeatherWireTest {

    private val place = WeatherPlace("Film City", 19.16, 72.86)

    private val body = """
        {
          "timezone": "Asia/Kolkata",
          "current": {
            "dt": 1787000000, "temp": 31.4, "feels_like": 36.2, "humidity": 62,
            "pressure": 1008, "wind_speed": 4.6, "uvi": 9.3, "visibility": 8000,
            "sunrise": 1786950000, "sunset": 1786995000,
            "weather": [{"main":"Rain","description":"light rain","icon":"10d"}]
          },
          "hourly": [
            {"dt": 1787003600, "temp": 30.1, "weather":[{"description":"clear sky","icon":"01n"}]}
          ],
          "daily": [
            {"dt": 1787000000, "temp": {"min": 26.0, "max": 33.0}, "wind_speed": 3.0,
             "weather":[{"description":"haze","icon":"50d"}]},
            {"dt": 1787086400, "temp": {"min": 27.2, "max": 34.6}, "wind_speed": 5.5,
             "weather":[{"description":"scattered clouds","icon":"03d"}]}
          ]
        }
    """.trimIndent()

    /** Metric in, metric out — no Kelvin arithmetic anywhere. */
    @Test
    fun `a forecast is read in celsius`() = runTest {
        val report = (repository(body).forecast(place) as ZillitResult.Success).data

        assertEquals(31, report.current.temperatureC.roundToInt())
        assertEquals("Asia/Kolkata", report.timezone)
        assertEquals("light rain", report.current.condition.summary)
    }

    /** Wind arrives in metres per second; both other clients show km/h. */
    @Test
    fun `wind is converted to kilometres per hour`() = runTest {
        val report = (repository(body).forecast(place) as ZillitResult.Success).data

        // 4.6 m/s = 16.56 km/h
        assertEquals(17, report.current.windKph.roundToInt())
    }

    /** Seconds on the wire, milliseconds in the app. */
    @Test
    fun `times are milliseconds`() = runTest {
        val report = (repository(body).forecast(place) as ZillitResult.Success).data

        assertEquals(1_786_950_000_000, report.current.sunriseMillis)
    }

    /**
     * The week is headed by today, so the "Today" row the web labels its
     * first row with (`SevendayForecastCard.jsx:17`) is true here — the web
     * slices from tomorrow and mislabels it.
     */
    @Test
    fun `the week ahead starts today`() = runTest {
        val report = (repository(body).forecast(place) as ZillitResult.Success).data

        assertEquals(2, report.daily.size)
        assertEquals("haze", report.daily.first().condition.summary)
        assertEquals("scattered clouds", report.daily.last().condition.summary)
    }

    /** A refused key is a failure with a sentence, not an empty screen. */
    @Test
    fun `a refusal is reported`() = runTest {
        val answer = repository(body, HttpStatusCode.Unauthorized).forecast(place)

        assertTrue(answer is ZillitResult.Failure)
    }

    /** No key: the tool says so rather than calling with none. */
    @Test
    fun `no key means not configured`() = runTest {
        val repository = WeatherRepositoryImpl(HttpClient(MockEngine { respond("") }), apiKey = null)

        assertFalse(repository.isConfigured)
        assertTrue(repository.forecast(place) is ZillitResult.Failure)
    }

    /** A thin answer for a remote spot must not fail the whole forecast. */
    @Test
    fun `a forecast with no hours still reads`() = runTest {
        val thin = """{"current":{"temp":12.0,"weather":[{"description":"fog","icon":"50n"}]}}"""
        val report = (repository(thin).forecast(place) as ZillitResult.Success).data

        assertTrue(report.hourly.isEmpty())
        assertTrue(report.daily.isEmpty())
        assertEquals(12, report.current.temperatureC.roundToInt())
    }

    private fun repository(json: String, status: HttpStatusCode = HttpStatusCode.OK) = WeatherRepositoryImpl(
        httpClient = HttpClient(
            MockEngine { respond(json, status, headersOf("Content-Type", "application/json")) },
        ),
        apiKey = "test-key",
    )
}
