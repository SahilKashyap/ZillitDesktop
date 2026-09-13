package com.zillit.desktop.feature.maps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.maps.domain.BoundaryMode
import com.zillit.desktop.feature.maps.domain.BoundaryStatus
import com.zillit.desktop.feature.maps.domain.CenterPointType
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.MapAttachment
import com.zillit.desktop.feature.maps.domain.PickedPhoto
import com.zillit.desktop.feature.maps.domain.PlacePrediction
import com.zillit.desktop.feature.maps.domain.RouteInfo
import com.zillit.desktop.feature.maps.domain.SharePerson
import com.zillit.desktop.feature.maps.domain.buildBoundaryPrompt
import com.zillit.desktop.feature.maps.domain.buildLocationShare
import com.zillit.desktop.feature.maps.ui.AddCityState
import com.zillit.desktop.feature.maps.ui.AddressPick
import com.zillit.desktop.feature.maps.ui.ConfirmAction
import com.zillit.desktop.feature.maps.ui.DirectionsState
import com.zillit.desktop.feature.maps.ui.ListViewState
import com.zillit.desktop.feature.maps.ui.LocationFormState
import com.zillit.desktop.feature.maps.ui.MapDialog
import com.zillit.desktop.feature.maps.ui.MapPanel
import com.zillit.desktop.feature.maps.ui.MapScreen
import com.zillit.desktop.feature.maps.ui.MapUiState
import com.zillit.desktop.feature.maps.ui.NewPhoto
import com.zillit.desktop.feature.maps.ui.NoticeTone
import com.zillit.desktop.feature.maps.ui.RouteEnd
import com.zillit.desktop.feature.maps.ui.SearchState
import com.zillit.desktop.feature.maps.ui.ShareState
import com.zillit.desktop.feature.maps.ui.TypeFormState
import com.zillit.desktop.feature.maps.ui.TypesPanelState
import com.zillit.desktop.feature.maps.ui.ZoneFormState
import com.zillit.desktop.feature.maps.ui.screen.LocalMapImages
import com.zillit.desktop.feature.maps.ui.screen.MapImages
import com.zillit.desktop.feature.maps.ui.screen.MapToast
import java.io.File
import kotlin.test.Test

/** TEMPORARY — renders the map tool's screens to PNGs for a visual check. Delete after. */
class MapShotsTmp {

    private val out = File("/private/tmp/claude-501/-Users-sahilkashyap-AndroidStudioProjects-Zillit/9da1d5a4-3b94-4f2d-87be-b28e5b9e1e42/scratchpad/shots")

    private fun picture(seed: Int): ImageBitmap {
        val bitmap = ImageBitmap(320, 240)
        val scope = CanvasDrawScope()
        val palette = listOf(Color(0xFF355C7D), Color(0xFF6C5B7B), Color(0xFFC06C84), Color(0xFFF67280), Color(0xFF2A9D8F))
        scope.draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), androidx.compose.ui.geometry.Size(320f, 240f)) {
            drawRect(Brush.linearGradient(listOf(palette[seed % 5], palette[(seed + 2) % 5]), Offset.Zero, Offset(320f, 240f)))
        }
        return bitmap
    }

    private val images = object : MapImages {
        override suspend fun photo(attachment: MapAttachment, preview: Boolean) = picture(attachment.name.length)
        override suspend fun zonePreview(centre: LatLng, radiusMiles: Double) = picture(3)
        override fun decode(photo: PickedPhoto) = picture(photo.name.length + 1)
    }

    /** Stands in for the Google map: a pale street grid. */
    private val fakeMap: @Composable (Boolean) -> Unit = { visible ->
        if (visible) {
            Canvas(Modifier.fillMaxSize().clipToBounds()) {
                drawRect(Color(0xFFE8ECE9))
                var x = 0f
                while (x < size.width) {
                    drawLine(Color.White, Offset(x, 0f), Offset(x + 180f, size.height), strokeWidth = 10f)
                    x += 260f
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(Color.White, Offset(0f, y), Offset(size.width, y - 90f), strokeWidth = 7f)
                    y += 190f
                }
                drawCircle(Color(0x332E86C1), radius = 260f, center = Offset(size.width * 0.52f, size.height * 0.48f))
            }
        }
    }

    private fun shot(
        name: String,
        state: MapUiState,
        dark: Boolean = false,
        widthDp: Int = 1440,
        heightDp: Int = 860,
        canvas: (@Composable (Boolean) -> Unit)? = fakeMap,
        toasts: List<MapToast> = emptyList(),
    ) {
        out.mkdirs()
        val scene = ImageComposeScene(width = widthDp * 2, height = heightDp * 2, density = Density(2f)) {
            ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                CompositionLocalProvider(LocalMapImages provides images) {
                    MapScreen(state = state, onEvent = {}, canvas = canvas, toasts = toasts)
                }
            }
        }
        scene.render(0L)
        scene.render(300_000_000L)
        scene.render(1_000_000_000L)
        val image = scene.render(2_000_000_000L)
        File(out, "$name.png").writeBytes(image.encodeToData()!!.bytes)
        scene.close()
    }

    private val base = MapFixtures.base
    private val l1 = MapFixtures.locations[0]

    private val locationForm = LocationFormState(
        editId = "l1",
        cityId = "mumbai",
        name = l1.name,
        type = "Hotel",
        subTypes = listOf("5 star"),
        description = l1.description,
        address = l1.address,
        point = l1.point,
        existing = l1.attachments,
        added = listOf(NewPhoto("n1", PickedPhoto("new-room.jpg", "image/jpeg", ByteArray(1)))),
    )

    private val zoneForm = ZoneFormState(
        cityId = "mumbai",
        name = "MG Road & Link Road Zone",
        point = LatLng(19.1646, 72.8493),
        useCustom = true,
        customRadius = "",
        customTouched = true,
        mode = CenterPointType.Intersection,
        street1 = "MG Road",
        street1Picked = true,
        street2 = "Link Road",
        street2Picked = true,
        intersection = "MG Rd & Link Rd, Goregaon West, Mumbai, Maharashtra 400104",
    )

    @Test
    fun shots() {
        shot("01-main", base)
        shot("02-cities", base.copy(panels = listOf(MapPanel.Cities)))
        shot("03-location-form", base.copy(panels = listOf(MapPanel.LocationForm), locationForm = locationForm))
        shot("04-zone-list", base.copy(panels = listOf(MapPanel.ZoneList)))
        shot("05-zone-form", base.copy(panels = listOf(MapPanel.ZoneList, MapPanel.ZoneForm), zoneForm = zoneForm))
        shot(
            "06-types",
            base.copy(
                panels = listOf(MapPanel.Types),
                typesPanel = TypesPanelState(
                    form = TypeFormState(name = "Hotel", icon = "🏨", subTypes = listOf("5 star", "Budget"), newSubType = "Boutique"),
                ),
            ),
        )
        shot("07-list-view", base.copy(listView = ListViewState(), panels = listOf(MapPanel.LocationDetail("l1"))))
        shot("08-pin-search", base.copy(pinMode = true, search = SearchState("ma")))
        shot("09-filter", base.copy(filterOpen = true, typeFilters = setOf("Hotel", "Shooting")))
        shot(
            "10-directions",
            base.copy(
                directions = DirectionsState(
                    pickup = RouteEnd(LatLng(19.05, 72.83), "Bandra West, Mumbai, Maharashtra"),
                    drop = RouteEnd(LatLng(18.9431, 72.823), "Marine Dr, Churchgate, Mumbai 400020", "Marine Drive Promenade"),
                    pickupText = "Bandra West, Mumbai, Maharashtra",
                    dropText = "Marine Dr, Churchgate, Mumbai 400020",
                    route = RouteInfo("14.2 km", 14_200.0, "38 mins"),
                ),
            ),
        )
        val outside = buildBoundaryPrompt(BoundaryStatus.OutsideCity, BoundaryMode.Add, cityName = "Mumbai", address = "Lonavala, Maharashtra 410401")!!
        shot("11-boundary", base.copy(dialog = MapDialog.Boundary(outside)))
        shot(
            "12-add-city",
            base.copy(
                panels = listOf(MapPanel.Cities),
                dialog = MapDialog.AddCity(
                    AddCityState(
                        query = "Hyder",
                        suggestions = listOf(
                            PlacePrediction("p1", "Hyderabad, Telangana, India", "Hyderabad", "Telangana, India"),
                            PlacePrediction("p2", "Hyderabad, Sindh, Pakistan", "Hyderabad", "Sindh, Pakistan"),
                        ),
                    ),
                ),
            ),
        )
        val share = buildLocationShare(l1)!!
        shot(
            "13-share",
            base.copy(
                dialog = MapDialog.Share(
                    ShareState(
                        title = share.title,
                        text = share.text,
                        url = share.url,
                        people = listOf(
                            SharePerson("u2", "Amy Rao", "Location Manager"),
                            SharePerson("u3", "Dev Patel", "Unit Manager"),
                            SharePerson("u4", "Zed Khan", "Driver"),
                        ),
                        selected = setOf("u3"),
                    ),
                ),
            ),
        )
        shot(
            "14-confirm",
            base.copy(
                dialog = MapDialog.Confirm(
                    title = "Delete City",
                    message = "Are you sure you want to delete \"Mumbai\"? This will also delete all associated locations.",
                    confirmLabel = "Delete",
                    danger = true,
                    action = ConfirmAction.DeleteCity("mumbai"),
                ),
            ),
        )
        shot(
            "15-new-type",
            base.copy(
                panels = listOf(MapPanel.LocationForm),
                locationForm = locationForm.copy(editId = null),
                dialog = MapDialog.NewType(TypeFormState(name = "Warehouse", subTypes = listOf("Props"), iconPickerOpen = true)),
            ),
        )
        shot("16-empty", MapUiState(viewer = MapFixtures.viewer, citiesLoaded = true), canvas = null)
        shot("17-dark-main", base.copy(panels = listOf(MapPanel.Cities)), dark = true)
        shot("18-dark-form", base.copy(panels = listOf(MapPanel.LocationForm), locationForm = locationForm), dark = true)
        shot("19-dark-boundary", base.copy(dialog = MapDialog.Boundary(outside)), dark = true)
        shot("20-narrow-zone-form", base.copy(panels = listOf(MapPanel.ZoneForm), zoneForm = zoneForm), widthDp = 1100, heightDp = 720)
        shot("21-blocked", base.copy(viewer = MapFixtures.viewer.copy(canView = false)))
        shot("22-toast", base, toasts = listOf(MapToast(1, "Location created successfully", NoticeTone.Success)))
        shot("23-zone-detail", base.copy(panels = listOf(MapPanel.ZoneList, MapPanel.ZoneDetail("z2"))))
        shot(
            "24-address-outside",
            base.copy(
                panels = listOf(MapPanel.LocationForm),
                locationForm = locationForm,
                dialog = MapDialog.AddressOutside("Mumbai", AddressPick("Lonavala", "Lonavala, Maharashtra 410401", LatLng(18.75, 73.4))),
            ),
        )
        shot("25-photo", base.copy(listView = ListViewState(), dialog = MapDialog.Photo(l1.attachments[0], null)))
        shot("26-dark-list", base.copy(listView = ListViewState(), panels = listOf(MapPanel.LocationDetail("l3"))), dark = true)
        shot("27-dark-zone-form", base.copy(panels = listOf(MapPanel.ZoneForm), zoneForm = zoneForm.copy(mode = CenterPointType.Point, useCustom = false)), dark = true)
    }
}
