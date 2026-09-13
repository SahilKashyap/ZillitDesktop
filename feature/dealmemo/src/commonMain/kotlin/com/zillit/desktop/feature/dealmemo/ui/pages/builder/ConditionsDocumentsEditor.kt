package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.domain.authoring.BuilderDocuments
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.FileTypeBadge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Conditions & Documents (`Step6CreditConditions.jsx` then `Step8Documents.jsx`):
 * the deal's conditions and work location, the Production Setup documents it
 * carries, and custom PDFs.
 */
@Composable
internal fun ConditionsDocumentsEditor(
    state: DealMemoUiState,
    builder: BuilderState,
    ops: FormOps,
    onEvent: (DealMemoEvent) -> Unit,
) {
    ConditionsCard(builder.form, ops)
    WorkLocationCard(builder, ops)
    AgreementDocumentsCard(state, builder, onEvent)
    UploadCard(onEvent)
    CustomDocumentsCard(builder, ops, onEvent)
}

@Composable
private fun ConditionsCard(form: DealForm, ops: FormOps) {
    val conditions = conditionTexts(form)
    CardBlock(title = "Conditions") {
        if (conditions.isEmpty()) {
            EmptyNote(
                "No custom conditions added. Click + to add one (e.g. “Travel out of London paid at agreed rate”, " +
                    "“Non-compete during principal photography”).",
                Modifier.padding(bottom = 14.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                conditions.forEachIndexed { index, condition ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BuilderInput(
                            value = condition,
                            onValueChange = { value ->
                                ops.edit { it.with("customConditions", replaceAt(conditionTexts(it), index, value)) }
                            },
                            placeholder = "Condition…",
                            modifier = Modifier.weight(1f),
                        )
                        SquareRemove(tooltip = "Remove") {
                            ops.edit {
                                it.with(
                                    "customConditions",
                                    JsonArray(
                                        conditionTexts(it).filterIndexed { i, _ -> i != index }.map {
                                            JsonPrimitive(it)
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        AddRowButton("Add Condition") {
            ops.edit {
                it.with("customConditions", JsonArray(conditionTexts(it).map { JsonPrimitive(it) } + JsonPrimitive("")))
            }
        }
    }
}

/** The agreement publishes distant-location provisions — then the work location matters. */
@Suppress("LongMethod")
@Composable
private fun WorkLocationCard(builder: BuilderState, ops: FormOps) {
    val form = builder.form
    val distant = (builder.reference.selectedUnion?.get("distant_location") as? JsonObject)?.get("applicable")
    if (!(distant is JsonPrimitive && !distant.isString && distant.content == "true")) return
    val onDistant = form.flag("distantLocation")
    val tag = when {
        onDistant -> "Distant Location"
        else -> when (form.text("workLocationType")) {
            "on-location" -> "On Location"
            "remote" -> "Remote"
            "mixed" -> "Mixed"
            else -> "Studio / Local"
        }
    }
    CardBlock(title = "Work Location", tag = tag, tone = BuilderTone.Teal) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 14.dp)) {
            listOf(
                "studio" to "Studio / Local",
                "on-location" to "On Location",
                "mixed" to "Mixed",
            ).forEach { (id, label) ->
                ChoiceChip(
                    label,
                    selected = form.text("workLocationType") == id,
                    onClick = { ops.set("workLocationType", id) },
                    teal = true,
                )
            }
        }
        if (form.text("union") in TRAVEL_ZONE_AGREEMENTS) {
            Field(
                "Travel Zone Election",
                required = true,
                hint = "Clause 8.3 — mandatory on deal memo. One option must be elected per Worker for the duration " +
                    "of the engagement.",
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                NativeSelect(
                    value = form.text("travelZone"),
                    options = listOf(
                        PickOption("30mile", "30 Mile Radius (Clause 8.3a)"),
                        PickOption("m25", "Within M25 (Clause 8.3b — Production Base within M25 only)"),
                    ),
                    onPick = { ops.set("travelZone", it) },
                    menuWidth = 420.dp,
                )
            }
        }
        ToggleRow(
            title = "Distant location applies to this engagement",
            sub = "Over 50 road miles from Production Base",
            checked = onDistant,
            onChange = { ops.set("distantLocation", it) },
        )
        if (onDistant) {
            Rule(bp.hairline, Modifier.padding(vertical = 10.dp))
            BuilderAlert(
                "Distant location provisions apply. Per-diem and accommodation rates are configured as allowance " +
                    "rows on the Allowances step and are paid in addition to the agreed deal rate.",
            )
        }
    }
}

@Composable
private fun AgreementDocumentsCard(state: DealMemoUiState, builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val rows = state.projectSettings.view.agreementDocuments
    val documents = builder.form.objects("documents")
    val policy = builder.form.obj("psSignRequired")
    CardBlock(title = "Additional Documents") {
        when {
            !state.projectSettings.loaded -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitSpinner(size = 14.dp, color = bp.cta)
                ZillitText(text = "Loading templates…", style = DmType.sans(12.5.sp), color = bp.muted)
            }
            rows.isEmpty() -> EmptyNote(
                "No agreement templates configured in Production Setup yet. Upload them under Production Setup → " +
                    "Agreements Documents to surface them here.",
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    val id = BuilderDocuments.agreementId(row)
                    AgreementRow(
                        row = row,
                        attached = BuilderDocuments.isAttached(documents, id),
                        signRequired = BuilderDocuments.signRequired(documents, policy, id),
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun AgreementRow(row: JsonObject, attached: Boolean, signRequired: Boolean, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    val id = BuilderDocuments.agreementId(row)
    val flat = BuilderDocuments.flatten(row)
    val filename = BuilderDocuments.filename(row)
    val name = text(row["title"]).ifEmpty { filename }.ifEmpty { "—" }
    val description = text(row["description"]).ifEmpty { filename }
    val extension = text(flat["content_subtype"]).ifEmpty { filename.substringAfterLast('.', "") }
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (attached) p.greenSoft else p.inputBg)
            .border(1.dp, if (attached) p.greenRing else p.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box {
            FileTypeBadge(extension)
            if (attached) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 4.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(p.greenSoft)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(p.greenInk),
                    contentAlignment = Alignment.Center,
                ) { ZillitIcon(ZillitIcons.Check, size = 7.dp, tint = Color.White) }
            }
        }
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = name,
                style = DmType.sans(13.5.sp, FontWeight.Bold),
                color = p.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ZillitText(
                text = description,
                style = DmType.sans(11.5.sp).copy(lineHeight = 16.sp),
                color = p.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SignToggle(signRequired) { onEvent(BuilderEvent.SetAgreementSignRequired(id, it)) }
        }
        if (text(flat["media"]).isNotEmpty()) {
            RowButton("View", RowButtonTone.Neutral) { onEvent(BuilderEvent.ViewAgreement(id)) }
        }
        if (attached) {
            RowButton("✕ Remove", RowButtonTone.Danger) { onEvent(BuilderEvent.DetachAgreement(id)) }
        } else {
            RowButton("+ Attach", RowButtonTone.Primary) { onEvent(BuilderEvent.AttachAgreement(id)) }
        }
    }
}

@Composable
private fun UploadCard(onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    CardBlock(title = "Upload Custom Document") {
        val (source, hovered) = rememberHover()
        val shape = RoundedCornerShape(12.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .dashedBorder(if (hovered) p.cta else p.menuBorder, 12.dp, 2.dp)
                .clip(shape)
                .background(if (hovered) p.amberSoft else p.infoBox)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null) { onEvent(BuilderEvent.PickCustomDocuments) }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 36.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.inputBg)
                    .border(1.dp, p.hairline, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { ZillitIcon(ZillitIcons.Paperclip, size = 18.dp, tint = if (hovered) p.sectionLabel else p.muted) }
            ZillitText(text = "Drop PDFs here", style = DmType.sans(14.sp, FontWeight.Bold), color = p.ink)
            ZillitText(
                text = "or click to browse — max 20MB per file. Files upload when you Save or Issue this deal memo.",
                style = DmType.sans(12.sp),
                color = p.muted,
            )
            Row(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(p.inputBg)
                    .border(1.dp, p.hairline, RoundedCornerShape(9.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitIcon(ZillitIcons.Upload, size = 12.dp, tint = p.ink2)
                ZillitText(text = "Browse Files", style = DmType.sans(12.5.sp, FontWeight.Bold), color = p.ink2)
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun CustomDocumentsCard(builder: BuilderState, ops: FormOps, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    val custom = builder.form.objects("documents").filter { text(it["source"]) != BuilderDocuments.SOURCE_PS }
    if (custom.isEmpty()) return
    CardBlock(title = "Attached Custom Documents", tone = BuilderTone.Blue) {
        custom.forEachIndexed { index, doc ->
            val id = text(doc["id"])
            val attachment = doc["attachment"] as? JsonObject
            val file = doc["file"] as? JsonObject
            if (index > 0) Rule(p.tile)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.padding(top = 6.dp).size(24.dp).clip(CircleShape).background(p.blueSoft),
                    contentAlignment = Alignment.Center,
                ) { ZillitIcon(ZillitIcons.File, size = 12.dp, tint = p.blue) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BuilderInput(
                        value = text(doc["title"]),
                        onValueChange = { value ->
                            ops.edit {
                                it.withDocument(id) { row -> JsonObject(row + ("title" to JsonPrimitive(value))) }
                            }
                        },
                        placeholder = "Document title",
                        height = 32.dp,
                        textSize = 12.5f,
                    )
                    BuilderInput(
                        value = text(doc["description"]),
                        onValueChange = { value ->
                            ops.edit {
                                it.withDocument(id) { row -> JsonObject(row + ("description" to JsonPrimitive(value))) }
                            }
                        },
                        placeholder = "Description (optional)",
                        height = 32.dp,
                        textSize = 12.5f,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ZillitText(
                            text = text(attachment?.get("name")).ifEmpty { text(file?.get("name")) }.ifEmpty { "—" },
                            style = DmType.mono(11.sp),
                            color = p.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Pill("Custom", p.tile, Color(0xFF6B7280))
                        if (attachment == null && file != null) {
                            Pill("Uploads on save", Color(0xFFFEF3C7), Color(0xFFB45309))
                        }
                    }
                    SignToggle(!isFalse(doc["signRequired"])) { required ->
                        ops.edit {
                            it.withDocument(id) { row -> JsonObject(row + ("signRequired" to JsonPrimitive(required))) }
                        }
                    }
                }
                if (attachment != null || file != null) {
                    RowButton("View", RowButtonTone.Neutral, height = 28.dp) { onEvent(BuilderEvent.ViewDocument(id)) }
                }
                RemoveButton(tooltip = "Remove", size = 28.dp, onClick = { ops.edit { it.withDocument(id) { null } } })
            }
        }
    }
}

@Composable
private fun SignToggle(required: Boolean, onChange: (Boolean) -> Unit) {
    val p = bp
    Row(
        Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BuilderSwitch(required, onChange)
        ZillitText(text = "Crew sign required", style = DmType.sans(11.5.sp, FontWeight.SemiBold), color = p.ink2)
        ZillitText(
            text = if (required) "— must be signed before Send for Approval" else "— informational only",
            style = DmType.sans(11.sp),
            color = p.muted,
        )
    }
}

internal enum class RowButtonTone { Neutral, Primary, Danger }

@Composable
internal fun RowButton(text: String, tone: RowButtonTone, height: Dp = 30.dp, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(8.dp)
    val (background, border, ink) = when (tone) {
        RowButtonTone.Neutral -> Triple(if (hovered) p.chipHover else p.inputBg, p.hairline, p.ink2)
        RowButtonTone.Primary -> Triple(if (hovered) p.ctaHover else p.cta, null, Color.White)
        RowButtonTone.Danger -> Triple(if (hovered) p.redSoft else p.inputBg, Color(0xFFF3C2C2), Color(0xFFB22A2A))
    }
    Box(
        modifier = Modifier
            .height(height)
            .clip(shape)
            .background(background)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { ZillitText(text = text, style = DmType.sans(12.sp, FontWeight.Bold), color = ink, maxLines = 1) }
}

@Composable
private fun Pill(text: String, background: Color, ink: Color) {
    Box(Modifier.clip(CircleShape).background(background).padding(horizontal = 6.dp, vertical = 2.dp)) {
        ZillitText(text = text.uppercase(), style = DmType.sans(9.sp, FontWeight.Bold), color = ink, maxLines = 1)
    }
}

/** The allowances table's add-row button: dashed, a plus, 36 tall. */
@Composable
internal fun AddRowButton(label: String, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .height(36.dp)
            .dashedBorder(p.menuBorder, 10.dp)
            .clip(shape)
            .background(if (hovered) p.infoBox else p.inputBg)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(ZillitIcons.Add, size = 11.dp, tint = p.ink2)
        ZillitText(text = label, style = DmType.sans(12.5.sp, FontWeight.Bold), color = p.ink2, maxLines = 1)
    }
}

/** A 36 px square remove — the conditions list. */
@Composable
internal fun SquareRemove(tooltip: String, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(9.dp)
    ZillitTooltip(text = tooltip) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(shape)
                .background(if (hovered) p.redSoft else p.inputBg)
                .border(1.dp, p.hairline, shape)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) { ZillitIcon(ZillitIcons.Close, size = 11.dp, tint = if (hovered) p.red else p.muted) }
    }
}

/** The conditions as editable strings — a legacy `{condition}` row reads as its text. */
internal fun conditionTexts(form: DealForm): List<String> = form.list("customConditions").map { condition ->
    when (condition) {
        is JsonPrimitive -> if (condition.isString) condition.content else Js.text(condition)
        is JsonObject -> text(condition["condition"])
        else -> ""
    }
}

private fun replaceAt(values: List<String>, index: Int, value: String): JsonArray =
    JsonArray(values.mapIndexed { i, v -> JsonPrimitive(if (i == index) value else v) })

private fun DealForm.withDocument(id: String, transform: (JsonObject) -> JsonObject?): DealForm =
    with("documents", BuilderDocuments.mapRow(JsonArray(list("documents")), id, transform))

private fun text(value: JsonElement?): String = when (value) {
    null, JsonNull -> ""
    else -> Js.text(value)
}

private fun isFalse(value: JsonElement?): Boolean =
    value is JsonPrimitive && !value.isString && value.content == "false"

private val TRAVEL_ZONE_AGREEMENTS = setOf("pact-bectu-tvda", "pact-bectu-mmp")
