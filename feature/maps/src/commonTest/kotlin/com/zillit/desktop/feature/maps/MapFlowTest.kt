package com.zillit.desktop.feature.maps

import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequest
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.feature.maps.domain.BoundaryChoice
import com.zillit.desktop.feature.maps.domain.CityDraft
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.LocationDraft
import com.zillit.desktop.feature.maps.domain.LocationType
import com.zillit.desktop.feature.maps.domain.MapAttachment
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapHost
import com.zillit.desktop.feature.maps.domain.MapLocator
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.MapPrefs
import com.zillit.desktop.feature.maps.domain.MapSyncEvent
import com.zillit.desktop.feature.maps.domain.MapViewer
import com.zillit.desktop.feature.maps.domain.PickedPhoto
import com.zillit.desktop.feature.maps.domain.PlacePrediction
import com.zillit.desktop.feature.maps.domain.ScenePin
import com.zillit.desktop.feature.maps.domain.SharePerson
import com.zillit.desktop.feature.maps.domain.TypeDraft
import com.zillit.desktop.feature.maps.domain.ZoneDraft
import com.zillit.desktop.feature.maps.domain.buildLocationShare
import com.zillit.desktop.feature.maps.ui.ConfirmAction
import com.zillit.desktop.feature.maps.ui.MapDialog
import com.zillit.desktop.feature.maps.ui.MapEffect
import com.zillit.desktop.feature.maps.ui.MapEvent
import com.zillit.desktop.feature.maps.ui.MapPanel
import com.zillit.desktop.feature.maps.ui.MapViewModel
import com.zillit.desktop.feature.maps.ui.NoticeTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The map tool driven through its view model — the web's flows end to end,
 * against a service and a map page that answer at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapFlowTest {

    private val dispatcher = StandardTestDispatcher()
    private val jobs = mutableListOf<Job>()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() {
        jobs.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    private val mumbai = MapCity(id = "mumbai", name = "Mumbai", coordinates = LatLng(19.0, 72.8), radiusMiles = 15.0)
    private val pune = MapCity(id = "pune", name = "Pune", coordinates = LatLng(18.52, 73.85))
    private val goa = MapCity(id = "goa", name = "Goa", coordinates = LatLng(15.3, 74.1))

    /** A point [miles] north of Mumbai's centre (~69 miles to a degree of latitude). */
    private fun north(miles: Double) = LatLng(19.0 + miles / 69, 72.8)

    private fun zone(id: String, cityId: String = "mumbai", miles: Double = 2.0) = MapLocation(
        id = id,
        cityId = cityId,
        name = "Zone $id",
        point = LatLng(19.0, 72.8),
        isStudioZone = true,
        miles = miles,
    )

    private val baseCamp = MapLocation(
        id = "l1",
        cityId = "mumbai",
        name = "Base Camp",
        type = "Base Camp",
        subTypes = listOf("Tents"),
        description = "North gate",
        address = "Film City Rd",
        sceneNumber = "12A",
        point = north(1.0),
        attachments = listOf(MapAttachment(media = "map/1/a.jpg", bucket = "b", region = "r", name = "a.jpg")),
    )

    private val hotel = baseCamp.copy(
        id = "l2",
        name = "Sea View",
        type = "Hotel",
        subTypes = emptyList(),
        attachments = emptyList(),
    )

    private inner class Tool(
        val repo: FakeMapRepository,
        viewer: MapViewer = MapViewer(userId = "u1", canPost = true, ready = true),
        val prefs: MapPrefs = MapPrefs.InMemory(),
        val photos: FakePhotos = FakePhotos(),
        share: FakeShare? = null,
        rights: RightsRequestBus? = null,
        locator: MapLocator? = null,
    ) {
        val canvas = FakeCanvasHost()
        val effects = mutableListOf<MapEffect>()
        val vm = MapViewModel(
            repo,
            { viewer },
            canvas,
            MapHost(photos = photos, share = share, prefs = prefs, locator = locator),
            rights,
        )

        val state get() = vm.currentState
        val notices: List<String> get() = effects.filterIsInstance<MapEffect.Notice>().map { it.message }

        init {
            jobs += CoroutineScope(dispatcher).launch { vm.effects.collect(effects::add) }
        }
    }

    private fun TestScope.open(tool: Tool): Tool {
        tool.vm.start()
        advanceUntilIdle()
        return tool
    }

    private fun TestScope.send(tool: Tool, vararg events: MapEvent) {
        events.forEach {
            tool.vm.onEvent(it)
            advanceUntilIdle()
        }
    }

    /** A message from the map page, as the browser would send it. */
    private suspend fun TestScope.page(tool: Tool, json: String) {
        tool.canvas.messages.emit(json)
        advanceUntilIdle()
    }

    private suspend fun TestScope.previewAdd(tool: Tool, point: LatLng, name: String = "Spot") =
        page(
            tool,
            """{"type":"preview-action","action":"add","lat":${point.lat},""" +
                """"lng":${point.lng},"name":"$name","address":""}""",
        )

    private fun photo(name: String) = PickedPhoto(name, "image/jpeg", byteArrayOf(1, 2, 3))

    // Opening ------------------------------------------------------------------

    @Test
    fun `the map reopens on the last city and draws the zone chosen there`() = runTest(dispatcher) {
        val repo = FakeMapRepository(
            cityList = mutableListOf(mumbai, pune, goa),
            zonesByCity = mutableMapOf("pune" to listOf(zone("za", "pune"), zone("zb", "pune"))),
        )
        val prefs = MapPrefs.InMemory().apply {
            setLastVisitedCityId("pune")
            setActiveZoneId("pune", "zb")
        }
        val tool = open(Tool(repo, prefs = prefs))

        assertEquals("pune", tool.state.selectedCityId)
        assertEquals(listOf("pune"), repo.locationCalls.distinct())
        assertEquals("zb", tool.state.activeZoneId)
        assertEquals("pune", prefs.lastVisitedCityId())
        assertTrue(tool.canvas.scripts.any { it.startsWith("zillitMap.camera(") && "fitCircle" in it })
    }

    @Test
    fun `a remembered zone that is gone falls to the first, which is not remembered`() = runTest(dispatcher) {
        val repo = FakeMapRepository(
            cityList = mutableListOf(mumbai),
            zonesByCity = mutableMapOf("mumbai" to listOf(zone("za"), zone("zb"))),
        )
        val prefs = MapPrefs.InMemory().apply { setActiveZoneId("mumbai", "deleted") }
        val tool = open(Tool(repo, prefs = prefs))

        assertEquals("mumbai", tool.state.selectedCityId)
        assertEquals("za", tool.state.activeZoneId)
        assertEquals("deleted", prefs.activeZoneId("mumbai"))
    }

    // Pinning inside and outside -----------------------------------------------

    @Test
    fun `a pin inside the active zone opens the form at once, with the address filled in`() = runTest(dispatcher) {
        val repo = FakeMapRepository(
            cityList = mutableListOf(mumbai),
            zonesByCity = mutableMapOf("mumbai" to listOf(zone("za"))),
        )
        val tool = open(Tool(repo))

        previewAdd(tool, north(1.0))

        assertNull(tool.state.dialog)
        val form = assertNotNull(tool.state.locationForm)
        assertEquals(north(1.0), form.point)
        assertEquals("Spot", form.name)
        assertEquals(tool.canvas.geocodedAddress, form.address)
        assertEquals(MapPanel.LocationForm, tool.state.topPanel)
    }

    @Test
    fun `outside the zone asks first, and only Yes pins`() = runTest(dispatcher) {
        val repo = FakeMapRepository(
            cityList = mutableListOf(mumbai),
            zonesByCity = mutableMapOf("mumbai" to listOf(zone("za"))),
        )
        val tool = open(Tool(repo))

        previewAdd(tool, north(6.0))
        val asked = assertIs<MapDialog.Boundary>(tool.state.dialog).prompt
        assertEquals("Outside Studio Zone", asked.title)
        assertEquals("Location is outside the Zone za. Do you want to pin the location?", asked.message)
        assertEquals(tool.canvas.geocodedAddress, asked.address)

        send(tool, MapEvent.Dialogs.Boundary(BoundaryChoice.Cancel))
        assertNull(tool.state.dialog)
        assertNull(tool.state.locationForm)

        previewAdd(tool, north(6.0))
        // Closing the dialog any other way is a No too.
        send(tool, MapEvent.Dialogs.Dismiss)
        assertNull(tool.state.locationForm)

        previewAdd(tool, north(6.0))
        send(tool, MapEvent.Dialogs.Boundary(BoundaryChoice.Confirm))
        assertEquals(north(6.0), tool.state.locationForm?.point)
    }

    @Test
    fun `outside the city, Create Another City pins there once the city exists`() = runTest(dispatcher) {
        val repo = FakeMapRepository(cityList = mutableListOf(mumbai))
        val tool = open(Tool(repo))
        val far = north(60.0)

        previewAdd(tool, far)
        val asked = assertIs<MapDialog.Boundary>(tool.state.dialog).prompt
        assertEquals("Outside Mumbai", asked.title)
        assertTrue(asked.stacked)

        send(tool, MapEvent.Dialogs.Boundary(BoundaryChoice.CreateCity))
        val add = assertIs<MapDialog.AddCity>(tool.state.dialog).state
        assertEquals("Andheri West", add.name)
        assertEquals(tool.canvas.geocodedAddress, add.description)
        assertEquals(far, add.point)
        assertTrue(add.prefilled)

        send(tool, MapEvent.Cities.AddSave)

        assertEquals(CityDraft("Andheri West", tool.canvas.geocodedAddress, far), repo.createdCities.single())
        assertEquals("c2", tool.state.selectedCityId)
        val form = assertNotNull(tool.state.locationForm)
        assertEquals("c2", form.cityId)
        assertEquals(far, form.point)
        assertEquals("Andheri West", form.name)
        assertNull(tool.state.dialog)
        assertTrue("City Added Successfully" in tool.notices)
    }

    @Test
    fun `leaving Add City forgets the waiting pin`() = runTest(dispatcher) {
        val repo = FakeMapRepository(cityList = mutableListOf(mumbai))
        val tool = open(Tool(repo))

        previewAdd(tool, north(60.0))
        send(tool, MapEvent.Dialogs.Boundary(BoundaryChoice.CreateCity), MapEvent.Dialogs.Dismiss)
        assertNull(tool.state.dialog)

        send(
            tool,
            MapEvent.Cities.Add,
            MapEvent.Cities.AddPick(PlacePrediction("p1", "Picked Place", "Picked Place")),
            MapEvent.Cities.AddSave,
        )

        assertEquals("Picked Place", repo.createdCities.single().name)
        assertEquals("c2", tool.state.selectedCityId)
        assertNull(tool.state.locationForm)
    }

    @Test
    fun `Add City refuses a name already taken, in the web's words`() = runTest(dispatcher) {
        val tool = open(Tool(FakeMapRepository(cityList = mutableListOf(mumbai))))
        tool.canvas.placeName = "mumbai "

        send(tool, MapEvent.Cities.Add, MapEvent.Cities.AddSave)
        assertEquals("Please enter a city name", tool.notices.last())

        send(tool, MapEvent.Cities.AddPick(PlacePrediction("p1", "Mumbai", "Mumbai")), MapEvent.Cities.AddSave)
        assertEquals("\"mumbai\" already exists. Please use a different city name.", tool.notices.last())
        assertTrue(tool.repo.createdCities.isEmpty())
    }

    // Moving a saved pin ---------------------------------------------------------

    @Test
    fun `a dropped pin asks, and Move sends the whole record with the new point and address`() = runTest(dispatcher) {
        val repo = FakeMapRepository(
            cityList = mutableListOf(mumbai),
            locationsByCity = mutableMapOf("mumbai" to listOf(baseCamp)),
        )
        val tool = open(Tool(repo))
        val dropped = north(3.0)

        page(tool, """{"type":"marker-dragged","id":"l1","lat":${dropped.lat},"lng":${dropped.lng}}""")
        val asked = assertIs<MapDialog.Boundary>(tool.state.dialog).prompt
        assertEquals("Move Location", asked.title)
        assertEquals("Move 'Base Camp' to the new position?", asked.message)
        assertEquals(ScenePin("l1", dropped), tool.state.pendingMove)

        send(tool, MapEvent.Dialogs.Boundary(BoundaryChoice.Confirm))

        val (id, draft) = repo.updatedLocations.single()
        assertEquals("l1", id)
        assertEquals(
            LocationDraft(
                cityId = "mumbai",
                name = "Base Camp",
                type = "Base Camp",
                subTypes = listOf("Tents"),
                description = "North gate",
                address = tool.canvas.geocodedAddress,
                sceneNumber = "12A",
                point = dropped,
                attachments = baseCamp.attachments,
            ),
            draft,
        )
        assertNull(tool.state.pendingMove)
        assertNull(tool.state.movingId)
        assertTrue("'Base Camp' moved" in tool.notices)
    }

    @Test
    fun `a cancelled or refused move sends the pin home`() = runTest(dispatcher) {
        val repo = FakeMapRepository(
            cityList = mutableListOf(mumbai),
            locationsByCity = mutableMapOf("mumbai" to listOf(baseCamp)),
        )
        val tool = open(Tool(repo))
        val dropped = north(3.0)
        val drag = """{"type":"marker-dragged","id":"l1","lat":${dropped.lat},"lng":${dropped.lng}}"""

        page(tool, drag)
        send(tool, MapEvent.Dialogs.Boundary(BoundaryChoice.Cancel))
        assertTrue(repo.updatedLocations.isEmpty())
        assertNull(tool.state.pendingMove)
        assertEquals(1, tool.state.snapNonce)

        repo.failWrites = true
        page(tool, drag)
        send(tool, MapEvent.Dialogs.Boundary(BoundaryChoice.Confirm))
        assertEquals(1, repo.updatedLocations.size)
        assertNull(tool.state.pendingMove)
        assertNull(tool.state.movingId)
        assertEquals(2, tool.state.snapNonce)
        assertEquals(north(1.0), tool.state.location("l1")?.point)
    }

    // The location form ------------------------------------------------------------

    @Test
    fun `the location form refuses in order, then stores its photos and saves`() = runTest(dispatcher) {
        val repo = FakeMapRepository(cityList = mutableListOf(mumbai))
        val tool = open(Tool(repo))
        tool.canvas.placeAt = north(2.0)

        send(tool, MapEvent.LocationForm.New, MapEvent.LocationForm.Save)
        assertEquals("Please fill in required fields", tool.notices.last())

        send(tool, MapEvent.LocationForm.Name("Unit Base"), MapEvent.LocationForm.Save)
        assertEquals("Please enter an address or search for a location", tool.notices.last())

        send(tool, MapEvent.LocationForm.AddressText("1 Pic"))
        assertEquals(1, tool.state.locationForm?.addressSuggestions?.size)
        send(
            tool,
            MapEvent.LocationForm.AddressPick(PlacePrediction("p1", "1 Picked Rd", "1 Picked Rd")),
            MapEvent.LocationForm.PickType("Hotel"),
            MapEvent.LocationForm.ToggleSubType("5 star"),
            MapEvent.LocationForm.PhotosDropped(listOf(photo("a.jpg"), photo("b.jpg")), refused = emptyList()),
            MapEvent.LocationForm.Save,
        )

        assertEquals(listOf("a.jpg", "b.jpg"), tool.photos.uploaded)
        val draft = repo.createdLocations.single()
        assertEquals("mumbai", draft.cityId)
        assertEquals("Unit Base", draft.name)
        assertEquals("Hotel", draft.type)
        assertEquals(listOf("5 star"), draft.subTypes)
        assertEquals("1 Picked Rd", draft.address)
        assertEquals(north(2.0), draft.point)
        assertEquals(listOf("map/a.jpg", "map/b.jpg"), draft.attachments.map { it.media })
        assertNull(tool.state.locationForm)
        assertTrue("Location created successfully" in tool.notices)
    }

    @Test
    fun `an address outside the city asks before it moves the pin`() = runTest(dispatcher) {
        val tool = open(Tool(FakeMapRepository(cityList = mutableListOf(mumbai))))
        tool.canvas.placeAt = north(200.0)

        send(tool, MapEvent.LocationForm.New, MapEvent.LocationForm.AddressPick(PlacePrediction("p1", "Far", "Far")))
        val outside = assertIs<MapDialog.AddressOutside>(tool.state.dialog)
        assertEquals("Mumbai", outside.areaName)
        assertNull(tool.state.locationForm?.point)

        send(tool, MapEvent.LocationForm.UseAddressAnyway)
        assertNull(tool.state.dialog)
        assertEquals(north(200.0), tool.state.locationForm?.point)
        assertEquals("Picked Place", tool.state.locationForm?.name)
    }

    @Test
    fun `photos stop at ten, and say so`() = runTest(dispatcher) {
        val tool = open(Tool(FakeMapRepository(cityList = mutableListOf(mumbai))))

        send(
            tool,
            MapEvent.LocationForm.New,
            MapEvent.LocationForm.PhotosDropped((1..12).map { photo("$it.jpg") }, refused = listOf("notes.pdf")),
        )
        assertEquals(10, tool.state.locationForm?.added?.size)
        assertTrue("notes.pdf is not a supported image." in tool.notices)
        assertTrue("Only 10 of 12 files added (limit: 10)" in tool.notices)

        send(tool, MapEvent.LocationForm.PhotosDropped(listOf(photo("13.jpg")), refused = emptyList()))
        assertEquals("Maximum 10 media allowed", tool.notices.last())
    }

    // Zones and types -----------------------------------------------------------------

    @Test
    fun `a zone must hold its city, and a custom radius must be given`() = runTest(dispatcher) {
        val repo = FakeMapRepository(cityList = mutableListOf(mumbai))
        val tool = open(Tool(repo))
        tool.canvas.placeAt = LatLng(19.5, 72.8)

        send(tool, MapEvent.Zones.Add)
        val fresh = assertNotNull(tool.state.zoneForm)
        assertEquals("Mumbai", fresh.name)
        assertEquals(mumbai.coordinates, fresh.point)

        send(tool, MapEvent.Zones.Custom, MapEvent.Zones.Save)
        assertEquals("Zone radius is required when Custom is selected.", tool.notices.last())
        assertTrue(tool.state.zoneForm!!.showCustomError)

        send(
            tool,
            MapEvent.Zones.CustomRadius("12"),
            MapEvent.Zones.CenterPick(PlacePrediction("p1", "Bandra", "Bandra")),
            MapEvent.Zones.Save,
        )
        assertEquals(
            "Mumbai is 34.5 miles from the center point, but zone radius is 12 miles. " +
                "Please adjust the center or increase the radius.",
            tool.notices.last(),
        )

        send(tool, MapEvent.Zones.Preset(40), MapEvent.Zones.Save)
        assertEquals(ZoneDraft("mumbai", "Picked Place Zone", LatLng(19.5, 72.8), 40.0), repo.createdZones.single())
        assertNull(tool.state.zoneForm)
    }

    @Test
    fun `renaming a type confirms first, then relabels the city's pins`() = runTest(dispatcher) {
        val repo = FakeMapRepository(
            cityList = mutableListOf(mumbai),
            typeList = mutableListOf(LocationType("t1", "Hotel", icon = "🏨", subTypes = listOf("5 star"))),
        )
        val tool = open(Tool(repo))
        val fetchesBefore = repo.locationCalls.size

        send(tool, MapEvent.Types.Edit("t1"), MapEvent.Types.Name("Hotels"), MapEvent.Types.Save)
        val confirm = assertIs<MapDialog.Confirm>(tool.state.dialog)
        assertEquals("Update Location Type", confirm.title)
        assertIs<ConfirmAction.UpdateType>(confirm.action)
        assertTrue(repo.updatedTypes.isEmpty())

        send(tool, MapEvent.Dialogs.Confirm)
        assertEquals(listOf("t1" to TypeDraft("Hotels", "🏨", listOf("5 star"))), repo.updatedTypes)
        assertNull(tool.state.dialog)
        assertNull(tool.state.typesPanel.form)
        assertTrue(repo.locationCalls.size > fetchesBefore)
    }

    // Rights ---------------------------------------------------------------------------

    @Test
    fun `without posting rights a write control asks for them and does nothing else`() = runTest(dispatcher) {
        val bus = RightsRequestBus()
        val asked = mutableListOf<RightsRequest>()
        jobs += CoroutineScope(dispatcher).launch { bus.requests.collect(asked::add) }
        val repo = FakeMapRepository(
            cityList = mutableListOf(mumbai),
            locationsByCity = mutableMapOf("mumbai" to listOf(baseCamp)),
        )
        val tool = open(
            Tool(repo, viewer = MapViewer(userId = "u1", canView = true, canPost = false, ready = true), rights = bus),
        )

        send(tool, MapEvent.Toolbar.TogglePinMode, MapEvent.Cities.Add, MapEvent.Zones.Add)
        page(tool, """{"type":"marker-action","action":"edit","id":"l1"}""")

        assertTrue(!tool.state.pinMode)
        assertNull(tool.state.dialog)
        assertNull(tool.state.zoneForm)
        assertNull(tool.state.locationForm)
        assertEquals(4, asked.size)
        assertTrue(asked.all { it.moduleLabel == "Map" && it.kind == RightsKind.Post })
        assertTrue(tool.effects.filterIsInstance<MapEffect.Notice>().all { it.tone == NoticeTone.Warning })

        // The frame's dialog floats over this window: the map steps aside for it.
        bus.setShowing(true)
        advanceUntilIdle()
        assertTrue(tool.state.canvasCovered)
        bus.setShowing(false)
        advanceUntilIdle()
        assertTrue(!tool.state.canvasCovered)
    }

    // Other clients ------------------------------------------------------------------------

    @Test
    fun `another client's pins splice in, and a deleted active zone hands over to the next`() = runTest(dispatcher) {
        val repo = FakeMapRepository(
            cityList = mutableListOf(mumbai),
            zonesByCity = mutableMapOf("mumbai" to listOf(zone("za"), zone("zb"))),
        )
        val prefs = MapPrefs.InMemory()
        val tool = open(Tool(repo, prefs = prefs))
        assertEquals("za", tool.state.activeZoneId)

        repo.syncFrames.emit(MapSyncEvent.LocationUpserted(hotel, isNew = true))
        repo.syncFrames.emit(MapSyncEvent.LocationUpserted(hotel.copy(id = "elsewhere", cityId = "pune"), isNew = true))
        repo.syncFrames.emit(MapSyncEvent.LocationUpserted(hotel, isNew = false))
        advanceUntilIdle()
        assertEquals(listOf("l2"), tool.state.locations.map { it.id })

        repo.syncFrames.emit(MapSyncEvent.LocationDeleted("za", "mumbai", isStudioZone = true))
        advanceUntilIdle()
        assertEquals("zb", tool.state.activeZoneId)
        assertEquals("zb", prefs.activeZoneId("mumbai"))
    }

    @Test
    fun `the city on screen deleted elsewhere hands over to the next by the ladder`() = runTest(dispatcher) {
        val repo = FakeMapRepository(cityList = mutableListOf(mumbai, pune))
        val tool = open(Tool(repo))
        assertEquals("mumbai", tool.state.selectedCityId)

        repo.cityList.removeAll { it.id == "mumbai" }
        repo.syncFrames.emit(MapSyncEvent.CityDeleted("mumbai"))
        advanceUntilIdle()

        assertEquals("pune", tool.state.selectedCityId)
        assertEquals(listOf("pune"), tool.state.cities.map { it.id })
    }

    @Test
    fun `a refused reorder puts the server's order back`() = runTest(dispatcher) {
        val repo = FakeMapRepository(cityList = mutableListOf(mumbai, pune, goa)).apply { failWrites = true }
        val tool = open(Tool(repo))

        send(tool, MapEvent.Cities.Reorder(listOf("goa", "mumbai", "pune")))

        assertEquals(listOf("goa", "mumbai", "pune"), repo.reordered)
        assertEquals(listOf("mumbai", "pune", "goa"), tool.state.cities.map { it.id })
        assertEquals(NoticeTone.Error, tool.effects.filterIsInstance<MapEffect.Notice>().last().tone)
    }

    @Test
    fun `the Cities panel offers where this machine is, once, and Add City opens filled in`() = runTest(dispatcher) {
        val repo = FakeMapRepository(cityList = mutableListOf(mumbai))
        val here = LatLng(18.52, 73.85)
        var asked = 0
        val locator = object : MapLocator {
            override suspend fun locate(): LatLng? {
                asked++
                return here
            }
        }
        val tool = open(Tool(repo, locator = locator).also { it.canvas.geocodedCity = "Pune" })

        send(tool, MapEvent.Cities.Open)
        val place = assertNotNull(tool.state.citiesPanel.currentPlace)
        assertEquals("Pune", place.name)
        assertEquals(here, place.point)
        // The page refused (no prompt to grant), so the host answered — and is not asked again.
        assertEquals(1, asked)
        assertEquals(1, tool.canvas.scripts.count { "currentPosition" in it })

        send(tool, MapEvent.Cities.Close, MapEvent.Cities.Open)
        assertEquals(1, asked)

        send(tool, MapEvent.Cities.AddCurrentPlace)
        val add = assertIs<MapDialog.AddCity>(tool.state.dialog).state
        assertEquals("Pune", add.name)
        assertEquals(here, add.point)
        assertTrue(add.prefilled)
    }

    @Test
    fun `with no city yet the map opens where this machine is`() = runTest(dispatcher) {
        val locator = object : MapLocator {
            override suspend fun locate() = LatLng(51.5, -0.12)
        }
        val tool = open(Tool(FakeMapRepository(), locator = locator))
        assertEquals(51.5, tool.canvas.lastCameraLat())
    }

    // Finding and sharing ----------------------------------------------------------------------

    @Test
    fun `a search result clears a filter that hides it and flies to its pin`() = runTest(dispatcher) {
        val repo = FakeMapRepository(
            cityList = mutableListOf(mumbai),
            locationsByCity = mutableMapOf("mumbai" to listOf(baseCamp, hotel)),
        )
        val tool = open(Tool(repo))

        send(tool, MapEvent.Bars.ToggleTypeFilter("Hotel"))
        assertEquals(listOf("l2"), tool.state.filteredLocations.map { it.id })

        send(tool, MapEvent.Toolbar.ToggleSearch, MapEvent.Bars.SearchQuery("base"), MapEvent.Bars.SearchPick("l1"))

        assertTrue(tool.state.typeFilters.isEmpty())
        assertNull(tool.state.search)
        val camera = tool.canvas.scripts.last { it.startsWith("zillitMap.camera(") }
        assertTrue("focus" in camera && "l1" in camera, camera)
    }

    @Test
    fun `a location shares the spec's text to the people picked`() = runTest(dispatcher) {
        val share = FakeShare(listOf(SharePerson("u2", "Zed"), SharePerson("u3", "Amy")))
        val repo = FakeMapRepository(
            cityList = mutableListOf(mumbai),
            locationsByCity = mutableMapOf("mumbai" to listOf(baseCamp)),
        )
        val tool = open(Tool(repo, share = share))

        send(tool, MapEvent.Locations.Share("l1"))
        val dialog = assertIs<MapDialog.Share>(tool.state.dialog).state
        assertEquals(buildLocationShare(baseCamp)!!.text, dialog.text)
        assertEquals(listOf("Amy", "Zed"), dialog.people.map { it.name })

        send(tool, MapEvent.Dialogs.ShareToggle("u2"), MapEvent.Dialogs.ShareSend)
        assertEquals(listOf(listOf("u2") to dialog.text), share.sent)
        assertNull(tool.state.dialog)
        assertEquals("Shared with 1 person", tool.notices.last())
    }
}
