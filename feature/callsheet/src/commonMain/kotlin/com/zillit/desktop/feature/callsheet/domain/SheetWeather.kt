package com.zillit.desktop.feature.callsheet.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * A weather cell's stored value: either free text typed by hand, or the web
 * `WeatherWidget`'s JSON (`_type: "weather"`), which both the preview's card
 * and the server PDF (through `summary`) read.
 */
data class WeatherValue(
    val location: String = "",
    val condition: String = "",
    val description: String = "",
    val icon: String = "",
    val tempC: Int? = null,
    val tempF: Int? = null,
    val tempHighC: Int? = null,
    val tempLowC: Int? = null,
    val tempHighF: Int? = null,
    val tempLowF: Int? = null,
    val feelsLikeC: Int? = null,
    val humidity: Int? = null,
    val windKmh: Int? = null,
    val pressure: Int? = null,
    val uvi: Double? = null,
    val visibilityMiles: Int? = null,
    /** Unix SECONDS, as the API gives them. */
    val sunrise: Long? = null,
    val sunset: Long? = null,
    val timezone: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    /** The forecast day's `dt` (unix seconds); null for current conditions. */
    val forecastDate: Long? = null,
    val fetchedAt: Long? = null,
    val summary: String = "",
) {
    /** `HH:mm` of a unix-seconds instant in the forecast's own zone, or `--`. */
    fun clock(unixSeconds: Long?): String {
        if (unixSeconds == null || unixSeconds <= 0) return "--"
        val zone = runCatching { TimeZone.of(timezone) }.getOrDefault(TimeZone.currentSystemDefault())
        val time = Instant.fromEpochMilliseconds(unixSeconds * MILLIS).toLocalDateTime(zone)
        return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    }

    /** `formatDateShort` of the forecast day — `5 Mar`. */
    fun forecastLabel(): String {
        val seconds = forecastDate ?: return ""
        val date = Instant.fromEpochMilliseconds(seconds * MILLIS).toLocalDateTime(TimeZone.currentSystemDefault()).date
        return "${date.day} ${str(SHORT_MONTHS[date.month.ordinal])}"
    }

    companion object {
        private const val MILLIS = 1000L
        internal val SHORT_MONTHS = listOf(
            S.desktop_month_short_jan,
            S.desktop_month_short_feb,
            S.desktop_month_short_mar,
            S.desktop_month_short_apr,
            S.desktop_month_short_may,
            S.desktop_month_short_jun,
            S.desktop_month_short_jul,
            S.desktop_month_short_aug,
            S.desktop_month_short_sep,
            S.desktop_month_short_oct,
            S.desktop_month_short_nov,
            S.desktop_month_short_dec,
        )

        /** The stored JSON, or null when the value is free text (or empty). */
        fun parse(raw: String): WeatherValue? {
            val obj = runCatching { Json.parseToJsonElement(raw.trim()) }.getOrNull() as? JsonObject ?: return null
            if (obj.text("_type") != "weather") return null
            return WeatherValue(
                location = obj.text("location"),
                condition = obj.text("condition"),
                description = obj.text("description"),
                icon = obj.text("icon"),
                tempC = obj.int("tempC"),
                tempF = obj.int("tempF"),
                tempHighC = obj.int("tempHighC"),
                tempLowC = obj.int("tempLowC"),
                tempHighF = obj.int("tempHighF"),
                tempLowF = obj.int("tempLowF"),
                feelsLikeC = obj.int("feelsLikeC"),
                humidity = obj.int("humidity"),
                windKmh = obj.int("windKmh"),
                pressure = obj.int("pressure"),
                uvi = obj.double("uvi"),
                visibilityMiles = obj.int("visibilityMiles"),
                sunrise = obj.long("sunrise"),
                sunset = obj.long("sunset"),
                timezone = obj.text("timezone"),
                lat = obj.double("lat"),
                lon = obj.double("lon"),
                forecastDate = obj.long("forecastDate"),
                fetchedAt = obj.long("fetchedAt"),
                summary = obj.text("summary"),
            )
        }
    }
}

/**
 * Builds the stored weather value from an OpenWeather One Call 3.0 response
 * (Kelvin — the web's call carries no `units`): the daily entry whose local
 * calendar day is the shoot date when the 8-day window has it, otherwise the
 * current conditions (`WeatherWidget.jsx:75-166`, `:331-377`).
 */
object SheetWeather {

    private const val KELVIN = 273.15
    private const val SECOND_MILLIS = 1000L
    private const val MS_TO_KMH = 3.6
    private const val METRES_PER_MILE = 1609.34
    private const val F_SCALE = 9.0 / 5.0
    private const val F_OFFSET = 32

    /** The daily entries' calendar days, for the forecast strip. */
    fun forecastDays(response: JsonObject, zone: TimeZone = TimeZone.currentSystemDefault()): List<LocalDate> =
        days(response).mapNotNull { day -> day.long("dt")?.let { dateOf(it, zone) } }

    /** The index of the shoot day in the forecast window, or null when the window does not reach it. */
    fun shootDayIndex(
        response: JsonObject,
        shootDateMs: Long?,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): Int? {
        val target = shootDateMs?.takeIf { it > 0 }?.let { SheetTime.localDate(it, zone) } ?: return null
        return days(response).indexOfFirst { day -> day.long("dt")?.let { dateOf(it, zone) } == target }
            .takeIf { it >= 0 }
    }

    /** The value for the shoot date, or current conditions when the forecast does not reach it. */
    fun valueFor(
        response: JsonObject,
        location: String,
        shootDateMs: Long?,
        nowMillis: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String? {
        val index = shootDayIndex(response, shootDateMs, zone)
        val day = index?.let { days(response).getOrNull(it) }
        return when {
            day != null -> daily(response, day, location, nowMillis)
            response["current"] is JsonObject -> current(response, location, nowMillis)
            else -> null
        }
    }

    /**
     * A specific daily entry — the forecast strip's pick. Today's entry reads
     * the current conditions when the response has them.
     */
    fun valueForDay(
        response: JsonObject,
        index: Int,
        location: String,
        nowMillis: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String? {
        val day = days(response).getOrNull(index) ?: return null
        val isToday = day.long("dt")?.let { dateOf(it, zone) } == SheetTime.localDate(nowMillis, zone)
        return if (index == 0 && isToday && response["current"] is JsonObject) {
            current(response, location, nowMillis)
        } else {
            daily(response, day, location, nowMillis)
        }
    }

    private fun days(response: JsonObject): List<JsonObject> =
        (response["daily"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun dateOf(seconds: Long, zone: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(seconds * SECOND_MILLIS).toLocalDateTime(zone).date

    private fun daily(response: JsonObject, day: JsonObject, location: String, nowMillis: Long): String {
        val temp = day["temp"] as? JsonObject
        val weather = firstWeather(day)
        val value = WeatherValue(
            location = location,
            condition = weather?.text("main").orEmpty(),
            description = weather?.text("description").orEmpty(),
            icon = weather?.text("icon").orEmpty(),
            tempC = celsius(temp?.double("day")),
            tempF = fahrenheit(temp?.double("day")),
            tempHighC = celsius(temp?.double("max")),
            tempLowC = celsius(temp?.double("min")),
            tempHighF = fahrenheit(temp?.double("max")),
            tempLowF = fahrenheit(temp?.double("min")),
            feelsLikeC = celsius((day["feels_like"] as? JsonObject)?.double("day") ?: temp?.double("day")),
            humidity = day.double("humidity")?.roundToInt(),
            windKmh = day.double("wind_speed")?.let { (it * MS_TO_KMH).roundToInt() },
            pressure = day.double("pressure")?.roundToInt(),
            uvi = day.double("uvi"),
            sunrise = day.long("sunrise"),
            sunset = day.long("sunset"),
            timezone = response.text("timezone"),
            lat = response.double("lat"),
            lon = response.double("lon"),
            forecastDate = day.long("dt"),
            fetchedAt = nowMillis,
        )
        val summary = listOfNotNull(
            value.condition.takeIf { it.isNotEmpty() },
            value.tempHighC?.let { "Hi: $it°C / ${value.tempHighF}°F" },
            value.tempLowC?.let { "Lo: $it°C / ${value.tempLowF}°F" },
            value.humidity?.let { "Humidity: $it%" },
            value.windKmh?.let { "Wind: $it km/h" },
            value.sunrise?.let { "SR: ${value.clock(it)}" },
            value.sunset?.let { "SS: ${value.clock(it)}" },
        ).joinToString(", ")
        return encode(value.copy(summary = summary))
    }

    private fun current(response: JsonObject, location: String, nowMillis: Long): String {
        val now = response["current"] as JsonObject
        val weather = firstWeather(now)
        val today = days(response).firstOrNull()
        val todayTemp = today?.get("temp") as? JsonObject
        val value = WeatherValue(
            location = location,
            condition = weather?.text("main").orEmpty(),
            description = weather?.text("description").orEmpty(),
            icon = weather?.text("icon").orEmpty(),
            tempC = celsius(now.double("temp")),
            tempF = fahrenheit(now.double("temp")),
            tempHighC = celsius(todayTemp?.double("max")),
            tempLowC = celsius(todayTemp?.double("min")),
            tempHighF = fahrenheit(todayTemp?.double("max")),
            tempLowF = fahrenheit(todayTemp?.double("min")),
            feelsLikeC = celsius(now.double("feels_like")),
            humidity = now.double("humidity")?.roundToInt(),
            windKmh = now.double("wind_speed")?.let { (it * MS_TO_KMH).roundToInt() },
            pressure = now.double("pressure")?.roundToInt(),
            uvi = now.double("uvi"),
            visibilityMiles = now.double("visibility")?.let { (it / METRES_PER_MILE).roundToInt() },
            sunrise = now.long("sunrise"),
            sunset = now.long("sunset"),
            timezone = response.text("timezone"),
            lat = response.double("lat"),
            lon = response.double("lon"),
            fetchedAt = nowMillis,
        )
        val summary = listOfNotNull(
            value.condition.takeIf { it.isNotEmpty() },
            value.tempC?.let { "$it°C / ${value.tempF}°F" },
            value.humidity?.let { "Humidity: $it%" },
            value.windKmh?.let { "Wind: $it km/h" },
            value.sunrise?.let { "SR: ${value.clock(it)}" },
            value.sunset?.let { "SS: ${value.clock(it)}" },
        ).joinToString(", ")
        return encode(value.copy(summary = summary))
    }

    @Suppress("CyclomaticComplexMethod") // One optional field per line of the stored JSON.
    fun encode(value: WeatherValue): String = buildJsonObject {
        put("_type", "weather")
        put("location", value.location)
        put("condition", value.condition)
        put("description", value.description)
        put("icon", value.icon)
        putNullable("tempC", value.tempC)
        putNullable("tempF", value.tempF)
        putNullable("tempHighC", value.tempHighC)
        putNullable("tempLowC", value.tempLowC)
        putNullable("tempHighF", value.tempHighF)
        putNullable("tempLowF", value.tempLowF)
        putNullable("feelsLikeC", value.feelsLikeC)
        putNullable("humidity", value.humidity)
        putNullable("windKmh", value.windKmh)
        putNullable("pressure", value.pressure)
        put("uvi", value.uvi?.let { JsonPrimitive(it) } ?: JsonNull)
        putNullable("visibilityMiles", value.visibilityMiles)
        put("sunrise", value.sunrise?.let { JsonPrimitive(it) } ?: JsonNull)
        put("sunset", value.sunset?.let { JsonPrimitive(it) } ?: JsonNull)
        put("timezone", value.timezone)
        put("lat", value.lat?.let { JsonPrimitive(it) } ?: JsonNull)
        put("lon", value.lon?.let { JsonPrimitive(it) } ?: JsonNull)
        put("forecastDate", value.forecastDate?.let { JsonPrimitive(it) } ?: JsonNull)
        put("fetchedAt", value.fetchedAt?.let { JsonPrimitive(it) } ?: JsonNull)
        put("summary", value.summary)
    }.toString()

    private fun JsonObjectBuilder.putNullable(key: String, value: Int?) {
        put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun firstWeather(obj: JsonObject): JsonObject? =
        (obj["weather"] as? JsonArray)?.firstOrNull() as? JsonObject

    private fun celsius(kelvin: Double?): Int? = kelvin?.let { (it - KELVIN).roundToInt() }

    private fun fahrenheit(kelvin: Double?): Int? = kelvin?.let { ((it - KELVIN) * F_SCALE + F_OFFSET).roundToInt() }
}

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.int(key: String): Int? = double(key)?.roundToInt()

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
