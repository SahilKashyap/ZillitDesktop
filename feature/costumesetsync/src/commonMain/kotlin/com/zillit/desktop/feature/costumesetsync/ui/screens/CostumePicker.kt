package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import com.zillit.desktop.feature.costumesetsync.ui.SyncDialogShell
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.CostumeRow
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.rememberRows
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.feature.costumesetsync.domain.SCAN_ENABLED
import com.zillit.desktop.feature.costumesetsync.domain.decodeQrText
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.Notice
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PICK_PAGE_SIZE = 100
private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * Pick a costume out of the inventory — the web's `CostumePicker`. Searches
 * server-side like the list screen does, so a production with thousands of
 * pieces stays usable. [exclude] drops pieces that cannot be chosen for this job
 * (the cleaning sink hides anything already in CLEANING). [characterId] opens on
 * that character's pieces ("This character only" turns it off) and is the default
 * character of a piece added with New costume; the new piece is picked at once.
 * [onPick] gets the piece and the dialog closes. Scan QR is hidden, as on the web, until `SCAN_ENABLED` is on.
 */
@Composable
fun CostumePickerDialog(
    open: Boolean,
    onClose: () -> Unit,
    onPick: (Rec) -> Unit,
    modifier: Modifier = Modifier,
    characterId: String = "",
    exclude: (Rec) -> Boolean = { false },
    title: String? = null,
) {
    val ctx = LocalSync.current
    var q by remember(open) { mutableStateOf("") }
    var debouncedQ by remember(open) { mutableStateOf("") }
    var onlyCharacter by remember(open, characterId) { mutableStateOf(characterId.isNotBlank()) }
    var newOpen by remember { mutableStateOf(false) }
    var scanning by remember(open) { mutableStateOf(false) }
    LaunchedEffect(q) {
        delay(SEARCH_DEBOUNCE_MS)
        debouncedQ = q
    }
    val pick = { piece: Rec ->
        onPick(piece)
        onClose()
    }

    SyncDialogShell(
        title = title ?: t("csync_pick_costume"),
        onDismiss = onClose,
        visible = open,
        modifier = modifier,
        width = 640.dp,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchWithButton(q, { q = it }, t("csync_picker_search"), Modifier.weight(1f))
            if (characterId.isNotBlank()) ZillitCheckbox(
                onlyCharacter,
                { onlyCharacter = it },
                label = t("csync_this_character_only")
            )
            if (SCAN_ENABLED) ZillitButton(
                if (scanning) t("csync_back_to_list") else t("csync_scan_qr"),
                onClick = { scanning = !scanning },
                variant = ButtonVariant.Secondary,
                leadingIcon = AhIcons.QrCode,
            )
            if (ctx.canPost) ZillitButton(
                t("csync_new_costume"),
                onClick = { newOpen = true },
                leadingIcon = ZillitIcons.Add
            )
        }
        // Only mounted while open, so a closed picker never searches.
        if (open && scanning) {
            PickerScan(exclude, pick)
        } else if (open) {
            PickerList(debouncedQ, characterId.takeIf { onlyCharacter }.orEmpty(), exclude, pick)
        }
    }
    // A piece that is not in the inventory yet is added here and picked straight away.
    CostumeFormDialog(
        open = newOpen,
        onClose = { newOpen = false },
        defaultCharacterId = characterId,
        onCreated = { piece -> if (!exclude(piece)) pick(piece) },
    )
}

@Composable
private fun PickerList(q: String, characterId: String, exclude: (Rec) -> Boolean, onPick: (Rec) -> Unit) {
    val result = rememberRows(q, characterId) {
        api.get("/costumes", mapOf("q" to q, "page" to 1, "pageSize" to PICK_PAGE_SIZE, "characterId" to characterId))
    }
    Await(result) { rows ->
        val shown = rows.filterNot(exclude)
        if (shown.isEmpty()) {
            EmptyState(t("csync_costumes_empty_title"))
        } else {
            // `.csync-picker`: the list scrolls inside the dialog, capped at 46% of the window.
            val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = (windowHeight * 0.46f).coerceAtLeast(200.dp))
                    .verticalScroll(rememberScrollState())
            ) {
                shown.forEachIndexed { i, piece ->
                    CostumeRow(
                        piece,
                        onClick = { onPick(piece) },
                        end = { ZillitIcon(ZillitIcons.Add, size = 16.dp) },
                        last = i == shown.lastIndex
                    )
                }
            }
        }
    }
}

/** Why a typed or scanned label could not be picked, or null when [piece] can. */
private fun scanProblem(asset: String, piece: Rec?, exclude: (Rec) -> Boolean): String? = when {
    piece == null -> t("csync_scan_no_such_label", "asset" to asset)
    exclude(piece) -> t("csync_scan_cannot_add", "asset" to "${piece.str("asset_number")} ${piece.str("name")}")
    else -> null
}

/**
 * Scan QR inside the picker: read a label from a picture (the host's camera window, or a file) or type its asset
 * number; the piece is picked as soon as it is found — unless [exclude] says it cannot be chosen for this job.
 */
@Composable
private fun PickerScan(exclude: (Rec) -> Boolean, onPick: (Rec) -> Unit) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val find = { raw: String ->
        val asset = raw.trim().uppercase()
        if (asset.isNotEmpty()) {
            scope.launch {
                val piece = (ctx.api.get("/costumes/lookup/$asset") as? ZillitResult.Success)?.data?.rec
                error = scanProblem(asset, piece, exclude).orEmpty()
                if (piece != null && error.isEmpty()) onPick(piece)
            }
        }
        Unit
    }
    val read = { bytes: ByteArray ->
        scope.launch {
            val text = withContext(Dispatchers.Default) { decodeQrText(bytes) }
            if (text == null) error = t("csync_scan_no_code") else find(text)
        }
        Unit
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        MutedText(t("csync_scan_point_camera"), maxLines = 2)
        PictureButtons(read)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            val hint = t("csync_scan_type_asset")
            TextInput(typed, { typed = it }, t("csync_asset_number"), Modifier.weight(1f), placeholder = hint)
            ZillitButton(t("csync_scan_find"), onClick = { find(typed) }, enabled = typed.isNotBlank())
        }
        if (error.isNotBlank()) Notice { ZillitText(error, style = ZillitTheme.typography.bodyMedium) }
    }
}

/** Camera (when the host has one) and Read from a picture. */
@Composable
private fun PictureButtons(onPicture: (ByteArray) -> Unit) {
    val host = LocalSync.current.host
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        if (host.hasCamera) {
            ZillitButton(
                t("csync_camera"),
                onClick = { scope.launch { host.capturePhoto()?.let { onPicture(it.bytes) } } },
                variant = ButtonVariant.Secondary,
                leadingIcon = AhIcons.Camera,
            )
        }
        ZillitButton(
            t("csync_scan_from_picture"),
            onClick = {
                scope.launch { host.pick(PICTURES, false).firstOrNull()?.let { onPicture(it.bytes) } }
            },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Photo,
        )
    }
}

private val PICTURES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
