package com.zillit.desktop.feature.recce.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.recce.domain.Recce
import com.zillit.desktop.feature.recce.domain.RecceCrewMember
import com.zillit.desktop.feature.recce.domain.RecceDraft
import com.zillit.desktop.feature.recce.domain.ReccePerson
import com.zillit.desktop.feature.recce.domain.RecceReport
import com.zillit.desktop.feature.recce.domain.RecceRepository
import com.zillit.desktop.feature.recce.domain.RecceStatus
import com.zillit.desktop.feature.recce.domain.RecceStop
import com.zillit.desktop.feature.recce.domain.StopKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The recce service (`recceapi`, `/api/v2/recce`).
 *
 * Transcription notes, from the web's `recceApi/api.js` and `RecceForm`:
 *
 *  - HTTP 200 + `status:0` is a failure, checked on every call;
 *  - the LIST answers `data.data` (a wrapper with paging the web ignores);
 *    the single read answers `data` itself; the crew answers either;
 *  - update is `PUT /recce` with `recce_id` in the body — no `/recce/{id}`
 *    write route; delete is `PUT /recce/delete {recce_ids:[...]}`;
 *  - the body is snake_case throughout, longitude is `long`, missing times
 *    are `0` and missing coordinates `null` — never the other way round;
 *  - `photo` is always `null`: the model has the slot, the web never fills it.
 */
class RecceRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
) : RecceRepository {

    private val base = config.apiV2(ZillitService.Recce).trimEnd('/') + "/recce"

    override suspend fun recces(): ZillitResult<List<Recce>> =
        get(base).mapData { data ->
            // The list is wrapped once more than the single read.
            val rows = (data as? JsonObject)?.get("data") as? JsonArray ?: data as? JsonArray
            rows.items().mapNotNull { parseRecce(it as? JsonObject) }
        }

    override suspend fun recce(id: String): ZillitResult<Recce> =
        get("$base/$id").flatMapData { data ->
            parseRecce(data as? JsonObject)
                ?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Unknown("recce $id answered no record"))
        }

    override suspend fun crew(): ZillitResult<List<RecceCrewMember>> =
        get("$base/crew").mapData { data ->
            val rows = data as? JsonArray ?: (data as? JsonObject)?.get("data") as? JsonArray
            rows.items().mapNotNull { parseCrew(it as? JsonObject) }
        }

    override suspend fun create(draft: RecceDraft): ZillitResult<String> =
        apiClient.envelope(HttpVerb.Post, base, RequestModule.ProjectUser, recceWire(draft, id = null))
            .flatMapData { data ->
                (data as? JsonObject)?.text("_id")?.takeIf { it.isNotBlank() }
                    ?.let { ZillitResult.Success(it) }
                    ?: ZillitResult.Failure(ZillitError.Unknown("create answered no _id"))
            }

    override suspend fun update(id: String, draft: RecceDraft): ZillitResult<Unit> =
        write(HttpVerb.Put, base, recceWire(draft, id = id))

    override suspend fun delete(id: String): ZillitResult<Unit> =
        write(
            HttpVerb.Put,
            "$base/delete",
            buildJsonObject { put("recce_ids", buildJsonArray { add(JsonPrimitive(id)) }) },
        )

    override suspend fun report(id: String): ZillitResult<RecceReport> =
        apiClient.envelope(HttpVerb.Post, "$base/$id/pdf", RequestModule.ProjectUser, null)
            .flatMapData { data ->
                val obj = data as? JsonObject
                    ?: return@flatMapData ZillitResult.Failure(ZillitError.Unknown("pdf answered no file"))
                ZillitResult.Success(
                    RecceReport(
                        url = obj.text("url").takeIf { it.isNotBlank() },
                        media = obj.text("media"),
                        bucket = obj.text("bucket"),
                        region = obj.text("region"),
                        name = obj.text("name").ifBlank { "recce.pdf" },
                    ),
                )
            }

    // Transport ------------------------------------------------------------

    private suspend fun get(url: String): ZillitResult<ApiEnvelope> =
        apiClient.envelope(HttpVerb.Get, url, RequestModule.ProjectUser)

    private suspend fun write(verb: HttpVerb, url: String, body: JsonObject?): ZillitResult<Unit> =
        apiClient.envelope(verb, url, RequestModule.ProjectUser, body).mapData { }

    private inline fun <T> ZillitResult<ApiEnvelope>.mapData(transform: (JsonElement?) -> T): ZillitResult<T> =
        flatMapData { ZillitResult.Success(transform(it)) }

    private inline fun <T> ZillitResult<ApiEnvelope>.flatMapData(
        transform: (JsonElement?) -> ZillitResult<T>,
    ): ZillitResult<T> =
        when (this) {
            is ZillitResult.Failure -> this
            is ZillitResult.Success ->
                if (data.status == 0) {
                    ZillitResult.Failure(
                        ZillitError.Http(status = HTTP_OK, serverMessage = data.message ?: "something_went_wrong"),
                    )
                } else {
                    transform(data.data)
                }
        }

    private companion object {
        const val HTTP_OK = 200
    }
}

/**
 * The create/update body — the web's `buildPayload`, key for key and in its
 * order. `recce_id` is appended LAST on update, as the web does.
 */
@Suppress("LongMethod") // The wire contract, key for key — splitting it hides the order.
internal fun recceWire(draft: RecceDraft, id: String?): JsonObject = buildJsonObject {
    put("unique_id", draft.uniqueId)
    put("timezone", draft.timezone)
    put("title", draft.title.trim())
    put("unit", draft.unit)
    put("recce_date", draft.dateMs)
    put("station", draft.station.trim())
    put("weather", draft.weather.trim())
    put("crew_note", draft.crewNote.trim())
    put(
        "rdv",
        buildJsonObject {
            put("time", draft.rdv.timeMs)
            put("place", draft.rdv.place.trim())
            put("address", draft.rdv.address.trim())
            put("w3w", draft.rdv.w3w.trim())
            putCoordinate("lat", draft.rdv.lat)
            putCoordinate("long", draft.rdv.long)
        },
    )
    put(
        "itinerary",
        buildJsonArray {
            draft.itinerary.forEach { stop ->
                add(
                    buildJsonObject {
                        put("time", stop.timeMs)
                        put("end_time", stop.endTimeMs)
                        put("kind", stop.kind.wire)
                        put("place", stop.place.trim())
                        put("address", stop.address.trim())
                        put("w3w", stop.w3w.trim())
                        putCoordinate("lat", stop.lat)
                        putCoordinate("long", stop.long)
                        put("description", stop.description.trim())
                        put("contact", stop.contact.trim())
                        put("travel", stop.travel.trim())
                        put("photo", JsonNull)
                    },
                )
            }
        },
    )
    put(
        "personnel",
        buildJsonArray {
            draft.personnel.forEach { person ->
                add(
                    buildJsonObject {
                        person.userId?.let { put("user_id", it) } ?: put("user_id", JsonNull)
                        put("name", person.name.trim())
                        put("role", person.role.trim())
                        put("email", person.email.trim())
                        put("contact", person.contact.trim())
                        put("note", person.note.trim())
                    },
                )
            }
        },
    )
    put("status", draft.status.wire)
    if (id != null) put("recce_id", id)
}

/** `null` for a missing coordinate — `0` is a real place on the equator. */
private fun kotlinx.serialization.json.JsonObjectBuilder.putCoordinate(key: String, value: Double?) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

// Parsers ------------------------------------------------------------------

internal fun parseRecce(obj: JsonObject?): Recce? {
    if (obj == null) return null
    val id = obj.text("_id", "id").takeIf { it.isNotBlank() } ?: return null
    return Recce(
        id = id,
        uniqueId = obj.text("unique_id"),
        title = obj.text("title"),
        unit = obj.text("unit"),
        dateMs = obj.long("recce_date") ?: 0L,
        station = obj.text("station"),
        weather = obj.text("weather"),
        crewNote = obj.text("crew_note"),
        rdv = parseStop(obj["rdv"] as? JsonObject) ?: RecceStop(kind = StopKind.Rendezvous),
        itinerary = (obj["itinerary"] as? JsonArray).items().mapNotNull { parseStop(it as? JsonObject) },
        personnel = (obj["personnel"] as? JsonArray).items().mapNotNull { parsePerson(it as? JsonObject) },
        status = RecceStatus.fromWire(obj.text("status")),
        version = obj.int("version") ?: 1,
    )
}

private fun parseStop(obj: JsonObject?): RecceStop? {
    if (obj == null) return null
    return RecceStop(
        timeMs = obj.long("time") ?: 0L,
        endTimeMs = obj.long("end_time") ?: 0L,
        kind = StopKind.fromWire(obj.text("kind")),
        place = obj.text("place"),
        address = obj.text("address"),
        w3w = obj.text("w3w"),
        lat = obj.double("lat"),
        long = obj.double("long", "lng"),
        description = obj.text("description"),
        contact = obj.text("contact"),
        travel = obj.text("travel"),
    )
}

private fun parsePerson(obj: JsonObject?): ReccePerson? {
    if (obj == null) return null
    return ReccePerson(
        userId = obj.text("user_id").takeIf { it.isNotBlank() },
        name = obj.text("name"),
        role = obj.text("role"),
        email = obj.text("email"),
        contact = obj.text("contact"),
        note = obj.text("note"),
    )
}

private fun parseCrew(obj: JsonObject?): RecceCrewMember? {
    if (obj == null) return null
    val userId = obj.text("user_id", "_id").takeIf { it.isNotBlank() } ?: return null
    return RecceCrewMember(
        userId = userId,
        name = obj.text("name", "full_name"),
        role = obj.text("role", "designation"),
        email = obj.text("email"),
        contact = obj.text("contact", "phone"),
    )
}

// Lenient readers ----------------------------------------------------------

private fun JsonObject.firstOf(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

private fun JsonObject.text(vararg names: String): String =
    (firstOf(*names) as? JsonPrimitive)?.content.orEmpty()

private fun JsonObject.long(vararg names: String): Long? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonObject.int(vararg names: String): Int? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

private fun JsonObject.double(vararg names: String): Double? =
    (firstOf(*names) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

private fun JsonArray?.items(): List<JsonElement> = this?.toList() ?: emptyList()
