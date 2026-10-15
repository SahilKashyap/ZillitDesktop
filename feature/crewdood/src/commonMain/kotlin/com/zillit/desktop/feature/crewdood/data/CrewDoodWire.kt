package com.zillit.desktop.feature.crewdood.data

import com.zillit.desktop.feature.crewdood.domain.DoodCalc
import com.zillit.desktop.feature.crewdood.domain.DoodCalendar
import com.zillit.desktop.feature.crewdood.domain.DoodCrewDepartment
import com.zillit.desktop.feature.crewdood.domain.DoodCrewUnit
import com.zillit.desktop.feature.crewdood.domain.DoodCrewUser
import com.zillit.desktop.feature.crewdood.domain.DoodDeal
import com.zillit.desktop.feature.crewdood.domain.DoodHoliday
import com.zillit.desktop.feature.crewdood.domain.DoodMasterDepartment
import com.zillit.desktop.feature.crewdood.domain.DoodMasterDesignation
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * Wire → model for Crew DOOD's four reads.
 *
 * Read as `JsonElement` rather than through `@Serializable` DTOs on purpose:
 * a deal names its department by any of three keys, a department by any of
 * five, and which of them a given project's crew list actually ships is still
 * an open question on the backend (handoff §5.6). Collecting every alias the
 * payload carries is the whole job here, and a strict DTO would have to name
 * them all anyway.
 *
 * **The two zones.** A deal's `start_date` is a UTC midnight and its
 * `end_date` the last millisecond of a UTC day, so both are read in
 * [TimeZone.UTC]; the Production Calendar's `activity_date` is a LOCAL
 * midnight (the calendar matches it against today's local midnight), so it is
 * read in the system zone. Swapping them shifts bars by a day either side of
 * UTC.
 */
internal object CrewDoodWire {

    // -- crew list ----------------------------------------------------------

    fun units(data: JsonElement?, labelOf: (String) -> String): List<DoodCrewUnit> =
        data.array().mapNotNull { it as? JsonObject }.map { unit ->
            DoodCrewUnit(
                departments = unit["departments"].array().mapNotNull { it as? JsonObject }.map { dept ->
                    department(dept, labelOf)
                },
            )
        }

    private fun department(dept: JsonObject, labelOf: (String) -> String): DoodCrewDepartment {
        val key = dept.text("department_name").orEmpty()
        return DoodCrewDepartment(
            name = key.takeIf { it.isNotBlank() }?.let(labelOf).orEmpty(),
            aliases = dept.aliases("department_id", "_id", "identifier", "department_identifier", "department_name"),
            users = dept["users"].array().mapNotNull { it as? JsonObject }.map { user ->
                DoodCrewUser(
                    userId = user.text("user_id").orEmpty(),
                    name = user.text("full_name").orEmpty(),
                    role = user.text("designation_name")?.takeIf { it.isNotBlank() }?.let(labelOf).orEmpty(),
                    picture = (user["profile_picture"] as? JsonObject)?.picture(),
                )
            },
        )
    }

    /** The picture reference an avatar is signed from — the thumbnail, else the file. */
    private fun JsonObject.picture(): String? =
        (text("thumbnail") ?: text("media"))?.takeIf { it.isNotBlank() }

    // -- deals --------------------------------------------------------------

    fun deals(data: JsonElement?): List<DoodDeal> =
        data.array().mapNotNull { it as? JsonObject }.map { row ->
            val crew = row["crew_details"] as? JsonObject
            val deal = row["deal"] as? JsonObject
            DoodDeal(
                id = row.text("_id") ?: row.text("id").orEmpty(),
                status = row.text("status").orEmpty(),
                userId = row.text("user_id").orEmpty(),
                reference = row.text("deal_reference").orEmpty(),
                crewName = (crew?.text("crew_name") ?: crew?.text("full_legal_name")).orEmpty().trim(),
                customDesignation = crew?.text("custom_designation").orEmpty(),
                designationRefs = refs(
                    crew?.text("designation_id"),
                    row.text("designation_id"),
                    crew?.text("designation_identifier"),
                ),
                departmentRefs = refs(
                    crew?.text("department_id"),
                    row.text("department_id"),
                    crew?.text("department_identifier"),
                ),
                start = deal?.get("start_date").utcDay(),
                end = deal?.get("end_date").utcDay(),
                // The deactivation stamp. The web's model reads `last_pay_day`
                // alone, which is the notice TEMPLATE's variable name — the
                // deal document itself carries `last_pay_date` (Account Hub's
                // `dealStatus.js`: "the deactivate endpoint stamps
                // `last_pay_date`"), so both are read here and the earlier
                // finish still lands where the rule says it should.
                lastPayDay = (row["last_pay_day"] ?: row["last_pay_date"]).utcDay(),
            )
        }

    // -- production days ----------------------------------------------------

    /** The system `production_dates` entry's days, as LOCAL days. */
    fun shootDays(data: JsonElement?): List<LocalDate> {
        val entry = data.array()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.text("identifier") == "production_dates" }
            ?: return emptyList()
        return entry["production_dates"].array()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { it["activity_date"].localDay() }
            .distinct()
            .sorted()
    }

    // -- department master --------------------------------------------------

    fun directory(data: JsonElement?, labelOf: (String) -> String): List<DoodMasterDepartment> =
        data.array().mapNotNull { it as? JsonObject }.map { dept ->
            DoodMasterDepartment(
                name = dept.text("department_name")?.takeIf { it.isNotBlank() }?.let(labelOf).orEmpty(),
                aliases = dept.aliases(
                    "department_id",
                    "_id",
                    "identifier",
                    "department_identifier",
                    "department_name",
                ),
                designations = dept["designations"].array().mapNotNull { it as? JsonObject }.map { designation ->
                    DoodMasterDesignation(
                        name = designation.text("designation_name")
                            ?.takeIf { it.isNotBlank() }
                            ?.let(labelOf)
                            .orEmpty(),
                        aliases = designation.aliases(
                            "designation_id",
                            "_id",
                            "identifier",
                            "designation_identifier",
                            "designation_name",
                        ),
                    )
                },
            )
        }

    // -- project calendar ---------------------------------------------------

    /**
     * `{ weekly_off: [0–6], holidays: [{ date: 'YYYY-MM-DD', name }] }`.
     *
     * A holiday date is a plain day string, never a stamp, so no zone can move
     * it. `weekly_off` absent (an empty record, or a service that answers
     * `{}`) means the default — Saturday and Sunday — not "nobody has a day
     * off"; a saved empty array means exactly what it says.
     */
    fun calendar(data: JsonElement?): DoodCalendar? {
        val body = data as? JsonObject ?: return null
        val weekly = (body["weekly_off"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.toIntOrNull() }
        val holidays = body["holidays"].array().mapNotNull { it as? JsonObject }.mapNotNull { holiday ->
            val date = holiday.text("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            DoodHoliday(date = date, name = holiday.text("name").orEmpty())
        }
        if (weekly == null && holidays.isEmpty()) return null
        return DoodCalc.calendarFrom(weekly, holidays)
    }

    /** The body a save sends: the same two fields, sorted so a diff is readable. */
    fun calendarBody(calendar: DoodCalendar): JsonObject = buildJsonObject {
        put(
            "weekly_off",
            buildJsonArray { calendar.weeklyOff.sorted().forEach { add(JsonPrimitive(it)) } },
        )
        put(
            "holidays",
            buildJsonArray {
                calendar.holidays.entries.sortedBy { it.key }.forEach { (date, name) ->
                    add(
                        buildJsonObject {
                            put("date", date.toString())
                            put("name", name.trim())
                        },
                    )
                }
            },
        )
    }

    // -- primitives ---------------------------------------------------------

    private fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Every value a department or designation is known by, in the payload's own precedence. */
    private fun JsonObject.aliases(vararg keys: String): List<String> =
        keys.mapNotNull { text(it)?.trim()?.takeIf { value -> value.isNotBlank() } }.distinct()

    private fun refs(vararg values: String?): List<String> =
        values.mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }.distinct()

    /** Epoch milliseconds, whether the service sent a number or a numeric string. */
    private fun JsonElement?.millis(): Long? =
        (this as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toLong()

    private fun JsonElement?.utcDay(): LocalDate? =
        millis()?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }

    private fun JsonElement?.localDay(): LocalDate? =
        millis()?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).date }
}
