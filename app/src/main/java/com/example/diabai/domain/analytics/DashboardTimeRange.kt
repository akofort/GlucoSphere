package com.example.diabai.domain.analytics

import com.example.diabai.data.AppLanguage
import java.time.Instant
import java.time.ZoneId

enum class DashboardTimeRange(val label: String) {
    LAST_24H("Letzte 24h"),
    LAST_NIGHT("Letzte Nacht"),
    YESTERDAY("Gestern"),
    LAST_7_DAYS("Letzte 7 Tage"),
    LAST_30_DAYS("30 Tage"),
    LAST_3_MONTHS("3 Monate"),
    /** No fixed window of its own -- [resolveWindow] refuses to compute one; the caller
     * ([com.example.diabai.domain.analytics.DiabetesDashboardManager.refresh]) must supply an
     * explicit [TimeWindow] from the user's own date picker instead. */
    CUSTOM("Eigener Zeitraum"),
}

/** UI-facing label only (time range chips in [com.example.diabai.ui.DashboardSection]) -- kept
 * separate from the stored [DashboardTimeRange.label] property above, which stays German-only:
 * that one is also fed straight into the narrative instruction text sent to the LLM (see
 * [DiabetesDashboardManager.fetchMetrics]/[DiabetesDashboardManager.buildNarrativeInstruction]),
 * which this app's scope decision keeps German regardless of [AppLanguage] (see [AppLanguage]'s
 * own doc comment). */
fun DashboardTimeRange.displayLabel(language: AppLanguage): String = when (this) {
    DashboardTimeRange.LAST_24H -> if (language == AppLanguage.GERMAN) "Letzte 24h" else "Last 24h"
    DashboardTimeRange.LAST_NIGHT -> if (language == AppLanguage.GERMAN) "Letzte Nacht" else "Last night"
    DashboardTimeRange.YESTERDAY -> if (language == AppLanguage.GERMAN) "Gestern" else "Yesterday"
    DashboardTimeRange.LAST_7_DAYS -> if (language == AppLanguage.GERMAN) "Letzte 7 Tage" else "Last 7 days"
    DashboardTimeRange.LAST_30_DAYS -> if (language == AppLanguage.GERMAN) "30 Tage" else "30 days"
    DashboardTimeRange.LAST_3_MONTHS -> if (language == AppLanguage.GERMAN) "3 Monate" else "3 months"
    DashboardTimeRange.CUSTOM -> if (language == AppLanguage.GERMAN) "Eigener Zeitraum" else "Custom period"
}

/** [requestHours] is deliberately wider than [fromMillis]..[toMillis] for calendar-based
 * ranges (Gestern/Letzte Nacht) -- the MCP tool only takes a simple hours-back window, so a
 * generous request is over-fetched and then filtered client-side to the exact range. */
data class TimeWindow(val fromMillis: Long, val toMillis: Long, val requestHours: Int)

/** The immediately-preceding window of equal length, for trend comparison (e.g. "letzte 24h"
 * vs. "die 24h davor"). Always a plain duration shift, even for calendar-based ranges like
 * Gestern/Letzte Nacht -- "the same weekday last week" would be a different, more surprising
 * definition of "Vorzeitraum" than what was asked for. */
fun TimeWindow.previous(): TimeWindow {
    val durationMillis = toMillis - fromMillis
    return TimeWindow(fromMillis - durationMillis, fromMillis, requestHours)
}

fun DashboardTimeRange.resolveWindow(nowMillis: Long = System.currentTimeMillis()): TimeWindow {
    val zone = ZoneId.systemDefault()
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)

    return when (this) {
        DashboardTimeRange.LAST_24H -> TimeWindow(nowMillis - 24 * 60 * 60 * 1000L, nowMillis, requestHours = 24)

        DashboardTimeRange.LAST_7_DAYS -> TimeWindow(nowMillis - 7 * 24 * 60 * 60 * 1000L, nowMillis, requestHours = 7 * 24)

        DashboardTimeRange.LAST_30_DAYS -> TimeWindow(nowMillis - 30 * 24 * 60 * 60 * 1000L, nowMillis, requestHours = 30 * 24)

        DashboardTimeRange.LAST_3_MONTHS -> {
            // Calendar months, not a flat 90 days -- "3 Monate zurück" from e.g. 31. May should
            // land on 28./29. Feb, the same "go back 3 calendar months" a user actually means.
            val threeMonthsAgo = now.minusMonths(3)
            TimeWindow(threeMonthsAgo.toInstant().toEpochMilli(), nowMillis, requestHours = 24 * 92)
        }

        DashboardTimeRange.CUSTOM -> error(
            "CUSTOM hat keinen eigenen Zeitraum -- DiabetesDashboardManager.refresh() muss dafür " +
                "ein explizites TimeWindow aus der Datumsauswahl übergeben bekommen.",
        )

        DashboardTimeRange.YESTERDAY -> {
            val startOfToday = now.toLocalDate().atStartOfDay(zone)
            val startOfYesterday = startOfToday.minusDays(1)
            TimeWindow(startOfYesterday.toInstant().toEpochMilli(), startOfToday.toInstant().toEpochMilli(), requestHours = 48)
        }

        DashboardTimeRange.LAST_NIGHT -> {
            // "Last night" = 22:00-06:00. If it's currently before 06:00, that window hasn't
            // finished yet from the user's perspective -- shift back to the night before.
            val sixAmToday = now.toLocalDate().atTime(6, 0).atZone(zone)
            val (nightStart, nightEnd) = if (now.isBefore(sixAmToday)) {
                now.toLocalDate().minusDays(2).atTime(22, 0).atZone(zone) to
                    now.toLocalDate().minusDays(1).atTime(6, 0).atZone(zone)
            } else {
                now.toLocalDate().minusDays(1).atTime(22, 0).atZone(zone) to sixAmToday
            }
            TimeWindow(nightStart.toInstant().toEpochMilli(), nightEnd.toInstant().toEpochMilli(), requestHours = 36)
        }
    }
}
