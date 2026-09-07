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
    suspend fun bearerFor(module: RequestModule, projectId: String?): String?

    /**
     * After a 401 carrying [failedToken]: a renewed token to retry once with,
     * or null when the session cannot be recovered. A token another call
     * already renewed meanwhile is returned without another rotation.
     */
    suspend fun recoverFromUnauthorized(module: RequestModule, projectId: String?, failedToken: String): String?
}
