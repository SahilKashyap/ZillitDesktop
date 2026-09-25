package com.zillit.desktop.core.network

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The header contract, transcribed from `AppHelper.getModelData`.
 *
 * These are exacting on purpose: the payload is encrypted and checked
 * server-side, so a renamed field or a stray `"project_id":null` is not a
 * warning — it is every request failing with a 401 that says nothing useful.
 *
 * The fake crypto here is identity-with-a-prefix, so the tests can read the
 * plaintext they would otherwise never see.
 */
class ZillitHeaderProviderTest {

    private var deviceId = "device-abc"
    private var projectId: String? = "project-1"
    private var userId: String? = "user-9"
    private var scannerDeviceId: String? = null
    private var clock = 1_700_000_000_000L

    /** Identity-with-a-prefix, so the tests can read plaintext they would not otherwise see. */
    private val fakeCrypto = object : HeaderCrypto {
        override fun encryptToHex(plaintext: String) = ZillitResult.Success("ENC:$plaintext")
        override fun bodyHash(bodyJson: String, encryptedModuleData: String) =
            ZillitResult.Success("HASH($bodyJson|$encryptedModuleData)")
    }

    private val provider = ZillitHeaderProvider(
        crypto = fakeCrypto,
        context = {
            HeaderContext(
                deviceId = deviceId,
                projectId = projectId,
                userId = userId,
                scannerDeviceId = scannerDeviceId,
            )
        },
        deviceDescription = DeviceDescription(
            network = "wifi",
            osVersion = "macOS 15.5",
            deviceName = "MacBook Pro",
            deviceType = "desktop",
        ),
        nowMillis = { clock },
        timeZoneId = { "Europe/London" },
    )

    @Test
    fun `every header the server expects is present`() = runTest {
        val headers = provider.headersFor(RequestModule.Default, bodyJson = null, projectId = null)

        // The web interceptor sends moduledata, bodyhash and timezone on every
        // call; a request missing bodyhash is rejected outright.
        assertEquals(
            setOf("moduledata", "deviceInfo", "bodyhash", "timezone"),
            headers.keys,
        )
        assertEquals("Europe/London", headers["timezone"])
    }

    @Test
    fun `bodyhash covers the body and the encrypted moduledata`() = runTest {
        // The digest binds the two together: swapping either one must change it,
        // which is the whole point of the header.
        val body = """{"code":"abc"}"""
        val headers = provider.headersFor(RequestModule.Device, bodyJson = body, projectId = null)

        assertEquals(
            "HASH($body|${headers.getValue("moduledata")})",
            headers["bodyhash"],
        )
    }

    @Test
    fun `a bodiless request still carries a bodyhash`() = runTest {
        // GET and DELETE hash an empty payload rather than skipping the header
        // — `generateBodyHash` is called unconditionally by the web interceptor.
        val headers = provider.headersFor(RequestModule.Device, bodyJson = null, projectId = null)

        assertTrue(headers.getValue("bodyhash").startsWith("HASH(|"))
    }

    @Test
    fun `a pre-auth call carries only device id and timestamp`() = runTest {
        // MODELDATA.DEFAULT. Sending a project id here would be wrong — there
        // is no project yet.
        val payload = moduleData(RequestModule.Device)

        assertEquals(setOf("device_id", "time_stamp"), payload.keys)
        assertEquals("device-abc", payload["device_id"]?.jsonPrimitive?.content)
        assertEquals("1700000000000", payload["time_stamp"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a project call adds the project id`() = runTest {
        val payload = moduleData(RequestModule.Project)

        assertEquals(setOf("device_id", "project_id", "time_stamp"), payload.keys)
        assertEquals("project-1", payload["project_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a chat call adds project and user`() = runTest {
        // MODELDATA.WITH_PROJECT_USER_ID.
        val payload = moduleData(RequestModule.Chat)

        assertEquals(setOf("device_id", "project_id", "user_id", "time_stamp"), payload.keys)
        assertEquals("user-9", payload["user_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `null fields are omitted, not serialised as null`() = runTest {
        // Gson omits nulls on the Android side. An extra `"user_id":null` is a
        // different string, so it encrypts differently and the server rejects it.
        val payload = moduleData(RequestModule.Default)

        assertFalse(payload.containsKey("user_id"), "a null field was serialised")
        assertFalse(payload.containsKey("project_id"))
        assertFalse(payload.containsKey("scanner_device_id"))
    }

    @Test
    fun `an absent project serialises as empty string, matching Android`() = runTest {
        // `projectDataModel?.projectId ?: ""` — the Android side sends "", not
        // an omitted field, once the module calls for a project.
        projectId = null
        userId = null

        val payload = moduleData(RequestModule.Chat)

        assertEquals("", payload["project_id"]?.jsonPrimitive?.content)
        assertEquals("", payload["user_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `QR linking carries the scanning device id`() = runTest {
        // MODELDATA.SCANNER_DEVICE_ID.
        scannerDeviceId = "scanner-77"

        val payload = moduleData(RequestModule.ScannerDevice)

        assertEquals("scanner-77", payload["scanner_device_id"]?.jsonPrimitive?.content)
        assertEquals("device-abc", payload["device_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `map routes go out unauthenticated`() = runTest {
        val payload = moduleData(RequestModule.MapRoute)

        assertEquals("", payload["device_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `each request gets a fresh timestamp`() = runTest {
        // Two identical calls must not produce identical ciphertext, or the
        // header becomes replayable by inspection.
        val first = provider.headersFor(RequestModule.Default, bodyJson = null, projectId = null)["moduledata"]
        clock += 1_000
        val second = provider.headersFor(RequestModule.Default, bodyJson = null, projectId = null)["moduledata"]

        assertNotEquals(first, second)
    }

    @Test
    fun `device info is plain JSON with the Android field names`() = runTest {
        val info = Json.parseToJsonElement(
            provider.headersFor(RequestModule.Default, bodyJson = null, projectId = null).getValue("deviceInfo"),
        ) as JsonObject

        assertEquals(setOf("network", "osversion", "deviceName", "deviceType"), info.keys)
        assertEquals("desktop", info["deviceType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the desktop names itself as the user agent on every call, token or not`() = runTest {
        val described = ZillitHeaderProvider(
            crypto = fakeCrypto,
            context = { HeaderContext(deviceId = deviceId) },
            deviceDescription = DeviceDescription(
                network = "unknown",
                osVersion = "macOS 26.5.1",
                deviceName = "Sahil's MacBook Pro",
                deviceType = "desktop",
                userAgent = "Zillit-Desktop/1.0.6 (macOS 26.5.1; aarch64; Mac15,3)",
            ),
            nowMillis = { clock },
            timeZoneId = { "Europe/London" },
        )
        val agent = "Zillit-Desktop/1.0.6 (macOS 26.5.1; aarch64; Mac15,3)"
        assertEquals(agent, described.headersFor(RequestModule.Default, null, null)[ZillitHeaders.USER_AGENT])
        assertEquals(agent, described.plainHeaders()[ZillitHeaders.USER_AGENT])

        // The deviceInfo header keeps the phones' four fields: the agent is its own header.
        val info = Json.parseToJsonElement(
            described.headersFor(RequestModule.Default, null, null).getValue("deviceInfo"),
        ) as JsonObject
        assertEquals(setOf("network", "osversion", "deviceName", "deviceType"), info.keys)
        assertEquals("macOS 26.5.1", info["osversion"]?.jsonPrimitive?.content)

        // Unset, the header is left out rather than sent blank.
        assertNull(provider.headersFor(RequestModule.Default, null, null)[ZillitHeaders.USER_AGENT])
    }

    @Test
    fun `a device name the header cannot carry raw is escaped, and reads back the same`() = runTest {
        // The owner's own name for the machine, curly apostrophe and all. The
        // HTTP client throws on any header character past `~`, so sent raw
        // this would fail every single call.
        val named = ZillitHeaderProvider(
            crypto = fakeCrypto,
            context = { HeaderContext(deviceId = deviceId) },
            deviceDescription = DeviceDescription(
                network = "unknown",
                osVersion = "macOS 26.5.1",
                deviceName = "Sahil\u2019s MacBook Pro — José",
                deviceType = "desktop",
                userAgent = "Zillit-Desktop/1.0.6 (macOS 26.5.1; café)",
            ),
            nowMillis = { clock },
            timeZoneId = { "Europe/London" },
        )
        val headers = named.headersFor(RequestModule.Default, null, null) + named.plainHeaders()
        headers.values.forEach { value -> assertTrue(value.all { it in ' '..'~' }, value) }

        val info = Json.parseToJsonElement(headers.getValue("deviceInfo")) as JsonObject
        assertEquals("Sahil\u2019s MacBook Pro — José", info["deviceName"]?.jsonPrimitive?.content)
        assertEquals("Zillit-Desktop/1.0.6 (macOS 26.5.1; caf?)", headers[ZillitHeaders.USER_AGENT])
    }

    @Test
    fun `missing keys produce no headers rather than plaintext ones`() = runTest {
        // Sending an unencrypted moduledata would put the device id on the wire
        // in clear and be rejected anyway. Better an honest 401.
        val broken = ZillitHeaderProvider(
            crypto = object : HeaderCrypto {
                override fun encryptToHex(plaintext: String) =
                    ZillitResult.Failure(ZillitError.Crypto("no key"))

                override fun bodyHash(bodyJson: String, encryptedModuleData: String) =
                    ZillitResult.Failure(ZillitError.Crypto("no key"))
            },
            context = { HeaderContext(deviceId = "d") },
            deviceDescription = DeviceDescription("wifi", "os", "name", "desktop"),
            nowMillis = { 0 },
            timeZoneId = { "UTC" },
        )

        val headers = broken.headersFor(RequestModule.Default, bodyJson = null, projectId = null)

        assertTrue(headers.isEmpty(), "headers were built without working crypto: $headers")
        assertNull(headers["moduledata"])
    }

    private suspend fun moduleData(module: RequestModule): JsonObject {
        val header = provider.headersFor(module, bodyJson = null, projectId = null).getValue("moduledata")
        return Json.parseToJsonElement(header.removePrefix("ENC:")) as JsonObject
    }
}
