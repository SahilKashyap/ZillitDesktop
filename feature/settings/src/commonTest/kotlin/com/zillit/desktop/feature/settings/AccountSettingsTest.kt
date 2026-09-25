package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.settings.account.AccountEvent
import com.zillit.desktop.feature.settings.account.AccountPage
import com.zillit.desktop.feature.settings.account.AccountRepository
import com.zillit.desktop.feature.settings.account.AccountEffect
import com.zillit.desktop.feature.settings.account.AccountViewModel
import com.zillit.desktop.feature.settings.account.LinkedDevice
import com.zillit.desktop.feature.settings.account.LinkedDeviceDto
import com.zillit.desktop.feature.settings.account.ProfileEdit
import com.zillit.desktop.feature.settings.account.ProfileSaveOutcome
import com.zillit.desktop.feature.settings.account.ProfileSeed
import com.zillit.desktop.feature.settings.account.RecoveryDetails
import com.zillit.desktop.feature.settings.account.allowsPrivateName
import com.zillit.desktop.feature.settings.account.inviteText
import com.zillit.desktop.feature.settings.approvals.ApprovalPresets
import com.zillit.desktop.feature.settings.approvals.CrewDepartment
import com.zillit.desktop.feature.settings.approvals.CrewPresets
import com.zillit.desktop.feature.settings.approvals.CrewRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reader's own account pages.
 *
 * What matters here is not the forms. It is that an edit goes down the right
 * one of two paths — an admin's is live, everyone else's waits for approval —
 * that a half-typed name is never thrown away by a background refresh, and that
 * signing a device out removes the right row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSettingsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- recovery ------------------------------------------------------------

    @Test
    fun `the recovery page shows the key and the address on file`() = runTest(dispatcher) {
        val model = viewModel(FakeAccount(), seed(isAdmin = false))
        model.onEvent(AccountEvent.Opened(AccountPage.RecoveryEmail))
        advanceUntilIdle()

        assertEquals("ABCD-1234", model.currentState.recovery.key)
        assertEquals("backup@example.com", model.currentState.recovery.email)
    }

    @Test
    fun `a reload never overwrites an address being typed`() = runTest(dispatcher) {
        val model = viewModel(FakeAccount(), seed(isAdmin = false))
        model.onEvent(AccountEvent.RecoveryEmailChanged("new@exa"))
        model.onEvent(AccountEvent.Opened(AccountPage.RecoveryEmail))
        advanceUntilIdle()

        assertEquals("new@exa", model.currentState.recovery.email)
        assertEquals("ABCD-1234", model.currentState.recovery.key)
    }

    @Test
    fun `copying the key puts it on the clipboard`() = runTest(dispatcher) {
        val model = viewModel(FakeAccount(), seed(isAdmin = false))
        model.onEvent(AccountEvent.Opened(AccountPage.RecoveryEmail))
        advanceUntilIdle()

        val copied = mutableListOf<String>()
        val job = launch {
            model.effects.collect { (it as? AccountEffect.CopyToClipboard)?.let { e -> copied += e.text } }
        }
        model.onEvent(AccountEvent.CopyRecoveryKey)
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("ABCD-1234"), copied)
    }

    // -- the two save paths --------------------------------------------------

    @Test
    fun `an admin's edit is written straight through`() = runTest(dispatcher) {
        val repository = FakeAccount()
        val model = viewModel(repository, seed(isAdmin = true))
        model.onEvent(AccountEvent.Opened(AccountPage.EditProfile))
        advanceUntilIdle()

        model.onEvent(AccountEvent.FirstNameChanged("Aisha"))
        model.onEvent(AccountEvent.SaveProfile)
        advanceUntilIdle()

        assertEquals(true, repository.savedAsAdmin)
        assertEquals(ProfileSaveOutcome.Saved, model.currentState.profile.outcome)
        // The rest of the app has to be told: the name is on every message this
        // person has sent.
        assertEquals(1, repository.profileReloads)
    }

    @Test
    fun `everyone else files a request, and nothing is reloaded`() = runTest(dispatcher) {
        val repository = FakeAccount()
        val model = viewModel(repository, seed(isAdmin = false))
        model.onEvent(AccountEvent.Opened(AccountPage.EditProfile))
        advanceUntilIdle()

        model.onEvent(AccountEvent.LastNameChanged("Khan"))
        model.onEvent(AccountEvent.SaveProfile)
        advanceUntilIdle()

        assertEquals(false, repository.savedAsAdmin)
        assertEquals(ProfileSaveOutcome.SentForApproval, model.currentState.profile.outcome)
        // Reloading would blank the form back to the old values, which reads as
        // the change having been refused rather than queued.
        assertEquals(0, repository.profileReloads)
    }

    @Test
    fun `a failed save keeps what was typed`() = runTest(dispatcher) {
        val repository = FakeAccount(saveFails = true)
        val model = viewModel(repository, seed(isAdmin = true))
        model.onEvent(AccountEvent.Opened(AccountPage.EditProfile))
        advanceUntilIdle()

        model.onEvent(AccountEvent.FirstNameChanged("Aisha"))
        model.onEvent(AccountEvent.SaveProfile)
        advanceUntilIdle()

        assertEquals("Aisha", model.currentState.profile.firstName)
        assertNull(model.currentState.profile.outcome)
        assertTrue(model.currentState.profile.error!!.isNotBlank())
    }

    // -- the form ------------------------------------------------------------

    @Test
    fun `a background refresh does not overwrite a half-typed name`() = runTest(dispatcher) {
        val seeds = MutableStateFlow(seed())
        val model = viewModel(FakeAccount(), seeds)
        model.onEvent(AccountEvent.Opened(AccountPage.EditProfile))
        advanceUntilIdle()

        model.onEvent(AccountEvent.FirstNameChanged("Ais"))
        // The crew list refreshes every time somebody joins, and the profile
        // flow re-emits with it.
        seeds.value = seed().copy(productionName = "Dune: Part Three")
        advanceUntilIdle()

        assertEquals("Ais", model.currentState.profile.firstName)
    }

    @Test
    fun `the form opens on the department and role the profile reports`() = runTest(dispatcher) {
        val model = viewModel(FakeAccount(), seed())
        model.onEvent(AccountEvent.Opened(AccountPage.EditProfile))
        advanceUntilIdle()

        // Straight from the profile's own ids. Seeding this from the crew
        // list's department *name* is what left both pickers blank on dev —
        // `project/users` does not name a department the way the department
        // catalogue does.
        assertEquals("dept-camera", model.currentState.profile.departmentId)
        assertEquals("role-focus", model.currentState.profile.designationId)
        // And resolved against the loaded catalogue, which is what the pickers
        // actually display.
        assertEquals("camera_department_label", model.currentState.profile.department?.name)
        assertEquals("focus_puller_label", model.currentState.profile.role?.name)
    }

    @Test
    fun `changing department clears the role rather than carrying it across`() =
        runTest(dispatcher) {
            val model = viewModel(FakeAccount(), seed())
            model.onEvent(AccountEvent.Opened(AccountPage.EditProfile))
            advanceUntilIdle()

            model.onEvent(AccountEvent.DepartmentChosen("dept-art"))

            assertNull(model.currentState.profile.designationId)
            // Blocked until one of the new department's own roles is picked: a
            // role carried across would seat this person as a focus puller in
            // the art department.
            assertFalse(model.currentState.profile.canSave)

            model.onEvent(AccountEvent.RoleChosen("role-standby"))
            assertTrue(model.currentState.profile.canSave)
        }

    @Test
    fun `the privacy toggle follows the role, not the person`() {
        assertTrue(allowsPrivateName("producer_label"))
        assertTrue(allowsPrivateName("main_cast_label"))
        assertTrue(allowsPrivateName("studio_executive_label"))
        assertFalse(allowsPrivateName("focus_puller_label"))
        assertFalse(allowsPrivateName(null))
    }

    @Test
    fun `a role that cannot withhold a name drops the flag`() = runTest(dispatcher) {
        val model = viewModel(FakeAccount(), seed(designation = "producer_label"))
        model.onEvent(AccountEvent.Opened(AccountPage.EditProfile))
        advanceUntilIdle()

        model.onEvent(AccountEvent.PrivateNameChanged(true))
        model.onEvent(AccountEvent.RoleChosen("role-focus"))

        // Left set, the server would keep withholding the name of someone whose
        // role no longer allows it.
        assertFalse(model.currentState.profile.keepNamePrivate)
    }

    // -- devices -------------------------------------------------------------

    @Test
    fun `signing a device out removes its row and no other`() = runTest(dispatcher) {
        val repository = FakeAccount()
        val model = viewModel(repository, seed())
        model.onEvent(AccountEvent.Opened(AccountPage.LinkedDevices))
        advanceUntilIdle()

        val phone = model.currentState.devices.devices.first { it.id == "phone" }
        model.onEvent(AccountEvent.AskUnlink(phone))
        model.onEvent(AccountEvent.ConfirmUnlink)
        advanceUntilIdle()

        assertEquals(listOf("here", "laptop"), model.currentState.devices.devices.map { it.id })
        assertEquals("phone", repository.unlinked)
    }

    @Test
    fun `a failed unlink leaves the device in the list`() = runTest(dispatcher) {
        val repository = FakeAccount(unlinkFails = true)
        val model = viewModel(repository, seed())
        model.onEvent(AccountEvent.Opened(AccountPage.LinkedDevices))
        advanceUntilIdle()

        model.onEvent(AccountEvent.AskUnlink(model.currentState.devices.devices.first()))
        model.onEvent(AccountEvent.ConfirmUnlink)
        advanceUntilIdle()

        assertEquals(3, model.currentState.devices.devices.size)
        assertTrue(model.currentState.devices.error!!.isNotBlank())
    }

    @Test
    fun `the account's own device is never offered a sign-out button`() {
        val primary = LinkedDevice(id = "here", name = "Mac", kind = "desktop", osVersion = null,
            appVersion = null, lastActiveMillis = null, isPrimary = true)
        assertFalse(primary.canUnlink)
    }

    // -- leaving --------------------------------------------------------------

    @Test
    fun `the production is dropped only after the server takes the leave`() =
        runTest(dispatcher) {
            val repository = FakeAccount(leaveFails = true)
            var dropped = false
            val model = viewModel(repository, seed(), onLeft = { dropped = true })

            model.onEvent(AccountEvent.AskLeave)
            model.onEvent(AccountEvent.ConfirmLeave)
            advanceUntilIdle()

            // Dropping first would leave the app on a production the server
            // still has this person on, refusing every request afterwards.
            assertFalse(dropped)
            assertTrue(model.currentState.leave.error!!.isNotBlank())
        }

    // -- the invite -----------------------------------------------------------

    @Test
    fun `the invite carries the code`() {
        val text = inviteText("Dune", "DUNE-1")
        assertTrue(text.contains("DUNE-1"), text)
        assertTrue(text.contains("Dune"), text)
    }

    @Test
    fun `an email address is checked before it is saved`() = runTest(dispatcher) {
        val model = viewModel(FakeAccount(), seed())

        model.onEvent(AccountEvent.RecoveryEmailChanged("not-an-address"))
        assertFalse(model.currentState.recovery.canSave)

        model.onEvent(AccountEvent.RecoveryEmailChanged("aisha@example.com"))
        assertTrue(model.currentState.recovery.canSave)
    }

    // -- helpers ---------------------------------------------------------------

    private fun seed(
        isAdmin: Boolean = false,
        designation: String = "focus_puller_label",
    ) = ProfileSeed(
        firstName = "A",
        lastName = "K",
        email = "a@k.com",
        departmentId = "dept-camera",
        designationId = "role-focus",
        designationName = designation,
        isAdmin = isAdmin,
        productionName = "Dune",
        productionCode = "DUNE-1",
    )

    private fun viewModel(
        repository: AccountRepository,
        seed: ProfileSeed,
        onLeft: () -> Unit = {},
    ) = viewModel(repository, MutableStateFlow(seed), onLeft)

    private fun viewModel(
        repository: AccountRepository,
        seeds: MutableStateFlow<ProfileSeed>,
        onLeft: () -> Unit = {},
    ) = AccountViewModel(
        repository = repository,
        presets = ApprovalPresets {
            ZillitResult.Success(
                CrewPresets(
                    departments = listOf(
                        CrewDepartment(
                            id = "dept-camera",
                            name = "camera_department_label",
                            roles = listOf(
                                CrewRole("role-focus", "focus_puller_label"),
                                CrewRole("role-producer", "producer_label"),
                            ),
                        ),
                        CrewDepartment(
                            id = "dept-art",
                            name = "art_department_label",
                            roles = listOf(CrewRole("role-standby", "standby_art_label")),
                        ),
                    ),
                ),
            )
        },
        onProfileChanged = { (repository as FakeAccount).profileReloads++ },
        onLeftProduction = { onLeft() },
        seed = seeds,
    )
}

/** A repository that records what it was asked, so the tests can assert on it. */
private class FakeAccount(
    private val saveFails: Boolean = false,
    private val unlinkFails: Boolean = false,
    private val leaveFails: Boolean = false,
) : AccountRepository {

    var savedAsAdmin: Boolean? = null
    var profileReloads = 0
    var unlinked: String? = null

    override suspend fun saveProfile(
        edit: ProfileEdit,
        asAdmin: Boolean,
    ): ZillitResult<ProfileSaveOutcome> {
        savedAsAdmin = asAdmin
        return if (saveFails) {
            ZillitResult.Failure(ZillitError.Validation("refused"))
        } else {
            ZillitResult.Success(
                if (asAdmin) ProfileSaveOutcome.Saved else ProfileSaveOutcome.SentForApproval,
            )
        }
    }

    override suspend fun setRecoveryEmail(email: String): ZillitResult<Unit> =
        ZillitResult.Success(Unit)

    var recovery = RecoveryDetails(key = "ABCD-1234", email = "backup@example.com")

    override suspend fun recoveryDetails(): ZillitResult<RecoveryDetails> = ZillitResult.Success(recovery)

    override suspend fun linkedDevices(): ZillitResult<List<LinkedDevice>> = ZillitResult.Success(
        listOf(
            LinkedDevice("here", "This Mac", "desktop", null, null, null, isThisDevice = true),
            LinkedDevice("phone", "iPhone", "ios", null, null, null),
            LinkedDevice("laptop", "MacBook", "desktop", null, null, null),
        ),
    )

    override suspend fun unlinkDevice(deviceId: String): ZillitResult<Unit> {
        unlinked = deviceId
        return if (unlinkFails) {
            ZillitResult.Failure(ZillitError.Validation("refused"))
        } else {
            ZillitResult.Success(Unit)
        }
    }

    override suspend fun leaveProduction(): ZillitResult<Unit> =
        if (leaveFails) ZillitResult.Failure(ZillitError.Validation("refused")) else ZillitResult.Success(Unit)
}

/**
 * `device/linked`, against a response captured from dev on 2026-08-12.
 *
 * Pinned to the real payload because the first reading of it was wrong in a way
 * no fake would have caught: the rule looked equivalent, the list rendered, and
 * every row came back "Main device" so the page offered no way to sign anything
 * out. What the endpoint actually sends is a `primary_device_id` on each child
 * naming the account's main device, null on the main device itself, and
 * `device_id`/`parent_device_id` null on every row.
 */
class LinkedDeviceWireShapeTest {

    private val captured = """
        [
          {"_id":"main","device_id":null,"parent_device_id":null,"primary_device_id":null,
           "app_version":null,"last_activity":1786500000000,
           "device_info":{"deviceName":"samsung SM-A166U1","deviceType":"Android","osVersion":"36"}},
          {"_id":"safari","device_id":null,"parent_device_id":null,"primary_device_id":"main",
           "device_info":{"deviceName":"Safari","deviceType":"Computer","osVersion":"10.15.7"}},
          {"_id":"mac","device_id":null,"parent_device_id":null,"primary_device_id":"main",
           "device_info":{"deviceName":"sahilkashyap's MacOs","deviceType":"Computer","osVersion":"Mac OS X 26.5.1"}}
        ]
    """.trimIndent()

    private fun read(thisDeviceId: String?): List<LinkedDevice> =
        Json { ignoreUnknownKeys = true }
            .decodeFromString(ListSerializer(LinkedDeviceDto.serializer()), captured)
            .mapNotNull { it.toDomain(thisDeviceId) }

    @Test
    fun `only the device the others point at is the main one`() {
        val devices = read(null)

        assertEquals(listOf(true, false, false), devices.map { it.isPrimary })
        // The whole point of the page: four of five rows must be actionable.
        assertEquals(listOf(false, true, true), devices.map { it.canUnlink })
    }

    @Test
    fun `rows are keyed on _id, because device_id is never sent`() {
        assertEquals(listOf("main", "safari", "mac"), read(null).map { it.id })
    }

    @Test
    fun `this computer is marked, and only this computer`() {
        val devices = read("mac")
        assertEquals(listOf(false, false, true), devices.map { it.isThisDevice })
    }

    @Test
    fun `the wire's words are not the reader's`() {
        val mac = read(null).first { it.id == "mac" }
        assertEquals("sahilkashyap's MacOs", mac.displayName)
        assertEquals("Computer · Mac OS X 26.5.1", mac.detail)
    }
}
