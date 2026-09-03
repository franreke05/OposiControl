package com.oposicontrol.admin.app.platform

import androidx.compose.runtime.Composable

/**
 * Atiende el botón/gesto Atrás del sistema donde el sistema tiene uno.
 *
 * Sólo Android lo implementa de verdad. En escritorio no hay gesto Atrás global
 * —se navega con la sidebar— y en iOS lo resuelve el propio contenedor de
 * navegación, así que ahí es un no-op.
 *
 * Un único punto de enganche y no un `BackHandler` por pantalla: repartirlos
 * haría que dos pantallas anidadas consumieran el mismo gesto y produjera el
 * doble pop que hay que evitar.
 */
@Composable
expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
