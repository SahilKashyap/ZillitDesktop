package com.zillit.desktop.feature.weather.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.weather.domain.WeatherPlace
import kotlinx.coroutines.launch

/** The Weather tool, at the web's path (`/film-tools/weather`). */
class WeatherToolProvider(
    private val viewModel: WeatherViewModel,
    /** The host's clock: an instant in the place's own day. */
    private val clock: WeatherClock = WeatherClock(),
    /** OpenWeather's artwork; null draws glyphs instead. */
    private val icons: WeatherIconLoader? = null,
) : ToolProvider {

    override val path: String = WEATHER_PATH
    override val title: String = "Weather"
    override val icon = ZillitToolIcons.IcWeather
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1100.dp, 780.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()

        // The web's search box is the whole of its place-picking; the map
        // picker the boards and chat share is a second way in, for a unit
        // that is somewhere no city name would find.
        val picker = LocalLocationPicker.current
        val scope = rememberCoroutineScope()

        LaunchedEffect(viewModel) { viewModel.onEvent(WeatherEvent.Load) }

        CompositionLocalProvider(LocalWeatherIconLoader provides icons) {
            WeatherScreen(
                state = state,
                onEvent = viewModel::onEvent,
                onPickPlace = picker?.let {
                    {
                        scope.launch {
                            val current = state.place?.let { place ->
                                PickedLocation(name = place.name, address = "", lat = place.lat, lng = place.lng)
                            }
                            it.pick(initial = current, title = "Where is the unit?")?.let { place ->
                                viewModel.onEvent(
                                    WeatherEvent.PlacePicked(
                                        WeatherPlace(
                                            name = place.name.ifBlank { place.address },
                                            lat = place.lat,
                                            lng = place.lng,
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                },
                clock = clock,
            )
        }
    }
}

const val WEATHER_PATH = "/film-tools/weather"
