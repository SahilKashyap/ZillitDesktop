package com.zillit.desktop.feature.weather

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.weather.domain.CurrentWeather
import com.zillit.desktop.feature.weather.domain.WeatherCondition
import com.zillit.desktop.feature.weather.domain.WeatherPlace
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

    /** Nothing remembered: no call, and the screen asks for a place. */
    @Test
    fun `with no place nothing is fetched`() = runTest(dispatcher) {
        val repository = FakeWeatherRepository()
        val model = viewModel(repository, saved = null)

        model.onEvent(WeatherEvent.Load)
        advanceUntilIdle()

        assertNull(model.state.value.place)
        assertTrue(repository.asked.isEmpty())
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
    ) = WeatherViewModel(
        repository = repository,
        viewer = { rights },
        savedPlace = { saved },
    )
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
