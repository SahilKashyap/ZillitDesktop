package com.zillit.desktop.feature.esignature.ui.flows

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.esignature.ui.EsignStore
import com.zillit.desktop.feature.esignature.ui.MARK_RASTER_HEIGHT
import com.zillit.desktop.feature.esignature.ui.MARK_RASTER_WIDTH
import com.zillit.desktop.feature.esignature.ui.PadMode
import com.zillit.desktop.feature.esignature.ui.PadState
import com.zillit.desktop.feature.esignature.ui.orFail

/**
 * The saved-marks library — the web's `SavedSignaturePicker` in manage
 * mode: signatures and initials drawn, typed in a script face or uploaded,
 * kept on the envelope service's own route so any envelope can stamp them.
 */
internal class MarksFlow(private val store: EsignStore) {

    fun load() {
        store.update { copy(marks = marks.copy(loading = true)) }
        store.runTask {
            when (val items = store.repository.savedSignatures()) {
                is ZillitResult.Failure -> store.update { copy(marks = marks.copy(loading = false)) }
                is ZillitResult.Success -> {
                    store.update {
                        copy(
                            marks = marks.copy(items = items.data, loading = false),
                            signing = signing?.copy(savedMarks = items.data),
                        )
                    }
                    items.data.forEach { mark ->
                        val image = mark.image ?: return@forEach
                        if (store.current.marks.images.containsKey(mark.id)) return@forEach
                        store.runTask {
                            val bytes = (store.transfer.fetch(image) as? ZillitResult.Success)?.data ?: return@runTask
                            store.update { copy(marks = marks.copy(images = marks.images + (mark.id to bytes))) }
                        }
                    }
                }
            }
        }
    }

    fun open() = store.update { copy(marks = marks.copy(open = true, pad = PadState(mode = PadMode.Draw))) }
    fun close() = store.update { copy(marks = marks.copy(open = false, confirmDeleteId = null)) }

    fun editPad(transform: (PadState) -> PadState) = store.update {
        copy(marks = marks.copy(pad = transform(marks.pad)))
    }

    @Suppress("CyclomaticComplexMethod") // Draw, type or upload, then store.
    fun save() {
        val marks = store.current.marks
        val pad = marks.pad
        if (!pad.canApply) {
            store.failed(
                when (pad.mode) {
                    PadMode.Draw -> "Draw something first."
                    PadMode.Type -> "Type your name first."
                    else -> "Choose an image first."
                },
            )
            return
        }
        editPad { it.copy(busy = true) }
        store.runTask {
            val png = when (pad.mode) {
                PadMode.Draw -> store.pdf.rasterizeStrokes(pad.strokes, MARK_RASTER_WIDTH, MARK_RASTER_HEIGHT)
                PadMode.Type -> store.pdf.rasterizeText(
                    pad.typedName.trim(),
                    pad.font.key,
                    MARK_RASTER_WIDTH,
                    MARK_RASTER_HEIGHT,
                )
                else -> pad.uploadBytes?.let { ZillitResult.Success(it) }
                    ?: ZillitResult.Failure(ZillitError.Validation("Choose an image first."))
            }
            val bytes = when (png) {
                is ZillitResult.Failure -> {
                    editPad { it.copy(busy = false) }
                    store.failed(png.error.userMessage)
                    return@runTask
                }
                is ZillitResult.Success -> png.data
            }
            val ext = if (pad.mode == PadMode.Upload && pad.uploadName.endsWith(".jpg", true)) "jpg" else "png"
            val contentType = if (ext == "jpg") "image/jpeg" else "image/png"
            val stored = store.orFail { store.transfer.store("${store.newId()}.$ext", contentType, bytes) }
                ?: run { editPad { it.copy(busy = false) }; return@runTask }
            val saved = store.repository.saveSignature(
                marks.forSignature,
                stored.copy(contentType = "image", contentSubtype = ext),
            )
            when (saved) {
                is ZillitResult.Failure -> {
                    editPad { it.copy(busy = false) }
                    store.failed("The mark could not be saved.")
                }
                is ZillitResult.Success -> {
                    store.update { copy(marks = marks.copy(pad = PadState(mode = pad.mode))) }
                    store.notice(if (marks.forSignature) "Signature saved." else "Initials saved.")
                    load()
                }
            }
        }
    }

    fun imagePicked(name: String, bytes: ByteArray) {
        if (bytes.size > SigningFlow.MAX_UPLOAD_BYTES) {
            store.failed("Images must be under 8 MB.")
            return
        }
        editPad { it.copy(mode = PadMode.Upload, uploadBytes = bytes, uploadName = name) }
    }

    fun confirmDelete() {
        val id = store.current.marks.confirmDeleteId ?: return
        store.update { copy(marks = marks.copy(confirmDeleteId = null)) }
        store.runTask {
            store.orFail { store.repository.deleteSavedSignature(id) } ?: return@runTask
            load()
        }
    }
}
