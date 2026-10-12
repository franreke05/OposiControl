package com.oposicontrol.backend

import java.util.concurrent.ConcurrentHashMap

/**
 * Cache corta en memoria para las lecturas PUBLICAS (`/v1/...`): rutas sin autenticacion que cualquiera puede llamar en
 * bucle, y cada llamada era una o varias lecturas a Supabase con la clave de servicio. Con esto, por mucho que se repita
 * una peticion, la base de datos la sirve como mucho una vez por [ttlMillis] y por replica.
 *
 * El contenido es publico e identico para todos, asi que compartirlo no filtra nada. Lo que cuesta es frescura: un examen
 * recien publicado (o retirado) tarda hasta [ttlMillis] en verse (o dejar de verse) por estas rutas.
 *
 * [maxEntries] acota la memoria: pedir identificadores al azar no puede hacerla crecer. Al llenarse se tiran las entradas
 * caducadas y, si no basta, todas (una cache vacia solo cuesta una lectura mas).
 */
internal class PublicReadCache(
    private val ttlMillis: Long = 30_000,
    private val maxEntries: Int = 512,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val value: Any?, val storedAt: Long)

    private val entries = ConcurrentHashMap<Any, Entry>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: Any, load: () -> T): T {
        val now = clock()
        entries[key]?.takeIf { now - it.storedAt < ttlMillis }?.let { return it.value as T }
        val value = load()
        if (entries.size >= maxEntries) {
            entries.entries.removeIf { now - it.value.storedAt >= ttlMillis }
            if (entries.size >= maxEntries) entries.clear()
        }
        entries[key] = Entry(value, now)
        return value
    }
}
