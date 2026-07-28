package com.example.diabai.domain.analytics

import android.util.Log
import com.example.diabai.data.AppLanguage
import com.example.diabai.data.McpDataCategory
import com.example.diabai.data.McpServerConfig
import com.example.diabai.data.SettingsRepository
import com.example.diabai.domain.DASHBOARD_ALLOWED_TAGS
import com.example.diabai.domain.DiabetesAgent
import com.example.diabai.domain.isNightscoutServer
import com.example.diabai.domain.restrictToOnePerCategory
import com.example.diabai.network.HttpStatusException
import com.example.diabai.network.McpServerPool
import com.example.diabai.network.NIGHTSCOUT_DIRECT_SERVER_ID
import com.example.diabai.network.NightscoutEntry
import com.example.diabai.network.fetchNightscoutDirectRawEntries
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

private const val LOG_TAG = "GlucoSphere-Dashboard"

/** True for any of the timeout flavors a refresh's network layer can throw -- the strict
 * Nightscout/MCP client timeouts (see `NightscoutDirectApi.kt`/`McpStreamableHttpClient.kt`/
 * `McpSseClient.kt`) surface as [HttpRequestTimeoutException]/[ConnectTimeoutException]/
 * [SocketTimeoutException], and any `withTimeout` elsewhere in the call chain surfaces as
 * [TimeoutCancellationException]. Public (not `private`) so `GlucoSphereViewModel` can pick between
 * "Update-Timeout – zeige letzten bekannten Stand" and a generic "Offline" Snackbar without
 * duplicating this classification. Also checks [Throwable.cause] -- a genuine timeout thrown deep
 * inside the LLM tool loop reaches here re-wrapped (see `DiabetesAgent`'s `AgentGenerationError`/
 * [fetchWithRetry]'s own re-wrap), so the original type would otherwise be invisible here. */
fun Throwable.isDashboardNetworkTimeout(): Boolean =
    this is TimeoutCancellationException ||
        this is HttpRequestTimeoutException ||
        this is ConnectTimeoutException ||
        this is SocketTimeoutException ||
        cause?.isDashboardNetworkTimeout() == true

/** True only for a genuine no-connectivity failure (DNS lookup or connection refused -- no HTTP
 * response at all) -- deliberately does NOT include [HttpStatusException]: the server was reached
 * and answered, just with an error status (wrong/expired key, retired model id, exhausted quota),
 * which is a completely different problem from "no internet" and needs its own wording rather than
 * the misleading "Offline" label (see [dashboardFailureDetail]). Same cause-chain walk as
 * [isDashboardNetworkTimeout] and for the same reason. */
fun Throwable.isDashboardOffline(): Boolean =
    this is UnknownHostException ||
        this is ConnectException ||
        cause?.isDashboardOffline() == true

/** Short, user-facing reason for a refresh failure that's neither a timeout nor genuine offline --
 * e.g. "HTTP 404" for an invalid/retired model id, or "HTTP 401" for an expired key, instead of a
 * blanket "Offline" that sends the user looking at their WLAN when the server actually responded.
 * Walks [Throwable.cause] to find the innermost [HttpStatusException] (if any -- see
 * [isDashboardNetworkTimeout]'s doc comment for why that's needed at all), otherwise falls back to
 * this exception's own message. */
fun Throwable.dashboardFailureDetail(): String {
    val httpCause = generateSequence(this as Throwable?) { it.cause }.firstOrNull { it is HttpStatusException } as? HttpStatusException
    if (httpCause != null) return "HTTP ${httpCause.statusCode}"
    return (message ?: this::class.simpleName ?: "unbekannter Fehler").take(80)
}

/**
 * Drives the Übersicht tab's traffic light through [DiabetesAgent], but keeps each LLM call as
 * small and fast as possible -- this used to be one big turn that fetched *both* the current and
 * the previous window in the same conversation and asked the model to compute status/trend/JSON
 * all at once, which reliably timed out on the 7/30-Tage ranges (two full multi-day tool results
 * stacked in one context, on top of the arithmetic and prose the model had to produce from them).
 * Now:
 *  1. The current-window and previous-window metrics are fetched with two small, single-tool-call
 *     LLM turns run **in parallel** (see [fetchMetrics]) -- each context only ever holds one
 *     window's data.
 *  2. The RED/YELLOW/GREEN status and the trend deltas are computed with plain arithmetic here
 *     (see [computeStatus]), not by the model -- faster and immune to arithmetic mistakes.
 *  3. Only the human-readable summary/tips/trend text comes from a third LLM call, and that one
 *     runs with `includeMcpTools = false` -- pure text generation over already-known numbers, no
 *     MCP round trip at all.
 */
class DiabetesDashboardManager(
    private val agent: DiabetesAgent,
    /** Offline-first Übersicht cache -- see [seedFromCache] (loaded once at app start, before the
     * first [refresh]) and [refresh]'s own cache-then-network handling (persisted on every
     * successful refresh, kept as-is on a failed background refresh). */
    private val settingsRepository: SettingsRepository,
    /** Only consulted for [isNightscoutOnline]'s "Nightscout First" liveness check (Turbo-
     * Übersicht item 2) -- an MCP-based Nightscout server only counts as the primary/1st choice
     * once it actually answered the connect handshake this session
     * ([McpServerPool.connectedServerIds]), not merely because it's configured+enabled; every
     * other use of [agent] in this class already goes through its own pool reference. */
    private val mcpServerPool: McpServerPool,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val _state = MutableStateFlow<DashboardState>(DashboardState.Idle)
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    /** Loads whatever was persisted from the last successful [refresh] (if any) and shows it
     * immediately -- called once from `GlucoSphereViewModel.init`, before the first [refresh], so the
     * Übersicht tab never opens on a blank/spinner-only screen for a returning user. A no-op if
     * there's nothing cached yet (first-ever launch) or if [refresh] has already produced a state
     * by the time this resolves. */
    suspend fun seedFromCache() {
        if (_state.value != DashboardState.Idle) return
        val cached = settingsRepository.loadDashboardCache() ?: return
        if (_state.value == DashboardState.Idle) {
            _state.value = DashboardState.Loaded(cached, isRefreshing = false)
        }
    }

    suspend fun refresh(
        timeRange: DashboardTimeRange = DashboardTimeRange.LAST_24H,
        /** The user's full configured server list -- used both for the "> 5 Tage -> maximal ein
         * Server pro Thema" throttling below and to actually fetch each candidate server's data
         * independently; pass the actual list whenever it's available (every real caller has it
         * via `settings.mcpServers`). */
        servers: List<McpServerConfig> = emptyList(),
        selectedServerIds: Set<String>? = null,
        /** Required (and only meaningful) when [timeRange] is [DashboardTimeRange.CUSTOM] -- the
         * user's own picked date range, since [DashboardTimeRange.resolveWindow] has no fixed
         * window to compute for CUSTOM on its own. */
        customWindow: TimeWindow? = null,
        /** Governs both the traffic-light label ("Grün"/"Gelb"/"Rot" vs "Green"/"Yellow"/"Red")
         * shown to the user AND the same label embedded in the narrative LLM instruction below --
         * previously hardcoded to the raw `status.name` ("GREEN"/"YELLOW"/"RED"), which leaked
         * English enum literals into an otherwise German prompt regardless of this setting. */
        appLanguage: AppLanguage = AppLanguage.GERMAN,
    ): Result<DiabetesDashboard> {
        // Offline-first: if there's already something on screen (fresh from an earlier refresh,
        // or seeded from cache via [seedFromCache]), keep showing it with a small "isRefreshing"
        // indicator instead of wiping it out for the old blocking full-screen [DashboardState.Loading]
        // spinner -- that's now reached only when there's truly nothing to show yet.
        val previousDashboard = (_state.value as? DashboardState.Loaded)?.dashboard
        _state.value = if (previousDashboard != null) {
            DashboardState.Loaded(previousDashboard, isRefreshing = true)
        } else {
            DashboardState.Loading
        }
        val refreshStartMillis = System.currentTimeMillis()
        return try {
            val window = customWindow ?: timeRange.resolveWindow()
            val previous = window.previous()

            // Data-volume throttling for long ranges: a multi-day fetch already costs more per
            // server, so cap to one server per category (e.g. only Nightscout, not also Glooko)
            // rather than compound that with redundant duplicate-category data.
            val spanDays = (window.toMillis - window.fromMillis) / (24 * 60 * 60 * 1000L)
            val allowedIds = selectedServerIds ?: servers.map { it.id }.toSet()

            // STUFE 1 (< 2s): an instant, purely deterministic live preview straight from
            // Nightscout's own REST API -- no LLM round trip at all -- so the Ampel and its
            // primary numbers are on screen almost immediately instead of waiting for the full
            // multi-call STUFE 2 pipeline below (which can legitimately take 30-90s across
            // several LLM-mediated per-server/per-window calls). Best-effort and silent on any
            // failure (wrong/missing config, offline, ...) -- STUFE 2 is still the sole source of
            // truth either way and overwrites this preview when it finishes regardless.
            var liveSnapshot: LiveGlucoseSnapshot? = null
            servers.firstOrNull { it.id == NIGHTSCOUT_DIRECT_SERVER_ID && it.id in allowedIds }?.let { directServer ->
                runCatching { fetchNightscoutDirectRawEntries(directServer, window.fromMillis, window.toMillis).getOrThrow() }
                    .mapCatching { entries -> computeLiveSnapshot(entries) ?: error("no entries in window") }
                    .onSuccess { snapshot ->
                        liveSnapshot = snapshot
                        _state.value = DashboardState.Loaded(
                            buildPreviewDashboard(previousDashboard, snapshot, window, previous),
                            isRefreshing = true,
                        )
                    }
            }

            val candidateIds = if (spanDays > 5 && servers.isNotEmpty()) {
                restrictToOnePerCategory(servers, allowedIds)
            } else {
                allowedIds
            }

            // "Nightscout First" (Turbo-Übersicht item 2): unlike restrictToOnePerCategory above
            // (only for > 5 Tage), this ALWAYS collapses multiple glucose-category candidates down
            // to just the Nightscout one(s) whenever an actually-ONLINE Nightscout source is among
            // them -- so a 7/14-day comparison window never fetches (and the model never has to
            // reconcile) a redundant second history from e.g. Glooko for the very same window.
            // "Online", not merely configured: [liveSnapshot] above already proved the synthetic
            // direct-API server reachable just now; an MCP-based Nightscout server counts only if
            // it answered this session's connect handshake (see [isNightscoutOnline]). A
            // Nightscout that's genuinely offline this session must NOT silently blank out the
            // only other configured source -- falls back to the untouched [candidateIds] whenever
            // no Nightscout candidate is online.
            val glucoseCandidates = servers.filter { it.id in candidateIds && it.category == McpDataCategory.GLUCOSE_TREATMENTS }
            val onlineNightscoutIds = glucoseCandidates
                .filter { isNightscoutServer(it) && isNightscoutOnline(it, liveSnapshot != null) }
                .map { it.id }
                .toSet()
            val nightscoutFirstIds = if (glucoseCandidates.size > 1 && onlineNightscoutIds.isNotEmpty()) {
                candidateIds - (glucoseCandidates.map { it.id }.toSet() - onlineNightscoutIds)
            } else {
                candidateIds
            }
            val candidateServers = servers.filter { it.id in nightscoutFirstIds }

            // Performance: current-window metrics, previous-window ("Vergleichs-Vorzeitraum")
            // metrics, AND the independent Körperzusammensetzung fetch all run CONCURRENTLY now
            // (one shared coroutineScope, one `async` each) instead of three separate, fully
            // sequential coroutineScope blocks one after another -- that used to mean every single
            // Übersicht refresh paid for 2-3 full LLM round trips back to back even after the
            // Turbo-Übersicht tag-restriction/Nightscout-First work already cut what EACH round
            // trip itself has to do. None of the three actually needs another one's *result* to
            // start: the previous-window fetch used to wait for the current-window fetch just to
            // skip an already-known-empty source (a small optimization, not a real dependency) --
            // that's now accepted as a rare, small extra cost (at most one wasted tool call, for a
            // source that turns out to have no current-window data) in exchange for it never
            // adding its own full round trip to the total latency. Each candidate server is still
            // queried independently rather than in one combined call -- see the (still valid)
            // reasoning below.
            //
            // One small LLM turn per server, not one combined call across every selected server:
            // a source that genuinely has no data for this window can be detected and excluded
            // instead of silently skewing (or, with only one combined call, silently being
            // favored or ignored by whatever the model decided to blend from mixed tool results
            // in one context); and this is the only way to tell *which* source disagrees when two
            // do -- the model reconciling multiple heterogeneous tool results inconsistently
            // inside one call is the likely reason sources sometimes diverged more than expected
            // before.
            val bodyMetricsServers = servers.filter { it.category == McpDataCategory.BODY_METRICS && it.id in candidateIds }
            val (perServerCurrent, perServerPrevious, bodyMetrics) = coroutineScope {
                val currentDeferred = candidateServers.associate { server ->
                    server.id to async { runCatching { fetchMetrics(timeRange.label, window, setOf(server.id)) } }
                }
                val previousDeferred = candidateServers.associate { server ->
                    server.id to async { runCatching { fetchMetrics("Vergleichs-Vorzeitraum", previous, setOf(server.id)) } }
                }
                val bodyMetricsDeferred = if (bodyMetricsServers.isEmpty()) {
                    null
                } else {
                    async { runCatching { fetchBodyMetrics(bodyMetricsServers.map { it.id }.toSet()) }.getOrNull() }
                }
                Triple(
                    currentDeferred.mapValues { it.value.await() },
                    previousDeferred.mapValues { it.value.await() },
                    bodyMetricsDeferred?.await(),
                )
            }

            val availableServerIds = perServerCurrent.filterValues { result -> result.getOrNull()?.looksEmpty() == false }.keys
            val unavailableServerIds = candidateServers.map { it.id }.toSet() - availableServerIds
            if (unavailableServerIds.isNotEmpty()) {
                Log.w(LOG_TAG, "no data for this window from server(s): $unavailableServerIds -- excluded from the combined result")
            }

            val noDataMessage = if (appLanguage == AppLanguage.GERMAN) {
                "Keine Datenquelle konnte Werte für diesen Zeitraum liefern"
            } else {
                "No data source could provide values for this period"
            }
            val metrics = availableServerIds.mapNotNull { perServerCurrent[it]?.getOrNull() }
                .let { if (it.isEmpty()) null else combineMetrics(it) }
                ?: throw IllegalStateException(
                    perServerCurrent.values.firstOrNull { it.isFailure }?.exceptionOrNull()?.message
                        ?: noDataMessage,
                )

            // Comparison period only counts from sources that actually had current-window data --
            // same selection semantics as before, just applied AFTER both fetches already ran
            // concurrently rather than by skipping the fetch itself for a known-empty source.
            val previousMetricsRaw = availableServerIds.mapNotNull { perServerPrevious[it]?.getOrNull() }
                .takeIf { it.isNotEmpty() }
                ?.let { combineMetrics(it) }
            // Some MCP tools turned out to ignore the requested date range entirely and just
            // return "recent" data regardless -- if the previous-period fetch comes back
            // byte-identical to the current period (observed in testing on real data), that's
            // not a genuine "no change", it's the same query answered twice. Treat it as no
            // comparison available rather than show a misleading 0.0% delta.
            if (previousMetricsRaw != null && previousMetricsRaw == metrics) {
                Log.w(LOG_TAG, "previous-period metrics identical to current -- MCP tool likely ignored the date range; suppressing trend")
            }
            val previousMetrics = previousMetricsRaw?.takeUnless { it == metrics }

            val evaluation = computeStatus(metrics)
            val trendDeltas = previousMetrics?.let {
                Triple(metrics.tirPercent - it.tirPercent, metrics.hypoPercent - it.hypoPercent, metrics.avgGlucose - it.avgGlucose)
            }

            val windowStart = formatGerman(window.fromMillis)
            val windowEnd = formatGerman(window.toMillis)
            val previousWindowStart = formatGerman(previous.fromMillis)
            val previousWindowEnd = formatGerman(previous.toMillis)

            // Not fetched here (this whole narrative call deliberately has no MCP tool access,
            // for speed -- see fetchNarrative), just named so the model can optionally point
            // {userName} at the chat for it if relevant, per "Das LLM soll diese Daten bei
            // Verfügbarkeit im Dashboard ... als Kontext einbeziehen". The actual "Körperzusammen-
            // setzung"-card data ([bodyMetrics]) was already fetched above, concurrently with the
            // glucose current-/previous-window calls -- restricted to whichever Körperzusammen-
            // setzung server(s) survived the same candidateIds selection/throttling the glucose
            // fetch also applied (so a >5-Tage window can't end up querying two body-metrics
            // servers either).
            val bodyMetricsServerNames = servers.filter { it.category == McpDataCategory.BODY_METRICS }.map { it.displayName }

            val narrative = fetchNarrative(
                timeRange, evaluation.status, metrics, trendDeltas, windowStart, windowEnd,
                previousMetrics != null, previousWindowStart, previousWindowEnd, availableServerIds, bodyMetricsServerNames,
                appLanguage,
            )

            // ">5 Tage -> Single-Select" is enforced up in the UI/ViewModel (only one server per
            // category ever gets selected in the first place for such ranges), so more than one
            // *available* source here only happens for <=5-day windows with multiple sources
            // explicitly selected -- exactly when this note should show.
            val combinedSourcesNote = if (availableServerIds.size > 1) {
                val names = candidateServers.filter { it.id in availableServerIds }.joinToString(", ") { it.displayName }
                "Kombinierte Auswertung ($names)"
            } else {
                null
            }

            val dashboard = DiabetesDashboard(
                overallStatus = evaluation.status,
                statusReason = evaluation.reason,
                metrics = metrics,
                trend = trendDeltas?.let { (tirDelta, hypoDelta, avgDelta) ->
                    DashboardTrend(tirDelta, hypoDelta, avgDelta, narrative.trendSummary)
                },
                summaryText = narrative.summaryText,
                tips = narrative.tips,
                windowStartMillis = window.fromMillis,
                windowEndMillis = window.toMillis,
                previousWindowStartMillis = previous.fromMillis,
                previousWindowEndMillis = previous.toMillis,
                generatedAtMillis = System.currentTimeMillis(),
                dataUnavailableServerIds = unavailableServerIds,
                combinedSourcesNote = combinedSourcesNote,
                bodyMetrics = bodyMetrics,
                generationDurationMillis = System.currentTimeMillis() - refreshStartMillis,
                latestValueMgDl = liveSnapshot?.latestValueMgDl,
                latestValueTrendArrow = liveSnapshot?.trendArrow,
            )

            _state.value = DashboardState.Loaded(dashboard)
            // A cache-write failure must never fail an otherwise-successful refresh -- the fresh
            // dashboard is already showing on screen either way; losing just the persisted-for-
            // next-launch copy is a much smaller problem than surfacing a spurious error over data
            // that actually loaded fine.
            runCatching { settingsRepository.saveDashboardCache(dashboard) }
            Result.success(dashboard)
        } catch (e: TimeoutCancellationException) {
            // A self-triggered `withTimeout` elsewhere in the call chain (e.g. McpSseClient's own
            // per-request timeout) -- IS a CancellationException subtype, but this one means "an
            // operation took too long", not "this coroutine was actually cancelled from outside".
            // Must be caught here, ahead of the plain CancellationException rethrow below, or it
            // would incorrectly propagate as real cancellation instead of a recoverable failure.
            handleRefreshFailure(e, previousDashboard, appLanguage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleRefreshFailure(e, previousDashboard, appLanguage)
        } finally {
            // Zwingender finally-Block: whatever happened above -- success, a caught failure, or
            // (via the CancellationException rethrow) actual cancellation -- the small
            // "isRefreshing" indicator must never be left spinning forever. This is a pure
            // backstop; every path above already resolves it correctly on its own, but a future
            // bug in any one of them still can't leave the UI stuck.
            (_state.value as? DashboardState.Loaded)?.takeIf { it.isRefreshing }?.let {
                _state.value = it.copy(isRefreshing = false)
            }
        }
    }

    /** "Online", for [refresh]'s "Nightscout First" dedup: the synthetic direct-REST-API server
     * has no persistent connection to check, so [liveSnapshotAvailable] (STUFE 1's own just-ran
     * live probe) stands in for it; an MCP-based Nightscout server counts only if it's actually in
     * [McpServerPool.connectedServerIds] this session. [server] itself isn't checked for being
     * Nightscout at all here -- callers already filtered to [isNightscoutServer] candidates. */
    private fun isNightscoutOnline(server: McpServerConfig, liveSnapshotAvailable: Boolean): Boolean =
        if (server.id == NIGHTSCOUT_DIRECT_SERVER_ID) liveSnapshotAvailable else server.id in mcpServerPool.connectedServerIds.value

    /** Shared by every failure branch of [refresh]: keep showing whatever was cached (per
     * "behalte einfach den gecachten Zustand bei") instead of replacing it with a full error
     * state -- [GlucoSphereViewModel] surfaces a short Snackbar for this case (distinguishing a
     * timeout from any other failure via [isDashboardNetworkTimeout]) by noticing the state is
     * still [DashboardState.Loaded] after a failed [Result]. Only a refresh with NOTHING cached
     * at all (first-ever launch) actually shows [DashboardState.Error] -- with a friendlier,
     * actionable message specifically for a first-ever-launch timeout, since "Unbekannter Fehler"
     * would otherwise be the very first thing a brand-new user ever sees. */
    private fun handleRefreshFailure(
        e: Exception,
        previousDashboard: DiabetesDashboard?,
        appLanguage: AppLanguage,
    ): Result<DiabetesDashboard> {
        _state.value = when {
            previousDashboard != null -> DashboardState.Loaded(previousDashboard, isRefreshing = false)
            e.isDashboardNetworkTimeout() -> DashboardState.Error(
                if (appLanguage == AppLanguage.GERMAN) {
                    "Noch keine Daten vorhanden – bitte überprüfe die Datenquellen in den Einstellungen"
                } else {
                    "No data available yet – please check your data sources in Settings"
                },
            )
            else -> DashboardState.Error(e.message ?: "Unbekannter Fehler beim Laden des Dashboards")
        }
        return Result.failure(e)
    }

    /** Reverted back to the model-computed-JSON approach (a brief Kotlin-side-only detour turned
     * out to produce wrong/contradictory numbers against several real MCP servers' differing
     * response shapes in live testing -- format-matching every possible tool's field-naming
     * convention client-side is inherently a losing, ever-growing game; the model reads and
     * interprets an arbitrary shape far more reliably than a fixed set of hardcoded field names
     * ever could). [buildMetricsInstruction] is written to keep the model's own reasoning
     * minimal -- see its own doc comment -- so this stays close to chat's own latency instead of
     * paying for elaborate deliberation on a simple lookup+format task. */
    private suspend fun fetchMetrics(windowLabel: String, window: TimeWindow, selectedServerIds: Set<String>?): DashboardMetrics {
        val raw = fetchWithRetry(buildMetricsInstruction(windowLabel, window), selectedServerIds)
        return parseMetrics(raw, json)
    }

    /** The Übersicht tab's "Körperzusammensetzung" card: asks whichever Withings/Health-Connect
     * server(s) are in scope for every weight/body-fat measurement over the last 90 days, then
     * hands the raw (model-reported, not model-computed) list to [parseBodyMetrics] so the actual
     * "latest reading" and "3-Monats-Trend" are derived deterministically in Kotlin -- same
     * reasoning as [computeStatus] not trusting the model's own arithmetic. Null (not thrown) on
     * any failure -- a broken body-metrics fetch shouldn't fail the whole (glucose-centric)
     * dashboard refresh. */
    private suspend fun fetchBodyMetrics(selectedServerIds: Set<String>): BodyMetricsSnapshot? {
        val now = System.currentTimeMillis()
        val window = TimeWindow(now - 90L * 24 * 60 * 60 * 1000, now, requestHours = 90 * 24)
        val raw = agent.generateReport(buildBodyMetricsInstruction(window), selectedServerIds).getOrNull() ?: return null
        return parseBodyMetrics(raw, json)
    }

    private suspend fun fetchNarrative(
        timeRange: DashboardTimeRange,
        status: TrafficLightStatus,
        metrics: DashboardMetrics,
        trendDeltas: Triple<Double, Double, Double>?,
        windowStart: String,
        windowEnd: String,
        hasPrevious: Boolean,
        previousWindowStart: String,
        previousWindowEnd: String,
        selectedServerIds: Set<String>?,
        bodyMetricsServerNames: List<String>,
        appLanguage: AppLanguage,
    ): NarrativeResult {
        val instruction = buildNarrativeInstruction(
            timeRange, status, metrics, trendDeltas, windowStart, windowEnd, hasPrevious, previousWindowStart, previousWindowEnd,
            bodyMetricsServerNames, appLanguage,
        )
        // Status/metrics/trend -- the important half of the dashboard -- are already known by
        // this point; a transient failure here (retried once) should cost the prose, not the
        // whole refresh.
        val raw = agent.generateReport(instruction, selectedServerIds, includeMcpTools = false).getOrNull()
            ?: agent.generateReport(instruction, selectedServerIds, includeMcpTools = false).getOrNull()
            ?: return NarrativeResult(summaryText = "", tips = emptyList(), trendSummary = "")
        return parseNarrative(raw, json)
    }

    /** Two independent, retried-once failure modes:
     *  - The call itself fails outright -- most commonly a transient upstream timeout from the
     *    cloud provider's own gateway (seen in testing as OpenRouter's "Socket timeout has
     *    expired", well within our own client-side timeout budget -- nothing we can tune away,
     *    but a plain retry of the same request usually goes through the second time).
     *  - The call succeeds, but the model announced intent ("Ich werde die Daten abrufen …")
     *    instead of calling a tool outright, ending the turn with no JSON and no tool call --
     *    retried with a harder nudge rather than the identical instruction.
     * Either way, failing the whole refresh on one bad turn alone would be needlessly fragile.
     *
     * Every call from this function is the Übersicht tab's core glucose-metrics fetch (current AND
     * comparison window, via [fetchMetrics]) -- so [DASHBOARD_ALLOWED_TAGS] (Turbo-Übersicht item
     * 2: strictly `cgm` + `insulin_carbs`, nothing else) is applied unconditionally here, not
     * threaded through as a parameter. [fetchBodyMetrics]/[fetchNarrative] call
     * [agent.generateReport] directly instead of through here and are correctly unaffected. */
    private suspend fun fetchWithRetry(instruction: String, selectedServerIds: Set<String>?): String {
        val first = agent.generateReport(instruction, selectedServerIds, allowedTags = DASHBOARD_ALLOWED_TAGS)
        val firstText = first.getOrNull()
        if (firstText != null && extractJsonObject(firstText) != null) return firstText

        val retryInstruction = if (firstText == null) {
            instruction
        } else {
            "$instruction\n\nWICHTIG: Deine letzte Antwort enthielt weder einen Tool-Aufruf noch JSON. " +
                "Rufe jetzt sofort das passende Tool auf und antworte danach ausschließlich mit dem " +
                "JSON-Objekt, ohne jeglichen weiteren Text."
        }
        return agent.generateReport(retryInstruction, selectedServerIds, allowedTags = DASHBOARD_ALLOWED_TAGS)
            .getOrElse { throw IllegalStateException(it.message ?: first.exceptionOrNull()?.message, it) }
    }
}

/** [DiabetesDashboardManager.fetchNarrative]'s result -- the human-readable half of the
 * dashboard, produced without any MCP tool access since the numbers are already known. */
private data class NarrativeResult(val summaryText: String, val tips: List<String>, val trendSummary: String)

/** [computeStatus]'s result: the color plus a plain-language sentence naming exactly which
 * condition(s) triggered it, e.g. "Status GELB aufgrund erhöhter Blutzucker-Variabilität (%CV = 41,2%,
 * > 36%) trotz guter Time in Range (74,2%)." -- so the traffic light is never just a color the user
 * has to reverse-engineer from the metrics list below it. */
private data class StatusEvaluation(val status: TrafficLightStatus, val reason: String)

/** Deterministic, not asked of the model: same rules, but now guaranteed to be applied
 * correctly and instantly instead of depending on the model's own arithmetic/priority handling. */
private fun computeStatus(m: DashboardMetrics): StatusEvaluation {
    val redReasons = buildList {
        if (m.severeHypoPercent > 1.0) add("schweren Unterzuckerungen (${"%.1f".format(m.severeHypoPercent)}% > 1%)")
        if (m.hypoPercent > 10.0) add("Unterzuckerungen über 10% der Zeit (${"%.1f".format(m.hypoPercent)}%)")
        if (m.tirPercent < 50.0) add("Time in Range unter 50% (${"%.1f".format(m.tirPercent)}%)")
    }
    if (redReasons.isNotEmpty()) {
        return StatusEvaluation(TrafficLightStatus.RED, "Status ROT aufgrund von ${redReasons.joinToString(" und ")}.")
    }

    val yellowReasons = buildList {
        if (m.hypoPercent in 4.0..10.0) add("erhöhtem Unterzuckerungs-Anteil (${"%.1f".format(m.hypoPercent)}%, Bereich 4–10%)")
        if (m.tirPercent in 50.0..70.0) add("Time in Range im mittleren Bereich (${"%.1f".format(m.tirPercent)}%, 50–70%)")
        if (m.cvPercent > 36.0) add("erhöhter Blutzucker-Variabilität (%CV = ${"%.1f".format(m.cvPercent)}%, > 36%)")
    }
    if (yellowReasons.isNotEmpty()) {
        // "trotz guter Time in Range" only makes sense to say when TIR itself isn't *why* it's
        // yellow -- i.e. some other condition triggered it while TIR was actually fine.
        val tirIsGood = m.tirPercent > 70.0
        val suffix = if (tirIsGood) " trotz guter Time in Range (${"%.1f".format(m.tirPercent)}%)" else ""
        return StatusEvaluation(TrafficLightStatus.YELLOW, "Status GELB aufgrund ${yellowReasons.joinToString(" und ")}$suffix.")
    }

    return StatusEvaluation(TrafficLightStatus.GREEN, "Status GRÜN -- alle Werte im empfohlenen Zielbereich.")
}

/** No usable data for the requested window -- TIR + hypo + hyper together should always sum to
 * roughly 100% if there's ANY glucose reading at all, so all three (plus the average) coming
 * back exactly zero is a reliable "this source had nothing to report" signature rather than a
 * coincidence. Used to exclude and grey out a data source instead of letting it silently pull a
 * combined result toward zero. */
internal fun DashboardMetrics.looksEmpty(): Boolean =
    tirPercent == 0.0 && hypoPercent == 0.0 && hyperPercent == 0.0 && avgGlucose == 0.0

/** Combines multiple sources' independently-fetched metrics for the same window by averaging
 * each field -- a plain, transparent combination instead of trusting the model to reconcile
 * several heterogeneous tool results inside one shared context (the likely root cause of sources
 * disagreeing more than expected: with everything in one call, the model has to silently pick a
 * winner or blend them itself, and does so inconsistently from run to run). Reduces to the single
 * value unchanged when only one source has data, the common case. */
internal fun combineMetrics(perSource: List<DashboardMetrics>): DashboardMetrics {
    if (perSource.size == 1) return perSource.single()
    return DashboardMetrics(
        tirPercent = perSource.map { it.tirPercent }.average(),
        hypoPercent = perSource.map { it.hypoPercent }.average(),
        severeHypoPercent = perSource.map { it.severeHypoPercent }.average(),
        hyperPercent = perSource.map { it.hyperPercent }.average(),
        cvPercent = perSource.map { it.cvPercent }.average(),
        avgGlucose = perSource.map { it.avgGlucose }.average(),
    )
}

/** STUFE 1's result: the freshest single reading (value/timestamp/trend arrow) plus metrics/status
 * computed deterministically from the same raw entries -- see [computeLiveSnapshot]'s doc comment
 * for why this (unlike the reverted MCP-tool-text parsing attempt) is safe to compute client-side. */
internal data class LiveGlucoseSnapshot(
    val latestValueMgDl: Double,
    val latestTimestampMillis: Long,
    val trendArrow: String,
    val metrics: DashboardMetrics,
    val status: TrafficLightStatus,
    val statusReason: String,
)

/** Deterministic Kotlin-side TIR/hypo/hyper/%CV/avg + [computeStatus] straight from [entries] --
 * safe here (unlike the reverted attempt at parsing arbitrary MCP *tool-result text*) because
 * [entries] already came from one single, well-known, fixed JSON shape (our own direct call to
 * Nightscout's `entries/sgv.json`, see [NightscoutEntry]), not free-form text whose field-naming
 * varies per third-party MCP server. Null if [entries] is empty -- nothing to preview yet. */
internal fun computeLiveSnapshot(entries: List<NightscoutEntry>): LiveGlucoseSnapshot? {
    if (entries.isEmpty()) return null
    val latest = entries.maxBy { it.dateMillis }
    val values = entries.map { it.sgvMgDl }
    val avg = values.average()
    val tir = 100.0 * values.count { it in 70.0..180.0 } / values.size
    val hypo = 100.0 * values.count { it < 70.0 } / values.size
    val severeHypo = 100.0 * values.count { it < 54.0 } / values.size
    val hyper = 100.0 * values.count { it > 180.0 } / values.size
    val stdDev = sqrt(values.sumOf { (it - avg) * (it - avg) } / values.size)
    val cv = if (avg > 0) 100.0 * stdDev / avg else 0.0
    val metrics = DashboardMetrics(
        tirPercent = tir,
        hypoPercent = hypo,
        severeHypoPercent = severeHypo,
        hyperPercent = hyper,
        cvPercent = cv,
        avgGlucose = avg,
    )
    val evaluation = computeStatus(metrics)
    return LiveGlucoseSnapshot(
        latestValueMgDl = latest.sgvMgDl,
        latestTimestampMillis = latest.dateMillis,
        trendArrow = trendArrowFor(latest.direction),
        metrics = metrics,
        status = evaluation.status,
        statusReason = evaluation.reason,
    )
}

/** Nightscout's own CGM trend words (`direction` field on every entry) mapped to a display arrow
 * -- shown next to the live value on the Übersicht tab (see [LiveGlucoseSnapshot.trendArrow]). */
private fun trendArrowFor(direction: String): String = when (direction) {
    "DoubleUp" -> "⇈"
    "SingleUp" -> "↑"
    "FortyFiveUp" -> "↗"
    "Flat" -> "→"
    "FortyFiveDown" -> "↘"
    "SingleDown" -> "↓"
    "DoubleDown" -> "⇊"
    else -> "–"
}

/** Builds STUFE 1's preview [DiabetesDashboard]: the traffic light/metrics/latest-value fields
 * come fresh from [snapshot], but the narrative (summary/tips/trend text) is intentionally left
 * blank rather than carried over from [existing] -- the Übersicht UI shows a "Berechne
 * Vergleichszeitraum ..." placeholder in that space for as long as it stays blank (see
 * DashboardSection's `isRefreshing` handling) and fades the real STUFE 2 narrative in once it
 * lands, so a stale narrative sitting there in the meantime would misleadingly look final. */
private fun buildPreviewDashboard(
    existing: DiabetesDashboard?,
    snapshot: LiveGlucoseSnapshot,
    window: TimeWindow,
    previous: TimeWindow,
): DiabetesDashboard = DiabetesDashboard(
    overallStatus = snapshot.status,
    statusReason = snapshot.statusReason,
    metrics = snapshot.metrics,
    trend = null,
    summaryText = "",
    tips = emptyList(),
    windowStartMillis = window.fromMillis,
    windowEndMillis = window.toMillis,
    previousWindowStartMillis = previous.fromMillis,
    previousWindowEndMillis = previous.toMillis,
    generatedAtMillis = snapshot.latestTimestampMillis,
    dataUnavailableServerIds = emptySet(),
    combinedSourcesNote = null,
    bodyMetrics = existing?.bodyMetrics,
    generationDurationMillis = 0L,
    latestValueMgDl = snapshot.latestValueMgDl,
    latestValueTrendArrow = snapshot.trendArrow,
)

/** Rewritten to minimize a reasoning model's (DeepSeek Reasoner and similar) own "thinking"
 * overhead -- a big share of the Übersicht tab's latency turned out to be the model deliberating
 * at length over an under-specified task, not the network round trip itself. Compared to a vaguer
 * "analysiere die Blutzuckerdaten" prompt, this: (1) states the task as a single, mechanical
 * lookup-and-format step up front ("einfache Abfrage, keine Analyse nötig"), (2) gives the exact
 * output shape as a filled example rather than an abstract schema description, and (3) explicitly
 * tells the model not to deliberate -- concrete, low-ambiguity instructions measurably shorten
 * reasoning-model "thinking" length in practice, though this can't force a specific response time
 * (a reasoning model still decides its own reasoning budget) -- the goal is chat-like latency, not
 * a guaranteed one. */
private fun buildMetricsInstruction(windowLabel: String, window: TimeWindow): String {
    val zone = ZoneId.systemDefault()
    // Kontext-Komprimierung (Turbo-Übersicht item 3), prompt-level half: for a > 24h window, steer
    // the model toward a pre-aggregated daily-statistics tool if the connected server offers one,
    // instead of a tool that returns every individual ~5-minute raw reading -- the Kotlin-side
    // half (DiabetesAgent.compressHistoricalCgmResult) still compresses a raw multi-day result
    // afterwards regardless, so this is a latency/token optimization, not the only safeguard.
    val spanHours = (window.toMillis - window.fromMillis) / (60 * 60 * 1000L)
    val aggregationHint = if (spanHours > 24) {
        "Dieser Zeitraum umfasst mehr als 24 Stunden: falls ein Tool voraggregierte Tages-Statistiken " +
            "(Tagesdurchschnitt, TIR, Min/Max, Hypo-Anzahl je Tag) anbietet, nutze DIESES Tool bevorzugt " +
            "statt eines Tools, das jeden einzelnen Rohmesswert (z. B. alle 5 Minuten) einzeln " +
            "zurückgibt -- das vermeidet unnötig große Tool-Antworten. Falls nur ein Tool mit " +
            "Rohmesswerten existiert, ist das weiterhin völlig in Ordnung.\n\n"
    } else {
        ""
    }
    return aggregationHint +
        "Einfache Abfrage, keine Analyse nötig -- führe SOFORT den folgenden einen Tool-Aufruf aus, " +
        "ohne Ankündigung, Zwischenmeldung oder langes Abwägen davor.\n\n" +
        "Rufe die Blutzuckerdaten für den Zeitraum \"$windowLabel\" ab: von ${isoWithOffset(window.fromMillis)} " +
        "bis ${isoWithOffset(window.toMillis)} (Zeitzone $zone; entspricht ${formatGerman(window.fromMillis)} " +
        "bis ${formatGerman(window.toMillis)} Uhr). Berechne aus dem Tool-Ergebnis: Time in Range (TIR, " +
        "70-180 mg/dL) in %, Hypo-Anteil (< 70 mg/dL) in %, schwere Hypos (< 54 mg/dL) in %, Hyper-Anteil " +
        "(> 180 mg/dL) in %, den Variationskoeffizienten (%CV), und den Durchschnittswert in mg/dL.\n\n" +
        "Antworte danach SOFORT und AUSSCHLIESSLICH mit einem JSON-Objekt in genau dieser Form (Beispielwerte, " +
        "keine echten Daten, keine Erklärung davor oder danach, kein Markdown-Codeblock): " +
        "{\"tirPercent\": 74.2, \"hypoPercent\": 2.1, \"severeHypoPercent\": 0.0, \"hyperPercent\": 23.7, " +
        "\"cvPercent\": 31.5, \"avgGlucose\": 142}.\n\n" +
        "WICHTIG: Falls dir kein passendes Tool zur Verfügung steht, oder das aufgerufene Tool für " +
        "diesen Zeitraum keine Daten liefert, erfinde NIEMALS plausibel wirkende Zahlen (auch nicht " +
        "die Beispielwerte oben) -- antworte in diesem Fall stattdessen exakt mit " +
        "{\"tirPercent\": 0, \"hypoPercent\": 0, \"severeHypoPercent\": 0, \"hyperPercent\": 0, " +
        "\"cvPercent\": 0, \"avgGlucose\": 0}."
}

private fun buildBodyMetricsInstruction(window: TimeWindow): String {
    val zone = ZoneId.systemDefault()
    return "Rufe mit dem verfügbaren Körperzusammensetzungs-Tool (z. B. Withings) ALLE Gewichts- und " +
        "Körperfett-Messungen im Zeitraum von ${isoWithOffset(window.fromMillis)} bis " +
        "${isoWithOffset(window.toMillis)} (Zeitzone $zone; die letzten 90 Tage) ab. Antworte in diesem " +
        "Turn NICHT mit einer Ankündigung oder Rückfrage -- rufe SOFORT das passende Tool auf. Rufe nur " +
        "EIN Tool auf.\n\n" +
        "Gib das Ergebnis AUSSCHLIESSLICH als JSON-Objekt zurück -- kein Markdown-Codeblock, kein " +
        "weiterer Text davor oder danach, in exakt diesem Format (Beispielwerte, keine echten Daten), " +
        "chronologisch aufsteigend sortiert: {\"measurements\": [{\"dateEpochMillis\": 1735689600000, " +
        "\"weightKg\": 82.3, \"bodyFatPercent\": 24.1}]}. \"bodyFatPercent\" darf null sein, wenn diese " +
        "Messgröße nicht verfügbar ist.\n\n" +
        "WICHTIG: Falls dir kein passendes Tool zur Verfügung steht, oder das aufgerufene Tool für " +
        "diesen Zeitraum keine Messungen liefert, erfinde NIEMALS plausibel wirkende Zahlen -- antworte " +
        "in diesem Fall stattdessen exakt mit {\"measurements\": []}."
}

private fun buildNarrativeInstruction(
    timeRange: DashboardTimeRange,
    status: TrafficLightStatus,
    metrics: DashboardMetrics,
    trendDeltas: Triple<Double, Double, Double>?,
    windowStart: String,
    windowEnd: String,
    hasPrevious: Boolean,
    previousWindowStart: String,
    previousWindowEnd: String,
    bodyMetricsServerNames: List<String>,
    appLanguage: AppLanguage,
): String {
    val trendText = if (hasPrevious && trendDeltas != null) {
        val (tirDelta, hypoDelta, avgDelta) = trendDeltas
        "Vergleich zum Vorzeitraum ($previousWindowStart – $previousWindowEnd Uhr): TIR-Änderung " +
            "%.1f Prozentpunkte, Hypo-Anteil-Änderung %.1f Prozentpunkte, Ø-Glukose-Änderung %.0f mg/dL " +
            "(jeweils aktueller Zeitraum minus Vorzeitraum).".format(tirDelta, hypoDelta, avgDelta)
    } else {
        "Kein Vergleichs-Vorzeitraum verfügbar."
    }
    val bodyMetricsHint = if (bodyMetricsServerNames.isNotEmpty()) {
        "\n\nZusätzlich ist über ${bodyMetricsServerNames.joinToString(", ")} auch Körperzusammensetzung " +
            "(Gewicht, Muskelanteil, Körperfett) verfügbar (hier nicht abgerufen) -- erwähne in einem Tipp " +
            "optional, dass man im Chat nach dem Zusammenhang zwischen Gewichts-/Körperdaten und diesen " +
            "Blutzuckerwerten fragen kann, falls inhaltlich passend, sonst ignoriere diesen Hinweis."
    } else {
        ""
    }
    return "Einfache Formulierungsaufgabe, keine eigene Analyse nötig -- alle Zahlen unten sind bereits " +
        "fertig berechnet, du musst sie nur noch in Prosa fassen. Antworte SOFORT, ohne langes Abwägen.\n\n" +
        "Die folgenden Blutzucker-Werte für den Zeitraum \"${timeRange.label}\" ($windowStart – " +
        "$windowEnd Uhr) wurden bereits berechnet -- Ampel-Status: ${status.label(appLanguage)}. Time in Range " +
        "${metrics.tirPercent}%, Hypo-Anteil ${metrics.hypoPercent}% (davon schwer: " +
        "${metrics.severeHypoPercent}%), Hyper-Anteil ${metrics.hyperPercent}%, Variationskoeffizient " +
        "${metrics.cvPercent}%, Durchschnittswert ${metrics.avgGlucose} mg/dL. $trendText$bodyMetricsHint\n\n" +
        "Rufe KEIN Tool auf -- nutze ausschließlich diese bereits berechneten Werte, rate keine neuen " +
        "Zahlen. Antworte SOFORT und AUSSCHLIESSLICH mit einem JSON-Objekt, ohne Markdown-Codeblock, ohne " +
        "weiteren Text: {\"summaryText\": \"kurze Zusammenfassung, ggf. mit Bezug auf die konkreten Uhrzeiten\", " +
        "\"tips\": [\"praxistauglicher Tipp 1\", \"praxistauglicher Tipp 2\"], \"trendSummary\": " +
        "\"kurzer Vergleich zum Vorzeitraum, oder ein leerer String falls keiner verfügbar ist\"}. " +
        "tips: keine Diagnose, keine Dosierungsempfehlung."
}

/** Local wall-clock time WITH an explicit UTC offset (e.g. `+02:00` in Europe/Berlin summer
 * time) instead of a bare UTC "Z" instant -- a server that (incorrectly) treats a "Z" timestamp
 * as already being local time would otherwise silently shift results by the zone offset (2h for
 * Europe/Berlin), which is exactly the mismatch this was written to rule out. */
private fun isoWithOffset(millis: Long): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

private val germanDateTimeFormat = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault())

/** `dd.MM. HH:mm`, no seconds, always the device's own timezone -- the exact format requested
 * for both the on-screen "Ausgewerteter Zeitraum" line and the LLM's own time references. */
private fun formatGerman(millis: Long): String = germanDateTimeFormat.format(Instant.ofEpochMilli(millis))

/** The model doesn't always follow "JSON only" perfectly (a stray ```json fence or a leading
 * sentence is common) -- takes the first `{...}` block found before parsing, rather than
 * requiring the whole answer to be nothing but JSON. */
private fun extractJsonObject(text: String): String? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start == -1 || end == -1 || end < start) return null
    return text.substring(start, end + 1)
}

private fun parseMetrics(rawAnswer: String, json: Json): DashboardMetrics {
    Log.d(LOG_TAG, "raw metrics answer: $rawAnswer")
    val jsonText = extractJsonObject(rawAnswer)
        ?: error("Modell-Antwort enthielt kein JSON-Objekt: \"${rawAnswer.take(200)}\"")
    val obj = runCatching { json.parseToJsonElement(jsonText) as JsonObject }
        .getOrElse { error("Modell-Antwort konnte nicht als JSON gelesen werden: \"${jsonText.take(200)}\"") }
    // Some models flatten a "metrics" wrapper in despite instructions not asking for one here --
    // check for it anyway so a stray wrapper doesn't silently zero everything out.
    val metricsObj = obj.objectField("metrics") ?: obj
    return DashboardMetrics(
        tirPercent = metricsObj.doubleField("tirPercent", "tir_percent", "tir") ?: 0.0,
        hypoPercent = metricsObj.doubleField("hypoPercent", "hypo_percent", "hypo") ?: 0.0,
        severeHypoPercent = metricsObj.doubleField("severeHypoPercent", "severe_hypo_percent", "severeHypo") ?: 0.0,
        hyperPercent = metricsObj.doubleField("hyperPercent", "hyper_percent", "hyper") ?: 0.0,
        cvPercent = metricsObj.doubleField("cvPercent", "cv_percent", "cv") ?: 0.0,
        avgGlucose = metricsObj.doubleField("avgGlucose", "avg_glucose", "average", "mean") ?: 0.0,
    )
}

/** One reported measurement -- [weightKg]/[bodyFatPercent] are independently nullable since a
 * scale reading doesn't always include body-fat impedance data. */
private data class BodyMeasurement(val dateMillis: Long, val weightKg: Double?, val bodyFatPercent: Double?)

private fun parseBodyMetrics(rawAnswer: String, json: Json): BodyMetricsSnapshot? {
    Log.d(LOG_TAG, "raw body-metrics answer: $rawAnswer")
    val jsonText = extractJsonObject(rawAnswer) ?: return null
    val obj = runCatching { json.parseToJsonElement(jsonText) as JsonObject }.getOrNull() ?: return null
    val array = (obj["measurements"] as? JsonArray) ?: return null
    val measurements = array.mapNotNull { element ->
        val measurementObj = element as? JsonObject ?: return@mapNotNull null
        val dateMillis = measurementObj.doubleField("dateEpochMillis", "date_epoch_millis", "date")?.toLong()
            ?: return@mapNotNull null
        BodyMeasurement(
            dateMillis = dateMillis,
            weightKg = measurementObj.doubleField("weightKg", "weight_kg", "weight"),
            bodyFatPercent = measurementObj.doubleField("bodyFatPercent", "body_fat_percent", "bodyFat"),
        )
    }.sortedBy { it.dateMillis }
    if (measurements.isEmpty()) return null

    val weightReadings = measurements.filter { it.weightKg != null }
    val bodyFatReadings = measurements.filter { it.bodyFatPercent != null }
    val latestWeight = weightReadings.lastOrNull()
    val latestBodyFat = bodyFatReadings.lastOrNull()
    return BodyMetricsSnapshot(
        weightKg = latestWeight?.weightKg,
        weightDateMillis = latestWeight?.dateMillis,
        bodyFatPercent = latestBodyFat?.bodyFatPercent,
        bodyFatDateMillis = latestBodyFat?.dateMillis,
        trend = computeWeightTrend(weightReadings),
    )
}

/** Plain first-vs-last comparison across the 90-Tage window, not a full regression -- simple,
 * deterministic, and matches what "3-Monats-Trend: Steigend/Fallend/Stabil" actually needs to
 * communicate. +-0.5 kg counts as noise/normal daily fluctuation rather than a real trend. */
private fun computeWeightTrend(readings: List<BodyMeasurement>): BodyMetricsTrend {
    if (readings.size < 2) return BodyMetricsTrend.UNKNOWN
    val first = readings.first().weightKg ?: return BodyMetricsTrend.UNKNOWN
    val last = readings.last().weightKg ?: return BodyMetricsTrend.UNKNOWN
    val delta = last - first
    return when {
        delta > 0.5 -> BodyMetricsTrend.UP
        delta < -0.5 -> BodyMetricsTrend.DOWN
        else -> BodyMetricsTrend.STABLE
    }
}

private fun parseNarrative(rawAnswer: String, json: Json): NarrativeResult {
    Log.d(LOG_TAG, "raw narrative answer: $rawAnswer")
    val jsonText = extractJsonObject(rawAnswer)
        ?: return NarrativeResult(summaryText = rawAnswer.trim(), tips = emptyList(), trendSummary = "")
    val obj = runCatching { json.parseToJsonElement(jsonText) as JsonObject }.getOrNull()
        ?: return NarrativeResult(summaryText = rawAnswer.trim(), tips = emptyList(), trendSummary = "")

    return NarrativeResult(
        summaryText = obj.stringField("summaryText", "summary_text", "summary").orEmpty(),
        tips = obj.stringListField("tips", "tip"),
        trendSummary = obj.stringField("trendSummary", "trend_summary").orEmpty(),
    )
}

private fun JsonObject.stringField(vararg names: String): String? {
    for (name in names) {
        val key = keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: continue
        this[key]?.jsonPrimitive?.contentOrNull?.let { return it }
    }
    return null
}

private fun JsonObject.doubleField(vararg names: String): Double? {
    for (name in names) {
        val key = keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: continue
        this[key]?.jsonPrimitive?.doubleOrNull?.let { return it }
    }
    return null
}

private fun JsonObject.objectField(vararg names: String): JsonObject? {
    for (name in names) {
        val key = keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: continue
        (this[key] as? JsonObject)?.let { return it }
    }
    return null
}

/** Accepts a real JSON array of strings (the requested shape) or, if the model collapsed it to
 * a single string instead, wraps that one string as a single-item list rather than dropping it. */
private fun JsonObject.stringListField(vararg names: String): List<String> {
    for (name in names) {
        val key = keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: continue
        val element = this[key] ?: continue
        return when (element) {
            is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> element.jsonPrimitive.contentOrNull?.let { listOf(it) } ?: emptyList()
        }
    }
    return emptyList()
}
