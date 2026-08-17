package com.zillit.desktop.core.socket

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reading a rejected handshake out of an error string.
 *
 * This decision does two irreversible-feeling things: it stops the reconnect
 * loop, and it signs the user out. Both are right when the server really did
 * refuse the credentials, and both are bad when it did not — so the false
 * positives below matter more than the true ones.
 */
class HandshakeRejectionTest {

    @Test
    fun `a rejected handshake is recognised`() {
        assertTrue(isHandshakeUnauthorized("Unexpected server response: 401"))
        assertTrue(isHandshakeUnauthorized("xhr poll error 401"))
        assertTrue(isHandshakeUnauthorized("401"))
    }

    @Test
    fun `however the server spells it`() {
        assertTrue(isHandshakeUnauthorized("Unauthorized"))
        assertTrue(isHandshakeUnauthorized("unauthorised device"))
        assertTrue(isHandshakeUnauthorized("UNAUTHORIZED"))
    }

    @Test
    fun `an ordinary network failure is not a rejection`() {
        // These must reconnect. Signing someone out for being on bad hotel
        // wifi is the app's worst behaviour at the worst moment.
        assertFalse(isHandshakeUnauthorized("xhr poll error"))
        assertFalse(isHandshakeUnauthorized("websocket error"))
        assertFalse(isHandshakeUnauthorized("timeout"))
        assertFalse(isHandshakeUnauthorized(""))
    }

    @Test
    fun `digits that merely contain 401 are not a rejection`() {
        // The reason this is a regex and not `contains("401")`.
        assertFalse(isHandshakeUnauthorized("transferred 4013 bytes"))
        assertFalse(isHandshakeUnauthorized("connect ECONNREFUSED 127.0.0.1:14012"))
        assertFalse(isHandshakeUnauthorized("session 8b3401f2"))
        assertFalse(isHandshakeUnauthorized("server responded 500 after 2401ms"))
    }

    @Test
    fun `a 401 next to punctuation still counts`() {
        // Real messages wrap the status in prose and brackets.
        assertTrue(isHandshakeUnauthorized("server returned (401)"))
        assertTrue(isHandshakeUnauthorized("status=401, retrying=false"))
    }
}
