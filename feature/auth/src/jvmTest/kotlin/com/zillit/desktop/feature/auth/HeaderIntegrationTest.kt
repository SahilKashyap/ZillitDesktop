package com.zillit.desktop.feature.auth

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.DeviceDescription
import com.zillit.desktop.core.network.HeaderContext
import com.zillit.desktop.core.network.HeaderCrypto
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.ZillitHeaderProvider
import com.zillit.desktop.core.network.ZillitHeaders
import com.zillit.desktop.core.network.headersFor
import com.zillit.desktop.core.security.AesCbcCryptoEngine
import com.zillit.desktop.core.security.CryptoKeyMaterial
import com.zillit.desktop.core.security.CryptoKeyProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole header chain, end to end: payload → JSON → AES/CBC → hex.
 *
 * The unit tests either fake the crypto or fake the payload. This wires the
 * **real** [ZillitHeaderProvider] to the **real** [AesCbcCryptoEngine] and
 * decrypts the result, which is the closest thing to server verification
 * available without a server.
 *
 * If this passes and a live call still 401s, the fault is in the key, the
 * endpoint, or the server's expectations — not in the assembly.
 */
class HeaderIntegrationTest {

    // Test keys at the canonical lengths (32 / 16). Not production values.
    private val key = "0123456789abcdef0123456789abcdef"
    private val iv = "abcdef9876543210"

    private val engine = AesCbcCryptoEngine(
        object : CryptoKeyProvider {
            override fun keyMaterial() = ZillitResult.Success(CryptoKeyMaterial.fromStrings(key, iv))
        },
    )

    private val provider = ZillitHeaderProvider(
        crypto = object : HeaderCrypto {
            override fun encryptToHex(plaintext: String) = engine.encryptToHex(plaintext)
            override fun bodyHash(bodyJson: String, encryptedModuleData: String) =
                engine.bodyHash(bodyJson, encryptedModuleData)
        },
        context = {
            HeaderContext(deviceId = "device-abc", projectId = "proj-1", userId = "user-9")
        },
        deviceDescription = DeviceDescription("unknown", "macOS 15.5", "Mac", "desktop"),
        nowMillis = { 1_700_000_000_000L },
        timeZoneId = { "Europe/London" },
    )

    @Test
    fun `the moduledata header decrypts back to the expected payload`() = runTest {
        val headers = provider.headersFor(RequestModule.Chat, bodyJson = null, projectId = null)
        val encrypted = headers.getValue(ZillitHeaders.MODULE_DATA)

        val decrypted = engine.decryptFromHex(encrypted)
        assertTrue(decrypted is ZillitResult.Success, "header did not decrypt: $decrypted")

        val payload = Json.parseToJsonElement(decrypted.data) as JsonObject
        assertEquals("device-abc", payload["device_id"]?.jsonPrimitive?.content)
        assertEquals("proj-1", payload["project_id"]?.jsonPrimitive?.content)
        assertEquals("user-9", payload["user_id"]?.jsonPrimitive?.content)
        assertEquals("1700000000000", payload["time_stamp"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the header is lowercase hex of whole AES blocks`() = runTest {
        // What the backend expects on the wire, and what the Android client
        // produces. A base64 or uppercase header would be rejected.
        val headers = provider.headersFor(RequestModule.Default, bodyJson = null, projectId = null)
        val encrypted = headers.getValue(ZillitHeaders.MODULE_DATA)

        assertTrue(encrypted.matches(Regex("[0-9a-f]+")), "not lowercase hex: $encrypted")
        assertEquals(0, encrypted.length % 32, "not a whole number of 16-byte blocks")
    }

    @Test
    fun `no header carries the payload in clear`() = runTest {
        // A regression guard: if someone ever "simplifies" the provider to skip
        // encryption, the device id would go out in plaintext.
        val headers = provider.headersFor(RequestModule.Chat, bodyJson = null, projectId = null)

        assertTrue(
            headers.getValue(ZillitHeaders.MODULE_DATA).none { it == '{' },
            "moduledata looks like raw JSON",
        )
        assertTrue(
            !headers.getValue(ZillitHeaders.MODULE_DATA).contains("device-abc"),
            "the device id is readable in the header",
        )
    }

    @Test
    fun `deviceInfo is intentionally NOT encrypted`() = runTest {
        // Matching Android: only `moduledata` is encrypted. Encrypting this one
        // too would be a silent protocol change.
        val headers = provider.headersFor(RequestModule.Default, bodyJson = null, projectId = null)
        val info = headers.getValue(ZillitHeaders.DEVICE_INFO)

        assertTrue(info.startsWith("{"), "deviceInfo should be plain JSON, got: $info")
        assertTrue(info.contains("desktop"))
    }
}
