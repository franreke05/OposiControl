package com.oposicontrol.admin.features.dashboard.presentation

/**
 * In-memory (process-lifetime) preference for the collapsible Home sections.
 *
 * [DashboardViewModel] is registered as a Koin `factory`, so a fresh instance —
 * and a fresh [DashboardState] — is created every time the Home tab is
 * re-entered. This object survives that recreation, so the expanded/collapsed
 * choice sticks across recomposition and tab navigation without needing a
 * database table or backend for what is a purely local UI preference.
 */
internal object HomeSectionPreferences {
    var quickActionsExpanded: Boolean = true
    var summaryExpanded: Boolean = true
}
