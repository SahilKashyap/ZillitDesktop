package com.zillit.desktop.feature.calls.data.livekit

import com.zillit.desktop.feature.calls.domain.CallJoin

/**
 * The commands Kotlin sends the call page's Line 3 half (`livekit.js`,
 * `window.zillitLk`).
 *
 * Strings rather than a typed bridge for the same reason `EngineBridge` and
 * `MediasoupScripts` are: the page is driven by `executeJavaScript`, and one
 * quoting function is easier to get right than a serialisation layer. The
 * page answers through `cefQuery` in the vocabulary `EngineBridge.parse`
 * already reads — `joined`, `peer-joined`, `peer-audio`, `speakers`,
 * `connection` — so the coordinator hears Line 3 exactly as it hears Line 2.
 */
object LiveKitScripts {

    fun join(params: CallJoin, microphoneId: String): String =
        "zillitLk.join(${params.livekitUrl.js()}, ${params.livekitToken.js()}, ${params.identity.js()}, " +
            "${params.displayName.js()}, ${params.hasVideo}, ${microphoneId.js()})"

    const val LEAVE = "zillitLk.leave()"

    fun setMic(muted: Boolean): String = "zillitLk.setMic($muted)"

    fun setCam(enabled: Boolean): String = "zillitLk.setCam($enabled)"

    fun setMicrophoneDevice(deviceId: String): String = "zillitLk.setMicrophoneDevice(${deviceId.js()})"

    fun setCameraDevice(deviceId: String): String = "zillitLk.setCameraDevice(${deviceId.js()})"

    fun startScreenShare(sourceId: String?): String = "zillitLk.startScreenShare(${sourceId?.js() ?: "null"})"

    const val STOP_SCREEN_SHARE = "zillitLk.stopScreenShare()"

    fun setHandRaised(raised: Boolean): String = "zillitLk.setHand($raised)"

    fun sendChat(id: String, text: String, atMillis: Long): String =
        "zillitLk.sendChat(${id.js()}, ${text.js()}, $atMillis)"

    /** A JS string literal: quoted, with the characters that would end it or the script escaped. */
    private fun String.js(): String = buildString {
        append('"')
        for (ch in this@js) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                ' ' -> append("\\u2028")
                ' ' -> append("\\u2029")
                '<' -> append("\\u003c")
                else -> append(ch)
            }
        }
        append('"')
    }
}
