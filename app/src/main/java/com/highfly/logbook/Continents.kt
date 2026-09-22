package com.highfly.logbook

object Continents {

    const val AFRICA = "africa"
    const val ANTARCTICA = "antarctica"
    const val ASIA = "asia"
    const val EUROPE = "europe"
    const val NORTH_AMERICA = "north_america"
    const val OCEANIA = "oceania"
    const val SOUTH_AMERICA = "south_america"
    const val UNKNOWN = "unknown"

    val ORDER = listOf(
        EUROPE, ASIA, NORTH_AMERICA, SOUTH_AMERICA, AFRICA, OCEANIA, ANTARCTICA, UNKNOWN
    )

    private val byIso2: Map<String, String> = buildMap {
        val mapping = mapOf(
            AFRICA to listOf(
                "DZ", "AO", "BJ", "BW", "BF", "BI", "CV", "CM", "CF", "TD",
                "KM", "CG", "CD", "CI", "DJ", "EG", "GQ", "ER", "SZ", "ET",
                "GA", "GM", "GH", "GN", "GW", "KE", "LS", "LR", "LY", "MG",
                "MW", "ML", "MR", "MU", "MA", "MZ", "NA", "NE", "NG", "RW",
                "ST", "SN", "SC", "SL", "SO", "ZA", "SS", "SD", "TZ", "TG",
                "TN", "UG", "ZM", "ZW", "EH"
            ),
            ANTARCTICA to listOf("AQ", "BV", "TF", "HM", "GS"),
            ASIA to listOf(
                "AF", "AM", "AZ", "BH", "BD", "BT", "BN", "KH", "CN", "CY",
                "GE", "HK", "IN", "ID", "IR", "IQ", "IL", "JP", "JO", "KZ",
                "KW", "KG", "LA", "LB", "MO", "MY", "MV", "MN", "MM", "NP",
                "KP", "OM", "PK", "PS", "PH", "QA", "SA", "SG", "KR", "LK",
                "SY", "TW", "TJ", "TH", "TR", "TM", "AE", "UZ", "VN", "YE"
            ),
            EUROPE to listOf(
                "AL", "AD", "AT", "BY", "BE", "BA", "BG", "HR", "CZ", "DK",
                "EE", "FI", "FR", "DE", "GR", "HU", "IS", "IE", "IT", "XK",
                "LV", "LI", "LT", "LU", "MT", "MD", "MC", "ME", "NL", "MK",
                "NO", "PL", "PT", "RO", "RU", "SM", "RS", "SK", "SI", "ES",
                "SE", "CH", "UA", "GB", "VA", "FO", "GI", "GG", "IM", "JE"
            ),
            NORTH_AMERICA to listOf(
                "AG", "BS", "BB", "BZ", "BM", "CA", "CR", "CU", "CW", "DM",
                "DO", "SV", "GL", "GD", "GT", "HT", "HN", "JM", "MX", "NI",
                "PA", "KN", "LC", "VC", "TT", "US", "AW", "BQ", "KY", "GP",
                "MQ", "PR", "SX", "TC", "VG", "VI"
            ),
            OCEANIA to listOf(
                "AU", "FJ", "KI", "MH", "FM", "NR", "NZ", "PW", "PG", "WS",
                "SB", "TO", "TV", "VU", "AS", "GU", "MP", "NC", "PF", "TK",
                "WF"
            ),
            SOUTH_AMERICA to listOf(
                "AR", "BO", "BR", "CL", "CO", "EC", "FK", "GF", "GY", "PY",
                "PE", "SR", "UY", "VE"
            ),
        )
        mapping.forEach { (continent, codes) ->
            codes.forEach { put(it, continent) }
        }
    }

    fun continentOf(iso2: String): String? = byIso2[iso2.uppercase()]
}