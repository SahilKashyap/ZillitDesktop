package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.settings.preferences.AppPreferencesEvent
import com.zillit.desktop.feature.settings.preferences.AppPreferencesRepository
import com.zillit.desktop.feature.settings.preferences.AppPreferencesViewModel
import com.zillit.desktop.feature.settings.preferences.HomeUnitChoice
import com.zillit.desktop.feature.settings.preferences.LocationMode
import com.zillit.desktop.feature.settings.preferences.LocationSharing
import com.zillit.desktop.feature.settings.preferences.NotificationActivity
import com.zillit.desktop.feature.settings.preferences.NotificationSection
import com.zillit.desktop.feature.settings.preferences.NotificationSectionDto
import com.zillit.desktop.feature.settings.preferences.NotificationTool
import com.zillit.desktop.feature.settings.preferences.PreferencesContext
import com.zillit.desktop.feature.settings.preferences.PreferencesProfile
import com.zillit.desktop.feature.settings.preferences.PreferencesProfileDto
import com.zillit.desktop.feature.settings.preferences.TimeWindow
import com.zillit.desktop.feature.settings.preferences.clockLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web's App Preferences cards: Default Home Unit, Manage Notifications, Location On/Off. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesTest {

    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository : AppPreferencesRepository {
        val calls = mutableListOf<String>()
        var profile = PreferencesProfile(defaultUnitId = "u2")
        var writeAnswer: ZillitResult<String?> = ZillitResult.Success("updated_successfully")
        var sections = listOf(
            NotificationSection("sos_label", enabled = true),
            NotificationSection(
                "tools_label",
                enabled = true,
                tools = listOf(
                    NotificationTool("call_sheet", enabled = true),
                    NotificationTool("not_on_this_production", enabled = true),
                    NotificationTool(
                        "document_distribution_label",
                        enabled = true,
                        activities = listOf(NotificationActivity("dd_published", enabled = true)),
                    ),
                ),
            ),
        )

        override suspend fun profile() = ZillitResult.Success(profile).also { calls += "profile" }
        override suspend fun setDefaultUnit(unitId: String) = writeAnswer.also { calls += "unit:$unitId" }
        override suspend fun setLocationSharing(on: Boolean) = writeAnswer.also { calls += "share:$on" }
        override suspend fun saveLocationSchedule(sharing: LocationSharing) =
            writeAnswer.also { calls += "schedule:${sharing.mode}:${sharing.days.keys.sorted()}" }
        override suspend fun reportLocation(lat: Double, lng: Double) =
            ZillitResult.Success(Unit).also { calls += "device:$lat,$lng" }
        override suspend fun notificationSections() = ZillitResult.Success(sections).also { calls += "sections" }
        override suspend fun setNotification(name: String, enabled: Boolean) =
            writeAnswer.also { calls += "notify:$name:$enabled" }
        override suspend fun setActivity(tool: String, activity: String, enabled: Boolean) =
            writeAnswer.also { calls += "activity:$tool:$activity:$enabled" }
    }

    private fun viewModel(
        repository: FakeRepository,
        locate: suspend () -> Pair<Double, Double>? = { 51.5 to -0.1 },
        teamProject: Boolean = true,
    ) = AppPreferencesViewModel(
        repository = repository,
        context = flowOf(PreferencesContext(projectId = "p1", isTeamProject = teamProject)),
        homeUnits = { listOf(HomeUnitChoice("u1", "bulletin_label"), HomeUnitChoice("u2", "calendar_label")) },
        toolNames = { setOf("call_sheet", "document_distribution_label") },
        locate = locate,
    )

    // -- Default Home Unit ---------------------------------------------------

    @Test
    fun `the stored unit is preselected, and an unknown one shows the placeholder`() = runTest {
        val repository = FakeRepository()
        val model = viewModel(repository)
        advanceUntilIdle()
        assertEquals("u2", model.state.value.defaultUnit?.id)

        repository.profile = PreferencesProfile(defaultUnitId = "gone")
        model.onEvent(AppPreferencesEvent.Refresh)
        advanceUntilIdle()
        assertNull(model.state.value.defaultUnit)
    }

    @Test
    fun `picking a unit writes default_unit_id and keeps the old one when refused`() = runTest {
        val repository = FakeRepository()
        val model = viewModel(repository)
        advanceUntilIdle()

        model.onEvent(AppPreferencesEvent.DefaultUnitPicked("u1"))
        advanceUntilIdle()
        assertTrue("unit:u1" in repository.calls)
        assertEquals("u1", model.state.value.defaultUnitId)

        repository.writeAnswer = ZillitResult.Failure(ZillitError.Http(200, "nope"))
        model.onEvent(AppPreferencesEvent.DefaultUnitPicked("u2"))
        advanceUntilIdle()
        assertEquals("u1", model.state.value.defaultUnitId)
        assertTrue(model.state.value.toast?.isError == true)
    }

    // -- Location ------------------------------------------------------------

    @Test
    fun `switching location on sends the position first, then the profile`() = runTest {
        val repository = FakeRepository()
        val model = viewModel(repository)
        advanceUntilIdle()

        model.onEvent(AppPreferencesEvent.LocationToggled(true))
        advanceUntilIdle()

        val writes = repository.calls.filter { it.startsWith("device") || it.startsWith("share") }
        assertEquals(listOf("device:51.5,-0.1", "share:true"), writes)
        assertTrue(model.state.value.location.enabled)
        assertTrue(model.state.value.showsSchedule)
    }

    @Test
    fun `no position means the switch stays off and nothing is written`() = runTest {
        val repository = FakeRepository()
        val model = viewModel(repository, locate = { null })
        advanceUntilIdle()

        model.onEvent(AppPreferencesEvent.LocationToggled(true))
        advanceUntilIdle()

        assertFalse(model.state.value.location.enabled)
        assertTrue(repository.calls.none { it.startsWith("share") || it.startsWith("device") })
        assertTrue(model.state.value.toast?.isError == true)
    }

    @Test
    fun `a personal production never shows the schedule`() = runTest {
        val repository = FakeRepository().apply {
            profile = PreferencesProfile(location = LocationSharing(enabled = true))
        }
        val model = viewModel(repository, teamProject = false)
        advanceUntilIdle()
        assertFalse(model.state.value.showsSchedule)
    }

    @Test
    fun `a start after the end is refused on the spot`() = runTest {
        val model = viewModel(FakeRepository())
        advanceUntilIdle()

        model.onEvent(AppPreferencesEvent.EverydayEndChanged(9 * 60))
        model.onEvent(AppPreferencesEvent.EverydayStartChanged(10 * 60))
        advanceUntilIdle()

        assertEquals(TimeWindow(0, 9 * 60), model.state.value.location.everyday)
        assertTrue(model.state.value.toast?.isError == true)
    }

    @Test
    fun `select days needs a day before it saves`() = runTest {
        val repository = FakeRepository()
        val model = viewModel(repository)
        advanceUntilIdle()

        model.onEvent(AppPreferencesEvent.ModeChanged(LocationMode.SelectDays))
        model.onEvent(AppPreferencesEvent.UpdateLocation)
        advanceUntilIdle()
        assertTrue(repository.calls.none { it.startsWith("schedule") })

        model.onEvent(AppPreferencesEvent.DayToggled(1))
        model.onEvent(AppPreferencesEvent.DayToggled(3))
        model.onEvent(AppPreferencesEvent.UpdateLocation)
        advanceUntilIdle()
        assertTrue("schedule:SelectDays:[1, 3]" in repository.calls)
    }

    // -- Notifications -------------------------------------------------------

    @Test
    fun `the drawer lists only tools this production has`() = runTest {
        val repository = FakeRepository()
        val model = viewModel(repository)
        advanceUntilIdle()

        model.onEvent(AppPreferencesEvent.OpenNotifications)
        advanceUntilIdle()

        val tools = model.state.value.notifications.sections.single { it.name == "tools_label" }.tools
        assertEquals(listOf("call_sheet", "document_distribution_label"), tools.map { it.name })
    }

    @Test
    fun `a refused switch goes back to where it was`() = runTest {
        val repository = FakeRepository()
        val model = viewModel(repository)
        advanceUntilIdle()
        model.onEvent(AppPreferencesEvent.OpenNotifications)
        advanceUntilIdle()

        repository.writeAnswer = ZillitResult.Failure(ZillitError.Http(200, "nope"))
        model.onEvent(AppPreferencesEvent.SectionSwitched("sos_label", false))
        advanceUntilIdle()

        assertTrue("notify:sos_label:false" in repository.calls)
        assertTrue(model.state.value.notifications.sections.single { it.name == "sos_label" }.enabled)
    }

    @Test
    fun `an activity is written under its tool`() = runTest {
        val repository = FakeRepository()
        val model = viewModel(repository)
        advanceUntilIdle()
        model.onEvent(AppPreferencesEvent.OpenNotifications)
        advanceUntilIdle()

        model.onEvent(AppPreferencesEvent.ActivitySwitched("document_distribution_label", "dd_published", false))
        advanceUntilIdle()

        assertTrue("activity:document_distribution_label:dd_published:false" in repository.calls)
        val activity = model.state.value.notifications.sections
            .flatMap { it.tools }.single { it.name == "document_distribution_label" }.activities.single()
        assertFalse(activity.enabled)
    }

    // -- Wire ------------------------------------------------------------------

    @Test
    fun `device notification decodes sections, tools and activities`() {
        val payload = """
            [
              {"section_name":"sos_label","enabled":true,"data":[]},
              {"section_name":"tools_label","enabled":false,"data":[
                {"name":"document_distribution_label","enabled":true,
                 "activities":[{"name":"dd_published","enabled":false}]}
              ]}
            ]
        """.trimIndent()
        val sections = json.decodeFromString(ListSerializer(NotificationSectionDto.serializer()), payload)
            .mapNotNull { it.toDomain() }

        assertEquals(listOf("sos_label", "tools_label"), sections.map { it.name })
        assertFalse(sections[1].enabled)
        assertFalse(sections[1].tools.single().activities.single().enabled)
    }

    @Test
    fun `the profile's select days read back as times of day`() {
        // 2026-10-21 09:30 and 18:00 UTC.
        val payload = """
            {"default_unit_id":"u1","show_location":true,"location_tracking_mode":"SelectDays",
             "select_days":[{"day_of_week":2,"start_time":1792575000000,"end_time":1792605600000}]}
        """.trimIndent()
        val profile = json.decodeFromString(PreferencesProfileDto.serializer(), payload).toDomain(TimeZone.UTC)

        assertEquals("u1", profile.defaultUnitId)
        assertTrue(profile.location.enabled)
        assertEquals(LocationMode.SelectDays, profile.location.mode)
        assertEquals(mapOf(2 to TimeWindow(9 * 60 + 30, 18 * 60)), profile.location.days)
    }

    @Test
    fun `a never-set schedule reads as the whole day, not midnight UTC`() {
        val payload = """
            {"show_location":true,"location_tracking_mode":"Everyday",
             "everyday_timing":{"start_time":0,"end_time":0}}
        """.trimIndent()
        val profile = json.decodeFromString(PreferencesProfileDto.serializer(), payload)
            .toDomain(TimeZone.of("Asia/Kolkata"))

        assertEquals(TimeWindow(), profile.location.everyday)
    }

    @Test
    fun `times read as the web's twelve-hour clock`() {
        assertEquals("12:00 AM", clockLabel(0))
        assertEquals("11:59 PM", clockLabel(TimeWindow.LAST_MINUTE))
        assertEquals("12:30 PM", clockLabel(12 * 60 + 30))
    }
}
