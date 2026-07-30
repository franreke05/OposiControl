package com.oposicontrol.admin.app.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.features.auth.presentation.AuthScreen
import com.oposicontrol.admin.features.auth.presentation.AuthViewModel
import com.oposicontrol.admin.features.exams.presentation.ExamsIntent
import com.oposicontrol.admin.features.exams.presentation.ExamsViewModel
import com.oposicontrol.admin.features.exams.presentation.MobileContentScreen
import com.oposicontrol.admin.features.news.presentation.NewsIntent
import com.oposicontrol.admin.features.news.presentation.NewsViewModel
import com.oposicontrol.admin.features.users.presentation.UsersViewModel
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessViewModel
import com.oposicontrol.admin.features.moderation.presentation.ForumModerationViewModel
import com.oposicontrol.admin.features.tickets.presentation.TicketsViewModel
import com.oposicontrol.admin.features.support.presentation.SupportViewModel
import com.oposicontrol.admin.features.settings.presentation.SettingsViewModel
import com.oposicontrol.admin.domain.repository.ConnectionDiagnosticsRepository
import com.oposicontrol.admin.domain.repository.TagSearchRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun MobileContentRoot(
    appConfig: AppConfig,
) {
    if (appConfig.environment == Environment.DEV) {
        AuthenticatedMobileContentRoot(appConfig)
        return
    }

    val authViewModel: AuthViewModel = koinInject()
    val authState by authViewModel.state.collectAsState()

    if (authState.isRestoring || !authState.isAuthenticated) {
        AuthScreen(
            viewModel = authViewModel,
            onAuthenticated = {},
        )
        return
    }

    AuthenticatedMobileContentRoot(appConfig)
}

@Composable
private fun AuthenticatedMobileContentRoot(
    appConfig: AppConfig,
) {
    val examsViewModel: ExamsViewModel = koinInject()
    val newsViewModel: NewsViewModel = koinInject()
    val usersViewModel: UsersViewModel = koinInject()
    val accessViewModel: ExamAccessViewModel = koinInject()
    val moderationViewModel: ForumModerationViewModel = koinInject()
    val ticketsViewModel: TicketsViewModel = koinInject()
    val supportViewModel: SupportViewModel = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val diagnosticsRepository: ConnectionDiagnosticsRepository = koinInject()
    val tagSearchRepository: TagSearchRepository = koinInject()
    val scope = rememberCoroutineScope()
    var diagnosticsRefreshSignal by remember { mutableIntStateOf(0) }
    var backendRestarting by remember { mutableStateOf(false) }

    MobileContentScreen(
        examsViewModel = examsViewModel,
        newsViewModel = newsViewModel,
        tagSearchRepository = tagSearchRepository,
        backendActionLabel = "Reconectar",
        backendActionInProgress = backendRestarting,
        onRestartBackend = {
            if (backendRestarting) return@MobileContentScreen
            scope.launch {
                backendRestarting = true
                diagnosticsRefreshSignal += 1
                examsViewModel.dispatch(ExamsIntent.RefreshAll)
                newsViewModel.dispatch(NewsIntent.Refresh)
                delay(800)
                backendRestarting = false
            }
        },
        connectionDiagnostics = {
            ConnectionDiagnosticsPanel(
                appConfig = appConfig,
                diagnosticsRepository = diagnosticsRepository,
                refreshSignal = diagnosticsRefreshSignal,
            )
        },
        adminContent = {
            MobileAdminPanel(
                usersViewModel = usersViewModel,
                moderationViewModel = moderationViewModel,
                accessViewModel = accessViewModel,
                ticketsViewModel = ticketsViewModel,
                supportViewModel = supportViewModel,
                settingsViewModel = settingsViewModel,
            )
        },
    )
}
