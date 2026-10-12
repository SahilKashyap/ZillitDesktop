package com.zillit.desktop.feature.talentdeals.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.talentdeals.domain.PickedTalentFile
import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.domain.TalentCountry
import com.zillit.desktop.feature.talentdeals.domain.TalentCurrencies

/** One rendered page of a PDF, as PNG bytes. */
class TalentPdfPage(val page: Int, val imageBytes: ByteArray, val widthPx: Int, val heightPx: Int)

/**
 * What the tool needs from this machine and the production's file store — the
 * file chooser, the upload, a signed read of a stored file, the PDF renderer
 * and a place to save to. A host seam, as Deal Memo's `DealDocumentStore` is:
 * the module has no file layer of its own.
 */
interface TalentFileHost {

    /** The OS chooser limited to [extensions]; null when cancelled or unreadable. */
    suspend fun pick(extensions: List<String>): PickedTalentFile?

    /** Puts a picked file in the production's store and answers the envelope the contract carries. */
    suspend fun upload(file: PickedTalentFile): ZillitResult<TalentAttachment>

    /** The stored file's bytes, through the signed read the production's store requires. */
    suspend fun fetch(attachment: TalentAttachment): ZillitResult<ByteArray>

    fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<TalentPdfPage>>

    /** Saves where the person will find it, and opens it. */
    suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit>

    companion object {
        /** A host with no file layer: nothing can be picked, and every file call says so. */
        val Unavailable: TalentFileHost = object : TalentFileHost {
            private val none = ZillitResult.Failure(ZillitError.Unknown("Files are not available here."))

            override suspend fun pick(extensions: List<String>): PickedTalentFile? = null

            override suspend fun upload(file: PickedTalentFile): ZillitResult<TalentAttachment> = none

            override suspend fun fetch(attachment: TalentAttachment): ZillitResult<ByteArray> = none

            override fun renderPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<TalentPdfPage>> = none

            override suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit> = none
        }
    }
}

/**
 * The production's reference data the form's pickers read — owned by Account
 * Hub's setup, so fetched by the host. Each answers the empty value on a failed
 * read: the territory list is then the wizard's 41, and the currency picker is
 * empty rather than the tool being down.
 */
interface TalentReference {

    /** The Add Company country list — the territories after the wizard's own. */
    suspend fun countries(): List<TalentCountry>

    /** Production Setup's Project Currencies and default, and the whole catalogue. */
    suspend fun currencies(): TalentCurrencies

    companion object {
        val Empty: TalentReference = object : TalentReference {
            override suspend fun countries(): List<TalentCountry> = emptyList()

            override suspend fun currencies(): TalentCurrencies = TalentCurrencies()
        }
    }
}
