package com.zillit.desktop.feature.sides.ui.flows

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.ui.SidesEffect
import com.zillit.desktop.feature.sides.ui.SidesPdfView
import com.zillit.desktop.feature.sides.ui.SidesStore

/**
 * The in-app preview and the save-to-disk download — the web's
 * `PdfViewerModal` and `downloadFile`.
 *
 * View is open to every viewer: the signed URL is fetched and rendered
 * in-app, never handed to the browser. Only leaving the app with the file
 * — Open externally, Download — is gated on the download right, and a
 * refused press asks an admin instead of dead-ending.
 */
internal class PdfFlow(private val store: SidesStore) {

    /**
     * Opens the viewer over [url]'s document. A non-PDF (a Final Draft
     * `.fdx`) is detected up front and shown as such rather than as a
     * render failure.
     */
    fun open(
        title: String,
        subtitle: String,
        fileName: String,
        info: List<Pair<String, String>> = emptyList(),
        url: suspend () -> ZillitResult<String>,
    ) {
        store.update { copy(pdf = SidesPdfView(title = title, subtitle = subtitle, info = info, fileName = fileName)) }
        store.runTask {
            val signed = when (val answer = url()) {
                is ZillitResult.Success -> answer.data
                is ZillitResult.Failure -> {
                    store.failed(answer.error)
                    return@runTask fail("Failed to load PDF")
                }
            }
            if (signed.isBlank()) return@runTask fail("No file URL returned")
            store.update { copy(pdf = pdf?.copy(url = signed)) }
            val bytes = when (val fetched = store.transfer.fetch(signed)) {
                is ZillitResult.Success -> fetched.data
                is ZillitResult.Failure -> return@runTask fail("Preview is unavailable for this file.")
            }
            if (!SidesRules.isPdfBytes(bytes)) {
                store.update { copy(pdf = pdf?.copy(loading = false, notPdf = true, bytes = bytes)) }
                return@runTask
            }
            when (val pages = store.transfer.renderPages(bytes, RENDER_WIDTH_PX)) {
                is ZillitResult.Success -> store.update {
                    copy(pdf = pdf?.copy(loading = false, pages = pages.data, bytes = bytes))
                }
                is ZillitResult.Failure -> fail("Failed to render PDF")
            }
        }
    }

    private fun fail(message: String) {
        store.update { copy(pdf = pdf?.copy(loading = false, error = message)) }
    }

    fun close() = store.update { copy(pdf = null) }

    /** "Open in new tab": the signed URL in the OS browser. */
    fun openExternal() {
        val view = store.current.pdf ?: return
        if (store.refuses(RightsKind.Download)) return
        if (view.url.isNotBlank()) store.effect(SidesEffect.OpenUrl(view.url))
    }

    /** The viewer's own Download, for the non-PDF state. */
    fun downloadShown() {
        val view = store.current.pdf ?: return
        if (store.refuses(RightsKind.Download)) return
        val bytes = view.bytes
        if (bytes != null) {
            store.effect(SidesEffect.SaveFile(view.fileName, bytes))
        } else if (view.url.isNotBlank()) {
            save(view.fileName) { ZillitResult.Success(view.url) }
        }
    }

    /**
     * The forced save-to-disk download: fetch the bytes and offer a save
     * dialog, falling back to the browser if the fetch is blocked. Callers
     * gate the right; this only moves the file.
     */
    fun save(fileName: String, url: suspend () -> ZillitResult<String>) {
        store.runTask {
            val signed = when (val answer = url()) {
                is ZillitResult.Success -> answer.data
                is ZillitResult.Failure -> return@runTask store.failed(answer.error)
            }
            if (signed.isBlank()) return@runTask store.failed("Download failed")
            when (val fetched = store.transfer.fetch(signed)) {
                is ZillitResult.Success -> store.effect(SidesEffect.SaveFile(fileName, fetched.data))
                is ZillitResult.Failure -> store.effect(SidesEffect.OpenUrl(signed))
            }
        }
    }

    private companion object {
        const val RENDER_WIDTH_PX = 1100
    }
}
