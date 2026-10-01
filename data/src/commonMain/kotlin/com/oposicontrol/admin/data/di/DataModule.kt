package com.oposicontrol.admin.data.di

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.database.DatabaseFactory
import com.oposicontrol.admin.core.network.ApiClientFactory
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.NewsEditorDraftLocalDataSource
import com.oposicontrol.admin.data.local.SqlDelightNewsEditorDraftLocalDataSource
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.local.SettingsLocalDataSource
import com.oposicontrol.admin.data.remote.api.AuditApi
import com.oposicontrol.admin.data.remote.api.AuthApi
import com.oposicontrol.admin.data.remote.api.DashboardApi
import com.oposicontrol.admin.data.remote.api.RolesApi
import com.oposicontrol.admin.data.remote.api.UserDetailApi
import com.oposicontrol.admin.data.remote.api.UsersApi
import com.oposicontrol.admin.data.remote.api.installAdminSessionRefresh
import com.oposicontrol.admin.data.remote.source.AuditDataSource
import com.oposicontrol.admin.data.remote.source.AuthDataSource
import com.oposicontrol.admin.data.remote.source.RealAuditDataSource
import com.oposicontrol.admin.data.remote.source.RealAuthDataSource
import com.oposicontrol.admin.data.remote.source.RealRolesDataSource
import com.oposicontrol.admin.data.remote.source.RealUsersDataSource
import com.oposicontrol.admin.data.remote.source.RolesDataSource
import com.oposicontrol.admin.data.remote.source.UsersDataSource
import com.oposicontrol.admin.data.repository.AuditRepositoryImpl
import com.oposicontrol.admin.data.repository.AuthRepositoryImpl
import com.oposicontrol.admin.data.repository.ContentDashboardRepositoryImpl

import com.oposicontrol.admin.data.repository.KtorAdminOperationsRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorDashboardOverviewRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorAdminV1ContentRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorCommunitiesRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorQuestionsRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorConnectionDiagnosticsRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorForumRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorTesterRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorModerationRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorNewsRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorTagSearchRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorTagsRepositoryImpl
import com.oposicontrol.admin.data.repository.RolesRepositoryImpl
import com.oposicontrol.admin.data.repository.SettingsRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorCommerceRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorCommercialCatalogRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorMarketCatalogRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorProductAnalyticsRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorRankingsRepositoryImpl
import com.oposicontrol.admin.data.repository.UsersRepositoryImpl
import com.oposicontrol.admin.data.storage.PlatformFileReader
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import com.oposicontrol.admin.domain.repository.AuditRepository
import com.oposicontrol.admin.domain.repository.AuthRepository
import com.oposicontrol.admin.domain.repository.CommerceRepository
import com.oposicontrol.admin.domain.repository.CommercialCatalogRepository
import com.oposicontrol.admin.domain.repository.MarketCatalogRepository
import com.oposicontrol.admin.domain.repository.ProductAnalyticsRepository
import com.oposicontrol.admin.domain.repository.RankingsRepository
import com.oposicontrol.admin.domain.repository.CommunitiesRepository
import com.oposicontrol.admin.domain.repository.ConnectionDiagnosticsRepository
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.repository.ContentImportRepository
import com.oposicontrol.admin.domain.repository.DashboardOverviewRepository
import com.oposicontrol.admin.domain.repository.DashboardRepository
import com.oposicontrol.admin.domain.repository.ForumRepository
import com.oposicontrol.admin.domain.repository.TesterRepository
import com.oposicontrol.admin.domain.repository.ModerationRepository
import com.oposicontrol.admin.domain.repository.NewsRepository
import com.oposicontrol.admin.domain.repository.QuestionsRepository
import com.oposicontrol.admin.domain.repository.RolesRepository
import com.oposicontrol.admin.domain.repository.SettingsRepository
import com.oposicontrol.admin.domain.repository.TagSearchRepository
import com.oposicontrol.admin.domain.repository.TagsRepository
import com.oposicontrol.admin.domain.repository.UsersRepository
import org.koin.dsl.module

val dataModule = module {
    single { DatabaseFactory(get()).create() }
    single { SessionLocalDataSource(get()) }
    single { SettingsLocalDataSource(get(), get()) }
    single<NewsEditorDraftLocalDataSource> { SqlDelightNewsEditorDraftLocalDataSource(get()) }
    single { PlatformFileReader(get()) }

    single {
        // MASTER6-AUTH-004: refresco de sesion en el cliente compartido. El repositorio se resuelve tarde (en la
        // primera peticion) porque depende de AuthApi, que a su vez usa este mismo cliente.
        val scope = this
        ApiClientFactory(get(), get()).create().also { client ->
            client.installAdminSessionRefresh(
                sessionLocalDataSource = get(),
                isExpiring = { session -> scope.get<AuthRepositoryImpl>().isExpiring(session) },
                refresher = { scope.get<AuthRepositoryImpl>() },
            )
        }
    }
    single { NetworkResultAdapter(get()) }

    single { AuthApi(get(), get()) }
    single { DashboardApi(get(), get(), get()) }
    single { UsersApi(get(), get(), get()) }
    single { RolesApi(get(), get(), get()) }
    single { AuditApi(get(), get(), get()) }
    single { UserDetailApi(get(), get(), get()) }

    single<AuthDataSource> { RealAuthDataSource(get(), get()) }
    single<UsersDataSource> { RealUsersDataSource(get(), get(), get()) }
    single<RolesDataSource> { RealRolesDataSource(get(), get()) }
    single<AuditDataSource> { RealAuditDataSource(get(), get()) }

    single<ContentRepository> {
        val appConfig: AppConfig = get()
        KtorAdminV1ContentRepositoryImpl(
            client = get(),
            appConfig = appConfig,
            sessionLocalDataSource = get(),
            adapter = get(),
            fileReader = get(),
        )
    }
    single<ContentImportRepository> { get<ContentRepository>() as ContentImportRepository }

    // Banco global de preguntas (pantalla Preguntas) — adapter propio,
    // deliberadamente separado de ContentRepository/KtorAdminV1ContentRepositoryImpl.
    single<QuestionsRepository> {
        KtorQuestionsRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    single<CommerceRepository> {
        KtorCommerceRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    // Analitica de producto. Solo lectura y agregada en Ktor.
    single<ProductAnalyticsRepository> {
        KtorProductAnalyticsRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    // Dashboard V2: UN contrato agregado en Ktor. Sustituye a la orquestacion de
    // cinco repositorios (mas una llamada por examen) que hacia el cliente.
    // DashboardRepository sigue vivo: alimenta el Home movil.
    single<DashboardOverviewRepository> {
        KtorDashboardOverviewRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    // Rankings y ligas. Solo lectura: la clasificacion la calcula el producto.
    single<RankingsRepository> {
        KtorRankingsRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    // OposiMarket: packs de OT, servicios, promociones y reglas de pop-up.
    single<MarketCatalogRepository> {
        KtorMarketCatalogRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    // Catálogo comercial canónico (planes, packs de OT y tarifas). Distinto del
    // modelo legacy de tienda de CommerceRepository: éste es el que lee OposiBot.
    single<CommercialCatalogRepository> {
        KtorCommercialCatalogRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    single<NewsRepository> {
        val appConfig: AppConfig = get()
        KtorNewsRepositoryImpl(
            client = get(),
            appConfig = appConfig,
            sessionLocalDataSource = get(),
            fileReader = get(),
            adapter = get(),
        )
    }

    single<TagSearchRepository> {
        KtorTagSearchRepositoryImpl(adapter = get())
    }

    single<TagsRepository> {
        KtorTagsRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    single<CommunitiesRepository> {
        KtorCommunitiesRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    single<ConnectionDiagnosticsRepository> {
        KtorConnectionDiagnosticsRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    single<AdminOperationsRepository> {
        KtorAdminOperationsRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    single<ModerationRepository> {
        KtorModerationRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    single<ForumRepository> {
        KtorForumRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    single<TesterRepository> {
        KtorTesterRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }

    single<AuditRepository> { AuditRepositoryImpl(get()) }
    single { AuthRepositoryImpl(get(), get()) }
    single<AuthRepository> { get<AuthRepositoryImpl>() }
    single<DashboardRepository> { ContentDashboardRepositoryImpl(get(), get(), get(), get(), get()) }
    single<UsersRepository> { UsersRepositoryImpl(get()) }
    single<RolesRepository> { RolesRepositoryImpl(get()) }
    single<SettingsRepository> { SettingsRepositoryImpl(get()) }
}
