package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.time.Instant

/**
 * How the crew form reads and writes its draft (`CrewDetailsEditorPanel.jsx`,
 * `AddressFields.jsx`, `UKPayrollFieldset.jsx`) — values exactly as typed, so
 * a space typed at the end of a line is still there on the next keystroke.
 */
object CrewFormValues {

    val GENDERS: List<Pair<String, String>>
        get() = listOf(
            "female" to str(S.female),
            "male" to str(S.male),
            "non_binary" to str(S.desktop_gender_non_binary),
            "other" to str(S.other),
            "prefer_not_to_say" to str(S.desktop_prefer_not_to_say),
        )

    /** Stored as the label itself. */
    val RIGHT_TO_WORK = listOf("Passport", "UK Citizen / Settled Status", "UK Visa", "EU Pre-Settled", "Work Permit")

    val NI_CATEGORIES = setOf("A", "B", "C", "D", "E", "F", "H", "I", "J", "K", "L", "M", "N", "S", "V", "X", "Z")
    val NI_CATEGORY_HINT: String get() = str(S.desktop_dm_ni_category_hint)
    val PENSION_NOTE: String get() = str(S.desktop_dm_pension_note)

    private const val WHOLE_LIMIT = 1e15

    /** An address as typed: the six keys untrimmed; a legacy string reads as its line 1. */
    fun address(element: JsonElement?): DealAddress {
        val json = element as? JsonObject ?: return DealAddress.of(element)
        fun raw(key: String) = (json[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        return DealAddress(raw("line1"), raw("line2"), raw("city"), raw("state"), raw("postal_code"), raw("country"))
    }

    /** `{ [key]: value || null }` for all six keys: only an emptied box changes, to null. */
    fun addressJson(address: DealAddress): JsonObject = buildJsonObject {
        put("line1", address.line1?.ifEmpty { null })
        put("line2", address.line2?.ifEmpty { null })
        put("city", address.city?.ifEmpty { null })
        put("state", address.state?.ifEmpty { null })
        put("postal_code", address.postalCode?.ifEmpty { null })
        put("country", address.country?.ifEmpty { null })
    }

    /** The calendar date an epoch falls on in [zone]; null for none. */
    fun dateOf(millis: Long?, zone: TimeZone): LocalDate? =
        millis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date }

    /**
     * Midnight of [date] in [zone], as the wire's epoch ms: the crew form's date
     * of birth is local midnight, the P45 leaving date UTC midnight.
     */
    fun epochOf(date: LocalDate?, zone: TimeZone): Long? = date?.atStartOfDayIn(zone)?.toEpochMilliseconds()

    /** `normalizeNiCategory`: trimmed, upper-cased, letters only, the first of them. */
    fun niCategory(value: String): String = value.trim().uppercase().filter { it in 'A'..'Z' }.take(1)

    /** A hint, never an error. */
    fun isNiCategoryUnknown(value: String?): Boolean {
        val s = value?.trim()?.uppercase().orEmpty()
        return s.isNotEmpty() && s !in NI_CATEGORIES
    }

    /** A committed amount as JSON: whole numbers as integers, the way a JS number serialises. */
    fun amount(value: Double?): JsonElement = when {
        value == null -> JsonNull
        value % 1.0 == 0.0 && abs(value) < WHOLE_LIMIT -> JsonPrimitive(value.toLong())
        else -> JsonPrimitive(value)
    }

    /** Account number state: digits only, no length cap. */
    fun accountNumber(value: String): String = value.filter { it.isDigit() }

    /** A bank additional-detail keystroke the field type allows. */
    fun allowsDetailInput(type: String, value: String): Boolean = when (type) {
        "number" -> Regex("^-?\\d*\\.?\\d*$").matches(value)
        "phone" -> Regex("^[+\\d\\s()-]*$").matches(value)
        else -> true
    }

    /** Soft validity for a detail once it is left: shown as a red border, never a block. */
    fun detailLooksValid(type: String, value: String): Boolean {
        if (value.isEmpty()) return true
        return when (type) {
            "email" -> Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(value)
            "url" -> Regex("^\\S+\\.\\S+$").matches(value)
            "number" -> value.toDoubleOrNull() != null
            else -> true
        }
    }
}
