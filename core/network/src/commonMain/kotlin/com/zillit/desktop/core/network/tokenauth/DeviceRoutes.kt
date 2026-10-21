package com.zillit.desktop.core.network.tokenauth

import com.zillit.desktop.core.network.HttpVerb

/**
 * The routes a signed-in device may call with NO production open — the only
 * ones the DEVICE token is allowed to reach.
 *
 * Every other route belongs to a module inside a production and needs that
 * production's token: the server answers a device token there with `401
 * libs_invalid_token_scope` (product rule, 2026-09-29). So with no production
 * open, a route that is not listed here is refused before it is sent — no
 * request, no 401, no refresh. This is the web's `api/deviceRoutes.js`, whose
 * `isDeviceRoute` gate in `api/interceptor.js` this mirrors.
 *
 * **FAIL-CLOSED**: a device-level route missing from this list stops working
 * on the pre-production surface (the production list after a scan, the device
 * screens, a public page opened by a signed-in visitor). Add it here, anchored
 * like the rest.
 *
 * Built the way the web's was — from a sweep of every request the app actually
 * makes with no production open, read off `~/.zillit/logs` across several
 * launches (2026-10-19) — and then cross-checked against the web's list. The
 * sweep is what makes this safe: every route below was already answering
 * successfully on a device token, and the only pre-production calls left out
 * are the three the server was observed refusing.
 *
 * Deliberately NOT listed, and so refused with no production open:
 *
 *  - `/chat-room` and `/chat/users-group` — the chat listing's warm pass;
 *  - `/mediasoup-call/active-group-calls` — the calling plane's seed.
 *
 * Those three are where this list came from: between them they answered `401
 * libs_invalid_token_scope` 21 times in two days of ordinary use, every one of
 * them on the production-list screen before a production had been chosen. The
 * web never sends them there either.
 *
 * Also not listed, because they never take the token path at all: the
 * `moduledata`-pinned bootstrap routes (the QR handshake, device recovery,
 * device OTP) and [com.zillit.desktop.core.network.RequestModule.Telemetry]'s
 * `location/log`, whose module keeps the legacy credential by itself.
 */
private val MATCHERS: List<(HttpVerb, String) -> Boolean> = listOf(
    // Every matcher is anchored at a path SEGMENT boundary and tolerates any
    // host, the `/api` prefix and a trailing slash. An unanchored `contains`
    // would silently admit a future sibling — and a wrongly admitted route
    // sends a device token the server refuses, the failure this list exists
    // to stop.

    // The production list itself: `/v2/project` exactly, never
    // `/v2/project/tools` and its kin, which belong to an open production.
    { verb, path -> verb == HttpVerb.Get && path.matches(route("project")) },

    // Starring a production on that list.
    { verb, path -> verb == HttpVerb.Post && path.matches(route("project/favourite-project")) },

    // One production read by id, for the card the user is about to open. The
    // web leaves this off and pins that production's own token instead; here
    // it is listed, because the desktop's caller does not name a production
    // and the route has been answering a device token all along.
    //
    // Matched as an id — 24 hex characters — and not as "one segment": every
    // tool route in the app is also one segment under `/project`, and a
    // `[^/]+` here admitted `/project/tools`, `/project/users` and
    // `/project/pendingtools`, which are the most project-scoped routes there
    // are. The test below holds that line.
    { verb, path -> verb == HttpVerb.Get && path.matches(route("project/[0-9a-fA-F]{24}")) },

    // Device badges across every production (the notification host).
    { verb, path ->
        verb == HttpVerb.Get && path.matches(route("project/all/notifications/[^/]+/(next|previous)"))
    },

    // The device record (its FCM token), the devices this account has linked,
    // and unlinking one at logout.
    { verb, path -> (verb == HttpVerb.Get || verb == HttpVerb.Put) && path.matches(route("device")) },
    { verb, path -> verb == HttpVerb.Get && path.matches(route("device/linked")) },
    { verb, path -> verb == HttpVerb.Post && path.matches(route("device/unlink")) },

    // Reference presets — labels, messages, identifiers, isd-codes, languages,
    // production types. They carry no production by nature.
    { verb, path -> verb == HttpVerb.Get && path.contains("/v2/preset/") },

    // The signed-in person's own profile, which the production list shows
    // before any production is open.
    { verb, path -> (verb == HttpVerb.Get || verb == HttpVerb.Put) && path.matches(route("user/profile")) },

    // The error-log sink: a failure raised on the pre-production surface must
    // still land. Listed for the web's reason, though `Telemetry` keeps this
    // route on `moduledata` here, so the gate is never consulted for it.
    { verb, path -> verb == HttpVerb.Post && path.matches(route("location/log")) },

    // Public signing links opened by a signed-in visitor: the link's own token
    // authorises these, not the Bearer.
    { verb, path ->
        (verb == HttpVerb.Post || verb == HttpVerb.Patch) &&
            path.matches(
                route(
                    "sign/(resolve|send-email-code|verify-access-code|request-new-link|" +
                        "accept|withdraw-consent|decline|submit)",
                ),
            )
    },
    { verb, path -> verb == HttpVerb.Post && path.matches(route("nda-draft/(upload|sign)")) },
)

/**
 * One route as a whole-path regex: any scheme and host, the optional `/api`
 * prefix, `/v2/`, then [tail] and nothing else but a trailing slash or query.
 */
private fun route(tail: String): Regex = Regex(".*/v2/+$tail/?(\\?.*)?$")

/**
 * Whether [url] is a route the device token may reach with no production open.
 *
 * Tolerates a query string and any base, as the web's does.
 */
fun isDeviceRoute(verb: HttpVerb, url: String): Boolean {
    val path = url.substringBefore('#').trim()
    return MATCHERS.any { match -> match(verb, path) }
}
