package com.zillit.desktop.core.network

/**
 * The Bearer credential a call carries in token mode, and its recovery.
 *
 * Null from [bearerFor] means "send the legacy headers": token mode is off,
 * this module stays on `moduledata`, or no token could be obtained. The
 * server accepts the encrypted header for the whole migration, so a call
 * degrades to it rather than going out with nothing and 401-ing into a
 * sign-out over a transient failure.
 */
interface RequestAuthenticator {
    /**
     * The Bearer for one request, or null to send `moduledata` instead.
     *
     * [path] is the request's URL: a few routes are genuinely project-scoped
     * and refuse a device token, so the authenticator needs to see where the
     * call is going, not only which module it belongs to.
     */
    suspend fun bearerFor(module: RequestModule, projectId: String?, path: String): String?

    /**
     * After a 401 carrying [failedToken]: a renewed token to retry once with,
     * or null when the session cannot be recovered. A token another call
     * already renewed meanwhile is returned without another rotation.
     */
    suspend fun recoverFromUnauthorized(
        module: RequestModule,
        projectId: String?,
        path: String,
        failedToken: String,
    ): String?

    /**
     * After a 401 whose envelope reads `libs_invalid_token_scope`: a token of
     * the OTHER scope to retry once with, or null when there is none to swap
     * to.
     *
     * A scope refusal is the server judging the credential's TYPE, not its
     * age — the device token on a production's route, or a production's token
     * after the open production moved under a call already in flight.
     * [recoverFromUnauthorized] is the wrong answer to it twice over: a
     * refresh cannot turn one scope into another, and it rotates the
     * single-use device refresh token to re-send a request certain to be
     * refused again. So the caller comes here instead, and the swap is what
     * can actually succeed.
     *
     * This is the web's `_zForceScope` retry (`api/interceptor.js`), including
     * its guard: device → project needs a production to mint for, and with
     * none there is nothing to swap to, so the 401 stands rather than
     * presenting the same device token twice.
     *
     * Defaults to null so an authenticator that does not model scopes (the
     * tests' fakes, and legacy mode) simply lets the refusal stand.
     */
    suspend fun recoverFromScopeRefusal(
        module: RequestModule,
        projectId: String?,
        path: String,
        failedToken: String,
    ): String? = null

    /**
     * Whether this call must not be sent at all, because it belongs to a
     * production and none is open.
     *
     * The device token reaches only the device-level routes
     * ([com.zillit.desktop.core.network.tokenauth.isDeviceRoute]); on any
     * other route the server answers `401 libs_invalid_token_scope`, and the
     * legacy header it used to degrade to is refused as well. So the caller
     * is told no here instead — no request, no 401, no refresh — which is
     * what the web's interceptor does with the same two lists.
     *
     * Defaults to false so an authenticator that does not model scopes (the
     * tests' fakes, and legacy mode) keeps sending everything.
     */
    suspend fun refusesWithNoProject(
        module: RequestModule,
        projectId: String?,
        verb: HttpVerb,
        path: String,
    ): Boolean = false
}
