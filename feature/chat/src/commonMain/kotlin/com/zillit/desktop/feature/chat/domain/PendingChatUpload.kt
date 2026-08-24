package com.zillit.desktop.feature.chat.domain

/**
 * A file picked (or pasted) for a thread but not yet in storage.
 *
 * The pick and the upload are split so the thread can preview the file before
 * anything moves: the bytes feed the preview dialog (and its picture editor),
 * and [upload] runs only after Send — taking the possibly edited bytes,
 * reporting 0..100 as they move, and answering with the stored file, or null
 * when storage refused it. Before the first percent arrives the file is
 * still being prepared (poster frames, PDF pages), which the bubble narrates
 * as processing.
 */
class PendingChatUpload(
    val name: String,
    val contentType: String,
    /** The file as picked — what the preview shows and the editor starts from. */
    val bytes: ByteArray,
    val upload: suspend (bytes: ByteArray, onProgress: (Int) -> Unit) -> ChatAttachment?,
)
