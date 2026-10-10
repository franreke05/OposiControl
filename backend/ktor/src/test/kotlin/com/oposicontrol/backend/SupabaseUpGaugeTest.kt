package com.oposicontrol.backend

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/** El gauge que usa la alerta SupabaseUnreachable: 1 con la sonda en UP, 0 con cualquier otra respuesta. */
class SupabaseUpGaugeTest {
    private fun PrometheusMeterRegistry.supabaseUp(): Double =
        scrape().lines().single { it.startsWith("upstream_supabase_up ") }.substringAfterLast(' ').toDouble()

    @Test
    fun theGaugeFollowsTheReadinessProbeUnderTheNameTheAlertUses() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        var answer = "UP"
        registerSupabaseUpGauge(registry) { answer }
        assertEquals(1.0, registry.supabaseUp())
        answer = "DOWN"
        assertEquals(0.0, registry.supabaseUp())
        answer = "UNKNOWN"
        assertEquals(0.0, registry.supabaseUp())
    }
}
