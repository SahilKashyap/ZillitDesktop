package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.recce.data.RecceRepositoryImpl
import com.zillit.desktop.feature.recce.domain.GeocodeHit
import com.zillit.desktop.feature.recce.domain.LatLng
import com.zillit.desktop.feature.recce.domain.RecceHost
import com.zillit.desktop.feature.recce.domain.ReccePdfPage
import com.zillit.desktop.feature.recce.domain.RecceReport
import com.zillit.desktop.feature.recce.domain.RecceViewer
import com.zillit.desktop.feature.recce.domain.RoutePin
import com.zillit.desktop.feature.recce.ui.RecceViewModel
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.util.TimeZone
import java.util.UUID

/**
 * Recce's host seams.
 *
 * The report the server renders is a stored file, fetched through the same
 * signed-storage path the notice boards use and rendered to pages in the app
 * — the desktop's equivalent of the web's "open the PDF in a new tab" — or
 * saved to Downloads, or handed to the OS print dialog.
 *
 * The web draws its route and per-stop previews on a live Google Map; the
 * desktop asks Google Static Maps for the same picture (the production's own
 * key from remote config, as the shared-location pictures do) and the
 * Geocoding REST for the stops the web's JS geocoder would resolve. Every
 * failure is a null picture: the page then shows the web's "add an address"
 * prompt rather than an error, since the pins still sit on each stop.
 */
@Suppress("TooManyFunctions") // One function per seam.
internal fun AppGraph.Ready.recceHost(): RecceHost = object : RecceHost {

    private fun mapsKey(): String? = remoteConfigRepository.credentials.value?.googleMapsKey?.takeIf { it.isNotBlank() }

    override suspend fun fetchReport(report: RecceReport): ZillitResult<ByteArray> = when {
        // A directly served URL — fetched bare; it is not an API route.
        report.url != null -> runCatching {
            val response = httpClient.get(report.url!!)
            check(response.status.isSuccess()) { "report fetch answered ${response.status}" }
            response.readRawBytes()
        }.fold(
            onSuccess = { ZillitResult.Success(it) },
            onFailure = { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "report fetch failed")) },
        )
        report.media.isNotBlank() -> noticeMedia.fetch(
            NoticeAttachment(
                media = report.media,
                fileName = report.name,
                bucket = report.bucket,
                region = report.region,
            ),
            preview = false,
        )
        else -> ZillitResult.Failure(ZillitError.Unknown("the server answered no report file"))
    }

    override suspend fun renderPages(pdf: ByteArray, widthPx: Int): ZillitResult<List<ReccePdfPage>> =
        withContext(Dispatchers.IO) {
            PdfBoxWork().renderPages(pdf, widthPx).map { pages ->
                pages.map { page -> ReccePdfPage(page.imageBytes, page.widthPx, page.heightPx) }
            }
        }

    override suspend fun savePdf(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        when (val saved = DownloadsAttachmentStore().save(fileName, bytes)) {
            is ZillitResult.Failure -> saved
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
        }

    /**
     * The OS print dialog over a temporary copy; where the platform offers no
     * print action the file opens instead, so the reader can print from
     * there — the web's own fallback when the iframe print is blocked.
     */
    override suspend fun printPdf(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File.createTempFile("recce-", "-${fileName.safeKeyPart()}").apply {
                    writeBytes(bytes)
                    deleteOnExit()
                }
                val desktop = java.awt.Desktop.getDesktop()
                if (desktop.isSupported(java.awt.Desktop.Action.PRINT)) desktop.print(file) else desktop.open(file)
            }.fold(
                onSuccess = { ZillitResult.Success(Unit) },
                onFailure = { ZillitResult.Failure(ZillitError.Unknown(it.message ?: "print failed")) },
            )
        }

    override suspend fun routeMap(pins: List<RoutePin>, widthPx: Int, heightPx: Int, dark: Boolean): ByteArray? {
        val key = mapsKey() ?: return null
        if (pins.isEmpty()) return null
        val markers = pins.joinToString("") { pin ->
            val colour = if (pin.isLunch) LUNCH_PIN else BRAND_PIN
            // Static Maps labels one character, so pins past 9 go unnumbered.
            val label = pin.number?.takeIf { it in 1..MAX_LABEL }?.let { "label:$it%7C" }.orEmpty()
            "&markers=color:0x$colour%7C$label${pin.pin.lat},${pin.pin.lng}"
        }
        val route = pins.filterNot { it.isLunch }.map { it.pin }
        val path = if (route.size > 1) {
            "&path=color:0x${BRAND_PIN}E6%7Cweight:4%7C" + route.joinToString("%7C") { "${it.lat},${it.lng}" }
        } else {
            ""
        }
        val single = if (route.size == 1) "&zoom=14" else ""
        return fetchPicture("$STATIC_MAPS?size=${widthPx}x$heightPx&scale=2$single$markers$path${style(dark)}&key=$key")
    }

    override suspend fun previewMap(pin: LatLng, widthPx: Int, heightPx: Int, dark: Boolean): ByteArray? {
        val key = mapsKey() ?: return null
        return fetchPicture(
            "$STATIC_MAPS?center=${pin.lat},${pin.lng}&zoom=15&size=${widthPx}x$heightPx&scale=2" +
                "&markers=color:0x$BRAND_PIN%7C${pin.lat},${pin.lng}${style(dark)}&key=$key",
        )
    }

    override suspend fun geocode(query: String, country: String?): GeocodeHit? {
        val key = mapsKey() ?: return null
        val bias = country?.let { "&components=country:${it.encodeURLParameter()}" }.orEmpty()
        val body = runCatching {
            val response = httpClient.get("$GEOCODE?address=${query.encodeURLParameter()}$bias&key=$key")
            if (response.status.isSuccess()) response.bodyAsText() else null
        }.onFailure { ZillitLog.w(TAG) { "geocode failed: ${it::class.simpleName}" } }.getOrNull()
        return body?.let(::firstGeocodeHit)
    }

    /** Fetches a Static Maps picture; the key rides the query and is never logged. */
    private suspend fun fetchPicture(url: String): ByteArray? = runCatching {
        val response = httpClient.get(url)
        if (response.status.isSuccess()) {
            response.readRawBytes()
        } else {
            ZillitLog.w(TAG) { "map picture refused: ${response.status.value}" }
            null
        }
    }.onFailure { ZillitLog.w(TAG) { "map picture failed: ${it::class.simpleName}" } }.getOrNull()

    /** The web's night-mode tile styling, reduced to what Static Maps accepts. */
    private fun style(dark: Boolean): String = if (!dark) {
        ""
    } else {
        "&style=element:geometry%7Ccolor:0x212121" +
            "&style=element:labels.icon%7Cvisibility:off" +
            "&style=element:labels.text.fill%7Ccolor:0x757575" +
            "&style=element:labels.text.stroke%7Ccolor:0x212121" +
            "&style=feature:road%7Celement:geometry.fill%7Ccolor:0x2c2c2c" +
            "&style=feature:road.highway%7Celement:geometry%7Ccolor:0x3c3c3c" +
            "&style=feature:water%7Celement:geometry%7Ccolor:0x000000" +
            "&style=feature:poi.park%7Celement:geometry%7Ccolor:0x181818"
    }
}

internal fun AppGraph.Ready.buildRecce(permissions: () -> ProjectPermissions) = RecceViewModel(
    repository = RecceRepositoryImpl(apiClient, config),
    host = recceHost(),
    units = { unitRepository.joinUnits() },
    resolveViewer = {
        RecceViewer.from(permissions(), projectContext?.context?.value?.profile?.userId.orEmpty())
    },
    newUniqueId = { UUID.randomUUID().toString() },
    // The IANA zone the server's PDF prints wall-clocks in — the web sends
    // `Intl.DateTimeFormat().resolvedOptions().timeZone`.
    timezone = { TimeZone.getDefault().id },
    currentProjectId = { projectContext?.context?.value?.project?.projectId },
    // A scout day added by somebody else should not wait for a reopen.
    events = socketEvents,
    // A press without the right asks an admin, as every other tool does.
    rights = rightsRequests,
)

/** The first result of a Geocoding answer as a pin plus its ISO-2 country, or null when it has none. */
private fun firstGeocodeHit(body: String): GeocodeHit? {
    val first = (Json.parseToJsonElement(body) as? JsonObject)
        ?.get("results")?.let { it as? JsonArray }?.firstOrNull() as? JsonObject
    val location = (first?.get("geometry") as? JsonObject)?.get("location") as? JsonObject
    val lat = (location?.get("lat") as? JsonPrimitive)?.doubleOrNull
    val lng = (location?.get("lng") as? JsonPrimitive)?.doubleOrNull
    if (first == null || lat == null || lng == null) return null
    val countryCode = (first["address_components"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { component ->
            (component["types"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "country" } == true
        }
        ?.get("short_name")?.let { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    return GeocodeHit(LatLng(lat, lng), countryCode)
}

private const val TAG = "Recce"
private const val STATIC_MAPS = "https://maps.googleapis.com/maps/api/staticmap"
private const val GEOCODE = "https://maps.googleapis.com/maps/api/geocode/json"
private const val BRAND_PIN = "F99300"
private const val LUNCH_PIN = "9CA3AF"
private const val MAX_LABEL = 9
