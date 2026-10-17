package com.oposicontrol.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Host del trabajo periodico del reto diario (W2): llama a `app_generate_daily_challenges()` cada `CHALLENGE_JOB_SECONDS` (por defecto 900; 0 = apagado).
 * La funcion toma un cerrojo consultivo propio (`reto-job`): varias instancias pueden tener el bucle y solo una genera a la vez (lider unico por transaccion), y
 * la creacion es idempotente. Si el trabajo falla o esta apagado, el primer acceso del dia crea las filas que falten (`app_get_daily_challenge`), asi que
 * este bucle es una optimizacion, no un punto de fallo. Sin PII en los logs.
 */
internal object DailyChallengeJob {
    fun start(scope: CoroutineScope, upstream: EconomyUpstream, intervalSeconds: Long, log: (String) -> Unit = {}, after: suspend () -> Unit = {}): Job? {
        if (intervalSeconds <= 0) return null
        return scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            delay(5_000)  // deja arrancar el servidor
            while (isActive) {
                for (function in listOf("app_generate_daily_challenges", "app_retry_deferred_free_ot", "app_job_dificultad", "app_job_ranking", "app_job_referidos", "app_job_foro")) {
                    runCatching { upstream.rpc(function, "{}") }
                        .onSuccess { if (!it.ok) log("daily challenge job $function: HTTP ${it.status}") }
                        .onFailure { log("daily challenge job $function failed: ${it::class.simpleName}") }
                }
                runCatching { after() }.onFailure { log("daily challenge job follow-up failed: ${it::class.simpleName}") }
                delay(intervalSeconds * 1_000)
            }
        }
    }

    fun intervalFrom(environment: (String) -> String? = System::getenv): Long = environment("CHALLENGE_JOB_SECONDS")?.trim()?.toLongOrNull() ?: 900L
}
