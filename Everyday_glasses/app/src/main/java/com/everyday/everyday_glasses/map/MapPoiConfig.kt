package com.everyday.everyday_glasses.map

import com.everyday.everyday_glasses.assistant.AssistantSemanticFact

/** Identifies whether a POI configuration is the legacy, compatibility configuration. */
enum class PoiDisplayConfigState {
    DEFAULT,
    CUSTOM
}

/** The two user-facing POI selection algorithms. */
enum class PoiDisplayMode {
    /** The quiet map algorithm: important representatives plus ordinary POIs. */
    DEFAULT,

    /** A simple category/radius/count query showing the nearest results. */
    NEAREST
}

/**
 * User-visible POI display settings.
 *
 * The exact [DEFAULT] value is retained as a compatibility profile. A user can then change any
 * Default-mode variable without changing the legacy algorithm's shape or losing the separate
 * nearest-results profile.
 */
data class PoiDisplayConfig(
    val categories: Set<String>,
    val radiusM: Int,
    val maxResults: Int,
    val state: PoiDisplayConfigState = PoiDisplayConfigState.CUSTOM,
    val mode: PoiDisplayMode = if (state == PoiDisplayConfigState.DEFAULT) {
        PoiDisplayMode.DEFAULT
    } else {
        PoiDisplayMode.NEAREST
    },
    /** Categories for which Default mode reserves the nearest representative. */
    val importantCategories: Set<String> = DEFAULT_IMPORTANT_CATEGORIES,
    /** Per-category search radius for Default mode's important representatives. */
    val importantRadiiM: Map<String, Int> = DEFAULT_IMPORTANT_RADII_M,
    /** Categories eligible for Default mode's ordinary POI pool. */
    val normalCategories: Set<String> = DEFAULT_NORMAL_CATEGORIES,
    /** Maximum number of spread-out ordinary POIs, excluding the optional closest POI. */
    val normalMaxResults: Int = DEFAULT_NORMAL_MAX_RESULTS,
    /** Whether Default mode reserves the closest ordinary POI. */
    val showClosest: Boolean = DEFAULT_SHOW_CLOSEST,
    /** Maximum distance used when choosing that closest ordinary POI. */
    val closestRadiusM: Int = DEFAULT_CLOSEST_RADIUS_M,
    /** Compatibility switch for the voice-created configuration from the previous update. */
    val balanceCategories: Boolean = state == PoiDisplayConfigState.CUSTOM
) {
    init {
        require(categories.isNotEmpty()) { "At least one POI category is required" }
        require(categories.all { it in MapPoiCategoryCatalog.supportedIds }) {
            "Unknown POI category"
        }
        require(radiusM in MIN_RADIUS_M..MAX_RADIUS_M) {
            "POI radius must be between $MIN_RADIUS_M and $MAX_RADIUS_M metres"
        }
        require(maxResults in MIN_RESULTS..MAX_RESULTS) {
            "POI result count must be between $MIN_RESULTS and $MAX_RESULTS"
        }
        require(importantCategories.all { it in MapPoiCategoryCatalog.supportedIds }) {
            "Unknown important POI category"
        }
        require(importantRadiiM.keys.all { it in MapPoiCategoryCatalog.supportedIds }) {
            "Unknown important POI radius category"
        }
        require(importantRadiiM.values.all { it in MIN_IMPORTANT_RADIUS_M..MAX_IMPORTANT_RADIUS_M }) {
            "Important POI radii must be between $MIN_IMPORTANT_RADIUS_M and $MAX_IMPORTANT_RADIUS_M metres"
        }
        require(normalCategories.all { it in MapPoiCategoryCatalog.supportedIds }) {
            "Unknown normal POI category"
        }
        require(importantCategories.intersect(normalCategories).isEmpty()) {
            "A POI category cannot be both important and normal"
        }
        require(normalMaxResults in MIN_NORMAL_RESULTS..MAX_NORMAL_RESULTS) {
            "Normal POI count must be between $MIN_NORMAL_RESULTS and $MAX_NORMAL_RESULTS"
        }
        require(closestRadiusM in MIN_RADIUS_M..MAX_RADIUS_M) {
            "Closest POI radius must be between $MIN_RADIUS_M and $MAX_RADIUS_M metres"
        }
    }

    /** True when the active algorithm is Default mode, including a user-tweaked Default profile. */
    val isDefaultMode: Boolean
        get() = mode == PoiDisplayMode.DEFAULT

    /** True only for the untouched legacy Default profile. */
    val isDefault: Boolean
        get() = this == DEFAULT

    /** Returns a copy whose active profile is Default mode. */
    fun asDefaultMode(): PoiDisplayConfig = copy(
        state = if (this == DEFAULT) PoiDisplayConfigState.DEFAULT else PoiDisplayConfigState.CUSTOM,
        mode = PoiDisplayMode.DEFAULT,
        balanceCategories = false
    )

    /** Returns a copy whose active profile is the simple nearest-results mode. */
    fun asNearestMode(): PoiDisplayConfig = copy(
        state = PoiDisplayConfigState.CUSTOM,
        mode = PoiDisplayMode.NEAREST,
        balanceCategories = false
    )

    companion object {
        const val MIN_RADIUS_M = 50
        const val MAX_RADIUS_M = 5_000
        const val MIN_RESULTS = 1
        const val MAX_RESULTS = 20

        const val MIN_IMPORTANT_RADIUS_M = 50
        const val MAX_IMPORTANT_RADIUS_M = 8_000
        const val MIN_NORMAL_RESULTS = 0
        const val MAX_NORMAL_RESULTS = 20

        /** The normal non-priority POI fetch radius in the legacy query. */
        const val DEFAULT_RADIUS_M = 400
        const val DEFAULT_MAX_RESULTS = 8
        const val DEFAULT_NORMAL_MAX_RESULTS = 4
        const val DEFAULT_SHOW_CLOSEST = true
        const val DEFAULT_CLOSEST_RADIUS_M = 160
        const val DEFAULT_STATION_RADIUS_M = 8_000
        const val DEFAULT_LOCAL_IMPORTANT_RADIUS_M = 1_609

        val DEFAULT_IMPORTANT_CATEGORIES: Set<String> = setOf(
            MapPoiCategoryCatalog.STATION,
            MapPoiCategoryCatalog.SUPERMARKET,
            MapPoiCategoryCatalog.SCHOOL
        )
        val DEFAULT_IMPORTANT_RADII_M: Map<String, Int> = mapOf(
            MapPoiCategoryCatalog.STATION to DEFAULT_STATION_RADIUS_M,
            MapPoiCategoryCatalog.SUPERMARKET to DEFAULT_LOCAL_IMPORTANT_RADIUS_M,
            MapPoiCategoryCatalog.SCHOOL to DEFAULT_LOCAL_IMPORTANT_RADIUS_M
        )
        val DEFAULT_NORMAL_CATEGORIES: Set<String> =
            MapPoiCategoryCatalog.DEFAULT_CATEGORIES - DEFAULT_IMPORTANT_CATEGORIES

        val DEFAULT = PoiDisplayConfig(
            categories = MapPoiCategoryCatalog.DEFAULT_CATEGORIES,
            radiusM = DEFAULT_RADIUS_M,
            maxResults = DEFAULT_MAX_RESULTS,
            state = PoiDisplayConfigState.DEFAULT,
            mode = PoiDisplayMode.DEFAULT,
            importantCategories = DEFAULT_IMPORTANT_CATEGORIES,
            importantRadiiM = DEFAULT_IMPORTANT_RADII_M,
            normalCategories = DEFAULT_NORMAL_CATEGORIES,
            normalMaxResults = DEFAULT_NORMAL_MAX_RESULTS,
            showClosest = DEFAULT_SHOW_CLOSEST,
            closestRadiusM = DEFAULT_CLOSEST_RADIUS_M,
            balanceCategories = false
        )

        fun custom(categories: Set<String>, radiusM: Int, maxResults: Int): PoiDisplayConfig =
            nearest(categories, radiusM, maxResults, balanceCategories = true)

        /** A user-facing nearest-results profile; unlike the legacy voice profile it is pure distance order. */
        fun nearest(categories: Set<String>, radiusM: Int, maxResults: Int): PoiDisplayConfig =
            nearest(categories, radiusM, maxResults, balanceCategories = false)

        private fun nearest(
            categories: Set<String>,
            radiusM: Int,
            maxResults: Int,
            balanceCategories: Boolean
        ): PoiDisplayConfig = PoiDisplayConfig(
                categories = MapPoiCategoryCatalog.normalizeCategories(categories),
                radiusM = radiusM,
                maxResults = maxResults,
                state = PoiDisplayConfigState.CUSTOM,
                mode = PoiDisplayMode.NEAREST,
                balanceCategories = balanceCategories
            )
    }
}

/** Stable category IDs shared by the canvas menu, voice tool, selector, and Overpass query. */
object MapPoiCategoryCatalog {
    const val STATION = "station"
    const val SUPERMARKET = "supermarket"
    const val SCHOOL = "school"
    const val HOSPITAL = "hospital"
    const val POLICE = "police"
    const val BUS_STATION = "bus_station"
    const val FERRY_TERMINAL = "ferry_terminal"
    const val PLACE_OF_WORSHIP = "place_of_worship"
    const val CAFE = "cafe"
    const val RESTAURANT = "restaurant"
    const val PHARMACY = "pharmacy"
    const val FUEL = "fuel"
    const val LIBRARY = "library"
    const val MARKETPLACE = "marketplace"
    const val SHOP = "shop"
    const val TOURISM = "tourism"
    const val HISTORIC = "historic"

    data class Definition(
        val id: String,
        val label: String,
        val overpassTag: String,
        val exactValue: String? = null,
        val valuesRegex: String? = null
    )

    /** Menu order is also the stable order used in assistant schemas and context summaries. */
    val definitions: List<Definition> = listOf(
        Definition(STATION, "Stations", "railway", exactValue = "station"),
        Definition(SUPERMARKET, "Supermarkets", "shop", exactValue = "supermarket"),
        Definition(SCHOOL, "Schools", "amenity", valuesRegex = "kindergarten|school|college|university"),
        Definition(HOSPITAL, "Hospitals", "amenity", exactValue = "hospital"),
        Definition(POLICE, "Police", "amenity", exactValue = "police"),
        Definition(BUS_STATION, "Bus stations", "amenity", exactValue = "bus_station"),
        Definition(FERRY_TERMINAL, "Ferry terminals", "amenity", exactValue = "ferry_terminal"),
        Definition(PHARMACY, "Pharmacies", "amenity", exactValue = "pharmacy"),
        Definition(FUEL, "Fuel", "amenity", exactValue = "fuel"),
        Definition(RESTAURANT, "Restaurants", "amenity", exactValue = "restaurant"),
        Definition(SHOP, "Shops", "shop"),
        Definition(TOURISM, "Tourism", "tourism"),
        Definition(PLACE_OF_WORSHIP, "Places of worship", "amenity", exactValue = "place_of_worship"),
        Definition(CAFE, "Cafes", "amenity", exactValue = "cafe"),
        Definition(LIBRARY, "Libraries", "amenity", exactValue = "library"),
        Definition(MARKETPLACE, "Markets", "amenity", exactValue = "marketplace"),
        Definition(HISTORIC, "Historic places", "historic")
    ).distinctBy(Definition::id)

    val supportedIds: Set<String> = definitions.map(Definition::id).toSet()
    val DEFAULT_CATEGORIES: Set<String> = supportedIds

    private val definitionsById = definitions.associateBy(Definition::id)

    private val aliases = mapOf(
        "stations" to STATION,
        "supermarkets" to SUPERMARKET,
        "schools" to SCHOOL,
        "hospitals" to HOSPITAL,
        "police_stations" to POLICE,
        "bus_stations" to BUS_STATION,
        "ferry_terminals" to FERRY_TERMINAL,
        "worship" to PLACE_OF_WORSHIP,
        "places_of_worship" to PLACE_OF_WORSHIP,
        "restaurants" to RESTAURANT,
        "pharmacies" to PHARMACY,
        "shops" to SHOP,
        "cafes" to CAFE,
        "libraries" to LIBRARY,
        "markets" to MARKETPLACE,
        "historic_places" to HISTORIC
    )

    private val sparseEmergencyAndTransport = setOf(
        HOSPITAL, POLICE, STATION, BUS_STATION, FERRY_TERMINAL
    )
    private val oneKilometreCategories = setOf(PHARMACY, SUPERMARKET, FUEL, SCHOOL)
    private val fiveHundredMetreCategories = setOf(
        RESTAURANT, SHOP, TOURISM, PLACE_OF_WORSHIP
    )

    fun definition(id: String): Definition = requireNotNull(definitionsById[id]) {
        "Unknown POI category: $id"
    }

    /** Expands voice-friendly group names and normalizes OSM-style category strings. */
    fun normalize(raw: String): Set<String> {
        val value = raw.trim().lowercase()
            .replace('-', '_')
            .replace(' ', '_')
        if (value.isBlank()) throw IllegalArgumentException("POI category must not be blank")
        when (value) {
            "emergency", "emergency_services" -> return setOf(HOSPITAL, POLICE)
            "transport", "transportation" -> return setOf(STATION, BUS_STATION, FERRY_TERMINAL)
        }
        aliases[value]?.let { return setOf(it) }
        if (value in supportedIds) return setOf(value)

        val separator = value.indexOf('=')
        if (separator > 0 && separator < value.lastIndex) {
            val tag = value.substring(0, separator)
            val tagValue = value.substring(separator + 1)
            return when (tag) {
                "railway" -> if (tagValue == "station") setOf(STATION) else emptySet()
                "amenity" -> when {
                    tagValue in setOf("kindergarten", "school", "college", "university") -> setOf(SCHOOL)
                    tagValue in supportedIds -> setOf(tagValue)
                    else -> emptySet()
                }
                "shop" -> if (tagValue == SUPERMARKET) setOf(SUPERMARKET) else setOf(SHOP)
                "tourism" -> setOf(TOURISM)
                "historic" -> setOf(HISTORIC)
                else -> emptySet()
            }.also { result ->
                if (result.isEmpty()) throw IllegalArgumentException("Unknown POI category: $raw")
            }
        }
        throw IllegalArgumentException("Unknown POI category: $raw")
    }

    fun normalizeCategories(raw: Set<String>): Set<String> =
        raw.flatMap(::normalize).toSet().also {
            require(it.isNotEmpty()) { "At least one POI category is required" }
        }

    /** Maps the raw category stored in [MapPoi] to a selector/configuration category. */
    fun categoryForPoi(category: String): String? =
        runCatching { normalize(category).firstOrNull() }.getOrNull()

    /** Returns the radius default used for omitted voice arguments. */
    fun inferredRadiusM(categories: Set<String>): Int {
        val normalized = normalizeCategories(categories)
        return when {
            normalized.any { it in sparseEmergencyAndTransport } -> 2_000
            normalized.any { it in oneKilometreCategories } -> 1_000
            normalized.any { it in fiveHundredMetreCategories } -> 500
            else -> 500
        }
    }

    fun label(id: String): String = definition(id).label
}

fun PoiDisplayConfig.toAssistantSemanticFacts(): List<AssistantSemanticFact> = listOf(
    AssistantSemanticFact("poi_config_state", state.name),
    AssistantSemanticFact("poi_display_mode", mode.name),
    AssistantSemanticFact("poi_categories", categories.sorted().joinToString(",")),
    AssistantSemanticFact("poi_radius_m", radiusM.toString()),
    AssistantSemanticFact("poi_max_results", maxResults.toString()),
    AssistantSemanticFact(
        "poi_important_categories",
        importantCategories.sorted().joinToString(",")
    ),
    AssistantSemanticFact(
        "poi_important_radii_m",
        importantRadiiM.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }
    ),
    AssistantSemanticFact("poi_normal_categories", normalCategories.sorted().joinToString(",")),
    AssistantSemanticFact("poi_normal_max_results", normalMaxResults.toString()),
    AssistantSemanticFact("poi_show_closest", showClosest.toString()),
    AssistantSemanticFact("poi_closest_radius_m", closestRadiusM.toString())
)
