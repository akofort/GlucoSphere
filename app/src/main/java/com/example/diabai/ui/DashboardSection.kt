package com.example.diabai.ui

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.diabai.data.AppLanguage
import com.example.diabai.data.McpServerConfig
import com.example.diabai.domain.analytics.BodyMetricsSnapshot
import com.example.diabai.domain.analytics.BodyMetricsTrend
import com.example.diabai.domain.analytics.DashboardMetrics
import com.example.diabai.domain.analytics.DashboardState
import com.example.diabai.domain.analytics.DashboardTimeRange
import com.example.diabai.domain.analytics.DashboardTrend
import com.example.diabai.domain.analytics.TrafficLightStatus
import com.example.diabai.domain.analytics.displayLabel
import com.example.diabai.domain.analytics.estimatedHbA1cPercent
import com.example.diabai.domain.analytics.label
import com.example.diabai.ui.theme.sourceTypeAccentColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

private val windowFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault())

private fun Long.toWindowLabel(): String = windowFormatter.format(Instant.ofEpochMilli(this))

/** Plain-text rendering of the current Ampel status/metrics/summary for [android.content.Intent.ACTION_SEND]
 * (see [MainTabsScreen]'s Share icon) -- deliberately not markdown/HTML since the target app (WhatsApp,
 * Mail, ...) is unknown and plain text renders correctly everywhere. */
fun buildDashboardShareText(dashboard: com.example.diabai.domain.analytics.DiabetesDashboard, strings: Strings, appLanguage: AppLanguage): String {
    val m = dashboard.metrics
    return buildString {
        appendLine("GlucoSphere – ${strings.overviewTitle}")
        appendLine(strings.lastUpdated(dashboard.generatedAtMillis.toWindowLabel()))
        appendLine()
        appendLine("Status: ${dashboard.overallStatus.label(appLanguage)}")
        appendLine(dashboard.statusReason)
        appendLine()
        appendLine("${strings.metricTir}: ${"%.1f".format(m.tirPercent)}%")
        appendLine("${strings.metricHypo}: ${"%.1f".format(m.hypoPercent)}% (${strings.metricSevereHypo}: ${"%.1f".format(m.severeHypoPercent)}%)")
        appendLine("${strings.metricHyper}: ${"%.1f".format(m.hyperPercent)}%")
        appendLine("${strings.metricVariability}: ${"%.1f".format(m.cvPercent)}%")
        appendLine("${strings.metricAvgGlucose}: ${"%.0f".format(m.avgGlucose)} mg/dL")
        if (dashboard.summaryText.isNotBlank()) {
            appendLine()
            appendLine(strings.summaryTitle + ":")
            appendLine(dashboard.summaryText)
        }
        if (dashboard.tips.isNotEmpty()) {
            appendLine()
            appendLine(strings.tipsTitle + ":")
            dashboard.tips.forEach { appendLine("• $it") }
        }
    }.trim()
}

/**
 * Header, Zeitraum-Auswahl, Datenquellen-Chips and the Ampel itself stay pinned at the top --
 * only the metrics/trend/summary/tips below scroll, in their own [Modifier.verticalScroll]
 * inside a `Modifier.weight(1f)` child, so the Ampel is never scrolled out of view on shorter
 * screens or at large system font scale.
 *
 * Zeitraum and Datenquellen are collapsed into one accordion, closed by default, showing only a
 * one-line summary ("Letzte 24h · NS, Glooko") underneath the "Übersicht" title -- previously
 * both were always fully expanded, which could push the Ampel itself below the fold on shorter
 * screens; collapsed by default, the Ampel is the first thing visible right after opening the
 * tab, with no scrolling required. Expanding shows both pickers in a non-scrolling, wrapping
 * [FlowRow]/[Column] layout -- there's deliberately no separate inner scroll area for the
 * Datenquellen-Chips even with many servers, since that would risk hiding chips from view instead
 * of just taking a bit more vertical space in the (otherwise-still-scrollable) full screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardSection(
    state: DashboardState,
    servers: List<McpServerConfig>,
    selectedTimeRange: DashboardTimeRange,
    selectedServerIds: Set<String>?,
    customRangeStart: Long?,
    customRangeEnd: Long?,
    /** ">5 Tage" -- Datenquellen-Chips behave as single-select (see [GlucoSphereViewModel.toggleServerSelected])
     * and show a short explanatory line instead of allowing free multi-select. */
    isSingleSelectMode: Boolean,
    onTimeRangeChange: (DashboardTimeRange) -> Unit,
    onToggleServer: (String) -> Unit,
    onRefresh: () -> Unit,
    onCustomRangeStartChange: (Long) -> Unit,
    onCustomRangeEndChange: (Long) -> Unit,
    onApplyCustomRange: () -> Unit,
    onUseComparisonPeriod: () -> Unit,
    appLanguage: AppLanguage = AppLanguage.GERMAN,
    ttsController: TtsController? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }

    // Pull-to-Refresh (item 3): a native swipe-down gesture alongside the existing "Aktualisieren"
    // text button -- both trigger the exact same [onRefresh], which already drives the full
    // progressive two-stage refresh (instant Ampel/BZ in <2s, then the async KI-Vergleichsbericht)
    // and already guarantees isRefreshing ends up false again via a try/catch/finally covering
    // timeouts, offline, and any other failure (see DiabetesDashboardManager.refresh) -- this
    // box's spinner just mirrors that same state, so it can never get stuck independently of it.
    val isRefreshing = (state as? DashboardState.Loaded)?.isRefreshing ?: (state == DashboardState.Loading)

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxWidth().fillMaxHeight(),
    ) {
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = strings.overviewTitle,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) { Text(strings.refresh) }
        }

        val selectedServerSummary = if (servers.size > 1) {
            val selectedNames = servers.filter { selectedServerIds?.contains(it.id) ?: true }.map { it.displayName }
            if (selectedNames.isEmpty() || selectedNames.size == servers.size) strings.allSources else selectedNames.joinToString(", ")
        } else {
            null
        }
        val filterSummary = listOfNotNull(selectedTimeRange.displayLabel(appLanguage), selectedServerSummary).joinToString(" · ")

        Row(
            modifier = Modifier.fillMaxWidth().clickable { filtersExpanded = !filtersExpanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = filterSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (filtersExpanded) strings.collapseFilters else strings.editFilters,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (filtersExpanded) {
            Spacer(Modifier.height(8.dp))
            TimeRangeSelector(selected = selectedTimeRange, onSelect = onTimeRangeChange, appLanguage = appLanguage)

            if (selectedTimeRange == DashboardTimeRange.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                CustomRangePicker(
                    startMillis = customRangeStart,
                    endMillis = customRangeEnd,
                    onStartChange = onCustomRangeStartChange,
                    onEndChange = onCustomRangeEndChange,
                    onApply = onApplyCustomRange,
                )
            }

            val dataUnavailableServerIds = (state as? DashboardState.Loaded)?.dashboard?.dataUnavailableServerIds.orEmpty()

            if (servers.size > 1) {
                Spacer(Modifier.height(12.dp))
                Text(strings.dataSources, style = MaterialTheme.typography.titleSmall)
                if (isSingleSelectMode) {
                    Text(
                        text = strings.singleSelectHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    servers.forEach { server ->
                        val isSelected = selectedServerIds?.contains(server.id) ?: true
                        val hasNoData = server.id in dataUnavailableServerIds
                        val accentColor = server.sourceTypeAccentColor
                        FilterChip(
                            selected = isSelected,
                            onClick = { onToggleServer(server.id) },
                            label = {
                                Text(
                                    "${server.displayName} · ${server.category.shortLabel}" +
                                        if (hasNoData) strings.noDataSuffix else "",
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accentColor.copy(alpha = 0.22f),
                                selectedLabelColor = accentColor,
                                selectedLeadingIconColor = accentColor,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = accentColor.copy(alpha = 0.5f),
                                selectedBorderColor = accentColor,
                            ),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when (state) {
            // Manuelle Aktualisierung (item 1): with the app-start auto-refresh removed (see
            // GlucoSphereViewModel's init), Idle is no longer just a fleeting instant before an
            // automatic fetch resolves it -- for a brand-new install (nothing ever cached), it's
            // now a real, potentially long-lived state. An indefinite spinner here (the old,
            // shared-with-Loading rendering) would never resolve on its own anymore, so this gets
            // its own friendly empty-state prompt with an explicit call-to-action instead --
            // Loading (reached only once the user has actually triggered a fetch and nothing was
            // cached yet to show meanwhile) keeps the spinner, since that one's guaranteed to
            // resolve shortly either way.
            DashboardState.Idle -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = strings.noDashboardDataTitle,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = strings.noDashboardDataHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onRefresh) { Text(strings.refresh) }
                }
            }
            DashboardState.Loading -> {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is DashboardState.Error -> {
                Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            is DashboardState.Loaded -> {
                val dashboard = state.dashboard
                // Offline-first: this renders unconditionally, whether `dashboard` is a
                // freshly-fetched result or still the cache seeded at app start -- only the thin
                // progress bar (background refresh in flight) and this timestamp change between
                // the two, never a blank screen or a full-screen spinner. See
                // DiabetesDashboardManager.refresh's cache-then-network state handling.
                if (state.isRefreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                    Spacer(Modifier.height(6.dp))
                }
                val durationBadge = formatDurationBadge(dashboard.generationDurationMillis)
                Text(
                    text = strings.lastUpdated(dashboard.generatedAtMillis.toWindowLabel()) +
                        (durationBadge?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                // Landscape/tablet: Ampel+Metriken links, Zusammenfassung+Trend+Tipps rechts, beide
                // Spalten unabhängig scrollbar -- vermeidet langes vertikales Scrollen auf breiten
                // Screens. Schmale/Hochformat-Screens (< 600dp) behalten das bewährte einspaltige
                // Layout unverändert bei.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val isWide = maxWidth >= 600.dp
                    if (isWide) {
                        Row(Modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            ) {
                                DashboardAmpelBlock(dashboard, appLanguage, onUseComparisonPeriod, strings)
                                Spacer(Modifier.height(16.dp))
                                MetricsCard(dashboard.metrics, dashboard.trend)
                                dashboard.bodyMetrics?.let { bodyMetrics ->
                                    Spacer(Modifier.height(16.dp))
                                    BodyCompositionCard(bodyMetrics)
                                }
                            }
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            ) {
                                DashboardSummaryBlock(dashboard, strings, ttsController, state.isRefreshing)
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                            DashboardAmpelBlock(dashboard, appLanguage, onUseComparisonPeriod, strings)
                            Spacer(Modifier.height(16.dp))
                            MetricsCard(dashboard.metrics, dashboard.trend)
                            dashboard.bodyMetrics?.let { bodyMetrics ->
                                Spacer(Modifier.height(16.dp))
                                BodyCompositionCard(bodyMetrics)
                            }
                            DashboardSummaryBlock(dashboard, strings, ttsController, state.isRefreshing)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun DashboardAmpelBlock(
    dashboard: com.example.diabai.domain.analytics.DiabetesDashboard,
    appLanguage: AppLanguage,
    onUseComparisonPeriod: () -> Unit,
    strings: Strings,
) {
    BigTrafficLight(dashboard.overallStatus, dashboard.metrics.tirPercent, appLanguage)
    // STUFE 1: the freshest single CGM reading + trend arrow, from the instant direct-Nightscout
    // preview (see DiabetesDashboardManager.computeLiveSnapshot) -- null (and hidden) when no
    // direct Nightscout REST API is configured, since STUFE 1 never ran in that case.
    dashboard.latestValueMgDl?.let { latestValue ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${strings.currentValueLabel}: ${"%.0f".format(latestValue)} mg/dL " +
                (dashboard.latestValueTrendArrow ?: ""),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = dashboard.statusReason,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Text(
        text = "${strings.evaluatedPeriod}: ${dashboard.windowStartMillis.toWindowLabel()} – " +
            "${dashboard.windowEndMillis.toWindowLabel()} Uhr",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "(${strings.comparisonPeriod}: ${dashboard.previousWindowStartMillis.toWindowLabel()} – " +
            "${dashboard.previousWindowEndMillis.toWindowLabel()} Uhr)",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onUseComparisonPeriod),
    )
    dashboard.combinedSourcesNote?.let { note ->
        Text(
            text = note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DashboardSummaryBlock(
    dashboard: com.example.diabai.domain.analytics.DiabetesDashboard,
    strings: Strings,
    ttsController: TtsController?,
    isRefreshing: Boolean,
) {
    val hasNarrative = dashboard.summaryText.isNotBlank() || dashboard.tips.isNotEmpty() ||
        dashboard.trend?.trendSummary?.isNotBlank() == true
    // STUFE 2 is still in flight (or hasn't started yet) and hasn't produced a narrative for
    // *this* preview yet -- a dezent placeholder in its place, rather than an empty gap, so it's
    // clear more content is still coming. Once STUFE 2 lands, this is replaced by the actual
    // cards below via a soft fade-in (see the AnimatedVisibility wrapping them) instead of an
    // abrupt pop-in.
    AnimatedVisibility(visible = isRefreshing && !hasNarrative, enter = fadeIn(), exit = fadeOut()) {
        Text(
            text = strings.computingComparisonPeriod,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
    AnimatedVisibility(visible = hasNarrative, enter = fadeIn()) {
        Column {
            if (dashboard.trend?.trendSummary?.isNotBlank() == true) {
                InfoCard(title = strings.trendVsPrevious, markdown = dashboard.trend.trendSummary)
                Spacer(Modifier.height(16.dp))
            }
            if (dashboard.summaryText.isNotBlank()) {
                InfoCard(
                    title = strings.summaryTitle,
                    markdown = dashboard.summaryText,
                    ttsController = ttsController,
                    speakId = "dashboard-summary",
                )
                Spacer(Modifier.height(16.dp))
            }
            if (dashboard.tips.isNotEmpty()) {
                TipsCard(dashboard.tips)
            }
        }
    }
}

@Composable
private fun TimeRangeSelector(selected: DashboardTimeRange, onSelect: (DashboardTimeRange) -> Unit, appLanguage: AppLanguage) {
    // Rows of three instead of one (potentially clipped) row -- matches the fixed display order
    // Letzte 24h / Letzte Nacht / Gestern / Letzte 7 Tage / 30 Tage (DashboardTimeRange's
    // declaration order), read left-to-right, top-to-bottom.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        DashboardTimeRange.entries.chunked(3).forEach { rowRanges ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowRanges.forEach { range ->
                    FilterChip(
                        selected = selected == range,
                        onClick = { onSelect(range) },
                        label = { Text(range.displayLabel(appLanguage)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private val datePickerLabelFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault())

/** Von/Bis date pickers for [DashboardTimeRange.CUSTOM], backed by the platform's native
 * [DatePickerDialog] rather than Compose Material3's DatePicker -- avoids depending on a newer
 * compose-bom than this project currently pins. Each button only picks a *date*; the time of
 * day is fixed to start/end-of-day so "Von"/"Bis" behave the way a user expects a day-level
 * range picker to. */
@Composable
private fun CustomRangePicker(
    startMillis: Long?,
    endMillis: Long?,
    onStartChange: (Long) -> Unit,
    onEndChange: (Long) -> Unit,
    onApply: () -> Unit,
) {
    val context = LocalContext.current
    val strings = LocalStrings.current

    fun openPicker(initialMillis: Long?, atEndOfDay: Boolean, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance()
        if (initialMillis != null) calendar.timeInMillis = initialMillis
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, if (atEndOfDay) 23 else 0, if (atEndOfDay) 59 else 0, if (atEndOfDay) 59 else 0)
                    set(Calendar.MILLISECOND, if (atEndOfDay) 999 else 0)
                }
                onPicked(picked.timeInMillis)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { openPicker(startMillis, atEndOfDay = false, onPicked = onStartChange) },
                modifier = Modifier.weight(1f),
            ) {
                Text(strings.dateFrom(startMillis?.let { datePickerLabelFormatter.format(Instant.ofEpochMilli(it)) } ?: "–"))
            }
            OutlinedButton(
                onClick = { openPicker(endMillis, atEndOfDay = true, onPicked = onEndChange) },
                modifier = Modifier.weight(1f),
            ) {
                Text(strings.dateTo(endMillis?.let { datePickerLabelFormatter.format(Instant.ofEpochMilli(it)) } ?: "–"))
            }
        }
        TextButton(onClick = onApply, modifier = Modifier.fillMaxWidth()) { Text(strings.applyRange) }
    }
}

@Composable
private fun BigTrafficLight(status: TrafficLightStatus, timeInRangePercent: Double, appLanguage: AppLanguage) {
    val strings = LocalStrings.current
    val onContainer = status.onContainerColor()
    Surface(
        color = status.containerColor(),
        contentColor = onContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Die Benennung der Farbe (z. B. "Grün") als eigene Textzeile war rein redundant zum
            // bereits farbcodierten Punkt darüber -- entfernt, [status] steuert weiterhin Punkt-/
            // Hintergrundfarbe sowie [DashboardAmpelBlock.dashboard.statusReason]'s Satz darunter.
            Box(modifier = Modifier.size(72.dp).background(color = status.dotColor(), shape = CircleShape))
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${strings.timeInRange}: %.0f%%".format(timeInRangePercent),
                style = MaterialTheme.typography.titleMedium,
                color = onContainer,
            )
        }
    }
}

@Composable
private fun MetricsCard(metrics: DashboardMetrics, trend: DashboardTrend?) {
    val strings = LocalStrings.current
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(strings.metricsTitle, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            MetricLine(strings.metricTir, "%.1f%%".format(metrics.tirPercent), trend?.tirDelta, "%")
            MetricLine(strings.metricHypo, "%.1f%%".format(metrics.hypoPercent), trend?.hypoDelta, "%")
            if (metrics.severeHypoPercent > 0.0) {
                MetricLine(strings.metricSevereHypo, "%.1f%%".format(metrics.severeHypoPercent), null, "%")
            }
            MetricLine(strings.metricHyper, "%.1f%%".format(metrics.hyperPercent), null, "%")
            MetricLine(strings.metricVariability, "%.1f%%".format(metrics.cvPercent), null, "%")
            MetricLine(strings.metricAvgGlucose, "%.0f mg/dL".format(metrics.avgGlucose), trend?.avgGlucoseDelta, " mg/dL")
            MetricLine(strings.metricHbA1c, "%.1f%%".format(metrics.estimatedHbA1cPercent), null, "%")
        }
    }
}

/** Übersicht-tab card for [BodyMetricsSnapshot] -- latest Withings/Health-Connect weight and
 * body-fat readings plus the deterministically-computed 90-day trend. Only rendered when
 * [DiabetesDashboard.bodyMetrics] is non-null (a Körperzusammensetzung-category server is
 * configured/selected and returned at least one reading), so an app without Withings/Health
 * Connect never sees an empty placeholder card. */
@Composable
private fun BodyCompositionCard(bodyMetrics: BodyMetricsSnapshot) {
    val strings = LocalStrings.current
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(strings.bodyCompositionTitle, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (bodyMetrics.weightKg != null) {
                BodyMetricLine(strings.bodyWeight, "%.1f kg".format(bodyMetrics.weightKg), bodyMetrics.weightDateMillis)
            }
            if (bodyMetrics.bodyFatPercent != null) {
                BodyMetricLine(strings.bodyFat, "%.1f%%".format(bodyMetrics.bodyFatPercent), bodyMetrics.bodyFatDateMillis)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${strings.bodyTrend3Month}: ${bodyMetrics.trend.describe(strings)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BodyMetricLine(label: String, value: String, dateMillis: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.bodyMedium)
            if (dateMillis != null) {
                Text(dateMillis.toWindowLabel(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun BodyMetricsTrend.describe(strings: Strings): String = when (this) {
    BodyMetricsTrend.UP -> strings.trendUp
    BodyMetricsTrend.DOWN -> strings.trendDown
    BodyMetricsTrend.STABLE -> strings.trendStable
    BodyMetricsTrend.UNKNOWN -> strings.trendUnknown
}

@Composable
private fun MetricLine(label: String, value: String, delta: Double?, deltaUnit: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
        if (delta != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = delta.trendIndicator(deltaUnit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** e.g. "↗ +5.2%" / "↘ -1.0 mg/dL" / "→ 0.0%" vs. the equal-length prior period -- direction
 * only, deliberately no good/bad color coding here since e.g. a falling average could be an
 * improvement or a new problem depending on where it started; [DashboardTrend.trendSummary]
 * carries that judgment instead. */
private fun Double.trendIndicator(unit: String): String {
    val arrow = when {
        this > 0 -> "↗"
        this < 0 -> "↘"
        else -> "→"
    }
    val sign = if (this > 0) "+" else ""
    // Format only the number, then append the unit as a plain string -- a "%" character
    // (e.g. unit = "%") folded into a format-string template would need escaping as "%%" or
    // else throw UnknownFormatConversionException at runtime.
    val formattedValue = "%.1f".format(this)
    return "$arrow $sign$formattedValue$unit"
}

/** [ttsController]/[speakId] are optional -- only the "Zusammenfassung" card passes them (a
 * speaker icon to have the summary read aloud, per "Sprachausgabe (TTS) ... neben ... der
 * Übersicht Zusammenfassung"); every other [InfoCard] use (e.g. "Trend vs. Vorzeitraum") stays
 * silent/unchanged. Emoji glyphs rather than `Icons.Filled.VolumeUp`/`Stop` -- this project has no
 * material-icons-extended dependency (only the small core set bundled with material3 is
 * available), and adding one blind, without a build to confirm it resolves, was judged riskier
 * than reusing this codebase's existing emoji-chip pattern (see the reasoning-block chip). */
@Composable
private fun InfoCard(title: String, markdown: String, ttsController: TtsController? = null, speakId: String? = null) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (ttsController != null && speakId != null) {
                    val speakingId by ttsController.isSpeakingId.collectAsState()
                    val isSpeaking = speakingId == speakId
                    Text(
                        text = if (isSpeaking) "⏹️" else "🔊",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable { ttsController.speak(speakId, markdown) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            MarkdownText(markdown)
        }
    }
}

@Composable
private fun TipsCard(tips: List<String>) {
    val strings = LocalStrings.current
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(strings.tipsTitle, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            tips.forEach { tip ->
                Text("• $tip", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

private fun TrafficLightStatus.dotColor(): Color = when (this) {
    TrafficLightStatus.GREEN -> Color(0xFF2E7D32)
    TrafficLightStatus.YELLOW -> Color(0xFFF9A825)
    TrafficLightStatus.RED -> Color(0xFFC62828)
}

private fun TrafficLightStatus.containerColor(): Color = when (this) {
    TrafficLightStatus.GREEN -> Color(0xFFDDF3DD)
    TrafficLightStatus.YELLOW -> Color(0xFFFFF3CD)
    TrafficLightStatus.RED -> Color(0xFFFBDADA)
}

/** [containerColor] is a fixed light pastel regardless of the system theme (deliberately -- it's
 * the Ampel's own color coding, not a theme-derived surface), so the text on top of it must be an
 * equally fixed dark shade, NOT `MaterialTheme.colorScheme.onPrimary`/`onSurface` or any other
 * theme-derived color -- in dark theme those resolve to a near-white color meant for a *dark*
 * surface, which on this always-light background was close to unreadable (confirmed: [Surface]
 * only auto-derives a contrasting `LocalContentColor` for colors that are actual theme roles;
 * [containerColor]'s custom hex values aren't one, so it silently fell back to the ambient
 * default -- light text in dark theme, on a light background). */
private fun TrafficLightStatus.onContainerColor(): Color = when (this) {
    TrafficLightStatus.GREEN -> Color(0xFF1B5E20)
    TrafficLightStatus.YELLOW -> Color(0xFF7A4F01)
    TrafficLightStatus.RED -> Color(0xFF7F1D1D)
}
