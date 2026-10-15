package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference

// =====================================================================
// Reloj del servidor para las decisiones de acceso (V1).
//
// "Cuando acaba un derecho" lo decide UN reloj: el de la base de datos (el mismo que escribe `valido_hasta` en el canje y que caduca los
// pedidos). Si Ktor decidiera con el reloj de su JVM, un desfase entre ambos mostraria un examen caducado como vigente (o al reves) y la
// misma pantalla discreparia de lo que el canje acaba de decidir. Este reloj lee `app_server_now()` y despues AVANZA con el reloj monotono
// del proceso (`System.nanoTime`, inmune a saltos del reloj de pared): hora de la base en la ultima sincronizacion + tiempo transcurrido.
//
//  - se resincroniza cada [resyncMillis] (60 s) y, si lleva mas de [maxStaleMillis] (5 min) sin lograrlo, falla CERRADO (503) en vez de
//    decidir con una hora vieja;
//  - la primera lectura es sincrona; una resincronizacion fallida con base reciente sigue con la extrapolacion (la base no esta disponible
//    justo cuando el resto de la peticion tampoco podra hablar con ella);
//  - bloqueante: se llama desde `withUpstreamIo` (pool de IO), como el resto de lecturas del servicio.
// =====================================================================

internal fun interface DatabaseClockSource {
    /** La hora de la base de datos AHORA (bloqueante). Lanza si la base no responde. */
    fun databaseNow(): Instant
}

internal class ServerClock(
    private val source: DatabaseClockSource,
    private val nanoTime: () -> Long = System::nanoTime,
    private val resyncMillis: Long = RESYNC_MILLIS,
    private val maxStaleMillis: Long = MAX_STALE_MILLIS,
) : () -> Instant {
    private class Sync(val databaseTime: Instant, val atNanos: Long)

    private val last = AtomicReference<Sync?>(null)

    override fun invoke(): Instant = now()

    fun now(): Instant {
        var sync = last.get()
        val age = sync?.let { elapsedMillis(it) }
        if (sync == null || age!! !in 0..resyncMillis) {
            sync = try {
                resync()
            } catch (failure: Exception) {
                // Base reciente: se sigue extrapolando. Sin base, o demasiado vieja: 503 (nunca una hora inventada con el reloj de la JVM).
                if (sync == null || age!! !in 0..maxStaleMillis) {
                    throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(cause = failure)
                }
                LOG.warn("resincronizacion del reloj fallida ({}); se extrapola desde hace {} ms", failure::class.simpleName, age)
                sync
            }
        }
        return sync.databaseTime.plusNanos(nanoTime() - sync.atNanos)
    }

    private fun elapsedMillis(sync: Sync): Long = (nanoTime() - sync.atNanos) / 1_000_000

    private fun resync(): Sync {
        val before = nanoTime()
        val databaseTime = source.databaseNow()
        val after = nanoTime()
        // La hora de la base corresponde a la mitad del viaje de ida y vuelta.
        val fresh = Sync(databaseTime, before + (after - before) / 2)
        last.set(fresh)
        return fresh
    }

    companion object {
        const val RESYNC_MILLIS = 60_000L
        const val MAX_STALE_MILLIS = 5 * 60_000L
        private val LOG = LoggerFactory.getLogger("com.oposicontrol.backend.ServerClock")

        /** `app_server_now()` por el transporte de servicio (migracion 20261018100300 antes que este Ktor: sin ella las lecturas de acceso son 503, nunca una hora inventada). */
        fun fromTransport(transport: SupabaseRequestTransport): () -> Instant {
            val clock = ServerClock(
                DatabaseClockSource {
                    val response = transport.rpcRaw("app_server_now", "{}")
                    if (!response.ok) throw IllegalStateException("app_server_now -> ${response.status}")
                    val text = Json.parseToJsonElement(response.body).jsonPrimitive.content
                    OffsetDateTime.parse(text).toInstant()
                },
            )
            return clock
        }

        /** Cableado de produccion; sin credenciales de servicio (pruebas sin base) cae al reloj de la JVM. */
        fun fromEnvironment(environment: (String) -> String? = System::getenv): () -> Instant =
            SupabaseRequestTransport.fromEnvironment(environment)?.let(::fromTransport) ?: Instant::now
    }
}
