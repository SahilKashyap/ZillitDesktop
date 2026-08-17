package com.zillit.desktop.feature.auth

import com.zillit.desktop.feature.auth.data.QrCodeDto
import com.zillit.desktop.core.network.HttpClientFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The poll response's two near-identical fields.
 *
 * `scanned_device_id` and `scanner_device_id` both appear in the payload, and
 * only the second means "a phone scanned this". Reading the first reports a scan
 * on the very first poll and drives `link-scanned` into a 406 — which is exactly
 * what happened against QA before this was pinned.
 *
 * Both clients agree: web checks `res?.scanner_device_id`, Android checks
 * `response.data?.scannerDeviceId`.
 */
class QrPollResponseTest {

    private fun parse(json: String) =
        HttpClientFactory.json.decodeFromString(QrCodeDto.serializer(), json)

    @Test
    fun `an unscanned code carries scanned_device_id but no scanner`() {
        // The shape QA actually returned on the first poll.
        val dto = parse(
            """{"_id":"6a70","code":"abc","scanned_device_id":"6a70616c8559","scanner_device_id":null}""",
        )

        assertNull(dto.scannerDeviceId, "no scan has happened yet")
        assertEquals("6a70616c8559", dto.scannedDeviceId, "the decoy field is populated from creation")
    }

    @Test
    fun `a scanned code carries the scanning device's id`() {
        val dto = parse("""{"_id":"6a70","code":"abc","scanner_device_id":"phone-9"}""")

        assertEquals("phone-9", dto.scannerDeviceId)
    }

    @Test
    fun `an absent scanner field decodes to null rather than failing`() {
        val dto = parse("""{"_id":"6a70","code":"abc"}""")

        assertNull(dto.scannerDeviceId)
    }
}
