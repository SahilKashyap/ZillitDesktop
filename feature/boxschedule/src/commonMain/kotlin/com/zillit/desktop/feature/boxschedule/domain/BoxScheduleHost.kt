package com.zillit.desktop.feature.boxschedule.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.TimeZone
import kotlin.random.Random

/**
 * What the diary needs from the app around it that is not the diary's own
 * service: the PDF's destinations, the crew directory, remembered view
 * choices, the History badge, and the printer.
 *
 * One value rather than a constructor parameter each, so a host that wires
 * none of it — a test — writes nothing, and a new seam is not a change to
 * every call site.
 */
data class BoxScheduleHost(
    /** Saves the generated PDF and opens it; null on a host without Downloads. */
    val transfer: DiaryPdfTransfer? = null,
    /** Publishes it into Document Distribution; null on a host without the library. */
    val publisher: DiaryPdfPublisher? = null,
    /** Posting rights on Document Distribution — the rights of the tool published INTO. */
    val canPublish: () -> Boolean = { false },
    /** Stamped onto the PDF — the phones and the web send the user's full name. */
    val watermark: () -> String = { "" },
    val directory: DiaryDirectory = DiaryDirectory.None,
    val preferences: DiaryPreferences = DiaryPreferences.InMemory(),
    /** Unread diary notifications — the web's `newToolUnitBadges.box_schedule_label`. */
    val historyBadge: Flow<Int> = emptyFlow(),
    /** Marks those read when History opens — `notification:read`, module `box_schedule_label`. */
    val onHistoryViewed: suspend () -> Unit = {},
    /** Prints a page of HTML through the system — Print Selected. Null hides the action. */
    val printer: DiaryPrinter? = null,
    val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    /** A fresh id for a newly-added external guest — the web mints a UUID v4. */
    val newId: () -> String = ::randomUuid,
)

/** The page's remembered choices — the web's `localStorage` keys, per machine. */
interface DiaryPreferences {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)

    class InMemory : DiaryPreferences {
        private val values = mutableMapOf<String, String>()
        override suspend fun read(key: String): String? = values[key]
        override suspend fun write(key: String, value: String) {
            values[key] = value
        }
    }

    companion object {
        const val DEFAULT_VIEW = "boxScheduleDefaultView"
        const val CALENDAR_MODE = "box-schedule-calendar-mode"
        const val LIST_MODE = "box-schedule-list-mode"
    }
}

/** Hands a printable page to the operating system's print flow. */
fun interface DiaryPrinter {
    suspend fun print(html: String): ZillitResult<Unit>
}

/** A random RFC 4122 version-4 id, lower-case and hyphenated. */
fun randomUuid(): String {
    val bytes = Random.nextBytes(UUID_BYTES)
    bytes[VERSION_BYTE] = ((bytes[VERSION_BYTE].toInt() and LOW_NIBBLE) or VERSION_4).toByte()
    bytes[VARIANT_BYTE] = ((bytes[VARIANT_BYTE].toInt() and VARIANT_MASK) or VARIANT_RFC).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX).padStart(2, '0') }
    var cursor = 0
    return UUID_GROUPS.joinToString("-") { length ->
        hex.substring(cursor, cursor + length).also { cursor += length }
    }
}

/** 8-4-4-4-12 hex digits. */
private val UUID_GROUPS = listOf(8, 4, 4, 4, 12)
private const val UUID_BYTES = 16
private const val VERSION_BYTE = 6
private const val VARIANT_BYTE = 8
private const val LOW_NIBBLE = 0x0F
private const val VERSION_4 = 0x40
private const val VARIANT_MASK = 0x3F
private const val VARIANT_RFC = 0x80
private const val BYTE_MASK = 0xFF
private const val HEX = 16
