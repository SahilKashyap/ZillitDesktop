@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.notifications

import com.zillit.desktop.feature.notifications.data.bannerFrom
import com.zillit.desktop.feature.notifications.domain.NotificationDecoder
import com.zillit.desktop.feature.notifications.domain.NotificationLabels
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `notification:save` frame as a banner, pinned against the phones:
 * iOS's socket handler drops `ignore` and `self`, its banner paths drop
 * `silent`, and the server wraps the record in whichever envelope it feels
 * like today — a bare object, `{"data": {...}}`, the data as a JSON string,
 * or an array.
 */
class NotificationBannerTest {

    private val labels = object : NotificationLabels {
        override fun translate(key: String, preferMessages: Boolean) = key
        override fun exact(key: String): String? = null
    }
    private val decoder = NotificationDecoder(labels = labels)

    private val record =
        """{"_id":"n1","section":"tools_label","tool":"call_sheet_label","action":"call_sheet_shared","message":"call_sheet_shared_message","path":"{tools_label/call_sheet_label}","is_global":true}"""

    private fun banner(json: String) = bannerFrom(Json.parseToJsonElement(json), decoder)

    @Test
    fun `a bare record banners with the decoded path as its area`() {
        val banner = banner(record)!!
        assertEquals("n1", banner.id)
        assertEquals("tools_label", banner.section)
        assertEquals("tools_label : call_sheet_label", banner.area)
        assertEquals("call_sheet_shared_message", banner.body)
    }

    @Test
    fun `every envelope the server sends unwraps to the same banner`() {
        val wrapped = """{"data":$record}"""
        val asString = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(record)).let { """{"data":$it}""" }
        val asArray = """[$record]"""
        for (envelope in listOf(wrapped, asString, asArray)) {
            assertEquals("n1", banner(envelope)?.id, "envelope failed: $envelope")
        }
    }

    @Test
    fun `a record carrying its own data field is still the record`() {
        val withData = record.dropLast(1) + ""","data":{"anything":"else"}}"""
        assertEquals("n1", banner(withData)?.id)
    }

    @Test
    fun `silent, ignore and self all stay quiet, as boolean or string`() {
        assertNull(banner(record.dropLast(1) + ""","silent":true}"""))
        assertNull(banner(record.dropLast(1) + ""","silent":"true"}"""))
        assertNull(banner(record.dropLast(1) + ""","reference_data":{"ignore":true}}"""))
        assertNull(banner(record.dropLast(1) + ""","reference_data":{"self":"true"}}"""))
    }

    @Test
    fun `frames with no record, no id, or no words to show are dropped`() {
        assertNull(banner("""{"data":{"unrelated":1}}"""))
        assertNull(banner("""{"section":"tools_label","message":"x"}"""))
        assertNull(banner("""{"_id":"n2","section":"tools_label","message":""}"""))
        assertNull(bannerFrom(null, decoder))
    }
}
