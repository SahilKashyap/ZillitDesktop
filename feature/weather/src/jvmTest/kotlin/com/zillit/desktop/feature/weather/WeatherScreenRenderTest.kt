package com.zillit.desktop.feature.weather

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.weather.domain.CurrentWeather
import com.zillit.desktop.feature.weather.domain.DailyPoint
import com.zillit.desktop.feature.weather.domain.HourlyPoint
import com.zillit.desktop.feature.weather.domain.PlaceSuggestion
import com.zillit.desktop.feature.weather.domain.WeatherCondition
import com.zillit.desktop.feature.weather.domain.WeatherPlace
import com.zillit.desktop.feature.weather.domain.WeatherReport
import com.zillit.desktop.feature.weather.ui.PlaceSearch
import com.zillit.desktop.feature.weather.ui.WeatherClock
import com.zillit.desktop.feature.weather.ui.WeatherEvent
import com.zillit.desktop.feature.weather.ui.WeatherScreen
import com.zillit.desktop.feature.weather.ui.WeatherUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Composes the real screen in each state it can reach, light and dark. */
@OptIn(ExperimentalTestApi::class)
class WeatherScreenRenderTest {

    @Test
    fun `a forecast draws in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = dark) {
                        WeatherScreen(
                            state = loaded(),
                            onEvent = {},
                            clock = WeatherClock(
                                time = { millis, _ -> if (millis == SUNRISE) "6:14 AM" else "7:02 PM" },
                                day = { _, _ -> "Today" },
                            ),
                        )
                    }
                }
                onNodeWithText("31°").assertExists()
                onNodeWithText("Light rain").assertExists()
                // The six readings in the web's order, the sun, the week.
                onNodeWithText("5 miles").assertExists()
                onNodeWithText("6:14 AM").assertExists()
                onNodeWithText("7-DAY FORECAST").assertExists()
                onNodeWithText("Today").assertExists()
                onNodeWithText("35°").assertExists()
            }
        }
    }

    /**
     * The search box is the web's header: typing reaches the model, and a
     * suggestion under it is a click away.
     */
    @Test
    fun `the search box suggests and picks`() {
        val events = mutableListOf<WeatherEvent>()
        val paris = PlaceSuggestion("p1", "Paris", "France")
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    WeatherScreen(
                        state = loaded().copy(search = PlaceSearch(query = "Par", suggestions = listOf(paris))),
                        onEvent = { events += it },
                    )
                }
            }
            onNodeWithText("France").assertExists()
            onNodeWithText("Paris").performClick()
            onNodeWithText("Par").performTextInput("i")
        }
        assertEquals(WeatherEvent.SuggestionPicked(paris), events.first())
        assertTrue(events.last() is WeatherEvent.SearchChanged)
    }

    /** Emptying the box is an event of its own — the model goes back to "here" on it. */
    @Test
    fun `my location is a click away`() {
        val events = mutableListOf<WeatherEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    WeatherScreen(state = loaded(), onEvent = { events += it })
                }
            }
            onNodeWithContentDescription("Use my location").performClick()
        }
        assertEquals(listOf<WeatherEvent>(WeatherEvent.UseMyLocation), events)
    }

    /** No place yet: the screen asks for one rather than showing an empty grid. */
    @Test
    fun `with no place the screen asks for one`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    WeatherScreen(state = WeatherUiState(rightsResolved = true, canView = true), onEvent = {})
                }
            }
            onNodeWithText("No place chosen").assertExists()
        }
    }

    /** No key: an honest sentence, not an error the reader cannot act on. */
    @Test
    fun `an unconfigured tool says so`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    WeatherScreen(
                        state = WeatherUiState(rightsResolved = true, canView = true, configured = false),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Weather is not configured").assertExists()
        }
    }

    /** Denied by the rights grid. */
    @Test
    fun `no access is stated plainly`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    WeatherScreen(state = WeatherUiState(rightsResolved = true, canView = false), onEvent = {})
                }
            }
            onNodeWithText("No weather access").assertExists()
        }
    }

    private fun loaded() = WeatherUiState(
        rightsResolved = true,
        canView = true,
        canSearch = true,
        place = WeatherPlace("Film City", 19.16, 72.86),
        report = WeatherReport(
            place = WeatherPlace("Film City", 19.16, 72.86),
            timezone = "Asia/Kolkata",
            current = CurrentWeather(
                temperatureC = 31.4,
                feelsLikeC = 36.2,
                humidityPercent = 62,
                pressureHpa = 1008,
                windKph = 16.6,
                uvIndex = 9.3,
                visibilityMetres = 8000,
                sunriseMillis = SUNRISE,
                sunsetMillis = 1_786_995_000_000,
                condition = WeatherCondition("light rain", "10d"),
            ),
            hourly = listOf(
                HourlyPoint(1_787_003_600_000, 30.1, WeatherCondition("clear sky", "01n")),
            ),
            daily = listOf(
                DailyPoint(1_787_086_400_000, 27.2, 34.6, 5.5, WeatherCondition("scattered clouds", "03d")),
            ),
        ),
    )
}

private const val SUNRISE = 1_786_950_000_000
