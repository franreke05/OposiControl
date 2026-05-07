package com.oposicontrol.admin.data.di

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.database.DatabaseFactory
import com.oposicontrol.admin.core.network.ApiClientFactory
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.local.SettingsLocalDataSource
import com.oposicontrol.admin.data.remote.api.AuditApi
import com.oposicontrol.admin.data.remote.api.AuthApi
import com.oposicontrol.admin.data.remote.api.DashboardApi
import com.oposicontrol.admin.data.remote.api.RolesApi
import com.oposicontrol.admin.data.remote.api.SettingsApi
import com.oposicontrol.admin.data.remote.api.UsersApi
import com.oposicontrol.admin.data.remote.source.AuditDataSource
import com.oposicontrol.admin.data.remote.source.AuthDataSource
import com.oposicontrol.admin.data.remote.source.DashboardDataSource
import com.oposicontrol.admin.data.remote.source.RealAuditDataSource
import com.oposicontrol.admin.data.remote.source.RealAuthDataSource
import com.oposicontrol.admin.data.remote.source.RealDashboardDataSource
import com.oposicontrol.admin.data.remote.source.RealRolesDataSource
import com.oposicontrol.admin.data.remote.source.RealSettingsDataSource
import com.oposicontrol.admin.data.remote.source.RealUsersDataSource
import com.oposicontrol.admin.data.remote.source.RolesDataSource
import com.oposicontrol.admin.data.remote.source.SettingsDataSource
import com.oposicontrol.admin.data.remote.source.UsersDataSource
import com.oposicontrol.admin.data.repository.AuditRepositoryImpl
import com.oposicontrol.admin.data.repository.AuthRepositoryImpl
import com.oposicontrol.admin.data.repository.ContentDashboardRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorAdminOperationsRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorConnectionDiagnosticsRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorNewsRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorContentRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorTagSearchRepositoryImpl
import com.oposicontrol.admin.data.repository.RolesRepositoryImpl
import com.oposicontrol.admin.data.repository.SettingsRepositoryImpl
import com.oposicontrol.admin.data.repository.SupabaseCommerceRepositoryImpl
import com.oposicontrol.admin.data.repository.UsersRepositoryImpl
import com.oposicontrol.admin.data.storage.PlatformFileReader
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import com.oposicontrol.admin.domain.repository.AuditRepository
import com.oposicontrol.admin.domain.repository.AuthRepository
import com.oposicontrol.admin.domain.repository.CommerceRepository
import com.oposicontrol.admin.domain.repository.ConnectionDiagnosticsRepository
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.repository.DashboardRepository
import com.oposicontrol.admin.domain.repository.NewsRepository
import com.oposicontrol.admin.domain.repository.RolesRepository
import com.oposicontrol.admin.domain.repository.SettingsRepository
import com.oposicontrol.admin.domain.repository.TagSearchRepository
import com.oposicontrol.admin.domain.repository.UsersRepository
import org.koin.dsl.module

val dataModule = module {
    single { DatabaseFactory(get()).create() }
    single { SessionLocalDataSource(get()) }
    single { SettingsLocalDataSource(get(), get()) }
    single { PlatformFileReader(get()) }

    single { ApiClientFactory(get(), get()).create() }
    single { NetworkResultAdapter(get()) }

    single { AuthApi(get(), get()) }
    single { DashboardApi(get(), get(), get()) }
    single { UsersApi(get(), get(), get()) }
    single { RolesApi(get(), get(), get()) }
    single { SettingsApi(get(), get(), get()) }
    single { AuditApi(get(), get(), get()) }

    single<AuthDataSource> { RealAuthDataSource(get(), get()) }
    single<DashboardDataSource> { RealDashboardDataSource(get(), get()) }
    single<UsersDataSource> { RealUsersDataSource(get(), get()) }
    single<RolesDataSource> { RealRolesDataSource(get(), get()) }
    single<SettingsDataSource> { RealSettingsDataSource(get(), get()) }
    single<AuditDataSource> { RealAuditDataSource(get(), get()) }

    single<ContentRepository> {
        val appConfig: AppConfig = get()
        KtorContentRepositoryImpl(
            client = get(),
            appConfig = appConfig,
            sessionLocalDataSource = get(),
            fileReader = get(),
            adapter = get(),
        )
    }

    single<CommerceRepository> {
        val appConfig: AppConfig = get()
        SupabaseCommerceRepositoryImpl(
            client = get(),
            appConfig = appConfig,
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
            adapter = get(),
        )
    }

    single<TagSearchRepository> {
        KtorTagSearchRepositoryImpl(
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

    single<AuditRepository> { AuditRepositoryImpl(get()) }
    single<AuthRepository> { AuthRepositoryImpl(get(), get()) }
    single<DashboardRepository> { ContentDashboardRepositoryImpl(get(), get(), get()) }
    single<UsersRepository> { UsersRepositoryImpl(get(), get()) }
    single<RolesRepository> { RolesRepositoryImpl(get(), get()) }
    single<SettingsRepository> { SettingsRepositoryImpl(get(), get()) }
}
