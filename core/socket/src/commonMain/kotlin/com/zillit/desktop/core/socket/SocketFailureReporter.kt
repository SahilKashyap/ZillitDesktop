package com.zillit.desktop.core.socket

/**
 * Where a socket failure goes to be recorded on the server.
 *
 * The socket layer names the failure; it does not know the error log exists.
 * The host maps these into the shared `location/log` envelope — see
 * `AppLogWiring.socketFailureReporter` — so the category, the cap and the
 * GDPR rules live in one place instead of in every module that can fail.
 *
 * Without this, a refused handshake and a payload this build cannot read were
 * a line in `~/.zillit/logs` on the user's own machine and nothing anywhere
 * else: the web has reported both to the store for a year
 * (`SocketManager`'s `connect_error`, `accountHubListeners`' decode failure),
 * so a socket outage was findable on web and invisible on the desktop.
 *
 * NEVER a payload, a header or a credential: [report]'s `message` is the
 * reason the library or the server gave, and `context` is small named facts
 * about the attempt. A socket message carries chat bodies, financials and
 * crew names.
 */
fun interface SocketFailureReporter {

    fun report(name: String, message: String, context: Map<String, String>)

    companion object {
        /** The default: the socket reports nowhere, as it did before this existed. */
        val None = SocketFailureReporter { _, _, _ -> }

        /** A handshake that never became a connection. */
        const val CONNECT_ERROR = "socket_connect_error"

        /** A payload that arrived and could not be read into the type the subscriber asked for. */
        const val UNDECODABLE = "socket_event_undecodable"

        /** How much of a reason string is kept — enough to tell two refusals apart. */
        const val MESSAGE_MAX = 300
    }
}
