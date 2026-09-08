package com.zillit.desktop.feature.auth

import com.zillit.desktop.feature.auth.data.DeviceDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The device record, in the two nestings the routes use, and the field the calling socket registers under. */
class DeviceDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a flat record names its primary device`() {
        val identity = json.decodeFromString(
            DeviceDto.serializer(),
            """{"_id":"d-desk","device_id":"d-desk","email":"me@x","primary_device_id":"d-phone"}""",
        ).toDomain()

        assertEquals("d-desk", identity?.deviceId)
        assertEquals("d-phone", identity?.primaryDeviceId)
        assertEquals("d-desk", identity?.recordId)
    }

    @Test
    fun `the record id is the underscore id, whatever device_id says`() {
        val identity = json.decodeFromString(
            DeviceDto.serializer(),
            """{"_id":"mongo","device_id":"hashed","primary_device_id":"d-phone"}""",
        ).toDomain()

        assertEquals("hashed", identity?.deviceId, "REST keeps the device_id form")
        assertEquals("mongo", identity?.recordId, "the socket names the record")
    }

    @Test
    fun `a record under data-device reads the same`() {
        val identity = json.decodeFromString(
            DeviceDto.serializer(),
            """{"device":{"_id":"d-desk","primary_device_id":"d-phone","is_primary":false}}""",
        ).toDomain()

        assertEquals("d-desk", identity?.deviceId)
        assertEquals("d-phone", identity?.primaryDeviceId)
    }

    @Test
    fun `the primary device itself names no primary`() {
        val identity = json.decodeFromString(
            DeviceDto.serializer(),
            """{"_id":"d-phone","primary_device_id":"","is_primary":true}""",
        ).toDomain()

        assertNull(identity?.primaryDeviceId)
        assertEquals(true, identity?.isPrimary)
    }
}
