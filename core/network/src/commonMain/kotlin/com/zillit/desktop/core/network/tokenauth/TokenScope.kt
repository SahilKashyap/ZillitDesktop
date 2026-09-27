package com.zillit.desktop.core.network.tokenauth

import com.zillit.desktop.core.network.RequestModule

/**
 * The credential a request carries in token mode: the device access token
 * for device-level routes, or a project access token bound to one production.
 */
sealed interface TokenScope {
    data object Device : TokenScope

    data class Project(val projectId: String) : TokenScope
}

/**
 * Which token a module's requests ride, or null when they stay on the legacy
 * `moduledata` header even in token mode — the phones' `TokenAuth.scopeFor`.
 *
 * Identity-only headers ride the Bearer token: it carries the same device,
 * project and user the encrypted header did. What stays legacy is every
 * header the server reads beyond identity — the scanning device's id on a
 * QR link — and what has no session to ride: the pre-auth device calls, the
 * session bootstrap itself (it would recurse), the map-route lookup, the
 * socket handshake (its own path), and Line 3's calling backend, which
 * signs with `moduledata` on the phones as well, and the error log, whose
 * handler reads identity out of the blob. Sending `moduledata`
 * alongside a token does not work — the token path never decrypts it — so
 * a request carries one or the other, never both.
 *
 * A project-scoped module with no production in context falls back to the
 * **device** token, not to `moduledata`. Several genuinely device-level calls
 * use a project variant only because that was the default at the call site —
 * the preset lookups on the create-project screen are fetched before any
 * production exists — and develop now answers `libs_moduledata_not_accepted`,
 * so degrading to the legacy header there is a guaranteed 401 rather than the
 * graceful fallback it used to be. The phones changed this for the same
 * reason (`TokenAuth.scopeFor`); [requiresProjectToken] covers the routes
 * where the fallback would itself be refused.
 */
fun RequestModule.tokenScope(projectId: String?): TokenScope? = when (this) {
    RequestModule.Default -> TokenScope.Device

    RequestModule.Project,
    RequestModule.Chat,
    RequestModule.Media,
    RequestModule.Configuration,
    RequestModule.ProjectUser,
    RequestModule.NotificationAcknowledge,
    -> projectId?.takeIf { it.isNotBlank() }?.let(TokenScope::Project) ?: TokenScope.Device

    RequestModule.Device,
    RequestModule.ScannerDevice,
    RequestModule.MapRoute,
    RequestModule.SocketHandshake,
    RequestModule.LiveKit,
    RequestModule.SessionBootstrap,
    RequestModule.Telemetry,
    -> null
}

/**
 * Routes the server has actually refused when handed a **device** token — the
 * ones that require a project token, no exceptions.
 *
 * Why this list exists: [tokenScope] falls back to the device token when a
 * project-scoped module resolves with no production open, deliberately. For a
 * route that is genuinely project-scoped that same fallback sends a credential
 * the server cannot accept — a guaranteed 401. The phones keep the same list
 * off their own production 401 report (`libs_invalid_token_scope`).
 *
 * Keep it **evidence-based**: add a route only once the server has been seen
 * refusing a device token on it. Listing one that would have worked sends
 * `moduledata` instead, which develop rejects — the opposite failure.
 */
private val PROJECT_SCOPED_PATHS = listOf(
    "/project/pendingtools",
    "/project/tools/group/order",
    "/project/tools/groups",
    "/project/users",
    "/location/units",
    "/webrtc/turn-credentials",
    "/account-hub/project-settings",
)

/**
 * Whether [path] must carry a project token, so a caller with only a device
 * token sends the legacy credential rather than one the route is certain to
 * refuse. Matched as a suffix, so a query string or a different host does not
 * matter.
 */
fun requiresProjectToken(path: String): Boolean {
    val clean = path.substringBefore('?').trimEnd('/')
    return PROJECT_SCOPED_PATHS.any { clean.endsWith(it) }
}
