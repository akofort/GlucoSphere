package com.example.diabai.domain.analytics

sealed interface DashboardState {
    data object Idle : DashboardState

    /** Blocking full-screen spinner -- only reached when there is truly nothing cached yet to
     * show instead (a brand-new install/first-ever refresh). See [Loaded]'s [Loaded.isRefreshing]
     * for the offline-first "cached data on screen, small indicator only" case that covers every
     * other refresh. */
    data object Loading : DashboardState

    /** [isRefreshing] -- true while a background refresh is in flight over [dashboard] (see
     * [DiabetesDashboardManager.refresh]): the UI keeps showing [dashboard] (freshly fetched, or
     * still the last cached one) and layers a small, non-blocking indicator on top instead of
     * replacing it with [Loading]. */
    data class Loaded(val dashboard: DiabetesDashboard, val isRefreshing: Boolean = false) : DashboardState
    data class Error(val message: String) : DashboardState
}
