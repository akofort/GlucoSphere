package com.example.diabai.domain.analytics

import com.example.diabai.data.AppLanguage
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class TrafficLightStatus { GREEN, YELLOW, RED }

/** "Grün/Gelb/Rot" vs. "Green/Yellow/Red" -- used both for the Übersicht tab's Ampel text and for
 * what's told to the LLM in [DiabetesDashboardManager]'s narrative instruction, so a German
 * session never leaks the bare English enum name ("GREEN") into either the UI or the model's own
 * prompt (see [AppLanguage]'s doc comment for the scope of this app's localization). */
fun TrafficLightStatus.label(language: AppLanguage): String = when (this) {
    TrafficLightStatus.GREEN -> if (language == AppLanguage.GERMAN) "Grün" else "Green"
    TrafficLightStatus.YELLOW -> if (language == AppLanguage.GERMAN) "Gelb" else "Yellow"
    TrafficLightStatus.RED -> if (language == AppLanguage.GERMAN) "Rot" else "Red"
}

/** Core glycemic metrics for one window, as computed by the model itself (see
 * [DiabetesDashboardManager]). [severeHypoPercent] (< 54 mg/dL) feeds the RED rule but isn't
 * otherwise surfaced as its own tile -- it's what makes "TIR looks okay but there were severe
 * lows" distinguishable from genuinely good control. */
@Serializable
data class DashboardMetrics(
    val tirPercent: Double,
    val hypoPercent: Double,
    val severeHypoPercent: Double,
    val hyperPercent: Double,
    val cvPercent: Double,
    val avgGlucose: Double,
)

/** Glucose Management Indicator (Bergenstal et al. 2018) -- a CGM-average-based HbA1c estimate,
 * the formula ADA/AACE recommend for continuous-monitoring averages specifically (the older
 * ADAG eAG formula was derived from periodic lab draws, not CGM streams). Deterministic, not
 * asked of the model -- same reasoning as [TrafficLightStatus] in DiabetesDashboardManager,
 * avoids the model getting simple arithmetic wrong or omitting it. NOT a substitute for a lab
 * HbA1c -- always labelled "geschätzt (GMI)" wherever it's shown, never bare "HbA1c". */
val DashboardMetrics.estimatedHbA1cPercent: Double
    get() = 3.31 + 0.02392 * avgGlucose

/** Comparison against the immediately-preceding window of equal length ([TimeWindow.previous]).
 * Deltas are nullable -- a model that skips the comparison (or a first-ever period with no
 * prior data) shouldn't block the rest of the dashboard from rendering. */
@Serializable
data class DashboardTrend(
    val tirDelta: Double?,
    val hypoDelta: Double?,
    val avgGlucoseDelta: Double?,
    val trendSummary: String,
)

@Serializable
enum class BodyMetricsTrend { UP, DOWN, STABLE, UNKNOWN }

/** Latest weight/body-fat reading plus a 90-day trend for the Übersicht tab's "Körperzusammensetzung"
 * card -- sourced from a Withings/Health Connect ([com.example.diabai.data.McpDataCategory.BODY_METRICS])
 * server if one is configured/selected, null otherwise. [trend] is a plain two-point comparison
 * (first vs. last weight reading in the 90-day window) computed deterministically in
 * [DiabetesDashboardManager], not asked of the model -- same "don't trust the model with
 * arithmetic" principle as [DashboardMetrics.estimatedHbA1cPercent]/the RED-YELLOW-GREEN rules. */
@Serializable
data class BodyMetricsSnapshot(
    val weightKg: Double?,
    val weightDateMillis: Long?,
    val bodyFatPercent: Double?,
    val bodyFatDateMillis: Long?,
    val trend: BodyMetricsTrend,
)

/**
 * The Übersicht tab's dashboard. Computed end-to-end by the LLM (see
 * [DiabetesDashboardManager]): given the MCP tool list and two explicit, timezone-correct time
 * windows (current + equal-length prior period), the model fetches glucose data for both,
 * computes [metrics] and the [trend] against the prior period, and applies the multi-parameter
 * RED/YELLOW/GREEN rules itself, returning all of it as one JSON object that this class only
 * parses -- no client-side glucose parsing or arithmetic happens here.
 */
@Serializable
data class DiabetesDashboard(
    val overallStatus: TrafficLightStatus,
    /** Plain-language sentence naming exactly which condition(s) triggered [overallStatus], e.g.
     * "Status GELB aufgrund erhöhter Blutzucker-Variabilität (%CV = 41,2%, > 36%) trotz guter
     * Time in Range (74,2%)." -- computed deterministically alongside the status itself (see
     * `DiabetesDashboardManager.computeStatus`), never asked of the model. */
    val statusReason: String,
    val metrics: DashboardMetrics,
    val trend: DashboardTrend?,
    val summaryText: String,
    val tips: List<String>,
    /** Raw epoch millis -- computed locally from [TimeWindow], not asked of the model, so the
     * displayed window is always exactly what was actually queried. Kept as millis rather than
     * pre-formatted text so the UI can both display them *and* feed the previous period straight
     * back into the custom date picker ("Vergleich zum Vorzeitraum" -> Eigener Zeitraum). */
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val previousWindowStartMillis: Long,
    val previousWindowEndMillis: Long,
    val generatedAtMillis: Long,
    /** Servers that were candidates for this refresh but returned no usable data for the window
     * (see [com.example.diabai.domain.analytics.DiabetesDashboardManager.looksEmpty]) -- excluded
     * from [metrics]/[trend] rather than silently skewing or dominating a blended answer, and
     * surfaced here so the UI can grey them out in the Datenquellen filter instead of leaving
     * the user to notice the divergence themselves. */
    val dataUnavailableServerIds: Set<String> = emptySet(),
    /** Set when more than one data source contributed to [metrics] for this window (only
     * possible for <=5-Tage ranges -- longer ranges are forced to a single source per category
     * upstream) -- e.g. "Kombinierte Auswertung (Nightscout, Glooko)", shown next to the traffic
     * light so an averaged result is never mistaken for a single source's own numbers. */
    val combinedSourcesNote: String? = null,
    /** Null when no Körperzusammensetzung-category server is configured/selected for this
     * refresh -- the card simply doesn't render rather than showing an empty state. */
    val bodyMetrics: BodyMetricsSnapshot? = null,
    /** Wall-clock duration of this refresh (request start to fully-parsed result), measured in
     * [DiabetesDashboardManager.refresh] -- shown next to "Letzter Stand" so a slow refresh is
     * visible, not just its timestamp. 0 for cache entries persisted before this field existed
     * (`ignoreUnknownKeys`/default value on decode), which simply hides the duration line. */
    val generationDurationMillis: Long = 0L,
    /** The single freshest CGM reading and its trend arrow -- from STUFE 1's instant, deterministic
     * direct-Nightscout preview (see [DiabetesDashboardManager.computeLiveSnapshot]), shown next to
     * the Ampel alongside the windowed [metrics]. Null when no direct Nightscout REST API is
     * configured (STUFE 1 never ran) -- the card simply omits this line rather than showing a
     * placeholder for a value that was never available. */
    val latestValueMgDl: Double? = null,
    val latestValueTrendArrow: String? = null,
)

/** Offline-first Übersicht caching: [DiabetesDashboard] itself doubles as the persisted cache
 * entry (its own [DiabetesDashboard.generatedAtMillis] already IS the "letzter Stand" timestamp,
 * see [SettingsRepository.saveDashboardCache]) rather than a separate DashboardCacheDto -- every
 * field on it is already a plain, JSON-friendly type, so a parallel DTO would just duplicate the
 * same shape and risk drifting out of sync with it. */
private val dashboardCacheJson = Json { ignoreUnknownKeys = true }

fun DiabetesDashboard.encodeToJson(): String = dashboardCacheJson.encodeToString(this)

fun String.decodeDashboardCache(): DiabetesDashboard? =
    if (isBlank()) null else runCatching { dashboardCacheJson.decodeFromString<DiabetesDashboard>(this) }.getOrNull()
