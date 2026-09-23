package com.oposicontrol.admin.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Botón/gesto Atrás del sistema.
 *
 * El defecto que cierran estas pruebas era P0: no había ningún `BackHandler` en
 * todo el proyecto, así que en Android el gesto Atrás **cerraba la aplicación**
 * desde cualquier pantalla de detalle. Se reprodujo en emulador dos veces.
 *
 * Lo que se fija aquí no es el `BackHandler` en sí —eso es Android— sino la
 * decisión de navegación, que es pura y por tanto comprobable: a dónde se va y
 * cuándo se deja pasar el gesto al sistema.
 */
class NavigationBackTest {

    // --- Los cinco casos mínimos del contrato --------------------------------

    @Test
    fun `desde el detalle de un reporte se vuelve a la lista de reportes`() {
        val navigator = AppNavigator()
        navigator.selectPrimaryTab(AppRoute.Reports.List)
        navigator.push(AppRoute.Reports.Detail("r-1"))

        assertTrue(navigator.handleSystemBack())
        assertEquals(AppRoute.Reports.List, navigator.currentRoute.value)
    }

    @Test
    fun `desde un hilo se vuelve a OposiRed`() {
        val navigator = AppNavigator()
        navigator.selectPrimaryTab(AppRoute.Community.OposiRed)
        navigator.push(AppRoute.Community.Thread("t-1"))

        assertTrue(navigator.handleSystemBack())
        assertEquals(AppRoute.Community.OposiRed, navigator.currentRoute.value)
    }

    @Test
    fun `desde el detalle de OposiTester se vuelve a su lista`() {
        val navigator = AppNavigator()
        navigator.selectPrimaryTab(AppRoute.OposiTester.List)
        navigator.push(AppRoute.OposiTester.Detail("i-1"))

        assertTrue(navigator.handleSystemBack())
        assertEquals(AppRoute.OposiTester.List, navigator.currentRoute.value)
    }

    @Test
    fun `desde Estado del sistema se vuelve a Ajustes`() {
        val navigator = AppNavigator()
        navigator.selectPrimaryTab(AppRoute.System.Settings)
        navigator.push(AppRoute.System.Status)

        assertTrue(navigator.handleSystemBack())
        assertEquals(AppRoute.System.Settings, navigator.currentRoute.value)
    }

    @Test
    fun `desde Importacion se vuelve al origen real, no a un padre fijo`() {
        // La pila manda sobre la tabla de padres: a Importación se llega desde
        // Ajustes y también desde una acción rápida del Dashboard, y el destino
        // correcto es distinto en cada caso.
        val desdeAjustes = AppNavigator()
        desdeAjustes.selectPrimaryTab(AppRoute.System.Settings)
        desdeAjustes.push(AppRoute.Content.Imports)
        assertTrue(desdeAjustes.handleSystemBack())
        assertEquals(AppRoute.System.Settings, desdeAjustes.currentRoute.value)

        val desdeHome = AppNavigator()
        desdeHome.push(AppRoute.Content.Imports)
        assertTrue(desdeHome.handleSystemBack())
        assertEquals(AppRoute.Home, desdeHome.currentRoute.value)
    }

    // --- Comportamiento en pantalla raíz ------------------------------------

    @Test
    fun `en una pantalla raiz el gesto se deja pasar al sistema`() {
        // false significa "no consumido": en Android eso manda la app al fondo,
        // que es el comportamiento normal de cualquier aplicación. Lo que no
        // puede pasar es que ocurra desde un detalle.
        AppNavigator().let { navigator ->
            assertFalse(navigator.handleSystemBack())
            assertEquals(AppRoute.Home, navigator.currentRoute.value)
        }
        listOf(
            AppRoute.Reports.List,
            AppRoute.Community.OposiRed,
            AppRoute.OposiTester.List,
            AppRoute.System.Settings,
        ).forEach { root ->
            val navigator = AppNavigator()
            navigator.selectPrimaryTab(root)
            assertFalse(
                navigator.handleSystemBack(),
                "El gesto no debe consumirse en la raíz $root",
            )
        }
    }

    @Test
    fun `canNavigateBack coincide con lo que handleSystemBack va a hacer`() {
        // El `BackHandler` se habilita con `canNavigateBack`. Si los dos no
        // coinciden, o el gesto se pierde o se consume sin navegar.
        listOf(
            AppRoute.Home,
            AppRoute.Reports.List,
            AppRoute.Community.OposiRed,
            AppRoute.OposiTester.List,
            AppRoute.System.Settings,
            AppRoute.Reports.Detail("r"),
            AppRoute.Community.Thread("t"),
            AppRoute.System.Status,
        ).forEach { route ->
            val navigator = AppNavigator(initialRoute = route)
            val esperado = navigator.canNavigateBack()
            assertEquals(
                esperado,
                navigator.handleSystemBack(),
                "canNavigateBack y handleSystemBack discrepan en $route",
            )
        }
    }

    // --- Lo que no debe ocurrir ---------------------------------------------

    @Test
    fun `no se pierde el tab seleccionado al volver`() {
        val navigator = AppNavigator()
        navigator.selectPrimaryTab(AppRoute.OposiTester.List)
        navigator.push(AppRoute.OposiTester.Detail("i-1"))
        navigator.handleSystemBack()

        // Sigue en el tab de OposiTester, no ha caído a Home.
        assertEquals(AppRoute.OposiTester.List, navigator.currentRoute.value)
        assertEquals(1, navigator.backStack.value.size)
    }

    @Test
    fun `no hay doble pop en un solo gesto`() {
        val navigator = AppNavigator()
        navigator.selectPrimaryTab(AppRoute.Reports.List)
        navigator.push(AppRoute.Reports.Detail("r-1"))
        val antes = navigator.backStack.value.size

        navigator.handleSystemBack()
        assertEquals(antes - 1, navigator.backStack.value.size)
    }

    @Test
    fun `atras repetido nunca deja la pila vacia ni entra en bucle`() {
        val navigator = AppNavigator()
        navigator.selectPrimaryTab(AppRoute.Community.OposiRed)
        navigator.push(AppRoute.Community.Thread("t-1"))
        navigator.push(AppRoute.Community.ModerationDetail("m-1"))

        // Veinte gestos seguidos: en algún momento debe dejar de consumir y no
        // puede quedarse dando vueltas entre dos rutas.
        repeat(20) { navigator.handleSystemBack() }
        assertTrue(navigator.backStack.value.isNotEmpty())
        assertFalse(navigator.handleSystemBack(), "Debería haber dejado de consumir el gesto")
    }

    @Test
    fun `una pantalla de detalle alcanzada sin pila tampoco cierra la app`() {
        // Éste es el caso que cubre la tabla de padres: tras un `replace()` la
        // pila tiene un solo elemento y aun así estamos en un detalle.
        val navigator = AppNavigator()
        navigator.replace(AppRoute.Reports.Detail("r-1"))
        assertEquals(1, navigator.backStack.value.size)

        assertTrue(navigator.handleSystemBack(), "No debe cerrarse la app desde un detalle")
        assertEquals(AppRoute.Reports.List, navigator.currentRoute.value)
    }

    // --- La tabla de padres -------------------------------------------------

    @Test
    fun `la tabla de padres no tiene ciclos`() {
        val rutas = listOf(
            AppRoute.Reports.Detail("r"),
            AppRoute.Community.Thread("t"),
            AppRoute.Community.ModerationDetail("m"),
            AppRoute.OposiTester.Detail("i"),
            AppRoute.Users.Detail("u"),
            AppRoute.Content.ExamDetail("e"),
            AppRoute.System.Status,
            AppRoute.Content.Imports,
        )
        rutas.forEach { inicio ->
            var actual: AppRoute? = inicio
            var saltos = 0
            while (actual != null) {
                actual = actual.parentRoute()
                saltos++
                assertTrue(saltos < 10, "Ciclo en la cadena de padres desde $inicio")
            }
        }
    }

    @Test
    fun `las pantallas raiz no declaran padre`() {
        listOf(
            AppRoute.Home,
            AppRoute.Reports.List,
            AppRoute.Community.OposiRed,
            AppRoute.OposiTester.List,
            AppRoute.System.Settings,
        ).forEach { root ->
            assertNull(root.parentRoute(), "$root no debería tener padre declarado")
        }
    }
}
