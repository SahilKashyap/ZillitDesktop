package com.zillit.desktop.core.badges

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wire rows and frames, as the notification service spells them. */
class NotificationPayloadsTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `a wire row reads leniently`() {
        val record = notificationRecordFrom(
            json(
                """{"notification_uuid":"n1","_id":"m1","project_id":"p1","device_id":"d1","section":"home_label",
                    "tool":"calendar_label",
                    "unit":"u1","level_1":"l1","reference_id":"ref","sender":"s","receiver":"r",
                    "created":"1700000000000","updated":1700000001000,"message_read":"false","is_global":"1",
                    "deleted":0,
                    "reference_data":"{\"chat_room_id\":\"room\",\"sender_id\":\"peer\",\"chat\":{\"unit_id\":\"cu\"},
                    \"calendar_data\":{\"end_datetime\":\"42\"},\"ignore\":false}"}""",
            ) as JsonObject,
            fromApi = true,
        )

        assertNotNull(record)
        assertEquals("n1", record.id)
        assertEquals("m1", record.mongoId)
        assertEquals("d1", record.deviceId)
        assertEquals(1_700_000_000_000L, record.created)
        assertEquals(1_700_000_001_000L, record.updated)
        assertTrue(!record.messageRead)
        assertTrue(record.isGlobal)
        assertEquals("room", record.chatRoomId)
        assertEquals("peer", record.senderId)
        assertEquals("cu", record.chatUnitId)
        assertEquals(42L, record.calendarEnd)
        assertTrue(record.fromApi)
        assertTrue(record.counts)
    }

    @Test
    fun `a row without a uuid falls back to the reference notification id, then _id`() {
        val byReference = notificationRecordFrom(
            json("""{"project_id":"p","reference_data":{"notification_id":"ref-id"}}""") as JsonObject, fromApi = true,
        )
        assertEquals("ref-id", byReference?.id)
        val byMongo = notificationRecordFrom(json("""{"project_id":"p","_id":"mongo"}""") as JsonObject, fromApi = true)
        assertEquals("mongo", byMongo?.id)
        assertNull(notificationRecordFrom(json("""{"project_id":"p"}""") as JsonObject, fromApi = true))
        assertNull(notificationRecordFrom(json("""{"notification_uuid":"n"}""") as JsonObject, fromApi = true))
        assertEquals(
            "fallback",
            notificationRecordFrom(json("""{"notification_uuid":"n"}""") as JsonObject, true, "fallback")?.projectId,
        )
    }

    @Test
    fun `a save frame yields its records, minus what no client counts`() {
        val base = """"notification_uuid":"%s","project_id":"p","section":"tools_label","tool":"sides_label""""
        val frame = json(
            """{"data":[
                {${base.format("keep")}},
                {${base.format("silent")},"silent":true},
                {${base.format("ignored")},"reference_data":{"ignore":true}},
                {${base.format("mine")},"reference_data":{"self":true}}
            ]}""",
        )
        assertEquals(listOf("keep"), ledgerArrivalsFrom(frame).map { it.id })
    }

    @Test
    fun `data sent as a string, a single record, or an array still opens`() {
        val asString = ledgerArrivalsFrom(json("""{"data":"{\"notification_uuid\":\"n\",\"project_id\":\"p\"}"}"""))
        assertEquals(listOf("n"), asString.map { it.id })
        val bare = ledgerArrivalsFrom(json("""{"notification_uuid":"n","project_id":"p"}"""))
        assertEquals(listOf("n"), bare.map { it.id })
        val array = ledgerArrivalsFrom(
            json("""[{"notification_uuid":"a","project_id":"p"},{"notification_uuid":"b","project_id":"p"}]"""),
        )
        assertEquals(listOf("a", "b"), array.map { it.id })
        assertTrue(ledgerArrivalsFrom(json("[]")).isEmpty())
    }

    @Test
    fun `a silent frame names everything the phones drop`() {
        val silence = badgeSilenceFrom(
            json(
                """{"project_id":"p1","notification_uuid":"own","reference_data":{
                     "read_notification_ids":["r1",null,"r2"],
                     "deleted_chat_ids":["m1"],
                     "deleted_comment_ids":["c1"],
                     "chat_room_no_access":["room"],
                     "tools_no_view_access":["accounts_label","sides_label"],
                     "unit_no_view_access":["unit-a"],
                     "admin_settings_no_access":["admin_settings_label"],
                     "self_device_id":"dev"}}""",
            ),
        )
        assertNotNull(silence)
        assertEquals(setOf("r1", "r2"), silence.readIds)
        assertEquals(setOf("m1"), silence.deletedChatIds)
        assertEquals(setOf("c1"), silence.deletedCommentIds)
        assertEquals(setOf("room"), silence.lostRooms)
        assertEquals(setOf("accounts_label", "sides_label"), silence.lostTools)
        assertEquals(setOf("unit-a"), silence.lostUnits)
        assertEquals(setOf("admin_settings_label"), silence.lostAdminSettings)
        assertEquals("dev", silence.selfDeviceId)
        assertEquals("p1", silence.projectId)
    }

    /** The web reads the frame's own uuid when no read ids are given (`AllBadges.jsx:398-402`). */
    @Test
    fun `a silent frame with no ids names itself`() {
        val silence = badgeSilenceFrom(json("""{"notification_uuid":"own","reference_data":{}}"""))
        assertEquals(setOf("own"), silence?.readIds)
        assertNull(badgeSilenceFrom(json("""{"reference_data":{}}""")))
        assertNull(badgeSilenceFrom(json("[]")))
    }

    @Test
    fun `a silent frame as an array, or with the keys at the top level, still opens`() {
        val asArray = badgeSilenceFrom(json("""[{"reference_data":{"tools_no_view_access":["sides_label"]}}]"""))
        assertEquals(setOf("sides_label"), asArray?.lostTools)
        val topLevel = badgeSilenceFrom(json("""{"chat_room_no_access":["r1"]}"""))
        assertEquals(setOf("r1"), topLevel?.lostRooms)
    }
}
