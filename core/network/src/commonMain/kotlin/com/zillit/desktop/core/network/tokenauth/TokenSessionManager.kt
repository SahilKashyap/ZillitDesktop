package com.zillit.desktop.core.network.tokenauth

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.network.RequestAuthenticator
import com.zillit.desktop.core.network.RequestModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * The device session and its per-production tokens — the phones'
 * `TokenSessionManager`, for the `moduledata` → Bearer migration.
 *
 * One device session, plus a cache of project access tokens: the backend
 * confirmed project tokens are stateless, any number may be valid at once,
 * and minting one never touches another — which is what lets a call about
 * another production, or a widget on one, sign as that production.
 *
 * ## The invariants this class exists to protect
 *
 *  - The device refresh token is SINGLE-USE. Every rotation goes through
 *    [refreshLock]: concurrent 401s share one refresh, because a second
 *    refresh with an already-rotated token reads server-side as theft and
 *    kills the whole session. Project mints rotate nothing and take only a
 *    per-production lock. Lock order is mint lock → [refreshLock], never
 *    the reverse.
 *  - The rotated refresh token is on disk BEFORE the new session is used.
 *  - Access tokens live in memory only. A token past ~80% of its life is
 *    treated as dead at the point of use, and a foreground loop renews the
 *    device session and the open production's token ahead of time, so
 *    bursts rarely pay a renewal round trip. Every timing derives from the
 *    server's `expires_in`; nothing is hardcoded.
 *
 * ## Mode
 *
 * Decided by `POST /session/device` itself, **not** by a configuration flag.
 * The phones dropped `token_auth_enabled` (Shubham, Sep 2026) because
 * `GET /configuration` is project-scoped while the token flow starts at
 * device level, before any production is chosen — and on develop that call
 * now answers 401 under `moduledata`, so a client waiting to be told to use
 * tokens waits forever. That is exactly how this port failed: every request
 * came back `libs_moduledata_not_accepted` while the flag it was waiting for
 * sat behind a call that needed the token.
 *
 * So token mode is **on by default** and turns off for two proven cases only:
 *
 * - `401 libs_invalid_device_id` — no device registered yet (a fresh install
 *   before create/join). Stay on `moduledata`, and [onDeviceRegistered]
 *   re-probes once the device exists.
 * - `session_token_auth_disabled` — the server's kill switch.
 *
 * A token-eligible call never *skips* the token: it waits while the session
 * is established and drops to `moduledata` only after acquisition has
 * genuinely failed. That ordering is the point — `moduledata` is still
 * accepted on some routes during the migration, but others have tightened,
 * so an eager fallback shows up as a 401 rather than a graceful degrade.
 */
@Suppress("TooManyFunctions") // The session's operations, each small; splitting them would only scatter the locks.
class TokenSessionManager(
    private val api: SessionApi,
    private val store: TokenAuthStore,
    private val scope: CoroutineScope,
    /** The open production, for the warm-ups; null when none is. */
    private val activeProjectId: () -> String?,
    private val nowMillis: () -> Long,
) : RequestAuthenticator {

    internal data class CachedToken(val value: String, val issuedAtMillis: Long, val expiresInSeconds: Long) {
        /** Renewal is due at ~80% of the token's life, never at its end. */
        val renewAtMillis: Long
            get() = issuedAtMillis + (expiresInSeconds * MILLIS_PER_SECOND * RENEW_AT_FRACTION).toLong()

        fun isUsable(now: Long): Boolean = value.isNotEmpty() && now < renewAtMillis
    }

    /**
     * The server does not know this device — set by a `libs_invalid_device_id`
     * verdict, cleared by [onDeviceRegistered]. The only thing that turns
     * token mode off besides the kill switch.
     */
    @Volatile private var noDeviceYet = false

    /**
     * Quiet period after a transient establish failure. Token mode stays on,
     * so without this every request would fire its own `/session/device`
     * attempt while the server is unreachable.
     */
    @Volatile private var establishBackoffUntil = 0L

    @Volatile private var killSwitched = false

    @Volatile private var deviceToken: CachedToken? = null

    /** A token the socket handshake refused; the socket presents `moduledata` until a different one exists. */
    @Volatile private var socketRejectedToken: String? = null

    /**
     * Bumped by [clearSession]. A renewal or mint captures it when it starts
     * and refuses to adopt its result if it changed — otherwise one finishing
     * after sign-out would persist a fresh refresh token and resurrect the
     * dead session.
     */
    @Volatile private var epoch = 0

    private val refreshLock = Mutex()
    private val tokensLock = Mutex()
    private val projectTokens = mutableMapOf<String, CachedToken>()
    private val mintLocks = mutableMapOf<String, Mutex>()
    val tokenMode: Boolean
        get() = !killSwitched && !noDeviceYet

    /**
     * The mode probe — `POST /session/device`, which replaced the
     * `token_auth_enabled` flag. Called once at start and after sign-in.
     *
     * Cheap and idempotent: a no-op when a usable device token is cached, a
     * refresh when one is stored, and a `moduledata` exchange only when there
     * is neither. A warm-up rather than a gate — requests establish the
     * session on demand anyway; this just gets it in place before the first
     * burst so those calls do not each wait on it, and it is where
     * `libs_invalid_device_id` is discovered.
     */
    fun probeDeviceSession() {
        if (killSwitched) return
        scope.launch { warmUp() }
    }

    /**
     * Wake seam — the window came back to the front, or the network did.
     *
     * The point-of-use 80% check already guarantees correctness; this only
     * moves the renewal off the critical path, and clears the establish
     * backoff so a session that failed while offline is retried at once
     * rather than after the quiet period. Since the timer went, this and the
     * point-of-use check are the whole renewal story — which is the shape the
     * backend asked for (24 Sep 2026): every rotation nobody needed is
     * another chance to be left holding a token the server has retired.
     */
    fun onAppForegrounded() {
        if (killSwitched) return
        establishBackoffUntil = 0L
        scope.launch { warmUp() }
    }

    /**
     * Create/join project has completed — that is the call which registers
     * the device, so a probe that answered `libs_invalid_device_id` will now
     * succeed.
     */
    fun onDeviceRegistered() {
        if (!noDeviceYet) return
        noDeviceYet = false
        ZillitLog.i(TAG) { "device registered; re-probing /session/device" }
        probeDeviceSession()
    }

    /**
     * The server does not recognise this device — removed from the project,
     * unlinked, or re-registered on another install.
     *
     * Leaving token mode is the only honest thing the client can do: a device
     * record is created by create/join project or device recovery, not by any
     * call this layer can make. Without it the phones saw two 401s per call,
     * on every call, for as long as the app stayed open — mint a replacement
     * for the same dead device, get refused, fall back. Idempotent: the first
     * rejection wins.
     */
    fun onDeviceRejectedByServer() {
        if (noDeviceYet) return
        noDeviceYet = true
        scope.launch { clearTokens() }
        ZillitLog.w(TAG) { "server rejected this device; on moduledata until it is registered again" }
    }

    override suspend fun bearerFor(module: RequestModule, projectId: String?, path: String): String? {
        if (!tokenMode) return null
        return scopeFor(module, projectId, path)?.let { bearerTokenFor(it) }
    }

    override suspend fun recoverFromUnauthorized(
        module: RequestModule,
        projectId: String?,
        path: String,
        failedToken: String,
    ): String? = scopeFor(module, projectId, path)?.let { recoverFromUnauthorized(it, failedToken) }

    /**
     * The scope one request rides, with the route taken into account.
     *
     * [tokenScope] answers the device token for a project-scoped module with
     * no production open, which is right for the many device-level calls
     * written against a project variant. For the handful of routes that are
     * genuinely project-scoped that fallback is a guaranteed 401, so those
     * take the legacy credential instead — see [requiresProjectToken].
     */
    private fun scopeFor(module: RequestModule, projectId: String?, path: String): TokenScope? {
        val scope = module.tokenScope(projectId) ?: return null
        if (scope == TokenScope.Device && requiresProjectToken(path)) return null
        return scope
    }

    /** The Bearer for one request; null when none could be obtained. */
    suspend fun bearerTokenFor(tokenScope: TokenScope): String? = when (tokenScope) {
        TokenScope.Device -> ensureDeviceToken()
        is TokenScope.Project -> projectToken(tokenScope.projectId, staleValue = null)
    }

    /**
     * Reactive 401 recovery: refresh or re-mint, and the caller retries ONCE
     * with what comes back. [failedToken] is what the refused request
     * carried — if another call already recovered while this one waited, the
     * current (different) token is returned without another rotation.
     */
    suspend fun recoverFromUnauthorized(tokenScope: TokenScope, failedToken: String): String? = when (tokenScope) {
        TokenScope.Device -> refreshLock.withLock {
            deviceToken?.takeIf { it.value != failedToken && it.isUsable(nowMillis()) }?.value
                ?: renewDeviceSessionLocked()
        }
        is TokenScope.Project -> projectToken(tokenScope.projectId, staleValue = failedToken)
    }

    /**
     * The device token for the socket handshake (`auth.token`, dual-accepted
     * server-side), from the cache only — the handshake is built
     * synchronously and must not wait on a mint. With token mode on and no
     * usable token cached this kicks a background establish and answers
     * null; the handshake falls back to `moduledata` this once, and the next
     * attempt picks the token up.
     */
    fun deviceTokenForSocket(): String? {
        if (!tokenMode) return null
        val usable = deviceToken?.takeIf { it.isUsable(nowMillis()) }
        if (usable == null) {
            scope.launch { ensureDeviceToken() }
            return null
        }
        return usable.value.takeIf { it != socketRejectedToken }
    }

    /**
     * The handshake refused [token]: renew once in the background; until a
     * different token exists, the socket sends `moduledata`.
     */
    fun socketTokenRejected(token: String) {
        socketRejectedToken = token
        scope.launch { recoverFromUnauthorized(TokenScope.Device, token) }
    }

    /** Mints the production's token ahead of its landing burst; a no-op when one is cached or the mode is off. */
    fun onActiveProjectChanged(projectId: String) {
        if (!tokenMode || projectId.isBlank()) return
        scope.launch { projectToken(projectId, staleValue = null) }
    }

    /** Sign-out: forget everything, the stored refresh token included. */
    fun clearSession() {
        epoch++
        scope.launch { clearTokens() }
        ZillitLog.i(TAG) { "session cleared" }
    }

    private suspend fun clearTokens() {
        deviceToken = null
        tokensLock.withLock { projectTokens.clear() }
        store.clearRefreshToken()
    }

    private suspend fun warmUp() {
        ensureDeviceToken() ?: return
        activeProjectId()?.takeIf { it.isNotBlank() }?.let { projectToken(it, staleValue = null) }
    }

    private suspend fun ensureDeviceToken(): String? {
        deviceToken?.takeIf { it.isUsable(nowMillis()) }?.let { return it.value }
        return refreshLock.withLock {
            deviceToken?.takeIf { it.isUsable(nowMillis()) }?.value ?: renewDeviceSessionLocked()
        }
    }

    /**
     * MUST hold [refreshLock]. Rotates through the stored refresh token when
     * there is one; otherwise — or when the server declares it dead — a fresh
     * `moduledata` exchange, which never expires during the migration, so an
     * invalidated refresh token re-establishes quietly instead of signing
     * the user out.
     */
    private suspend fun renewDeviceSessionLocked(): String? {
        val startEpoch = epoch
        val stored = store.refreshToken()
        if (stored != null) {
            when (rotate(stored, startEpoch)) {
                Rotation.Renewed -> return deviceToken?.value
                Rotation.Dead -> clearTokens()
                Rotation.Stop -> return null
            }
        }
        return establish(startEpoch)
    }

    private enum class Rotation { Renewed, Dead, Stop }

    private suspend fun rotate(stored: String, startEpoch: Int): Rotation =
        when (val result = api.refreshDeviceSession(stored)) {
            is SessionCallResult.Success ->
                if (adoptDeviceSession(result.data, startEpoch)) {
                    ZillitLog.i(TAG) { "device session refreshed (expires_in=${result.data.expiresInSeconds}s)" }
                    Rotation.Renewed
                } else {
                    Rotation.Stop
                }
            is SessionCallResult.Failure -> when {
                // The kill switch needs the explicit verdict: a bare 503 is a
                // load balancer hiccup, not the feature being turned off.
                result.serverMessage == MSG_TOKEN_AUTH_DISABLED -> {
                    killSwitch()
                    Rotation.Stop
                }
                result.serverMessage == MSG_REFRESH_INVALID ||
                    result.serverMessage == MSG_REFRESH_REUSE ||
                    result.httpStatus == STATUS_UNAUTHORIZED -> {
                    ZillitLog.w(TAG) { "refresh token dead (${result.serverMessage}); re-establishing" }
                    Rotation.Dead
                }
                // A transport error: keep the stored token, try again later.
                else -> Rotation.Stop
            }
        }

    /**
     * The `moduledata` exchange — and the mode probe, since its verdict is
     * what decides whether token auth is available at all.
     *
     * A transient failure starts a quiet period: token mode stays on, so
     * without one every request in a burst would fire its own attempt while
     * the server is unreachable.
     */
    private suspend fun establish(startEpoch: Int): String? {
        if (nowMillis() < establishBackoffUntil) return null
        return when (val result = api.establishDeviceSession()) {
            is SessionCallResult.Success -> {
                establishBackoffUntil = 0L
                if (adoptDeviceSession(result.data, startEpoch)) {
                    ZillitLog.i(TAG) { "device session established (expires_in=${result.data.expiresInSeconds}s)" }
                    deviceToken?.value
                } else {
                    null
                }
            }

            is SessionCallResult.Failure -> {
                when {
                    result.serverMessage == MSG_TOKEN_AUTH_DISABLED -> killSwitch()
                    // Not a token problem: there is no device record to mint
                    // for. Token mode goes off until one is registered.
                    result.serverMessage == MSG_INVALID_DEVICE_ID -> onDeviceRejectedByServer()
                    else -> establishBackoffUntil = nowMillis() + ESTABLISH_BACKOFF_MILLIS
                }
                ZillitLog.w(TAG) { "device session not established: ${result.httpStatus} ${result.serverMessage}" }
                null
            }
        }
    }

    /**
     * False, and nothing adopted, when the session was cleared while this
     * renewal was in flight — the server-side rotation is orphaned and
     * simply expires.
     */
    private suspend fun adoptDeviceSession(data: DeviceSession, startEpoch: Int): Boolean {
        if (epoch != startEpoch) {
            ZillitLog.i(TAG) { "session cleared mid-renewal; discarding the result" }
            return false
        }
        // The previous refresh token is already consumed server-side, so the
        // new one must survive a crash before the session is used. A persist
        // that fails leaves the store empty on purpose: a consumed token left
        // on disk would trip the server's reuse alarm at the next start, and
        // a clean `moduledata` re-establish is the safe recovery.
        if (!store.saveRefreshToken(data.refreshToken)) {
            store.clearRefreshToken()
            ZillitLog.w(TAG) { "refresh token could not be persisted; store cleared for a clean re-establish" }
        }
        deviceToken = CachedToken(data.accessToken, nowMillis(), data.expiresInSeconds ?: DEFAULT_TTL_SECONDS)
        return true
    }

    /**
     * [staleValue] is a token the server refused: a cached token equal to it
     * is skipped so recovery mints fresh, while one another call minted in
     * the meantime is reused without a round trip.
     */
    private suspend fun projectToken(projectId: String, staleValue: String?): String? {
        cachedProjectToken(projectId, staleValue)?.let { return it }
        val lock = tokensLock.withLock { mintLocks.getOrPut(projectId) { Mutex() } }
        return lock.withLock {
            cachedProjectToken(projectId, staleValue) ?: mintProjectTokenLocked(projectId)
        }
    }

    private suspend fun cachedProjectToken(projectId: String, staleValue: String?): String? =
        tokensLock.withLock {
            projectTokens[projectId]?.takeIf { it.isUsable(nowMillis()) && it.value != staleValue }?.value
        }

    /**
     * MUST hold the production's mint lock. Lock order is mint lock →
     * [refreshLock]; nothing under the latter ever takes a mint lock.
     */
    private suspend fun mintProjectTokenLocked(projectId: String): String? {
        val startEpoch = epoch
        var device = ensureDeviceToken() ?: return null
        var result = api.mintProjectToken(device, projectId)
        if (result.isNoAccess()) {
            // Not an auth problem: the device has no usable membership there.
            // The call falls back to `moduledata` and the route itself answers
            // the proper no-access response for the screen.
            val why = (result as SessionCallResult.Failure).serverMessage
            ZillitLog.i(TAG) { "project token denied for $projectId: $why" }
            return null
        }
        if (result is SessionCallResult.Failure && result.httpStatus == STATUS_UNAUTHORIZED) {
            // The device token was refused despite looking fresh: one renewal, one retry.
            val failed = device
            device = refreshLock.withLock {
                deviceToken?.takeIf { it.value != failed && it.isUsable(nowMillis()) }?.value
                    ?: renewDeviceSessionLocked()
            } ?: return null
            result = api.mintProjectToken(device, projectId)
        }
        return when (result) {
            is SessionCallResult.Success -> adoptProjectToken(projectId, result.data, startEpoch)
            is SessionCallResult.Failure -> {
                if (result.serverMessage == MSG_TOKEN_AUTH_DISABLED) killSwitch()
                ZillitLog.w(TAG) {
                    "project token not minted for $projectId: ${result.httpStatus} ${result.serverMessage}"
                }
                null
            }
        }
    }

    private suspend fun adoptProjectToken(projectId: String, data: ProjectSession, startEpoch: Int): String? {
        if (epoch != startEpoch) {
            ZillitLog.i(TAG) { "session cleared mid-mint; discarding the project token" }
            return null
        }
        val token = CachedToken(data.accessToken, nowMillis(), data.expiresInSeconds ?: DEFAULT_TTL_SECONDS)
        tokensLock.withLock { projectTokens[projectId] = token }
        ZillitLog.i(TAG) { "project token minted for $projectId (scope=${data.scope})" }
        return token.value
    }

    private fun SessionCallResult<*>.isNoAccess(): Boolean =
        this is SessionCallResult.Failure && (serverMessage == MSG_NO_MEMBERSHIP || serverMessage == MSG_ACCESS_DENIED)

    private fun killSwitch() {
        killSwitched = true
        ZillitLog.w(TAG) { "kill switch: token auth disabled by the server; back to moduledata" }
    }

    companion object {
        private const val TAG = "TokenSession"
        private const val MILLIS_PER_SECOND = 1000L
        private const val RENEW_AT_FRACTION = 0.8
        private const val STATUS_UNAUTHORIZED = 401

        /**
         * Assumed only when a mint answers without a usable `expires_in`.
         *
         * Deliberately tiny, and deliberately not an hour. All three mints
         * always return `expires_in`, it always equals the token's own
         * `exp - iat`, and it is one fixed value per environment — 3600s on
         * production, **300s on develop and QA** (backend, 23 Sep 2026). So a
         * missing value means our parse failed, not the server omitting it,
         * and the old 3600 default held a token about twelve times past its
         * real life on develop, with a genuine 401 on every call in between.
         * A minute costs one extra renewal and cannot outlive the shortest
         * TTL in any environment.
         */
        internal const val DEFAULT_TTL_SECONDS = 60L

        /** Quiet period after a transient establish failure — see [establish]. */
        private const val ESTABLISH_BACKOFF_MILLIS = 30_000L

        /**
         * No device record the server recognises. Matched on ordinary 401s
         * too, not just the session call's — see [onDeviceRejectedByServer].
         */
        const val MSG_INVALID_DEVICE_ID = "libs_invalid_device_id"

        // The server's verdicts, by name.
        const val MSG_REFRESH_INVALID = "session_refresh_invalid"
        const val MSG_REFRESH_REUSE = "session_refresh_reuse_detected"
        const val MSG_TOKEN_AUTH_DISABLED = "session_token_auth_disabled"
        const val MSG_NO_MEMBERSHIP = "session_project_no_membership"
        const val MSG_ACCESS_DENIED = "session_project_access_denied"
    }
}
