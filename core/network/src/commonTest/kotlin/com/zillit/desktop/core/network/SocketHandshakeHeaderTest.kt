package com.zillit.desktop.core.network

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Socket.IO handshake payload.
 *
 * `ReqHeaderForSocket` on Android is one field. The REST device header carries a
 * timestamp as well, and reusing it here would send a key the handshake does not
 * expect — which fails as a refused connection, not a parse error.
 */
class SocketHandshakeHeaderTest {

    private val provider = ZillitHeaderProvider(
        crypto = object : HeaderCrypto {
            override fun encryptToHex(plaintext: String) = ZillitResult.Success("ENC:$plaintext")
            override fun bodyHash(bodyJson: String, encryptedModuleData: String) =
                ZillitResult.Success("HASH")
        },
        context = { HeaderContext(deviceId = "device-abc", projectId = "p1", userId = "u1") },
        deviceDescription = DeviceDescription("wifi", "os", "name", "desktop"),
        nowMillis = { 1_700_000_000_000L },
        timeZoneId = { "UTC" },
    )

    @Test
    fun `the handshake carries the device id and nothing else`() = runTest {
        val header = provider.headersFor(RequestModule.SocketHandshake, bodyJson = null, projectId = null)
            .getValue(ZillitHeaders.MODULE_DATA)
        val payload = Json.parseToJsonElement(header.removePrefix("ENC:")) as JsonObject

        assertEquals(setOf("device_id"), payload.keys, "the handshake payload gained a field")
        assertEquals("device-abc", payload["device_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the REST device header still carries its timestamp`() = runTest {
        // Guards against "simplifying" the two into one shape.
        val header = provider.headersFor(RequestModule.Device, bodyJson = null, projectId = null)
            .getValue(ZillitHeaders.MODULE_DATA)
        val payload = Json.parseToJsonElement(header.removePrefix("ENC:")) as JsonObject

        assertEquals(setOf("device_id", "time_stamp"), payload.keys)
    }
}
