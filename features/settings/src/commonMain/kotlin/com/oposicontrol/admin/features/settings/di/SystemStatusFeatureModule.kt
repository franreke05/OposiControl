package com.oposicontrol.admin.features.settings.di

import com.oposicontrol.admin.data.repository.ktorSystemStatusRepository
import com.oposicontrol.admin.domain.repository.SystemStatusRepository
import com.oposicontrol.admin.features.settings.presentation.SystemStatusViewModel
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
    factory { SystemStatusViewModel(get(), get(), get()) }
}
