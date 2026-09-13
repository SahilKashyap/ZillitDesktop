package com.zillit.desktop.feature.dealmemo.ui.pages.crew

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewFormValues
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAttachment
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.dashedBorder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The passport / ID uploader: up to two files, each viewable and removable;
 * files upload the moment they are picked and are kept by the next Save.
 */
@Composable
internal fun PassportUploader(
    files: List<DealAttachment>,
    uploading: Boolean,
    onAdd: () -> Unit,
    onView: (DealAttachment) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEachIndexed { index, file ->
            PassportRow(file, onView = { onView(file) }, onRemove = { onRemove(index) })
        }
        if (files.size < PASSPORT_MAX) {
            AddFileButton(
                label = when {
                    uploading -> "Uploading…"
                    files.isEmpty() -> "Upload passport / ID (PDF, JPG or PNG — up to 2)"
                    else -> "Add another"
                },
                uploading = uploading,
                roomy = files.isEmpty(),
                onClick = onAdd,
            )
        }
    }
}

@Composable
private fun PassportRow(file: DealAttachment, onView: () -> Unit, onRemove: () -> Unit) {
    val p = cp
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.fileRow)
            .border(1.dp, p.fileBorder, shape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitIcon(ZillitIcons.File, size = 18.dp, tint = p.brand)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = file.name ?: file.media ?: "Passport / ID",
                style = DmType.sans(12.sp, FontWeight.Bold),
                color = p.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ZillitText(
                text = (file.contentSubtype ?: "file").uppercase(),
                style = DmType.sans(10.sp),
                color = p.muted,
                maxLines = 1,
            )
        }
        ViewChip(onView)
        RemoveButton(onRemove)
    }
}

@Composable
private fun ViewChip(onClick: () -> Unit) {
    val p = cp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(4.dp)
    val ink = if (hovered) p.brand else p.label
    Row(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, if (hovered) p.brand else p.fileBorder, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitIcon(ZillitIcons.Eye, size = 10.dp, tint = ink)
        ZillitText(text = "View", style = DmType.sans(10.sp, FontWeight.SemiBold), color = ink)
    }
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) Color(0xFFEF4444).copy(alpha = 0.10f) else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            ZillitIcons.Close,
            size = 11.dp,
            tint = Color(0xFFEF4444),
            contentDescription = "Remove passport / ID",
        )
    }
}

@Composable
private fun AddFileButton(label: String, uploading: Boolean, roomy: Boolean, onClick: () -> Unit) {
    val p = cp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (uploading) DISABLED else 1f)
            .clip(shape)
            .background(p.fileRow)
            .dashedBorder(if (hovered && !uploading) p.brand else p.fileBorder, 6.dp)
            .hoverable(source)
            .then(
                if (uploading) {
                    Modifier
                } else {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                        .pointerHoverIcon(PointerIcon.Hand)
                },
            )
            .padding(horizontal = 12.dp, vertical = if (roomy) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (uploading) {
            ZillitSpinner(size = if (roomy) 16.dp else 14.dp, color = p.brand)
        } else {
            ZillitIcon(ZillitIcons.Upload, size = if (roomy) 16.dp else 14.dp, tint = p.muted)
        }
        ZillitText(text = label, style = DmType.sans(11.sp, FontWeight.SemiBold), color = p.label)
    }
}

/**
 * `BankAdditionalDetailsEditor`: rows of `{field, value, field_type}`. A new
 * row opens on its definition — type and title — and a titled row on its
 * value; the edit icon goes back. Keystrokes are filtered by type, and an
 * odd-looking value gets a red border once left, which never blocks a save.
 */
@Composable
internal fun AdditionalDetails(rows: List<JsonObject>, onChange: (JsonArray) -> Unit) {
    val p = cp
    // A row's mode once someone has chosen it; until then a titled row fills and an untitled one defines.
    val modes = remember { mutableStateMapOf<Int, Boolean>() }

    fun write(next: List<JsonObject>) = onChange(JsonArray(next))
    fun patch(index: Int, key: String, value: String) =
        write(rows.mapIndexed { i, row -> if (i == index) JsonObject(row + (key to JsonPrimitive(value))) else row })
    fun remove(index: Int) {
        val shifted = modes.filterKeys { it != index }.mapKeys { (key, _) -> if (key > index) key - 1 else key }
        modes.clear()
        modes.putAll(shifted)
        write(rows.filterIndexed { i, _ -> i != index })
    }

    Column(Modifier.padding(top = 16.dp)) {
        RowLabel("Additional Details")
        if (rows.isEmpty()) {
            ZillitText(
                text = "None.",
                style = DmType.sans(11.sp),
                color = p.muted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            rows.forEachIndexed { index, row ->
                val type = DocRead.text(row, "field_type") ?: "text"
                val title = DocRead.text(row, "field").orEmpty()
                val value = DocRead.text(row, "value").orEmpty()
                if (modes[index] ?: title.isBlank()) {
                    DefineRow(
                        type = type,
                        title = title,
                        onType = { patch(index, "field_type", it) },
                        onTitle = {
                            modes[index] = true
                            patch(index, "field", it)
                        },
                        onDone = { if (title.isNotBlank()) modes[index] = false },
                        onRemove = { remove(index) },
                    )
                } else {
                    FillRow(
                        type = type,
                        title = title,
                        value = value,
                        onValue = { if (CrewFormValues.allowsDetailInput(type, it)) patch(index, "value", it) },
                        onEdit = { modes[index] = true },
                        onRemove = { remove(index) },
                    )
                }
            }
        }
        DashedAddButton("Add detail") {
            modes[rows.size] = true
            write(
                rows + buildJsonObject {
                    put("field", "")
                    put("value", "")
                    put("field_type", "text")
                },
            )
        }
    }
}

private val DETAIL_TYPES =
    listOf("text" to "Text", "number" to "Number", "phone" to "Phone", "email" to "Email", "url" to "URL")

@Composable
private fun DefineRow(
    type: String,
    title: String,
    onType: (String) -> Unit,
    onTitle: (String) -> Unit,
    onDone: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormSelect(
            value = type,
            options = DETAIL_TYPES,
            onPick = onType,
            modifier = Modifier.widthIn(min = 104.dp).width(120.dp),
            menuWidth = 160.dp,
        )
        FormInput(
            value = title,
            onValueChange = onTitle,
            placeholder = "Field title (e.g. IBAN)",
            modifier = Modifier.weight(1f),
            onEnter = onDone,
        )
        if (title.isNotBlank()) DoneButton(onDone)
        IconSquare(ZillitIcons.Close, danger = true, description = "Remove", onClick = onRemove)
    }
}

@Composable
private fun FillRow(
    type: String,
    title: String,
    value: String,
    onValue: (String) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val p = cp
    var left by remember { mutableStateOf(false) }
    Column {
        ZillitText(
            text = title,
            style = DmType.sans(12.sp, FontWeight.Medium),
            color = p.label,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormInput(
                value = value,
                onValueChange = onValue,
                placeholder = "Value",
                modifier = Modifier.weight(1f),
                error = left && !CrewFormValues.detailLooksValid(type, value),
                onBlur = { left = true },
            )
            IconSquare(ZillitIcons.Edit, danger = false, description = "Edit field", onClick = onEdit)
            IconSquare(ZillitIcons.Close, danger = true, description = "Remove", onClick = onRemove)
        }
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Color(0xFFC87400) else Color(0xFFE08600))
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { ZillitText(text = "Done", style = DmType.sans(11.sp, FontWeight.Bold), color = Color.White) }
}

@Composable
private fun IconSquare(icon: ImageVector, danger: Boolean, description: String, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val (bg, tint) = when {
        !hovered -> Color.Transparent to Color(0xFFB8B7B1)
        danger -> Color(0xFFFDE7E7) to Color(0xFFE23B3B)
        else -> Color(0xFFFFF4EA) to Color(0xFFE08600)
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { ZillitIcon(icon, size = 13.dp, tint = tint, contentDescription = description) }
}

@Composable
private fun DashedAddButton(label: String, onClick: () -> Unit) {
    val p = cp
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered) p.hover else p.inputBg)
            .dashedBorder(p.pickerBorder, 10.dp)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(ZillitIcons.Add, size = 12.dp, tint = p.label)
        ZillitText(text = label, style = DmType.sans(11.5.sp, FontWeight.Bold), color = p.label)
    }
}

private const val PASSPORT_MAX = 2
private const val DISABLED = 0.6f
