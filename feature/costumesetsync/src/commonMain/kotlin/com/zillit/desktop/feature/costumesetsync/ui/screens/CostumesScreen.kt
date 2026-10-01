package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.CostumeRow
import com.zillit.desktop.feature.costumesetsync.ui.LIST_MAX_VIEWPORT
import com.zillit.desktop.feature.costumesetsync.ui.Page
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FilterSelect
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.Pager
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.enumOptions
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.delay

private const val PAGE_SIZE = 50

/**
 * Inventory list: server-side search, the five filters the service supports,
 * and paging. Filter state lives in the route's query (`costumes?status=X`),
 * so a filtered list can be linked to — the Dashboard's status chips do.
 */
@Composable
fun CostumesScreen() {
    val ctx = LocalSync.current
    val route = ctx.nav.current
    val status = route.arg("status")
    val category = route.arg("category")
    val characterId = route.arg("characterId")
    val source = route.arg("source")
    val page = route.arg("page").toIntOrNull() ?: 1

    var q by remember { mutableStateOf(route.arg("q")) }
    var debouncedQ by remember { mutableStateOf(q) }
    // `?new=1` (the Dashboard's + Costume) opens the form on arrival.
    var createOpen by remember { mutableStateOf(route.arg("new") == "1") }

    // Typing shouldn't fire a request per keystroke.
    LaunchedEffect(q) {
        delay(300)
        debouncedQ = q
    }

    val characters = rememberResource { api.get("/characters").mapRows() }
    val result = rememberResource(debouncedQ, status, category, characterId, source, page) {
        api.get(
            "/costumes",
            mapOf("q" to debouncedQ, "status" to status, "category" to category, "characterId" to characterId, "source" to source, "page" to page, "pageSize" to PAGE_SIZE),
        )
    }
    SocketRefresh(SyncEvents.Costume) { result.reload(silent = true) }

    val loaded = result.value
    val total = loaded?.rec?.long("total")?.toInt() ?: loaded?.rows?.size ?: 0
    val pageSize = loaded?.rec?.long("page_size")?.toInt()?.takeIf { it > 0 } ?: PAGE_SIZE
    val pages = maxOf(1, (total + pageSize - 1) / pageSize)
    // The header and filters stay put while the list reloads, so typing keeps its focus.
    Page {
        PageHead(
            title = t("csync_costumes_title"),
            sub = if (loaded != null) t("csync_costumes_count", "count" to total) else t("csync_inventory"),
            actions = { if (ctx.canPost) ZillitButton(t("csync_costume"), onClick = { createOpen = true }, leadingIcon = ZillitIcons.Add) },
            bottomPadding = 0.dp,
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchWithButton(q, { q = it; ctx.nav.setQuery("q", it) }, t("csync_costumes_search_placeholder"), Modifier.width(SEARCH_WIDTH))
            FilterSelect(status, enumOptions(ctx.metaList("costume_statuses")), t("csync_costumes_any_status"), { ctx.nav.setQuery("status", it); ctx.nav.setQuery("page", null) }, FILTER)
            FilterSelect(category, enumOptions(ctx.metaList("costume_categories")), t("csync_costumes_any_category"), { ctx.nav.setQuery("category", it); ctx.nav.setQuery("page", null) }, FILTER)
            FilterSelect(
                characterId,
                characters.value.orEmpty().map { it.id to it.str("name") },
                t("csync_costumes_any_character"),
                { ctx.nav.setQuery("characterId", it); ctx.nav.setQuery("page", null) },
                FILTER,
            )
            FilterSelect(source, enumOptions(ctx.metaList("costume_sources")), t("csync_costumes_any_source"), { ctx.nav.setQuery("source", it); ctx.nav.setQuery("page", null) }, FILTER)
        }
        SectionCard(flush = true, modifier = Modifier.fillMaxWidth()) {
            Await(result) { answer ->
                val items = answer.rows
                if (items.isEmpty()) {
                    EmptyState(t("csync_costumes_empty_title"), t("csync_costumes_empty_hint"))
                } else {
                    // `.csync-rows`: the list scrolls inside its card (62vh) so the pager stays in view.
                    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
                    val cap = (windowHeight * LIST_MAX_VIEWPORT).coerceAtLeast(MIN_LIST_HEIGHT)
                    Column(Modifier.heightIn(max = cap).verticalScroll(rememberScrollState())) {
                        items.forEachIndexed { i, c -> CostumeRow(c, onClick = { ctx.nav.go("costumes/${c.id}") }, last = i == items.lastIndex) }
                    }
                }
            }
            Pager(page, pages, onPage = { ctx.nav.setQuery("page", it.toString()) })
        }
    }
    CostumeFormDialog(
        open = createOpen,
        onClose = { createOpen = false; if (route.arg("new").isNotEmpty()) ctx.nav.setQuery("new", null) },
        onSaved = { result.reload(silent = true) },
        defaultCharacterId = characterId,
    )
}

// antd's `Input.Search` here is `flex: 1 1 260px; max-width: 340px`; each Select holds `min-width: 150px`.
private val SEARCH_WIDTH = 340.dp
private val FILTER = Modifier.width(150.dp)
private val MIN_LIST_HEIGHT = 240.dp
