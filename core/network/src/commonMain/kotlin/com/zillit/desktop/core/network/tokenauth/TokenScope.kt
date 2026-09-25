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
 * A project-scoped module with no production in context also answers null,
 * so a misplaced call degrades to today's behaviour rather than failing.
 */
fun RequestModule.tokenScope(projectId: String?): TokenScope? = when (this) {
    RequestModule.Default -> TokenScope.Device

    RequestModule.Project,
    RequestModule.Chat,
    RequestModule.Media,
    RequestModule.Configuration,
    RequestModule.ProjectUser,
    RequestModule.NotificationAcknowledge,
    -> projectId?.takeIf { it.isNotBlank() }?.let(TokenScope::Project)

    RequestModule.Device,
    RequestModule.ScannerDevice,
    RequestModule.MapRoute,
    RequestModule.SocketHandshake,
    RequestModule.LiveKit,
    RequestModule.SessionBootstrap,
    RequestModule.Telemetry,
    -> null
}
