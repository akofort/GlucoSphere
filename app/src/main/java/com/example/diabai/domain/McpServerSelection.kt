package com.example.diabai.domain

import com.example.diabai.data.McpDataCategory
import com.example.diabai.data.McpServerConfig
import com.example.diabai.network.NIGHTSCOUT_DIRECT_SERVER_ID

/** Keyword heuristics for routing a free-form chat request to the right MCP server category
 * without the user having to manually toggle server filter chips first -- e.g. "Wie war mein
 * Training heute?" should only query the Sport server(s), not also ask Nightscout/Glooko for no
 * reason. Deliberately simple (substring match, no NLP): returns null on any ambiguous or
 * off-topic request so the caller falls back to "every allowed server", which is always safe. */
private val sportKeywords = listOf(
    "sport", "bewegung", "training", "trainiert", "workout", "schritte", "laufen", "joggen",
    "fitness", "aktivität", "aktivitäten", "fahrrad", "radfahren", "kalorien verbrannt",
)
private val diabetesKeywords = listOf(
    "blutzucker", "glukose", "insulin", "diabetes", "hypo", "hyper", "kohlenhydrate",
    "nightscout", "glooko", "tir", "time in range", "bolus", "basal", "sensor", "cgm",
)
private val bodyMetricsKeywords = listOf(
    "gewicht", "abgenommen", "zugenommen", "muskelmasse", "muskelanteil", "körperfett",
    "körperzusammensetzung", "waage", "withings", "bmi",
)

fun inferQueryCategory(request: String): McpDataCategory? {
    val lower = request.lowercase()
    val matches = listOf(
        McpDataCategory.ACTIVITY to sportKeywords.any { lower.contains(it) },
        McpDataCategory.GLUCOSE_TREATMENTS to diabetesKeywords.any { lower.contains(it) },
        McpDataCategory.BODY_METRICS to bodyMetricsKeywords.any { lower.contains(it) },
    ).filter { (_, matched) -> matched }.map { (category, _) -> category }
    // Ambiguous (touches more than one category, or none at all) -> null, so the caller falls
    // back to every allowed server rather than guessing wrong.
    return matches.singleOrNull()
}

private val spanPattern = Regex("""(\d+)\s*(tag|tage|woche|wochen|monat|monate)""", RegexOption.IGNORE_CASE)

/** Rough day-count implied by phrasing like "die letzten 14 Tage" or "diesen Monat", or null if
 * the request doesn't mention an explicit duration at all -- used only to decide whether the
 * "> 5 Tage -> maximal ein Server pro Thema" throttling rule below should kick in for chat (the
 * Übersicht tab already knows its window exactly and applies the same rule directly from the
 * actual [com.example.diabai.domain.analytics.TimeWindow] instead of parsing text for it). */
fun inferSpanDays(request: String): Int? {
    val match = spanPattern.find(request.lowercase()) ?: return null
    val count = match.groupValues[1].toIntOrNull() ?: return null
    return when {
        match.groupValues[2].startsWith("tag") -> count
        match.groupValues[2].startsWith("woche") -> count * 7
        match.groupValues[2].startsWith("monat") -> count * 30
        else -> null
    }
}

/** Picks at most one server per [McpDataCategory] out of [allowedIds] (first one in [servers]'
 * configured order wins per category) -- the "> 5 Tage -> streng maximal EINEN MCP-Server pro
 * Thema" rule, so a multi-day fetch never doubles up on two servers covering the same category
 * (e.g. Nightscout *and* Glooko both configured for glucose) and inflates the data volume for no
 * benefit. */
fun restrictToOnePerCategory(servers: List<McpServerConfig>, allowedIds: Set<String>): Set<String> {
    val candidates = servers.filter { it.id in allowedIds }
    if (candidates.isEmpty()) return allowedIds
    return candidates.groupBy { it.category }.values.mapNotNull { it.firstOrNull()?.id }.toSet()
}

/** "Wie ist mein Wert gerade/aktuell?" style phrasing -- a proxy for "< 2 Stunden" since users
 * ask for the current reading in natural language, not literally "in den letzten 2 Stunden".
 * Deliberately narrow (a handful of fixed phrases) rather than a duration parser, matching
 * [inferQueryCategory]'s own "simple substring match, fall back to unrestricted on any doubt"
 * philosophy. */
private val currentValueKeywords = listOf(
    "aktuell", "gerade", "jetzt", "momentan", "im moment", "gerade eben", "im augenblick",
    "right now", "currently", "at the moment",
)

fun isCurrentValueQuery(request: String): Boolean {
    val lower = request.lowercase()
    return currentValueKeywords.any { lower.contains(it) }
}

/** Whether [server] is "the Nightscout one" among possibly several glucose-category sources --
 * matched by [NIGHTSCOUT_DIRECT_SERVER_ID] (the synthetic direct-REST-API server), a "nightscout"
 * substring in the display name, or the exact short name "NS" (the common real-world abbreviation
 * seen in live testing, which a plain substring match alone would miss). Shared by
 * [preferNightscoutForCurrentValue] (chat) and
 * [com.example.diabai.domain.analytics.DiabetesDashboardManager]'s own "Nightscout First"
 * dedup for the Übersicht tab (Turbo-Übersicht item 2) -- both need exactly the same "is this
 * candidate Nightscout" test, just gated behind different conditions. */
fun isNightscoutServer(server: McpServerConfig): Boolean =
    server.id == NIGHTSCOUT_DIRECT_SERVER_ID ||
        server.displayName.lowercase().contains("nightscout") ||
        server.displayName.equals("ns", ignoreCase = true)

/** Priority routing rule for [isCurrentValueQuery] requests: Nightscout reports the freshest
 * sensor value of any source this app can query, so when a glucose question is about "right now"
 * and more than one glucose-category server is in scope, narrow to just the Nightscout one(s)
 * (see [isNightscoutServer]) -- mirrors [restrictToOnePerCategory]'s "id, not name, decides which
 * stays" shape. Falls back to the untouched [allowedIds] whenever no Nightscout candidate is
 * actually present, so a Nightscout-less setup (e.g. Glooko only) is never left with zero sources. */
fun preferNightscoutForCurrentValue(servers: List<McpServerConfig>, allowedIds: Set<String>): Set<String> {
    val glucoseCandidates = servers.filter { it.id in allowedIds && it.category == McpDataCategory.GLUCOSE_TREATMENTS }
    if (glucoseCandidates.size <= 1) return allowedIds
    val nightscoutIds = glucoseCandidates.filter(::isNightscoutServer).map { it.id }.toSet()
    if (nightscoutIds.isEmpty()) return allowedIds
    val nonGlucoseIds = allowedIds - glucoseCandidates.map { it.id }.toSet()
    return nightscoutIds + nonGlucoseIds
}
