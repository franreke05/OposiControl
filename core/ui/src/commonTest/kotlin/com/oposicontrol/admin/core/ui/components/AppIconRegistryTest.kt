package com.oposicontrol.admin.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppIconRegistryTest {

    @Test
    fun `no hay dos iconos compartiendo clave`() {
        val duplicated = AppIcon.entries
            .groupBy { it.key }
            .filterValues { it.size > 1 }
        assertEquals(emptyMap(), duplicated, "Claves duplicadas en el registro de iconos")
    }

    @Test
    fun `toda clave del registro se resuelve a su propio icono`() {
        AppIcon.entries.forEach { icon ->
            assertEquals(icon, AppIcon.fromKey(icon.key), "La clave '${icon.key}' no resuelve a su icono")
        }
    }

    @Test
    fun `una clave desconocida no se resuelve en silencio`() {
        // Importa que devuelva null y no un icono cualquiera: quien llama decide
        // el sustituto, y el test de navegación puede detectar claves huérfanas.
        assertNull(AppIcon.fromKey("no-existe"))
        assertNull(AppIcon.fromKey(""))
    }

    @Test
    fun `la localizacion tiene icono propio y se resuelve por su clave`() {
        // «Ámbito y clasificación» abre con un pin. El set no tenía ninguno y la
        // sección habría tenido que tomar prestado un icono que no dice «dónde».
        // Que el `when` del registro sea exhaustivo garantiza en compilación que
        // tiene drawable; esto garantiza que la clave estable no se pierde.
        assertEquals(AppIcon.LOCATION, AppIcon.fromKey("location"))
    }

    @Test
    fun `los cinco destinos de la barra inferior del movil siguen teniendo icono`() {
        // Regresión de navegación móvil: estas cinco claves las consume
        // ShellMobileNavigation y no pueden desaparecer al reorganizar el menú
        // de escritorio.
        listOf("home", "reports", "forum", "tester", "settings").forEach { key ->
            assertNotNull(AppIcon.fromKey(key), "Falta el icono de la barra inferior '$key'")
        }
    }
}
