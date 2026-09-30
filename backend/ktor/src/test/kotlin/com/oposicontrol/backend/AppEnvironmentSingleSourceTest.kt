package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MASTER2-SEC-001: UN solo criterio de "desarrollo" ([AppEnvironment.isDevelopment], fail-closed) para el bypass de
 * token dev, el peer local de desarrollo y el reinicio del backend. Antes: el bypass y el peer usaban su propio
 * conjunto (sin `local`) y el reinicio (que lanza powershell/gradlew) quedaba ABIERTO para cualquier APP_ENV distinto
 * de prod/production (p. ej. `staging` o uno desconocido).
 */
class AppEnvironmentSingleSourceTest {
    private data class Row(val appEnv: String?, val development: Boolean)

    private val table = listOf(
        Row("dev", true),
        Row("development", true),
        Row("local", true),
        Row("test", true),
        Row(" Dev ", true),
        Row("staging", false),
        Row("preprod", false),
        Row("unknown-value", false),
        Row("", false),
        Row("   ", false),
        Row(null, false),
        Row("prod", false),
        Row("production", false),
    )

    @Test
    fun everyEnvironmentDecisionUsesTheSameFailClosedResolver() {
        table.forEach { (raw, development) ->
            assertEquals(development, AppEnvironment.isDevelopment(raw), "isDevelopment($raw)")
            assertEquals(development, allowsDevBypass(raw.orEmpty(), enabled = true), "allowsDevBypass($raw)")
            assertEquals(development, isLocalDevelopmentPeer(raw.orEmpty(), "127.0.0.1"), "isLocalDevelopmentPeer($raw)")
            assertEquals(development, backendRestartEnabled(raw, explicitFlag = null), "backendRestartEnabled($raw)")
            // Nunca sin el interruptor explicito del bypass.
            assertEquals(false, allowsDevBypass(raw.orEmpty(), enabled = false), "bypass apagado ($raw)")
        }
    }

    @Test
    fun theRestartOnlyObeysAnExplicitBooleanFlagOtherwiseTheEnvironment() {
        assertEquals(true, backendRestartEnabled("production", explicitFlag = "true"))
        assertEquals(false, backendRestartEnabled("dev", explicitFlag = "false"))
        assertEquals(true, backendRestartEnabled("dev", explicitFlag = " TRUE ".lowercase()))
        // Un valor que no es booleano no abre nada: decide el entorno.
        assertEquals(false, backendRestartEnabled("staging", explicitFlag = "yes"))
        assertEquals(true, backendRestartEnabled("local", explicitFlag = "yes"))
        assertEquals(false, backendRestartEnabled(null, explicitFlag = ""))
    }
}
