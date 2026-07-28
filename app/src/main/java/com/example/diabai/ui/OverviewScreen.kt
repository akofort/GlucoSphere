package com.example.diabai.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.diabai.network.allDataSourceServers

@Composable
fun OverviewScreen(viewModel: GlucoSphereViewModel, ttsController: TtsController? = null, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsState()
    val dashboardState by viewModel.dashboardState.collectAsState()
    val selectedTimeRange by viewModel.selectedTimeRange.collectAsState()
    val selectedServerIds by viewModel.selectedServerIds.collectAsState()
    val customRangeStart by viewModel.customRangeStart.collectAsState()
    val customRangeEnd by viewModel.customRangeEnd.collectAsState()
    val isSingleSelectMode by viewModel.isSingleSelectMode.collectAsState()

    DashboardSection(
        state = dashboardState,
        servers = settings.allDataSourceServers.filter { it.enabled },
        selectedTimeRange = selectedTimeRange,
        selectedServerIds = selectedServerIds,
        customRangeStart = customRangeStart,
        customRangeEnd = customRangeEnd,
        isSingleSelectMode = isSingleSelectMode,
        onTimeRangeChange = viewModel::setTimeRange,
        onToggleServer = viewModel::toggleServerSelected,
        onRefresh = viewModel::refreshDashboard,
        onCustomRangeStartChange = viewModel::setCustomRangeStart,
        onCustomRangeEndChange = viewModel::setCustomRangeEnd,
        onApplyCustomRange = viewModel::applyCustomRange,
        onUseComparisonPeriod = viewModel::useComparisonPeriodAsCustomRange,
        appLanguage = settings.appLanguage,
        ttsController = ttsController,
        modifier = modifier.fillMaxSize(),
    )
}
