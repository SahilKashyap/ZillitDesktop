package com.zillit.desktop.feature.chat.domain

/**
 * A file picked for a thread but not yet in storage.
 *
 * The pick and the upload are split so the thread can show the bubble the
 * moment the file is chosen: [upload] runs after the optimistic bubble is on
 * screen, reporting 0..100 as the bytes move, and answers with the stored
 * file — or null when storage refused it. Before the first percent arrives
 * the file is still being prepared (poster frames, PDF pages), which the
 * bubble narrates as processing.
 */
class PendingChatUpload(
    val name: String,
    val contentType: String,
    val upload: suspend (onProgress: (Int) -> Unit) -> ChatAttachment?,
)
