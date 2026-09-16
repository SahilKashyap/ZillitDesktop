package com.zillit.desktop.feature.weather

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.weather.domain.CurrentWeather
import com.zillit.desktop.feature.weather.domain.NoWeatherPlaces
import com.zillit.desktop.feature.weather.domain.PlaceSuggestion
import com.zillit.desktop.feature.weather.domain.WeatherCondition
import com.zillit.desktop.feature.weather.domain.WeatherPlace
import com.zillit.desktop.feature.weather.domain.WeatherPlaces
import com.zillit.desktop.feature.weather.domain.WeatherReport
import com.zillit.desktop.feature.weather.domain.WeatherRepository
import com.zillit.desktop.feature.weather.ui.WeatherEvent
import com.zillit.desktop.feature.weather.ui.WeatherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val place = WeatherPlace("Film City", 19.16, 72.86)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** The place from last time comes back, and its forecast with it. */
    @Test
    fun `the remembered place is asked about on open`() = runTest(dispatcher) {
        val repository = FakeWeatherRepository()
        val model = viewModel(repository, saved = place)

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()

        assertEquals(place, model.state.value.place)
        assertEquals(listOf(place), repository.asked)
    }

    /**
     * Nothing remembered: the web's first load — where this machine is —
     * named, remembered, and asked about.
     */
    @Test
    fun `with no place the machine's own location is used`() = runTest(dispatcher) {
        val repository = FakeWeatherRepository()
        val places = FakePlaces(here = WeatherPlace("", 51.5, -0.12), city = "London")
        var saved: WeatherPlace? = null
        val model = WeatherViewModel(
            repository = repository,
            places = places,
            viewer = { rights() },
            savePlace = { saved = it },
        )

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()

        assertEquals("London", model.state.value.place?.name)
        assertEquals("London", model.state.value.report?.place?.name)
        assertEquals("London", saved?.name)
        assertEquals(listOf(51.5), repository.asked.map { it.lat })
    }

    /** No host that can locate, nothing remembered: the screen asks for a place, and no call is made. */
    @Test
    fun `with no place and no locator nothing is fetched`() = runTest(dispatcher) {
        val repository = FakeWeatherRepository()
        val model = viewModel(repository, saved = null)

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()

        assertNull(model.state.value.place)
        assertTrue(repository.asked.isEmpty())
    }

    /** Where this machine is cannot be told: a sentence, and the search box is the way on. */
    @Test
    fun `a failed locate says what to do instead`() = runTest(dispatcher) {
        val model = WeatherViewModel(FakeWeatherRepository(), places = FakePlaces(here = null), viewer = { rights() })

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()

        assertEquals(WeatherViewModel.NOT_LOCATED, model.state.value.error)
        assertFalse(model.state.value.locating)
    }

    /**
     * Typing suggests after a pause, not per keystroke; picking a row turns it
     * into a place and its forecast, and the box keeps the place's name.
     */
    @Test
    fun `typing suggests and picking a row fetches`() = runTest(dispatcher) {
        val repository = FakeWeatherRepository()
        val paris = PlaceSuggestion("p1", "Paris", "France")
        val places = FakePlaces(
            suggestions = mapOf("Par" to listOf(paris)),
            resolved = mapOf("p1" to WeatherPlace("Paris", 48.85, 2.35)),
            city = "Paris",
        )
        val model = WeatherViewModel(repository, places = places, viewer = { rights() }, savedPlace = { place })

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()
        model.onEvent(WeatherEvent.SearchChanged("P"))
        model.onEvent(WeatherEvent.SearchChanged("Pa"))
        model.onEvent(WeatherEvent.SearchChanged("Par"))
        advanceUntilIdle()

        assertEquals(listOf("Par"), places.suggested)
        assertEquals(listOf(paris), model.state.value.search.suggestions)
        assertTrue(model.state.value.search.open)

        model.onEvent(WeatherEvent.SuggestionPicked(paris))
        advanceUntilIdle()

        assertEquals("Paris", model.state.value.search.query)
        assertTrue(model.state.value.search.suggestions.isEmpty())
        assertEquals(48.85, model.state.value.place?.lat)
        assertEquals(listOf(place.lat, 48.85), repository.asked.map { it.lat })
    }

    /** `handleSearchText`: emptying the box goes back to where this machine is. */
    @Test
    fun `clearing the search returns to the machine's location`() = runTest(dispatcher) {
        val repository = FakeWeatherRepository()
        val places = FakePlaces(here = WeatherPlace("Here", 1.0, 2.0))
        val model = WeatherViewModel(repository, places = places, viewer = { rights() }, savedPlace = { place })

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()
        model.onEvent(WeatherEvent.SearchChanged("Pa"))
        model.onEvent(WeatherEvent.SearchChanged(""))
        advanceUntilIdle()

        assertEquals(1.0, model.state.value.place?.lat)
        assertEquals(listOf(place.lat, 1.0), repository.asked.map { it.lat })
        assertTrue(places.suggested.isEmpty())
    }

    /** A picked point is renamed to the city it is in, as `getCityName` renames every reading. */
    @Test
    fun `a picked place takes the city's name`() = runTest(dispatcher) {
        val model = WeatherViewModel(
            FakeWeatherRepository(),
            places = FakePlaces(city = "Mumbai"),
            viewer = { rights() },
        )

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()
        model.onEvent(WeatherEvent.PlacePicked(WeatherPlace("Some pin", 19.16, 72.86)))
        advanceUntilIdle()

        assertEquals("Mumbai", model.state.value.place?.name)
        assertEquals("Mumbai", model.state.value.report?.place?.name)
    }

    /** Picking a place fetches it and keeps it for next time. */
    @Test
    fun `a picked place is remembered`() = runTest(dispatcher) {
        val repository = FakeWeatherRepository()
        var saved: WeatherPlace? = null
        val model = WeatherViewModel(
            repository = repository,
            viewer = { rights() },
            savePlace = { saved = it },
        )

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()
        model.onEvent(WeatherEvent.PlacePicked(place))
        advanceUntilIdle()

        assertEquals(place, saved)
        assertEquals(listOf(place), repository.asked)
    }

    /**
     * No rights is only a refusal once the tools call has answered — an empty
     * set is a call still in flight, and must not flash "no access".
     */
    @Test
    fun `unresolved rights are not a refusal`() = runTest(dispatcher) {
        val model = viewModel(FakeWeatherRepository(), rights = ProjectPermissions(emptyList()))

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()

        assertFalse(model.state.value.hasNoAccess)
    }

    /** Denied: the screen says so and the service is never asked. */
    @Test
    fun `a viewer without the tool sees no access`() = runTest(dispatcher) {
        val repository = FakeWeatherRepository()
        val model = viewModel(repository, rights = rights(canView = false), saved = place)

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()

        assertTrue(model.state.value.hasNoAccess)
        assertTrue(repository.asked.isEmpty())
    }

    /** No key configured: the tool says so, and asks nobody. */
    @Test
    fun `an unconfigured tool fetches nothing`() = runTest(dispatcher) {
        val repository = FakeWeatherRepository(configured = false)
        val model = viewModel(repository, saved = place)

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()

        assertFalse(model.state.value.configured)
        assertTrue(repository.asked.isEmpty())
    }

    /**
     * A failure is a sentence on screen, not a blank panel.
     *
     * A lost connection wears the app's own standard wording rather than
     * whatever the exception said — the reader is told the same thing here as
     * everywhere else in the app.
     */
    @Test
    fun `a lost connection says so in the app's own words`() = runTest(dispatcher) {
        val repository = FakeWeatherRepository(failure = ZillitError.NoConnection("socket closed"))
        val model = viewModel(repository, saved = place)

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()

        assertEquals("No internet connection.", model.state.value.error)
        assertFalse(model.state.value.loading)
    }

    /** The service's own complaint, though, is shown as it came. */
    @Test
    fun `a refusal shows what the service said`() = runTest(dispatcher) {
        val repository = FakeWeatherRepository(
            failure = ZillitError.Http(status = 401, serverMessage = "The weather service refused the request."),
        )
        val model = viewModel(repository, saved = place)

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()

        assertEquals("The weather service refused the request.", model.state.value.error)
    }

    private fun rights(canView: Boolean = true) = ProjectPermissions(
        listOf(ToolAccess(identifier = WeatherViewModel.TOOL_IDENTIFIER, canView = canView)),
    )

    private fun viewModel(
        repository: WeatherRepository,
        rights: ProjectPermissions = rights(),
        saved: WeatherPlace? = null,
        places: WeatherPlaces = NoWeatherPlaces,
    ) = WeatherViewModel(
        repository = repository,
        places = places,
        viewer = { rights },
        savedPlace = { saved },
    )
}

/** Google, scripted. */
private class FakePlaces(
    private val here: WeatherPlace? = null,
    private val city: String? = null,
    private val suggestions: Map<String, List<PlaceSuggestion>> = emptyMap(),
    private val resolved: Map<String, WeatherPlace> = emptyMap(),
) : WeatherPlaces {

    val suggested = mutableListOf<String>()

    override val canSearch: Boolean = true

    override suspend fun suggest(query: String): List<PlaceSuggestion> {
        suggested += query
        return suggestions[query].orEmpty()
    }

    override suspend fun resolve(suggestion: PlaceSuggestion): WeatherPlace? = resolved[suggestion.id]

    override suspend fun locate(): WeatherPlace? = here?.let { it.copy(name = city ?: it.name) }

    override suspend fun cityName(lat: Double, lng: Double): String? = city
}

private class FakeWeatherRepository(
    override val isConfigured: Boolean = true,
    private val failure: ZillitError? = null,
) : WeatherRepository {

    constructor(configured: Boolean) : this(isConfigured = configured, failure = null)

    val asked = mutableListOf<WeatherPlace>()

    override suspend fun forecast(place: WeatherPlace): ZillitResult<WeatherReport> {
        asked += place
        failure?.let { return ZillitResult.Failure(it) }
        return ZillitResult.Success(
            WeatherReport(
                place = place,
                timezone = "Asia/Kolkata",
                current = CurrentWeather(
                    temperatureC = 30.0,
                    feelsLikeC = 34.0,
                    humidityPercent = 60,
                    pressureHpa = 1008,
                    windKph = 12.0,
                    uvIndex = 8.0,
                    visibilityMetres = 9000,
                    sunriseMillis = 1,
                    sunsetMillis = 2,
                    condition = WeatherCondition("clear sky", "01d"),
                ),
                hourly = emptyList(),
                daily = emptyList(),
            ),
        )
    }
}
