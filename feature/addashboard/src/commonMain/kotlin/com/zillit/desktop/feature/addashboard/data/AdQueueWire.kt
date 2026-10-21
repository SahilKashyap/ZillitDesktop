package com.zillit.desktop.feature.addashboard.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.feature.addashboard.domain.AgencyPurchaseOrder
import com.zillit.desktop.feature.addashboard.domain.ArtisteCategory
import com.zillit.desktop.feature.addashboard.domain.ArtisteQuery
import com.zillit.desktop.feature.addashboard.domain.ImportPreview
import com.zillit.desktop.feature.addashboard.domain.ImportRow
import com.zillit.desktop.feature.addashboard.domain.PayrollQueueRun
import com.zillit.desktop.feature.addashboard.domain.PendingPayrollItem
import com.zillit.desktop.feature.addashboard.domain.PoQueue
import com.zillit.desktop.feature.addashboard.domain.QueryDay
import com.zillit.desktop.feature.addashboard.domain.QueryMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Queries, the PO / Payroll queue and an agency list waiting to be committed. */

@Serializable
internal data class QueryMessageDto(
    @Serializable(with = LooseString::class)
    @SerialName("_id") val id: String? = null,
    @SerialName("text") val text: String? = null,
    @SerialName("message") val message: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("who_name") val whoName: String? = null,
    @SerialName("who_type") val whoType: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("who_id") val whoId: String? = null,
) {
    fun toDomain(): QueryMessage = QueryMessage(
        id = id.orEmpty(),
        text = text ?: message.orEmpty(),
        at = createdAt?.toLong(),
        authorName = whoName.orEmpty(),
        whoType = whoType.orEmpty(),
        whoId = whoId.orEmpty(),
    )
}

@Serializable
internal data class QueryArtisteDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("_id") val underscoreId: String? = null,
    @SerialName("name") val name: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("ref") val ref: String? = null,
)

@Serializable
internal data class QueryDayDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("_id") val underscoreId: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("shoot_date") val shootDate: Double? = null,
) {
    fun toDomain(): QueryDay = QueryDay(
        id = id ?: underscoreId.orEmpty(),
        shootDate = shootDate?.toLong(),
    )
}

@Serializable
internal data class QueryDto(
    @Serializable(with = LooseString::class)
    @SerialName("_id") val underscoreId: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @SerialName("topic") val topic: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("subject") val subject: String? = null,
    @SerialName("priority") val priority: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("artiste") val artiste: QueryArtisteDto? = null,
    @SerialName("day") val day: QueryDayDto? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("updated_at") val updatedAt: Double? = null,
    @SerialName("last_message") val lastMessage: QueryMessageDto? = null,
    @SerialName("messages") val messages: List<QueryMessageDto>? = null,
) {
    fun toDomain(): ArtisteQuery? {
        val resolved = underscoreId ?: id ?: return null
        val thread = messages?.map { it.toDomain() }.orEmpty()
        return ArtisteQuery(
            id = resolved,
            topic = topic.orEmpty(),
            title = title ?: subject.orEmpty(),
            priority = priority.orEmpty(),
            status = status.orEmpty(),
            artisteId = artiste?.id ?: artiste?.underscoreId.orEmpty(),
            artisteName = artiste?.name.orEmpty(),
            artisteRef = artiste?.ref.orEmpty(),
            day = day?.toDomain(),
            updated = updatedAt?.toLong(),
            // The slim list carries the last message only; the thread arrives
            // when the query is opened.
            lastMessage = lastMessage?.toDomain()?.text ?: thread.lastOrNull()?.text.orEmpty(),
            messages = thread,
        )
    }
}

@Serializable
internal data class AgencyPoDto(
    @Serializable(with = LooseString::class)
    @SerialName("po_id") val poId: String? = null,
    @SerialName("po_reference") val poReference: String? = null,
    @SerialName("agency") val agency: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("amount") val amount: Double? = null,
    @SerialName("status") val status: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("shoot_date") val shootDate: Double? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("day_number") val dayNumber: Int? = null,
    @SerialName("approved_by") val approvedBy: String? = null,
) {
    fun toDomain(): AgencyPurchaseOrder = AgencyPurchaseOrder(
        poId = poId.orEmpty(),
        poReference = poReference.orEmpty(),
        agency = agency.orEmpty(),
        currency = currency.orEmpty(),
        amount = amount ?: 0.0,
        status = status.orEmpty(),
        shootDate = shootDate?.toLong(),
        dayNumber = dayNumber,
        approvedBy = approvedBy.orEmpty(),
    )
}

@Serializable
internal data class PayrollRunDto(
    @SerialName("label") val label: String? = null,
    @SerialName("period") val period: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("amount") val amount: Double? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("status") val status: String? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("artiste_count") val artisteCount: Int? = null,
    @SerialName("bacs_date") val bacsDate: String? = null,
) {
    fun toDomain(): PayrollQueueRun = PayrollQueueRun(
        label = label.orEmpty(),
        period = period.orEmpty(),
        amount = amount ?: 0.0,
        currency = currency.orEmpty(),
        status = status.orEmpty(),
        artisteCount = artisteCount,
        bacsDate = bacsDate.orEmpty(),
    )
}

@Serializable
internal data class PendingPayrollDto(
    @Serializable(with = LooseString::class)
    @SerialName("artiste_id") val artisteId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("status") val status: String? = null,
) {
    fun toDomain(): PendingPayrollItem = PendingPayrollItem(
        artisteId = artisteId.orEmpty(),
        name = name.orEmpty(),
        reason = reason.orEmpty(),
        status = status.orEmpty(),
    )
}

/**
 * The queue is an **object of lists**.
 *
 * Read as an array it would come back empty with no error: a list reader
 * rewrites a non-array body to `[]` and every nested list disappears.
 */
@Serializable
internal data class PoQueueDto(
    @SerialName("agency_pos") val agencyPos: List<AgencyPoDto>? = null,
    @SerialName("payroll") val payroll: List<PayrollRunDto>? = null,
    @SerialName("pending") val pending: List<PendingPayrollDto>? = null,
) {
    fun toDomain(): PoQueue = PoQueue(
        agencyPos = agencyPos?.map { it.toDomain() }.orEmpty(),
        payroll = payroll?.map { it.toDomain() }.orEmpty(),
        pending = pending?.map { it.toDomain() }.orEmpty(),
    )
}

@Serializable
internal data class ImportRowDto(
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("email") val email: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("phone") val phone: String? = null,
    @SerialName("agency_name") val agencyName: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("agency_id") val agencyId: String? = null,
    @SerialName("date_of_birth") val dateOfBirth: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("category") val category: String? = null,
) {
    fun toDomain(index: Int): ImportRow = ImportRow(
        index = index,
        firstName = firstName.orEmpty(),
        lastName = lastName.orEmpty(),
        email = email.orEmpty(),
        phone = phone.orEmpty(),
        agencyName = agencyName.orEmpty(),
        agencyId = agencyId.orEmpty(),
        dateOfBirth = dateOfBirth.orEmpty(),
        address = address.orEmpty(),
        category = ArtisteCategory.from(category),
    )
}

@Serializable
internal data class ImportSummaryDto(
    @Serializable(with = LooseInt::class)
    @SerialName("total") val total: Int? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("matched_agencies") val matchedAgencies: Int? = null,
)

@Serializable
internal data class ImportPreviewDto(
    @SerialName("columns") val columns: List<String>? = null,
    @SerialName("rows") val rows: List<ImportRowDto>? = null,
    @SerialName("summary") val summary: ImportSummaryDto? = null,
) {
    fun toDomain(): ImportPreview {
        val parsed = rows.orEmpty().mapIndexed { index, row -> row.toDomain(index) }
        return ImportPreview(
            columns = columns.orEmpty(),
            rows = parsed,
            totalRows = summary?.total ?: parsed.size,
            matchedAgencies = summary?.matchedAgencies ?: parsed.count { it.agencyMatched },
        )
    }
}

/** What a commit answers: how many rows landed. */
@Serializable
internal data class ImportCommitDto(
    @Serializable(with = LooseInt::class)
    @SerialName("created") val created: Int? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("count") val count: Int? = null,
) {
    val resolved: Int get() = created ?: count ?: 0
}

/** `POST /artistes/:id/portal-link` answers the token, nothing else. */
@Serializable
internal data class PortalTokenDto(
    @Serializable(with = LooseString::class)
    @SerialName("token") val token: String? = null,
)

/** A created artiste's id, which the quick-add path needs back. */
@Serializable
internal data class CreatedIdDto(
    @Serializable(with = LooseString::class)
    @SerialName("_id") val underscoreId: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
) {
    val resolved: String get() = underscoreId ?: id.orEmpty()
}

/** A created purchase order, as the PO service answers it. */
@Serializable
internal data class CreatedPoDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("_id") val underscoreId: String? = null,
) {
    val resolved: String get() = id ?: underscoreId.orEmpty()
}

/** One of the AD's saved signatures, from the e-signature service. */
@Serializable
internal data class SavedSignatureDto(
    @Serializable(with = LooseString::class)
    @SerialName("_id") val underscoreId: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("signature_image") val signatureImage: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("image_url") val imageUrl: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("created_at") val createdAt: Double? = null,
)

/** The approval tiers a raised purchase order seeds its approvals from. */
@Serializable
internal data class ApprovalTierDto(
    @Serializable(with = LooseString::class)
    @SerialName("_id") val id: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("department_id") val departmentId: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("threshold") val threshold: Double? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("min_amount") val minAmount: Double? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("max_amount") val maxAmount: Double? = null,
    @SerialName("approvers") val approvers: List<ApproverDto>? = null,
)

@Serializable
internal data class ApproverDto(
    @Serializable(with = LooseString::class)
    @SerialName("user_id") val userId: String? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("level") val level: Int? = null,
    @SerialName("role") val role: String? = null,
)
