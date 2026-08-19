@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.notifications

import com.zillit.desktop.feature.notifications.data.NotificationsEndpoints
import com.zillit.desktop.feature.notifications.data.readNotification
import com.zillit.desktop.feature.notifications.domain.NotificationDecoder
import com.zillit.desktop.feature.notifications.domain.NotificationLabels
import com.zillit.desktop.feature.notifications.domain.forDisplay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The routes and the row reader, pinned against Android's `ApiUrl` and `NotificationDataModel`. */
class NotificationsWireTest {

    private val base = "https://notificationapi-dev.zillit.com/api/v2/"

    private val labels = object : NotificationLabels {
        override fun translate(key: String, preferMessages: Boolean) = key
        override fun exact(key: String): String? = null
    }
    private val decoder = NotificationDecoder(labels = labels)

    @Test
    fun `routes are the notification host's, verbatim`() {
        assertEquals("${base}project/notifications/1700/previous", NotificationsEndpoints.page(base, 1700))
        assertEquals("${base}project/notifications/newest", NotificationsEndpoints.newestPageName(base))
        assertEquals("${base}levelmarkread/global_label/1700", NotificationsEndpoints.markRead(base, "global_label", 1700))
        assertEquals("${base}markdelete/abc/1700", NotificationsEndpoints.delete(base, "abc", 1700))
        assertEquals("${base}delete-all", NotificationsEndpoints.deleteAll(base))
        assertEquals("global_label", NotificationsEndpoints.GLOBAL_SEGMENT)
    }

    @Test
    fun `a row is read with its path, target and stamps, unit as string or number`() {
        val row = Json.parseToJsonElement(
            """{"_id":"n1","notification_uuid":"u1","project_id":"p","section":"tools_label","tool":"call_sheet_label","unit":42,"action":"call_sheet_shared","message":"call_sheet_shared_message","path":"{tools_label/call_sheet_label}","created":1700000000000,"updated":1700000001000,"is_global":true,"message_read":false,"reference_id":"r1","reference_data":{"messageElements":[{"search":"{{name}}","replacer":7}]}}""",
        ) as JsonObject
        val notification = readNotification(row, decoder)!!
        assertEquals("n1", notification.id)
        assertEquals("u1", notification.uuid)
        assertEquals("tools_label : call_sheet_label", notification.pathLabel)
        assertEquals("42", notification.target.unit)
        assertEquals("call_sheet_label", notification.target.tool)
        assertEquals("r1", notification.target.referenceId)
        assertEquals(1700000000000L, notification.createdMillis)
        assertEquals(1700000001000L, notification.updatedMillis)
        assertFalse(notification.read)
        assertTrue(notification.isGlobal)
    }

    @Test
    fun `a row without an id is dropped, and one flagged non-global stays off the list`() {
        assertNull(readNotification(Json.parseToJsonElement("""{"message":"x"}""") as JsonObject, decoder))
        val hidden = readNotification(
            Json.parseToJsonElement("""{"_id":"n2","message":"x","created":5}""") as JsonObject,
            decoder,
        )!!
        val shown = hidden.copy(id = "n3", isGlobal = true, createdMillis = 9)
        val newer = shown.copy(id = "n4", createdMillis = 10)
        assertEquals(listOf("n4", "n3"), listOf(hidden, shown, newer, shown).forDisplay().map { it.id })
    }
}
