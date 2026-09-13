package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.feature.dealmemo.domain.authoring.BuilderDocuments
import com.zillit.desktop.feature.dealmemo.domain.authoring.BuilderSeeds
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.builder.megabytes
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.CoaCodeField
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.FileTypeBadge
import com.zillit.desktop.feature.dealmemo.ui.pages.rules.GridInput
import com.zillit.desktop.feature.dealmemo.ui.pages.rules.GridOption
import com.zillit.desktop.feature.dealmemo.ui.pages.rules.GridSelect
import com.zillit.desktop.feature.dealmemo.ui.pages.rules.rp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock

// -- §5 Production Schedule ------------------------------------------------------------------------

/**
 * The setup's engagement dates and its prep / shoot / wrap schedule — the
 * project's production schedule once saved. Each phase starts no earlier than
 * the day after the one before it ends, and nothing runs past the end date.
 */
@Suppress("LongMethod")
@Composable
internal fun SetupScheduleSection(builder: BuilderState, ops: FormOps) {
    val form = builder.form
    val dealStart = form.text("dealStart")
    val dealEnd = form.text("dealEnd")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BuilderGrid(columns = 2, gap = 16.dp) {
            cell {
                Field("Start Date") { IsoDateInput(dealStart, { ops.set("dealStart", it) }) }
            }
            cell {
                Field("End Date") {
                    IsoDateInput(
                        dealEnd,
                        { ops.set("dealEnd", it) },
                        min = BuilderSeeds.nextDay(dealStart).ifEmpty { null },
                    )
                }
            }
        }
        ToggleRow(
            title = "Set Prep / Shoot / Wrap Schedule",
            sub = "Phase dates become the project's production schedule.",
            checked = form.flag("schedOn"),
            onChange = { ops.set("schedOn", it) },
        )
        if (form.flag("schedOn")) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PHASES.forEach { (label, startKey, endKey) ->
                    val startMin = when (label) {
                        "Prep" -> dealStart
                        "Shoot" -> BuilderSeeds.nextDay(form.text("schedPrepEnd")).ifEmpty { dealStart }
                        else -> BuilderSeeds.nextDay(form.text("schedShootEnd")).ifEmpty { dealStart }
                    }.ifEmpty { null }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ZillitText(
                            text = label,
                            style = DmType.sans(12.sp, FontWeight.SemiBold),
                            color = bp.ink2,
                            modifier = Modifier.width(64.dp),
                        )
                        IsoDateInput(
                            value = form.text(startKey),
                            onChange = { ops.set(startKey, it) },
                            modifier = Modifier.weight(1f),
                            min = startMin,
                            max = dealEnd.ifEmpty { null },
                        )
                        IsoDateInput(
                            value = form.text(endKey),
                            onChange = { ops.set(endKey, it) },
                            modifier = Modifier.weight(1f),
                            min = BuilderSeeds.nextDay(form.text(startKey)).ifEmpty { null } ?: startMin,
                            max = dealEnd.ifEmpty { null },
                        )
                    }
                }
            }
        }
    }
}

private val PHASES = listOf(
    Triple("Prep", "schedPrepStart", "schedPrepEnd"),
    Triple("Shoot", "schedShootStart", "schedShootEnd"),
    Triple("Wrap", "schedWrapStart", "schedWrapEnd"),
)

// -- §7 Allowances & Rentals ------------------------------------------------------------------------

/** The project's entitlement catalogue as two spreadsheets — every row part of it, no enable switch. */
@Composable
internal fun SetupEntitlementsSection(state: DealMemoUiState, builder: BuilderState, ops: FormOps) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EntitlementSheet(state, builder.form, ops, rental = false)
        EntitlementSheet(state, builder.form, ops, rental = true)
    }
}

@Composable
private fun EntitlementSheet(state: DealMemoUiState, form: DealForm, ops: FormOps, rental: Boolean) {
    val key = if (rental) "rentals" else "allowances"
    val rows = form.objects(key)
    val columns = if (rental) RENTAL_COLUMNS else ALLOWANCE_COLUMNS
    fun patch(id: String, field: String, value: String) = ops.edit { current ->
        current.with(key, JsonArray(current.list(key).map { row ->
            if (row is JsonObject && Js.text(row["id"]) == id) {
                JsonObject(row + (field to JsonPrimitive(value)))
            } else {
                row
            }
        }))
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = if (rental) "Rentals" else "Allowances",
                style = DmType.sans(13.sp, FontWeight.Bold),
                color = rp.ink,
            )
            CountPill(rows.size, Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            DashedAddButton(
                label = if (rental) "+ Add Rental" else "+ Add Allowance",
                onClick = {
                    ops.edit { current ->
                        current.with(key, JsonArray(current.list(key) + newEntitlement(key, rental)))
                    }
                },
            )
        }
        if (rows.isEmpty()) return@Column
        val shape = RoundedCornerShape(10.dp)
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, rp.border, shape)) {
            SheetHeader(columns)
            rows.forEach { row ->
                val id = Js.text(row["id"])
                EntitlementRow(
                    state = state,
                    row = row,
                    rental = rental,
                    columns = columns,
                    patch = { field, value -> patch(id, field, value) },
                    onRemove = {
                        ops.edit { current ->
                            current.with(
                                key,
                                JsonArray(
                                    current.list(key).filterNot { (it as? JsonObject)?.get("id")?.let(Js::text) == id },
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(columns: List<SheetColumn>) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(rp.surfaceAlt)
            .bottomLine(rp.border)
            .padding(start = 18.dp, end = 12.dp),
    ) {
        columns.forEachIndexed { index, column ->
            SheetCell(column, index, center = false) {
                if (column.title.isNotEmpty()) {
                    ZillitText(
                        text = column.title.uppercase() + if (column.required) " *" else "",
                        style = DmType.sans(10.5.sp, FontWeight.ExtraBold, 0.3.sp),
                        color = rp.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 9.dp),
                    )
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
private fun EntitlementRow(
    state: DealMemoUiState,
    row: JsonObject,
    rental: Boolean,
    columns: List<SheetColumn>,
    patch: (String, String) -> Unit,
    onRemove: () -> Unit,
) {
    fun text(field: String) = row[field]?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty()
    val rail = if (rental) RENTAL_RAIL else ALLOWANCE_RAIL
    Box(Modifier.fillMaxWidth().height(37.dp).background(rp.surface).bottomLine(rp.divider)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(rail))
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(start = 18.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEachIndexed { index, column ->
                SheetCell(column, index, center = column.id == COLUMN_REMOVE) {
                    when (column.id) {
                        "name" -> GridInput(
                            text("name"),
                            { patch("name", it) },
                            placeholder = if (rental) "e.g. Camera Kit" else "e.g. Per Diem",
                        )
                        "rate" -> GridInput(
                            text("rate"),
                            { patch("rate", it) },
                            placeholder = "0.00",
                            mono = true,
                            filter = ::decimal,
                        )
                        "basis" -> GridSelect(
                            value = text("basis"),
                            options = listOf(GridOption("", "—")) + basisOptions(text("basis")).map {
                                GridOption(it.key, it.label, disabled = it.disabled)
                            },
                            onPick = { patch("basis", it) },
                        )
                        "applies" -> GridSelect(
                            value = text("applies_to"),
                            options = listOf(GridOption("", "—")) + (if (rental) RENTAL_APPLIES else ALLOWANCE_APPLIES)
                                .map { GridOption(it.key, it.label) },
                            onPick = { patch("applies_to", it) },
                        )
                        "cap" -> GridSelect(
                            value = text("cap_type").ifEmpty { "uncapped" },
                            options = listOf(GridOption("uncapped", "Uncapped"), GridOption("capped", "Capped")),
                            onPick = { patch("cap_type", it) },
                        )
                        "capAmount" -> Box(Modifier.alpha(if (text("cap_type") == "capped") 1f else DISABLED_ALPHA)) {
                            if (text("cap_type") == "capped") {
                                GridInput(
                                    text("cap_amount"),
                                    { patch("cap_amount", it) },
                                    placeholder = "0.00",
                                    mono = true,
                                    filter = ::decimal,
                                )
                            } else {
                                ZillitText(
                                    text = "0.00",
                                    style = DmType.mono(12.5.sp),
                                    color = rp.ink3,
                                    modifier = Modifier.padding(horizontal = 9.dp),
                                )
                            }
                        }
                        "nominal" -> CoaCodeField(
                            value = text("nominal"),
                            onValueChange = { patch("nominal", it) },
                            coa = state.coa,
                            placeholder = "—",
                            alignEnd = false,
                            borderless = true,
                        )
                        else -> RemoveRowButton(if (rental) "Remove rental" else "Remove allowance", onRemove)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.SheetCell(column: SheetColumn, index: Int, center: Boolean, content: @Composable () -> Unit) {
    val divider = if (column.edge) rp.border else rp.divider
    val base = if (column.width != null) Modifier.width(column.width) else Modifier.weight(column.weight)
    Box(
        modifier = base
            .fillMaxHeight()
            .drawBehind { if (index > 0) drawLine(divider, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) },
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
    ) { content() }
}

@Composable
private fun RemoveRowButton(tooltip: String, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    ZillitTooltip(text = tooltip) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (hovered) rp.red.copy(alpha = REMOVE_WASH) else Color.Transparent)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) { ZillitText(text = "×", style = DmType.sans(15.sp), color = if (hovered) rp.red else rp.ink3) }
    }
}

@Composable
internal fun CountPill(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(50))
            .background(rp.chipBg)
            .border(1.dp, rp.chipBorder, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 1.dp),
    ) { ZillitText(text = count.toString(), style = DmType.mono(11.sp, FontWeight.Bold), color = rp.ink2) }
}

private class SheetColumn(
    val id: String,
    val title: String,
    val weight: Float = 1f,
    val width: Dp? = null,
    val required: Boolean = false,
    val edge: Boolean = false,
)

private const val COLUMN_REMOVE = "remove"

private val ALLOWANCE_COLUMNS = listOf(
    SheetColumn("name", "Name", 1.6f, required = true),
    SheetColumn("rate", "Amount", 0.9f, required = true),
    SheetColumn("basis", "Basis", 1.1f),
    SheetColumn("applies", "Applies To", 1.4f),
    SheetColumn("nominal", "Nominal", 0.9f, edge = true),
    SheetColumn(COLUMN_REMOVE, "", width = 40.dp),
)

private val RENTAL_COLUMNS = listOf(
    SheetColumn("name", "Name", 1.5f, required = true),
    SheetColumn("rate", "Amount", 0.8f, required = true),
    SheetColumn("basis", "Basis", 1f),
    SheetColumn("applies", "Applies To", 1.2f),
    SheetColumn("cap", "Cap", 0.9f, edge = true),
    SheetColumn("capAmount", "Cap Amount", 0.9f),
    SheetColumn("nominal", "Nominal", 0.8f, edge = true),
    SheetColumn(COLUMN_REMOVE, "", width = 40.dp),
)

private val ALLOWANCE_RAIL = Color(0xFFEA7A0E)
private val RENTAL_RAIL = Color(0xFF2563EB)
private const val DISABLED_ALPHA = 0.5f
private const val REMOVE_WASH = 0.10f

private fun decimal(text: String): Boolean = text.all { it.isDigit() || it == '.' || it == ',' }

private fun newEntitlement(key: String, rental: Boolean): JsonObject = JsonObject(
    buildMap {
        put("id", JsonPrimitive("custom-$key-${Clock.System.now().toEpochMilliseconds()}"))
        put("name", JsonPrimitive(""))
        put("on", JsonPrimitive(true))
        put("rate", JsonPrimitive(""))
        put("basis", JsonPrimitive(if (rental) "week" else "day"))
        put("applies_to", JsonPrimitive(""))
        put("nominal", JsonPrimitive(""))
        if (rental) {
            put("cap_type", JsonPrimitive("uncapped"))
            put("cap_amount", JsonPrimitive(""))
        }
    },
)

private fun Modifier.bottomLine(color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
}

// -- §8 Conditions & Documents -----------------------------------------------------------------------

/** The standard conditions every deal carries, and the project's agreement documents, managed in place. */
@Composable
internal fun SetupConditionsSection(
    state: DealMemoUiState,
    builder: BuilderState,
    ops: FormOps,
    onEvent: (DealMemoEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StandardConditions(builder.form, ops)
        AgreementDocuments(state, builder, onEvent)
    }
}

@Composable
private fun StandardConditions(form: DealForm, ops: FormOps) {
    val conditions = form.list("customConditions")
    Column {
        FieldLabel("Standard Deal Conditions")
        if (conditions.isEmpty()) {
            ZillitText(
                text = "No conditions yet — add the clauses every deal should carry.",
                style = DmType.sans(12.5.sp),
                color = bp.muted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            conditions.forEachIndexed { index, condition ->
                val text = when (condition) {
                    is JsonObject -> condition["condition"]?.takeUnless(Js::isNullish)?.let(Js::text).orEmpty()
                    else -> condition.takeUnless(Js::isNullish)?.let(Js::text).orEmpty()
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BuilderInput(
                        value = text,
                        onValueChange = { typed ->
                            ops.edit { current ->
                                val next = current.list("customConditions").toMutableList()
                                if (index < next.size) next[index] = JsonPrimitive(typed)
                                current.with("customConditions", JsonArray(next))
                            }
                        },
                        placeholder = "e.g. Travel out of London paid at agreed rate",
                        modifier = Modifier.weight(1f),
                    )
                    RemoveButton("Remove condition", onClick = {
                        ops.edit { current ->
                            current.with(
                                "customConditions",
                                JsonArray(current.list("customConditions").filterIndexed { i, _ -> i != index }),
                            )
                        }
                    })
                }
            }
        }
        DashedAddButton(
            label = "+ Add Condition",
            onClick = {
                ops.edit { it.with("customConditions", JsonArray(it.list("customConditions") + JsonPrimitive(""))) }
            },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Agreement Documents: the project's own list, edited in place — a saved
 * row's title and description are written when the field is left, and
 * picked PDFs wait in a queue for Save all.
 */
@Suppress("LongMethod")
@Composable
private fun AgreementDocuments(state: DealMemoUiState, builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val page = builder.setupPage
    val busy = page.documentsBusy
    val saved = state.projectSettings.view.agreementDocuments
    Column {
        FieldLabel("Agreement Documents")
        saved.forEachIndexed { index, row ->
            val id = BuilderDocuments.agreementId(row)
            val nested = row["document"] as? JsonObject
            fun text(key: String) =
                (row[key]?.takeIf(Js::truthy) ?: nested?.get(key)?.takeIf(Js::truthy))?.let(Js::text)
            val edit = page.documentEdits[id]
            val title = edit?.title ?: text("title") ?: text("name").orEmpty()
            val description = edit?.description ?: text("description").orEmpty()
            val fileName = text("name").orEmpty()
            DocumentRow(
                first = index == 0,
                subtype = text("content_subtype") ?: fileName.substringAfterLast('.', ""),
                title = title,
                description = description,
                caption = fileName.ifEmpty { null },
                busy = busy,
                removeLabel = "Remove document",
                onEdit = { t, d -> onEvent(BuilderEvent.EditAgreementDocument(id, t, d)) },
                onLeave = { onEvent(BuilderEvent.CommitAgreementDocument(id)) },
                onRemove = { onEvent(BuilderEvent.DeleteAgreementDocument(id)) },
            )
        }
        page.pendingDocuments.forEachIndexed { index, row ->
            DocumentRow(
                first = saved.isEmpty() && index == 0,
                subtype = row.file.name.substringAfterLast('.', ""),
                title = row.title,
                description = row.description,
                caption = "${row.file.name} · ${megabytes(row.file.bytes.size)}",
                busy = busy,
                removeLabel = "Remove from pending",
                onEdit = { t, d -> onEvent(BuilderEvent.EditPendingDocument(row.id, t, d)) },
                onLeave = {},
                onRemove = { onEvent(BuilderEvent.RemovePendingDocument(row.id)) },
            )
        }
        Row(
            modifier = Modifier.padding(top = if (saved.isEmpty() && page.pendingDocuments.isEmpty()) 0.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.alpha(if (busy) BUSY_ALPHA else 1f)) {
                DashedAddButton(
                    label = "+ Add Document (PDF)",
                    onClick = { if (!busy) onEvent(BuilderEvent.QueueAgreementDocuments) },
                )
            }
            if (page.pendingDocuments.isNotEmpty()) {
                SolidButton(
                    text = if (busy) "Uploading…" else "Save all ${page.pendingDocuments.size}",
                    enabled = !busy,
                    onClick = { onEvent(BuilderEvent.SaveAgreementDocuments) },
                )
            }
        }
    }
}

@Composable
private fun DocumentRow(
    first: Boolean,
    subtype: String,
    title: String,
    description: String,
    caption: String?,
    busy: Boolean,
    removeLabel: String,
    onEdit: (String, String) -> Unit,
    onLeave: () -> Unit,
    onRemove: () -> Unit,
) {
    val p = bp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (first) Modifier else Modifier.topLine(p.hairline).padding(top = 12.dp)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FileTypeBadge(subtype)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BuilderInput(
                    value = title,
                    onValueChange = { onEdit(it, description) },
                    placeholder = "Title *",
                    enabled = !busy,
                    onBlur = onLeave,
                    modifier = Modifier.weight(1f),
                )
                BuilderInput(
                    value = description,
                    onValueChange = { onEdit(title, it) },
                    placeholder = "Description (optional)",
                    enabled = !busy,
                    onBlur = onLeave,
                    modifier = Modifier.weight(DESCRIPTION_WEIGHT),
                )
            }
            Box(Modifier.alpha(if (busy) BUSY_ALPHA else 1f)) {
                RemoveButton(removeLabel, onClick = { if (!busy) onRemove() })
            }
        }
        caption?.let {
            ZillitTooltip(text = it) {
                ZillitText(
                    text = it,
                    style = DmType.mono(11.sp),
                    color = p.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 52.dp, top = 4.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SolidButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else BUSY_ALPHA)
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered && enabled) p.ctaHover else p.cta)
            .hoverable(source)
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { ZillitText(text = text, style = DmType.sans(12.5.sp, FontWeight.SemiBold), color = Color.White, maxLines = 1) }
}

private fun Modifier.topLine(color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
}

private const val DESCRIPTION_WEIGHT = 1.4f
private const val BUSY_ALPHA = 0.6f

// -- §11 Payroll Bureau -------------------------------------------------------------------------------

/** The project's payroll bureaus; the first titled one becomes the setup's bureau. */
@Composable
internal fun SetupBureauSection(builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        builder.setupBureaus.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    if (index == 0) FieldLabel("Bureau")
                    BuilderInput(
                        value = row.title,
                        onValueChange = { onEvent(BuilderEvent.EditBureau(row.id, title = it)) },
                        placeholder = "e.g. Sargent-Disc",
                    )
                }
                Column(Modifier.weight(DESCRIPTION_WEIGHT)) {
                    if (index == 0) FieldLabel("Description")
                    BuilderInput(
                        value = row.description,
                        onValueChange = { onEvent(BuilderEvent.EditBureau(row.id, description = it)) },
                        placeholder = "Optional — what this bureau handles",
                    )
                }
                Box(Modifier.padding(top = if (index == 0) FIRST_ROW_OFFSET else 0.dp)) {
                    RemoveButton("Remove bureau", onClick = { onEvent(BuilderEvent.RemoveBureau(row.id)) })
                }
            }
        }
        DashedAddButton(label = "+ Add Bureau", onClick = { onEvent(BuilderEvent.AddBureau) })
    }
}

private val FIRST_ROW_OFFSET = 25.dp
