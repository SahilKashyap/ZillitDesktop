package com.zillit.desktop.feature.taxfiling.domain

/**
 * Where in the tool someone is — the web's `TaxFilingShell` routes.
 *
 * The base path lists the filings the service offers; `/{country}/{key}` below
 * it opens one. A return being filed is *not* a route, on the web or here:
 * it is a view inside the filing, and leaving the filing leaves it.
 */
sealed interface TaxFilingRoute {

    val path: String

    /** The country-agnostic landing page: one card per filing. */
    data object Catalog : TaxFilingRoute {
        override val path: String = BASE_PATH
    }

    /** One filing, e.g. `GB` / `mtd-vat`. */
    data class Filing(val country: String, val key: String) : TaxFilingRoute {
        override val path: String get() = "$BASE_PATH/$country/$key"

        /** The filing this client can render, or null for one it cannot. */
        val supported: SupportedFiling? get() = SupportedFiling.of(country, key)
    }

    companion object {
        /** Under the hub's path, which is the only place the tool is reached from. */
        const val BASE_PATH = "/film-tools/account-hub/tax-filing"

        /**
         * The route [path] names.
         *
         * Anything that is not `/{country}/{key}` below the base is the
         * catalogue: a half path has nothing to open, and a blank screen with
         * no way back is worse than the list it came from.
         */
        fun parse(path: String): TaxFilingRoute {
            val tail = path.substringBefore('?').takeIf { it.startsWith(BASE_PATH) }?.removePrefix(BASE_PATH)
            if (tail == null || (tail.isNotEmpty() && !tail.startsWith('/'))) return Catalog
            val segments = tail.split('/').filter { it.isNotBlank() }
            return if (segments.size < 2) Catalog else Filing(country = segments[0].uppercase(), key = segments[1])
        }
    }
}

/**
 * The filings this client has a screen for — the web's `FILING_COMPONENTS`.
 *
 * A catalogue entry with no match here opens the "isn't available yet" page
 * rather than a half-built one, exactly as the web's router does.
 */
enum class SupportedFiling(val country: String, val key: String, val regime: String, val title: String) {
    MtdVat(country = "GB", key = "mtd-vat", regime = "VAT", title = "MTD VAT"),
    ;

    val route: TaxFilingRoute.Filing get() = TaxFilingRoute.Filing(country, key)

    companion object {
        fun of(country: String, key: String): SupportedFiling? =
            entries.firstOrNull { it.country.equals(country, ignoreCase = true) && it.key == key }
    }
}
