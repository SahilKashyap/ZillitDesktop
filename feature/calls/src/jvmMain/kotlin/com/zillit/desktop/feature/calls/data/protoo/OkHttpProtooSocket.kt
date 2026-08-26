package com.zillit.desktop.feature.calls.data.protoo

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * The protoo signalling socket, in Kotlin rather than in the call page.
 *
 * That placement is the whole reason this class exists. protoo notices a
 * half-open socket — the CGNAT case, where the connection is gone but nothing
 * says so — by sending a WebSocket PING every twenty seconds and watching for
 * the pong. The browser WebSocket API exposes no ping at all and Chromium
 * sends none of its own, so a page-hosted socket loses that detector entirely:
 * signalling dies silently, media keeps flowing over UDP for a while, and the
 * call ends up in a state nobody can explain. OkHttp does ping, so signalling
 * lives here and only the media pipeline runs in the page.
 *
 * Everything requiring judgement is elsewhere and tested: the frame grammar in
 * [parseProtoo], correlation in [ProtooPeer], and when to redial in
 * [ProtooReconnect]. What is left here is the part only a real socket can do.
 */
class OkHttpProtooSocket(
    private val scope: CoroutineScope,
    private val client: OkHttpClient = defaultClient(),
    private val policy: ProtooReconnect = ProtooReconnect(),
    /** Each received frame, in order. */
    private val onFrame: suspend (String) -> Unit,
    /** The socket is up. Fires again after every successful redial. */
    private val onConnected: suspend () -> Unit = {},
    /** No more redials will happen. The call is over as far as signalling goes. */
    private val onGiveUp: suspend (String) -> Unit = {},
) {
    private var socket: WebSocket? = null

    /**
     * Asked for a URL on every dial, not once.
     *
     * A redial must not replay the original: the peer id in it has to change,
     * or the SFU evicts the peer we are trying to restore and tells the remote
     * everybody left. See `mediasoupRejoinPeerId`.
     */
    private var nextUrl: (() -> String)? = null
    private var reconnect: Job? = null

    /**
     * True once [close] has been called. Distinguishes our own teardown from
     * the socket dying, which otherwise look identical from the listener.
     */
    @Volatile
    private var shuttingDown = false

    /**
     * Opens the socket and keeps it open.
     *
     * [url] is a function because every redial needs a new one; calling it
     * again is how the caller gets to change the peer id between attempts.
     */
    fun dial(url: () -> String) {
        nextUrl = url
        shuttingDown = false
        open()
    }

    /** Sends one frame. False when the socket would not take it. */
    fun send(frame: String): Boolean = socket?.send(frame) ?: false

    /** Our own teardown. No redial follows this. */
    fun close(reason: String = "client closed") {
        shuttingDown = true
        reconnect?.cancel()
        reconnect = null
        nextUrl = null
        // 1000 is the only code a client may send that is not reserved.
        socket?.close(NORMAL_CLOSURE, reason)
        socket = null
    }

    private fun open() {
        val target = nextUrl?.invoke() ?: return
        val request = Request.Builder()
            .url(target)
            // protoo is a subprotocol, not a convention: a server that speaks
            // it will refuse a handshake that does not ask for it.
            .header("Sec-WebSocket-Protocol", PROTOCOL)
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // A frame from a socket we have already rotated away from is not
            // ours; ignoring it is what stops a slow redial from resurrecting
            // a dead session.
            if (webSocket !== socket) return
            ZillitLog.i(TAG) { "protoo open" }
            policy.onConnected()
            scope.launch { onConnected() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            scope.launch { onFrame(text) }
        }

        /** A close frame arrived — the server said goodbye deliberately. */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            webSocket.close(NORMAL_CLOSURE, null)
            settle(policy.onCleanClose(code, reason), "clean close $code ${reason.take(REASON_LOG_CHARS)}")
        }

        /**
         * The socket died without a close frame, or OkHttp's ping found it
         * half-open. This is the recoverable path.
         */
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            val code = response?.code ?: 0
            val reason = t.message.orEmpty()
            ZillitLog.w(TAG) { "protoo dropped: ${t::class.simpleName} ${reason.take(REASON_LOG_CHARS)}" }
            settle(policy.onAbruptClose(code, reason), "drop")
        }
    }

    private fun settle(step: ProtooNextStep, context: String) {
        socket = null
        // Our own close already told everyone; anything the listener reports
        // afterwards is the echo of it.
        if (shuttingDown) return

        when (step) {
            is ProtooNextStep.Stop -> {
                ZillitLog.i(TAG) { "protoo stopping ($context): ${step.reason}" }
                scope.launch { onGiveUp(step.reason) }
            }

            is ProtooNextStep.Retry -> {
                // Coalesced: several failures can land for one outage, and each
                // scheduling its own redial is how a reconnect becomes a storm.
                if (reconnect?.isActive == true) return
                ZillitLog.i(TAG) { "protoo redial ${step.attempt} in ${step.delayMillis}ms" }
                reconnect = scope.launch {
                    delay(step.delayMillis)
                    if (!shuttingDown) open()
                }
            }
        }
    }

    companion object {
        /** The subprotocol protoo servers require in the handshake. */
        const val PROTOCOL = "protoo"

        private const val NORMAL_CLOSURE = 1000
        private const val REASON_LOG_CHARS = 120

        /**
         * Pings every twenty seconds, which is the point of using OkHttp here.
         *
         * No read timeout: a signalling socket is idle for most of a call by
         * design, and a read timeout would kill exactly the healthy quiet ones.
         * The ping is what distinguishes quiet from dead.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(PROTOO_PING_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}

private const val TAG = "ProtooSocket"
