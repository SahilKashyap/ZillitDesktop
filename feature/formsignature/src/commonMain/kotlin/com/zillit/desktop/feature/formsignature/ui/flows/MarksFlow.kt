package com.zillit.desktop.feature.formsignature.ui.flows

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.formsignature.domain.FormSignatureRepository
import com.zillit.desktop.feature.formsignature.domain.PdfWork
import com.zillit.desktop.feature.formsignature.domain.SignFileTransfer
import com.zillit.desktop.feature.formsignature.domain.SignSpotKind
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.domain.StrokePoint
import com.zillit.desktop.feature.formsignature.domain.UploadPurpose
import com.zillit.desktop.feature.formsignature.ui.DrawState
import com.zillit.desktop.feature.formsignature.ui.FormSignScreen
import com.zillit.desktop.feature.formsignature.ui.FormSignStore
import com.zillit.desktop.feature.formsignature.ui.orFail

/**
 * The saved marks — the signature block gallery, the drawing pad, and the
 * images the other flows stamp.
 *
 * The web keeps exactly one signature and one initials per person: the Add
 * buttons hide once a kind exists, and an edit is a redraw saved with the
 * block's id. Mirrored here.
 */
internal class MarksFlow(
    private val store: FormSignStore,
    private val repository: FormSignatureRepository,
    private val transfer: SignFileTransfer,
    private val pdfWork: PdfWork,
    private val newId: () -> String,
) {

    fun load() {
        store.update { copy(signatures = signatures.copy(loading = true)) }
        store.launch {
            when (val blocks = repository.signatures()) {
                is ZillitResult.Failure -> store.update { copy(signatures = signatures.copy(loading = false)) }
                is ZillitResult.Success -> {
                    store.update { copy(signatures = signatures.copy(blocks = blocks.data, loading = false)) }
                    fetchImages(blocks.data)
                }
            }
        }
    }

    private fun fetchImages(blocks: List<SignatureBlock>) {
        blocks.forEach { block ->
            val image = block.image ?: return@forEach
            if (store.state.signatures.images.containsKey(block.id)) return@forEach
            store.launch {
                val bytes = (transfer.fetch(image) as? ZillitResult.Success)?.data ?: return@launch
                store.update {
                    copy(signatures = signatures.copy(images = signatures.images + (block.id to bytes)))
                }
            }
        }
    }

    /** The PNG of one saved block — from the preview cache, else fetched. */
    suspend fun imageOf(block: SignatureBlock): ZillitResult<ByteArray> {
        store.state.signatures.images[block.id]?.let { return ZillitResult.Success(it) }
        val stored = block.image
            ?: return ZillitResult.Failure(ZillitError.Validation("That ${block.kind.label.lowercase()} has no image."))
        return transfer.fetch(stored)
    }

    /** The saved block for [kind], or a message telling the user to make one. */
    suspend fun imageFor(kind: SignSpotKind): ZillitResult<ByteArray> {
        val block = store.state.signatures.of(kind)
            ?: return ZillitResult.Failure(
                ZillitError.Validation("Set up your ${kind.label.lowercase()} in the signature block first."),
            )
        return imageOf(block)
    }

    // ------------------------------------------------------------------ pad

    fun start(isSignature: Boolean, existingId: String?, asPage: Boolean) {
        val existing = existingId?.let { id -> store.state.signatures.blocks.firstOrNull { it.id == id } }
        store.update {
            copy(
                draw = DrawState(
                    isSignature = isSignature,
                    existingId = existingId,
                    name = existing?.name.orEmpty(),
                    asPage = asPage,
                ),
                screen = if (asPage) FormSignScreen.DrawSignature else screen,
            )
        }
    }

    fun addStroke(stroke: List<StrokePoint>) = store.update {
        copy(draw = draw?.copy(strokes = draw.strokes + listOf(stroke)))
    }

    fun clear() = store.update { copy(draw = draw?.copy(strokes = emptyList())) }

    fun cancel() {
        val draw = store.state.draw ?: return
        store.update {
            copy(
                draw = null,
                screen = if (draw.asPage && screen == FormSignScreen.DrawSignature) {
                    FormSignScreen.SignatureBlock
                } else {
                    screen
                },
            )
        }
    }

    /**
     * Save: rasterise, upload under the project's `sign/` prefix, post the
     * block. The web refuses a blank name and an empty pad in that order.
     */
    fun submit() {
        val draw = store.state.draw ?: return
        if (draw.name.isBlank()) {
            store.fail(if (draw.isSignature) "Signature Name is required." else "Initials name is required")
            return
        }
        if (!draw.hasInk) {
            store.fail(
                if (draw.isSignature) "Please draw your signature first." else "Please draw your initials first.",
            )
            return
        }
        store.update { copy(draw = draw.copy(saving = true)) }
        store.launch {
            val png = store.orFail(pdfWork.rasterizeStrokes(draw.strokes, DRAW_WIDTH, DRAW_HEIGHT))
                ?: return@launch unsave()
            val stored = store.orFail(
                transfer.store(UploadPurpose.SignatureImage, "${newId()}_painting.png", "image/png", png),
            ) ?: return@launch unsave()
            val saved = repository.saveSignature(
                image = stored,
                name = draw.name.trim(),
                isSignature = draw.isSignature,
                existingId = draw.existingId,
            )
            when (saved) {
                is ZillitResult.Failure -> {
                    unsave()
                    store.fail(saved.error)
                }
                is ZillitResult.Success -> {
                    // The replaced block's preview is stale; drop it so the gallery refetches.
                    store.update {
                        copy(
                            draw = null,
                            signatures = signatures.copy(images = signatures.images - draw.existingId.orEmpty()),
                            screen = if (draw.asPage) FormSignScreen.SignatureBlock else screen,
                        )
                    }
                    store.notice(if (draw.isSignature) "Signature saved." else "Initials saved.")
                    load()
                }
            }
        }
    }

    private fun unsave() = store.update { copy(draw = draw?.copy(saving = false)) }

    fun delete(blockId: String) {
        store.launch {
            when (val gone = repository.deleteSignature(blockId)) {
                is ZillitResult.Failure -> store.fail(gone.error)
                is ZillitResult.Success -> {
                    store.update {
                        copy(
                            signatures = signatures.copy(
                                blocks = signatures.blocks.filterNot { it.id == blockId },
                                images = signatures.images - blockId,
                            ),
                        )
                    }
                    store.notice("Deleted.")
                }
            }
        }
    }

    private companion object {
        // The drawing canvas, matching the web's 800×300 pad.
        const val DRAW_WIDTH = 800
        const val DRAW_HEIGHT = 300
    }
}
