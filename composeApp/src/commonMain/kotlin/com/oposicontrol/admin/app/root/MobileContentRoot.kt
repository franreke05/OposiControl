package com.oposicontrol.admin.app.root

import androidx.compose.runtime.Composable
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.features.exams.presentation.ExamsViewModel
import com.oposicontrol.admin.features.exams.presentation.MobileContentScreen
import com.oposicontrol.admin.features.news.presentation.NewsViewModel
import com.oposicontrol.admin.domain.repository.ConnectionDiagnosticsRepository
import org.koin.compose.koinInject

@Composable
fun MobileContentRoot(
    appConfig: AppConfig,
) {
    val examsViewModel: ExamsViewModel = koinInject()
    val newsViewModel: NewsViewModel = koinInject()
    val diagnosticsRepository: ConnectionDiagnosticsRepository = koinInject()
    MobileContentScreen(
        examsViewModel = examsViewModel,
        newsViewModel = newsViewModel,
        connectionDiagnostics = {
            ConnectionDiagnosticsPanel(
                appConfig = appConfig,
                diagnosticsRepository = diagnosticsRepository,
            )
        },
    )
}
