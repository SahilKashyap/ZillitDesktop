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
 * [tokenMode] is `token_auth_enabled` from the configuration — cached across
 * starts, so a cold start knows before the first configuration answers —
 * minus the server's kill switch: a `session_token_auth_disabled` verdict
 * falls back to `moduledata` until the next configuration says otherwise.
 * The server accepts `moduledata` for the whole migration, so every failure
 * to obtain a token degrades to it rather than to a credential-less call.
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

    @Volatile private var configEnabled: Boolean? = null

    @Volatile private var cachedEnabled = false

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
    private var proactive: Job? = null

    init {
        scope.launch { cachedEnabled = store.tokenModeCache() }
    }

    val tokenMode: Boolean
        get() = !killSwitched && (configEnabled ?: cachedEnabled)

    /** Every configuration answer lands here — the phones wire it to `GET /configuration`. */
    fun onConfigFetched(enabled: Boolean) {
        val wasOn = tokenMode
        configEnabled = enabled
        killSwitched = false
        cachedEnabled = enabled
        ZillitLog.i(TAG) { "token_auth_enabled=$enabled" }
        scope.launch { store.cacheTokenMode(enabled) }
        if (enabled && !wasOn) scope.launch { warmUp() }
        if (!enabled) stopProactiveLoop()
    }

    override suspend fun bearerFor(module: RequestModule, projectId: String?): String? {
        if (!tokenMode) return null
        val tokenScope = module.tokenScope(projectId) ?: return null
        return bearerTokenFor(tokenScope)
    }

    override suspend fun recoverFromUnauthorized(
        module: RequestModule,
        projectId: String?,
        failedToken: String,
    ): String? {
        val tokenScope = module.tokenScope(projectId) ?: return null
        return recoverFromUnauthorized(tokenScope, failedToken)
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
        stopProactiveLoop()
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

    private suspend fun establish(startEpoch: Int): String? = when (val result = api.establishDeviceSession()) {
        is SessionCallResult.Success ->
            if (adoptDeviceSession(result.data, startEpoch)) {
                ZillitLog.i(TAG) { "device session established (expires_in=${result.data.expiresInSeconds}s)" }
                deviceToken?.value
            } else {
                null
            }
        is SessionCallResult.Failure -> {
            if (result.serverMessage == MSG_TOKEN_AUTH_DISABLED) killSwitch()
            ZillitLog.w(TAG) { "device session not established: ${result.httpStatus} ${result.serverMessage}" }
            null
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
        ensureProactiveLoop()
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

    /**
     * Foreground renewal: sleep until the device token's ~80% mark, rotate,
     * and keep the open production's token warm. Re-arms itself from each
     * new token's `expires_in`, dies quietly on failure, and the next
     * successful renewal (the reactive path) restarts it.
     */
    private fun ensureProactiveLoop() {
        if (proactive?.isActive == true) return
        proactive = scope.launch {
            while (isActive && tokenMode && renewWhenDue()) {
                activeProjectId()?.takeIf { it.isNotBlank() }?.let { id ->
                    projectToken(id, staleValue = tokensLock.withLock { projectTokens[id]?.value })
                }
            }
        }
    }

    /** Sleeps until the device token's renewal mark and rotates; false ends the loop. */
    private suspend fun renewWhenDue(): Boolean {
        val current = deviceToken ?: return false
        val wait = current.renewAtMillis - nowMillis()
        if (wait > 0) delay(wait)
        if (!tokenMode) return false
        val renewed = refreshLock.withLock {
            deviceToken?.takeIf { it.isUsable(nowMillis()) }?.value ?: renewDeviceSessionLocked()
        }
        return renewed != null
    }

    private fun stopProactiveLoop() {
        proactive?.cancel()
        proactive = null
    }

    companion object {
        private const val TAG = "TokenSession"
        private const val MILLIS_PER_SECOND = 1000L
        private const val RENEW_AT_FRACTION = 0.8
        private const val STATUS_UNAUTHORIZED = 401

        /** Only for a malformed answer without `expires_in`. */
        internal const val DEFAULT_TTL_SECONDS = 3600L

        // The server's verdicts, by name.
        const val MSG_REFRESH_INVALID = "session_refresh_invalid"
        const val MSG_REFRESH_REUSE = "session_refresh_reuse_detected"
        const val MSG_TOKEN_AUTH_DISABLED = "session_token_auth_disabled"
        const val MSG_NO_MEMBERSHIP = "session_project_no_membership"
        const val MSG_ACCESS_DENIED = "session_project_access_denied"
    }
}
