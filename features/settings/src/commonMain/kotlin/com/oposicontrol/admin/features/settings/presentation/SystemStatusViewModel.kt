package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.SystemStatusAvailability
import com.oposicontrol.admin.domain.repository.SystemStatusRepository
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * [environmentLabel] y [panelVersion] llegan ya resueltos (del `AppConfig`, en el módulo de Koin): son dos textos fijos
 * del proceso y así las pruebas no tienen que fabricar una configuración entera para comprobar una lectura.
 */
class SystemStatusViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: SystemStatusRepository,
    private val analyticsTracker: AnalyticsTracker,
    environmentLabel: String = "",
    panelVersion: String = "",
    private val clock: () -> Instant = { Clock.System.now() },
) : MviViewModel<SystemStatusState, SystemStatusIntent>(
    initialState = SystemStatusState(environmentLabel = environmentLabel, panelVersion = panelVersion),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        dispatch(SystemStatusIntent.Refresh)
    }

    override suspend fun handleIntent(intent: SystemStatusIntent) {
        when (intent) {
            SystemStatusIntent.Refresh -> refresh()
        }
    }

    private suspend fun refresh() {
        // «Actualizar» está siempre a la vista: un segundo clic mientras la primera lectura sigue en curso no lanza otra.
        if (currentState().refreshing) return
        analyticsTracker.trackScreen("system_status")
        updateState { state ->
            state.copy(
                refreshing = true,
                // Con una lectura ya en pantalla no se vuelve al esqueleto: se queda hasta que llegue la nueva.
                uiState = state.uiState.takeIf { it is SystemStatusUiState.Content } ?: SystemStatusUiState.Loading,
                session = state.session.takeIf { it is UiAsync.Success } ?: UiAsync.Loading,
            )
        }
        val uiState = when (val result = repository.loadStatus()) {
            is SystemStatusAvailability.Available -> SystemStatusUiState.Content(result.value, readAt = clock())
            SystemStatusAvailability.NotConnected -> SystemStatusUiState.NotConnected
            // Un fallo SUSTITUYE a la lectura anterior: dejar a la vista un «todo funciona» de hace diez minutos junto a
            // un «no he podido preguntar» sería justo el verde por desconocimiento que esta pantalla no debe pintar.
            is SystemStatusAvailability.Failure -> SystemStatusUiState.Error(result.error)
        }
        updateState { it.copy(uiState = uiState) }
        val session = if (uiState == SystemStatusUiState.NotConnected) {
            // Sin servidor no hay a quién preguntar por la sesión; el bloque lo dice en vez de enseñar un error de red.
            UiAsync.Empty
        } else {
            when (val result = repository.loadSession()) {
                is AppResult.Success -> UiAsync.Success(result.value)
                is AppResult.Failure -> UiAsync.Error(result.error)
            }
        }
        updateState { it.copy(session = session, refreshing = false) }
    }
}
