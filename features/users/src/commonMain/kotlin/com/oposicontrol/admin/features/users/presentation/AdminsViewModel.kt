package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.repository.RolesRepository
import com.oposicontrol.admin.domain.repository.UsersRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface AdminsIntent {
    /** Vuelve a leer la lista (botón de actualizar y «Reintentar» del error). */
    data object Refresh : AdminsIntent
}

data class AdminsState(
    /** Cuentas con rol `admin` o `superadmin`. `Empty` SOLO si las dos lecturas respondieron y no hay ninguna. */
    val accounts: UiAsync<List<AdminAccount>> = UiAsync.Loading,
    /** Relectura en curso con la lista anterior todavía en pantalla. */
    val refreshing: Boolean = false,
    /** La última relectura falló: se conserva lo leído y se avisa, en vez de cambiarlo por un error a pantalla completa. */
    val refreshFailed: Boolean = false,
) {
    val loadedAccounts: List<AdminAccount> get() = (accounts as? UiAsync.Success)?.value.orEmpty()
}

/**
 * «Quién puede entrar» de Sistema > Administradores.
 *
 * Pide al servidor solo quien tiene `admin` y quien tiene `superadmin` (`roleCode` de `GET /api/v1/admin/usuarios`) y
 * el catálogo de roles: dos lecturas cortas, no el censo entero, que con miles de usuarios gastaría tráfico de Supabase
 * en cada apertura de la pestaña para enseñar tres filas. El nivel de cada cuenta se sigue deduciendo aquí con el
 * catálogo. Solo lee: los roles y el estado de una cuenta se cambian desde su ficha en Usuarios.
 */
class AdminsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val usersRepository: UsersRepository,
    private val rolesRepository: RolesRepository,
) : MviViewModel<AdminsState, AdminsIntent>(
    initialState = AdminsState(),
    dispatcherProvider = dispatcherProvider,
) {
    /** Lectura en curso: una relectura cancela la anterior en vez de dejar dos respuestas compitiendo. */
    private var loadJob: Job? = null

    init {
        load()
    }

    override suspend fun handleIntent(intent: AdminsIntent) {
        when (intent) {
            AdminsIntent.Refresh -> load()
        }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val hadData = currentState().accounts.let { it is UiAsync.Success || it is UiAsync.Empty }
            updateState {
                if (hadData) it.copy(refreshing = true, refreshFailed = false) else it.copy(accounts = UiAsync.Loading, refreshFailed = false)
            }
            val holders = combine(AdminLevel.entries.map { usersRepository.observeUsersWithRole(it.code) }) { mergeRoleHolders(it.toList()) }
            combine(holders, rolesRepository.observeRoles(), ::resolveAdminAccounts).collectLatest { result ->
                updateState { state ->
                    when (result) {
                        // Un fallo NUNCA se pinta como «No hay administradores»: o se conserva lo leído, o es un error.
                        is AppResult.Failure ->
                            if (hadData) {
                                state.copy(refreshing = false, refreshFailed = true)
                            } else {
                                state.copy(accounts = UiAsync.Error(result.error), refreshing = false)
                            }
                        is AppResult.Success -> state.copy(
                            accounts = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value),
                            refreshing = false,
                            refreshFailed = false,
                        )
                    }
                }
            }
        }
    }
}
