package com.zillit.desktop.core.network.tokenauth

import com.zillit.desktop.core.network.RequestModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session's invariants, against a scripted backend: which endpoint is
 * called when, what is on disk before a token is used, and what a refusal
 * turns into. The phones learnt each of these the hard way — a double
 * refresh reads as theft server-side and ends the session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenSessionManagerTest {

    private class FakeApi : SessionApi {
        var establish: suspend () -> SessionCallResult<DeviceSession> =
            { SessionCallResult.Success(DeviceSession("dev-1", "ref-1", HOUR)) }
        var refresh: suspend (String) -> SessionCallResult<DeviceSession> =
            { SessionCallResult.Success(DeviceSession("dev-2", "ref-2", HOUR)) }
        var mint: suspend (String, String) -> SessionCallResult<ProjectSession> =
            { _, project -> SessionCallResult.Success(ProjectSession("proj-$project", "project", QUARTER)) }
        val calls = mutableListOf<String>()

        override suspend fun establishDeviceSession(): SessionCallResult<DeviceSession> {
            calls += "establish"
            return establish()
        }

        override suspend fun refreshDeviceSession(refreshToken: String): SessionCallResult<DeviceSession> {
            calls += "refresh:$refreshToken"
            return refresh(refreshToken)
        }

        override suspend fun mintProjectToken(
            deviceAccessToken: String,
            projectId: String,
        ): SessionCallResult<ProjectSession> {
            calls += "mint:$projectId:$deviceAccessToken"
            return mint(deviceAccessToken, projectId)
        }

        fun count(prefix: String) = calls.count { it.startsWith(prefix) }
    }

    private class FakeStore : TokenAuthStore {
        var refresh: String? = null
        var mode = false
        var saves = 0
        override suspend fun refreshToken(): String? = refresh
        override suspend fun saveRefreshToken(token: String): Boolean {
            refresh = token
            saves++
            return true
        }
        override suspend fun clearRefreshToken() {
            refresh = null
        }
        override suspend fun tokenModeCache(): Boolean = mode
        override suspend fun cacheTokenMode(enabled: Boolean) {
            mode = enabled
        }
    }

    private fun TestScope.manager(api: FakeApi, store: FakeStore, project: String? = "p1") = TokenSessionManager(
        api = api,
        store = store,
        scope = backgroundScope,
        activeProjectId = { project },
        nowMillis = { testScheduler.currentTime },
    )

    @Test
    fun `with the mode off nothing is asked for and nothing is sent`() = runTest {
        val api = FakeApi()
        val manager = manager(api, FakeStore())
        runCurrent()

        assertNull(manager.bearerFor(RequestModule.Default, null))
        assertNull(manager.bearerFor(RequestModule.ProjectUser, "p1"))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a cold start trusts the cached mode before any configuration answers`() = runTest {
        val manager = manager(FakeApi(), FakeStore().apply { mode = true })
        runCurrent()
        assertTrue(manager.tokenMode)
    }

    @Test
    fun `the first token establishes over moduledata and persists the refresh token`() = runTest {
        val api = FakeApi()
        val store = FakeStore()
        val manager = manager(api, store)
        manager.onConfigFetched(true)
        runCurrent()

        assertEquals("dev-1", manager.bearerFor(RequestModule.Default, null))
        assertEquals("ref-1", store.refresh)
        assertEquals(1, api.count("establish"), "the warm-up's session is the one every call reuses")
        assertTrue(store.mode)
    }

    @Test
    fun `a refresh token on disk is rotated instead of a fresh exchange`() = runTest {
        val api = FakeApi()
        val store = FakeStore().apply { refresh = "ref-0" }
        val manager = manager(api, store)
        manager.onConfigFetched(true)
        runCurrent()

        assertEquals("refresh:ref-0", api.calls.first())
        assertEquals(0, api.count("establish"))
        assertEquals("ref-2", store.refresh, "the rotated token is on disk")
        assertEquals("dev-2", manager.bearerFor(RequestModule.Default, null))
    }

    @Test
    fun `a dead refresh token falls back to a fresh exchange, not a sign-out`() = runTest {
        val api = FakeApi().apply {
            refresh = { SessionCallResult.Failure(401, TokenSessionManager.MSG_REFRESH_INVALID) }
        }
        val store = FakeStore().apply { refresh = "ref-0" }
        val manager = manager(api, store)
        manager.onConfigFetched(true)
        runCurrent()

        assertEquals(listOf("refresh:ref-0", "establish"), api.calls.take(2))
        assertEquals("dev-1", manager.bearerFor(RequestModule.Default, null))
        assertEquals("ref-1", store.refresh)
    }

    @Test
    fun `a transport failure keeps the stored refresh token for later`() = runTest {
        val api = FakeApi().apply { refresh = { SessionCallResult.Failure(null, "timed out") } }
        val store = FakeStore().apply { refresh = "ref-0" }
        val manager = manager(api, store)
        manager.onConfigFetched(true)
        runCurrent()

        assertNull(manager.bearerFor(RequestModule.Default, null), "no token, so the call sends moduledata")
        assertEquals("ref-0", store.refresh)
        assertEquals(0, api.count("establish"), "a timeout is not a dead token")
    }

    @Test
    fun `the server's kill switch turns the mode off until the next configuration`() = runTest {
        val api = FakeApi().apply {
            establish = { SessionCallResult.Failure(503, TokenSessionManager.MSG_TOKEN_AUTH_DISABLED) }
        }
        val manager = manager(api, FakeStore())
        manager.onConfigFetched(true)
        runCurrent()

        assertFalse(manager.tokenMode)
        assertNull(manager.bearerFor(RequestModule.Default, null))
        manager.onConfigFetched(true)
        assertTrue(manager.tokenMode, "the next configuration lifts the switch")
    }

    @Test
    fun `project tokens are minted once per production and reused`() = runTest {
        val api = FakeApi()
        val manager = manager(api, FakeStore())
        manager.onConfigFetched(true)
        runCurrent()

        assertEquals("proj-p1", manager.bearerFor(RequestModule.ProjectUser, "p1"))
        assertEquals("proj-p1", manager.bearerFor(RequestModule.Chat, "p1"))
        assertEquals(1, api.count("mint:p1"), "the warm-up minted it; nothing since")
        assertEquals("proj-p2", manager.bearerFor(RequestModule.ProjectUser, "p2"), "another production, its own token")
        assertNull(manager.bearerFor(RequestModule.ProjectUser, ""), "no production in context: legacy")
    }

    @Test
    fun `no membership on a production answers null once, without a retry`() = runTest {
        val api = FakeApi().apply {
            mint = { _, project ->
                if (project == "p9") {
                    SessionCallResult.Failure(403, TokenSessionManager.MSG_NO_MEMBERSHIP)
                } else {
                    SessionCallResult.Success(ProjectSession("proj-$project", "project", QUARTER))
                }
            }
        }
        val manager = manager(api, FakeStore())
        manager.onConfigFetched(true)
        runCurrent()

        assertNull(manager.bearerFor(RequestModule.ProjectUser, "p9"))
        assertEquals(1, api.count("mint:p9"))
    }

    @Test
    fun `a 401 rotates once, and a token already renewed is reused rather than rotated again`() = runTest {
        val api = FakeApi()
        val store = FakeStore()
        val manager = manager(api, store)
        manager.onConfigFetched(true)
        runCurrent()
        assertEquals("dev-1", manager.bearerFor(RequestModule.Default, null))

        assertEquals("dev-2", manager.recoverFromUnauthorized(RequestModule.Default, null, failedToken = "dev-1"))
        assertEquals("ref-2", store.refresh)
        // A second call that carried the same dead token finds the renewal done.
        assertEquals("dev-2", manager.recoverFromUnauthorized(RequestModule.Default, null, failedToken = "dev-1"))
        assertEquals(1, api.count("refresh"), "one rotation, however many 401s carried the old token")
    }

    @Test
    fun `a refused project token is re-minted with the device session`() = runTest {
        val api = FakeApi()
        val manager = manager(api, FakeStore())
        manager.onConfigFetched(true)
        runCurrent()
        api.mint = { _, project ->
            SessionCallResult.Success(ProjectSession("proj-$project-fresh", "project", QUARTER))
        }

        assertEquals("proj-p1-fresh", manager.recoverFromUnauthorized(RequestModule.ProjectUser, "p1", "proj-p1"))
        assertEquals(0, api.count("refresh"), "a project mint rotates nothing")
    }

    @Test
    fun `clearing the session discards a renewal that finishes after it`() = runTest {
        val gate = CompletableDeferred<SessionCallResult<DeviceSession>>()
        val api = FakeApi().apply { establish = { gate.await() } }
        val store = FakeStore()
        val manager = manager(api, store)
        manager.onConfigFetched(true)
        runCurrent()
        assertEquals(1, api.count("establish"), "the exchange is in flight")

        manager.clearSession()
        gate.complete(SessionCallResult.Success(DeviceSession("dev-late", "ref-late", HOUR)))
        runCurrent()

        assertEquals(0, store.saves, "a session that ended mid-renewal is not resurrected")
        assertNull(store.refresh)
    }

    @Test
    fun `a token the socket refused is not shown again until a fresh one exists`() = runTest {
        val api = FakeApi()
        val manager = manager(api, FakeStore())
        manager.onConfigFetched(true)
        runCurrent()
        assertEquals("dev-1", manager.deviceTokenForSocket())

        manager.socketTokenRejected("dev-1")
        assertNull(manager.deviceTokenForSocket(), "moduledata for this attempt")
        runCurrent()
        assertEquals("dev-2", manager.deviceTokenForSocket())
    }

    @Test
    fun `the device session is renewed ahead of time, at eighty percent of its life`() = runTest {
        val api = FakeApi()
        val manager = manager(api, FakeStore())
        manager.onConfigFetched(true)
        runCurrent()
        assertEquals(0, api.count("refresh"))

        advanceTimeBy(HOUR * 1000 * 8 / 10 + 1)
        runCurrent()

        assertEquals(1, api.count("refresh"))
        assertEquals("dev-2", manager.bearerFor(RequestModule.Default, null))
        assertEquals(2, api.count("mint:p1"), "the open production's token is kept warm too")
    }

    private companion object {
        const val HOUR = 3600L
        const val QUARTER = 900L
    }
}
