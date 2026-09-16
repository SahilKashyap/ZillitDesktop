package com.zillit.desktop.feature.accounthub

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.AgreementRuleImport
import com.zillit.desktop.feature.accounthub.domain.AgreementRuleRow
import com.zillit.desktop.feature.accounthub.domain.AllowancesRentals
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.BankDetail
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.CountryTaxes
import com.zillit.desktop.feature.accounthub.domain.CurrencySettings
import com.zillit.desktop.feature.accounthub.domain.EntitlementRow
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubNavigation
import com.zillit.desktop.feature.accounthub.domain.PayRateType
import com.zillit.desktop.feature.accounthub.domain.PayTrigger
import com.zillit.desktop.feature.accounthub.domain.ProjectCurrency
import com.zillit.desktop.feature.accounthub.domain.TaxType
import com.zillit.desktop.feature.accounthub.domain.UnionAgreementSummary
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.RuleImportState
import com.zillit.desktop.feature.accounthub.ui.SectionEdit
import com.zillit.desktop.feature.accounthub.ui.SetupState
import com.zillit.desktop.feature.accounthub.ui.SetupTab
import com.zillit.desktop.feature.accounthub.ui.pages.ProductionSetupPage
import java.io.File
import kotlin.test.Test

/**
 * Production Setup as PNGs, for looking at — not asserting on.
 *
 * Opt-in: runs only when `SETUP_SHOTS` names a directory, so the normal test
 * pass stays fast and writes nothing. Tall, so a whole tab is one image.
 */
class ProductionSetupScreenshots {

    private val viewer = AccountHubViewer(
        userId = "u1",
        isAccountant = true,
        canView = true,
        canPost = true,
        canDownload = true,
        ready = true,
        viewableTools = setOf(AccountHubViewer.TOOL_IDENTIFIER),
    )

    private val gb = CountryTaxes(
        country = "United Kingdom",
        countryCode = "GB",
        taxes = listOf(
            TaxType("vat", "GB_standard", "VAT 20%", "20", country = "United Kingdom", storedCountryCode = "GB"),
            TaxType("vat", "GB_reduced", "VAT 5%", "5", country = "United Kingdom", storedCountryCode = "GB"),
            TaxType("vat", "GB_zero", "VAT 0%", "0", country = "United Kingdom", storedCountryCode = "GB"),
        ),
    )
    private val ie = CountryTaxes(
        country = "Ireland",
        countryCode = "IE",
        taxes = listOf(
            TaxType("vat", "IE_standard", "VAT 23%", "23", country = "Ireland", storedCountryCode = "IE"),
            TaxType("vat", "IE_reduced", "VAT 13.5%", "13.5", country = "Ireland", storedCountryCode = "IE"),
        ),
    )

    private val banks = listOf(
        BankAccount(
            id = "b1", name = "Barclays", entityId = "co-1", accountHolderName = "Zillit Films",
            sortCode = "204891", accountNumber = "20481234", ibanNumber = "GB29NWBK60161331926819",
            swiftCode = "BARCGB22", nominalCode = "1200", apClearanceNominalCode = "2100",
            currencyCode = "GBP", currencySymbol = "£", currencyName = "Pound Sterling",
            additionalDetails = listOf(BankDetail(title = "Routing", value = "021000021")),
        ),
        BankAccount(
            id = "b2", name = "Chase", entityId = "co-2", accountHolderName = "Zillit US Inc",
            accountNumber = "998877", swiftCode = "CHASUS33", currencyCode = "USD", currencySymbol = "$",
            currencyName = "US Dollar", chequeNumber = "100123", wireNumber = "500456",
        ),
    )

    @Suppress("LongMethod") // One fixture covering every section; splitting it hides the set.
    private fun setup() = SetupState(
        loaded = true,
        companies = SectionEdit(
            listOf(
                Company(
                    id = "co-1", name = "Zillit Films", legalName = "Zillit Films Limited",
                    country = "United Kingdom", countryCode = "GB", taxCredits = listOf("UK HETV"),
                    ukPayeRef = "120/AB12345", ukAccountsOfficeRef = "120PA00012345",
                ),
                Company(id = "co-2", name = "Zillit US Inc", country = "United States", countryCode = "US"),
                Company(id = "co-3", name = "Dormant Holdings", country = "Ireland", countryCode = "IE"),
            ),
        ),
        banks = banks,
        banksLoaded = true,
        currencies = SectionEdit(
            CurrencySettings(
                currencies = listOf(
                    ProjectCurrency("GBP", "Pound Sterling", "£", rate = 1.0),
                    ProjectCurrency("USD", "US Dollar", "$", rate = 1.27),
                    ProjectCurrency("EUR", "Euro", "€"),
                ),
                defaultCode = "GBP",
            ),
        ),
        currencyCatalogue = listOf(
            ProjectCurrency("GBP", "Pound Sterling", "£", country = "United Kingdom"),
            ProjectCurrency("USD", "US Dollar", "$", country = "United States"),
            ProjectCurrency("EUR", "Euro", "€", country = "Eurozone"),
            ProjectCurrency("JPY", "Japanese Yen", "¥", country = "Japan"),
            ProjectCurrency("CHF", "Swiss Franc", "CHF", country = "Switzerland"),
            ProjectCurrency("CAD", "Canadian Dollar", "$", country = "Canada"),
            ProjectCurrency("AUD", "Australian Dollar", "$", country = "Australia"),
            ProjectCurrency("INR", "Indian Rupee", "₹", country = "India"),
            ProjectCurrency("NZD", "New Zealand Dollar", "$", country = "New Zealand"),
        ),
        taxTypes = SectionEdit(
            listOf(
                gb.taxes[0].copy(isRecoverable = true, nominal = "2201"),
                gb.taxes[1],
                ie.taxes[0].copy(isRecoverable = true),
                TaxType("custom", "custom_1", "Levy", "2.5", isRecoverable = false),
            ),
        ),
        countryTaxes = listOf(gb, ie, CountryTaxes("France", "FR", emptyList())),
        assetTags = SectionEdit(listOf("CAMERA", "LIGHTING")),
        allowances = SectionEdit(
            AllowancesRentals(
                allowances = listOf(
                    EntitlementRow(id = "a1", name = "Per diem", amount = "45", basis = "day", appliesTo = "shoot"),
                ),
                rentals = listOf(
                    EntitlementRow(
                        id = "r1", name = "Box rental", amount = "150", basis = "week", appliesTo = "full_production",
                    ),
                    EntitlementRow(
                        id = "r2", name = "Laptop", amount = "60", basis = "week", capped = true, capAmount = "600",
                    ),
                ),
            ),
        ),
    )

    private fun state(setup: SetupState) = AccountHubUiState(
        viewer = viewer,
        sections = HubNavigation.visibleTo(viewer),
        area = HubArea.ProductionSetup,
        setup = setup,
    )

    @Test
    fun `render every surface`() {
        val dir = System.getenv("SETUP_SHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        dir.mkdirs()
        val base = setup()
        val shots = listOf(
            Triple("accounting", base, TALL),
            Triple("deal", base.copy(tab = SetupTab.DealMemo), TALL),
            Triple("company", base.copy(companyDraft = base.companies.saved.first()), HEIGHT_DP),
            Triple("company-new", base.copy(companyDraft = Company(id = "co-new")), HEIGHT_DP),
            Triple(
                "bank-from-company",
                base.copy(
                    companyDraft = base.companies.saved.first(),
                    bankDraft = BankAccount(id = "", entityId = "co-1", accountHolderName = "Zillit Films"),
                    bankDraftFromCompany = true,
                ),
                HEIGHT_DP,
            ),
            Triple("bank", base.copy(bankDraft = banks.first()), HEIGHT_DP),
            Triple(
                "import-rules",
                base.copy(
                    tab = SetupTab.DealMemo,
                    ruleImport = RuleImportState(
                        covered = setOf("uk", "us"),
                        territory = "uk",
                        agreements = listOf(UnionAgreementSummary("pact-tv", "PACT/BECTU TV Drama", "uk")),
                        agreementId = "pact-tv",
                        rules = AgreementRuleImport.project(
                            overtimes = listOf(
                                AgreementRuleRow(
                                    id = "overtime", label = "OT after 10 hrs", rateAmount = 1.5,
                                    trigger = PayTrigger(afterMinutes = 600),
                                ),
                                AgreementRuleRow(
                                    id = "meal_penalty", label = "Meal Penalty", rateType = PayRateType.Flat,
                                    rateAmount = 25.0, trigger = PayTrigger(meal = true, afterMinutes = 360),
                                ),
                            ),
                            premiums = listOf(
                                AgreementRuleRow(
                                    id = "night", label = "Night Work", rateAmount = 1.25,
                                    trigger = PayTrigger(clock = true, afterMinutes = 0),
                                ),
                            ),
                            turnarounds = emptyList(),
                            salt = "shot",
                        ),
                    ),
                ),
                HEIGHT_DP,
            ),
        )
        listOf(false, true).forEach { dark ->
            shots.forEach { (name, setup, height) ->
                shoot(File(dir, "$name-${if (dark) "dark" else "light"}.png"), state(setup), dark, height)
            }
        }
    }

    private fun shoot(file: File, state: AccountHubUiState, dark: Boolean, heightDp: Int) {
        val scene = ImageComposeScene(
            width = WIDTH_DP * DENSITY,
            height = heightDp * DENSITY,
            density = Density(DENSITY.toFloat()),
        ) {
            ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                ProductionSetupPage(state, {}, canAttachAgreements = true, canOpenDocuments = true)
            }
        }
        // A late frame: dialogs fade in, and frame 0 catches them mid-way.
        scene.render(0L)
        val image = scene.render(SETTLED_NANOS)
        file.writeBytes(requireNotNull(image.encodeToData()).bytes)
        scene.close()
    }

    private companion object {
        const val DENSITY = 2
        const val WIDTH_DP = 1180
        const val HEIGHT_DP = 900
        const val TALL = 3400
        const val SETTLED_NANOS = 2_000_000_000L
    }
}
