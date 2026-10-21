package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.network.ApiFailure
import com.zillit.desktop.core.network.applog.LogCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the error log (`POST location/log`) is told about the failures that do
 * NOT pass through `ApiClient` — the raw exports, the token session's own
 * verdict, and the socket.
 *
 * The shape is the point: these have to arrive in the same envelope as an
 * ordinary failed call, or they are unqueryable beside it and beside the
 * phones' events.
 */
class AppLogEventsTest {

    @Test
    fun `a raw export failure is recorded as the api failure it is`() {
        val event = ApiFailure(
            method = "GET",
            url = "https://costapi.zillit.com/api/v2/cost-reports/42/export/pdf?token=secret",
            error = ZillitError.Unauthorized("no rights"),
        ).toLogEvent()

        assertEquals(LogCategory.Api, event.category)
        // Grouped by endpoint, as iOS names it: method and path, no host, no `/api`.
        assertEquals("GET /v2/cost-reports/42/export/pdf", event.name)
        assertEquals(401, event.api?.status)
        // GDPR: ids and tokens ride in the query string, so it never reaches the store.
        assertEquals("https://costapi.zillit.com/api/v2/cost-reports/42/export/pdf", event.api?.url)
        assertFalse(event.api?.url?.contains("secret") == true, "the query string must be stripped")
    }

    @Test
    fun `a long server message is capped before it is sent`() {
        val event = apiLogEvent("POST", "https://costapi.zillit.com/api/v2/export", 500, "x".repeat(900))

        assertEquals(300, event.error?.message?.length, "the web caps this at 300; so does this")
    }

    @Test
    fun `refresh reuse is recorded as the verdict, and nothing else`() {
        val event = refreshReuseLogEvent("https://api.zillit.com/api/v2/session/device/refresh", status = 401)

        assertEquals(LogCategory.Api, event.category)
        assertEquals("POST /v2/session/device/refresh", event.name)
        assertEquals("session_refresh_reuse_detected", event.error?.message)
        assertEquals("401", event.error?.code)
        // Never the token that was reused.
        assertTrue(event.context.isEmpty(), "nothing else belongs in this event")
    }

    @Test
    fun `a repeating socket failure is capped, and a later hour reports again`() {
        var now = 0L
        val cap = RepeatedFailureCap(nowMillis = { now })

        repeat(3) { assertTrue(cap.allows("connect|refused"), "the first three are the informative ones") }
        assertFalse(cap.allows("connect|refused"), "a retry loop must not fill a store with no retention")
        // A DIFFERENT fault still gets through: one loop cannot crowd out everything else.
        assertTrue(cap.allows("connect|offline"))

        now += 60L * 60 * 1000
        assertTrue(cap.allows("connect|refused"), "an outage still going an hour later is worth a line")
    }
}
