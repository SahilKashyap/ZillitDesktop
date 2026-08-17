package com.zillit.desktop.feature.chat.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * The chat composer's microphone.
 *
 * Start opens the take; stop closes it, uploads it, and returns the stored
 * attachment ready to ride a message envelope; cancel throws the take away.
 * Hosts implement it with the same recorder and routed uploader the board
 * uses, so a voice note is one capture pipeline wherever it is spoken.
 */
interface ChatVoice {
    suspend fun start(): ZillitResult<Unit>
    suspend fun stop(): ZillitResult<ChatAttachment>
    fun cancel()
}
