package com.zillit.desktop.core.network

import com.zillit.desktop.core.network.tokenauth.isDeviceRoute
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pre-production gate, locked to the evidence it was built from.
 *
 * The allowed half is every request the app was observed making with no
 * production open (`~/.zillit/logs`, several launches, 2026-10-19) that the
 * server answered. If one of these ever stops matching, that screen stops
 * working on the production list — which is the failure mode of a fail-closed
 * list and the reason this test names them one by one rather than sampling.
 *
 * The refused half is the three routes the server was observed refusing there,
 * `401 libs_invalid_token_scope`, 21 times across two days.
 */
class DeviceRoutesTest {

    private val host = "https://projectapi-dev.zillit.com/api/v2"

    @Test
    fun `every route the app calls with no production open is allowed`() {
        val allowed = listOf(
            HttpVerb.Get to "$host/project",
            HttpVerb.Get to "$host/project/67f4c25ad7b27a11acf7d6c0",
            HttpVerb.Get to "$host/project/all/notifications/1792396859607/next",
            HttpVerb.Get to "$host/project/all/notifications/1770724612805/previous",
            HttpVerb.Get to "$host/device",
            HttpVerb.Put to "$host/device",
            HttpVerb.Get to "$host/device/linked",
            HttpVerb.Get to "$host/preset/identifiers",
            HttpVerb.Get to "$host/preset/labels",
            HttpVerb.Get to "$host/preset/messages",
            HttpVerb.Get to "$host/user/profile",
            HttpVerb.Post to "$host/location/log",
        )
        allowed.forEach { (verb, url) ->
            assertTrue(isDeviceRoute(verb, url), "should be a device route: $verb $url")
        }
    }

    @Test
    fun `the three routes the server refuses there are not device routes`() {
        val refused = listOf(
            HttpVerb.Get to "https://cncapi-dev.zillit.com/api/v2/chat-room",
            HttpVerb.Get to "https://cncapi-dev.zillit.com/api/v2/chat/users-group",
            HttpVerb.Get to "https://callapi-dev.zillit.com/api/v2/mediasoup-call/active-group-calls",
        )
        refused.forEach { (verb, url) ->
            assertFalse(isDeviceRoute(verb, url), "should NOT be a device route: $verb $url")
        }
    }

    /** A query string rides along on several of these, as it does on the web. */
    @Test
    fun `a query string does not stop a match`() {
        assertTrue(isDeviceRoute(HttpVerb.Get, "$host/project?limit=50"))
        assertTrue(isDeviceRoute(HttpVerb.Get, "$host/preset/labels?lang=en"))
    }

    /**
     * The anchoring, which is the whole reason the matchers are regexes: the
     * production list is `/v2/project` itself and nothing under it, or every
     * tool route in the app would ride a device token.
     */
    @Test
    fun `project does not admit its children`() {
        assertFalse(isDeviceRoute(HttpVerb.Get, "$host/project/tools"))
        assertFalse(isDeviceRoute(HttpVerb.Get, "$host/project/tools/groups"))
        assertFalse(isDeviceRoute(HttpVerb.Get, "$host/project/users"))
        assertFalse(isDeviceRoute(HttpVerb.Get, "$host/project/pendingtools"))
    }

    /** The verb is load-bearing: a write is not the read that was allow-listed. */
    @Test
    fun `the verb is part of the match`() {
        assertFalse(isDeviceRoute(HttpVerb.Post, "$host/project"))
        assertFalse(isDeviceRoute(HttpVerb.Delete, "$host/device"))
        assertFalse(isDeviceRoute(HttpVerb.Get, "$host/location/log"))
    }

    /** A neighbour must not be read as the family. */
    @Test
    fun `a sibling segment is not the route`() {
        assertFalse(isDeviceRoute(HttpVerb.Get, "$host/device-otp-settings"))
        assertFalse(isDeviceRoute(HttpVerb.Get, "$host/user/profile-archive"))
    }
}
