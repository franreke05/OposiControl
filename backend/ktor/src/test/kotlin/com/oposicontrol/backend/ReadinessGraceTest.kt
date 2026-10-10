package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals

/** Margen de gracia de /health/ready: solo un TIMEOUT reciente se tolera; todo lo demas se comporta como antes. */
class ReadinessGraceTest {
    private var now = 0L
    private var next = "UP"
    private var probes = 0
    private val grace = ReadinessGrace(probe = { probes++; next }, graceMillis = 30_000, cacheMillis = 0, nowMillis = { now })

    @Test
    fun aTimeoutRightAfterAGoodProbeIsTolerated() {
        assertEquals("UP", grace.check())
        next = READINESS_TRANSIENT
        now = 29_000
        assertEquals("UP", grace.check())
    }

    @Test
    fun aSustainedTimeoutGoesDownOnceTheGraceIsOver() {
        assertEquals("UP", grace.check())
        next = READINESS_TRANSIENT
        now = 30_001
        assertEquals("DOWN", grace.check())
    }

    @Test
    fun aTimeoutWithNoPreviousGoodProbeIsDown() {
        next = READINESS_TRANSIENT
        assertEquals("DOWN", grace.check())
    }

    @Test
    fun aRejectedCredentialOrRefusedConnectionIsDownImmediately() {
        assertEquals("UP", grace.check())
        next = "DOWN"
        now = 1
        assertEquals("DOWN", grace.check())
        next = "UNKNOWN"
        assertEquals("UNKNOWN", grace.check())
    }

    @Test
    fun theGraceWindowRestartsWithEveryGoodProbe() {
        assertEquals("UP", grace.check())
        now = 25_000
        assertEquals("UP", grace.check())
        next = READINESS_TRANSIENT
        now = 50_000
        assertEquals("UP", grace.check())
    }

    @Test
    fun withinTheCacheWindowTheDependencyIsAskedOnlyOnce() {
        val cached = ReadinessGrace(probe = { probes++; next }, graceMillis = 90_000, cacheMillis = 30_000, nowMillis = { now })
        repeat(50) { assertEquals("UP", cached.check()) }
        now = 29_999
        next = "DOWN"
        assertEquals("UP", cached.check(), "dentro de la ventana se reutiliza la ultima respuesta")
        assertEquals(1, probes)
        now = 30_000
        assertEquals("DOWN", cached.check(), "pasada la ventana se vuelve a preguntar")
        assertEquals(2, probes)
    }

    @Test
    fun theProbeWithTheOldContractStillMapsATimeoutToDown() {
        assertEquals("UNKNOWN", checkSupabaseReachable(url = null, apiKey = "k"))
    }
}
