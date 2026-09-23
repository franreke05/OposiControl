package com.oposicontrol.backend

import java.net.http.HttpRequest

/**
 * Quien firma una peticion a Supabase, y con que. Regla unica del backend:
 *
 * ```
 * apikey:        SIEMPRE la clave de servidor. Sin condicion.
 * Authorization: Bearer <clave-servidor>  -> SOLO si la clave tiene forma de JWT.
 * Authorization: Bearer <jwt-usuario>     -> en peticiones user-scoped, y gana
 *                                            sobre la del servidor.
 * ```
 *
 * ### Por que el Authorization del servidor va CONDICIONADO
 *
 * No es elegancia: es que las dos claves de servidor que este proyecto puede
 * tener no son la misma cosa.
 *
 * - La `service_role` legacy **es un JWT firmado**, y PostgREST resuelve el rol
 *   de la conexion leyendo su claim `role`. Dejar de mandarla en
 *   `Authorization` cambiaria el comportamiento de todo el backend apostando a
 *   que `apikey` a solas basta en la configuracion concreta de este proyecto.
 *   Eso no esta verificado contra el proyecto vivo, y la compatibilidad no
 *   puede depender de una suposicion no verificable.
 * - Una clave `sb_secret_...` **no** es un JWT. Mandarla como `Bearer` a
 *   PostgREST/GoTrue, que ahi esperan un JWT, es simplemente incorrecto.
 *
 * Condicionar por la FORMA preserva byte a byte lo que hoy se envia mientras la
 * variable contenga la legacy, y es correcto el dia que contenga la nueva.
 * Consecuencia buscada: **migrar de clave deja de ser un cambio de codigo**. Y
 * una `sb_secret_...` no viaja nunca en `Authorization`.
 *
 * El KDoc anterior afirmaba «only a user JWT is a Bearer token». Bajo esta
 * regla eso dejo de ser cierto, y por eso esta reescrito y no retocado.
 *
 * Precedente en el propio repo: `OposiBotsAuthGateway` ya decide por forma de
 * clave. Esto canoniza esa decision y endurece su predicado (ver [hasJwtShape]).
 */
internal class SupabaseRequestAuthPolicy(private val secretKey: String) {
    init {
        require(secretKey.isNotBlank()) { "SUPABASE_SECRET_KEY es obligatorio." }
    }

    /**
     * Credencial de servidor: la identidad es el servicio, no una persona.
     *
     * `apikey` va siempre. `Authorization` solo si la clave tiene forma de JWT.
     */
    fun elevatedHeaders(): Map<String, String> {
        val headers = mapOf("apikey" to secretKey)
        return if (secretKey.hasJwtShape()) headers + ("Authorization" to "Bearer $secretKey") else headers
    }

    /**
     * Peticion a nombre de una persona ya autenticada.
     *
     * Comprueba SOLO la forma del transporte. **La identidad tiene que haberla
     * verificado ya quien llama**, y verificarla significa validar firma y
     * emisor —hoy el unico que lo hace de verdad es
     * `SupabaseJwtVerifier.subFrom`—, nunca reenviar tal cual el `Authorization`
     * que traia la peticion entrante. "El llamante lo verifica" es exactamente
     * la frase que precede a los incidentes, asi que queda escrita aqui.
     *
     * El orden del `+` es contrato, no casualidad: el JWT de usuario
     * SOBRESCRIBE al `Authorization` de servidor que [elevatedHeaders] haya
     * podido poner. Gana siempre el usuario; lo contrario haria correr una
     * peticion user-scoped con el rol de servicio.
     */
    fun userScopedHeaders(jwt: String): Map<String, String> {
        // `!startsWith("sb_")` es una guarda DISTINTA de la forma, y por eso va
        // aparte: impide que una clave de servidor se cuele por el parametro
        // del token de usuario. No es parte de "ser un JWT".
        require(!jwt.startsWith("sb_") && jwt.hasJwtShape()) {
            "Se necesita un token con formato JWT de usuario."
        }
        return elevatedHeaders() + ("Authorization" to "Bearer $jwt")
    }

    companion object {
        /** Se avisa UNA vez por proceso, no una vez por peticion. */
        private val legacyKeyWarned = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * La clave de servidor, con la precedencia decidida en UN solo sitio.
         *
         * `SUPABASE_SECRET_KEY` es la canonica. `SUPABASE_SERVICE_ROLE_KEY` es
         * un fallback **explicito y deprecado** —es la que leen hoy los
         * `fromEnvironment()` del backend— y **nunca al reves**: la legacy no
         * tiene prioridad en ningun caso.
         *
         * Devuelve `""` cuando no hay ninguna, para que quien llama pueda
         * tratar "sin configurar" como un `null` suyo en vez de capturar una
         * excepcion en tiempo de peticion.
         */
        fun secretFromEnvironment(environment: (String) -> String? = System::getenv): String {
            environment("SUPABASE_SECRET_KEY")?.trim()?.ifBlank { null }?.let { return it }
            val legacy = environment("SUPABASE_SERVICE_ROLE_KEY")?.trim()?.ifBlank { null } ?: return ""
            if (legacyKeyWarned.compareAndSet(false, true)) {
                System.err.println(
                    "[ktor] SUPABASE_SECRET_KEY ausente: se usa SUPABASE_SERVICE_ROLE_KEY (deprecada). " +
                        "La deuda queda visible en el log de despliegue, no en cada peticion.",
                )
            }
            return legacy
        }
    }
}

/**
 * Forma de JWT: tres segmentos separados por `.` y **ninguno vacio**.
 *
 * Es el unico predicado de forma-JWT del backend y por eso tiene nombre: estaba
 * incrustado dentro de `userScopedHeaders` y no se podia invocar desde
 * [SupabaseRequestAuthPolicy.elevatedHeaders], que es justo donde hacia falta.
 *
 * Deliberadamente mas estricto que el `count { it == '.' } == 2` que usa
 * `OposiBotsAuthGateway`: ese acepta `".."`, `"a..b"` y `".x."`. Un segmento
 * vacio no es un JWT, y tratarlo como tal manda un `Bearer` con una credencial
 * rota en vez de fallar donde se ve.
 *
 * No valida firma, ni emisor, ni caducidad. Solo forma. Quien necesite
 * identidad usa `SupabaseJwtVerifier`.
 */
internal fun String.hasJwtShape(): Boolean {
    val segments = split('.')
    return segments.size == 3 && segments.all { it.isNotBlank() }
}

/**
 * Firma una peticion elevada sobre un builder.
 *
 * **Aplicar a un builder que YA tiene URI y que todavia no lleva
 * `Authorization` propio.** Las dos condiciones importan, y por motivos
 * distintos:
 *
 * - *Ya tiene URI* porque la comprobacion inspecciona cabeceras via `build()`,
 *   y `HttpRequest.Builder.build()` lanza `IllegalStateException` si falta la
 *   URI. El KDoc anterior pedia un builder «fresh», que no es lo mismo: un
 *   builder recien creado sin URI revienta aqui. Era una trampa para el primer
 *   integrador y por eso se dice explicito.
 * - *Sin `Authorization` propio* porque lo que hay que impedir es que el
 *   llamante meta una identidad y la politica meta otra: **dos identidades en
 *   la misma peticion**. La asercion NO significa «una peticion elevada no
 *   lleva Authorization» —bajo la regla de [SupabaseRequestAuthPolicy] si lo
 *   lleva cuando la clave tiene forma de JWT—; significa «la identidad la pone
 *   la politica, no el llamante». Despues de comprobarlo, la politica escribe
 *   el suyo si toca.
 */
internal fun HttpRequest.Builder.supabaseElevatedAuth(secretKey: String): HttpRequest.Builder = apply {
    require(!build().headers().firstValue("Authorization").isPresent) {
        "El llamante no puede fijar Authorization en una petición elevada: lo decide la política de credenciales."
    }
    SupabaseRequestAuthPolicy(secretKey).elevatedHeaders().forEach { (name, value) -> setHeader(name, value) }
}
