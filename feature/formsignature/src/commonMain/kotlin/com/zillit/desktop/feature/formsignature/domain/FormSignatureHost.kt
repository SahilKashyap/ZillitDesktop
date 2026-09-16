package com.zillit.desktop.feature.formsignature.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult

/**
 * What the host app lends this tool beyond the documents service: the
 * crew list for names and designations, the external-users directory
 * for outside signers, and the machine — Downloads, the printer, the
 * browser, and the media service's Word-to-PDF conversion.
 *
 * Every member has a harmless default so the view model can be built in
 * a test with none of them.
 */
interface FormSignatureHost {

    /** The external-users directory — the web's `useExternalUsers()`. */
    suspend fun externalSigners(): List<ExternalSigner> = emptyList()

    /** A crew member's name/designation/standing, from the production context. */
    fun crew(userId: String): CrewPerson? = null

    /** A file into Downloads, then opened — the web's `saveAs`. */
    suspend fun saveToDownloads(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("Downloads are not wired"))

    /** The OS print flow for a PDF — the web's `handleDocPrint`. */
    suspend fun print(fileName: String, bytes: ByteArray): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("Printing is not wired"))

    /** `POST file-processing/convert-to/pdf` on the media service. */
    suspend fun convertToPdf(fileName: String, bytes: ByteArray): ZillitResult<ByteArray> =
        ZillitResult.Failure(ZillitError.Unknown("Conversion is not wired"))

    /** The admin guide link on the tile hub. */
    fun openGuide() {}

    companion object {
        val None: FormSignatureHost = object : FormSignatureHost {}
        const val GUIDE_URL = "https://documentation.zillit.com/?for=admin#contracts-and-signature"
    }
}

data class CrewPerson(
    val fullName: String = "",
    val designation: String = "",
    val status: String = "",
)
