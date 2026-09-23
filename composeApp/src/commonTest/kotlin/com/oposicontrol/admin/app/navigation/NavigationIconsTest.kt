package com.oposicontrol.admin.app.navigation

import com.oposicontrol.admin.core.ui.components.AppIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guarda de regresión del defecto que tenía el menú de escritorio.
 *
 * Veintiún items del sidebar llevaban como "icono" un carácter Unicode (`☷`,
 * `⚿`, `⌁`, `▦`, `◫`…) porque el mapa de iconos del shell sólo cubría los cinco
 * destinos de la barra inferior del móvil y todo lo demás caía en un
 * `Text(icon)`. Manrope no contiene esos glifos, así que se dibujaban como caja
 * vacía o con la fuente del sistema, con una métrica distinta en cada fila.
 *
 * El fallo era invisible para el compilador —el icono es un `String`— así que la
 * única forma de que no vuelva es comprobarlo aquí.
 */
class NavigationIconsTest {

    private val allItems = appNavigationGroups.flatMap { it.items }

    @Test
    fun `todo item del menu apunta a un icono real del registro`() {
        val unresolved = allItems
            .filter { AppIcon.fromKey(it.icon) == null }
            .map { "${it.metadata.id} -> '${it.icon}'" }

        assertEquals(
            emptyList(),
            unresolved,
            "Items de menú sin icono en el registro (se dibujarían como texto suelto)",
        )
    }

    @Test
    fun `ningun icono es un caracter suelto`() {
        // Red de seguridad por si alguien vuelve a escribir un glifo a mano: una
        // clave del registro siempre es una palabra, nunca un símbolo de 1-2
        // caracteres.
        val suspicious = allItems.filter { it.icon.length <= 2 }.map { it.metadata.id }
        assertEquals(emptyList(), suspicious, "Iconos que parecen glifos escritos a mano")
    }

    @Test
    fun `los destinos de la barra inferior del movil conservan su icono`() {
        val mobileIcons = mobilePrimaryNavigation.map { it.icon }
        assertEquals(5, mobilePrimaryNavigation.size, "La barra inferior debe tener exactamente 5 zonas")
        mobileIcons.forEach { key ->
            assertTrue(AppIcon.fromKey(key) != null, "Falta el icono '$key' de la barra inferior")
        }
        assertEquals(mobileIcons.distinct(), mobileIcons, "Dos destinos del móvil comparten icono")
    }

    @Test
    fun `cada grupo del menu tiene al menos un item`() {
        val empty = appNavigationGroups.filter { it.items.isEmpty() }.map { it.id }
        assertEquals(emptyList(), empty, "Grupos de menú vacíos")
    }
}
