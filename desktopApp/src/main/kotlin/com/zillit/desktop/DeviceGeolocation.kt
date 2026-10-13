package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Where this machine is, approximately — a bare point, with no map type
 * attached. Public only because [KcefLocationPickerHost] takes one in its
 * constructor; nothing outside this module has a use for it.
 */
data class GeoPoint(val lat: Double, val lng: Double)

/**
 * Where this machine is, from its network address.
 *
 * The web asks `navigator.geolocation` and the browser answers for free. The
 * desktop cannot: embedded Chromium has no way to show the geolocation prompt
 * (this JBR's JCEF has no `onShowPermissionPrompt`) and a JCEF build carries no
 * network-location key of its own, so the page's answer is always a refusal.
 * Google's **Geolocation API** is the desktop's second try — city-level
 * accuracy, which is what every caller here needs: a map that opens near you
 * rather than on the far side of the world.
 *
 * One implementation for the whole app — the Maps tool's locator and the shared
 * place picker both come through here — so a key or quota problem shows up
 * once and reads the same way in the log.
 *
 * ## It is a separate API, and it has to be switched on
 *
 * `geolocation/v1/geolocate` is **not** the Maps JavaScript API. It is enabled,
 * and billed, per Google Cloud project on its own, and because the web client
 * never needed it nobody had reason to enable it. With it off the call answers
 * 403 `accessNotConfigured`, which is all the desktop used to say:
 *
 *     W WeatherPlaces: geolocate refused: 403
 *
 * Hence the reason in the log line below. Do not reduce it back to a status
 * code: "403" sends a reader looking for a bug in this file when the answer is
 * a checkbox in the Cloud console.
 */
internal class DeviceGeolocation(
    private val httpClient: HttpClient,
    /** The production's own Maps key, already decrypted by remote config. */
    private val mapsKey: suspend () -> String?,
) {

    /**
     * The point, or null for every failure — no key, a refused key, an answer
     * with no location in it, no network. Never throws: a picker that could not
     * place you still opens, as the web's does when the browser says no.
     */
    suspend fun locate(): GeoPoint? {
        val key = runCatching { mapsKey() }.getOrNull()?.takeIf { it.isNotBlank() }
        if (key == null) {
            ZillitLog.w(TAG) { "no Google Maps key in remote config; cannot locate this machine" }
            return null
        }
        return runCatching {
            val response = httpClient.post("$GEOLOCATE?key=$key") {
                contentType(ContentType.Application.Json)
                // There is no Wi-Fi scan to offer from the JVM, so Google reads
                // the network address — the web's own IP-lookup accuracy.
                setBody(BODY)
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                ZillitLog.w(TAG) { "geolocate refused: ${response.status.value} ${reason(body)}" }
                return null
            }
            point(body)
        }.onFailure { thrown -> ZillitLog.w(TAG) { "geolocate failed: ${thrown::class.simpleName}" } }
            .getOrNull()
    }

    /** The point in a successful answer, or null when the shape is not the documented one. */
    private fun point(body: String): GeoPoint? {
        val location = obj(body)?.get("location") as? JsonObject ?: return null
        val lat = location.dbl("lat") ?: return null
        val lng = location.dbl("lng") ?: return null
        return GeoPoint(lat, lng)
    }

    /**
     * Google's own words for a refusal: the reason code, then the sentence.
     *
     * `{"error":{"code":403,"status":"PERMISSION_DENIED","message":"Geolocation
     * API has not been used in project 123 before or it is disabled",
     * "errors":[{"reason":"accessNotConfigured"}]}}`. The message is truncated
     * because it ends in a console URL that would wrap the log line three
     * times, and nothing past the first clause says anything new.
     */
    private fun reason(body: String): String {
        val error = obj(body)?.get("error") as? JsonObject ?: return NO_REASON
        val code = ((error["errors"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("reason")
            ?: error.str("status")
        val message = error.str("message")?.take(REASON_CHARS)
        return listOfNotNull(code, message).joinToString(" — ").ifBlank { NO_REASON }
    }

    private fun obj(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private companion object {
        const val TAG = "DeviceGeolocation"
        const val GEOLOCATE = "https://www.googleapis.com/geolocation/v1/geolocate"
        const val BODY = "{\"considerIp\":true}"
        const val NO_REASON = "(no reason given)"
        const val REASON_CHARS = 140
        val json = Json { ignoreUnknownKeys = true }
    }
}
