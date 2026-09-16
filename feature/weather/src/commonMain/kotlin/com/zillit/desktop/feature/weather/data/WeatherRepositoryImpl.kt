package com.zillit.desktop.feature.weather.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.weather.domain.WeatherPlace
import com.zillit.desktop.feature.weather.domain.WeatherReport
import com.zillit.desktop.feature.weather.domain.WeatherRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/**
 * OpenWeatherMap's One Call 3.0, straight from this machine.
 *
 * **On the plain client, never the API one.** This is a third party: the
 * Zillit session headers — token, project, user — have no business on their
 * servers, and the plain client is the same one the map tiles and the static
 * maps use for exactly that reason.
 *
 * Metric units, as Android asks (`WeatherVM.kt:55`); the web instead takes
 * Kelvin and subtracts 273.15 in four separate components.
 */
class WeatherRepositoryImpl(
    private val httpClient: HttpClient,
    /** Null switches the tool off rather than calling with no key. */
    private val apiKey: String?,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : WeatherRepository {

    override val isConfigured: Boolean get() = !apiKey.isNullOrBlank()

    override suspend fun forecast(place: WeatherPlace): ZillitResult<WeatherReport> {
        val key = apiKey?.takeIf { it.isNotBlank() }
            ?: return ZillitResult.Failure(ZillitError.Unknown(NO_KEY))

        return runCatching {
            val url = "$BASE?lat=${place.lat}&lon=${place.lng}&units=metric&appid=$key"
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) {
                // Their 401 means the key is wrong or unsubscribed — One Call
                // 3.0 needs its own subscription, separate from the free 2.5.
                return ZillitResult.Failure(
                    ZillitError.Http(status = response.status.value, serverMessage = REFUSED),
                )
            }
            val dto = json.decodeFromString(OneCallDto.serializer(), response.bodyAsText())
            val current = dto.current
                ?: return ZillitResult.Failure(ZillitError.Unknown(NO_READING))

            ZillitResult.Success(
                WeatherReport(
                    place = place,
                    timezone = dto.timezone.orEmpty(),
                    current = current.toCurrent(),
                    hourly = dto.hourly.orEmpty().take(HOURS_SHOWN).map { it.toPoint() },
                    // Seven rows headed "Today", as the web labels its first row
                    // (`SevendayForecastCard.jsx:17`). The web slices `daily`
                    // from index 1 and still calls that row "Today", so its
                    // first row is really tomorrow; starting at index 0 makes
                    // the label true without changing the list's shape.
                    daily = dto.daily.orEmpty().take(DAYS_SHOWN).map { it.toPoint() },
                ),
            )
        }.getOrElse { failure ->
            ZillitResult.Failure(ZillitError.NoConnection(failure.message ?: UNREACHABLE))
        }
    }

    private companion object {
        const val BASE = "https://api.openweathermap.org/data/3.0/onecall"

        /** Two days of hours is more than a call sheet ever needs. */
        const val HOURS_SHOWN = 24

        /** A week, today included — the web's "7-Day Forecast". */
        const val DAYS_SHOWN = 7

        const val NO_KEY = "No weather key is configured for this environment."
        const val REFUSED = "The weather service refused the request."
        const val NO_READING = "The weather service sent no reading for this place."
        const val UNREACHABLE = "The weather service could not be reached."
    }
}
