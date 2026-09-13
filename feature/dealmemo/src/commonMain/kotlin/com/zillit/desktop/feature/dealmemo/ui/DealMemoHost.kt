package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAttachment
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCompany
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCountry
import com.zillit.desktop.feature.dealmemo.domain.preview.DealUnit

/**
 * The production's name and legal company, and its `project_sub_type` — the
 * production type a deal is written for (`scripted-tv`, `feature_label`…).
 */
data class DealProjectInfo(val projectName: String = "", val companyName: String = "", val productionType: String = "")

/** A row of the chart of accounts, as the nominal pickers offer it. */
data class DealCoaAccount(
    val code: String,
    val name: String,
    /** `header`, `section`, `category`, `sub_category` — or null on an untyped chart. */
    val lineType: String? = null,
    /** Opt-out: only an explicit `false` hides a code. */
    val posting: Boolean = true,
)

/**
 * What the deal pages read from the rest of the production — the host owns
 * these because other tools own the data.
 */
interface DealProductionData {

    fun project(): DealProjectInfo

    /** The web app's origin — a crew member's share link opens there. */
    fun webOrigin(): String?

    suspend fun companies(): List<DealCompany>

    /** The units crew can join; their names are translation keys. */
    suspend fun units(): List<DealUnit>

    /** The ISD list — dial codes and names by ISO code. */
    suspend fun countries(): List<DealCountry>

    suspend fun chartOfAccounts(): ZillitResult<List<DealCoaAccount>>

    companion object {
        val None: DealProductionData = object : DealProductionData {
            override fun project() = DealProjectInfo()
            override fun webOrigin(): String? = null
            override suspend fun companies() = emptyList<DealCompany>()
            override suspend fun units() = emptyList<DealUnit>()
            override suspend fun countries() = emptyList<DealCountry>()
            override suspend fun chartOfAccounts(): ZillitResult<List<DealCoaAccount>> =
                ZillitResult.Success(emptyList())
        }
    }
}

/** A signature kept in the E-Signature library. */
data class DealSavedSignature(val id: String, val image: DealAttachment?)

/**
 * The file work behind the deal page: stored attachments in, signed copies
 * out, and the signer's saved signatures — S3 and the E-Signature library,
 * which the host owns.
 */
interface DealDocumentStore {

    /** A stored attachment's bytes, through a presigned read. */
    suspend fun fetch(attachment: DealAttachment): ZillitResult<ByteArray>

    /**
     * Uploads a file the page produced — a signed PDF, a signature, a passport
     * scan — and answers its attachment: `{media, bucket, region, name}`.
     */
    suspend fun upload(fileName: String, contentType: String, bytes: ByteArray): ZillitResult<DealAttachment>

    suspend fun savedSignatures(): ZillitResult<List<DealSavedSignature>>

    /** Uploads [png] and adds it to the library. */
    suspend fun saveSignature(png: ByteArray): ZillitResult<Unit>

    suspend fun deleteSignature(id: String): ZillitResult<Unit>

    /** The OS file chooser, limited to [extensions]; empty when cancelled. */
    suspend fun pickFiles(extensions: List<String>, multiple: Boolean): List<PickedDealFile>
}

/** A file the user picked from disk. */
class PickedDealFile(val name: String, val bytes: ByteArray, val mime: String)
