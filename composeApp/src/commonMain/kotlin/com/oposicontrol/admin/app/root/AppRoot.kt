package com.oposicontrol.admin.app.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.oposicontrol.admin.app.navigation.AdminSection
import com.oposicontrol.admin.app.navigation.AppDestination
import com.oposicontrol.admin.app.navigation.AppNavigator
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.features.audit.presentation.AuditIntent
import com.oposicontrol.admin.features.audit.presentation.AuditScreen
import com.oposicontrol.admin.features.audit.presentation.AuditViewModel
import com.oposicontrol.admin.features.auth.presentation.AuthScreen
import com.oposicontrol.admin.features.auth.presentation.AuthViewModel
import com.oposicontrol.admin.features.dashboard.presentation.DashboardIntent
import com.oposicontrol.admin.features.dashboard.presentation.DashboardQuickAction
import com.oposicontrol.admin.features.dashboard.presentation.DashboardScreen
import com.oposicontrol.admin.features.dashboard.presentation.DashboardViewModel
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessIntent
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessScreen
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessViewModel
import com.oposicontrol.admin.features.exams.presentation.ExamsIntent
import com.oposicontrol.admin.features.exams.presentation.ExamsScreen
import com.oposicontrol.admin.features.exams.presentation.ExamsViewModel
import com.oposicontrol.admin.features.exams.presentation.QuestionsResourcesScreen
import com.oposicontrol.admin.features.moderation.presentation.ModerationIntent
import com.oposicontrol.admin.features.moderation.presentation.ModerationScreen
import com.oposicontrol.admin.features.moderation.presentation.ModerationViewModel
import com.oposicontrol.admin.features.news.presentation.NewsScreen
import com.oposicontrol.admin.features.news.presentation.NewsViewModel
import com.oposicontrol.admin.features.newstypes.presentation.NewsTypesIntent
import com.oposicontrol.admin.features.newstypes.presentation.NewsTypesScreen
import com.oposicontrol.admin.features.newstypes.presentation.NewsTypesViewModel
import com.oposicontrol.admin.features.roles.presentation.RolesIntent
import com.oposicontrol.admin.features.roles.presentation.RolesScreen
import com.oposicontrol.admin.features.roles.presentation.RolesViewModel
import com.oposicontrol.admin.features.settings.presentation.SettingsIntent
import com.oposicontrol.admin.features.settings.presentation.SettingsScreen
import com.oposicontrol.admin.features.settings.presentation.SettingsViewModel
import com.oposicontrol.admin.features.specialties.presentation.SpecialtiesIntent
import com.oposicontrol.admin.features.specialties.presentation.SpecialtiesScreen
import com.oposicontrol.admin.features.specialties.presentation.SpecialtiesViewModel
import com.oposicontrol.admin.features.store.presentation.StoreIntent
import com.oposicontrol.admin.features.store.presentation.StoreScreen
import com.oposicontrol.admin.features.store.presentation.StoreViewModel
import com.oposicontrol.admin.features.support.presentation.SupportScreen
import com.oposicontrol.admin.features.support.presentation.SupportViewModel
import com.oposicontrol.admin.features.tickets.presentation.TicketsIntent
import com.oposicontrol.admin.features.tickets.presentation.TicketsScreen
import com.oposicontrol.admin.features.tickets.presentation.TicketsViewModel
import com.oposicontrol.admin.features.users.presentation.UsersIntent
import com.oposicontrol.admin.features.users.presentation.UsersScreen
import com.oposicontrol.admin.features.users.presentation.UsersViewModel
import com.oposicontrol.admin.domain.repository.TagSearchRepository
import org.koin.compose.koinInject

private const val TEMPORARILY_DISABLE_LOGIN = false

@Composable
fun AppRoot(
    appConfig: AppConfig,
    sections: List<AdminSection> = AdminSection.entries,
) {
    val navigator = remember { AppNavigator() }
    val authViewModel: AuthViewModel = koinInject()
    val tagSearchRepository: TagSearchRepository = koinInject()

    val authState by authViewModel.state.collectAsState()
    val destination by navigator.destination.collectAsState()
    val environmentLabel = buildString {
        append("Entorno ")
        append(appConfig.environment.name.lowercase())
        append(" | vista frontend")
        if (appConfig.supabaseUrl.isBlank()) append(" | Supabase sin configurar")
    }

    LaunchedEffect(authState.isAuthenticated, authState.isRestoring) {
        if (TEMPORARILY_DISABLE_LOGIN) {
            if (destination is AppDestination.Auth) {
                navigator.openSection(AdminSection.DASHBOARD)
            }
            return@LaunchedEffect
        }
        if (authState.isRestoring) return@LaunchedEffect
        if (authState.isAuthenticated && destination is AppDestination.Auth) {
            navigator.openSection(AdminSection.DASHBOARD)
        } else if (!authState.isAuthenticated && destination !is AppDestination.Auth) {
            navigator.openAuth()
        }
    }

    when {
        !TEMPORARILY_DISABLE_LOGIN && authState.isRestoring -> AuthScreen(
            viewModel = authViewModel,
            onAuthenticated = { navigator.openSection(AdminSection.DASHBOARD) },
        )

        !TEMPORARILY_DISABLE_LOGIN && !authState.isAuthenticated -> AuthScreen(
            viewModel = authViewModel,
            onAuthenticated = { navigator.openSection(AdminSection.DASHBOARD) },
        )

        destination is AppDestination.Admin -> {
            val section = (destination as AppDestination.Admin).section
            val route = adminSectionRoute(
                section = section,
                environmentLabel = environmentLabel,
                openSection = navigator::openSection,
            )
            AdminShell(
                currentSection = section,
                sections = sections,
                environmentLabel = environmentLabel,
                onSectionSelected = navigator::openSection,
                onPrimaryAction = route.onPrimaryAction,
                showHeader = section != AdminSection.DASHBOARD,
                globalSearch = {
                    GlobalTagSearch(
                        currentSection = section,
                        repository = tagSearchRepository,
                        onOpenSection = navigator::openSection,
                    )
                },
            ) {
                route.content()
            }
        }

        else -> {
            val dashboardViewModel: DashboardViewModel = koinInject()
            DashboardScreen(
                viewModel = dashboardViewModel,
                environmentLabel = environmentLabel,
                onQuickAction = { action -> handleDashboardAction(action, navigator::openSection) },
            )
        }
    }
}

private class AdminSectionRoute(
    val onPrimaryAction: (() -> Unit)?,
    val content: @Composable () -> Unit,
)

@Composable
private fun adminSectionRoute(
    section: AdminSection,
    environmentLabel: String,
    openSection: (AdminSection) -> Unit,
): AdminSectionRoute {
    return when (section) {
        AdminSection.DASHBOARD -> {
            val viewModel: DashboardViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(DashboardIntent.Refresh) },
                content = {
                    DashboardScreen(
                        viewModel = viewModel,
                        environmentLabel = environmentLabel,
                        onQuickAction = { action -> handleDashboardAction(action, openSection) },
                    )
                },
            )
        }

        AdminSection.USERS -> {
            val viewModel: UsersViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(UsersIntent.CreateNew) },
                content = { UsersScreen(viewModel) },
            )
        }

        AdminSection.ROLES -> {
            val viewModel: RolesViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(RolesIntent.CreateNew) },
                content = { RolesScreen(viewModel) },
            )
        }

        AdminSection.SPECIALTIES -> {
            val viewModel: SpecialtiesViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(SpecialtiesIntent.CreateNew) },
                content = { SpecialtiesScreen(viewModel) },
            )
        }

        AdminSection.NEWS_TYPES -> {
            val viewModel: NewsTypesViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(NewsTypesIntent.CreateNew) },
                content = { NewsTypesScreen(viewModel) },
            )
        }

        AdminSection.NEWS -> {
            val viewModel: NewsViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = null,
                content = { NewsScreen(viewModel) },
            )
        }

        AdminSection.EXAMS -> {
            val viewModel: ExamsViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(ExamsIntent.CreateNew) },
                content = { ExamsScreen(viewModel) },
            )
        }

        AdminSection.QUESTIONS_RESOURCES -> {
            val viewModel: ExamsViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(ExamsIntent.CreateQuestion) },
                content = { QuestionsResourcesScreen(viewModel) },
            )
        }

        AdminSection.EXAM_ACCESS -> {
            val viewModel: ExamAccessViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(ExamAccessIntent.CreateNew) },
                content = { ExamAccessScreen(viewModel) },
            )
        }

        AdminSection.STORE -> {
            val viewModel: StoreViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(StoreIntent.CreateNew) },
                content = { StoreScreen(viewModel) },
            )
        }

        AdminSection.TICKETS -> {
            val viewModel: TicketsViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(TicketsIntent.PrepareAdjustment) },
                content = { TicketsScreen(viewModel) },
            )
        }

        AdminSection.SUPPORT -> {
            val viewModel: SupportViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = null,
                content = { SupportScreen(viewModel) },
            )
        }

        AdminSection.MODERATION -> {
            val viewModel: ModerationViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(ModerationIntent.CreateNew) },
                content = { ModerationScreen(viewModel) },
            )
        }

        AdminSection.SETTINGS -> {
            val viewModel: SettingsViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(SettingsIntent.Refresh) },
                content = { SettingsScreen(viewModel) },
            )
        }

        AdminSection.AUDIT -> {
            val viewModel: AuditViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(AuditIntent.Refresh) },
                content = { AuditScreen(viewModel) },
            )
        }
    }
}

private fun handleDashboardAction(
    action: DashboardQuickAction,
    openSection: (AdminSection) -> Unit,
) {
    val target = when (action) {
        DashboardQuickAction.REVIEW_PENDING -> AdminSection.QUESTIONS_RESOURCES
        DashboardQuickAction.IMPORT_QUESTIONS -> AdminSection.QUESTIONS_RESOURCES
        DashboardQuickAction.AUDIT_RESOURCES -> AdminSection.QUESTIONS_RESOURCES
        DashboardQuickAction.CREATE_NEWS -> AdminSection.NEWS
        DashboardQuickAction.CREATE_EXAM -> AdminSection.EXAMS
        DashboardQuickAction.ACTIVATE_OFFER -> AdminSection.STORE
        DashboardQuickAction.REVIEW_TICKETS -> AdminSection.TICKETS
        DashboardQuickAction.OPEN_AUDIT -> AdminSection.AUDIT
    }
    openSection(target)
}
