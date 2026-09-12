package com.zillit.desktop.feature.accounthub.domain

/**
 * One country: its name, its dialling code and its ISO 3166-1 alpha-2 code.
 *
 * A row of `GET /v2/preset/isd-codes`, the list every Account Hub country and
 * dial-code picker reads on the web (ZL-20594).
 */
data class IsdCountry(val name: String, val dialCode: String, val code: String)

/**
 * Where a postcode is, as the preset lookup answers it.
 *
 * Both blank when the service knows the postcode's country but not the
 * postcode itself.
 */
data class PostcodePlace(val city: String = "", val state: String = "")

/**
 * The country catalogue, and the lookups the vendor form makes against it.
 *
 * ## Why a bundled list at all
 *
 * The web ships `data/isd-codes.js` as the fallback for when the preset call
 * fails, and so does this. A picker with no options is worse than a slightly
 * stale one: the vendor form's country is required, so an empty list would make
 * the form impossible to save.
 */
object IsdCountries {

    /** The web's bundled copy of the catalogue, in the server's order. */
    val bundled: List<IsdCountry> by lazy {
        BUNDLED.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val (code, dial, name) = line.split('|', limit = 3)
                IsdCountry(name = name, dialCode = dial, code = code)
            }
            .toList()
    }

    /**
     * The row a stored dial code reads as, or null when none is stored.
     *
     * Several countries share a code (+44 is Guernsey, the Isle of Man, Jersey
     * and the UK) and nothing on the vendor says which was meant, so the
     * vendor's own country breaks the tie and the list's first match stands in
     * otherwise, as on the web. A code the list does not hold is kept as it was
     * stored rather than shown as empty.
     */
    fun forDial(countries: List<IsdCountry>, dial: String, country: String = ""): IsdCountry? {
        if (dial.isBlank()) return null
        val matches = countries.filter { it.dialCode == dial }
        return matches.firstOrNull { it.name.equals(country, ignoreCase = true) }
            ?: matches.firstOrNull()
            ?: IsdCountry(name = "", dialCode = dial, code = "")
    }

    /** The row a stored country name reads as; a name the list does not hold is kept as stored. */
    fun forName(countries: List<IsdCountry>, name: String): IsdCountry? {
        if (name.isBlank()) return null
        return countries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: IsdCountry(name = name, dialCode = "", code = "")
    }

    /**
     * The ISO code the postcode lookup is keyed on, or blank when the catalogue
     * does not know the country. The lookup does not run then.
     */
    fun isoFor(countries: List<IsdCountry>, name: String): String =
        countries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.code.orEmpty()

    // ISO|dial|name, one per line, copied from the web's `data/isd-codes.js`.
    private const val BUNDLED = """
        AF|+93|Afghanistan
        AX|+358|Aland Islands
        AL|+355|Albania
        DZ|+213|Algeria
        AS|+1684|AmericanSamoa
        AD|+376|Andorra
        AO|+244|Angola
        AI|+1264|Anguilla
        AQ|+672|Antarctica
        AG|+1268|Antigua and Barbuda
        AR|+54|Argentina
        AM|+374|Armenia
        AW|+297|Aruba
        AU|+61|Australia
        AT|+43|Austria
        AZ|+994|Azerbaijan
        BS|+1242|Bahamas
        BH|+973|Bahrain
        BD|+880|Bangladesh
        BB|+1246|Barbados
        BY|+375|Belarus
        BE|+32|Belgium
        BZ|+501|Belize
        BJ|+229|Benin
        BM|+1441|Bermuda
        BT|+975|Bhutan
        BO|+591|Bolivia, Plurinational State of
        BA|+387|Bosnia and Herzegovina
        BW|+267|Botswana
        BR|+55|Brazil
        IO|+246|British Indian Ocean Territory
        BN|+673|Brunei Darussalam
        BG|+359|Bulgaria
        BF|+226|Burkina Faso
        BI|+257|Burundi
        KH|+855|Cambodia
        CM|+237|Cameroon
        CA|+1|Canada
        CV|+238|Cape Verde
        KY|+ 345|Cayman Islands
        CF|+236|Central African Republic
        TD|+235|Chad
        CL|+56|Chile
        CN|+86|China
        CX|+61|Christmas Island
        CC|+61|Cocos (Keeling) Islands
        CO|+57|Colombia
        KM|+269|Comoros
        CG|+242|Congo
        CD|+243|Congo, The Democratic Republic of the Congo
        CK|+682|Cook Islands
        CR|+506|Costa Rica
        CI|+225|Cote d'Ivoire
        HR|+385|Croatia
        CU|+53|Cuba
        CY|+357|Cyprus
        CZ|+420|Czech Republic
        DK|+45|Denmark
        DJ|+253|Djibouti
        DM|+1767|Dominica
        DO|+1849|Dominican Republic
        EC|+593|Ecuador
        EG|+20|Egypt
        SV|+503|El Salvador
        GQ|+240|Equatorial Guinea
        ER|+291|Eritrea
        EE|+372|Estonia
        ET|+251|Ethiopia
        FK|+500|Falkland Islands (Malvinas)
        FO|+298|Faroe Islands
        FJ|+679|Fiji
        FI|+358|Finland
        FR|+33|France
        GF|+594|French Guiana
        PF|+689|French Polynesia
        GA|+241|Gabon
        GM|+220|Gambia
        GE|+995|Georgia
        DE|+49|Germany
        GH|+233|Ghana
        GI|+350|Gibraltar
        GR|+30|Greece
        GL|+299|Greenland
        GD|+1473|Grenada
        GP|+590|Guadeloupe
        GU|+1671|Guam
        GT|+502|Guatemala
        GG|+44|Guernsey
        GN|+224|Guinea
        GW|+245|Guinea-Bissau
        GY|+595|Guyana
        HT|+509|Haiti
        VA|+379|Holy See (Vatican City State)
        HN|+504|Honduras
        HK|+852|Hong Kong
        HU|+36|Hungary
        IS|+354|Iceland
        IN|+91|India
        ID|+62|Indonesia
        IR|+98|Iran, Islamic Republic of Persian Gulf
        IQ|+964|Iraq
        IE|+353|Ireland
        IM|+44|Isle of Man
        IL|+972|Israel
        IT|+39|Italy
        JM|+1876|Jamaica
        JP|+81|Japan
        JE|+44|Jersey
        JO|+962|Jordan
        KZ|+77|Kazakhstan
        KE|+254|Kenya
        KI|+686|Kiribati
        KP|+850|Korea, Democratic People's Republic of Korea
        KR|+82|Korea, Republic of South Korea
        KW|+965|Kuwait
        KG|+996|Kyrgyzstan
        LA|+856|Laos
        LV|+371|Latvia
        LB|+961|Lebanon
        LS|+266|Lesotho
        LR|+231|Liberia
        LY|+218|Libyan Arab Jamahiriya
        LI|+423|Liechtenstein
        LT|+370|Lithuania
        LU|+352|Luxembourg
        MO|+853|Macao
        MK|+389|Macedonia
        MG|+261|Madagascar
        MW|+265|Malawi
        MY|+60|Malaysia
        MV|+960|Maldives
        ML|+223|Mali
        MT|+356|Malta
        MH|+692|Marshall Islands
        MQ|+596|Martinique
        MR|+222|Mauritania
        MU|+230|Mauritius
        YT|+262|Mayotte
        MX|+52|Mexico
        FM|+691|Micronesia, Federated States of Micronesia
        MD|+373|Moldova
        MC|+377|Monaco
        MN|+976|Mongolia
        ME|+382|Montenegro
        MS|+1664|Montserrat
        MA|+212|Morocco
        MZ|+258|Mozambique
        MM|+95|Myanmar
        NA|+264|Namibia
        NR|+674|Nauru
        NP|+977|Nepal
        NL|+31|Netherlands
        AN|+599|Netherlands Antilles
        NC|+687|New Caledonia
        NZ|+64|New Zealand
        NI|+505|Nicaragua
        NE|+227|Niger
        NG|+234|Nigeria
        NU|+683|Niue
        NF|+672|Norfolk Island
        MP|+1670|Northern Mariana Islands
        NO|+47|Norway
        OM|+968|Oman
        PK|+92|Pakistan
        PW|+680|Palau
        PS|+970|Palestinian Territory, Occupied
        PA|+507|Panama
        PG|+675|Papua New Guinea
        PY|+595|Paraguay
        PE|+51|Peru
        PH|+63|Philippines
        PN|+872|Pitcairn
        PL|+48|Poland
        PT|+351|Portugal
        PR|+1939|Puerto Rico
        QA|+974|Qatar
        RO|+40|Romania
        RU|+7|Russia
        RW|+250|Rwanda
        RE|+262|Reunion
        BL|+590|Saint Barthelemy
        SH|+290|Saint Helena, Ascension and Tristan Da Cunha
        KN|+1869|Saint Kitts and Nevis
        LC|+1758|Saint Lucia
        MF|+590|Saint Martin
        PM|+508|Saint Pierre and Miquelon
        VC|+1784|Saint Vincent and the Grenadines
        WS|+685|Samoa
        SM|+378|San Marino
        ST|+239|Sao Tome and Principe
        SA|+966|Saudi Arabia
        SN|+221|Senegal
        RS|+381|Serbia
        SC|+248|Seychelles
        SL|+232|Sierra Leone
        SG|+65|Singapore
        SK|+421|Slovakia
        SI|+386|Slovenia
        SB|+677|Solomon Islands
        SO|+252|Somalia
        ZA|+27|South Africa
        SS|+211|South Sudan
        GS|+500|South Georgia and the South Sandwich Islands
        ES|+34|Spain
        LK|+94|Sri Lanka
        SD|+249|Sudan
        SR|+597|Suriname
        SJ|+47|Svalbard and Jan Mayen
        SZ|+268|Swaziland
        SE|+46|Sweden
        CH|+41|Switzerland
        SY|+963|Syrian Arab Republic
        TW|+886|Taiwan
        TJ|+992|Tajikistan
        TZ|+255|Tanzania, United Republic of Tanzania
        TH|+66|Thailand
        TL|+670|Timor-Leste
        TG|+228|Togo
        TK|+690|Tokelau
        TO|+676|Tonga
        TT|+1868|Trinidad and Tobago
        TN|+216|Tunisia
        TR|+90|Turkey
        TM|+993|Turkmenistan
        TC|+1649|Turks and Caicos Islands
        TV|+688|Tuvalu
        UG|+256|Uganda
        UA|+380|Ukraine
        AE|+971|United Arab Emirates
        GB|+44|United Kingdom
        US|+1|United States
        UY|+598|Uruguay
        UZ|+998|Uzbekistan
        VU|+678|Vanuatu
        VE|+58|Venezuela, Bolivarian Republic of Venezuela
        VN|+84|Vietnam
        VG|+1284|Virgin Islands, British
        VI|+1340|Virgin Islands, U.S.
        WF|+681|Wallis and Futuna
        YE|+967|Yemen
        ZM|+260|Zambia
        ZW|+263|Zimbabwe
    """
}
