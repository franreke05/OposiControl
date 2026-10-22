package com.oposicontrol.backend

import org.slf4j.LoggerFactory

// =====================================================================
// DRENAJE AL APAGAR (CERT-LOAD-1000, 2026-10-21)
//
// El pool bot de Caddy ya no tiene salud pasiva (contaba tambien los timeouts y aparto las dos replicas): solo la sonda activa
// de /health/live, cada 5 s con 4 s de tope. Sin drenaje, una replica que se para cerraba el puerto al instante y, hasta la
// siguiente sonda, `least_conn` le seguia mandando peticiones (con 0 conexiones es la preferida): 502 durante hasta ~5 s.
//
// Con drenaje: al recibir SIGTERM, /health/live responde 503 DRAINING AL INSTANTE y la replica sigue atendiendo (en vuelo y
// nuevas) durante [ShutdownPlan.drainMillis] (> intervalo + tope de la sonda = 9 s), tiempo en el que Caddy la marca caida y
// deja de enviarle trafico; despues, el cierre ordenado de siempre (Netty deja de aceptar, termina lo que queda, recursos).
// Presupuesto: drenaje + SHUTDOWN_TIMEOUT_MS <= stop_grace_period del compose (35 s); lo comprueba ShutdownDrainTest.
// =====================================================================

internal data class ShutdownPlan(val drainMillis: Long, val graceMillis: Long, val timeoutMillis: Long) {
    /** Lo maximo que puede tardar el apagado antes de que Docker mande SIGKILL. */
    val worstCaseMillis: Long get() = drainMillis + timeoutMillis

    companion object {
        const val BOT_DEFAULT_DRAIN_MS = 10_000L
        const val DEFAULT_GRACE_MS = 5_000L
        const val DEFAULT_TIMEOUT_MS = 25_000L

        /** Con drenaje el tope del cierre baja a 20 s: 10 + 20 = 30 s, dentro de stop_grace_period 35 s. */
        const val DRAINING_DEFAULT_TIMEOUT_MS = 20_000L

        /**
         * SHUTDOWN_DRAIN_MS (vacio = 10 s en `bot`, 0 en el resto: `control` es una sola instancia y `combined` es desarrollo),
         * SHUTDOWN_GRACE_PERIOD_MS (5 s) y SHUTDOWN_TIMEOUT_MS (>= grace; vacio = 20 s con drenaje, 25 s sin el).
         */
        fun fromEnvironment(env: (String) -> String?): ShutdownPlan {
            val profile = runCatching { ServerProfile.parse(env("SERVER_PROFILE")) }.getOrDefault(ServerProfile.COMBINED)
            val drain = env("SHUTDOWN_DRAIN_MS")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                ?: if (profile == ServerProfile.BOT) BOT_DEFAULT_DRAIN_MS else 0L
            val grace = env("SHUTDOWN_GRACE_PERIOD_MS")?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: DEFAULT_GRACE_MS
            val timeout = env("SHUTDOWN_TIMEOUT_MS")?.trim()?.toLongOrNull()?.takeIf { it >= grace }
                ?: maxOf(grace, if (drain > 0) DRAINING_DEFAULT_TIMEOUT_MS else DEFAULT_TIMEOUT_MS)
            return ShutdownPlan(drain, grace, timeout)
        }
    }
}

internal object ShutdownDrain {
    private val logger = LoggerFactory.getLogger("OC-KTOR-SHUTDOWN")

    @Volatile
    var draining: Boolean = false
        private set

    fun begin() {
        draining = true
    }

    /** Solo pruebas. */
    internal fun reset() {
        draining = false
    }

    /** Marca el drenaje (la sonda ve 503 desde ya), espera [ShutdownPlan.drainMillis] atendiendo y despues para el motor. */
    fun drainThenStop(plan: ShutdownPlan, sleep: (Long) -> Unit, stop: (graceMillis: Long, timeoutMillis: Long) -> Unit) {
        begin()
        if (plan.drainMillis > 0) {
            logger.warn("shutdown: draining for {} ms (/health/live -> 503 DRAINING) before stopping", plan.drainMillis)
            try {
                sleep(plan.drainMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        stop(plan.graceMillis, plan.timeoutMillis)
    }
}
