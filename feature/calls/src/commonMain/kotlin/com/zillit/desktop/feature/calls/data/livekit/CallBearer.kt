package com.zillit.desktop.feature.calls.data.livekit

/**
 * The Bearer the calling backend's `/v1` routes authenticate with: the
 * PROJECT-scoped token — user, production and device in one — never the
 * device token, which `/v1` refuses (`libs_invalid_token_scope`).
 *
 * Null from [tokenFor] means "no token here": outside token mode, or none
 * could be obtained. The request then goes on `moduledata` alone, exactly as
 * it did before the migration.
 */
interface CallBearer {
    suspend fun tokenFor(projectId: String): String?

    /** After a 401 carrying [failed]: a renewed token to retry once with, or null. */
    suspend fun renew(projectId: String, failed: String): String?
}

/**
 * Sends one calling-backend request with the Bearer, and heals an expired one
 * once — Android's `ModuleDataInterceptor`, which is the reference:
 *
 *  - the token rides ALONGSIDE `moduledata` and `bodyhash`, never instead of
 *    them, so a calling backend not yet migrated still authenticates on the
 *    legacy header and a migrated one takes the Bearer;
 *  - no production known ([projectId] blank), or no token: the request goes
 *    without one;
 *  - a 401 to a request that DID carry a token renews it and retries once;
 *    the retry's answer is the one reported.
 *
 * [send] performs the request with the given token (null: none) and
 * [statusOf] reads its HTTP status.
 */
suspend fun <T> CallBearer?.signed(
    projectId: String?,
    statusOf: (T) -> Int,
    send: suspend (bearer: String?) -> T,
): T {
    val production = projectId?.takeIf { it.isNotBlank() }
    val token = production?.let { this?.tokenFor(it) }?.takeIf { it.isNotBlank() }
    val first = send(token)
    if (token == null || production == null || statusOf(first) != HTTP_UNAUTHORIZED) return first
    val renewed = this?.renew(production, token)?.takeIf { it.isNotBlank() && it != token } ?: return first
    return send(renewed)
}

private const val HTTP_UNAUTHORIZED = 401
