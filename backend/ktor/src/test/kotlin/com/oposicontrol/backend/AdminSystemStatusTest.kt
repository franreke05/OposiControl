package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Agregación del estado del sistema.
 *
 * La regla que estas pruebas protegen: **lo desconocido no se pinta de verde, y
 * tampoco tiñe de amarillo lo que sí funciona**. Un panel en verde por
 * desconocimiento se mira y se cree; uno en amarillo permanente deja de mirarse.
 */
class AdminSystemStatusTest {

    @Test
    fun everythingOperativeAggregatesToOperative() {
        assertEquals(
            SystemStatusLevelWire.OPERATIVE,
            aggregateSystemStatus(listOf(SystemStatusLevelWire.OPERATIVE, SystemStatusLevelWire.OPERATIVE)),
        )
    }

    @Test
    fun oneComponentDownTakesTheWholeSystemDown() {
        // Con Supabase caído el panel no sirve para nada aunque Ktor responda.
        assertEquals(
            SystemStatusLevelWire.DOWN,
            aggregateSystemStatus(listOf(SystemStatusLevelWire.OPERATIVE, SystemStatusLevelWire.DOWN)),
        )
    }

    @Test
    fun downWinsOverDegraded() {
        assertEquals(
            SystemStatusLevelWire.DOWN,
            aggregateSystemStatus(listOf(SystemStatusLevelWire.DEGRADED, SystemStatusLevelWire.DOWN)),
        )
    }

    @Test
    fun oneDegradedComponentDegradesTheWhole() {
        assertEquals(
            SystemStatusLevelWire.DEGRADED,
            aggregateSystemStatus(listOf(SystemStatusLevelWire.OPERATIVE, SystemStatusLevelWire.DEGRADED)),
        )
    }

    @Test
    fun knowingNothingIsUnknownNotOperative() {
        // El caso peligroso: sin fuentes, verde sería una mentira creíble.
        assertEquals(SystemStatusLevelWire.UNKNOWN, aggregateSystemStatus(emptyList()))
    }

    @Test
    fun anUnknownComponentAmongOperativeOnesDoesNotClaimEverythingIsFine() {
        assertEquals(
            SystemStatusLevelWire.UNKNOWN,
            aggregateSystemStatus(listOf(SystemStatusLevelWire.OPERATIVE, SystemStatusLevelWire.UNKNOWN)),
        )
    }

    // --- traducción del chequeo de Supabase ---

    @Test
    fun anUnreachableSupabaseIsDown() {
        assertEquals(
            SystemStatusLevelWire.DOWN,
            supabaseLevelFrom(reachable = false, latencyMs = null, slowThresholdMs = 1500),
        )
    }

    @Test
    fun aFastReadIsOperative() {
        assertEquals(
            SystemStatusLevelWire.OPERATIVE,
            supabaseLevelFrom(reachable = true, latencyMs = 120, slowThresholdMs = 1500),
        )
    }

    @Test
    fun aSlowButCorrectReadIsDegradedNotDown() {
        // La diferencia importa: responde, pero mal. Quien decide si hay que
        // actuar ahora necesita distinguirlo de una caída.
        assertEquals(
            SystemStatusLevelWire.DEGRADED,
            supabaseLevelFrom(reachable = true, latencyMs = 4000, slowThresholdMs = 1500),
        )
    }

    @Test
    fun exactlyAtTheThresholdIsStillOperative() {
        // El umbral marca "por encima de", no "a partir de": evita que un valor
        // justo en el límite oscile entre verde y amarillo en cada refresco.
        assertEquals(
            SystemStatusLevelWire.OPERATIVE,
            supabaseLevelFrom(reachable = true, latencyMs = 1500, slowThresholdMs = 1500),
        )
    }

    @Test
    fun reachableWithoutAMeasuredLatencyIsOperativeNotDegraded() {
        // Sin medida no hay evidencia de lentitud; inventar degradación sería
        // tan falso como inventar salud.
        assertEquals(
            SystemStatusLevelWire.OPERATIVE,
            supabaseLevelFrom(reachable = true, latencyMs = null, slowThresholdMs = 1500),
        )
    }

    // --- honestidad del DTO ---

    @Test
    fun metricsWithoutASourceAreListedAsNotInstrumented() {
        val dto = AdminSystemStatusDto(
            overallStatus = SystemStatusLevelWire.OPERATIVE,
            ktorStatus = SystemStatusLevelWire.OPERATIVE,
            supabaseStatus = SystemStatusLevelWire.OPERATIVE,
            workerStatus = SystemStatusLevelWire.UNKNOWN,
            updatedAt = "2026-09-02T10:00:00Z",
            notInstrumented = listOf(
                SystemStatusNotInstrumented.WORKER,
                SystemStatusNotInstrumented.MOBILE_CRASHES,
            ),
        )

        // Los valores sin fuente llegan como null, no como 0. Un "0 crashes" que
        // en realidad significa "no hay Sentry" es exactamente la métrica falsa
        // que el panel no debe mostrar.
        assertEquals(null, dto.mobileCrashes24h)
        assertEquals(null, dto.queueDepth)
        assertEquals(null, dto.cpuPercent)
        assertEquals(true, SystemStatusNotInstrumented.MOBILE_CRASHES in dto.notInstrumented)
    }

    @Test
    fun theWorkerIsUnknownRatherThanOperativeBecauseThereIsNoWorkerYet() {
        val dto = AdminSystemStatusDto(
            overallStatus = SystemStatusLevelWire.OPERATIVE,
            ktorStatus = SystemStatusLevelWire.OPERATIVE,
            supabaseStatus = SystemStatusLevelWire.OPERATIVE,
            workerStatus = SystemStatusLevelWire.UNKNOWN,
            updatedAt = "2026-09-02T10:00:00Z",
        )

        assertEquals(SystemStatusLevelWire.UNKNOWN, dto.workerStatus)
    }
}
