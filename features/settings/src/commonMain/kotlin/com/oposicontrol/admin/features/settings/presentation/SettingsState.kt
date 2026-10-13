package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync

data class SettingsProfile(
    val displayName: String,
    val roleLabel: String,
)

/**
 * Ajustes de la vista estrecha. Todo lo que hay aquí es REAL: el perfil sale de la sesión iniciada y el entorno de la
 * configuración de arranque. Antes llevaba además un «nombre visible» y un «correo» que arrancaban con valores inventados
 * y dos interruptores sin efecto.
 */
data class SettingsState(
    val profile: UiAsync<SettingsProfile> = UiAsync.Loading,
    val environmentLabel: String = "",
    val logoutState: UiAsync<Unit> = UiAsync.Idle,
)
