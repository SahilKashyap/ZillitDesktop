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
    }

    /** Any device-scoped route; only the project-scoped list in TokenScope cares which. */
    private val path = "https://projectapi-dev.zillit.com/api/v2/user/profile"

    /**
     * Built and probed, which is what the host does: `AppGraph` calls
     * [TokenSessionManager.probeDeviceSession] as the graph comes up. There
     * is no configuration answer to wait for any more, so the probe is the
     * only thing that warms the session ahead of the first call.
     */
    private fun TestScope.manager(api: FakeApi, store: FakeStore, project: String? = "p1") = TokenSessionManager(
        api = api,
        store = store,
        scope = backgroundScope,
        activeProjectId = { project },
        nowMillis = { testScheduler.currentTime },
    ).also { it.probeDeviceSession() }

    /**
     * Token mode is ON without being told — the whole point of dropping
     * `token_auth_enabled`. A client that waited for that flag waited behind
     * `GET /configuration`, which is project-scoped and answers 401 under
     * `moduledata`, so it never arrived and every call 401'd.
     */
    @Test
    fun `token mode is on by default, with no configuration answer at all`() = runTest {
        val api = FakeApi()
        val manager = manager(api, FakeStore())
        runCurrent()

        assertTrue(manager.tokenMode)
        assertEquals("dev-1", manager.bearerFor(RequestModule.Default, null, path))
    }

    /**
     * `libs_invalid_device_id` is the one verdict that turns the mode off: no
     * device record exists to mint for, and only create/join project can make
     * one. Calls go back to `moduledata` until [onDeviceRegistered].
     */
    @Test
    fun `an unregistered device leaves token mode until it is registered`() = runTest {
        val api = FakeApi().apply {
            establish = { SessionCallResult.Failure(401, "libs_invalid_device_id") }
        }
        val manager = manager(api, FakeStore())
        manager.probeDeviceSession()
        runCurrent()

        assertFalse(manager.tokenMode)
        assertNull(manager.bearerFor(RequestModule.Default, null, path))

        api.establish = { SessionCallResult.Success(DeviceSession("dev-1", "ref-1", HOUR)) }
        manager.onDeviceRegistered()
        runCurrent()
        assertTrue(manager.tokenMode)
    }

    /**
     * A project-scoped module with no production open rides the DEVICE token
     * rather than dropping to `moduledata`, which develop refuses — except on
     * the handful of routes the server has been seen refusing a device token
     * on, which take the legacy credential instead.
     */
    @Test
    fun `no open production falls back to the device token, but not on a project-only route`() = runTest {
        val manager = manager(FakeApi(), FakeStore(), project = null)
        runCurrent()

        assertEquals("dev-1", manager.bearerFor(RequestModule.ProjectUser, null, path))
        assertNull(
            manager.bearerFor(RequestModule.Project, null, "https://projectapi-dev.zillit.com/api/v2/project/users"),
        )
    }

    @Test
    fun `the first token establishes over moduledata and persists the refresh token`() = runTest {
        val api = FakeApi()
        val store = FakeStore()
        val manager = manager(api, store)
        runCurrent()

        assertEquals("dev-1", manager.bearerFor(RequestModule.Default, null, path))
        assertEquals("ref-1", store.refresh)
        assertEquals(1, api.count("establish"), "the warm-up's session is the one every call reuses")
    }

    @Test
    fun `a refresh token on disk is rotated instead of a fresh exchange`() = runTest {
        val api = FakeApi()
        val store = FakeStore().apply { refresh = "ref-0" }
        val manager = manager(api, store)
        runCurrent()

        assertEquals("refresh:ref-0", api.calls.first())
        assertEquals(0, api.count("establish"))
        assertEquals("ref-2", store.refresh, "the rotated token is on disk")
        assertEquals("dev-2", manager.bearerFor(RequestModule.Default, null, path))
    }

    @Test
    fun `a dead refresh token falls back to a fresh exchange, not a sign-out`() = runTest {
        val api = FakeApi().apply {
            refresh = { SessionCallResult.Failure(401, TokenSessionManager.MSG_REFRESH_INVALID) }
        }
        val store = FakeStore().apply { refresh = "ref-0" }
        val manager = manager(api, store)
        runCurrent()

        assertEquals(listOf("refresh:ref-0", "establish"), api.calls.take(2))
        assertEquals("dev-1", manager.bearerFor(RequestModule.Default, null, path))
        assertEquals("ref-1", store.refresh)
    }

    @Test
    fun `a transport failure keeps the stored refresh token for later`() = runTest {
        val api = FakeApi().apply { refresh = { SessionCallResult.Failure(null, "timed out") } }
        val store = FakeStore().apply { refresh = "ref-0" }
        val manager = manager(api, store)
        runCurrent()

        assertNull(manager.bearerFor(RequestModule.Default, null, path), "no token, so the call sends moduledata")
        assertEquals("ref-0", store.refresh)
        assertEquals(0, api.count("establish"), "a timeout is not a dead token")
    }

    /**
     * The kill switch needs the explicit verdict — a bare 503 is a load
     * balancer hiccup, not the feature being turned off — and with the
     * configuration flag gone it is final for the life of the process. There
     * is no longer a "next configuration" to lift it, which is the honest
     * shape: the server said stop, so the client stops until it restarts.
     */
    @Test
    fun `the server's kill switch turns the mode off for good`() = runTest {
        val api = FakeApi().apply {
            establish = { SessionCallResult.Failure(503, TokenSessionManager.MSG_TOKEN_AUTH_DISABLED) }
        }
        val manager = manager(api, FakeStore())
        runCurrent()

        assertFalse(manager.tokenMode)
        assertNull(manager.bearerFor(RequestModule.Default, null, path))
        // A probe after the switch does not re-arm it, and asks for nothing.
        val before = api.count("establish")
        manager.probeDeviceSession()
        runCurrent()
        assertFalse(manager.tokenMode)
        assertEquals(before, api.count("establish"), "nothing is asked for once the server has said stop")
    }

    @Test
    fun `project tokens are minted once per production and reused`() = runTest {
        val api = FakeApi()
        val manager = manager(api, FakeStore())
        runCurrent()

        assertEquals("proj-p1", manager.bearerFor(RequestModule.ProjectUser, "p1", path))
        assertEquals("proj-p1", manager.bearerFor(RequestModule.Chat, "p1", path))
        assertEquals(1, api.count("mint:p1"), "the warm-up minted it; nothing since")
        assertEquals(
            "proj-p2",
            manager.bearerFor(RequestModule.ProjectUser, "p2", path),
            "another project, its own token",
        )
        assertEquals(
            "dev-1",
            manager.bearerFor(RequestModule.ProjectUser, "", path),
            "no production in context: the device token, not moduledata",
        )
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
        runCurrent()

        assertNull(manager.bearerFor(RequestModule.ProjectUser, "p9", path))
        assertEquals(1, api.count("mint:p9"))
    }

    /**
     * A 502 on develop (2026-09-29) became 172 mint attempts a minute,
     * forever: a failed mint caches nothing, so every call that wanted that
     * production's token re-attempted it. The calls kept working on
     * `moduledata`, so only the log showed it.
     */
    @Test
    fun `a server error stops the mint being retried once per call`() = runTest {
        val api = FakeApi().apply { mint = { _, _ -> SessionCallResult.Failure(502, null) } }
        val manager = manager(api, FakeStore())
        runCurrent()

        repeat(20) { assertNull(manager.bearerFor(RequestModule.ProjectUser, "p1", path)) }

        // The warm-up's own mint, and nothing from the twenty calls behind it.
        assertEquals(1, api.count("mint:p1"))
    }

    @Test
    fun `the mint is tried again once the quiet period is over`() = runTest {
        val api = FakeApi().apply { mint = { _, _ -> SessionCallResult.Failure(502, null) } }
        val manager = manager(api, FakeStore())
        runCurrent()
        manager.bearerFor(RequestModule.ProjectUser, "p1", path)
        val duringOutage = api.count("mint:p1")

        advanceTimeBy(BACKOFF + 1)
        // Back up: the next call mints and the production works again.
        api.mint = { _, project -> SessionCallResult.Success(ProjectSession("proj-$project", "project", QUARTER)) }

        assertEquals("proj-p1", manager.bearerFor(RequestModule.ProjectUser, "p1", path))
        assertEquals(duringOutage + 1, api.count("mint:p1"))
    }

    /** One production being unreachable says nothing about another. */
    @Test
    fun `a production in its quiet period does not silence the others`() = runTest {
        val api = FakeApi().apply {
            mint = { _, project ->
                if (project == "p1") {
                    SessionCallResult.Failure(502, null)
                } else {
                    SessionCallResult.Success(ProjectSession("proj-$project", "project", QUARTER))
                }
            }
        }
        val manager = manager(api, FakeStore())
        runCurrent()
        manager.bearerFor(RequestModule.ProjectUser, "p1", path)

        assertEquals("proj-p2", manager.bearerFor(RequestModule.ProjectUser, "p2", path))
    }

    /** Coming back to the window is the clearest sign the network may differ now. */
    @Test
    fun `returning to the front retries a production that was failing`() = runTest {
        val api = FakeApi().apply { mint = { _, _ -> SessionCallResult.Failure(502, null) } }
        val manager = manager(api, FakeStore())
        runCurrent()
        manager.bearerFor(RequestModule.ProjectUser, "p1", path)
        val duringOutage = api.count("mint:p1")

        api.mint = { _, project -> SessionCallResult.Success(ProjectSession("proj-$project", "project", QUARTER)) }
        manager.onAppForegrounded()
        runCurrent()

        assertTrue(api.count("mint:p1") > duringOutage, "the wake seam must clear the quiet period")
    }

    @Test
    fun `a 401 rotates once, and a token already renewed is reused rather than rotated again`() = runTest {
        val api = FakeApi()
        val store = FakeStore()
        val manager = manager(api, store)
        runCurrent()
        assertEquals("dev-1", manager.bearerFor(RequestModule.Default, null, path))

        assertEquals("dev-2", manager.recoverFromUnauthorized(RequestModule.Default, null, path, failedToken = "dev-1"))
        assertEquals("ref-2", store.refresh)
        // A second call that carried the same dead token finds the renewal done.
        assertEquals("dev-2", manager.recoverFromUnauthorized(RequestModule.Default, null, path, failedToken = "dev-1"))
        assertEquals(1, api.count("refresh"), "one rotation, however many 401s carried the old token")
    }

    @Test
    fun `a refused project token is re-minted with the device session`() = runTest {
        val api = FakeApi()
        val manager = manager(api, FakeStore())
        runCurrent()
        api.mint = { _, project ->
            SessionCallResult.Success(ProjectSession("proj-$project-fresh", "project", QUARTER))
        }

        assertEquals("proj-p1-fresh", manager.recoverFromUnauthorized(RequestModule.ProjectUser, "p1", path, "proj-p1"))
        assertEquals(0, api.count("refresh"), "a project mint rotates nothing")
    }

    @Test
    fun `clearing the session discards a renewal that finishes after it`() = runTest {
        val gate = CompletableDeferred<SessionCallResult<DeviceSession>>()
        val api = FakeApi().apply { establish = { gate.await() } }
        val store = FakeStore()
        val manager = manager(api, store)
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
        runCurrent()
        assertEquals("dev-1", manager.deviceTokenForSocket())

        manager.socketTokenRejected("dev-1")
        assertNull(manager.deviceTokenForSocket(), "moduledata for this attempt")
        runCurrent()
        assertEquals("dev-2", manager.deviceTokenForSocket())
    }

    /**
     * Renewal happens at the point of use and at the wake seam — never on a
     * timer.
     *
     * The backend asked for the timer to go (24 Sep 2026): every rotation
     * nobody needed is another chance to be left holding a token the server
     * has already retired. Correctness is the 80% check below; the wake seam
     * only moves that cost off the first call after a long idle.
     */
    @Test
    fun `a token past eighty percent of its life is renewed when it is used, not on a timer`() = runTest {
        val api = FakeApi()
        val manager = manager(api, FakeStore())
        runCurrent()
        assertEquals(0, api.count("refresh"))

        advanceTimeBy(HOUR * 1000 * 8 / 10 + 1)
        runCurrent()
        assertEquals(0, api.count("refresh"), "no timer: time passing alone rotates nothing")

        // Using it is what renews it.
        assertEquals("dev-2", manager.bearerFor(RequestModule.Default, null, path))
        assertEquals(1, api.count("refresh"))
    }

    /** The wake seam renews ahead of the first call, so that call does not pay for it. */
    @Test
    fun `coming back to the front renews before anything is asked for`() = runTest {
        val api = FakeApi()
        val manager = manager(api, FakeStore())
        runCurrent()

        advanceTimeBy(HOUR * 1000 * 8 / 10 + 1)
        manager.onAppForegrounded()
        runCurrent()

        assertEquals(1, api.count("refresh"), "renewed at the seam, before the first request")
    }

    private companion object {
        const val HOUR = 3600L
        const val QUARTER = 900L

        /** The manager's own quiet period after a failed mint, in millis. */
        const val BACKOFF = 30_000L
    }
}
