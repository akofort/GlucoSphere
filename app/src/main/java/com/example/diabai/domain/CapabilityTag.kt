package com.example.diabai.domain

import com.example.diabai.data.McpDataCategory

/**
 * Tag-basiertes Capability-Routing: a semantic, per-TOOL capability instead of the rigid
 * per-SERVER brand dependency [McpDataCategory] already provides -- e.g. a "Nightscout" server
 * and a "Glooko" server both expose CGM tools, but neither name tells the router that on its own.
 * The chat tab's capability chips (see [CapabilityTagGroup]) and the Übersicht tab's strict
 * "nur cgm/insulin_carbs" tool filter (see `DiabetesDashboardManager`) both operate on this axis,
 * layered on top of (not replacing) [McpDataCategory]/server selection.
 */
enum class CapabilityTag {
    /** Echtzeit-Blutzucker, Sensor-Verlauf, Glukose-Trends (CGM). */
    CGM,

    /** Bolus, Basalraten, Pen-Daten, Kohlenhydrate/BE. */
    INSULIN_CARBS,

    /** Workouts, Schritte, verbrauchte Kalorien, Sport. */
    ACTIVITY,

    /** Gewicht, Körperzusammensetzung, Blutdruck. */
    BODY_METRICS,
}

/** Only an explicitly-named field/keyword counts -- deliberately narrower than a bare digit
 * regex (see [DiabetesAgent.pruneToolResultForLocal]'s own doc comment for why matching *any*
 * plausible-looking number in free text once produced dangerously wrong results): a tool's name
 * and description are short, curated text, not a heterogeneous data payload, so keyword matching
 * here carries none of that risk -- it only ever decides which CHIP/FILTER a tool shows up under,
 * never a medical value itself. */
private val cgmKeywordRegex = Regex(
    """(?i)\b(cgm|glukose|glucose|sgv|sensor|blutzucker|blood.?glucose|glucose.?entries|entries|trend|\bbg\b)""",
)
private val insulinCarbsKeywordRegex = Regex(
    """(?i)\b(insulin|bolus|basal|pen|kohlenhydrate|carbs?|\bbe\b|broteinheit|treatment|behandlung|dosis|dose)""",
)
private val activityKeywordRegex = Regex(
    """(?i)\b(workout|activity|aktivit|sport|schritte|\bsteps\b|training|trainiert|kalorien|calories|bewegung|joggen|laufen)""",
)
private val bodyMetricsKeywordRegex = Regex(
    """(?i)\b(weight|gewicht|body.?fat|k(ö|oe)rperfett|blood.?pressure|blutdruck|\bbmi\b|composition|k(ö|oe)rperzusammensetzung|scale|waage|schlaf|sleep)""",
)

/**
 * Assigns dynamic capability tags to one MCP tool (Tag-basiertes Capability-Routing, item 1) --
 * mirrors [com.example.diabai.domain.discovery.DiscoveryService]'s `looksWriteCapable()` shape:
 * a small set of curated keyword regexes over the tool's own name+description, checked first.
 * Works identically for a statically configured server's tools and for anything the Discovery
 * Modus found at runtime -- both end up as a plain [com.example.diabai.network.PooledTool] with a
 * name/description by the time this runs, so no separate handling is needed for either source.
 *
 * When the tool's own text doesn't clearly match any tag (a terse or unlabeled tool name), falls
 * back to the owning server's own [McpDataCategory] as a broad prior rather than returning nothing
 * (which would make the tool invisible to every capability chip/filter) -- a
 * [McpDataCategory.GLUCOSE_TREATMENTS] server's tools default to BOTH [CapabilityTag.CGM] and
 * [CapabilityTag.INSULIN_CARBS] in that case, since real Nightscout-style servers commonly expose
 * one blended entries+treatments tool rather than two separate ones.
 */
fun inferCapabilityTags(toolName: String, toolDescription: String?, serverCategory: McpDataCategory?): Set<CapabilityTag> {
    val text = "$toolName ${toolDescription.orEmpty()}"
    val fromText = buildSet {
        if (cgmKeywordRegex.containsMatchIn(text)) add(CapabilityTag.CGM)
        if (insulinCarbsKeywordRegex.containsMatchIn(text)) add(CapabilityTag.INSULIN_CARBS)
        if (activityKeywordRegex.containsMatchIn(text)) add(CapabilityTag.ACTIVITY)
        if (bodyMetricsKeywordRegex.containsMatchIn(text)) add(CapabilityTag.BODY_METRICS)
    }
    if (fromText.isNotEmpty()) return fromText
    return when (serverCategory) {
        McpDataCategory.GLUCOSE_TREATMENTS -> setOf(CapabilityTag.CGM, CapabilityTag.INSULIN_CARBS)
        McpDataCategory.ACTIVITY -> setOf(CapabilityTag.ACTIVITY)
        McpDataCategory.BODY_METRICS -> setOf(CapabilityTag.BODY_METRICS)
        null -> emptySet()
    }
}

/** One capability chip shown in the chat input bar (replaces the old per-brand pill row, item 1)
 * -- groups the four underlying [CapabilityTag]s into the three intuitive, german-labelled chips
 * from the requirement ("[🟢 BZ & Insulin] [🟢 Aktivität & Sport] [🟢 Körperdaten]"): CGM and
 * INSULIN_CARBS share one chip since a diabetes question is rarely about only one of the two, but
 * the underlying tool filter in [com.example.diabai.domain.DiabetesAgent.resolveTools] still
 * operates on the finer-grained tags (needed on its own for the Übersicht tab's strict
 * cgm/insulin_carbs-only restriction). */
data class CapabilityTagGroup(val label: String, val emoji: String, val tags: Set<CapabilityTag>)

val CHAT_CAPABILITY_TAG_GROUPS: List<CapabilityTagGroup> = listOf(
    CapabilityTagGroup("BZ & Insulin", "🩸", setOf(CapabilityTag.CGM, CapabilityTag.INSULIN_CARBS)),
    CapabilityTagGroup("Aktivität & Sport", "🏃", setOf(CapabilityTag.ACTIVITY)),
    CapabilityTagGroup("Körperdaten", "⚖️", setOf(CapabilityTag.BODY_METRICS)),
)

/** The exact tag combination the Übersicht tab's Stage-2 dashboard generation is locked to
 * (Turbo-Übersicht item 2): "AUSSCHLIESSLICH Tools mit den Tags cgm und insulin_carbs" -- sport/
 * body-metrics tools are never even offered to the model for this call, so it can't spend a round
 * "thinking" about calling one. [com.example.diabai.domain.analytics.DiabetesDashboardManager]'s
 * separate, explicit Körperzusammensetzung-card fetch is unaffected (it calls
 * [com.example.diabai.domain.DiabetesAgent.generateReport] with `allowedTags = null` and its own
 * server-id selection instead). */
val DASHBOARD_ALLOWED_TAGS: Set<CapabilityTag> = setOf(CapabilityTag.CGM, CapabilityTag.INSULIN_CARBS)
