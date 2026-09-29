package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Ambito de credencial de una peticion. No hay un tercer caso.
 *
 * Se modela como tipo y no como un `Boolean` o un `String?` porque
 * "elevada" y "a nombre de una persona" no son dos valores de la misma cosa:
 * son dos identidades distintas, y un `jwt: String?` invita a colar un `null`
 * accidental que silenciosamente degrada una peticion de usuario a peticion de
 * servicio.
 */
sealed interface SupabaseAuthScope {
    /** Credencial de servidor. La identidad es el servicio, no una persona. */
    data object Elevated : SupabaseAuthScope

    /**
     * JWT de un usuario **ya verificado por quien llama**.
     *
     * Verificado significa firma y emisor comprobados —hoy solo lo hace
     * `SupabaseJwtVerifier`—, no "venia en la cabecera `Authorization`".
     */
    data class User(val jwt: String) : SupabaseAuthScope
}

/**
 * El unico transporte HTTP hacia Supabase de los gateways migrados.
 *
 * ### Que problema cierra
 *
 * Hasta ahora cada gateway traia su propio `private fun request(...)` con las
 * mismas dos lineas de credencial concatenadas a mano. Once ficheros escribian
 * literalmente el mismo `"Bearer $serviceRoleKey"`, de modo que cambiar de
 * clave era un cambio en once sitios y la politica de credenciales no podia
 * existir en ningun lado porque no habia un "ningun lado". Aqui si lo hay:
 * [SupabaseRequestAuthPolicy] decide, y este transporte es el unico que la
 * aplica.
 *
 * Efecto colateral buscado: **todas** las respuestas de error pasan ahora por
 * un solo punto, asi que los gateways migrados ganan traduccion de errores de
 * dominio ([mapSupabaseDomainError]) que antes solo tenian dos de trece. No ha
 * hecho falta codigo nuevo para eso, solo un sitio unico.
 *
 * ### Que NO es, y no va a ser
 *
 * Cero dominio: ni una tabla, ni una columna, ni una RPC, ni un DTO, ni una
 * regla de publicacion. Cero `Json` de dominio: recibe y devuelve `String`/`ByteArray`,
 * y deserializar es de quien llama —cada gateway tiene su propia configuracion de
 * `Json` y meterla aqui obligaria a elegir una—. La unica excepcion es la respuesta
 * de FIRMA de Storage (`signedURL`), que es forma del transporte y no de ninguna
 * tabla, y por eso la lee [storageSignedUrl]. Cero cache, salvo el memo de
 * buckets de Storage, que si es un hecho del transporte. Cero `System.getenv`
 * fuera de [fromEnvironment].
 *
 * ### Deuda que este diseno NO cierra, dicha y no escondida
 *
 * `path` llega ya construido, concatenado por el llamante. No hay builder de
 * query, asi que **el riesgo de inyeccion PostgREST sigue viviendo en los
 * llamadores**, con `validatedUuid` y el urlencode de valores como unicas
 * defensas. Un builder obligaria a reescribir cientos de llamadas sin cerrar
 * ningun defecto hoy. Es deuda consciente.
 *
 * ### Por que no es `suspend`
 *
 * Porque hacerlo `suspend` obliga a convertir trece gateways y unos doscientos
 * metodos, y la ganancia real sobre envolver la llamada en [withUpstreamIo] en
 * el handler de ruta es practicamente nula: el cuello de botella no es el hilo
 * del gateway, es que la llamada bloquea uno del pool de Netty. Esa costura es
 * el cambio minimo que resuelve el problema real, y ademas es lo que mantiene
 * la correlacion de la request al cruzar de hilo.
 */
class SupabaseRequestTransport internal constructor(
    private val baseUrl: String,
    private val auth: SupabaseRequestAuthPolicy,
    private val client: HttpClient = sharedUpstreamHttpClient,
) {
    /**
     * Buckets cuya existencia ya se comprobo en ESTA instancia.
     *
     * Es la unica cache tolerada, y lo es porque la existencia de un bucket es
     * un hecho del transporte y no un dato de negocio: no caduca de forma
     * interesante y volver a preguntarlo en cada subida es una peticion de mas
     * por fichero. `ConcurrentHashMap.newKeySet` porque el gateway se comparte
     * entre hilos de request.
     */
    private val checkedBuckets: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // -----------------------------------------------------------------
    // PostgREST
    // -----------------------------------------------------------------

    /**
     * GET. Devuelve el cuerpo crudo; decodificar es del llamante.
     *
     * Existe ademas de [send] porque nueve de cada diez llamadas son un GET sin
     * cuerpo, y escribir `send("GET", path, null, null)` en cada sitio es ruido
     * que esconde las pocas llamadas que si escriben.
     */
    fun get(path: String, scope: SupabaseAuthScope = SupabaseAuthScope.Elevated): String =
        send(method = "GET", path = path, body = null, prefer = null, scope = scope)

    /** Cualquier metodo, con cuerpo opcional y `Prefer` opcional. */
    fun send(
        method: String,
        path: String,
        body: String? = null,
        prefer: String? = null,
        scope: SupabaseAuthScope = SupabaseAuthScope.Elevated,
    ): String {
        val builder = newPathBuilder(path, scope).header("Content-Type", "application/json")
        if (prefer != null) builder.header("Prefer", prefer)
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val response = exchange(method, path) { builder.method(method, publisher).build() }
        failOnError(method, path, response.statusCode(), response.body())
        return response.body()
    }

    /**
     * Conteo exacto via `Prefer: count=exact` + `Range: 0-0`.
     *
     * El total viaja en `Content-Range`, asi que no depende del `max-rows` de
     * PostgREST ni carga la tabla en memoria. **Lanza si no hay `Content-Range`
     * utilizable**, deliberadamente: un conteo que no se pudo leer no puede
     * confundirse con un cero. Quien llama decide si eso significa "metrica no
     * disponible", pero nunca "hay cero filas".
     */
    fun count(path: String, scope: SupabaseAuthScope = SupabaseAuthScope.Elevated): Int {
        val builder = newPathBuilder(path, scope)
            .header("Content-Type", "application/json")
            .header("Prefer", "count=exact")
            .header("Range-Unit", "items")
            .header("Range", "0-0")
        // 206 Partial Content es la respuesta normal a un Range acotado.
        val response = exchange("COUNT", path) { builder.GET().build() }
        failOnError("COUNT", path, response.statusCode(), response.body())
        return parseContentRangeTotal(response.headers().firstValue("content-range").orElse(null))
            ?: throw IllegalStateException(
                "Supabase COUNT ${path.substringBefore('?')} no devolvió Content-Range utilizable.",
            )
    }

    /**
     * Una PÁGINA y el total exacto en UNA petición (KQ0-3): `Prefer: count=exact`,
     * con `limit` y `offset` ya dentro de [path].
     *
     * El total sale de `Content-Range` (`0-24/137`; con cero filas el rango es un
     * asterisco y el total `0`). Un `offset` más allá del final puede responder 416
     * con el total en la cabecera: eso es una página VACÍA, no un error, porque el
     * total sigue siendo cierto. Sin total utilizable lanza, igual que [count]:
     * «no sé cuántas hay» no puede llegar como «hay 0».
     */
    fun getPage(path: String, scope: SupabaseAuthScope = SupabaseAuthScope.Elevated): SupabasePage {
        val builder = newPathBuilder(path, scope)
            .header("Content-Type", "application/json")
            .header("Prefer", "count=exact")
        val response = exchange("PAGE", path) { builder.GET().build() }
        val total = parseContentRangeTotal(response.headers().firstValue("content-range").orElse(null))
        if (response.statusCode() == 416 && total != null) return SupabasePage(body = "[]", total = total)
        failOnError("PAGE", path, response.statusCode(), response.body())
        return SupabasePage(
            body = response.body(),
            total = total ?: throw IllegalStateException(
                "Supabase PAGE ${path.substringBefore('?')} no devolvió Content-Range utilizable.",
            ),
        )
    }

    // -----------------------------------------------------------------
    // RPC
    // -----------------------------------------------------------------

    /**
     * Llama a una funcion de Postgres. [function] es el nombre desnudo, sin
     * `/rest/v1/rpc/`.
     *
     * Que el prefijo viva aqui y no en cada llamador tiene un motivo practico
     * ademas del obvio: un `grep` por RPC realmente usadas pasa a ser fiable,
     * que es justo lo que hace falta para saber que funciones tienen que existir
     * en Supabase antes de un despliegue.
     */
    fun rpc(
        function: String,
        body: String,
        prefer: String? = null,
        scope: SupabaseAuthScope = SupabaseAuthScope.Elevated,
    ): String = send(
        method = "POST",
        path = "/rest/v1/rpc/$function",
        body = body,
        prefer = prefer,
        scope = scope,
    )

    /**
     * RPC que DEVUELVE la respuesta tal cual, tambien las de error (SLICE 1).
     *
     * Los RPC de comando de perfil y de borrado de cuenta levantan claves estables
     * en `message` y el campo con el problema en `hint`; el llamante los traduce al
     * sobre canonico, cosa que [rpc] no permite porque convierte todo error en
     * excepcion antes de que se pueda leer el `hint`. Un fallo de RED sigue siendo
     * `IllegalStateException`, como en el resto del transporte.
     */
    fun rpcRaw(function: String, body: String): SupabaseRawResponse {
        val path = "/rest/v1/rpc/$function"
        val builder = newPathBuilder(path, SupabaseAuthScope.Elevated).header("Content-Type", "application/json")
        val response = exchange("POST", path) { builder.POST(HttpRequest.BodyPublishers.ofString(body)).build() }
        return SupabaseRawResponse(response.statusCode(), response.body())
    }

    // -----------------------------------------------------------------
    // Storage
    // -----------------------------------------------------------------

    /**
     * Garantiza que el bucket existe. Idempotente: un 409 significa "ya
     * estaba", que es exactamente el resultado que se buscaba.
     *
     * [public] es OBLIGATORIO y no tiene valor por defecto (F-11). Tenía `true`, y
     * eso convertía olvidar el argumento en publicar un bucket: cualquier llamador
     * nuevo que no lo escribiera creaba, en un entorno limpio, un bucket de
     * recursos servido a cualquiera con la URL. La visibilidad de un bucket es una
     * decisión, no un default.
     */
    fun storageEnsureBucket(bucket: String, public: Boolean) {
        // Se apunta en el memo SOLO tras confirmar, nunca antes: marcarlo al
        // entrar haria que una comprobacion fallida quedara registrada como
        // hecha y que el siguiente upload subiera a un bucket inexistente.
        if (bucket in checkedBuckets) return
        // `public` es un modifier keyword de Kotlin: legal como identificador,
        // pero se copia a un local antes de interpolarlo para no depender de
        // esa sutileza dentro de una plantilla de cadena.
        val isPublic = public
        val body = """{"id":"$bucket","name":"$bucket","public":$isPublic}"""
        val builder = newAbsoluteBuilder(absoluteUrl = "$baseUrl/storage/v1/bucket", scope = SupabaseAuthScope.Elevated)
            .header("Content-Type", "application/json")
        val response = try {
            client.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (error: Exception) {
            throw IllegalStateException(
                "Supabase Storage bucket check error: ${error::class.simpleName}: ${error.message}",
                error,
            )
        }
        // Storage puede expresar "ya existe" de dos formas segun su version:
        // HTTP 409, o HTTP 400 con el codigo JSON `BucketAlreadyExists` y
        // `statusCode=409`. Ambas confirman exactamente el estado buscado.
        if (response.statusCode() in 200..299 || storageBucketAlreadyExists(response.statusCode(), response.body())) {
            checkedBuckets.add(bucket)
            return
        }
        throw IllegalStateException(
            "Supabase Storage bucket check -> ${response.statusCode()}: ${response.body().take(300)}",
        )
    }

    /** Sube un objeto. Devuelve el cuerpo crudo de Storage. */
    fun storagePut(
        bucket: String,
        objectPath: String,
        bytes: ByteArray,
        contentType: String,
        upsert: Boolean = false,
    ): String {
        val url = "$baseUrl/storage/v1/object/$bucket/${encodeObjectPath(objectPath)}"
        val builder = newAbsoluteBuilder(absoluteUrl = url, scope = SupabaseAuthScope.Elevated)
            .header("Content-Type", contentType)
        if (upsert) builder.header("x-upsert", "true")
        val response = try {
            client.send(
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (error: Exception) {
            // Punto unico de subida a Storage: un solo sitio cubre cualquier
            // upload presente y futuro sin tocar cada llamador.
            logStrategicEvent(StrategicEvent.STORAGE_UPLOAD_FAILED, operation = "POST", errorType = error::class.simpleName)
            throw IllegalStateException("Supabase storage error: ${error.message}", error)
        }
        if (response.statusCode() !in 200..299) {
            logStrategicEvent(
                StrategicEvent.STORAGE_UPLOAD_FAILED,
                operation = "POST",
                result = response.statusCode().toString(),
            )
            throw IllegalStateException(
                "Supabase storage POST -> ${response.statusCode()}: ${response.body().take(200)}",
            )
        }
        return response.body()
    }

    /**
     * Borra un objeto. `true` si lo borro, `false` si no existia.
     *
     * **Un 404 no lanza**, a proposito. Antes habia tres comportamientos
     * distintos ante el mismo 404 repartidos por el backend —uno lo toleraba y
     * dos lo tragaban con `runCatching`—, lo que significaba que nadie podia
     * saber si "no fallo" queria decir "borrado" o "no estaba". Aqui hay una
     * sola semantica y el resultado lo dice el tipo de retorno.
     */
    fun storageDelete(bucket: String, objectPath: String): Boolean {
        val url = "$baseUrl/storage/v1/object/$bucket/${encodeObjectPath(objectPath)}"
        val builder = newAbsoluteBuilder(absoluteUrl = url, scope = SupabaseAuthScope.Elevated)
        val response = try {
            client.send(builder.DELETE().build(), HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException(
                "Supabase storage DELETE error: ${error::class.simpleName}: ${error.message}",
                error,
            )
        }
        if (response.statusCode() == 404) return false
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "Supabase storage DELETE -> ${response.statusCode()}: ${response.body().take(200)}",
            )
        }
        return true
    }

    /** URL publica de un objeto. No hace ninguna peticion: es composicion de texto. */
    fun storagePublicUrl(bucket: String, objectPath: String): String =
        "$baseUrl/storage/v1/object/public/$bucket/${encodeObjectPath(objectPath)}"

    /**
     * URL FIRMADA de un objeto de un bucket PRIVADO, válida [expiresInSeconds] (K5-1).
     *
     * `POST /storage/v1/object/sign/<bucket>/<ruta>` con `{"expiresIn":N}` responde
     * `{"signedURL":"…?token=…"}`. La forma exacta de `signedURL` —relativa a
     * `/storage/v1`, absoluta o con el prefijo completo— depende de la versión de
     * Storage, así que se aceptan las tres ([absoluteStorageUrl]) y el bucle vivo la
     * confirma. Credencial elevada: firmar es una capacidad de servidor.
     */
    fun storageSignedUrl(bucket: String, objectPath: String, expiresInSeconds: Int): String {
        val url = "$baseUrl/storage/v1/object/sign/$bucket/${encodeObjectPath(objectPath)}"
        val body = buildJsonObject { put("expiresIn", JsonPrimitive(expiresInSeconds)) }.toString()
        val response = exchange("SIGN", "/storage/v1/object/sign/$bucket") {
            newAbsoluteBuilder(absoluteUrl = url, scope = SupabaseAuthScope.Elevated)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Supabase storage SIGN -> ${response.statusCode()}: ${response.body().take(200)}")
        }
        val signed = runCatching {
            Json.parseToJsonElement(response.body()).jsonObject["signedURL"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: throw IllegalStateException("Supabase storage SIGN sin signedURL.")
        return absoluteStorageUrl(signed)
    }

    /**
     * Variante por LOTES para listados (K5-1): UNA petición para todas las rutas.
     *
     * `POST /storage/v1/object/sign/<bucket>` con `{"expiresIn":N,"paths":[…]}`
     * responde una lista de `{"path","signedURL","error"}`. La clave del mapa es la
     * ruta; el valor es `null` si Storage no pudo firmar ESA ruta (objeto borrado,
     * por ejemplo), sin que eso impida firmar las demás. Una lista vacía no hace
     * ninguna petición.
     */
    fun storageSignedUrls(bucket: String, objectPaths: Collection<String>, expiresInSeconds: Int): Map<String, String?> {
        if (objectPaths.isEmpty()) return emptyMap()
        val body = buildJsonObject {
            put("expiresIn", JsonPrimitive(expiresInSeconds))
            put("paths", JsonArray(objectPaths.map { JsonPrimitive(it) }))
        }.toString()
        val response = exchange("SIGN_BATCH", "/storage/v1/object/sign/$bucket") {
            newAbsoluteBuilder(absoluteUrl = "$baseUrl/storage/v1/object/sign/$bucket", scope = SupabaseAuthScope.Elevated)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "Supabase storage SIGN_BATCH -> ${response.statusCode()}: ${response.body().take(200)}",
            )
        }
        val items = runCatching { Json.parseToJsonElement(response.body()).jsonArray }.getOrNull()
            ?: throw IllegalStateException("Supabase storage SIGN_BATCH no devolvió una lista.")
        val signedByPath = items.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val path = (entry["path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            path to (entry["signedURL"] as? JsonPrimitive)?.contentOrNull?.let(::absoluteStorageUrl)
        }.toMap()
        // Toda ruta pedida tiene su entrada, firmada o no: quien llama no tiene que
        // distinguir «no vino» de «no se pudo».
        return objectPaths.associateWith { signedByPath[it] }
    }

    /** `signedURL` en cualquiera de sus tres formas → URL absoluta sobre este proyecto. */
    private fun absoluteStorageUrl(signed: String): String = when {
        signed.startsWith("http://") || signed.startsWith("https://") -> signed
        signed.startsWith("/storage/v1/") -> "$baseUrl$signed"
        else -> "$baseUrl/storage/v1${if (signed.startsWith("/")) signed else "/$signed"}"
    }

    // -----------------------------------------------------------------
    // Interno
    // -----------------------------------------------------------------

    /**
     * Builder para un path relativo a `SUPABASE_URL`.
     *
     * Nombre distinto de [newAbsoluteBuilder] y no una sobrecarga: las dos
     * tomarian `(String, SupabaseAuthScope)`, que es la MISMA firma, y elegir
     * "path" o "url absoluta" no puede depender de recordar cual se resolvia.
     */
    private fun newPathBuilder(path: String, scope: SupabaseAuthScope): HttpRequest.Builder =
        newAbsoluteBuilder(absoluteUrl = "$baseUrl$path", scope = scope)

    private fun newAbsoluteBuilder(absoluteUrl: String, scope: SupabaseAuthScope): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(uriOrRedactedFailure(absoluteUrl)).timeout(UPSTREAM_REQUEST_TIMEOUT)
        val headers = when (scope) {
            SupabaseAuthScope.Elevated -> auth.elevatedHeaders()
            is SupabaseAuthScope.User -> auth.userScopedHeaders(scope.jwt)
        }
        // `setHeader` y no `header`: `header` ACUMULA valores, y dos
        // `Authorization` en la misma peticion no es "el ultimo gana", es una
        // peticion con dos identidades.
        headers.forEach { (name, value) -> builder.setHeader(name, value) }
        return builder
    }

    private fun exchange(
        operation: String,
        path: String,
        request: () -> HttpRequest,
    ): HttpResponse<String> = try {
        client.send(request(), HttpResponse.BodyHandlers.ofString())
    } catch (error: Exception) {
        throw IllegalStateException(
            "Supabase network error on $operation ${path.substringBefore('?')}: " +
                "${error::class.simpleName}: ${error.message}",
            error,
        )
    }

    /**
     * El unico sitio donde una respuesta de error se convierte en excepcion.
     *
     * Dos salidas y ninguna mas. Si el cuerpo **empieza** por un codigo de
     * dominio conocido, [mapSupabaseDomainError] devuelve un
     * [BackendDomainException] que `StatusPages` publica tal cual al cliente
     * —status y mensaje humano de la tabla—. Si no, un [IllegalStateException]
     * que `StatusPages` **redacta**: el detalle tecnico va al log con su
     * `requestId` y al cliente solo le llega una referencia.
     *
     * No se introduce ningun tipo de error nuevo. Un `SupabaseTransportException`
     * propio obligaria a un segundo handler y a duplicar la redaccion, que es
     * justamente la parte que no se puede permitir tener por duplicado: el
     * cuerpo de PostgREST lleva `hint`s que son un oraculo de esquema.
     */
    private fun failOnError(method: String, path: String, status: Int, body: String) {
        if (status in 200..299) return
        mapSupabaseDomainError(status, body)?.let { throw it }
        throw IllegalStateException("Supabase $method ${path.substringBefore('?')} -> $status: ${body.take(300)}")
    }

    /**
     * `URI.create` sin filtrar la URL del proyecto (F-06).
     *
     * `URI.create` lanza `IllegalArgumentException` ante un carácter ilegal —un
     * espacio en un id de ruta, por ejemplo— con un mensaje que contiene la URL
     * COMPLETA: host del proyecto Supabase, tabla y filtro. `StatusPages` publica
     * los `IllegalArgumentException` como 400 con su mensaje, así que el cliente
     * recibía el esquema en la respuesta.
     *
     * Se relanza como `IllegalStateException`, que `StatusPages` redacta, y el
     * mensaje —que sólo va al log— lleva el path sin host y sin query: basta para
     * saber qué llamada se rompió y no repite lo que escribió el cliente en el
     * filtro. La defensa de verdad es validar el id en la ruta (`validatedUuid`);
     * esto es la red para el llamador que se olvide.
     */
    private fun uriOrRedactedFailure(absoluteUrl: String): URI =
        try {
            URI.create(absoluteUrl)
        } catch (error: IllegalArgumentException) {
            val path = absoluteUrl.removePrefix(baseUrl).substringBefore('?')
            throw IllegalStateException("Ruta de Supabase inválida: $path")
        }

    /** Cada segmento por separado: las barras del path NO se codifican. */
    private fun encodeObjectPath(objectPath: String): String =
        objectPath.split('/').joinToString("/") {
            URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20")
        }

    private fun storageBucketAlreadyExists(status: Int, body: String): Boolean {
        if (status == 409) return true
        if (status != 400) return false
        return runCatching {
            val error = Json.parseToJsonElement(body).jsonObject
            error["code"]?.jsonPrimitive?.contentOrNull == "BucketAlreadyExists" &&
                error["statusCode"]?.jsonPrimitive?.contentOrNull == "409"
        }.getOrDefault(false)
    }

    companion object {
        /**
         * `null` si falta `SUPABASE_URL` o la clave — exactamente el mismo
         * contrato que los `fromEnvironment()` de los gateways de hoy, para que
         * el cableado de `Application.kt` no tenga que cambiar ni una linea.
         *
         * La clave la resuelve [SupabaseRequestAuthPolicy.secretFromEnvironment],
         * que es el unico sitio donde se decide la precedencia entre
         * `SUPABASE_SECRET_KEY` y la legacy `SUPABASE_SERVICE_ROLE_KEY`.
         */
        fun fromEnvironment(environment: (String) -> String? = System::getenv): SupabaseRequestTransport? {
            val url = environment("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val secretKey = SupabaseRequestAuthPolicy.secretFromEnvironment(environment)
            if (url.isBlank() || secretKey.isBlank()) return null
            return SupabaseRequestTransport(url, SupabaseRequestAuthPolicy(secretKey))
        }

        /**
         * Solo para tests: inyecta el `HttpClient` para poder observar lo que
         * sale de verdad hacia el upstream —metodo, cabeceras y URL—, que es
         * parte del contrato y no un detalle.
         */
        internal fun forTesting(
            baseUrl: String,
            secretKey: String,
            client: HttpClient,
        ): SupabaseRequestTransport =
            SupabaseRequestTransport(baseUrl, SupabaseRequestAuthPolicy(secretKey), client)
    }
}

/**
 * Una página de PostgREST con su total exacto ([SupabaseRequestTransport.getPage]).
 * [body] es el cuerpo crudo: decodificar es del llamante, igual que con `get`.
 */
data class SupabasePage(val body: String, val total: Int)

/** Respuesta cruda de [SupabaseRequestTransport.rpcRaw]: estado y cuerpo, sin traducir errores. */
data class SupabaseRawResponse(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}
