package com.zillit.desktop.feature.talentdeals

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.talentdeals.domain.PickedTalentFile
import com.zillit.desktop.feature.talentdeals.domain.SigningDay
import com.zillit.desktop.feature.talentdeals.domain.StoredSchedule
import com.zillit.desktop.feature.talentdeals.domain.StoredTranche
import com.zillit.desktop.feature.talentdeals.domain.StoredWithholding
import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.domain.TalentContract
import com.zillit.desktop.feature.talentdeals.domain.TalentDealsRepository
import com.zillit.desktop.feature.talentdeals.domain.TalentDealsViewer
import com.zillit.desktop.feature.talentdeals.domain.TalentRefresh
import com.zillit.desktop.feature.talentdeals.domain.TalentSaved
import com.zillit.desktop.feature.talentdeals.domain.TalentUnion
import com.zillit.desktop.feature.talentdeals.ui.TalentFileHost
import com.zillit.desktop.feature.talentdeals.ui.TalentPdfPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonObject

/** An in-memory talent-contract service that records what it is asked. */
class FakeTalentRepository : TalentDealsRepository {
    val socket = MutableSharedFlow<TalentRefresh>(extraBufferCapacity = 8)
    override val refreshes: Flow<TalentRefresh> = socket

    var rows: MutableList<TalentContract> = mutableListOf()
    var listError: ZillitError? = null
    var detailError: ZillitError? = null
    var saveError: ZillitError? = null
    var unionsError: ZillitError? = null
    var unionRows: List<TalentUnion> = emptyList()
    var exhibitDays: List<SigningDay> = emptyList()
    var exhibitError: ZillitError? = null

    var listCalls = 0
    var detailCalls = mutableListOf<String>()
    val creates = mutableListOf<JsonObject>()
    val updates = mutableListOf<Pair<String, JsonObject>>()
    val removes = mutableListOf<String>()
    val unionAsks = mutableListOf<Pair<String, String>>()
    val exhibitAsks = mutableListOf<String>()

    override suspend fun contracts(): ZillitResult<List<TalentContract>> {
        listCalls++
        return listError?.let { ZillitResult.Failure(it) } ?: ZillitResult.Success(rows.toList())
    }

    override suspend fun contract(id: String): ZillitResult<TalentContract> {
        detailCalls += id
        detailError?.let { return ZillitResult.Failure(it) }
        return rows.firstOrNull { it.id == id }?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Validation("Contract not found."))
    }

    override suspend fun create(body: JsonObject): ZillitResult<TalentSaved> {
        saveError?.let { return ZillitResult.Failure(it) }
        creates += body
        return ZillitResult.Success(TalentSaved(id = "new-1", message = null))
    }

    override suspend fun update(id: String, body: JsonObject): ZillitResult<TalentSaved> {
        saveError?.let { return ZillitResult.Failure(it) }
        updates += id to body
        return ZillitResult.Success(TalentSaved(id = id, message = null))
    }

    override suspend fun remove(id: String): ZillitResult<String?> {
        removes += id
        rows.removeAll { it.id == id }
        return ZillitResult.Success(null)
    }

    override suspend fun unions(crewType: String, territory: String): ZillitResult<List<TalentUnion>> {
        unionAsks += crewType to territory
        return unionsError?.let { ZillitResult.Failure(it) } ?: ZillitResult.Success(unionRows)
    }

    override suspend fun exhibitG(name: String): ZillitResult<List<SigningDay>> {
        exhibitAsks += name
        return exhibitError?.let { ZillitResult.Failure(it) } ?: ZillitResult.Success(exhibitDays)
    }
}

/** A file layer that picks what it is told to and records what it uploads. */
class FakeTalentFiles : TalentFileHost {
    val picks = ArrayDeque<PickedTalentFile?>()
    val uploads = mutableListOf<PickedTalentFile>()
    var uploadError: ZillitError? = null
    val fetched = mutableListOf<TalentAttachment>()
    var bytes: ByteArray = byteArrayOf(1, 2, 3)
    var pages: List<TalentPdfPage> = listOf(TalentPdfPage(1, byteArrayOf(9), 10, 10))
    val saved = mutableListOf<String>()
    var pickedExtensions: List<String> = emptyList()

    override suspend fun pick(extensions: List<String>): PickedTalentFile? {
        pickedExtensions = extensions
        return picks.removeFirstOrNull()
    }

    override suspend fun upload(file: PickedTalentFile): ZillitResult<TalentAttachment> {
        uploads += file
        uploadError?.let { return ZillitResult.Failure(it) }
        return ZillitResult.Success(
            TalentAttachment(
                media = "store/${uploads.size}/${file.name}",
                bucket = "bkt",
                region = "reg",
                name = file.name,
                contentType = "document",
                contentSubtype = file.name.substringAfterLast('.').lowercase(),
            ),
        )
    }

    override suspend fun fetch(attachment: TalentAttachment): ZillitResult<ByteArray> {
        fetched += attachment
        return ZillitResult.Success(bytes)
    }

    override fun renderPages(pdf: ByteArray,
        targetWidthPx: Int): ZillitResult<List<TalentPdfPage>> = ZillitResult.Success(pages)

    override suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit> {
        saved += fileName
        return ZillitResult.Success(Unit)
    }
}

object Talent {
    val poster = TalentDealsViewer(userId = "me", hasPostingAccess = true, hasViewAccess = true, rightsLoaded = true)
    val reader = TalentDealsViewer(userId = "me", hasViewAccess = true, rightsLoaded = true)
    val reader2 = reader.copy(hasDownloadAccess = true)
    val nobody = TalentDealsViewer(userId = "me", rightsLoaded = true)

    fun contract(
        id: String = "c1",
        person: String = "Jane Doe",
        role: String = "Cast",
        union: String = "sag-aftra",
        fee: Double = 100000.0,
        weekly: Boolean = false,
    ) = TalentContract(
        id = id,
        reference = "TC-${id.uppercase()}",
        person = person,
        role = role,
        status = "issued",
        longFormContract = TalentAttachment(media = "store/$id.pdf", bucket = "b", region = "r",
            name = "Agreement-$id.pdf", contentSubtype = "pdf"),
        territoryCode = "us",
        unionIdentifier = union,
        fee = fee,
        currency = "USD",
        paymentMode = "escrow",
        feeBasis = "salary",
        withholding = StoredWithholding(),
        schedule = if (weekly) {
            StoredSchedule(cadence = "weekly", tranches = listOf(StoredTranche("Shoot", 1767571200000L, 1768089600000L,
                1.0, 100.0)))
        } else {
            StoredSchedule()
        },
    )
}
