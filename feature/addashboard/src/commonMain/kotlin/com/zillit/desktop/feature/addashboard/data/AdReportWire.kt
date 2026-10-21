package com.zillit.desktop.feature.addashboard.data

import com.zillit.desktop.feature.addashboard.domain.AdReport
import com.zillit.desktop.feature.addashboard.domain.ReportBlockType
import com.zillit.desktop.feature.addashboard.domain.ReportCell
import com.zillit.desktop.feature.addashboard.domain.ReportColumn
import com.zillit.desktop.feature.addashboard.domain.ReportFills
import com.zillit.desktop.feature.addashboard.domain.ReportKind
import com.zillit.desktop.feature.addashboard.domain.ReportPageRow
import com.zillit.desktop.feature.addashboard.domain.ReportRow
import com.zillit.desktop.feature.addashboard.domain.ReportSignature
import com.zillit.desktop.feature.addashboard.domain.ReportStatus
import com.zillit.desktop.feature.addashboard.domain.ReportSummary
import com.zillit.desktop.feature.addashboard.domain.ReportTemplate
import com.zillit.desktop.feature.addashboard.domain.ReportValueType
import com.zillit.desktop.feature.addashboard.domain.SigningRow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The report service's shapes: a layout of blocks, the values filled into it,
 * and — for Exhibit G — who has signed.
 *
 * A report's values travel as a flat `fills` array of `{ cell_id, row_id, col,
 * value }`, which the editor holds as a map keyed `cellId.rowId.col`
 * ([ReportFills]). The named header fields are **not** in there: they live on
 * `meta.header`, because Picture Title and Company are constant for the whole
 * shoot and as free rows they were retyped every day.
 */

@Serializable
internal data class ReportColumnDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("type") val type: String? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("w") val width: Int? = null,
) {
    fun toDomain(index: Int): ReportColumn = ReportColumn(
        id = id ?: "c$index",
        label = label.orEmpty(),
        type = ReportValueType.from(type),
        width = width,
    )
}

@Serializable
internal data class ReportRowDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @SerialName("cells") val cells: List<String?>? = null,
    @SerialName("vtype") val valueType: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("hkey") val headerKey: String? = null,
    @SerialName("field") val field: String? = null,
) {
    fun toDomain(index: Int): ReportRow = ReportRow(
        id = id ?: "r$index",
        cells = cells.orEmpty().map { it.orEmpty() },
        valueType = ReportValueType.from(valueType),
        source = source.orEmpty(),
        headerKey = headerKey?.takeIf { it.isNotBlank() },
        field = field?.takeIf { it.isNotBlank() },
    )
}

@Serializable
internal data class ReportCellDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("title") val title: String? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("hideTitle") val hideTitle: Boolean? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("columns") val columns: List<ReportColumnDto>? = null,
    @SerialName("rows") val rows: List<ReportRowDto>? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("frozen") val frozen: Boolean? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("mealGrid") val mealGrid: Boolean? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("wrap") val wrap: Boolean? = null,
    @SerialName("rowOrder") val rowOrder: List<String>? = null,
) {
    fun toDomain(index: Int): ReportCell = ReportCell(
        id = id ?: "cell$index",
        type = ReportBlockType.from(type),
        title = title.orEmpty(),
        hideTitle = hideTitle == true,
        source = source.orEmpty(),
        columns = columns.orEmpty().mapIndexed { position, column -> column.toDomain(position) },
        rows = rows.orEmpty().mapIndexed { position, row -> row.toDomain(position) },
        frozen = frozen == true,
        mealGrid = mealGrid == true,
        wrap = wrap == true,
        rowOrder = rowOrder.orEmpty(),
    )
}

@Serializable
internal data class ReportPageRowDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("break") val isBreak: Boolean? = null,
    @SerialName("cells") val cells: List<ReportCellDto>? = null,
) {
    fun toDomain(index: Int): ReportPageRow = ReportPageRow(
        id = id ?: "pr$index",
        isBreak = isBreak == true,
        cells = cells.orEmpty().mapIndexed { position, cell -> cell.toDomain(position) },
    )
}

@Serializable
internal data class ReportFillDto(
    @Serializable(with = LooseString::class)
    @SerialName("cell_id") val cellId: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("row_id") val rowId: String? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("col") val column: Int? = null,
    @Serializable(with = LooseString::class)
    @SerialName("value") val value: String? = null,
)

@Serializable
internal data class ReportMetaDto(
    @SerialName("header") val header: Map<String, String?>? = null,
)

@Serializable
internal data class ReportSignatureDto(
    @Serializable(with = LooseString::class)
    @SerialName("person_id") val personId: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("cell_id") val cellId: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("row_id") val rowId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("status") val status: String? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("signed") val signed: Boolean? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("signed_at") val signedAt: Double? = null,
    @Serializable(with = LooseString::class)
    @SerialName("signature_file") val signatureFile: String? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("in_person") val inPerson: Boolean? = null,
) {
    fun toDomain(): ReportSignature = ReportSignature(
        personId = personId.orEmpty(),
        cellId = cellId.orEmpty(),
        rowId = rowId.orEmpty(),
        name = name.orEmpty(),
        // A reset leaves the row in place marked void, for the audit trail.
        signed = signed ?: (status == "signed"),
        signedAt = signedAt?.toLong(),
        signatureFile = signatureFile.orEmpty(),
        inPerson = inPerson == true,
    )
}

@Serializable
internal data class SigningRowDto(
    @Serializable(with = LooseString::class)
    @SerialName("cell_id") val cellId: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("row_id") val rowId: String? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("row_index") val rowIndex: Int? = null,
    @Serializable(with = LooseString::class)
    @SerialName("person_id") val personId: String? = null,
    @SerialName("person_type") val personType: String? = null,
    @SerialName("name") val name: String? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("signed") val signed: Boolean? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("signed_at") val signedAt: Double? = null,
) {
    fun toDomain(): SigningRow = SigningRow(
        cellId = cellId.orEmpty(),
        rowId = rowId.orEmpty(),
        rowIndex = rowIndex ?: 0,
        personId = personId.orEmpty(),
        personType = personType.orEmpty(),
        name = name.orEmpty(),
        signed = signed == true,
        signedAt = signedAt?.toLong(),
    )
}

/** The slim list row, which carries no dependable `report_type`. */
@Serializable
internal data class ReportSummaryDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("_id") val underscoreId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("status") val status: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("shoot_date") val shootDate: Double? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("day_number") val dayNumber: Int? = null,
    @SerialName("template_name") val templateName: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("posted_at") val postedAt: Double? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("updated_at") val updatedAt: Double? = null,
) {
    /**
     * [kind] comes from the **endpoint** that answered, not from the row.
     *
     * The AD endpoint also returns untyped legacy rows, so a row's own
     * `report_type` cannot be trusted — and the kind decides which endpoint
     * every later call for this row uses.
     */
    fun toDomain(kind: ReportKind): ReportSummary? {
        val resolved = id ?: underscoreId ?: return null
        return ReportSummary(
            id = resolved,
            kind = kind,
            name = name.orEmpty(),
            status = ReportStatus.from(status),
            shootDate = shootDate?.toLong(),
            dayNumber = dayNumber,
            templateName = templateName.orEmpty(),
            postedAt = postedAt?.toLong(),
            updatedAt = updatedAt?.toLong(),
        )
    }
}

@Serializable
internal data class ReportDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("_id") val underscoreId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("report_type") val reportType: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("shoot_date") val shootDate: Double? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("day_number") val dayNumber: Int? = null,
    @SerialName("pageRows") val pageRowsCamel: List<ReportPageRowDto>? = null,
    @SerialName("page_rows") val pageRows: List<ReportPageRowDto>? = null,
    @SerialName("fills") val fills: List<ReportFillDto>? = null,
    @SerialName("meta") val meta: ReportMetaDto? = null,
    @SerialName("template_name") val templateName: String? = null,
    @SerialName("signatures") val signatures: List<ReportSignatureDto>? = null,
    @SerialName("sign_state") val signState: String? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("sign_round") val signRound: Int? = null,
    @Serializable(with = LooseString::class)
    @SerialName("report_attachment") val reportAttachment: String? = null,
    @Serializable(with = LooseDouble::class)
    @SerialName("posted_at") val postedAt: Double? = null,
    @Serializable(with = LooseBoolean::class)
    @SerialName("existed") val existed: Boolean? = null,
) {
    fun toDomain(kind: ReportKind): AdReport? {
        val resolved = id ?: underscoreId ?: return null
        val layout = (pageRowsCamel ?: pageRows).orEmpty()
        return AdReport(
            id = resolved,
            // A row's own type wins when it has one; otherwise the endpoint's.
            kind = reportType?.takeIf { it.isNotBlank() }?.let(ReportKind::from) ?: kind,
            name = name.orEmpty(),
            status = ReportStatus.from(status),
            shootDate = shootDate?.toLong(),
            dayNumber = dayNumber,
            pageRows = layout.mapIndexed { index, row -> row.toDomain(index) },
            fills = fills.orEmpty()
                .mapNotNull { fill ->
                    val cell = fill.cellId ?: return@mapNotNull null
                    val row = fill.rowId ?: return@mapNotNull null
                    val column = fill.column ?: return@mapNotNull null
                    ReportFills.key(cell, row, column) to fill.value.orEmpty()
                }
                .toMap(),
            header = meta?.header.orEmpty().mapNotNull { (key, value) ->
                value?.let { key to it }
            }.toMap(),
            templateName = templateName.orEmpty(),
            signatures = signatures?.map { it.toDomain() }.orEmpty(),
            signState = signState ?: "none",
            signRound = signRound ?: 0,
            reportAttachment = reportAttachment.orEmpty(),
            postedAt = postedAt?.toLong(),
            existed = existed == true,
        )
    }
}

@Serializable
internal data class TemplateBlockDto(
    @SerialName("type") val type: String? = null,
    @SerialName("title") val title: String? = null,
)

@Serializable
internal data class TemplateDto(
    @Serializable(with = LooseString::class)
    @SerialName("id") val id: String? = null,
    @Serializable(with = LooseString::class)
    @SerialName("_id") val underscoreId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("report_type") val reportType: String? = null,
    @SerialName("origin") val origin: String? = null,
    @SerialName("sub") val subtitle: String? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("blockCount") val blockCount: Int? = null,
    @Serializable(with = LooseInt::class)
    @SerialName("rowCount") val rowCount: Int? = null,
    @SerialName("blocks") val blocks: List<TemplateBlockDto>? = null,
    @SerialName("pageRows") val pageRowsCamel: List<ReportPageRowDto>? = null,
    @SerialName("page_rows") val pageRows: List<ReportPageRowDto>? = null,
    @SerialName("meta") val meta: ReportMetaDto? = null,
) {
    fun toDomain(): ReportTemplate? {
        val resolved = id ?: underscoreId ?: return null
        val layout = (pageRowsCamel ?: pageRows).orEmpty()
        return ReportTemplate(
            id = resolved,
            name = name.orEmpty(),
            kind = ReportKind.from(reportType),
            origin = origin ?: "custom",
            subtitle = subtitle.orEmpty(),
            blockCount = blockCount ?: layout.sumOf { it.cells.orEmpty().size },
            rowCount = rowCount ?: layout.sumOf { page ->
                page.cells.orEmpty().sumOf { it.rows.orEmpty().size }
            },
            blocks = blocks.orEmpty().map { ReportBlockType.from(it.type) to it.title.orEmpty() },
            pageRows = layout.mapIndexed { index, row -> row.toDomain(index) },
            header = meta?.header.orEmpty().mapNotNull { (key, value) ->
                value?.let { key to it }
            }.toMap(),
        )
    }
}

/** The structured refusal a publish answers with while cast rows are unsigned. */
@Serializable
internal data class SignaturesPendingDto(
    @SerialName("rows") val rows: List<SigningRowDto>? = null,
    @SerialName("pending") val pending: List<SigningRowDto>? = null,
) {
    fun toDomain(): List<SigningRow> = (rows ?: pending).orEmpty().map { it.toDomain() }
}
