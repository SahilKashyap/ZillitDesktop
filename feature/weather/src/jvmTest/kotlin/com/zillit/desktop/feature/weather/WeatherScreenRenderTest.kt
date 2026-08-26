package com.zillit.desktop.feature.weather

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.weather.domain.CurrentWeather
import com.zillit.desktop.feature.weather.domain.DailyPoint
import com.zillit.desktop.feature.weather.domain.HourlyPoint
import com.zillit.desktop.feature.weather.domain.WeatherCondition
import com.zillit.desktop.feature.weather.domain.WeatherPlace
import com.zillit.desktop.feature.weather.domain.WeatherReport
import com.zillit.desktop.feature.weather.ui.WeatherScreen
import com.zillit.desktop.feature.weather.ui.WeatherUiState
import kotlin.test.Test

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
                            formatTime = { "06:14" },
                            formatDay = { "Tue 26" },
                        )
                    }
                }
                onNodeWithText("31°").assertExists()
                onNodeWithText("Light rain").assertExists()
                // ZillitSectionLabel upper-cases its text.
                onNodeWithText("THE WEEK AHEAD").assertExists()
            }
        }
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
                sunriseMillis = 1_786_950_000_000,
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
