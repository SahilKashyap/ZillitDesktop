package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.AutoFillGrid
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FilterSelect
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.Page
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.PreviewDialog
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.enumOptions
import com.zillit.desktop.feature.costumesetsync.ui.isVideo
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.rememberRows
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.rememberStoredImage
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.delay

// The reference's type filter, in its order. Anything the service adds later (meta.photo_entity_types) follows these.
private val TYPES = listOf(
    "COSTUME",
    "CHANGE",
    "CHARACTER",
    "ACTOR",
    "FITTING",
    "CONTINUITY",
    "CLEANING",
    "DAMAGE",
    "ALTERATION",
    "MISSING"
)
private const val SEARCH_DEBOUNCE_MS = 300L

/** The gallery is pictures and clips; files and links stay on their record. */
private fun Rec.isGalleryMedia(): Boolean = str("media_type").let { it.isBlank() || it == "IMAGE" } || isVideo()

/**
 * Every photo and video in the production, filterable by what it shows — the reference app's
 * `pages/Gallery.tsx`. A tile opens a preview whose Open goes to the record it is attached to
 * (the service's `link`, relative to the tool root).
 */
@Suppress("LongMethod")
@Composable
fun GalleryScreen() {
    val ctx = LocalSync.current
    var q by remember { mutableStateOf("") }
    var debouncedQ by remember { mutableStateOf("") }
    var entityType by remember { mutableStateOf("") }
    var characterId by remember { mutableStateOf("") }
    var sceneId by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Rec?>(null) }
    LaunchedEffect(q) {
        delay(SEARCH_DEBOUNCE_MS)
        debouncedQ = q
    }
    val characters = rememberResource { api.get("/characters").mapRows() }
    val scenes = rememberResource { api.get("/scenes").mapRows() }
    val gallery = rememberRows(entityType, characterId, sceneId, debouncedQ) {
        api.get(
            "/photos/gallery",
            mapOf("entityType" to entityType, "characterId" to characterId, "sceneId" to sceneId, "q" to debouncedQ)
        )
    }
    SocketRefresh(SyncEvents.Photo) { gallery.reload(silent = true) }

    val count = gallery.value?.count { it.isGalleryMedia() }
    Page {
        PageHead(
            title = t("csync_gallery_title"),
            sub = when (count) {
                null -> t("csync_gallery_sub_all")
                1 -> t("csync_gallery_sub_one", "n" to count)
                else -> t("csync_gallery_sub", "n" to count)
            },
            bottomPadding = 0.dp,
        )
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchWithButton(q, { q = it }, t("csync_gallery_search"), Modifier.width(SEARCH_WIDTH))
            val types = TYPES + ctx.metaList("photo_entity_types").filter { it !in TYPES }
            FilterSelect(entityType, enumOptions(types), t("csync_gallery_any_type"), { entityType = it }, FILTER)
            FilterSelect(
                characterId,
                characters.value.orEmpty().map { it.id to it.str("name") },
                t("csync_costumes_any_character"),
                { characterId = it },
                FILTER
            )
            FilterSelect(
                sceneId,
                scenes.value.orEmpty().map { it.id to "${t("csync_sc")} ${it.str("number")}" },
                t("csync_gallery_any_scene"),
                { sceneId = it },
                FILTER
            )
        }
        SectionCard(Modifier.fillMaxWidth()) {
            Await(gallery) { rows ->
                val items = rows.filter { it.isGalleryMedia() }
                if (items.isEmpty()) {
                    EmptyState(t("csync_gallery_empty_title"), t("csync_gallery_empty_hint"))
                } else {
                    // `.csync-gallery`: auto-fill columns of at least 160, 8 apart, square tiles.
                    AutoFillGrid(items.size, TILE_MIN, 8.dp, stretch = false) { i, cell ->
                        GalleryTile(items[i], cell) { preview = items[i] }
                    }
                }
            }
        }
    }
    PreviewDialog(preview, onClose = { preview = null }, onOpenRecord = { link -> ctx.nav.go(link) })
}

@Composable
private fun GalleryTile(item: Rec, cell: Modifier, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val bitmap = rememberStoredImage(item)
    val label = item.str("label").ifBlank { tEnum(item.str("entity_type")) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        cell
            .aspectRatio(1f)
            .clip(shape)
            .background(colors.surfaceHover)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) Image(
            bitmap,
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (item.isVideo()) {
            // `.csync-ref__play`: a dark round plate with a white triangle, centred.
            Box(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(8.dp)) {
                ZillitIcon(ZillitIcons.Play, tint = Color.White, size = 16.dp)
            }
        }
        if (item.str("kind").isNotBlank()) {
            // `.csync-gallery__kind`: top-left, 10 bold upper-case, white on a dark chip.
            ZillitText(
                tEnum(item.str("kind")).uppercase(),
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White,
                maxLines = 1,
            )
        }
        // `.csync-gallery__label`: 11 semibold white over a fade to dark at the foot.
        ZillitText(
            label,
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
        )
    }
}

private val SEARCH_WIDTH = 340.dp
private val FILTER = Modifier.width(150.dp)
private val TILE_MIN = 160.dp
