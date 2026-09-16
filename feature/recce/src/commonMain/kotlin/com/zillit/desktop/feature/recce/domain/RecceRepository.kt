package com.zillit.desktop.feature.recce.domain

import com.zillit.desktop.core.common.ZillitResult

/** The recce service (`recceapi`), routes under `/api/v2/recce`. */
interface RecceRepository {

    /**
     * One server page of the production's recces.
     *
     * The service defaults to `limit: 10`, so a bare call caps the list at ten
     * records no matter how many the project has — the web found this the
     * hard way and now drives page / limit / status / search off the query
     * (`ReccePage.jsx`, `loadList`). Empty filters are dropped rather than
     * sent as `?status=`.
     */
    suspend fun recces(query: RecceQuery): ZillitResult<RecceListPage>

    suspend fun recce(id: String): ZillitResult<Recce>

    /** The crew offered by the personnel picker. */
    suspend fun crew(): ZillitResult<List<RecceCrewMember>>

    /** Creates; the returned id is the new record's. */
    suspend fun create(draft: RecceDraft): ZillitResult<String>

    /** Updates in place; the id rides in the body, not the path. */
    suspend fun update(id: String, draft: RecceDraft): ZillitResult<Unit>

    suspend fun delete(id: String): ZillitResult<Unit>

    /** Asks the server to render the report; answers a stored file. */
    suspend fun report(id: String): ZillitResult<RecceReport>
}

/** The list's query — the web's `{ page, limit, status, search }`. */
data class RecceQuery(
    val page: Int = 1,
    val limit: Int = DEFAULT_PAGE_SIZE,
    /** Null lists every status — the web's "all" tab sends no `status`. */
    val status: RecceStatus? = null,
    val search: String = "",
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        val PAGE_SIZES = listOf(10, 20, 50, 100)
    }
}

/** One page of rows plus the project-wide count for the query's tab + search. */
data class RecceListPage(
    val rows: List<Recce>,
    val total: Int,
)

/**
 * Everything the client sends. Distinct from [Recce] because the server owns
 * the id and the version, and because a draft may be blank in ways a saved
 * record cannot — the web saves drafts with no validation at all.
 */
data class RecceDraft(
    val uniqueId: String,
    /** IANA zone, so the server's PDF prints the wall-clock the user typed. */
    val timezone: String,
    val title: String,
    val unit: String,
    val dateMs: Long,
    val station: String,
    val weather: String,
    val crewNote: String,
    val rdv: RecceStop,
    val itinerary: List<RecceStop>,
    val personnel: List<ReccePerson>,
    val status: RecceStatus,
)

/**
 * Host seams the module cannot own: the rendered report and its pages, the
 * map pictures, the geocoder, the OS hand-offs.
 *
 * The web draws its route map and its per-stop previews on a live Google Map;
 * the desktop draws the same pins on Google Static Maps pictures fetched with
 * the production's own key, and resolves pin-less stops through the Geocoding
 * REST, as the web's `RecceRouteMap` does through the JS geocoder.
 */
interface RecceHost {

    /** Fetches the stored report's bytes (a signed storage read, or the served URL). */
    suspend fun fetchReport(report: RecceReport): ZillitResult<ByteArray>

    /** Renders every page of a PDF for the in-app viewer, [widthPx] wide. */
    suspend fun renderPages(pdf: ByteArray, widthPx: Int): ZillitResult<List<ReccePdfPage>>

    /** Saves to Downloads and hands the file to the OS. */
    suspend fun savePdf(fileName: String, bytes: ByteArray): ZillitResult<Unit>

    /** Hands the PDF to the OS print dialog — the web's `handlePrintPdf`. */
    suspend fun printPdf(fileName: String, bytes: ByteArray): ZillitResult<Unit>

    /**
     * A route picture: numbered pins joined by the brand-coloured line, the
     * map fitted around them. Null without a key or on any failure — the
     * card then shows the web's "add an address" prompt instead.
     */
    suspend fun routeMap(pins: List<RoutePin>, widthPx: Int, heightPx: Int, dark: Boolean): ByteArray?

    /** One pin, centred — the form's inline preview once a location is placed. */
    suspend fun previewMap(pin: LatLng, widthPx: Int, heightPx: Int, dark: Boolean): ByteArray?

    /**
     * Resolves an address to a pin, optionally biased to a country (ISO-2),
     * answering the country the hit landed in — the web's second pass
     * re-geocodes outliers biased to the dominant country of the other stops.
     */
    suspend fun geocode(query: String, country: String? = null): GeocodeHit?
}

/** One page of the rendered report, as encoded image bytes. */
data class ReccePdfPage(
    val imageBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
)

/** A pin on the route picture; [number] null draws the web's grey lunch "L" pin. */
data class RoutePin(
    val pin: LatLng,
    val number: Int?,
    val isLunch: Boolean,
)

data class GeocodeHit(
    val pin: LatLng,
    /** ISO-2 country code of the hit, blank when Google did not say. */
    val country: String,
)
