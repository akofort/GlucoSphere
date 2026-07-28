package com.example.diabai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId

/** How many days of daily usage buckets to keep -- matches the "Letzte 30 Tage" card in
 * "Über GlucoSphere". Older buckets are pruned on every write rather than kept forever. */
const val LLM_USAGE_RETENTION_DAYS = 30L

/** One day's cumulative cloud-LLM token usage, keyed by ISO date (`yyyy-MM-dd`, always the
 * device's local date) -- coarse enough to answer "heute" / "letzte 30 Tage" without storing an
 * unbounded per-message log. Local-model usage is never recorded here (see
 * [com.example.diabai.domain.DiabetesAgent]) -- it costs nothing and never leaves the device. */
@Serializable
data class DailyLlmUsage(
    val dateKey: String,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
)

private val llmUsageJson = Json { ignoreUnknownKeys = true }

fun List<DailyLlmUsage>.encodeToJson(): String = llmUsageJson.encodeToString(this)

fun String.decodeDailyLlmUsage(): List<DailyLlmUsage> =
    if (isBlank()) {
        emptyList()
    } else {
        runCatching { llmUsageJson.decodeFromString<List<DailyLlmUsage>>(this) }.getOrElse { emptyList() }
    }

/** Today's date key in the device's own timezone -- the same key [addUsage] buckets by. */
fun todayUsageDateKey(): String = LocalDate.now(ZoneId.systemDefault()).toString()

/** Adds one turn's token counts to today's bucket (creating it if this is the first cloud call
 * today), then drops any bucket older than [LLM_USAGE_RETENTION_DAYS] -- ISO date strings sort
 * lexicographically the same as chronologically, so a plain string comparison is enough. */
fun List<DailyLlmUsage>.addUsage(dateKey: String, promptTokens: Int, completionTokens: Int): List<DailyLlmUsage> {
    val updated = if (any { it.dateKey == dateKey }) {
        map {
            if (it.dateKey == dateKey) {
                it.copy(promptTokens = it.promptTokens + promptTokens, completionTokens = it.completionTokens + completionTokens)
            } else {
                it
            }
        }
    } else {
        this + DailyLlmUsage(dateKey, promptTokens.toLong(), completionTokens.toLong())
    }
    val cutoffKey = LocalDate.now(ZoneId.systemDefault()).minusDays(LLM_USAGE_RETENTION_DAYS).toString()
    return updated.filter { it.dateKey >= cutoffKey }.sortedByDescending { it.dateKey }
}

/** Rough, deliberately simplified blended cost estimate across the app's "günstige Modelle"
 * tiers -- not tied to which specific provider/model actually answered (this app doesn't record
 * that per bucket, only the token counts), so treat this as a ballpark, not an invoice. Rates are
 * an approximate EUR/1M-token blend of typical cheap-tier cloud pricing (input notably cheaper
 * than output). */
fun List<DailyLlmUsage>.estimatedCostEuros(): Double {
    val promptTokens = sumOf { it.promptTokens }
    val completionTokens = sumOf { it.completionTokens }
    return (promptTokens / 1_000_000.0) * 0.27 + (completionTokens / 1_000_000.0) * 1.10
}
