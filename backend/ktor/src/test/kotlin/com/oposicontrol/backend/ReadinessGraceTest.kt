package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals

/** Margen de gracia de /health/ready: solo un TIMEOUT reciente se tolera; todo lo demas se comporta como antes. */
class ReadinessGraceTest {
    private var now = 0L
    private var next = "UP"
    private val grace = ReadinessGrace(probe = { next }, graceMillis = 30_000, nowMillis = { now })

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
    fun theProbeWithTheOldContractStillMapsATimeoutToDown() {
        assertEquals("UNKNOWN", checkSupabaseReachable(url = null, apiKey = "k"))
    }
}
