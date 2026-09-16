package com.zillit.desktop.feature.weather.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.media.decodeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * OpenWeather's condition artwork by icon code (`10d`, `01n`), or null when it
 * cannot be had — the screen then draws a glyph in its place.
 */
fun interface WeatherIconLoader {
    suspend fun load(code: String): ImageBitmap?
}

/** In scope for the screen; null where no host is wired (render tests). */
val LocalWeatherIconLoader = staticCompositionLocalOf<WeatherIconLoader?> { null }

/**
 * The pictures the web shows — `openweathermap.org/img/wn/{code}@4x.png`
 * (`HourlyForecast.jsx:41`, `CurrentWeather.jsx:60`) — fetched once per code
 * and kept for the session. There are eighteen codes in all, so the cache is
 * bounded by the vocabulary, not by use.
 *
 * On the plain client: a third-party CDN gets no Zillit session header. A
 * failed fetch is not remembered, so the next tile that needs the code tries
 * again — a picture that is missing because the network blinked should come
 * back when the network does.
 */
class OpenWeatherIconLoader(private val httpClient: HttpClient) : WeatherIconLoader {

    private val cache = mutableMapOf<String, ImageBitmap>()
    private val lock = Mutex()

    override suspend fun load(code: String): ImageBitmap? {
        if (code.isBlank()) return null
        lock.withLock { cache[code] }?.let { return it }
        val bytes = runCatching {
            val response = httpClient.get("$BASE/$code@4x.png")
            if (response.status.isSuccess()) response.readRawBytes() else null
        }.onFailure { ZillitLog.w(TAG) { "icon $code failed: ${it::class.simpleName}" } }.getOrNull()
        val bitmap = bytes?.let(::decodeImageBitmap)
        if (bitmap != null) lock.withLock { cache[code] = bitmap }
        return bitmap
    }

    private companion object {
        const val TAG = "WeatherIcons"
        const val BASE = "https://openweathermap.org/img/wn"
    }
}
