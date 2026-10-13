package com.oposicontrol.admin.features.settings.presentation

/**
 * Lo único que se puede HACER en Ajustes es cerrar la sesión. Las intenciones de editar «nombre visible», «correo» y los
 * interruptores de avisos y logs se retiraron con el formulario: guardaban en una tabla local que nadie leía.
 */
sealed interface SettingsIntent {
    data object Logout : SettingsIntent
}
