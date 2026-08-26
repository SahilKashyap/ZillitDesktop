package com.zillit.desktop.feature.weather.data

import com.zillit.desktop.feature.weather.domain.CurrentWeather
import com.zillit.desktop.feature.weather.domain.DailyPoint
import com.zillit.desktop.feature.weather.domain.HourlyPoint
import com.zillit.desktop.feature.weather.domain.WeatherCondition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenWeatherMap's One Call 3.0 answer.
 *
 * Asked for in metric, as Android asks (`WeatherVM.kt:55`), so temperatures
 * arrive in Celsius and wind in metres per second. The web omits `units` and
 * subtracts 273.15 by hand in four places; asking the service is less to get
 * wrong.
 *
 * Every field is nullable: a thin answer for a remote spot must not fail the
 * whole forecast.
 */
@Serializable
internal data class OneCallDto(
    @SerialName("timezone") val timezone: String? = null,
    @SerialName("current") val current: CurrentDto? = null,
    @SerialName("hourly") val hourly: List<HourlyDto>? = null,
    @SerialName("daily") val daily: List<DailyDto>? = null,
)

@Serializable
internal data class ConditionDto(
    @SerialName("main") val main: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("icon") val icon: String? = null,
)

@Serializable
internal data class CurrentDto(
    @SerialName("dt") val at: Long? = null,
    @SerialName("temp") val temp: Double? = null,
    @SerialName("feels_like") val feelsLike: Double? = null,
    @SerialName("humidity") val humidity: Int? = null,
    @SerialName("pressure") val pressure: Int? = null,
    @SerialName("wind_speed") val windSpeed: Double? = null,
    @SerialName("uvi") val uvi: Double? = null,
    @SerialName("visibility") val visibility: Int? = null,
    @SerialName("sunrise") val sunrise: Long? = null,
    @SerialName("sunset") val sunset: Long? = null,
    @SerialName("weather") val weather: List<ConditionDto>? = null,
)

@Serializable
internal data class HourlyDto(
    @SerialName("dt") val at: Long? = null,
    @SerialName("temp") val temp: Double? = null,
    @SerialName("weather") val weather: List<ConditionDto>? = null,
)

@Serializable
internal data class DailyDto(
    @SerialName("dt") val at: Long? = null,
    @SerialName("temp") val temp: DailyTempDto? = null,
    @SerialName("wind_speed") val windSpeed: Double? = null,
    @SerialName("weather") val weather: List<ConditionDto>? = null,
)

@Serializable
internal data class DailyTempDto(
    @SerialName("min") val min: Double? = null,
    @SerialName("max") val max: Double? = null,
)

/** Seconds since the epoch, as this service counts — milliseconds, as we do. */
private fun Long?.toMillis(): Long = (this ?: 0L) * MILLIS_PER_SECOND

/** Metres per second to kilometres per hour, the way both other clients show wind. */
internal fun Double?.msToKph(): Double = (this ?: 0.0) * SECONDS_PER_HOUR / METRES_PER_KM

private fun List<ConditionDto>?.toCondition(): WeatherCondition {
    val first = this?.firstOrNull()
    return WeatherCondition(
        // The long description reads better than the one-word `main`
        // ("light rain" over "Rain"), and is what the phones show.
        summary = first?.description?.takeIf { it.isNotBlank() }
            ?: first?.main.orEmpty(),
        icon = first?.icon.orEmpty(),
    )
}

internal fun CurrentDto.toCurrent(): CurrentWeather = CurrentWeather(
    temperatureC = temp ?: 0.0,
    feelsLikeC = feelsLike ?: 0.0,
    humidityPercent = humidity ?: 0,
    pressureHpa = pressure ?: 0,
    windKph = windSpeed.msToKph(),
    uvIndex = uvi ?: 0.0,
    visibilityMetres = visibility ?: 0,
    sunriseMillis = sunrise.toMillis(),
    sunsetMillis = sunset.toMillis(),
    condition = weather.toCondition(),
)

internal fun HourlyDto.toPoint(): HourlyPoint = HourlyPoint(
    atMillis = at.toMillis(),
    temperatureC = temp ?: 0.0,
    condition = weather.toCondition(),
)

internal fun DailyDto.toPoint(): DailyPoint = DailyPoint(
    atMillis = at.toMillis(),
    minC = temp?.min ?: 0.0,
    maxC = temp?.max ?: 0.0,
    windKph = windSpeed.msToKph(),
    condition = weather.toCondition(),
)

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_HOUR = 3600.0
private const val METRES_PER_KM = 1000.0
