package com.oposicontrol.admin.features.settings.di

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.repository.ktorSystemStatusRepository
import com.oposicontrol.admin.domain.repository.SystemStatusRepository
import com.oposicontrol.admin.features.settings.presentation.SystemStatusViewModel
import com.oposicontrol.admin.features.settings.presentation.environmentDisplayName
import org.koin.dsl.module

/**
 * `GET /api/v1/admin/estado-sistema` existe y responde desde hace tiempo, pero
 * esta pantalla seguía inyectando `NotConnectedSystemStatusRepository` y decía
 * "sin conectar" con el dato ya disponible al otro lado. El binding real cierra
 * ese hueco.
 *
 * `NotConnectedSystemStatusRepository` se conserva a propósito: sigue siendo la
 * respuesta correcta para un despliegue sin backend, y es la única forma honesta
 * de decir "aquí no hay observabilidad" sin fabricar métricas.
 */
val systemStatusFeatureModule = module {
    single<SystemStatusRepository> { ktorSystemStatusRepository() }
    factory {
        // Entorno y versión del panel salen del mismo `AppConfig` que usa Ajustes (`SettingsViewModel`).
        val appConfig = get<AppConfig>()
        SystemStatusViewModel(
            dispatcherProvider = get(),
            repository = get(),
            analyticsTracker = get(),
            environmentLabel = environmentDisplayName(appConfig.environment),
            panelVersion = appConfig.appVersion,
        )
    }
}
