package com.zillit.desktop.feature.calls.data.livekit

import com.zillit.desktop.feature.calls.data.protoo.OkHttpProtooSocket
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException

/**
 * The presence socket's transport — one OkHttp WebSocket per connection.
 *
 * In Kotlin rather than in the call page for the reason the protoo socket
 * gives: OkHttp pings, the browser API cannot, and a half-open presence
 * socket is a device that believes it is reachable while every ring for it
 * goes to voicemail. Reconnection policy lives in [LiveKitLine]; this only
 * opens, sends, and reports what happened.
 */
class OkHttpLiveKitSocket(
    private val client: OkHttpClient = OkHttpProtooSocket.defaultClient(),
) : LiveKitSocketFactory {

    override suspend fun connect(
        url: String,
        headers: Map<String, String>,
        onFrame: (String) -> Unit,
        onClosed: (reason: String) -> Unit,
    ): LiveKitSocket {
        val opened = CompletableDeferred<LiveKitSocket>()
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        var handle: Handle? = null
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    handle?.let { if (!opened.isCompleted) opened.complete(it) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) = onFrame(text)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val reason = "presence socket failed: ${t.message ?: t::class.simpleName}" +
                        (response?.let { " (${it.code})" } ?: "")
                    if (!opened.isCompleted) {
                        opened.completeExceptionally(IOException(reason, t))
                    } else {
                        handle?.closedOnce(reason)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handle?.closedOnce("closed $code ${reason.ifBlank { "(no reason)" }}")
                }
            },
        )
        handle = Handle(socket, onClosed)
        return opened.await()
    }

    /** One live connection. Reports its close exactly once, whichever path noticed it. */
    private class Handle(
        private val socket: WebSocket,
        private val onClosed: (String) -> Unit,
    ) : LiveKitSocket {
        private var reported = false

        override fun send(frame: String): Boolean = socket.send(frame)

        override fun close(reason: String) {
            socket.close(NORMAL_CLOSURE, reason.take(MAX_REASON))
            closedOnce("closed by us: $reason")
        }

        fun closedOnce(reason: String) {
            synchronized(this) {
                if (reported) return
                reported = true
            }
            onClosed(reason)
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val MAX_REASON = 120
    }
}
