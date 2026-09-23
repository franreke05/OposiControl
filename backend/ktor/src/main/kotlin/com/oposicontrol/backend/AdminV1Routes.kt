package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Superficie admin v1 contra el ESQUEMA REAL de Supabase (tablas plural, PK uuid,
 * columnas en espanol). Convive con /v1 (lecturas publicas) y /content (legacy).
 * Montado bajo authenticate("gateway-auth").
 *
 * Fase 1a: especialidades + examenes (incl. publicar/despublicar).
 */

/**
 * Resuelve el actor real (uuid) de un [UserIdPrincipal.name] para auditoría
 * (`reportes_contenido.revisado_por` / `acciones_moderacion.moderador_id`).
 * El bypass legacy de token estático autentica como el string literal
 * "oposicontrol-admin", que no es un uuid válido: en ese caso, y en el de
 * ausencia de principal, se devuelve `null` en vez de fabricar un actor
 * artificial (HL-23: nunca se registra un actor que no viene realmente del JWT).
 */
internal fun actorIdFromPrincipalName(name: String?): String? =
    name?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.toString()

/**
 * Cursor keyset opaco para el listado de hilos de Foro: codifica el par
 * (creado_en, id) de la última fila de la página, en ese orden — el mismo
 * orden de clasificación usado por la consulta (`order=creado_en.desc,id.desc`).
 * Opaco a propósito (no expone timestamps/ids crudos en la URL del cliente).
 */
internal fun encodeForoCursor(creadoEn: String, id: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("$creadoEn|$id".toByteArray(Charsets.UTF_8))

/** Decodifica y valida un cursor de [encodeForoCursor]. Lanza [IllegalArgumentException]
 * (→ 400) ante cualquier cursor ilegible, corrupto o con un creado_en no parseable
 * como instante ISO-8601 — nunca intenta "adivinar" una página a partir de un cursor inválido. */
internal fun decodeForoCursor(cursor: String): Pair<String, String> {
    val raw = runCatching { String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8) }.getOrNull()
        ?: throw IllegalArgumentException("Cursor invalido.")
    val separatorIndex = raw.indexOf('|')
    if (separatorIndex <= 0 || separatorIndex == raw.lastIndex) throw IllegalArgumentException("Cursor invalido.")
    val creadoEn = raw.substring(0, separatorIndex)
    val id = raw.substring(separatorIndex + 1)
    runCatching { Instant.parse(creadoEn) }.getOrElse { throw IllegalArgumentException("Cursor invalido.") }
    runCatching { UUID.fromString(id) }.getOrElse { throw IllegalArgumentException("Cursor invalido.") }
    return creadoEn to id
}

/**
 * Cursor keyset opaco para el listado de feedback de preguntas (pantalla
 * OposiTester). Misma forma exacta que [encodeForoCursor]/[decodeForoCursor]
 * (par creado_en+id, mismo orden de clasificación) — se duplica en vez de
 * reutilizarse para no tocar las funciones ya probadas de Foro por un cambio
 * que no lo necesita; ver AdminV1ForoTest/AdminV1FeedbackTest para la prueba
 * de cada una por separado.
 */
internal fun encodeFeedbackCursor(creadoEn: String, id: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("$creadoEn|$id".toByteArray(Charsets.UTF_8))

internal fun decodeFeedbackCursor(cursor: String): Pair<String, String> {
    val raw = runCatching { String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8) }.getOrNull()
        ?: throw IllegalArgumentException("Cursor invalido.")
    val separatorIndex = raw.indexOf('|')
    if (separatorIndex <= 0 || separatorIndex == raw.lastIndex) throw IllegalArgumentException("Cursor invalido.")
    val creadoEn = raw.substring(0, separatorIndex)
    val id = raw.substring(separatorIndex + 1)
    runCatching { Instant.parse(creadoEn) }.getOrElse { throw IllegalArgumentException("Cursor invalido.") }
    runCatching { UUID.fromString(id) }.getOrElse { throw IllegalArgumentException("Cursor invalido.") }
    return creadoEn to id
}

/** Recorta el contenido de un hilo a un fragmento acotado para el listado móvil:
 * evita mandar publicaciones enteras (potencialmente largas) por cada fila de una
 * lista paginada de alto volumen — el cliente igual aplica maxLines/ellipsis,
 * pero el payload de red ya llega pequeño y predecible. */
/**
 * `?activa=` de `GET /preguntas`: `true` (activas) · `false` (inactivas) ·
 * `todas`. Devuelve `null` para «todas», que es lo que el gateway entiende como
 * «sin filtro».
 *
 * ### El defecto que cierra (Q-04 / Q-2)
 *
 * `listPreguntas` filtraba `activa=eq.true` INCONDICIONALMENTE, así que el
 * filtro «Inactivas» del banco no podía devolver nada nunca: filtra un conjunto
 * del que las inactivas ya se habían ido en el servidor. Y «Todos los estados»
 * era idéntico a «Activas».
 *
 * Lo que lo hace grave es que archivar SÍ funciona (`DELETE /preguntas/{id}` →
 * `activa=false`): un administrador desactivaba una pregunta y **desaparecía del
 * banco sin forma de volver a encontrarla desde esa pantalla**. Reversible en la
 * base, irreversible en la interfaz.
 *
 * ### Por qué el default es `true` y no «todas»
 *
 * El contrato de la fase propone «ausente = TODAS». Aquí el default preserva el
 * comportamiento actual a propósito: el cliente de escritorio llama a este
 * endpoint SIN parámetro y pinta el resultado como «el banco». Cambiar el
 * significado de una llamada que ya existe metería preguntas archivadas en una
 * pantalla que nadie ha pedido que las muestre, y lo haría en silencio. La
 * capacidad nueva se PIDE; no se impone a quien no la conoce.
 *
 * Es una divergencia deliberada del contrato y está declarada como tal en el
 * informe de la fase.
 *
 * ### Nada del cliente se interpola
 *
 * Los tres valores mapean a literales fijos. La cadena del cliente no llega a la
 * consulta de PostgREST ni codificada: o es uno de los tres, o es un 400.
 */
private fun parseActivaFilter(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
    null, "", "true" -> true
    "false" -> false
    "todas", "all" -> null
    else -> throw IllegalArgumentException("activa debe ser uno de: true, false, todas.")
}

/** Orden del listado del banco (KQ0-3). Cada valor es un literal de PostgREST fijo. */
internal enum class PreguntaListOrden(val wire: String, val postgrest: String) {
    RECIENTES("recientes", "creado_en.desc,id.desc"),
    CODIGO("codigo", "codigo_pregunta.asc,id.asc"),
}

/**
 * La consulta del listado del banco, ya validada (KQ0-3). Nada de aquí es texto
 * del cliente sin neutralizar: identificadores canónicos, literales de un
 * vocabulario cerrado, números acotados y la búsqueda que sanea
 * [preguntaSearchFilter].
 */
internal data class AdminPreguntaListQuery(
    val texto: String? = null,
    val tipo: String? = null,
    val activa: Boolean? = true,
    val examenId: String? = null,
    val orden: PreguntaListOrden = PreguntaListOrden.RECIENTES,
    val limit: Int = PREGUNTA_LIST_DEFAULT_LIMIT,
    val offset: Int = 0,
)

private const val PREGUNTA_LIST_DEFAULT_LIMIT = 25
private const val PREGUNTA_LIST_MAX_LIMIT = 100
private const val PREGUNTA_LIST_MAX_OFFSET = 100_000
private const val PREGUNTA_SEARCH_MAX_LENGTH = 100
private val PREGUNTA_LIST_TYPES: Set<String> = setOf("rafaga", "razonada", "inmersiva")

/**
 * `GET /preguntas` → [AdminPreguntaListQuery], o 400 (`IllegalArgumentException`)
 * ANTES de ninguna petición.
 *
 * Límites: `limit` 1..100 (25 por defecto), `offset` 0..100 000, `q` como mucho
 * 100 caracteres. Un `offset` enorme no es un ataque, pero sí una lectura que
 * PostgREST tiene que recorrer entera para devolver cero filas.
 */
internal fun Parameters.toPreguntaListQuery(): AdminPreguntaListQuery {
    val texto = this["q"]?.trim()?.takeIf { it.isNotEmpty() }
    require(texto == null || texto.length <= PREGUNTA_SEARCH_MAX_LENGTH) {
        "La búsqueda admite como mucho $PREGUNTA_SEARCH_MAX_LENGTH caracteres."
    }
    val tipo = this["tipo"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    require(tipo == null || tipo in PREGUNTA_LIST_TYPES) { "Tipo de pregunta no válido." }
    val limit = this["limit"]?.let { it.trim().toIntOrNull() ?: throw IllegalArgumentException("limit no es un número.") }
        ?: PREGUNTA_LIST_DEFAULT_LIMIT
    val offset = this["offset"]?.let { it.trim().toIntOrNull() ?: throw IllegalArgumentException("offset no es un número.") }
        ?: 0
    require(limit in 1..PREGUNTA_LIST_MAX_LIMIT) { "limit tiene que estar entre 1 y $PREGUNTA_LIST_MAX_LIMIT." }
    require(offset in 0..PREGUNTA_LIST_MAX_OFFSET) { "offset fuera de rango." }
    val orden = when (this["orden"]?.trim()?.lowercase()) {
        null, "", PreguntaListOrden.RECIENTES.wire -> PreguntaListOrden.RECIENTES
        PreguntaListOrden.CODIGO.wire -> PreguntaListOrden.CODIGO
        else -> throw IllegalArgumentException("Orden no válido.")
    }
    return AdminPreguntaListQuery(
        texto = texto,
        tipo = tipo,
        activa = parseActivaFilter(this["activa"]),
        examenId = this["examenId"]?.takeIf { it.isNotBlank() }?.validatedUuid("examenId"),
        orden = orden,
        limit = limit,
        offset = offset,
    )
}

/**
 * La búsqueda por código o enunciado como filtro `or` de PostgREST, o `null` si no
 * queda nada que buscar.
 *
 * Mismo saneado que [perfilSearchFilter]: fuera los separadores de la sintaxis de
 * PostgREST (paréntesis, coma, asterisco, comillas y barra invertida), que dentro
 * de un `or=(…)` cambiarían la estructura del filtro, y después URL-encode, que
 * impide que `#` trunque la consulta o que `&` añada parámetros.
 */
internal fun preguntaSearchFilter(termino: String): String? {
    val saneado = termino.replace(Regex("[(),*\"\\\\]"), " ").replace(Regex("\\s+"), " ").trim()
    if (saneado.isEmpty()) return null
    val codificado = URLEncoder.encode("*$saneado*", StandardCharsets.UTF_8).replace("+", "%20")
    return "codigo_pregunta.ilike.$codificado,titulo.ilike.$codificado"
}

internal fun String.toForoSnippet(maxLength: Int = 240): String {
    val trimmed = trim()
    if (trimmed.length <= maxLength) return trimmed
    return trimmed.take(maxLength).trimEnd() + "…"
}

fun Route.adminV1Routes(gateway: AdminV1Gateway) {
    route("/api/v1/admin") {
        // F-10: todas las llamadas al gateway, en `withUpstreamIo` (el porqué, en el
        // bloque de comentario de `/examenes`, justo debajo). F-06: los `{id}` que
        // acaban en `?id=eq.$id` se validan ANTES de salir: sin validar, un espacio
        // hacía que `URI.create` fallara con la URL del proyecto en el mensaje, y un
        // `#` dejaba el PATCH de archivar sin filtro.
        route("/especialidades") {
            get {
                call.respond(AdminEspecialidadesResponse(withUpstreamIo { gateway.listEspecialidades() }))
            }
            post {
                val body = call.receive<AdminEspecialidadDto>()
                val saved = withUpstreamIo { gateway.createEspecialidad(body) }
                call.respond(HttpStatusCode.Created, AdminEspecialidadResponse(saved))
            }
            patch("/{id}") {
                val id = call.requiredPath("id").validatedUuid("id")
                val body = call.receive<AdminEspecialidadDto>()
                val saved = withUpstreamIo { gateway.updateEspecialidad(id, body) }
                call.respond(AdminEspecialidadResponse(saved))
            }
            delete("/{id}") {
                val id = call.requiredPath("id").validatedUuid("id")
                val saved = withUpstreamIo { gateway.archiveEspecialidad(id) }
                call.respond(AdminEspecialidadResponse(saved))
            }
            // Modos disponibles por especialidad (§ especialidades como configuración
            // de producto). PUT y no PATCH: el cuerpo es el estado completo de los
            // flags, así que enviar uno parcial no debe dejar el resto a medias.
            get("/{id}/capacidades") {
                val id = call.requiredPath("id")
                call.respond(
                    AdminEspecialidadCapacidadesResponse(
                        withUpstreamIo { gateway.getCapacidadesEspecialidad(id) },
                    ),
                )
            }
            put("/{id}/capacidades") {
                val id = call.requiredPath("id")
                val body = call.receive<AdminEspecialidadCapacidadesDto>()
                val saved = withUpstreamIo { gateway.upsertCapacidadesEspecialidad(id, body) }
                call.respond(AdminEspecialidadCapacidadesResponse(saved))
            }
        }
        // ---------------------------------------------------------------
        // `withUpstreamIo { ... }` en los handlers de las cuatro verticales
        // de esta fase.
        //
        // Los gateways llaman `client.send(...)`, que BLOQUEA. Las rutas de
        // Ktor corren sobre el dispatcher de Netty, cuyo pool está
        // dimensionado para trabajo no bloqueante. Cada llamada upstream
        // secuestra un hilo de ese pool hasta 20 s (el timeout de request).
        //
        // El riesgo no es la lentitud: es la INANICIÓN del pool. Con
        // suficientes peticiones lentas a la vez, Ktor deja de aceptar
        // peticiones nuevas — `/health/ready` incluida, así que el servicio se
        // declara caído por culpa de una pantalla de administración.
        //
        // Envolver aquí, y no convertir los gateways a `suspend`, es el cambio
        // más pequeño que resuelve el problema real: el hilo de Netty queda
        // libre de inmediato, el bloqueo pasa al pool `IO` (que está hecho
        // para eso y crece hasta 64 hilos), no cambia ni el orden, ni los
        // errores, ni los timeouts. Convertir 13 gateways a `suspend` son
        // ~200 firmas y una ganancia cercana a cero: el cuello de botella no
        // es el hilo del gateway.
        //
        // Es `withUpstreamIo` (Observability.kt) y NUNCA un salto al
        // dispatcher de IO escrito a mano: el cambio de hilo suelto
        // pierde el MDC, y con él el `request_id` de todo `ADMIN_ACTION` que
        // `registrarAuditoria` emite desde dentro del bloque — justo el
        // identificador que la respuesta 500 le da al cliente para
        // correlacionar. La costura reinstala ese MDC al otro lado.
        //
        // Corolario que el compilador impone y conviene leer una vez: el
        // bloque NO es `suspend`, así que `call.receive()` y
        // `call.receiveMultipart()` van FUERA. Leer el cuerpo de la petición
        // pertenece al contexto de la petición, no al pool de IO.
        // ---------------------------------------------------------------
        route("/examenes") {
            get {
                call.respond(AdminExamenesResponse(withUpstreamIo { gateway.listExamenes() }))
            }
            post {
                val body = call.receive<AdminExamenDto>()
                // F-07: los dos ids del CUERPO acaban en columnas uuid del INSERT.
                // Sin validar, un valor que no es UUID llegaba a Postgres, volvía
                // como 22P02 y salía como 500. Ahora es un 400 que nombra el campo
                // y no sale ninguna petición a Supabase. (`comunidadId` ausente o
                // `null` es "Nacional" y no se valida.)
                if (body.especialidadId.isNotBlank()) body.especialidadId.validatedUuid("especialidadId")
                body.comunidadId?.validatedUuid("comunidadId")
                val saved = withUpstreamIo { gateway.createExamen(body) }
                call.respond(HttpStatusCode.Created, AdminExamenResponse(saved))
            }
            // Todas las rutas `/{id}` de esta vertical validan el identificador.
            // `?id=eq.$id` lo lleva en PRIMERA posición del filtro y estas rutas
            // escriben: un `#` en `DELETE /examenes/{id}` deja el PATCH
            // `{"activo":false}` SIN filtro y archiva el catálogo entero; el de
            // `publicar` publicaría todos los exámenes de golpe. Es la misma
            // regla del comentario de `desvincular`, aplicada donde toca.
            get("/{id}") {
                val id = call.requiredPath("id").validatedUuid("id")
                val examen = withUpstreamIo { gateway.getExamen(id) }
                if (examen == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto(error = "not_found", message = "Examen no encontrado."))
                } else {
                    call.respond(AdminExamenResponse(examen))
                }
            }
            get("/{id}/preguntas") {
                // Esta ruta es ADMIN y NO filtra publicación: ver los borradores
                // es el caso de uso central del editor de exámenes. Su gemela
                // pública `GET /v1/examenes/{id}/preguntas` sí los descarta
                // (EXAM-009). Las dos no se mezclan a propósito.
                val id = call.requiredPath("id").validatedUuid("id")
                call.respond(AdminPreguntasResponse(withUpstreamIo { gateway.listPreguntasExamen(id) }))
            }
            // Se recibe el JSON CRUDO y no el DTO: sólo así se distingue una clave
            // AUSENTE (no se toca, en todas las columnas: contrato C-01) de una clave
            // con `null` (se borra, sólo en las tres anulables). Ver
            // `AdminV1Gateway.updateExamen(id, raw)` y `PATCHABLE_EXAMEN_COLUMNS`.
            patch("/{id}") {
                val id = call.requiredPath("id").validatedUuid("id")
                val raw = call.receive<JsonObject>()
                // Cuarta ruta de la misma familia: un id que no existe es un 404
                // con el MISMO `ErrorDto` que `GET /examenes/{id}`, no un 500.
                val saved = withUpstreamIo { gateway.updateExamen(id, raw) }
                    ?: return@patch call.respond(
                        HttpStatusCode.NotFound,
                        ErrorDto(error = "not_found", message = "Examen no encontrado."),
                    )
                call.respond(AdminExamenResponse(saved))
            }
            // Mismo vector de truncado que el DELETE de abajo, y por eso la misma
            // red: `reordenarPreguntasExamen` compone
            // `?examen_id=eq.$examenId&pregunta_id=eq.$preguntaId`, con el
            // identificador del cliente en PRIMERA posición. Un `#` en `id`
            // borra el filtro `pregunta_id` y el bucle escribe `posicion` sobre
            // TODAS las filas del examen, una vez por cada id del cuerpo: el
            // examen acaba con todas sus preguntas en la misma posición.
            //
            // Los ids del CUERPO también se validan: van en segunda posición,
            // así que no truncan, pero son la otra mitad del mismo filtro y
            // validarlos cuesta una línea.
            patch("/{id}/preguntas/orden") {
                val id = call.requiredPath("id").validatedUuid("id")
                val orden = call.receive<AdminPreguntaOrdenRequest>()
                    .let { body -> AdminPreguntaOrdenRequest(body.ids.map { it.validatedUuid("ids") }) }
                call.respond(
                    AdminPreguntasResponse(withUpstreamIo { gateway.reordenarPreguntasExamen(id, orden) }),
                )
            }
            // Desvincular ≠ archivar. `DELETE /preguntas/{id}` pone `activa=false`
            // en la tabla global y, siendo `preguntas_examen` una M:N, hace
            // desaparecer la pregunta de TODOS los exámenes que la comparten.
            // Esta ruta borra únicamente la fila de asociación de ESTE examen y
            // deja la pregunta intacta en el banco.
            delete("/{id}/preguntas/{preguntaId}") {
                // Los DOS identificadores se validan como UUID, y aquí no es
                // higiene: el identificador del cliente va en PRIMERA posición
                // de un filtro compuesto de PostgREST, y ahí un `#` borra todo
                // lo que viene detrás.
                //
                // LA REGLA, que es lo único que hay que recordar de aquí:
                //
                //   Todo VALOR del cliente que acabe en una PETICIÓN a PostgREST
                //   se neutraliza antes de entrar. Da igual por dónde llegue
                //   —PATH, CUERPO, QUERY o un campo de MULTIPART—, da igual si
                //   está inventariado en algún comentario o no, y da igual si
                //   acaba en la CONSULTA o en el CUERPO.
                //
                // Neutralizar es una cosa distinta según DÓNDE acabe el valor y
                // según QUÉ sea, y confundirlas ha sido el agujero cuarto y el
                // quinto:
                //
                //   · En la CONSULTA, si es un IDENTIFICADOR, `validatedUuid`.
                //     Un no-uuid es un 400 y nunca viaja.
                //   · En la CONSULTA, si es TEXTO LIBRE de un vocabulario que
                //     este backend no posee —`estado` de feedback es el caso—, no
                //     hay uuid que valga: se CODIFICA (`urlEncode`), que es lo que
                //     impide que `#` trunque la consulta y que `&` añada
                //     parámetros.
                //   · En el CUERPO, SIEMPRE el serializador y NUNCA una plantilla
                //     de cadena. `sbJson.encodeToString(valor)` escapa comillas y
                //     control; `"""{"x":"$valor"}"""` no escapa nada.
                //
                // La quinta versión de este comentario decía «dentro de una
                // consulta de PostgREST», y el cuerpo de una escritura no es la
                // consulta: por esa rendija se coló el `mimeType` del cliente,
                // concatenado crudo en el JSON del PATCH de `examenes`
                // (`uploadExamenPortada`). La redacción anterior no acotaba de
                // menos el INVENTARIO, que era el error de las tres primeras;
                // acotaba de menos la CLASE DE DESTINO, que es peor porque suena
                // completa.
                //
                // No hay lista cerrada, y este comentario ya ha tenido que
                // corregirse TRES veces por intentar tenerla:
                //
                //   1.ª versión: afirmaba que ésta era «la única ruta de las
                //      cuatro verticales» con esta forma. Falso: había tres más
                //      por el path.
                //   2.ª versión: enumeró esas tres y dijo que se validaba «en la
                //      RUTA y no en el gateway para que no se pueda saltar
                //      añadiendo otro llamador del gateway». También falso: el
                //      otro llamador YA EXISTÍA y era anterior al arreglo
                //      (`PATCH /preguntas/{id}` → `updatePregunta`), y entraba
                //      por una puerta que la enumeración ni miraba: el CUERPO.
                //      Dos identificadores de cuerpo —`examenId` y
                //      `recursos[].id`— llegaban crudos a un filtro.
                //   3.ª versión: decía «PATH o CUERPO», y la vertical de
                //      IMPORTACIONES entera no la cumplía: cuatro rutas
                //      (`GET /importaciones/preguntas/{loteId}`,
                //      `PATCH /importaciones/staging/{id}`,
                //      `POST /importaciones/{loteId}/importar` y el `examenId`
                //      de MULTIPART de `/importaciones/preguntas/pdf`). Y el
                //      `estado` de `GET /feedback/preguntas` entraba por la
                //      QUERY, que no miraba ninguna de las dos redes — un `#`
                //      ahí se llevaba el `limit` de una tabla de alto volumen.
                //
                // Una red de seguridad descrita más amplia de lo que es resulta
                // peor que ninguna: quien lea el inventario deja de buscar. Por
                // eso aquí ya no hay inventario, hay regla.
                //
                // El vector concreto: `URI.create("$supabaseUrl$path")` parsea
                // `#` como inicio de fragmento, y el fragmento NO viaja en el
                // request-target. Un `#` dentro de `examenId` trunca todo lo
                // que va detrás, así que
                //   DELETE /preguntas_examen?examen_id=eq.<uuid>#&pregunta_id=eq.<X>
                // llega al upstream como
                //   DELETE /preguntas_examen?examen_id=eq.<uuid>
                // El filtro `pregunta_id` desaparece y **el DELETE borra todas
                // las asociaciones del examen**. Una ruta que promete "quita
                // una pregunta sin tocarla en el banco" vaciaría el examen
                // entero. El mismo truncado afecta al PATCH de recompactación
                // que hace esta misma función después de borrar, dejando todas
                // las filas con la misma `posicion`.
                //
                // Se valida en la RUTA porque aquí es donde el valor deja de ser
                // del cliente. Eso NO basta por sí solo —un segundo llamador del
                // gateway se la salta, y eso fue exactamente lo que pasó— así que
                // la regla se aplica en TODAS las rutas que reciben el valor, por
                // el path o por el cuerpo, y no sólo en ésta.
                val examenId = call.requiredPath("id").validatedUuid("id")
                val preguntaId = call.requiredPath("preguntaId").validatedUuid("preguntaId")
                call.respond(
                    AdminPreguntasResponse(
                        withUpstreamIo {
                            gateway.desvincularPreguntaExamen(examenId, preguntaId)
                        },
                    ),
                )
            }
            // Las tres capacidades de ciclo de vida: un id que no existe es un
            // 404, no un 500.
            //
            // Antes las tres hacían `.firstOrNull() ?: error("…")`, y un
            // `IllegalStateException` sale por el handler de upstream: 500
            // redactado, «Error al consultar el servicio de datos. Referencia:
            // …». Eso dice «el servidor está roto» cuando lo que pasa es que el
            // examen no existe, y además lo hace indistinguible de un fallo real
            // de Supabase, que es exactamente el caso que esa referencia existe
            // para poder investigar. Ahora el gateway devuelve `null` y la ruta
            // responde con el MISMO `ErrorDto` que `GET /examenes/{id}`.
            post("/{id}/publicar") {
                val id = call.requiredPath("id").validatedUuid("id")
                val saved = withUpstreamIo { gateway.publicarExamen(id) }
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorDto(error = "not_found", message = "Examen no encontrado."),
                    )
                call.respond(AdminExamenResponse(saved))
            }
            post("/{id}/despublicar") {
                val id = call.requiredPath("id").validatedUuid("id")
                val saved = withUpstreamIo { gateway.despublicarExamen(id) }
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorDto(error = "not_found", message = "Examen no encontrado."),
                    )
                call.respond(AdminExamenResponse(saved))
            }
            // Cuarta capacidad de ciclo de vida, y la que faltaba: ARCHIVAR con
            // ruta propia.
            //
            // Antes archivar era lo que hacía `DELETE /{id}`, así que la consola
            // ofrecía «Eliminar» sobre una operación que no eliminaba: el examen
            // seguía en la base con `activo=false` y no había forma de quitar del
            // catálogo real un examen de prueba. Ahora el DELETE borra (ver
            // abajo) y archivar vive aquí, con la misma forma que sus hermanas
            // `publicar`/`despublicar` —POST, 404 si el id no existe, el examen
            // guardado en la respuesta—.
            post("/{id}/archivar") {
                val id = call.requiredPath("id").validatedUuid("id")
                val saved = withUpstreamIo { gateway.archiveExamen(id) }
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorDto(error = "not_found", message = "Examen no encontrado."),
                    )
                call.respond(AdminExamenResponse(saved))
            }
            // BORRADO REAL, con guarda. Ver `AdminV1Gateway.deleteExamen` para
            // por qué hay guarda, cómo se cuenta y en qué orden se borra.
            //
            // `false` = el examen no existe, y se traduce al MISMO 404 que
            // `GET /examenes/{id}`. Un examen con datos de usuario NO sale por
            // aquí: el gateway lanza `BackendDomainException` y el handler de
            // `Application.kt` la convierte en el 409 con su código de dominio.
            //
            // 204 y no la fila borrada: lo que había ya no existe, y devolver su
            // último retrato invitaría al cliente a re-sembrar un editor con un
            // examen que acaba de desaparecer.
            delete("/{id}") {
                val id = call.requiredPath("id").validatedUuid("id")
                val deleted = withUpstreamIo { gateway.deleteExamen(id) }
                if (!deleted) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorDto(error = "not_found", message = "Examen no encontrado."),
                    )
                } else {
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            post("/{id}/portada") {
                val id = call.requiredPath("id").validatedUuid("id")
                val multipart = call.receiveMultipart()
                var fileBytes: ByteArray? = null
                var mimeType = ""
                multipart.forEachPart { part ->
                    try {
                        if (part is PartData.FileItem && part.name == "file") {
                            // K5: el TIPO se decide antes de leer un solo byte, contra los tres
                            // formatos de portada (422 `EXAM_COVER_TYPE_INVALID`). Un tipo
                            // ausente también se rechaza: ya no hay un «image/jpeg» por defecto
                            // que etiquete como imagen lo que no lo es. F-09: el mensaje es una
                            // constante y no repite lo que mandó el cliente.
                            val partMime = part.contentType
                                ?.let { "${it.contentType}/${it.contentSubtype}" }
                                ?.lowercase()
                                .orEmpty()
                            if (partMime !in EXAM_COVER_EXTENSIONS) throw examCoverTypeInvalidException()
                            // F-13: `readRemaining` suspende en vez de bloquear el hilo de la
                            // petición, y el `+ 1` distingue «justo el límite» de «lo supera».
                            val limited = part.provider().readRemaining(EXAM_COVER_MAX_BYTES + 1L).readByteArray()
                            require(limited.size <= EXAM_COVER_MAX_BYTES) { "El archivo supera el limite de 10 MB." }
                            fileBytes = limited
                            mimeType = partMime
                            // `originalFileName` ya no se usa: el objeto se nombra con un uuid.
                        }
                    } finally {
                        part.dispose()
                    }
                }
                val bytes = fileBytes
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("no_file", "No se recibió archivo."))
                // Sólo la llamada al gateway va al pool IO: el `return@post` de
                // arriba tiene que seguir siendo un retorno del handler.
                val saved = withUpstreamIo { gateway.uploadExamenPortada(id, bytes, mimeType) }
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorDto(error = "not_found", message = "Examen no encontrado."),
                    )
                call.respond(AdminExamenResponse(saved))
            }
            delete("/{id}/portada") {
                val id = call.requiredPath("id").validatedUuid("id")
                val saved = withUpstreamIo { gateway.clearExamenPortada(id) }
                    ?: return@delete call.respond(
                        HttpStatusCode.NotFound,
                        ErrorDto(error = "not_found", message = "Examen no encontrado."),
                    )
                call.respond(AdminExamenResponse(saved))
            }
        }
        route("/preguntas") {
            // KQ0-3: una PÁGINA del banco con su total, filtrada y ordenada EN EL
            // SERVIDOR. `?q=&tipo=&activa=&examenId=&orden=&limit=&offset=` (la pregunta no
            // tiene especialidad: no hay filtro por ella; `especialidadId` se ignora)
            // → `{data, total, limit, offset}`. `activa` ausente sigue siendo `true`
            // (ver [parseActivaFilter] para por qué el default NO es «todas»).
            //
            // Todo el parseo va FUERA de `withUpstreamIo` y antes de la primera
            // petición: un valor inválido es un 400 y no sale ni una consulta.
            get {
                val query = call.request.queryParameters.toPreguntaListQuery()
                call.respond(withUpstreamIo { gateway.listPreguntasPagina(query) })
            }
            // KQ2-1: alta por `admin_create_question`, idempotente por `id`.
            //
            // El código de estado distingue las dos cosas que el cliente necesita
            // saber tras un reintento: **201** = esta llamada creó la pregunta;
            // **200** = ya existía con el mismo contenido (el primer intento sí
            // llegó, aunque su respuesta se perdiera). Un 201 en el reintento
            // haría creer al banco que hay dos preguntas donde hay una.
            post {
                // F-07: mismos ids de cuerpo que el PATCH de abajo. Sin validar, un
                // valor que no es UUID volvía de Postgres como 22P02 (500) y un `#`
                // en `examenId` truncaba el filtro. Se rechaza con 400 antes de
                // llamar a Supabase. La pregunta NO lleva especialidad (M8): es del examen.
                val body = call.receive<AdminPreguntaDto>().withValidatedIdentifiers()
                // Q2-4: el autor es el `sub` del JWT verificado, o nadie. Un id
                // inexistente en `auth.users` era un 23503 con el texto del borrado.
                val actorId = actorIdFromPrincipalName(call.principal<UserIdPrincipal>()?.name)
                val saved = withUpstreamIo { gateway.createPregunta(body, actorId = actorId) }
                call.respond(
                    if (saved.creada) HttpStatusCode.Created else HttpStatusCode.OK,
                    AdminPreguntaResponse(saved.pregunta),
                )
            }
            get("/{id}") {
                val id = call.requiredPath("id").validatedUuid("id")
                val pregunta = withUpstreamIo { gateway.getPregunta(id) }
                if (pregunta == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto(error = "not_found", message = "Pregunta no encontrada."))
                } else {
                    call.respond(AdminPreguntaResponse(pregunta))
                }
            }
            // KQ3-1 / D-03: la ÚNICA puerta para ver, crear y quitar el vínculo
            // pregunta ↔ examen. Antes esto no existía en ninguna forma: los
            // vínculos se INFERÍAN de la sesión (sólo veías uno si habías llegado
            // filtrando por ese examen), «Quitar» estaba bloqueado desde Preguntas,
            // y vincular era un efecto secundario del PATCH de la pregunta que
            // reenviaba el borrador entero y guardaba ediciones sin confirmar.
            //
            // Las tres responden la lista COMPLETA de vínculos ya releída, no un
            // 204: quien vincula o quita quiere pintar el resultado, y hacerle
            // pedir la lista otra vez abre una ventana en la que la pantalla
            // enseña un estado que ya no es.
            get("/{id}/examenes") {
                val id = call.requiredPath("id").validatedUuid("id")
                call.respond(
                    AdminPreguntaExamenesResponse(withUpstreamIo { gateway.listExamenesDePregunta(id) }),
                )
            }
            post("/{id}/examenes") {
                val id = call.requiredPath("id").validatedUuid("id")
                val body = call.receive<AdminVincularPreguntaRequest>()
                val examenId = body.examenId.validatedUuid("examenId")
                // La RPC también lo rechaza (`EXAM_QUESTION_POSITION_INVALID`), pero
                // un 0 o un negativo no necesita viajar para saberse mal.
                require(body.posicion == null || body.posicion >= 1) { "La posición tiene que ser 1 o mayor." }
                val vinculos = withUpstreamIo {
                    gateway.vincularPreguntaExamen(examenId = examenId, preguntaId = id, posicion = body.posicion)
                    gateway.registrarAuditoria("pregunta_vinculada", "preguntas_examen", examenId)
                    gateway.listExamenesDePregunta(id)
                }
                call.respond(AdminPreguntaExamenesResponse(vinculos))
            }
            delete("/{id}/examenes/{examenId}") {
                val id = call.requiredPath("id").validatedUuid("id")
                val examenId = call.requiredPath("examenId").validatedUuid("examenId")
                val vinculos = withUpstreamIo {
                    gateway.quitarVinculoPreguntaExamen(examenId = examenId, preguntaId = id)
                    gateway.listExamenesDePregunta(id)
                }
                call.respond(AdminPreguntaExamenesResponse(vinculos))
            }
            // Aquí entraban TRES identificadores de cliente que acababan en un
            // filtro de PostgREST; desde KQ0-8 y D-03 queda UNO, el del path:
            // `recursos[]` se ignora entero y `examenId` ya no vincula. Los del
            // cuerpo fueron el agujero real (D-3, C-1/C-2): la validación se había
            // puesto sólo en las rutas hermanas, y `updatePregunta` era un segundo
            // llamador del mismo gateway.
            //
            //   · `{id}` (path)        → `PATCH /preguntas?id=eq.$id`. Con un `#`
            //     el filtro entero desaparece y el PATCH reescribe TODAS las
            //     preguntas del banco. (Hoy el PATCH es una RPC con el id como
            //     argumento `uuid`, así que ya no hay filtro que truncar; el id se
            //     sigue validando porque un no-uuid sería un 22P02 → 500.)
            //   · `examenId` (cuerpo)  → `upsertPreguntaExamen`, que leía
            //     `?examen_id=eq.$examenId&pregunta_id=eq.$preguntaId` y escribía
            //     `{"posicion":N}` con el mismo filtro. Truncado: la lectura pasaba
            //     a ser «la primera fila cualquiera del examen» —con lo que casi
            //     siempre entraba por la rama de PATCH— y la escritura ponía la
            //     misma `posicion` en todas las preguntas del examen. CERRADO por
            //     partida doble: ese helper ya no existe y este PATCH no vincula.
            //   · `recursos[].id` (cuerpo) → `updateRecurso`, que compone
            //     `?id=eq.$recursoId&pregunta_id=eq.$preguntaId`. El filtro que
            //     se pierde es el de PERTENENCIA, y `updateRecurso` reescribe la
            //     fila entera **incluido `pregunta_id`**: el recurso cambia de
            //     dueño y el cliente recibe un 200. CERRADO por KQ0-8: el PATCH
            //     de pregunta ya no toca recursos; tienen sus propias rutas.
            //
            // KQ2-2: el cuerpo se recibe como JSON CRUDO y no como `AdminPreguntaDto`.
            // Con el DTO no hay forma de distinguir «no mandes el título» de «pon el
            // título por defecto», porque un campo ausente y uno con su default se
            // decodifican igual; y `admin_update_question` escribe SÓLO las claves
            // presentes. Con el objeto crudo, ausente es ausente.
            //
            // `examenId` y `posicion` se IGNORAN aquí (D-03/D-04): vincular tiene una
            // única puerta, `POST /preguntas/{id}/examenes`.
            patch("/{id}") {
                val id = call.requiredPath("id").validatedUuid("id")
                val body = call.receive<JsonObject>()
                call.respond(AdminPreguntaResponse(withUpstreamIo { gateway.updatePregunta(id, body) }))
            }
            // `archivePregunta` compone `PATCH /preguntas?id=eq.$id` con
            // `{"activa":false}`: un `#` aquí archiva el banco entero.
            delete("/{id}") {
                val id = call.requiredPath("id").validatedUuid("id")
                call.respond(AdminPreguntaResponse(withUpstreamIo { gateway.archivePregunta(id) }))
            }
            get("/{id}/recursos") {
                val id = call.requiredPath("id").validatedUuid("id")
                call.respond(AdminRecursosResponse(withUpstreamIo { gateway.listRecursos(id) }))
            }
            // «Sólo INMERSIVA admite recursos» se impone AQUÍ DENTRO, en el
            // gateway, y no en este handler: un gateway puede tener más llamadores
            // que una ruta —`PATCH /preguntas/{id}` con `recursos[]` lo fue hasta
            // KQ0-8— y ésa es exactamente la lección que ya costó dos correcciones
            // en la regla de filtros de más arriba. Ver `AdminV1Gateway.addRecurso`.
            post("/{id}/recursos") {
                val id = call.requiredPath("id").validatedUuid("id")
                val body = call.receive<AdminRecursoDto>()
                val saved = withUpstreamIo { gateway.addRecurso(id, body) }
                call.respond(HttpStatusCode.Created, AdminRecursoResponse(saved))
            }
            // KQ4-2: el ARCHIVO de un recurso (imagen, audio, vídeo o PDF).
            //
            // La regla «sólo inmersiva» se comprueba ANTES de leer el cuerpo: si la
            // pregunta no existe o no admite material, el administrador recibe su 404 o
            // su 422 sin que 50 MB hayan cruzado la red ni tocado Storage.
            post("/{id}/recursos/archivo") {
                val id = call.requiredPath("id").validatedUuid("id")
                withUpstreamIo { gateway.requireQuestionAcceptsResources(id) }
                var fileBytes: ByteArray? = null
                var mimeType = ""
                var tipoRecurso = ""
                var titulo: String? = null
                var descripcion: String? = null
                var posicion: Int? = null
                var recursoId = ""
                call.receiveMultipart().forEachPart { part ->
                    try {
                        when (part) {
                            is PartData.FormItem -> when (part.name) {
                                "tipoRecurso" -> tipoRecurso = part.value
                                "titulo" -> titulo = part.value
                                "descripcion" -> descripcion = part.value
                                "posicion" -> posicion = part.value.toIntOrNull()
                                "recursoId" -> recursoId = part.value
                                else -> Unit
                            }
                            is PartData.FileItem -> if (part.name == "file") {
                                // El MIME sale de la PARTE, sin parámetros (`; charset=…`), y
                                // nunca del nombre del fichero: la extensión del objeto la
                                // decide el mapa del servidor.
                                mimeType = part.contentType
                                    ?.let { "${it.contentType}/${it.contentSubtype}" }
                                    ?.lowercase()
                                    .orEmpty()
                                // F-13: `readRemaining` suspende en vez de bloquear el hilo de
                                // la petición; el `+ 1` distingue «justo el límite» de «lo supera».
                                val limited = part.provider()
                                    .readRemaining(QUESTION_RESOURCE_MAX_BYTES + 1L)
                                    .readByteArray()
                                if (limited.size > QUESTION_RESOURCE_MAX_BYTES) throw domainError("QUESTION_RESOURCE_TOO_LARGE")
                                fileBytes = limited
                            }
                            else -> Unit
                        }
                    } finally {
                        part.dispose()
                    }
                }
                val bytes = fileBytes ?: throw domainError("QUESTION_RESOURCE_FILE_REQUIRED")
                // El id es la clave de idempotencia del reintento: si el cliente lo manda,
                // se respeta; si no, esta llamada es necesariamente un primer intento.
                val resourceId = recursoId.takeIf { it.isNotBlank() }?.validatedUuid("recursoId")
                    ?: UUID.randomUUID().toString()
                val saved = withUpstreamIo {
                    // La compresión va DENTRO de `withUpstreamIo` y no antes: es
                    // trabajo bloqueante —CPU para una imagen, un proceso externo
                    // para vídeo o audio— y el hilo de Netty no es sitio para eso.
                    // Aquí ya no se suspende nada, que es lo que exige el contrato
                    // de `withUpstreamIo`.
                    val optimized = optimizedQuestionResource(tipoRecurso, mimeType, bytes)
                    gateway.addRecursoArchivo(
                        preguntaId = id,
                        recursoId = resourceId,
                        tipoRecurso = tipoRecurso,
                        titulo = titulo,
                        descripcion = descripcion,
                        posicion = posicion,
                        mimeType = optimized.mimeType,
                        bytes = optimized.bytes,
                    )
                }
                call.respond(HttpStatusCode.Created, AdminRecursoResponse(saved))
            }
            // `recursoId` va en PRIMERA posición del filtro que componen
            // `updateRecurso`/`archiveRecurso`
            // (`?id=eq.$recursoId&pregunta_id=eq.$preguntaId`), así que un `#`
            // ahí no sólo trunca: el filtro que desaparece es justamente el de
            // PERTENENCIA. El recurso se sobrescribiría —o se archivaría— sin
            // comprobar de qué pregunta es. Se validan los dos, por el mismo
            // motivo que en el DELETE de desvincular.
            //
            // El daño era mayor cuando este comentario se escribió:
            // `updateRecurso` reescribía la fila entera e incluía `pregunta_id`,
            // así que el recurso además cambiaba de dueño. Hoy el PATCH sólo
            // manda los campos editables, pero **eso no hace innecesaria la
            // validación**: sin ella el título y el contenido de un recurso
            // ajeno se sobrescriben igual.
            patch("/{id}/recursos/{recursoId}") {
                val id = call.requiredPath("id").validatedUuid("id")
                val recursoId = call.requiredPath("recursoId").validatedUuid("recursoId")
                val body = call.receive<AdminRecursoDto>()
                call.respond(
                    AdminRecursoResponse(withUpstreamIo { gateway.updateRecurso(id, recursoId, body) }),
                )
            }
            delete("/{id}/recursos/{recursoId}") {
                val id = call.requiredPath("id").validatedUuid("id")
                val recursoId = call.requiredPath("recursoId").validatedUuid("recursoId")
                call.respond(
                    AdminRecursoResponse(withUpstreamIo { gateway.archiveRecurso(id, recursoId) }),
                )
            }
        }
        route("/importaciones") {
            route("/preguntas") {
                get {
                    call.respond(AdminLotesResponse(withUpstreamIo { gateway.listLotes() }))
                }
                post {
                    val body = call.receive<CrearLoteRequest>()
                    // D-11: el lote tiene autor. `subido_por` no se escribía nunca, así
                    // que el historial no podía decir quién subió qué.
                    val actorId = actorIdFromPrincipalName(call.principal<UserIdPrincipal>()?.name)
                    val lote = withUpstreamIo { gateway.crearLote(body, actorId) }
                    call.respond(HttpStatusCode.Created, AdminLoteResponse(lote))
                }
                post("/pdf") {
                    var examenId = ""
                    var tipoExamen = "rafaga"
                    var nombreArchivo = "importacion.pdf"
                    var pdfBytes: ByteArray? = null
                    call.receiveMultipart().forEachPart { part ->
                        try {
                            when (part) {
                                is PartData.FormItem -> when (part.name) {
                                    "examenId" -> examenId = part.value
                                    "tipoExamen" -> tipoExamen = part.value
                                }
                                is PartData.FileItem -> if (part.name == "file") {
                                    nombreArchivo = part.originalFileName ?: nombreArchivo
                                    // F-13: lectura que suspende, no que bloquea el hilo de la petición.
                                    val bytes = part.provider().readRemaining(20L * 1024 * 1024 + 1).readByteArray()
                                    require(bytes.size <= 20 * 1024 * 1024) { "El PDF supera el límite de 20 MB." }
                                    pdfBytes = bytes
                                }
                                else -> Unit
                            }
                        } finally {
                            part.dispose()
                        }
                    }
                    if (examenId.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "examenId es obligatorio"))
                    }
                    // CUARTA ruta de esta vertical con el mismo hueco, y la que
                    // el inventario de residuos no contaba: `crearLoteDesdePdf`
                    // arranca leyendo `examenes?select=*&id=eq.` con este valor.
                    // Llega por un campo de MULTIPART, que no es ni el path ni un
                    // cuerpo JSON — otra puerta mas por la que la regla se
                    // esquivaba sola.
                    examenId.validatedUuid("examenId")
                    val bytes = pdfBytes
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Campo 'file' con el PDF es obligatorio"))
                    val actorId = actorIdFromPrincipalName(call.principal<UserIdPrincipal>()?.name)
                    // El flujo canónico hereda los eventos de la ruta retirada
                    // (DEC-IMP-05): el observatorio de importaciones dejaría de ver
                    // nada si se hubiesen ido con ella.
                    logStrategicEvent(
                        StrategicEvent.IMPORT_STARTED,
                        operation = "import_pdf",
                        entityType = "examenes",
                        entityId = examenId,
                    )
                    // El parseo del PDF, además de bloqueante en red, es
                    // bloqueante en CPU (PDFBox). Motivo de más para no hacerlo
                    // en un hilo del pool de Netty.
                    //
                    // `try/catch` con re-lanzado explícito y NO `runCatching`:
                    // `runCatching` captura `Throwable`, cancelación incluida.
                    // Mientras esto envolvía una llamada síncrona no podía
                    // llegar una `CancellationException`; envolviendo un salto
                    // de contexto sí. Si el admin cierra la pestaña a mitad de
                    // una subida de 18 MB, tragársela convertiría una
                    // cancelación en un 422 de negocio sobre una llamada ya
                    // cancelada — ese `respond` vuelve a lanzar y acaba en el
                    // catch-all como `UNCAUGHT_EXCEPTION`, ensuciando la
                    // taxonomía de eventos con un error que no es ningún error.
                    // Relanzarla es lo que mantiene la concurrencia
                    // estructurada: la cancelación es del llamante, no un fallo
                    // del PDF.
                    //
                    // F-05: sólo un RECHAZO DEL PDF ([PdfImportRejectedException]) es
                    // un 422, con el mensaje que escribió el gateway para el
                    // administrador. Todo lo demás —Supabase, red, un error de dominio—
                    // sigue hasta StatusPages, que lo redacta con referencia. Capturar
                    // `Throwable` aquí devolvía `error.message` tal cual, y ese mensaje
                    // podía ser el cuerpo crudo de PostgREST.
                    val lote = try {
                        withUpstreamIo {
                            gateway.crearLoteDesdePdf(examenId, tipoExamen, nombreArchivo, bytes, actorId)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (rejected: PdfImportRejectedException) {
                        logStrategicEvent(
                            StrategicEvent.IMPORT_VALIDATION_FAILED,
                            operation = "import_pdf",
                            result = "rejected",
                            entityType = "examenes",
                            entityId = examenId,
                        )
                        return@post call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorDto("pdf_unprocessable", rejected.message ?: "No se pudo procesar el PDF."),
                        )
                    }
                    // `count` = cuántas filas necesitan a un humano, NUNCA su contenido:
                    // ese texto sale del PDF y no puede acabar en un evento.
                    val revisables = lote.staging.count { it.estadoParseo == "error" }
                    if (revisables > 0) {
                        logStrategicEvent(
                            StrategicEvent.IMPORT_VALIDATION_FAILED,
                            operation = "import_pdf",
                            result = "validation_failed",
                            entityType = "examenes",
                            entityId = examenId,
                            count = revisables,
                        )
                    }
                    call.respond(HttpStatusCode.Created, AdminLoteResponse(lote))
                }
                // `getLote` compone DOS filtros con este identificador
                // (`lotes_importacion_preguntas?id=eq.` y
                // `preguntas_parseadas_staging?lote_id=eq.`), y el segundo lleva
                // detrás un `order`. Es la MISMA regla del `delete` de
                // desvincular, aplicada donde faltaba.
                get("/{loteId}") {
                    val loteId = call.requiredPath("loteId").validatedUuid("loteId")
                    val lote = withUpstreamIo { gateway.getLote(loteId) }
                    if (lote == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorDto(error = "not_found", message = "Lote no encontrado."))
                    } else {
                        call.respond(AdminLoteResponse(lote))
                    }
                }
            }
            // `updateStaging` compone `PATCH /preguntas_parseadas_staging?id=eq.`
            // con el identificador en ULTIMA posicion de la consulta. Ahi el `#`
            // no borra ningun filtro, pero el `&` es peor: el cliente ESCRIBE
            // parametros de PostgREST sobre una ruta de escritura que responde
            // `return=representation`. Un `select` embebido devolveria filas de
            // otras tablas desde un PATCH.
            patch("/staging/{id}") {
                val id = call.requiredPath("id").validatedUuid("id")
                val body = call.receive<AdminStagingItemDto>()
                call.respond(AdminStagingResponse(withUpstreamIo { gateway.updateStaging(id, body) }))
            }
            // La operación más cara de la vertical —crea preguntas reales, una por
            // fila de staging— y su identificador entraba crudo en dos filtros.
            //
            // El cuerpo es OPCIONAL: `{"confirmarExamenPublicado":true}` es la
            // respuesta del administrador al diálogo «el examen está publicado, las
            // preguntas se verán al instante». Sin cuerpo se asume `false`, que es el
            // lado que pregunta en vez del que publica.
            post("/{loteId}/importar") {
                val loteId = call.requiredPath("loteId").validatedUuid("loteId")
                val req: AdminImportarLoteRequest = if (call.request.contentLength().let { it == null || it == 0L }) {
                    AdminImportarLoteRequest()
                } else {
                    call.receive<AdminImportarLoteRequest>()
                }
                val actorId = actorIdFromPrincipalName(call.principal<UserIdPrincipal>()?.name)
                val respuesta = withUpstreamIo {
                    gateway.importarLote(loteId, req.confirmarExamenPublicado, actorId)
                }
                if (respuesta.resultado.ejecutado) {
                    logStrategicEvent(
                        StrategicEvent.IMPORT_PUBLISHED,
                        operation = "import_publish",
                        result = "success",
                        entityType = "lotes_importacion_preguntas",
                        entityId = loteId,
                        count = respuesta.resultado.nuevas + respuesta.resultado.vinculadas,
                    )
                }
                call.respond(respuesta)
            }
        }
        route("/feedback/preguntas") {
            get {
                val params = call.request.queryParameters
                val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                val cursor = params["cursor"]?.takeIf { it.isNotBlank() }
                call.respond(
                    gateway.listFeedback(
                        estado = params["estado"],
                        tipoFeedback = params["tipo"]?.takeIf { it.isNotBlank() },
                        cursor = cursor,
                        limit = limit,
                    ),
                )
            }
            get("/{id}") {
                val detail = gateway.getFeedbackDetail(call.requiredPath("id").validatedUuid("id"))
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Incidencia no encontrada."))
                call.respond(AdminFeedbackDetailResponse(detail))
            }
            // El cuerpo se recibe SIN red: un cuerpo inválido tiene que dar 400.
            //
            // Antes era
            //   runCatching { call.receive<…>() }.getOrDefault(ResolverFeedbackRequest())
            // y eso era fail-open sobre una ESCRITURA, que es la peor forma de
            // fail-open. `ResolverFeedbackRequest.estado` vale `"resuelto"` por
            // defecto, así que un cuerpo malformado, ausente o abortado a mitad
            // no daba 400: caía al default, escribía `{"estado":"resuelto"}` en
            // `feedback_pregunta`, registraba `feedback_resuelto` en auditoría y
            // emitía `FEEDBACK_RESOLVED`. Un error benigno del cliente se
            // convertía en un cambio de estado de negocio MÁS un rastro de
            // auditoría afirmando que un administrador resolvió la incidencia.
            // Una pista de auditoría que no se puede creer no sirve de nada.
            //
            // Además `call.receive()` es `suspend` sobre el contexto de la
            // petición: si el admin cierra la pestaña a mitad del envío, el
            // `runCatching` se tragaba también la `CancellationException` y la
            // convertía en una resolución. Sin `runCatching` la cancelación se
            // propaga, que es lo correcto: es del llamante, no un fallo nuestro.
            //
            // Un cuerpo mal formado sale por `BadRequestException` (400 con
            // mensaje genérico, sin filtrar el DTO esperado) y un `estado` fuera
            // del vocabulario por el `require` de `resolverFeedback` (400).
            post("/{id}/resolver") {
                val id = call.requiredPath("id").validatedUuid("id")
                val req = call.receive<ResolverFeedbackRequest>()
                call.respond(AdminFeedbackResponse(gateway.resolverFeedback(id, req)))
            }
        }
        get("/auditoria") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
            call.respond(AdminAuditoriasResponse(gateway.listAuditoria(limit)))
        }
        route("/usuarios") {
            get {
                // `limit`/`offset` opcionales: sin ellos se sirve la primera página
                // con el tamaño por defecto, así que los consumidores anteriores
                // siguen funcionando sin cambios.
                val page = gateway.listUsuarios(
                    limit = call.request.queryParameters["limit"]?.toIntOrNull(),
                    offset = call.request.queryParameters["offset"]?.toIntOrNull(),
                )
                call.respond(page)
            }
            get("/{id}") {
                val user = gateway.getUsuario(call.requiredPath("id"))
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Usuario no encontrado."))
                call.respond(AdminUserResponse(user))
            }
            // Ficha completa: perfil, rendimiento, estado competitivo, racha,
            // economía, suscripciones, OposiTester y debilidades por etiqueta.
            get("/{id}/detalle") {
                val detail = gateway.getUsuarioDetalle(call.requiredPath("id"))
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Usuario no encontrado."))
                call.respond(AdminUserDetailResponse(detail))
            }
            patch("/{id}") {
                val saved = gateway.updateUsuario(call.requiredPath("id"), call.receive())
                call.respond(AdminUserResponse(saved))
            }
            post("/{id}/roles") {
                @kotlinx.serialization.Serializable data class RolesBody(val roleIds: List<String>)
                val body = call.receive<RolesBody>()
                // Cuerpo Y path se validan como UUID. No es cosmético, y el
                // motivo histórico importa: `mapSupabaseDomainError` buscaba sus
                // códigos como subcadena DENTRO del cuerpo que devuelve Supabase,
                // así que cualquier cadena del cliente que
                // Postgres echara de vuelta en «invalid input syntax for type uuid»
                // se traducía a su error de dominio. Un
                // `POST /usuarios/ROLE_SYNC_LAST_SUPERADMIN/roles` devolvía un 409
                // con el mensaje de la guarda sin que la guarda llegara a
                // ejecutarse: el 409 dejaba de ser prueba de que la invariante
                // saltó. Validar sólo el cuerpo tapaba la mitad del vector, y la
                // primera versión de este comentario afirmaba que estaba cerrado.
                //
                // Hoy el vector está cerrado por DOS capas independientes: esta
                // validación, y un `mapSupabaseDomainError` que sólo traduce
                // respuestas >= 400 y exige que el código ENCABECE el campo
                // `message`. Se mantienen las dos a propósito: que el 409 siga
                // significando «la invariante saltó» no debe depender de una sola.
                // `AdminV1UserIdValidationOrderTest` fija que no sale ni una
                // petición cuando el identificador no es un UUID.
                gateway.syncRoles(
                    call.requiredPath("id"),
                    body.roleIds.map { it.validatedUuid("roleIds") },
                )
                call.respond(OkDto())
            }
            post("/{id}/tickets") {
                val body = call.receive<GrantTicketsBody>()
                val idempotencyKey = body.idempotencyKey ?: call.request.headers["Idempotency-Key"]
                val saldo = gateway.grantTickets(call.requiredPath("id"), body.cantidad, body.motivo, idempotencyKey = idempotencyKey)
                call.respond(GrantTicketsResponse(saldo))
            }
        }
        route("/reportes") {
            get { call.respond(AdminReportesResponse(gateway.listReportes(call.request.queryParameters["estado"]))) }
            get("/{id}") { call.respond(gateway.getReporte(call.requiredPath("id"))) }
            post("/{id}/resolver") {
                val actorId = actorIdFromPrincipalName(call.principal<UserIdPrincipal>()?.name)
                gateway.resolverReporte(call.requiredPath("id"), call.receive(), actorId)
                call.respond(OkDto())
            }
        }
        route("/roles") {
            get {
                call.respond(AdminRolesV1Response(gateway.listRoles()))
            }
        }
        route("/tickets") {
            get { call.respond(gateway.listTicketWallets()) }
            // Resumen agregado de la economía de OT.
            get("/resumen") { call.respond(AdminTicketsOverviewResponse(gateway.getTicketsOverview())) }
            // Concesión administrativa. Pasa por el RPC atómico e idempotente;
            // esta ruta nunca escribe un saldo por su cuenta.
            post("/conceder") {
                call.respond(
                    HttpStatusCode.Created,
                    AdminGrantTicketsResponse(gateway.grantTicketsAdmin(call.receive())),
                )
            }
            get("/{usuarioId}/transacciones") {
                call.respond(gateway.listTicketTransactions(call.requiredPath("usuarioId")))
            }
            post("/{usuarioId}/transacciones") {
                call.respond(HttpStatusCode.Created, gateway.appendTicketAdjustment(call.requiredPath("usuarioId"), call.receive()))
            }
        }
        route("/soporte") {
            get { call.respond(gateway.listSupportConversations()) }
            route("/conversaciones") {
                get { call.respond(gateway.listSupportConversations()) }
                get("/{id}/mensajes") {
                    call.respond(gateway.listSupportMessages(call.requiredPath("id")))
                }
                post("/{id}/mensajes") {
                    call.respond(HttpStatusCode.Created, gateway.sendSupportMessage(call.requiredPath("id"), call.receive()))
                }
            }
        }
        route("/accesos") {
            get { call.respond(gateway.listExamAccesses()) }
            post { call.respond(HttpStatusCode.Created, gateway.upsertExamAccess(call.receive())) }
        }
        route("/moderacion") {
            get { call.respond(AdminReportesResponse(gateway.listReportes())) }
            get("/reportes") { call.respond(AdminReportesResponse(gateway.listReportes())) }
            get("/actions") { call.respond(gateway.listModerationActions()) }
            get("/acciones") { call.respond(gateway.listModerationActions()) }
            post("/actions") {
                val req = call.receive<WarnUserRequest>()
                req.targetUserId.validatedUuid("targetUserId")
                gateway.warnUser(req.targetUserId, req.actionType, req.reason)
                call.respond(HttpStatusCode.Created, OkDto())
            }
            get("/hilos") {
                val params = call.request.queryParameters
                val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                val cursor = params["cursor"]?.takeIf { it.isNotBlank() }
                val reportedOnly = params["reportados"] == "true"
                val categoriaId = params["categoriaId"]?.takeIf { it.isNotBlank() }?.also { it.validatedUuid("categoriaId") }
                call.respond(gateway.listForoHilos(limit = limit, cursor = cursor, reportedOnly = reportedOnly, categoriaId = categoriaId))
            }
            get("/hilos/{id}") {
                val hilo = gateway.getForoHilo(call.requiredPath("id"))
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Hilo no encontrado."))
                call.respond(hilo)
            }
            // `hiloId` va ANTES de `&order` y `&limit` en la consulta que compone
            // `getForoHiloRespuestas`, así que un `#` no estrecha la lectura: la
            // deja SIN cota. `comentarios_oposired` es una tabla de alto volumen
            // y el `limit` es lo único que la acota. No corrompe nada (es un GET)
            // pero es amplificación de lectura a una petición de distancia.
            get("/hilos/{id}/respuestas") {
                val hiloId = call.requiredPath("id").validatedUuid("id")
                val params = call.request.queryParameters
                val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                val cursor = params["cursor"]?.takeIf { it.isNotBlank() }
                call.respond(gateway.getForoHiloRespuestas(hiloId, limit = limit, cursor = cursor))
            }
        }
        get("/settings") { call.respond(gateway.settings()) }
        get("/ajustes") { call.respond(gateway.settings()) }
        route("/etiquetas") {
            get { call.respond(AdminEtiquetasResponse(gateway.listEtiquetas())) }
            post {
                val saved = gateway.createEtiqueta(call.receive())
                call.respond(HttpStatusCode.Created, AdminEtiquetaResponse(saved))
            }
            patch("/{id}") {
                val saved = gateway.updateEtiqueta(call.requiredPath("id"), call.receive())
                call.respond(AdminEtiquetaResponse(saved))
            }
            delete("/{id}") {
                gateway.deleteEtiqueta(call.requiredPath("id"))
                call.respond(OkDto())
            }
        }
        // F-10/F-06: mismas reglas que `/especialidades`. Gateway en `withUpstreamIo`
        // y `{id}` validado antes de componer `?id=eq.$id`: un `#` en el DELETE
        // borraba sin filtro la tabla entera de comunidades.
        route("/comunidades-autonomas") {
            get { call.respond(AdminComunidadesResponse(withUpstreamIo { gateway.listComunidades() })) }
            post {
                val body = call.receive<AdminComunidadDto>()
                val saved = withUpstreamIo { gateway.createComunidad(body) }
                call.respond(HttpStatusCode.Created, AdminComunidadResponse(saved))
            }
            patch("/{id}") {
                val id = call.requiredPath("id").validatedUuid("id")
                val body = call.receive<AdminComunidadDto>()
                val saved = withUpstreamIo { gateway.updateComunidad(id, body) }
                call.respond(AdminComunidadResponse(saved))
            }
            delete("/{id}") {
                val id = call.requiredPath("id").validatedUuid("id")
                withUpstreamIo { gateway.deleteComunidad(id) }
                call.respond(OkDto())
            }
        }
        // Estado agregado de los servicios. El panel consume esto, nunca logs.
        get("/estado-sistema") {
            call.respond(AdminSystemStatusResponse(withUpstreamIo { gateway.getSystemStatus() }))
        }
        get("/dashboard") {
            call.respond(withUpstreamIo { gateway.getDashboardMetrics() })
        }
        // Catálogo comercial data-driven: planes, packs de OT y sus precios.
        // Es lo que consume `/v1/comercial/catalogo` en OposiBot, así que editar
        // aquí cambia la app sin desplegar nada.
        route("/comercial") {
            route("/productos") {
                get { call.respond(AdminProductosComercialesResponse(gateway.listProductosComerciales())) }
                post { call.respond(AdminProductoComercialResponse(gateway.upsertProductoComercial(call.receive()))) }
                route("/{productoId}/tarifas") {
                    get {
                        call.respond(AdminTarifasProductoResponse(gateway.listTarifas(call.requiredPath("productoId"))))
                    }
                    post {
                        call.respond(
                            AdminTarifaProductoResponse(
                                gateway.upsertTarifa(call.requiredPath("productoId"), call.receive()),
                            ),
                        )
                    }
                }
            }
        }
        // Planes de suscripción y sus beneficios. Los precios NO están aquí:
        // viven en tarifas_producto_comercial, por periodo y fase.
        route("/planes") {
            get { call.respond(AdminPlansResponse(gateway.listPlanes())) }
            post { call.respond(AdminPlanResponse(gateway.upsertPlan(call.receive()))) }
            route("/{planId}/beneficios") {
                get {
                    call.respond(
                        AdminPlanBenefitsResponse(gateway.listPlanBenefits(call.requiredPath("planId"))),
                    )
                }
                post {
                    call.respond(
                        AdminPlanBenefitResponse(
                            gateway.upsertPlanBenefit(call.requiredPath("planId"), call.receive()),
                        ),
                    )
                }
            }
        }
        // OposiMarket administrable. Los precios y las reglas viven aquí como
        // datos: cambiarlos no debe requerir un despliegue.
        route("/market") {
            route("/packs") {
                get { call.respond(AdminTicketPacksResponse(gateway.listTicketPacks())) }
                post { call.respond(AdminTicketPackResponse(gateway.upsertTicketPack(call.receive()))) }
            }
            route("/servicios") {
                get { call.respond(AdminServicesResponse(gateway.listServices())) }
                post { call.respond(AdminServiceResponse(gateway.upsertService(call.receive()))) }
            }
            route("/promociones") {
                get { call.respond(AdminPromotionsResponse(gateway.listPromotions())) }
                post { call.respond(AdminPromotionResponse(gateway.upsertPromotion(call.receive()))) }
            }
            route("/popups") {
                get { call.respond(AdminPopupRulesResponse(gateway.listPopupRules())) }
                post { call.respond(AdminPopupRuleResponse(gateway.upsertPopupRule(call.receive()))) }
            }
        }
        route("/tienda") {
            route("/articulos") {
                get { call.respond(AdminArticulosTiendaResponse(gateway.listArticulosTienda())) }
                post { call.respond(AdminArticuloTiendaResponse(gateway.upsertArticuloTienda(call.receive()))) }
            }
            route("/ofertas") {
                get { call.respond(AdminOfertasTiendaResponse(gateway.listOfertasTienda())) }
                post { call.respond(AdminOfertaTiendaResponse(gateway.upsertOfertaTienda(call.receive()))) }
            }
            route("/recursos-temporales") {
                get { call.respond(AdminRecursosTemporalesTiendaResponse(gateway.listRecursosTemporalesTienda())) }
                post { call.respond(AdminRecursoTemporalTiendaResponse(gateway.upsertRecursoTemporalTienda(call.receive()))) }
            }
            route("/rafagas") {
                get { call.respond(AdminCampanasRafagaResponse(gateway.listCampanasRafaga())) }
                post { call.respond(AdminCampanaRafagaResponse(gateway.upsertCampanaRafaga(call.receive()))) }
            }
        }
    }
}

// ---------- Respuestas API (camelCase, contrato con el cliente) ----------

@Serializable
data class AdminEspecialidadesResponse(val data: List<AdminEspecialidadDto>)

@Serializable
data class AdminEspecialidadResponse(val data: AdminEspecialidadDto)

@Serializable
data class AdminEspecialidadCapacidadesResponse(val data: AdminEspecialidadCapacidadesDto)

@Serializable
data class AdminUserDetailResponse(val data: AdminUserDetailDto)

@Serializable
data class AdminProductosComercialesResponse(val data: List<AdminProductoComercialDto>)

@Serializable
data class AdminProductoComercialResponse(val data: AdminProductoComercialDto)

@Serializable
data class AdminTarifasProductoResponse(val data: List<AdminTarifaProductoDto>)

@Serializable
data class AdminTarifaProductoResponse(val data: AdminTarifaProductoDto)

@Serializable
data class AdminExamenesResponse(val data: List<AdminExamenDto>)

@Serializable
data class AdminExamenResponse(val data: AdminExamenDto)

@Serializable
data class AdminArticulosTiendaResponse(val data: List<AdminArticuloTiendaDto>)
@Serializable
data class AdminArticuloTiendaResponse(val data: AdminArticuloTiendaDto)
@Serializable
data class AdminOfertasTiendaResponse(val data: List<AdminOfertaTiendaDto>)
@Serializable
data class AdminOfertaTiendaResponse(val data: AdminOfertaTiendaDto)
@Serializable
data class AdminRecursosTemporalesTiendaResponse(val data: List<AdminRecursoTemporalTiendaDto>)
@Serializable
data class AdminRecursoTemporalTiendaResponse(val data: AdminRecursoTemporalTiendaDto)
@Serializable
data class AdminCampanasRafagaResponse(val data: List<AdminCampanaRafagaDto>)
@Serializable
data class AdminCampanaRafagaResponse(val data: AdminCampanaRafagaDto)

@Serializable
data class AdminArticuloTiendaDto(
    val id: Int? = null,
    val nombre: String = "",
    @SerialName("tipo_articulo") val tipoArticulo: String = "",
    @SerialName("precio_tickets") val precioTickets: Int = 0,
    val activo: Boolean = true,
)

@Serializable
data class AdminOfertaTiendaDto(
    val id: Int? = null,
    @SerialName("articulo_tienda_id") val articuloTiendaId: Int = 0,
    val titulo: String = "",
    @SerialName("discount_percent") val discountPercent: Int? = null,
    @SerialName("special_price_tickets") val specialPriceTickets: Int? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val status: String = "draft",
)

@Serializable
data class AdminRecursoTemporalTiendaDto(
    val id: Int? = null,
    @SerialName("articulo_tienda_id") val articuloTiendaId: Int = 0,
    @SerialName("recurso_id") val recursoId: Int = 0,
    val titulo: String = "",
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("access_duration_hours") val accessDurationHours: Int = 0,
    val status: String = "draft",
)

@Serializable
data class AdminCampanaRafagaDto(
    val id: Int? = null,
    val titulo: String = "",
    val descripcion: String = "",
    val tipo: String = "daily_streak",
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val multiplier: Double = 1.0,
    @SerialName("reward_tickets") val rewardTickets: Int = 0,
    @SerialName("linked_exam_id") val linkedExamId: Int? = null,
    @SerialName("linked_store_item_id") val linkedStoreItemId: Int? = null,
    val status: String = "draft",
)

// Especialidad: las columnas reales ya son palabras simples => DTO == fila Supabase.
@Serializable
data class AdminEspecialidadDto(
    val id: String = "",
    val codigo: String = "",
    val nombre: String,
    val slug: String = "",
    val descripcion: String = "",
    val activa: Boolean = true,
    val posicion: Int = 0,
)

/** `encodeDefaults` para que un `false` o un `0` explícitos no se caigan del cuerpo. */
private val especialidadPatchJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Cuerpo del PATCH de una especialidad, sin pisar lo que el cliente no edita.
 *
 * El panel no tiene campo `codigo` —el modelo de dominio `Specialty` tampoco lo
 * lleva—, así que la peticion llega con `codigo = ""`, el default del DTO. Con
 * `encodeDefaults = true` ese `""` viajaba a PostgREST y **sobrescribía el código
 * real en cada edición**; en los datos vivos ya hay una especialidad con el código
 * en blanco por este camino.
 *
 * Un `codigo` vacío significa «este cliente no gestiona el código», no «ponlo en
 * blanco», así que se omite la clave y PostgREST deja la columna intacta. Si algún
 * cliente sí manda uno, se escribe con normalidad.
 *
 * Se serializa y luego se filtra, en vez de construir el objeto campo a campo, para
 * que añadir una columna al DTO no la deje fuera del PATCH en silencio.
 */
internal fun especialidadPatchPayload(row: AdminEspecialidadDto): String {
    val encoded = especialidadPatchJson
        .encodeToJsonElement(AdminEspecialidadDto.serializer(), row)
        .jsonObject
    val safe = if (row.codigo.isBlank()) encoded.filterKeys { it != "codigo" } else encoded
    return JsonObject(safe).toString()
}

// Examen: API en camelCase; la fila Supabase (snake_case) es AdminExamenRow.
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class AdminExamenDto(
    val id: String = "",
    val codigoExamen: String = "",
    val especialidadId: String,
    val comunidadId: String? = null,
    val titulo: String,
    val descripcion: String = "",
    @EncodeDefault val tipoExamen: String = "rafaga",
    @EncodeDefault val tipoAcceso: String = "gratuito",
    val anio: Int? = null,
    @EncodeDefault val esOficial: Boolean = false,
    @EncodeDefault val activo: Boolean = true,
    val publicadoEn: String? = null,
    @EncodeDefault val precioTickets: Int = 0,
    /**
     * Número de preguntas ACTIVAS vinculadas al examen. **Sólo lectura.**
     *
     * La mantiene la base (M1, `exams_total_preguntas_maintained`): triggers al
     * vincular, desvincular o archivar preguntas, 0 al crear y una guarda que
     * neutraliza cualquier otra escritura. Este backend nunca la escribe —ni el
     * POST ni el PATCH la llevan, se mande lo que se mande en el cuerpo (C-05)—, y
     * por eso la cifra servida es un dato y no una estimación.
     */
    val totalPreguntas: Int? = null,
    val duracionMinutos: Int? = null,
    /**
     * URL FIRMADA y efímera (1 h) de la portada. Se calcula en cada lectura a partir
     * de [portadaStoragePath]; nunca se persiste ni se acepta en escritura (K5). Puede
     * venir `null` con una ruta guardada si Storage no pudo firmarla en ese momento.
     */
    val portadaUrl: String? = null,
    val portadaStoragePath: String? = null,
    val portadaMimeType: String? = null,
    /**
     * Metadatos de sólo lectura. La columna ya existe en `examenes` — el listado
     * ordena por `creado_en.desc` — simplemente no se exponía en este DTO. Nunca
     * se envían en escritura: `encodeDefaults=false` (comportamiento por defecto
     * de kotlinx.serialization en este proyecto) omite un valor igual a su
     * default, así que dejarlos en null en un `Exam` de escritura los omite del
     * JSON en vez de mandar `null` y borrar la fecha real.
     */
    val creadoEn: String? = null,
    val actualizadoEn: String? = null,
)

/**
 * Contrato del PATCH de examen (C-01, K-C01): **sólo se escribe lo que viene en el
 * cuerpo.** Clave ausente = columna intacta. Única lista blanca, clave de la API →
 * columna: lo que no está aquí no se escribe nunca por PATCH.
 *
 * ### Por qué «ausente = intacta» para TODAS las claves
 *
 * El PATCH anterior decodificaba el cuerpo a [AdminExamenDto] y escribía la fila
 * entera, así que una clave ausente se escribía con el DEFAULT del DTO:
 * `codigo_examen = ''`, `es_oficial = false`, `tipo_examen = 'rafaga'`,
 * `tipo_acceso = 'gratuito'`, `precio_tickets = 0`. El escritorio funcionaba sólo
 * porque omitía los defaults y el servidor los reescribía; el guardado de ráfaga
 * del móvil, que no conoce el código, lo vaciaba y chocaba con 409. Ahora el
 * cliente manda sus claves explícitas (K-C01a/b) y el servidor no inventa ninguna.
 *
 * ### Lo que nunca escribe un PATCH (E-1, C-05, portada)
 *
 * - **Visibilidad** (`activo`, `publicadoEn`): sólo `publicar`, `despublicar` y
 *   `archivar`. Guardar no es publicar: antes un cuerpo sin `activo` decodificaba
 *   al default `true` y desarchivaba el examen en silencio.
 * - **`totalPreguntas`**: la mantiene la base (M1).
 * - **Portada** (`portadaUrl`, `portadaStoragePath`, `portadaMimeType`): su ruta.
 * - Metadatos (`id`, `creadoEn`, `actualizadoEn`) y cualquier clave desconocida.
 */
private val PATCHABLE_EXAMEN_COLUMNS: Map<String, String> = linkedMapOf(
    "codigoExamen" to "codigo_examen",
    "especialidadId" to "especialidad_id",
    "comunidadId" to "comunidad_id",
    "titulo" to "titulo",
    "descripcion" to "descripcion",
    "tipoExamen" to "tipo_examen",
    "tipoAcceso" to "tipo_acceso",
    "anio" to "anio",
    "esOficial" to "es_oficial",
    "precioTickets" to "precio_tickets",
    "duracionMinutos" to "duracion_minutos",
)

/**
 * Las únicas claves que aceptan `null`, que significa «borrar» (merge-patch, RFC
 * 7396): volver a Nacional, dejar el año o la duración en blanco. En cualquier otra
 * clave admitida un `null` es un 400: no hay columna NOT NULL que se pueda vaciar
 * por accidente.
 */
private val CLEARABLE_EXAMEN_KEYS: Set<String> = setOf("comunidadId", "anio", "duracionMinutos")

/** Mismo 400 y mismo mensaje que daba `call.receive<AdminExamenDto>()`, sin nombres de campo. */
private fun badExamBody(): Nothing = throw BadRequestException("El cuerpo de la petición no es válido.")

private fun JsonPrimitive.textOrBad(): String = if (isString) content else badExamBody()

/**
 * El parche que se manda a PostgREST: sólo las claves presentes, validadas y
 * normalizadas. Un objeto vacío significa «no hay nada que escribir».
 *
 * La validación ocurre ENTERA antes de la primera petición: un 400/422 de aquí no
 * deja ninguna escritura a medias.
 */
internal fun examenPatchFrom(raw: JsonObject): JsonObject = buildJsonObject {
    PATCHABLE_EXAMEN_COLUMNS.forEach { (key, column) ->
        val value = raw[key] ?: return@forEach
        if (value is JsonNull) {
            if (key in CLEARABLE_EXAMEN_KEYS) put(column, JsonNull) else badExamBody()
            return@forEach
        }
        val primitive = value as? JsonPrimitive ?: badExamBody()
        val normalized: JsonPrimitive = when (key) {
            "codigoExamen" -> JsonPrimitive(primitive.textOrBad().trim().ifBlank { throw examCodeRequiredException() })
            "especialidadId", "comunidadId" -> JsonPrimitive(primitive.textOrBad().validatedUuid(key))
            "titulo" -> JsonPrimitive(
                primitive.textOrBad().trim().ifBlank { throw BadRequestException("El examen necesita titulo.") },
            )
            "descripcion" -> JsonPrimitive(primitive.textOrBad())
            "tipoExamen" -> JsonPrimitive(canonicalExamTypeOrReject(primitive.textOrBad()))
            "tipoAcceso" -> JsonPrimitive(canonicalExamAccessTypeOrReject(primitive.textOrBad()))
            "anio", "precioTickets", "duracionMinutos" ->
                JsonPrimitive(primitive.takeUnless { it.isString }?.intOrNull ?: badExamBody())
            "esOficial" -> JsonPrimitive(primitive.takeUnless { it.isString }?.booleanOrNull ?: badExamBody())
            else -> badExamBody()
        }
        put(column, normalized)
    }
}

/**
 * Las SEIS tablas de datos de usuario que apuntan a `examenes`, con la etiqueta
 * humana que las nombra en el mensaje del 409.
 *
 * La columna es `examen_id` en las seis. Verificado en el repositorio para
 * cuatro: `intentos_examen` (`AppV1Routes` filtra por ella),
 * `contenido_desbloqueado_usuario` (`listDesbloqueos`), y
 * `feedback_examen` + `eventos_conversion_premium`
 * (`supabase/migrations/20260803101711_add_missing_fk_indexes.sql`, que indexa
 * `examen_id` en las dos). En `respuestas_intento` y `abandonos_examen` el
 * NOMBRE de la columna no está verificado: las dos aparecen en el esquema vivo
 * como referencias a `examenes` y toda la base usa `examen_id`, pero esa
 * migración no las indexa (ya lo estarían) y ningún fichero del repo las filtra.
 *
 * Si en alguna el nombre fuera otro, PostgREST responde 42703 a la lectura, la
 * guarda falla CERRADA —[examDeleteGuardUnavailableException]— y no se borra
 * nada. Ése es el modo de fallo elegido: un nombre de columna equivocado impide
 * borrar, nunca borra de más.
 *
 * Las etiquetas son lo que lee el administrador y por eso no llevan nombres de
 * tabla: van derechas al mensaje del 409.
 */
private val EXAMEN_USER_DATA_TABLES: List<Pair<String, String>> = listOf(
    "intentos_examen" to "intentos de usuarios",
    "respuestas_intento" to "respuestas contestadas",
    "abandonos_examen" to "abandonos registrados",
    "feedback_examen" to "opiniones de usuarios",
    "contenido_desbloqueado_usuario" to "compras o desbloqueos de contenido",
    "eventos_conversion_premium" to "eventos de conversión a premium",
)

// Vocabulario canónico de `examenes.tipo_examen` — lo único que se ESCRIBE.
// NO se declara aquí: `CANONICAL_EXAM_TYPES` ya existe como `internal` en
// `AdminMarket.kt:181` y `AdminAnalyticsSupportTest` fija su contenido. Una
// segunda copia en este paquete no sólo no compila (choca por resolución de
// nombre aunque sea `private`): dejaría dos listas del mismo vocabulario que
// pueden divergir en silencio, que es exactamente el defecto que esta
// validación existe para impedir. Se reutiliza la que hay.

/**
 * Alias legacy que se ACEPTAN en la entrada y se normalizan. Nunca se escriben.
 *
 * Quien manda `guiado` no está equivocado, está desactualizado: se le acepta el
 * valor y se guarda el canónico. Lo que no se hace jamás es devolverlo a la
 * columna, que dejaría la base con dos vocabularios a la vez.
 */
private val LEGACY_EXAM_TYPE_ALIASES: Map<String, String> = mapOf("guiado" to "razonado")

/** Vocabulario canónico de `examenes.tipo_acceso`. Conjunto cerrado. */
private val CANONICAL_EXAM_ACCESS_TYPES: Set<String> = setOf("gratuito", "premium")

/**
 * Normaliza `tipo_examen` al vocabulario canónico o RECHAZA con 422.
 *
 * ### Por qué el servidor y no sólo el ViewModel
 *
 * Hasta ahora la única validación de este vocabulario vivía en el cliente de
 * escritorio (`ExamsViewModel`), y el backend pasaba el valor crudo a la
 * columna. Una validación que vive sólo en el único cliente que existe hoy no
 * es una garantía: es una costumbre.
 *
 * ### Un desconocido se RECHAZA; jamás se convierte en `rafaga`
 *
 * El repositorio del cliente tiene una función que hace justo lo contrario
 * (`canonicalExamType`, con su `else -> RAFAGA`). Eso es correcto **sólo** en el
 * camino de importación, donde no hay un valor previo que perder. Aquí hay uno,
 * y reetiquetar en silencio un examen como Ráfaga destruye un dato sin decirlo.
 *
 * El mensaje nombra los tres valores válidos y **no** el valor recibido: nada
 * del cliente vuelve en un mensaje de error (EXAM-004).
 *
 * ### Precondición de esquema — `LIVE_READ_REQUIRED`
 *
 * Escribir el canónico `razonado` depende de que el CHECK vivo de
 * `examenes.tipo_examen` ya admita el vocabulario nuevo. El fixture de dry-run
 * declara el ANTERIOR (`rafaga`,`guiado`,`inmersivo`). Si MAIN conserva ése, un
 * examen Razonado se rechaza con `23514` — que desde este cambio llega como 422
 * legible y no como 500 opaco, pero se rechaza igual. La migración de
 * vocabulario es prerrequisito, y NO se arregla escribiendo `guiado`.
 */
private fun canonicalExamTypeOrReject(raw: String): String {
    val normalized = raw.trim().lowercase()
    LEGACY_EXAM_TYPE_ALIASES[normalized]?.let { return it }
    if (normalized in CANONICAL_EXAM_TYPES) return normalized
    throw BackendDomainException(
        "EXAM_TYPE_UNKNOWN",
        HttpStatusCode.UnprocessableEntity,
        "El tipo de examen no es válido. Los valores admitidos son: rafaga, razonado, inmersivo.",
    )
}

/**
 * Vocabulario canónico de `contenido_desbloqueado_usuario.motivo` (CHECK vivo en
 * MAIN). Conjunto cerrado: es lo único que la base admite.
 */
private val CANONICAL_UNLOCK_REASONS: Set<String> =
    setOf("premium", "compra", "regalo", "admin", "promocion")

/**
 * Normaliza el tipo de un acceso manual al `motivo` canónico o RECHAZA con 422.
 *
 * Recorta y pasa a minúsculas. `manual` (alias del cliente antiguo, que lo mandaba
 * por defecto) y el vacío equivalen a `admin`: quien concede a mano es la
 * administración. Cualquier otro valor se rechaza; jamás se escribe un motivo
 * fuera del conjunto. El mensaje nombra los válidos y no devuelve la entrada.
 */
private fun canonicalUnlockReasonOrReject(raw: String): String {
    val normalized = raw.trim().lowercase()
    if (normalized.isEmpty() || normalized == "manual") return "admin"
    if (normalized in CANONICAL_UNLOCK_REASONS) return normalized
    throw BackendDomainException(
        "EXAM_ACCESS_GRANT_TYPE_UNKNOWN",
        HttpStatusCode.UnprocessableEntity,
        "Tipo de acceso no válido. Valores admitidos: premium, compra, regalo, admin, promocion.",
    )
}

/**
 * Normaliza `tipo_acceso` al conjunto cerrado o RECHAZA con 422.
 *
 * No hay alias legacy aquí. Y la combinación tipo↔acceso (`rafaga⇒gratuito`,
 * `inmersivo⇒premium`) **no se pre-impone**: sólo aparece en un CHECK del
 * fixture cuyo estado vivo es desconocido, y convertir una restricción
 * posiblemente inexistente en una restricción real desde el backend la volvería
 * invisible para quien administre la base. Si la base la rechaza, se traduce su
 * `23514`; no se adelanta.
 */
private fun canonicalExamAccessTypeOrReject(raw: String): String {
    val normalized = raw.trim().lowercase()
    if (normalized in CANONICAL_EXAM_ACCESS_TYPES) return normalized
    throw BackendDomainException(
        "EXAM_ACCESS_TYPE_UNKNOWN",
        HttpStatusCode.UnprocessableEntity,
        "El tipo de acceso no es válido. Los valores admitidos son: gratuito, premium.",
    )
}

/**
 * Aplica el vocabulario canónico a los dos campos que lo tienen.
 *
 * Se hace sobre el DTO y no sobre la fila para que el valor que se escribe y el
 * que se devuelve al cliente sean el mismo: si se normalizara sólo al serializar,
 * la respuesta diría `guiado` y la columna `razonado`.
 */
private fun AdminExamenDto.withCanonicalVocabulary(): AdminExamenDto = copy(
    tipoExamen = canonicalExamTypeOrReject(tipoExamen),
    tipoAcceso = canonicalExamAccessTypeOrReject(tipoAcceso),
)

@Serializable
private data class AdminExamenRow(
    val id: String? = null,
    @SerialName("codigo_examen") val codigoExamen: String = "",
    @SerialName("especialidad_id") val especialidadId: String,
    @SerialName("comunidad_id") val comunidadId: String? = null,
    val titulo: String,
    // `examenes.descripcion` es NULL-able en la base: un examen creado fuera de la API (SQL, otra
    // herramienta) rompía el listado y la importación entera con un 502 «upstream contract failure».
    val descripcion: String? = null,
    @SerialName("tipo_examen") val tipoExamen: String = "rafaga",
    @SerialName("tipo_acceso") val tipoAcceso: String = "gratuito",
    val anio: Int? = null,
    @SerialName("es_oficial") val esOficial: Boolean = false,
    val activo: Boolean = true,
    @SerialName("publicado_en") val publicadoEn: String? = null,
    @SerialName("precio_tickets") val precioTickets: Int? = null,
    // Sólo lectura: la mantiene la base (M1). En escritura queda a null y
    // `sbJson` (explicitNulls=false) la omite. Ver `AdminExamenDto.totalPreguntas`.
    @SerialName("total_preguntas") val totalPreguntas: Int? = null,
    @SerialName("duracion_minutos") val duracionMinutos: Int? = null,
    // Sin `portada_url`: la columna no existe (M6 sólo añade ruta y tipo) y la URL
    // se firma en cada lectura (K5). Ver `AdminExamenDto.portadaUrl`.
    @SerialName("portada_storage_path") val portadaStoragePath: String? = null,
    @SerialName("portada_mime_type") val portadaMimeType: String? = null,
    @SerialName("creado_en") val creadoEn: String? = null,
    @SerialName("actualizado_en") val actualizadoEn: String? = null,
)

/** Lo mínimo para subir o quitar una portada: la fila existe y qué objeto tenía. */
@Serializable
private data class AdminExamenPortadaRow(
    val id: String,
    @SerialName("portada_storage_path") val portadaStoragePath: String? = null,
)

/**
 * Formatos de portada (K5): tipo MIME → extensión del objeto. Son los tres que
 * promete la pantalla (`EXAM_COVER_FORMATS`) y un subconjunto de los que admite el
 * CHECK de M6. Cualquier otro tipo es 422 `EXAM_COVER_TYPE_INVALID` antes de leer
 * un solo byte.
 */
private val EXAM_COVER_EXTENSIONS: Map<String, String> =
    mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp")

/** Vida de una URL firmada de portada: una hora, de sobra para una sesión de edición. */
private const val EXAM_COVER_URL_TTL_SECONDS = 3600

/** Tamaño máximo de una portada. El bucket `exam-resources` admite 10 MB. */
private const val EXAM_COVER_MAX_BYTES = 10 * 1024 * 1024

/** Fallos de firma y de limpieza de portadas: al log con el `request_id` del MDC. */
private val ExamCoverLogger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("OC-KTOR-EXAM-COVER")

private fun AdminExamenRow.toDto() = AdminExamenDto(
    id = id.orEmpty(),
    codigoExamen = codigoExamen,
    especialidadId = especialidadId,
    comunidadId = comunidadId,
    titulo = titulo,
    descripcion = descripcion.orEmpty(),
    tipoExamen = tipoExamen,
    tipoAcceso = tipoAcceso,
    anio = anio,
    esOficial = esOficial,
    activo = activo,
    publicadoEn = publicadoEn,
    precioTickets = precioTickets ?: 0,
    // Sin coerción: un examen con 0 preguntas se lee como 0. El `else 1` anterior
    // convertía todo examen vacío en "1 pregunta" y hacía imposible detectar los
    // exámenes sin preguntas desde el panel.
    totalPreguntas = totalPreguntas,
    duracionMinutos = duracionMinutos,
    // Nunca de la fila: la firma el gateway en la lectura (`withSignedCovers`).
    portadaUrl = null,
    portadaStoragePath = portadaStoragePath,
    portadaMimeType = portadaMimeType,
    creadoEn = creadoEn,
    actualizadoEn = actualizadoEn,
)

private fun AdminExamenDto.toRow(forcedId: String? = null) = AdminExamenRow(
    id = (forcedId ?: id).ifBlank { UUID.randomUUID().toString() },
    codigoExamen = codigoExamen,
    especialidadId = especialidadId,
    comunidadId = comunidadId,
    titulo = titulo,
    descripcion = descripcion,
    tipoExamen = tipoExamen,
    tipoAcceso = tipoAcceso,
    anio = anio,
    esOficial = esOficial,
    activo = activo,
    publicadoEn = publicadoEn,
    precioTickets = precioTickets,
    // `totalPreguntas` NO se copia (K2): la fila de escritura nunca lleva la
    // columna. Queda a `null` y `sbJson` (`explicitNulls = false`) la omite. La
    // mantiene la base (M1).
    duracionMinutos = duracionMinutos,
)

// ----- Preguntas (pregunta + opciones + solucion + recursos) -----

@Serializable
data class AdminPreguntasResponse(val data: List<AdminPreguntaDto>)

/** Una página del banco (KQ0-3): las filas, el total exacto y la ventana pedida. */
@Serializable
data class AdminPreguntasPageResponse(
    val data: List<AdminPreguntaDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

/** Fila de `preguntas_examen` con su pregunta embebida (`preguntas!inner`), para el listado por examen. */
@Serializable
private data class AdminPreguntaExamenListRow(
    val posicion: Int,
    val anulada: Boolean = false,
    @SerialName("motivo_anulacion") val motivoAnulacion: String? = null,
    val preguntas: AdminPreguntaRow,
)

/** Las columnas del listado del banco: las que pinta la fila, ni una más. */
private const val PREGUNTA_LIST_COLUMNS = "id,codigo_pregunta,tipo_pregunta,titulo,fuente_texto,dificultad,activa"

@Serializable
data class AdminPreguntaResponse(val data: AdminPreguntaDto)

@Serializable
data class AdminRecursoResponse(val data: AdminRecursoDto)

@Serializable
data class AdminRecursosResponse(val data: List<AdminRecursoDto>)

@Serializable
data class AdminOpcionDto(
    val id: String = "",
    val letra: String,
    val texto: String,
    val posicion: Int = 0,
    val esCorrecta: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminPreguntaDto(
    val id: String = "",
    /**
     * Sólo lectura (KQ1). Lo genera la base (`PREG-000001`, Q1) y es inmutable: se
     * ignora en escritura, ni el alta ni el PATCH lo mandan a Supabase.
     */
    val codigoPregunta: String = "",
    @EncodeDefault val tipoPregunta: String = "rafaga",
    val titulo: String,
    /**
     * En la RESPUESTA siempre es texto (vacío si la columna es NULL). En el PATCH,
     * `null` o ausente significa **«no lo cambies»** (QN-17): el editor de Exámenes
     * no edita la fuente y no puede vaciarla ni reescribirla con el enunciado. Quien
     * sí la edita (el banco de Preguntas) la manda siempre, vacía incluida.
     */
    val fuenteTexto: String? = null,
    /** Vacío = sin dificultad. Nunca un `"media"` inventado (KQ0-2, QN-24). */
    val dificultad: String = "",
    @EncodeDefault val activa: Boolean = true,
    val razonamiento: String = "",
    val opciones: List<AdminOpcionDto> = emptyList(),
    /**
     * Sólo lectura (KQ0-8). Los recursos se gestionan ÚNICAMENTE por sus rutas
     * (`/preguntas/{id}/recursos…`): el PATCH de pregunta ignora esta lista, así
     * que ni la regla «sólo inmersiva» ni un identificador de recurso pueden
     * colarse por el cuerpo de la pregunta.
     */
    val recursos: List<AdminRecursoDto> = emptyList(),
    // Asociacion opcional a un examen al crear.
    val examenId: String? = null,
    val posicion: Int? = null,
    /**
     * Sólo lectura (M10), y sólo con [examenId]: la pregunta esta ANULADA por el tribunal en ESE examen.
     * Es propiedad de la relacion pregunta-examen, no de la pregunta: sin `examenId` es siempre `false`.
     * Se ignora en escritura (crear/editar una pregunta nunca la anula).
     */
    @EncodeDefault val anulada: Boolean = false,
    /** Sólo lectura (M10): motivo de la anulacion en [examenId]; `null` si no hay. */
    val motivoAnulacion: String? = null,
)

@Serializable
data class AdminRecursoDto(
    val id: String = "",
    val preguntaId: String = "",
    val tipoRecurso: String,
    val titulo: String = "",
    val descripcion: String = "",
    /**
     * Enlace EXTERNO (`https://…`) de un recurso de tipo `enlace`. Nunca una ruta
     * local del disco del administrador, que es lo que llegaba aquí antes de KQ4 y
     * no existía para nadie más que para él.
     */
    val urlExterna: String? = null,
    val contenidoTexto: String? = null,
    val posicion: Int = 0,
    /** Sólo LECTURA: dónde vive el archivo dentro del bucket privado. No se acepta en escritura. */
    val rutaStorage: String? = null,
    /** Sólo LECTURA: el MIME con el que se subió, para que la pantalla sepa qué reproductor usar. */
    val mimeType: String? = null,
    /**
     * Sólo LECTURA: URL FIRMADA y con caducidad (una hora) para abrir el archivo, o el
     * enlace externo si el recurso es de ese tipo.
     *
     * No se persiste jamás: guardarla haría que el material dejara de verse cuando
     * expira, que es exactamente el defecto que tenía el editor de Exámenes.
     */
    val urlAcceso: String? = null,
)

/**
 * Valida los identificadores que este DTO trae **en el cuerpo** y devuelve una
 * copia con ellos normalizados.
 *
 * Existe porque la validación por path no cubría este camino: el alta recibe
 * `examenId` por el cuerpo y acababa dentro de un filtro de PostgREST sin pasar
 * por ninguna red. Ver el comentario de `patch("/preguntas/{id}")` para el daño.
 *
 * `id` se valida desde KQ2-1: el cliente lo genera y lo reenvía en cada
 * reintento del alta, y viaja como argumento `uuid` de `admin_create_question`.
 * Un valor que no es UUID volvía de Postgres como 22P02 (un 500 redactado)
 * cuando lo honesto es un 400 que dice qué campo está mal.
 *
 * Devuelve una copia en vez de sólo comprobar: así el valor que sigue viaje es
 * la forma canónica de [java.util.UUID], no la cadena del cliente.
 *
 * `recursos[].id` YA NO se valida, y no es un olvido (KQ0-8): el PATCH de pregunta
 * ignora `recursos[]` entero, así que ningún id de esa lista llega a un filtro.
 * Además se VACÍA la lista, para que ningún camino posterior pueda leerla.
 */
internal fun AdminPreguntaDto.withValidatedIdentifiers(): AdminPreguntaDto = copy(
    id = id.takeIf { it.isNotBlank() }?.validatedUuid("id").orEmpty(),
    examenId = examenId?.takeIf { it.isNotBlank() }?.validatedUuid("examenId"),
    recursos = emptyList(),
)

/**
 * Fila de `preguntas` para LEER.
 *
 * `fuente_texto` y `dificultad` son NULL-ables en la base (KQ0-2, QN-12): con
 * `String` y default, un NULL real —el que escriben Q2 o una importación— hacía
 * fallar la decodificación y el listado entero salía como 5xx, porque el `Json`
 * del gateway no coacciona nulos. Nullable aquí y `.orEmpty()` al pasar al DTO.
 */
@Serializable
private data class AdminPreguntaRow(
    val id: String? = null,
    @SerialName("codigo_pregunta") val codigoPregunta: String,
    @SerialName("tipo_pregunta") val tipoPregunta: String = "rafaga",
    val titulo: String,
    @SerialName("fuente_texto") val fuenteTexto: String? = null,
    val dificultad: String? = null,
    val activa: Boolean = true,
)

/**
 * Proyección mínima para conocer el tipo de una pregunta sin traerse la fila.
 *
 * La usa la regla «sólo INMERSIVA admite recursos»: es una lectura de una
 * columna, no un `select=*`.
 */
@Serializable
private data class AdminPreguntaTipoRow(
    @SerialName("tipo_pregunta") val tipoPregunta: String = "",
)

/**
 * El ÚNICO valor de `preguntas.tipo_pregunta` que admite recursos.
 *
 * Es el vocabulario de PREGUNTA (`rafaga` · `razonada` · `inmersiva`), no el de
 * examen (`rafaga` · `razonado` · `inmersivo`). No se aceptan alias: la
 * canonización de entrada de preguntas no está cerrada (el tipo del cuerpo se
 * escribe crudo en el PATCH) y aceptar aquí «inmersivo» abriría la puerta a que
 * la regla dependa de qué vocabulario usó el cliente.
 */
private const val QUESTION_TYPE_WITH_RESOURCES = "inmersiva"

/** Bucket PRIVADO del material de preguntas (Q3). Privado: se lee con URL firmada, nunca pública. */
private const val QUESTION_RESOURCES_BUCKET = "recursos-pregunta"

/** 50 MB. Una clase de audio larga cabe; un vídeo de media hora no, y tiene que fallar ANTES de subirse. */
private const val QUESTION_RESOURCE_MAX_BYTES = 50 * 1024 * 1024

/** Una hora: suficiente para abrir el material desde la consola y lo bastante corto para que un enlace filtrado caduque. */
private const val QUESTION_RESOURCE_URL_TTL_SECONDS = 3600

/**
 * `tipo_recurso` → (MIME admitido → extensión del objeto).
 *
 * Es la intersección del CHECK de `tipo_recurso` con los MIME que acepta el bucket, y
 * decide DOS cosas de una vez: si el archivo se admite y con qué extensión se guarda.
 * La extensión no sale nunca del nombre del fichero del cliente — el CHECK de ruta de
 * Q3 sólo admite unas pocas, y un `.php` en el nombre no puede acabar en el bucket.
 */
private val QUESTION_RESOURCE_MIME: Map<String, Map<String, String>> = mapOf(
    "imagen" to mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/gif" to "gif"),
    "audio" to mapOf("audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/wav" to "wav"),
    "video" to mapOf("video/mp4" to "mp4"),
    "pdf" to mapOf("application/pdf" to "pdf"),
)

/** Los tipos que EXIGEN archivo: por JSON no se pueden crear (no habría nada que reproducir). */
private val QUESTION_RESOURCE_FILE_TYPES: Set<String> = QUESTION_RESOURCE_MIME.keys

/**
 * Comprime el archivo de un recurso ANTES de que cruce la red hacia Storage.
 *
 * ### Lo que esta función se prohíbe a sí misma
 *
 * **No puede cambiar QUÉ se admite.** [QUESTION_RESOURCE_MIME] es la política de
 * admisión y sigue decidiéndola [AdminV1Gateway.addRecursoArchivo]; aquí se
 * comprueba dos veces contra ese mismo mapa:
 *
 * 1. Si el MIME de ENTRADA no está admitido, no se toca nada. Sin esto, un
 *    `video/quicktime` —hoy un 422 limpio— se transcodificaría a `video/mp4` y
 *    empezaría a colarse por la puerta de atrás. Comprimir no es una política de
 *    admisión y no debe comportarse como una.
 * 2. Si el MIME de SALIDA no está admitido para ese `tipo_recurso`, gana el
 *    original. Así la extensión que deriva el gateway (`.jpg`, `.mp4`…) existe
 *    siempre y corresponde a los bytes que se suben.
 *
 * Cualquier fallo de la compresión sale por aquí como "el original", nunca como
 * una excepción: el material del administrador no se pierde porque la
 * optimización tenga un mal día.
 */
private fun optimizedQuestionResource(tipoRecurso: String, mimeType: String, bytes: ByteArray): CompressedMedia {
    val tipo = tipoRecurso.trim().lowercase()
    val mime = mimeType.substringBefore(';').trim().lowercase()
    val intacto = CompressedMedia(bytes, mime, MediaCompressionOutcome.SKIPPED_UNSUPPORTED)
    val admitidos = QUESTION_RESOURCE_MIME[tipo] ?: return intacto
    if (!admitidos.containsKey(mime)) return intacto
    // El gateway construye la ruta como `<recurso>.<ext>` con la extensión que
    // sale de este mismo mapa, así que aquí SÍ se puede cambiar de formato.
    val optimized = MediaCompression.compress(bytes, mime, allowFormatChange = true)
    if (!optimized.applied || !admitidos.containsKey(optimized.mimeType)) return intacto
    return optimized
}

@Serializable
private data class AdminOpcionRow(
    val id: String? = null,
    @SerialName("pregunta_id") val preguntaId: String,
    val letra: String,
    @SerialName("texto_opcion") val textoOpcion: String,
    val posicion: Int,
)

@Serializable
private data class AdminSolucionRow(
    val id: String? = null,
    @SerialName("pregunta_id") val preguntaId: String,
    @SerialName("opcion_correcta_id") val opcionCorrectaId: String,
    val razonamiento: String,
)

/**
 * Fila de `preguntas_examen` **sólo para LEER** (K3-2/K3-3): quien escribe en esta
 * tabla son las RPC de M4, que mantienen `posicion` y `numero_en_examen` bajo el
 * cerrojo del examen. `numero_en_examen` es NOT NULL en la base y aquí es nullable
 * únicamente para poder decodificar consultas que no lo seleccionan.
 */
@Serializable
private data class AdminPreguntaExamenRow(
    @SerialName("examen_id") val examenId: String,
    @SerialName("pregunta_id") val preguntaId: String,
    val posicion: Int,
    @SerialName("numero_en_examen") val numeroEnExamen: Int? = null,
    /** M10: relacion anulada por el tribunal. Default `false` para decodificar selects que no la piden. */
    val anulada: Boolean = false,
    @SerialName("motivo_anulacion") val motivoAnulacion: String? = null,
)

@Serializable
data class AdminPreguntaOrdenRequest(val ids: List<String> = emptyList())

/** Resultado de un alta: la pregunta leída y si esta llamada la creó o era un reintento (KQ2-1). */
data class AdminPreguntaCreada(val pregunta: AdminPreguntaDto, val creada: Boolean)

/** Cuerpo de `POST /preguntas/{id}/examenes`: la única puerta para vincular (D-03/D-04). `posicion` ausente = al final. */
@Serializable
data class AdminVincularPreguntaRequest(val examenId: String, val posicion: Int? = null)

/**
 * Un examen al que está vinculada una pregunta (K3-4, lectura inversa M:N).
 *
 * [estado] usa la misma derivación que el catálogo (`borrador|publicado|archivado`) y
 * [totalPreguntas] es el recuento mantenido por la base (M1): con los dos, el diálogo de
 * archivar o quitar puede avisar ANTES de que un examen publicado se quede vacío (KQ5, Q4).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminPreguntaExamenVinculoDto(
    val examenId: String,
    val codigoExamen: String = "",
    val titulo: String = "",
    val tipoExamen: String = "",
    val estado: String = "borrador",
    val posicion: Int = 0,
    val totalPreguntas: Int = 0,
    /** M10: en ESTE examen la pregunta esta anulada por el tribunal (no puntua). */
    @EncodeDefault val anulada: Boolean = false,
    val motivoAnulacion: String? = null,
)

@Serializable
data class AdminPreguntaExamenesResponse(val data: List<AdminPreguntaExamenVinculoDto>)

@Serializable
private data class AdminPreguntaExamenVinculoRow(
    val posicion: Int = 0,
    val anulada: Boolean = false,
    @SerialName("motivo_anulacion") val motivoAnulacion: String? = null,
    val examenes: AdminPreguntaExamenEmbed? = null,
)

@Serializable
private data class AdminPreguntaExamenEmbed(
    val id: String,
    @SerialName("codigo_examen") val codigoExamen: String? = null,
    val titulo: String? = null,
    @SerialName("tipo_examen") val tipoExamen: String? = null,
    val activo: Boolean = true,
    @SerialName("publicado_en") val publicadoEn: String? = null,
    @SerialName("total_preguntas") val totalPreguntas: Int? = null,
)

private fun AdminPreguntaExamenVinculoRow.toDto(): AdminPreguntaExamenVinculoDto? {
    val examen = examenes ?: return null
    return AdminPreguntaExamenVinculoDto(
        examenId = examen.id,
        codigoExamen = examen.codigoExamen.orEmpty(),
        titulo = examen.titulo.orEmpty(),
        tipoExamen = examen.tipoExamen.orEmpty(),
        estado = when {
            !examen.activo -> "archivado"
            examen.publicadoEn != null -> "publicado"
            else -> "borrador"
        },
        posicion = posicion,
        totalPreguntas = examen.totalPreguntas ?: 0,
        anulada = anulada,
        motivoAnulacion = motivoAnulacion,
    )
}

/**
 * Clave del cuerpo HTTP del PATCH de pregunta → clave de `p_cambios` de
 * `admin_update_question` (KQ2-2). Todo lo demás del cuerpo se IGNORA: `id`,
 * `codigoPregunta` (Q1), cualquier `especialidadId` sobrante (la pregunta no la tiene, M8), `recursos` (KQ0-8) y
 * `examenId`/`posicion` (D-03: el PATCH ya no vincula; la única puerta es
 * `POST /preguntas/{id}/examenes`).
 */
private val PREGUNTA_PATCH_KEYS: Map<String, String> = linkedMapOf(
    "titulo" to "titulo",
    "tipoPregunta" to "tipo_pregunta",
    "fuenteTexto" to "fuente_texto",
    "dificultad" to "dificultad",
    "activa" to "activa",
    "razonamiento" to "razonamiento",
)

/**
 * `p_cambios` a partir del cuerpo crudo: sólo las claves PRESENTES, con su valor tal
 * cual (un `null` presente también viaja: «vacía la fuente»). La validación de tipos
 * la hace la RPC (`QUESTION_PATCH_INVALID`, `QUESTION_STATEMENT_REQUIRED`…), dentro de
 * la misma transacción que escribe.
 *
 * `opciones`: `[]` es «no toco las opciones» (compatibilidad con los clientes que la
 * mandaban vacía); una lista con elementos viaja con `letra`, `texto` y `es_correcta`;
 * otra cosa es un 400 antes de llamar a nadie.
 */
internal fun preguntaCambiosFrom(raw: JsonObject): JsonObject = buildJsonObject {
    PREGUNTA_PATCH_KEYS.forEach { (http, sql) -> raw[http]?.let { put(sql, it) } }
    when (val opciones = raw["opciones"]) {
        null, JsonNull -> Unit
        is JsonArray -> if (opciones.isNotEmpty()) {
            put(
                "opciones",
                JsonArray(
                    opciones.map { opcion ->
                        val obj = opcion as? JsonObject
                            ?: throw IllegalArgumentException("Cada opción tiene que ser un objeto.")
                        buildJsonObject {
                            put("letra", obj["letra"] ?: JsonNull)
                            put("texto", obj["texto"] ?: JsonNull)
                            put("es_correcta", obj["esCorrecta"] ?: JsonNull)
                        }
                    },
                ),
            )
        }
        else -> throw IllegalArgumentException("opciones tiene que ser una lista.")
    }
}

/**
 * Fila de `recursos_pregunta`. `titulo` y `descripcion` son NULL-ables en la base
 * (KQ0-2): nullable aquí, `.orEmpty()` en el DTO. `bucket_storage` y `ruta_storage`
 * se leen ya para que la subida real (KQ4) no tenga que tocar la forma de la fila.
 */
@Serializable
private data class AdminRecursoRow(
    val id: String? = null,
    @SerialName("pregunta_id") val preguntaId: String,
    @SerialName("codigo_recurso") val codigoRecurso: String,
    @SerialName("tipo_recurso") val tipoRecurso: String,
    val titulo: String? = null,
    val descripcion: String? = null,
    @SerialName("url_externa") val urlExterna: String? = null,
    @SerialName("contenido_texto") val contenidoTexto: String? = null,
    @SerialName("bucket_storage") val bucketStorage: String? = null,
    @SerialName("ruta_storage") val rutaStorage: String? = null,
    val posicion: Int = 0,
    val activo: Boolean = true,
    val metadatos: JsonObject = JsonObject(emptyMap()),
)

/**
 * Lo que un PATCH de recurso escribe, que NO es la fila entera.
 *
 * Existe porque `sbJson` tiene `encodeDefaults = true`: serializar
 * [AdminRecursoRow] para editar mandaba siempre `activo`, `codigo_recurso`,
 * `pregunta_id` y `metadatos`, ninguno de los cuales el cliente está editando, y
 * el primero de ellos resucitaba recursos archivados. Un tipo aparte es la única
 * forma de que «lo que se escribe» no dependa de acordarse de pasar el valor
 * correcto a cada campo del row. Ver `updateRecurso`.
 */
@Serializable
private data class AdminRecursoPatch(
    val titulo: String = "",
    val descripcion: String = "",
    @SerialName("contenido_texto") val contenidoTexto: String? = null,
    val posicion: Int = 0,
)

private fun AdminRecursoRow.toDto() = AdminRecursoDto(
    id = id.orEmpty(),
    preguntaId = preguntaId,
    tipoRecurso = tipoRecurso,
    titulo = titulo.orEmpty(),
    descripcion = descripcion.orEmpty(),
    urlExterna = urlExterna,
    contenidoTexto = contenidoTexto,
    posicion = posicion,
)

// ----- Importaciones (lote + staging -> importar a tablas definitivas) -----

@Serializable
data class AdminLotesResponse(val data: List<AdminLoteDto>)

@Serializable
data class AdminLoteResponse(val data: AdminLoteDto)

@Serializable
data class AdminStagingResponse(val data: AdminStagingItemDto)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminStagingItemDto(
    val id: String = "",
    val posicion: Int = 0,
    val titulo: String = "",
    val opcionA: String = "",
    val opcionB: String = "",
    val opcionC: String = "",
    val opcionD: String = "",
    val respuestaCorrecta: String = "",
    val razonamiento: String = "",
    val fuenteTexto: String = "",
    val etiquetaTexto: String = "",
    val bloqueOriginal: String = "",
    @EncodeDefault val estadoParseo: String = "ok",
    val errorParseo: String? = null,
    val preguntaId: String? = null,
    /**
     * La pregunta YA EXISTENTE con la que el revisor decide vincular esta fila en vez
     * de crear una nueva (IM2, KI2). `null` = crear.
     *
     * Sólo tiene sentido con `estadoParseo = "aprobada"`: una fila omitida no vincula
     * nada. Si la pregunta elegida se borra, IM2 pasa la fila a `advertencia` y lo
     * explica en `errorParseo`, en vez de dejar un puntero a la nada.
     */
    val vincularPreguntaId: String? = null,
    /** Lectura: la coincidencia detectada para esta fila, o `null` si no hay (KI3-2). */
    val duplicado: AdminImportDuplicadoDto? = null,
    /**
     * La pregunta fue ANULADA por el tribunal en el examen de este lote (M10). Es propiedad de la
     * RELACION pregunta-examen: al publicar se escribe en `preguntas_examen`, nunca en `preguntas`.
     * Ortogonal a [estadoParseo]: una fila anulada y bien formada es `ok`.
     *
     * El PATCH `/importaciones/staging/{id}` REEMPLAZA la fila entera como el resto de campos: `false`
     * (o ausente) desanula.
     */
    @EncodeDefault val anulada: Boolean = false,
    /**
     * Motivo de la anulacion. Sólo con [anulada] = `true`; se recorta, vacio = `null` y >500 es un 400.
     * En el PATCH un `null` (o ausente) BORRA el motivo guardado.
     */
    val motivoAnulacion: String? = null,
)

/**
 * Una coincidencia detectada para una fila de staging (`admin_import_lote_duplicados`).
 *
 * [origen] distingue las dos cosas que el revisor resuelve de forma distinta:
 * `banco` = la pregunta ya existe publicada (se puede vincular), `lote` = el propio
 * PDF trae la misma pregunta dos veces (hay que omitir una).
 */
@Serializable
data class AdminImportDuplicadoDto(
    val origen: String,
    val preguntaId: String? = null,
    val codigoPregunta: String = "",
    val stagingOriginalId: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminLoteDto(
    val id: String = "",
    val examenId: String,
    val nombreArchivo: String = "",
    @EncodeDefault val tipoExamen: String = "rafaga",
    @EncodeDefault val estado: String = "parseado",
    val totalDetectadas: Int = 0,
    @EncodeDefault val totalImportadas: Int = 0,
    val staging: List<AdminStagingItemDto> = emptyList(),
    /**
     * `false` cuando la detección de duplicados NO se pudo ejecutar (falta IM3).
     *
     * La pantalla lo dice tal cual. La alternativa —responder la lista sin
     * `duplicado` en ninguna fila— se leería como «no hay duplicados», que es una
     * afirmación que este backend no puede hacer, y el revisor publicaría repetidas.
     */
    @EncodeDefault val duplicadosComprobados: Boolean = true,
    /**
     * Cuántas filas hay en cada estado de revision (M10 / historial). `null` = NO se pudo
     * calcular: nunca un 0/0 inventado. Ver [AdminLoteResumenDto].
     */
    val resumen: AdminLoteResumenDto? = null,
)

/**
 * Recuento de las filas de staging de un lote por estado de parseo.
 *
 * - [listas] = `ok` + `aprobada` (publicables); [conError] = `error`; [advertencias] =
 *   `advertencia`; [omitidas] = `omitida`; [importadas] = `importada`.
 * - Las anuladas NO forman categoria: la anulacion es ortogonal al estado de parseo.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminLoteResumenDto(
    @EncodeDefault val listas: Int = 0,
    @EncodeDefault val conError: Int = 0,
    @EncodeDefault val advertencias: Int = 0,
    @EncodeDefault val omitidas: Int = 0,
    @EncodeDefault val importadas: Int = 0,
)

/** Cuerpo (opcional) de `POST /importaciones/preguntas/{loteId}/importar`. */
@Serializable
data class AdminImportarLoteRequest(val confirmarExamenPublicado: Boolean = false)

/**
 * El recuento de lo que hizo la publicación, separado del lote.
 *
 * [ejecutado] `false` = el lote ya estaba publicado y esta llamada no escribió nada
 * (un reintento tras un timeout, o dos clics). Sin este campo, el cliente no puede
 * distinguir «he publicado 120» de «ya estaban», y las dos respuestas tienen los
 * mismos totales.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminImportResultadoDto(
    val ejecutado: Boolean,
    val nuevas: Int,
    val vinculadas: Int,
    val omitidas: Int,
    val totalImportadas: Int,
    /** Cuantas de las preguntas publicadas quedaron ANULADAS en el examen (M10). */
    @EncodeDefault val anuladas: Int = 0,
)

/** `data` conserva la forma de [AdminLoteResponse]: un cliente que sólo lea `data` no se rompe. */
@Serializable
data class AdminLoteImportResponse(val data: AdminLoteDto, val resultado: AdminImportResultadoDto)

@Serializable
data class CrearLoteRequest(
    val examenId: String,
    val nombreArchivo: String = "",
    val tipoExamen: String = "rafaga",
    // El parseo PDF->preguntas es responsabilidad externa; aqui se reciben ya parseadas.
    val preguntas: List<AdminStagingItemDto> = emptyList(),
)

/**
 * El vocabulario de `preguntas_parseadas_staging.estado_parseo` (verificado en MAIN
 * el 2026-09-17, más los dos valores que añade IM1).
 *
 * `advertencia` lo escribe el parser (una Ráfaga con explicación o fuente, que se
 * descartarán al publicar) y lo escribe IM2 cuando la pregunta elegida para
 * vincular desaparece. `aprobada` y `omitida` son las dos decisiones del revisor y
 * **todavía no las admite el CHECK vivo**: llegan aquí, viajan, y la base las
 * rechaza con un 23514 que [mapSupabaseDomainError] traduce a
 * `IMPORT_REVIEW_STATE_UNAVAILABLE`. Se deja así a propósito: el día que IM1 esté
 * aplicada funcionan sin tocar una línea, y mientras tanto el administrador lee
 * «revisar todavía no está disponible» en vez de un error de restricción.
 *
 * `importada` NO está: lo escribe únicamente la RPC de publicación (KI0-4). Que un
 * cliente pudiera marcar una fila como publicada sin publicarla convertía la
 * revisión en decorado.
 */
private val STAGING_PARSE_STATES: Set<String> = setOf("ok", "advertencia", "error", "aprobada", "omitida")

/**
 * Valida contra [STAGING_PARSE_STATES] y devuelve el valor normalizado.
 *
 * `importada` tiene mensaje propio: es el único valor que un cliente puede intentar
 * de buena fe y que aquí es siempre un error de concepto.
 */
private fun requireKnownStagingState(value: String): String {
    val normalized = value.trim().lowercase()
    require(normalized != "importada") {
        "El estado «importada» sólo lo escribe la publicación del lote."
    }
    require(normalized in STAGING_PARSE_STATES) {
        "estado_parseo debe ser uno de: ${STAGING_PARSE_STATES.joinToString(", ")}."
    }
    return normalized
}

/**
 * Las cuatro letras que el contrato de Ráfaga exige en una pregunta.
 *
 * La tabla de staging tiene exactamente cuatro columnas de opción y el único
 * importador que existe hoy es el de Ráfaga, cuyo parser marca el bloque en
 * cuanto le falta una de las cuatro. Si algún día entra un tipo con otro juego
 * de opciones, éste es el sitio donde se decide, y tendrá que decidirse por
 * TIPO y no por columna.
 */
private val RAFAGA_OPTION_LETTERS: List<String> = listOf("A", "B", "C", "D")

/**
 * Un PDF que este importador RECHAZA, con un mensaje escrito para el administrador (F-05).
 *
 * Es el único error que `POST /importaciones/preguntas/pdf` traduce a 422. La ruta
 * capturaba `Throwable` y respondía 422 con `error.message`, y eso publicaba lo que
 * fuera: el cuerpo de PostgREST de un `IllegalStateException` del transporte
 * (`"code"`, `"details":"Failing row contains (…)"`, `"hint"`), el texto de un fallo
 * de red con la ruta de Supabase, o las tripas de PDFBox. Ahora un rechazo del PDF
 * lleva su propio tipo y todo lo demás llega a StatusPages, que lo redacta con
 * referencia.
 *
 * `RuntimeException` a secas a propósito: si extendiera `IllegalArgumentException`
 * o `IllegalStateException`, un olvido en otra ruta la publicaría o la redactaría
 * por accidente en vez de fallar donde se ve.
 */
internal class PdfImportRejectedException(message: String) : RuntimeException(message)

/** Lotes por peticion del resumen: 50 uuid en la URL son ~1.9 KB, muy por debajo de cualquier limite. */
private const val LOTE_SUMMARY_IDS_PER_REQUEST = 50

/** Tamano de pagina del resumen; el fin de la lectura lo decide `Content-Range`, no este numero. */
private const val LOTE_SUMMARY_PAGE_SIZE = 1000

/** Solo las dos columnas que hacen falta para contar filas de staging por estado. */
@Serializable
private data class StagingEstadoRow(
    @SerialName("lote_id") val loteId: String,
    @SerialName("estado_parseo") val estadoParseo: String = "",
)

/** Detalle técnico de la importación: al log (con el `request_id` del MDC), nunca al cliente. */
private val PdfImportLogger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("OC-KTOR-IMPORT")

/** Fallos al escribir `auditoria_log` (F-12b): al log con el `request_id` del MDC. */
private val AuditLogger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("OC-KTOR-AUDIT")

/**
 * ¿Este fallo dice que la RPC de escritura de preguntas NO EXISTE en la base?
 *
 * PGRST202 es el código de PostgREST para «no encuentro la función». Es un fallo
 * de INFRAESTRUCTURA —falta una migración— y no de contenido, y confundirlos es
 * el defecto que se cierra abajo en `importarLote`.
 *
 * ### Se mira `code`, no el mensaje — la lección de E-4, aplicada aquí
 *
 * Esto era `message.contains("PGRST202")`, y el `message` de
 * `SupabaseRequestTransport.failOnError` arrastra **300 caracteres del cuerpo de
 * PostgREST**. En un `23514` ese cuerpo lleva el `Failing row contains (…)` de
 * Postgres con el contenido de la fila dentro, y el contenido de la fila lo
 * escribe el administrador con `updateStaging`: una fila cuyo título contuviera
 * `PGRST202` y violara un CHECK abortaba el lote entero diciendo «no está
 * aprovisionada». Es el mismo oráculo que `mapRaisedDomainCode` ya había cerrado
 * en `BackendErrors`, con la misma frase: el `code` lo pone el servidor.
 *
 * [supabaseErrorCode] devuelve la PRIMERA aparición de `"code"` en el texto, y
 * PostgREST emite sus claves en orden alfabético (`code`, `details`, `hint`,
 * `message`), así que el eco del cliente —que vive en `details` o en `message`—
 * llega siempre después del código real y no puede ganarle.
 *
 * Degradación declarada: si algún día PostgREST cambiara ese formato, esto deja
 * de reconocer la falta de la RPC y el fallo vuelve a salir como «fallo por
 * fila», que es el comportamiento anterior — ruidoso, pero no peor.
 */
private fun Throwable.saysWriteRpcIsMissing(): Boolean =
    supabaseErrorCode(message.orEmpty()) == "PGRST202"

@Serializable
private data class LoteRow(
    val id: String? = null,
    @SerialName("examen_id") val examenId: String,
    @SerialName("nombre_archivo_original") val nombreArchivoOriginal: String,
    @SerialName("tipo_examen") val tipoExamen: String,
    val estado: String,
    @SerialName("total_detectadas") val totalDetectadas: Int,
    @SerialName("total_importadas") val totalImportadas: Int = 0,
    /** Quién subió el PDF (D-11). Nunca se escribía, así que ningún lote tenía autor. */
    @SerialName("subido_por") val subidoPor: String? = null,
    val metadatos: JsonObject = JsonObject(emptyMap()),
)

@Serializable
private data class StagingRow(
    val id: String? = null,
    @SerialName("lote_id") val loteId: String,
    @SerialName("pregunta_id") val preguntaId: String? = null,
    val posicion: Int,
    @SerialName("bloque_original") val bloqueOriginal: String,
    val titulo: String? = null,
    @SerialName("opcion_a") val opcionA: String? = null,
    @SerialName("opcion_b") val opcionB: String? = null,
    @SerialName("opcion_c") val opcionC: String? = null,
    @SerialName("opcion_d") val opcionD: String? = null,
    @SerialName("respuesta_correcta") val respuestaCorrecta: String? = null,
    val razonamiento: String? = null,
    @SerialName("fuente_texto") val fuenteTexto: String? = null,
    @SerialName("etiqueta_texto") val etiquetaTexto: String? = null,
    @SerialName("estado_parseo") val estadoParseo: String,
    @SerialName("error_parseo") val errorParseo: String? = null,
    @SerialName("vincular_pregunta_id") val vincularPreguntaId: String? = null,
    /** M10: la anulacion vive en la relacion; en staging es lo que el revisor ve y corrige antes de publicar. */
    val anulada: Boolean = false,
    @SerialName("motivo_anulacion") val motivoAnulacion: String? = null,
    val metadatos: JsonObject = JsonObject(emptyMap()),
)

/**
 * Lo que una revisión escribe en una fila de staging, serializado con claves
 * EXPLÍCITAS ([toJson]) y no con `sbJson`.
 *
 * El motivo es un null que tiene que viajar: al aprobar u omitir una fila hay que
 * BORRAR el diagnóstico del parser (`error_parseo = null`), y `sbJson`
 * (`explicitNulls = false`) omite los nulos, así que el diagnóstico se quedaba
 * pegado para siempre a una fila ya corregida. Lo mismo con `vincular_pregunta_id`:
 * un `null` es «deshaz el vínculo elegido», no «no lo toques».
 *
 * Por eso cada campo es un [JsonElement] opcional: `null` en Kotlin = clave ausente
 * = no se toca; `JsonNull` = se escribe NULL.
 */
private data class StagingPatch(
    val titulo: String? = null,
    val opcionA: String? = null,
    val opcionB: String? = null,
    val opcionC: String? = null,
    val opcionD: String? = null,
    val respuestaCorrecta: JsonElement? = null,
    val razonamiento: String? = null,
    val fuenteTexto: String? = null,
    val etiquetaTexto: String? = null,
    val estadoParseo: String? = null,
    val errorParseo: JsonElement? = null,
    val vincularPreguntaId: JsonElement? = null,
    /** `null` = no se toca. El PATCH de revision lo manda siempre (la fila se reemplaza entera). */
    val anulada: Boolean? = null,
    /** `JsonNull` = borra el motivo; `null` = no se toca. */
    val motivoAnulacion: JsonElement? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        titulo?.let { put("titulo", JsonPrimitive(it)) }
        opcionA?.let { put("opcion_a", JsonPrimitive(it)) }
        opcionB?.let { put("opcion_b", JsonPrimitive(it)) }
        opcionC?.let { put("opcion_c", JsonPrimitive(it)) }
        opcionD?.let { put("opcion_d", JsonPrimitive(it)) }
        respuestaCorrecta?.let { put("respuesta_correcta", it) }
        razonamiento?.let { put("razonamiento", JsonPrimitive(it)) }
        fuenteTexto?.let { put("fuente_texto", JsonPrimitive(it)) }
        etiquetaTexto?.let { put("etiqueta_texto", JsonPrimitive(it)) }
        estadoParseo?.let { put("estado_parseo", JsonPrimitive(it)) }
        errorParseo?.let { put("error_parseo", it) }
        vincularPreguntaId?.let { put("vincular_pregunta_id", it) }
        anulada?.let { put("anulada", JsonPrimitive(it)) }
        motivoAnulacion?.let { put("motivo_anulacion", it) }
    }
}

/**
 * La anulacion de una fila de staging, saneada: motivo recortado, vacio = `null`, sólo con `anulada`
 * y de 500 caracteres como maximo (el CHECK de `preguntas_parseadas_staging`). Lanza
 * [IllegalArgumentException] (400) con un mensaje que el administrador puede leer.
 */
private fun normalizedAnnulmentReason(anulada: Boolean, motivo: String?): String? {
    val limpio = motivo?.trim()?.takeIf { it.isNotEmpty() }
    require(limpio == null || anulada) { "El motivo de anulacion solo se admite si la pregunta esta anulada." }
    require(limpio == null || limpio.length <= MAX_ANNULMENT_REASON_LENGTH) {
        "El motivo de anulacion no puede superar los $MAX_ANNULMENT_REASON_LENGTH caracteres."
    }
    return limpio
}

private fun StagingRow.toDto() = AdminStagingItemDto(
    id = id.orEmpty(),
    posicion = posicion,
    titulo = titulo.orEmpty(),
    opcionA = opcionA.orEmpty(),
    opcionB = opcionB.orEmpty(),
    opcionC = opcionC.orEmpty(),
    opcionD = opcionD.orEmpty(),
    respuestaCorrecta = respuestaCorrecta.orEmpty(),
    razonamiento = razonamiento.orEmpty(),
    fuenteTexto = fuenteTexto.orEmpty(),
    etiquetaTexto = etiquetaTexto.orEmpty(),
    bloqueOriginal = bloqueOriginal,
    estadoParseo = estadoParseo,
    errorParseo = errorParseo,
    preguntaId = preguntaId,
    vincularPreguntaId = vincularPreguntaId,
    anulada = anulada,
    motivoAnulacion = motivoAnulacion,
)

// ----- Feedback de preguntas + Auditoria -----

/** [nextCursor] es un cursor opaco keyset (creado_en+id), null si no hay más
 * páginas. Nuevo respecto al endpoint original (que devolvía todas las filas
 * sin límite) — ver [AdminV1Gateway.listFeedback]. */
@Serializable
data class AdminFeedbacksResponse(val data: List<AdminFeedbackDto>, val nextCursor: String? = null)

@Serializable
data class AdminFeedbackResponse(val data: AdminFeedbackDto)

@Serializable
data class AdminFeedbackDto(
    val id: String = "",
    val usuarioId: String? = null,
    /** Nombre real resuelto vía perfiles_usuario; "" si no se pudo resolver
     * (nunca se rellena con el UUID). */
    val autorNombre: String = "",
    val preguntaId: String = "",
    val intentoId: String? = null,
    val tipoFeedback: String = "",
    val descripcion: String = "",
    val estado: String = "pendiente",
    val creadoEn: String = "",
)

@Serializable
data class ResolverFeedbackRequest(
    val estado: String = "resuelto", // resuelto | descartado | revisado
    val nota: String = "",
)

@Serializable
data class AdminFeedbackDetailResponse(val data: AdminFeedbackDetailDto)

/** Detalle real de una incidencia de OposiTester (Screen 07): mismos campos
 * que [AdminFeedbackDto] más [pregunta], contexto de la pregunta asociada
 * resuelto sin N+1 (una fila) — null si la pregunta ya no existe. No incluye
 * examen: `preguntas` no tiene relación 1:1 con examen (ver
 * `preguntas_examen`, M:N), así que ese dato queda fuera hasta que haya una
 * forma real y no ambigua de resolverlo. */
@Serializable
data class AdminFeedbackDetailDto(
    val id: String = "",
    val usuarioId: String? = null,
    val autorNombre: String = "",
    val preguntaId: String = "",
    val intentoId: String? = null,
    val tipoFeedback: String = "",
    val descripcion: String = "",
    val estado: String = "pendiente",
    val creadoEn: String = "",
    val pregunta: AdminFeedbackPreguntaContextDto? = null,
)

/** [especialidadNombre] va SIEMPRE null desde M8: la pregunta no tiene especialidad propia (es del examen).
 * Se conserva como campo reservado del contrato; nunca se rellena con el uuid crudo. */
@Serializable
data class AdminFeedbackPreguntaContextDto(
    val codigoPregunta: String,
    val titulo: String,
    val especialidadNombre: String? = null,
)

/** Pura y testeable sin red: separa el ensamblado del DTO de detalle de las
 * llamadas HTTP de [AdminV1Routes.Gateway.getFeedbackDetail]. */
internal fun buildFeedbackDetailDto(
    row: FeedbackRow,
    autorNombre: String,
    pregunta: AdminPreguntaDto?,
    especialidadNombre: String?,
): AdminFeedbackDetailDto = AdminFeedbackDetailDto(
    id = row.id.orEmpty(),
    usuarioId = row.usuarioId,
    autorNombre = autorNombre,
    preguntaId = row.preguntaId,
    intentoId = row.intentoId,
    tipoFeedback = row.tipoFeedback,
    descripcion = row.descripcion.orEmpty(),
    estado = row.estado,
    creadoEn = row.creadoEn.orEmpty(),
    pregunta = pregunta?.let {
        AdminFeedbackPreguntaContextDto(
            codigoPregunta = it.codigoPregunta,
            titulo = it.titulo,
            especialidadNombre = especialidadNombre,
        )
    },
)

@Serializable
data class AdminAuditoriasResponse(val data: List<AdminAuditoriaDto>)

@Serializable
data class AdminAuditoriaDto(
    val id: String = "",
    val usuarioId: String? = null,
    val accion: String = "",
    val tablaAfectada: String? = null,
    val registroId: String? = null,
    val creadoEn: String? = null,
)

// internal (no private): AdminV1FeedbackDetailTest construye filas directamente
// para probar buildFeedbackDetailDto sin red.
@Serializable
internal data class FeedbackRow(
    val id: String? = null,
    @SerialName("usuario_id") val usuarioId: String? = null,
    @SerialName("pregunta_id") val preguntaId: String,
    @SerialName("intento_id") val intentoId: String? = null,
    @SerialName("tipo_feedback") val tipoFeedback: String,
    val descripcion: String? = null,
    val estado: String,
    @SerialName("creado_en") val creadoEn: String? = null,
)

@Serializable
private data class AuditoriaRow(
    val id: String? = null,
    @SerialName("usuario_id") val usuarioId: String? = null,
    val accion: String,
    @SerialName("tabla_afectada") val tablaAfectada: String? = null,
    @SerialName("registro_id") val registroId: String? = null,
    @SerialName("creado_en") val creadoEn: String? = null,
)

private fun FeedbackRow.toDto(autorNombre: String = "") = AdminFeedbackDto(
    id = id.orEmpty(),
    usuarioId = usuarioId,
    autorNombre = autorNombre,
    preguntaId = preguntaId,
    intentoId = intentoId,
    tipoFeedback = tipoFeedback,
    descripcion = descripcion.orEmpty(),
    estado = estado,
    creadoEn = creadoEn.orEmpty(),
)

private fun AuditoriaRow.toDto() = AdminAuditoriaDto(
    id = id.orEmpty(),
    usuarioId = usuarioId,
    accion = accion,
    tablaAfectada = tablaAfectada,
    registroId = registroId,
    creadoEn = creadoEn,
)

// ---------- Usuarios / Roles V1 DTOs (nombres en inglés para compatibilidad con KMP DTO) ----------

@Serializable
data class AdminV1UserDto(
    val id: String,
    val fullName: String = "",
    val email: String = "",
    val roleIds: List<String> = emptyList(),
    val isActive: Boolean = true,
    val createdAt: String = "",
    val ticketsBalance: Int = 0,
)

// Moderación del foro (OposiRed) + tickets manuales
@Serializable
data class AdminReporteDto(
    val id: String,
    val tipoEntidad: String = "",
    val entidadId: String = "",
    val motivo: String = "",
    val descripcion: String = "",
    val estado: String = "",
    val creadoEn: String = "",
    val reportadoPor: String = "",
    val reporterNombre: String = "",
    val revisadoEn: String = "",
    /** Solo poblado en el detalle (getReporte); la lista no lo resuelve por coste. */
    val contentPreview: AdminReporteContentPreviewDto? = null,
)

/** Contenido real reportado, resuelto desde publicaciones_oposired/comentarios_oposired
 * a partir de tipoEntidad+entidadId. Null si la entidad no existe o el tipo no se reconoce
 * (nunca se rellena con datos inventados). */
@Serializable
data class AdminReporteContentPreviewDto(
    val type: String,
    val text: String = "",
    val author: String = "",
    val createdAt: String = "",
    val threadTitle: String? = null,
    val currentState: String = "",
)
@Serializable data class AdminReportesResponse(val data: List<AdminReporteDto>)

/**
 * Listado paginado de hilos reales de OposiRed (publicaciones_oposired) para la
 * pantalla móvil de Foro. [nextCursor] es un cursor opaco keyset (creado_en+id);
 * null significa que no hay más páginas. Nunca pagina con OFFSET.
 */
@Serializable data class AdminForoHilosResponse(val items: List<AdminForoHiloDto>, val nextCursor: String? = null)

/**
 * Un hilo real con lo mínimo para escanear la lista. [respuestas] se resuelve con
 * un count embebido de PostgREST en la MISMA consulta que trae la página (sin
 * N+1). [tieneReportePendiente] indica un reporte PENDIENTE real sobre el propio
 * hilo (reportes de comentarios dentro del hilo no lo activan aquí: ver pestaña
 * "reportados", que sí los resuelve, para no pagar una consulta extra por página
 * solo para un indicador discreto).
 */
@Serializable data class AdminForoHiloDto(
    val id: String,
    val categoriaId: String? = null,
    val categoriaNombre: String = "",
    val titulo: String = "",
    val fragmento: String = "",
    val estado: String = "",
    val fijada: Boolean = false,
    val autorNombre: String = "",
    val creadoEn: String = "",
    val actualizadoEn: String = "",
    val respuestas: Int = 0,
    val tieneReportePendiente: Boolean = false,
)

/**
 * Detalle completo de un hilo (pantalla de detalle móvil). A diferencia de
 * [AdminForoHiloDto] (lista), [contenido] va SIN truncar — la pantalla de
 * detalle debe mostrar el post original completo — y [reportesAsociados] es
 * un conteo real (no solo un booleano): reportes PENDIENTES sobre el propio
 * hilo + reportes PENDIENTES sobre cualquiera de sus comentarios, mismo
 * criterio "pendiente" que [AdminForoHiloDto.tieneReportePendiente] y que
 * Informes, para que los tres sitios nunca discrepen sobre qué cuenta como
 * "reporte asociado".
 */
@Serializable data class AdminForoHiloDetailDto(
    val id: String,
    val categoriaId: String? = null,
    val categoriaNombre: String = "",
    val titulo: String = "",
    val contenido: String = "",
    val estado: String = "",
    val fijada: Boolean = false,
    val autorNombre: String = "",
    val creadoEn: String = "",
    val actualizadoEn: String = "",
    val respuestas: Int = 0,
    val reportesAsociados: Int = 0,
)

/**
 * Página de respuestas (comentarios_oposired) de un hilo, en orden natural de
 * lectura (más antiguas primero). Mismo mecanismo de cursor keyset opaco que
 * [AdminForoHilosResponse], solo que ordenado ascendente en vez de descendente.
 */
@Serializable data class AdminForoRespuestasResponse(val items: List<AdminForoRespuestaDto>, val nextCursor: String? = null)

@Serializable data class AdminForoRespuestaDto(
    val id: String,
    val autorNombre: String = "",
    val contenido: String = "",
    val estado: String = "",
    val creadoEn: String = "",
)

@Serializable data class ResolverReporteBody(val accion: String = "descartar", val notas: String? = null, val ticketsReporter: Int = 0)
@Serializable data class GrantTicketsBody(
    val cantidad: Int,
    val motivo: String = "Ajuste manual",
    val idempotencyKey: String? = null,
)
@Serializable data class GrantTicketsResponse(val saldo: Int)

@Serializable
data class AdminV1RoleDto(
    val id: String,
    val name: String,
    val code: String = "",
    val description: String = "",
    val permissions: List<AdminV1PermissionDto> = emptyList(),
)

@Serializable
data class AdminV1PermissionDto(val key: String, val label: String)

@Serializable data class AdminUserResponse(val data: AdminV1UserDto)
@Serializable data class AdminRolesV1Response(val data: List<AdminV1RoleDto>)
@Serializable data class OkDto(val ok: Boolean = true)
// `RafagaImportResponse`, `RafagaParseErrorDto` y `RafagaImportErrorResponse` se
// retiraron con `POST /rafagas/import` (DEC-IMP-05). Aquella ruta creaba preguntas
// SIN staging ni revisión —saltándose el único punto donde un humano mira lo que va
// a publicarse—, parseaba el PDF en el hilo de la petición y guardaba las etiquetas
// como recurso, que la base rechaza siempre. No tenía consumidor. El flujo canónico
// (subir PDF → staging → revisar → importar) hace lo mismo y sí es revisable.
@Serializable data class WarnUserRequest(
    @SerialName("targetUserId") val targetUserId: String,
    @SerialName("actionType") val actionType: String = "advertencia",
    val reason: String = "",
)

// Etiquetas — esquema real: id, nombre, slug, descripcion, color_hex, activa
@Serializable
data class AdminEtiquetaDto(
    val id: String = "",
    val nombre: String,
    val slug: String = "",
    val descripcion: String? = null,
    val colorHex: String? = null,
    val activa: Boolean = true,
)
@Serializable data class AdminEtiquetasResponse(val data: List<AdminEtiquetaDto>)
@Serializable data class AdminEtiquetaResponse(val data: AdminEtiquetaDto)

// Comunidades autónomas
@Serializable
data class AdminComunidadDto(
    val id: String = "",
    val nombre: String,
    val codigo: String = "",
    val slug: String = "",
    val activa: Boolean = true,
    val posicion: Int = 0,
)
@Serializable data class AdminComunidadesResponse(val data: List<AdminComunidadDto>)
@Serializable data class AdminComunidadResponse(val data: AdminComunidadDto)

// Dashboard
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminDashboardDto(
    val totalSpecialties: Int = 0,
    val totalExams: Int = 0,
    val publishedExams: Int = 0,
    val totalQuestions: Int = 0,
    val lastUpdatedAt: String = "",
    val draftExams: Int = 0,
    @EncodeDefault val totalAttempts: Int = 0,
    @EncodeDefault val activeUsers30d: Int = 0,
    @EncodeDefault val avgScore: Double = 0.0,
    @EncodeDefault val globalErrorRate: Double = 0.0,
    @EncodeDefault val attemptsPerDay: List<DayCountDto> = emptyList(),
    /**
     * Métricas que el backend no ha podido calcular en esta respuesta, por su
     * nombre de campo (ver [DashboardMetricKey]). El cliente debe pintar NO_DATA
     * para cada una en lugar del valor numérico, que en ese caso es sólo relleno
     * de serialización y no un dato real. Vacío = todo lo devuelto es real.
     */
    @EncodeDefault val unavailableMetrics: List<String> = emptyList(),
)

@Serializable
data class DayCountDto(val date: String, val count: Int)

// Rows privados moderación + tickets
@Serializable
private data class ReporteRow(
    val id: String,
    @SerialName("tipo_entidad") val tipoEntidad: String = "",
    @SerialName("entidad_id") val entidadId: String? = null,
    val motivo: String? = null,
    val descripcion: String? = null,
    val estado: String? = null,
    @SerialName("reportado_por") val reportadoPor: String? = null,
    @SerialName("creado_en") val creadoEn: String? = null,
    @SerialName("revisado_en") val revisadoEn: String? = null,
)

@Serializable
private data class AccionModeracionInsert(
    @SerialName("reporte_id") val reporteId: String,
    @SerialName("tipo_accion") val tipoAccion: String,
    val notas: String? = null,
    /** uuid del admin real (JWT sub); null si autenticó con el token estático legacy. */
    @SerialName("moderador_id") val moderadorId: String? = null,
)

@Serializable
private data class ReportePatch(
    val estado: String,
    @SerialName("revisado_en") val revisadoEn: String,
    @SerialName("tickets_otorgados") val ticketsOtorgados: Int,
    @SerialName("recompensa_entregada") val recompensaEntregada: Boolean,
    @SerialName("revisado_por") val revisadoPor: String? = null,
)

// Contenido real de OposiRed (hilos/comentarios), solo lectura para el preview del detalle.
@Serializable
private data class PublicacionOposiredRow(
    val id: String,
    @SerialName("usuario_id") val usuarioId: String? = null,
    val titulo: String? = null,
    val contenido: String? = null,
    val estado: String? = null,
    @SerialName("creado_en") val creadoEn: String? = null,
)

@Serializable
private data class ComentarioOposiredRow(
    val id: String,
    @SerialName("publicacion_id") val publicacionId: String? = null,
    @SerialName("usuario_id") val usuarioId: String? = null,
    val contenido: String? = null,
    val estado: String? = null,
    @SerialName("creado_en") val creadoEn: String? = null,
)

@Serializable
private data class PublicacionTituloRow(val titulo: String? = null)

// Listado paginado de hilos (pantalla móvil de Foro). Fila más rica que
// [PublicacionOposiredRow] (que solo sirve al preview del detalle de informe):
// incluye categoria_id/fijada/actualizado_en y el count embebido de respuestas.
@Serializable
private data class PublicacionOposiredListRow(
    val id: String,
    @SerialName("usuario_id") val usuarioId: String? = null,
    @SerialName("categoria_id") val categoriaId: String? = null,
    val titulo: String? = null,
    val contenido: String? = null,
    val estado: String? = null,
    val fijada: Boolean? = null,
    @SerialName("creado_en") val creadoEn: String? = null,
    @SerialName("actualizado_en") val actualizadoEn: String? = null,
    // Embed de PostgREST vía la FK real comentarios_oposired.publicacion_id:
    // `select=*,comentarios_oposired(count)` trae el conteo en la MISMA consulta
    // que la página de hilos (sin N+1). Sintaxis verificada contra el esquema
    // real (curl de solo lectura); sin filas reales aún para confirmar la forma
    // exacta del JSON con datos, así que se decodifica defensivamente
    // (firstOrNull()?.count ?: 0 en el mapeo, nunca lanza si difiere).
    @SerialName("comentarios_oposired") val comentarios: List<ForoRespuestasCountRow> = emptyList(),
)

@Serializable
private data class ForoRespuestasCountRow(val count: Int = 0)

@Serializable
private data class CategoriaOposiredRow(val id: String, val nombre: String? = null)

@Serializable
private data class CarteraRow(
    @SerialName("usuario_id") val usuarioId: String = "",
    @SerialName("saldo_tickets") val saldoTickets: Int = 0,
)

/** Nivel de liga (`ligas_nivel`): la ventana de cálculo y el mínimo de preguntas son configuración. */
@Serializable
internal data class LigaNivelRow(
    val id: String = "",
    val codigo: String = "",
    val nombre: String = "",
    @SerialName("preguntas_minimas") val preguntasMinimas: Int = 0,
    @SerialName("ventana_calculo") val ventanaCalculo: Int = 0,
    val activa: Boolean = true,
    val posicion: Int = 0,
)

@Serializable
private data class EstadoPremiumRow(
    @SerialName("es_premium") val esPremium: Boolean = false,
    @SerialName("estado_suscripcion") val estadoSuscripcion: String? = null,
    @SerialName("producto_codigo") val productoCodigo: String? = null,
    val proveedor: String? = null,
    @SerialName("renovacion_en") val renovacionEn: String? = null,
    @SerialName("fin_en") val finEn: String? = null,
)

@Serializable
private data class AdminSyncRolesRpc(
    @SerialName("p_usuario_id") val usuarioId: String,
    @SerialName("p_role_ids") val roleIds: List<String>,
)

@Serializable
private data class AdminGrantTicketsRpc(
    @SerialName("p_usuario_id") val usuarioId: String,
    @SerialName("p_cantidad") val cantidad: Int,
    @SerialName("p_descripcion") val descripcion: String,
    @SerialName("p_idempotency_key") val idempotencyKey: String,
    @SerialName("p_tipo") val tipo: String,
)

@Serializable
private data class AdminGrantTicketsResult(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("saldo_resultante") val saldoResultante: Int,
)

/**
 * Argumentos de `public.admin_create_question` (Q2; M8 retiró `p_especialidad_id`: la pregunta
 * no tiene especialidad, así que son 12 parámetros con nombre, los ocho últimos con default).
 *
 * - [preguntaId] es la CLAVE DE IDEMPOTENCIA (KQ2-1, P1 de C): la genera el cliente
 *   UNA vez por alta y la repite en cada reintento. La RPC hace `on conflict (id)
 *   do nothing` y compara el contenido: mismo contenido = `creada:false` sin
 *   escribir; otro contenido = `QUESTION_ID_CONFLICT`. Sin él, un reintento tras una
 *   respuesta perdida creaba la pregunta dos veces.
 * - Sin `p_codigo_pregunta` (KQ1): el código lo genera la base (`PREG-000001`, Q1).
 * - `sbJson` omite los `null` (`explicitNulls = false`): la RPC aplica sus defaults.
 *   Eso sólo es correcto en el ALTA; la edición va por `p_cambios` con claves presentes.
 */
@Serializable
private data class AdminCreateQuestionRpc(
    @SerialName("p_pregunta_id") val preguntaId: String,
    @SerialName("p_tipo_pregunta") val tipoPregunta: String,
    @SerialName("p_titulo") val titulo: String,
    @SerialName("p_opciones") val opciones: List<AdminRpcOpcionInput>,
    @SerialName("p_razonamiento") val razonamiento: String? = null,
    @SerialName("p_fuente_texto") val fuenteTexto: String? = null,
    // `null` se omite: sin dificultad, nunca un «media» inventado (QN-24).
    @SerialName("p_dificultad") val dificultad: String? = null,
    @SerialName("p_activa") val activa: Boolean = true,
    /** Vínculo inicial en la MISMA transacción (M4). `null` = pregunta suelta en el banco. */
    @SerialName("p_examen_id") val examenId: String? = null,
    /** `null` = al final del examen (Q-14). Las importaciones siempre lo mandan `null`. */
    @SerialName("p_posicion") val posicion: Int? = null,
    @SerialName("p_lote_importacion_id") val loteImportacionId: String? = null,
    /**
     * El `sub` del administrador autenticado (Q2-4). Sólo un id que existe en
     * `auth.users` —el del JWT verificado—, nunca uno fabricado: con el token estático
     * no hay persona y va `null` (se omite), que la base guarda como autor
     * desconocido en vez de fallar con 23503.
     */
    @SerialName("p_creado_por") val creadoPor: String? = null,
)

/**
 * Opción tal y como la valida `private.validar_opciones_pregunta`: `letra`, `texto` y
 * `es_correcta` (booleano). Sin `posicion`: la deriva la base de la letra (A=1…D=4).
 */
@Serializable
private data class AdminRpcOpcionInput(
    val letra: String,
    val texto: String,
    @SerialName("es_correcta") val esCorrecta: Boolean,
)

/** Respuesta jsonb de `admin_create_question`: el id, el código `PREG-n`, si se creó y la posición del vínculo. */
@Serializable
private data class AdminCreateQuestionResult(
    @SerialName("pregunta_id") val preguntaId: String,
    @SerialName("codigo_pregunta") val codigoPregunta: String = "",
    val creada: Boolean,
    @SerialName("posicion_asignada") val posicionAsignada: Int? = null,
)

// `admin_link_question_to_exam` devuelve una fila (posicion_asignada, numero_asignado,
// ya_vinculada) que NO se decodifica: la ruta responde la lista de vínculos ya releída,
// que es lo único que la pantalla pinta. Un DTO que nadie lee sólo sería otra forma más
// de que el contrato y el código se separen sin que ningún test lo note.

/** Argumentos de `admin_import_lote_preguntas` (IM3, KI3-1). */
@Serializable
private data class AdminImportLoteRpc(
    @SerialName("p_lote_id") val loteId: String,
    @SerialName("p_confirmar_examen_publicado") val confirmarExamenPublicado: Boolean,
    @SerialName("p_actor_id") val actorId: String? = null,
)

/**
 * Respuesta jsonb de `admin_import_lote_preguntas`: los totales del lote más lo que
 * hizo ESTA llamada.
 *
 * Los defaults no son laxitud: `omitidas` y `vinculadas` valen 0 en un lote sin
 * duplicados, y decodificar a la fuerza una clave ausente convertiría una
 * publicación correcta en un 500.
 */
@Serializable
private data class AdminImportLoteResultRow(
    @SerialName("lote_id") val loteId: String = "",
    val ejecutado: Boolean = false,
    val estado: String = "",
    @SerialName("total_detectadas") val totalDetectadas: Int = 0,
    @SerialName("total_importadas") val totalImportadas: Int = 0,
    val nuevas: Int = 0,
    val vinculadas: Int = 0,
    val omitidas: Int = 0,
    /** M10: filas publicadas con la relacion anulada. Ausente en una RPC anterior a M10. */
    val anuladas: Int = 0,
)

/** Una fila de `admin_import_lote_duplicados` (IM3, KI3-2). */
@Serializable
private data class AdminImportDuplicadoRow(
    @SerialName("staging_id") val stagingId: String,
    val origen: String = "banco",
    @SerialName("pregunta_id") val preguntaId: String? = null,
    @SerialName("codigo_pregunta") val codigoPregunta: String? = null,
    @SerialName("staging_original_id") val stagingOriginalId: String? = null,
)

// ScoreRow / UsuarioIdRow / EsCorrectaRow / IniciadoEnRow existían sólo para leer
// filas crudas y agregarlas aquí (media, usuarios distintos, intentos por día).
// Esa vía quedaba recortada en silencio por el max-rows de PostgREST, así que se
// retiró junto con el cálculo; cuando exista el read model agregado, el DTO que
// lo consuma tendrá forma de agregado, no de fila cruda.

@Serializable
private data class V1EtiquetaRow(
    val id: String? = null,
    val nombre: String,
    val slug: String = "",
    val descripcion: String? = null,
    @SerialName("color_hex") val colorHex: String? = null,
    val activa: Boolean = true,
)

@Serializable
private data class V1ComunidadRow(
    val id: String? = null,
    val nombre: String,
    val codigo: String = "",
    val slug: String = "",
    val activa: Boolean = true,
    val posicion: Int = 0,
)

@Serializable
private data class PerfilUsuarioRow(
    @SerialName("usuario_id") val usuarioId: String,
    @SerialName("nombre_mostrar") val nombreMostrar: String? = null,
    val username: String? = null,
    @SerialName("especialidad_principal_id") val especialidadPrincipalId: String? = null,
    @SerialName("es_invitado") val esInvitado: Boolean = false,
    @SerialName("creado_en") val creadoEn: String? = null,
)

@Serializable
private data class RolAppRow(
    val id: String,
    val codigo: String = "",
    val nombre: String,
    val descripcion: String? = null,
    @SerialName("creado_en") val creadoEn: String? = null,
)

@Serializable
private data class RolUsuarioRow(
    @SerialName("usuario_id") val usuarioId: String,
    @SerialName("rol_id") val rolId: String,
)

@Serializable
private data class EspecialidadNombreRow(val id: String, val nombre: String)

@Serializable
private data class ExamenTituloRow(val id: String, val titulo: String)

@Serializable
private data class TransaccionCarteraRow(
    val id: String,
    @SerialName("usuario_id") val usuarioId: String,
    val tipo: String,
    val cantidad: Int,
    val descripcion: String? = null,
    @SerialName("creado_en") val creadoEn: String? = null,
    // Parte auditable del ledger: permite reconstruir el histórico, detectar un
    // descuadre y saber qué originó cada movimiento.
    @SerialName("saldo_anterior") val saldoAnterior: Int? = null,
    @SerialName("saldo_resultante") val saldoResultante: Int? = null,
    @SerialName("referencia_tipo") val referenciaTipo: String? = null,
    @SerialName("referencia_id") val referenciaId: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
)

@Serializable
private data class SoporteConversacionRow(
    val id: String,
    @SerialName("usuario_id") val usuarioId: String,
    val asunto: String,
    val estado: String = "abierta",
    val prioridad: String = "normal",
    @SerialName("creado_en") val creadoEn: String? = null,
    @SerialName("actualizado_en") val actualizadoEn: String? = null,
)

@Serializable
private data class SoporteMensajeRow(
    val id: String,
    @SerialName("conversacion_id") val conversacionId: String,
    @SerialName("remitente_id") val remitenteId: String,
    val cuerpo: String,
    @SerialName("es_staff") val esStaff: Boolean = false,
    @SerialName("creado_en") val creadoEn: String? = null,
)

@Serializable
private data class SoporteMensajeInsertRow(
    @SerialName("conversacion_id") val conversacionId: String,
    @SerialName("remitente_id") val remitenteId: String,
    val cuerpo: String,
    @SerialName("es_staff") val esStaff: Boolean,
)

@Serializable
private data class ContenidoDesbloqueadoRow(
    val id: String,
    @SerialName("usuario_id") val usuarioId: String,
    @SerialName("examen_id") val examenId: String? = null,
    @SerialName("pregunta_id") val preguntaId: String? = null,
    val motivo: String? = null,
    @SerialName("valido_hasta") val validoHasta: String? = null,
    @SerialName("creado_en") val creadoEn: String? = null,
)

@Serializable
private data class ContenidoDesbloqueadoInsertRow(
    val id: String,
    @SerialName("usuario_id") val usuarioId: String,
    @SerialName("examen_id") val examenId: String,
    val motivo: String,
    @SerialName("valido_hasta") val validoHasta: String? = null,
)

@Serializable
private data class AccionModeracionRow(
    val id: String,
    @SerialName("reporte_id") val reporteId: String,
    @SerialName("moderador_id") val moderadorId: String? = null,
    @SerialName("tipo_accion") val tipoAccion: String,
    val notas: String? = null,
    @SerialName("creado_en") val creadoEn: String? = null,
)

private fun PerfilUsuarioRow?.displayName(fallback: String): String {
    return this?.nombreMostrar.orEmpty()
        .ifBlank { this?.username.orEmpty() }
        .ifBlank { fallback }
}

private fun TransaccionCarteraRow.toDto() = AdminTicketTransactionDto(
    id = id,
    userId = usuarioId,
    type = tipo,
    amount = cantidad.toDouble(),
    reason = descripcion.orEmpty(),
    occurredAt = creadoEn.orEmpty(),
    balanceBefore = saldoAnterior,
    balanceAfter = saldoResultante,
    originType = referenciaTipo,
    originId = referenciaId,
    idempotencyKey = idempotencyKey,
)

// ---------- Gateway Supabase (esquema real) ----------

class AdminV1Gateway private constructor(
    supabaseUrl: String,
    serviceRoleKey: String,
    private val json: Json,
    client: HttpClient = sharedUpstreamHttpClient,
    private val systemMetrics: SystemMetricsProvider = PrometheusSystemMetricsGateway.fromEnvironment(),
    private val supabaseDisk: SupabaseDiskUsageProvider = SupabaseDiskMetricsGateway.fromEnvironment(),
) {
    /**
     * Migrado a [SupabaseRequestTransport] (FASE 1).
     *
     * Este gateway tenía **seis** funciones de transporte propias y las dos
     * únicas llamadas `DELETE` de Storage escritas a mano en el backend. Es,
     * además, las cuatro verticales de esta fase —Exámenes, Preguntas,
     * Importaciones y los dos endpoints legacy de Dashboard—, así que migrarlo
     * es el grueso del beneficio.
     *
     * El memo de buckets ya no vive aquí: lo lleva el transporte, que es donde
     * la existencia de un bucket es un hecho y no un dato de negocio.
     */
    private val transport = SupabaseRequestTransport(
        baseUrl = supabaseUrl,
        auth = SupabaseRequestAuthPolicy(serviceRoleKey),
        client = client,
    )

    // Para ESCRIBIR en Supabase: encodeDefaults=true (envia tipo_examen="rafaga" aunque
    // sea el default) + explicitNulls=false (omite nulls para que apliquen defaults de BD).
    private val sbJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // Para inserciones EN BLOQUE (varias filas en un único POST de array): PostgREST exige
    // que todos los objetos del array tengan exactamente las mismas claves (PGRST102 "All
    // object keys must match"). `sbJson` omite los null por fila, así que dos filas con
    // distinto patrón de nulos (habitual: una fila de staging con error_parseo y otra sin
    // él) generan claves distintas y PostgREST rechaza el lote entero. explicitNulls=true
    // fuerza la misma clave en todas las filas, aunque el valor sea null.
    private val sbJsonBulk = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    // ESPECIALIDADES
    fun listEspecialidades(): List<AdminEspecialidadDto> =
        request("GET", "/rest/v1/especialidades?select=*&order=posicion.asc,nombre.asc", null)
            .decodeList()

    /**
     * Capacidades (modos disponibles) de una especialidad.
     *
     * Si la especialidad todavía no tiene fila, devuelve los valores por defecto
     * del esquema marcados como `configurado = false`, en lugar de un 404: para el
     * panel "aún no configurada" es un estado normal, no un error.
     *
     * Si la tabla no existe en el proyecto Supabase apuntado, falla explícito
     * (NOT_PROVISIONED) en vez de devolver todo en false, que se leería como
     * "esta especialidad no tiene ningún modo" y es justo lo contrario de la verdad.
     */
    fun getCapacidadesEspecialidad(especialidadId: String): AdminEspecialidadCapacidadesDto {
        especialidadId.validatedUuid("especialidadId")
        val rows = try {
            request(
                "GET",
                "/rest/v1/capacidades_especialidad?select=*&especialidad_id=eq.$especialidadId&limit=1",
                null,
            ).decodeList<CapacidadesEspecialidadRow>()
        } catch (error: IllegalStateException) {
            throw error.asCapabilitiesNotProvisionedOrRethrow()
        }
        return rows.firstOrNull()?.toDto() ?: defaultCapacidades(especialidadId)
    }

    /**
     * Alta o actualización de las capacidades. Upsert sobre la PK
     * `especialidad_id`: el panel no debería tener que saber si la fila ya existía.
     */
    fun upsertCapacidadesEspecialidad(
        especialidadId: String,
        input: AdminEspecialidadCapacidadesDto,
    ): AdminEspecialidadCapacidadesDto {
        especialidadId.validatedUuid("especialidadId")
        val row = input.validated().toRow(especialidadId)
        val saved = try {
            request(
                "POST",
                "/rest/v1/capacidades_especialidad",
                sbJson.encodeToString(listOf(row)),
                prefer = "resolution=merge-duplicates,return=representation",
            ).decodeList<CapacidadesEspecialidadRow>().firstOrNull()
        } catch (error: IllegalStateException) {
            throw error.asCapabilitiesNotProvisionedOrRethrow()
        }
        registrarAuditoria("capacidades_especialidad_actualizadas", "capacidades_especialidad", especialidadId)
        return saved?.toDto() ?: getCapacidadesEspecialidad(especialidadId)
    }

    /**
     * PGRST205 = la tabla no está en el cache de esquema de PostgREST, es decir no
     * existe en este proyecto. Se traduce a un error de dominio propio para que el
     * panel pueda enseñar NOT_CONFIGURED; cualquier otro fallo se propaga tal cual.
     */
    private fun IllegalStateException.asCapabilitiesNotProvisionedOrRethrow(): Exception {
        val text = message.orEmpty()
        val missingTable = text.contains("PGRST205") ||
            text.contains("Could not find the table") ||
            text.contains("schema cache")
        if (!missingTable) return this
        return BackendDomainException(
            "SPECIALTY_CAPABILITIES_NOT_PROVISIONED",
            HttpStatusCode.NotImplemented,
            "La tabla capacidades_especialidad no existe en el proyecto Supabase configurado. " +
                "Las capacidades por especialidad no se pueden leer ni administrar hasta que se aprovisione.",
        )
    }

    fun createEspecialidad(input: AdminEspecialidadDto): AdminEspecialidadDto {
        require(input.nombre.isNotBlank()) { "La especialidad necesita nombre." }
        val row = input.copy(
            id = input.id.ifBlank { UUID.randomUUID().toString() },
            codigo = input.codigo.ifBlank { "ESP-" + shortId() },
            slug = input.slug.ifBlank { input.nombre.toSlug() },
        )
        return request(
            "POST",
            "/rest/v1/especialidades",
            sbJson.encodeToString(listOf(row)),
            prefer = "return=representation",
        ).decodeList<AdminEspecialidadDto>().first()
    }

    fun updateEspecialidad(id: String, input: AdminEspecialidadDto): AdminEspecialidadDto {
        require(input.nombre.isNotBlank()) { "La especialidad necesita nombre." }
        val row = input.copy(id = id, slug = input.slug.ifBlank { input.nombre.toSlug() })
        return request(
            "PATCH",
            "/rest/v1/especialidades?id=eq.$id",
            // `especialidadPatchPayload` y no `sbJson.encodeToString`: omite el
            // `codigo` en blanco para no borrar el real. Ver su KDoc.
            especialidadPatchPayload(row),
            prefer = "return=representation",
        ).decodeList<AdminEspecialidadDto>().firstOrNull()
            ?: error("Especialidad no encontrada para actualizar: $id")
    }

    fun archiveEspecialidad(id: String): AdminEspecialidadDto {
        val saved = request(
            "PATCH",
            "/rest/v1/especialidades?id=eq.$id",
            """{"activa":false}""",
            prefer = "return=representation",
        ).decodeList<AdminEspecialidadDto>().firstOrNull()
            ?: error("Especialidad no encontrada.")
        registrarAuditoria("especialidad_archivada", "especialidades", id)
        return saved
    }

    // EXAMENES
    /** El catálogo, con TODAS sus portadas firmadas en una sola petición a Storage (K5). */
    fun listExamenes(): List<AdminExamenDto> =
        request("GET", "/rest/v1/examenes?select=*&order=creado_en.desc", null)
            .decodeList<AdminExamenRow>()
            .map { it.toDto() }
            .withSignedCovers()

    fun getExamen(id: String): AdminExamenDto? =
        request("GET", "/rest/v1/examenes?select=*&id=eq.$id&limit=1", null)
            .decodeList<AdminExamenRow>()
            .firstOrNull()
            ?.toDto()
            .withSignedCover()

    /**
     * La URL de cada portada se FIRMA en la lectura y nunca se guarda (K5, PW-8
     * opción A): el bucket es privado, así que una URL pública daría 404, y una
     * firmada caduca en [EXAM_COVER_URL_TTL_SECONDS]. UNA petición a Storage para
     * todo el listado, no una por examen.
     *
     * Un fallo al firmar nunca rompe la lectura: el examen sale sin `portadaUrl` —
     * el cliente conserva la ruta y enseña «Portada guardada»— y el fallo va al log.
     */
    private fun List<AdminExamenDto>.withSignedCovers(): List<AdminExamenDto> {
        val paths = mapNotNull { it.portadaStoragePath?.takeIf(String::isNotBlank) }.distinct()
        if (paths.isEmpty()) return this
        val signed = try {
            transport.storageSignedUrls(examResourcesBucket(), paths, EXAM_COVER_URL_TTL_SECONDS)
        } catch (error: Exception) {
            ExamCoverLogger.warn("firma de portadas fallida: {}", error.redactedMessage("Fallo al firmar portadas", 160))
            emptyMap()
        }
        return map { dto -> dto.copy(portadaUrl = dto.portadaStoragePath?.let { signed[it] }) }
    }

    private fun AdminExamenDto?.withSignedCover(): AdminExamenDto? = this?.let { listOf(it).withSignedCovers().first() }

    fun createExamen(input: AdminExamenDto): AdminExamenDto {
        require(input.titulo.isNotBlank()) { "El examen necesita titulo." }
        require(input.especialidadId.isNotBlank()) { "El examen necesita especialidad." }
        // Vocabulario canónico ANTES de construir la fila: ver
        // [canonicalExamTypeOrReject]. Un valor desconocido es un 422, nunca un
        // `rafaga` silencioso.
        val row = input.withCanonicalVocabulary().toRow()
            .let { if (it.codigoExamen.isBlank()) it.copy(codigoExamen = "EX-" + shortId()) else it }
            // `total_preguntas` NO viaja (K2, tras M1). La mantiene la base:
            // `trg_examenes_total_preguntas_insert` la fija a 0 al insertar —un
            // borrador vacío dice la verdad desde PW-2, que admite `>= 0`— y la
            // guarda `trg_examenes_total_preguntas_guard` neutraliza cualquier otra
            // escritura (C-05). `toRow()` ya no la copia, así que tampoco se cuela
            // un `totalPreguntas` que venga en el cuerpo del POST.
            //
            // UN EXAMEN NACE EN BORRADOR Y ACTIVO. Siempre, diga lo que diga el
            // cuerpo.
            //
            // `updateExamen` nunca escribe la visibilidad (su lista blanca,
            // [PATCHABLE_EXAMEN_COLUMNS], no la contiene), pero
            // `createExamen` no filtraba nada: construía la fila con `toRow()` y
            // la mandaba entera. Un `POST` con
            // `"publicadoEn":"2020-01-01T00:00:00Z"` insertaba `activo=true` +
            // `publicado_en` no nulo, que es EXACTAMENTE la condición con la que
            // `SupabaseV1Gateway.listExamenes` sirve un examen a los usuarios de
            // la app. La regla de E-1 —«la visibilidad se cambia sólo por
            // publicar, despublicar y archivar»— tenía una puerta abierta.
            //
            // Lo que lo convierte en defecto y no en decisión de producto es la
            // AUDITORÍA: `createExamen` registra `examen_creado` y nada más;
            // `publicarExamen` registra `examen_publicado`. Por esta puerta un
            // examen se volvía visible para usuarios finales sin una sola línea
            // que dijera que alguien lo publicó. Una pista de auditoría que se
            // puede esquivar no sirve para lo único que sirve.
            //
            // `publicadoEn = null` se OMITE del JSON (`explicitNulls = false`),
            // así que la columna queda con su default de base. `activo = true`
            // sí viaja (`encodeDefaults = true`) y es lo correcto: nacer
            // archivado sería un examen que el propio panel no lista.
            //
            // Para publicar hay una ruta, y es `POST /examenes/{id}/publicar`.
            .copy(activo = true, publicadoEn = null)
        val saved = request(
            "POST",
            "/rest/v1/examenes",
            sbJson.encodeToString(listOf(row)),
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().first().toDto()
        registrarAuditoria("examen_creado", "examenes", saved.id)
        return saved
    }

    /**
     * PATCH de examen con el contrato C-01: **sólo las claves presentes en el
     * cuerpo** se escriben. Ver [PATCHABLE_EXAMEN_COLUMNS] y [examenPatchFrom].
     *
     * `null` = el examen no existe, y eso lo traduce la ruta a 404 — igual que
     * `publicar`, `despublicar` y `archivar`. El `null` no se confunde con
     * «falló»: `failOnError` ya convirtió cualquier respuesta de error del
     * upstream en excepción antes de llegar aquí, así que una lista vacía con 200
     * sólo puede significar que `id=eq.` no casó.
     *
     * Un cuerpo sin ninguna clave admitida no sale como PATCH: se responde el
     * examen tal como está. Un PATCH vacío no es una escritura y no tiene por qué
     * parecerlo.
     */
    fun updateExamen(id: String, raw: JsonObject): AdminExamenDto? {
        val patch = examenPatchFrom(raw)
        if (patch.isEmpty()) return getExamen(id)
        return request(
            "PATCH",
            "/rest/v1/examenes?id=eq.$id",
            sbJson.encodeToString(JsonObject.serializer(), patch),
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().firstOrNull()?.toDto().withSignedCover()
    }

    /**
     * Publica. `null` = el examen no existe, y eso lo traduce la ruta a 404.
     *
     * ### DEC-EX-01 = A: un examen sin preguntas no se publica (K-DEC01)
     *
     * La regla va DENTRO de la sentencia que publica —`total_preguntas=gt.0` en el
     * filtro del PATCH— y no en una lectura previa: entre leer «tiene preguntas» y
     * publicar, otro administrador podría archivar la última. La cifra es fiable
     * porque la mantiene la base (M1).
     *
     * Cero filas actualizadas significa una de dos cosas, y se distinguen con una
     * lectura mínima que sólo paga el camino del rechazo: el examen no existe
     * (`null` → 404) o existe y está vacío (422 `EXAM_PUBLISH_REQUIRES_QUESTIONS`).
     *
     * Lo que NO se bloquea, y es deliberado: desvincular o archivar la última
     * pregunta de un examen ya publicado. Bloquearlo impediría archivar preguntas
     * del banco; lo decide DEC-Q-04 (Q4), no esta función.
     */
    fun publicarExamen(id: String): AdminExamenDto? {
        val saved = request(
            "PATCH",
            "/rest/v1/examenes?id=eq.$id&total_preguntas=gt.0",
            """{"publicado_en":"${Instant.now()}","activo":true}""",
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().firstOrNull()?.toDto()
        if (saved == null) {
            val existe = request("GET", "/rest/v1/examenes?select=id&id=eq.$id&limit=1", null)
                .decodeList<JsonObject>()
                .isNotEmpty()
            if (!existe) return null
            throw examPublishRequiresQuestionsException()
        }
        registrarAuditoria("examen_publicado", "examenes", id)
        return saved.withSignedCover()
    }

    /**
     * Despublica. `null` = no existe → 404 en la ruta.
     *
     * **NO toca `activo`, y eso es deliberado.** Despublicar un examen archivado
     * lo dejaría publicado-pero-inactivo, un estado que ninguna lectura de la app
     * entiende. Queda escrito para que nadie lo «arregle» añadiendo
     * `"activo":true`.
     */
    fun despublicarExamen(id: String): AdminExamenDto? {
        val saved = request(
            "PATCH",
            "/rest/v1/examenes?id=eq.$id",
            """{"publicado_en":null}""",
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().firstOrNull()?.toDto()
            ?: return null
        registrarAuditoria("examen_despublicado", "examenes", id)
        return saved.withSignedCover()
    }

    /**
     * Archiva. `null` = no existe → 404 en la ruta.
     *
     * Tiene ruta propia (`POST /examenes/{id}/archivar`) desde que
     * `DELETE /examenes/{id}` pasó a [deleteExamen] y borra de verdad. Antes era
     * lo que hacía el DELETE, y por eso la consola ofrecía «Eliminar» sobre una
     * operación que no eliminaba nada.
     */
    fun archiveExamen(id: String): AdminExamenDto? {
        val saved = request(
            "PATCH",
            "/rest/v1/examenes?id=eq.$id",
            """{"activo":false,"publicado_en":null}""",
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().firstOrNull()?.toDto()
            ?: return null
        registrarAuditoria("examen_archivado", "examenes", id)
        return saved.withSignedCover()
    }

    /**
     * **Borra el examen de verdad**, con una guarda delante. `false` = no existe →
     * 404 en la ruta.
     *
     * ### Por qué hay guarda
     *
     * Ocho tablas apuntan a `examenes` (leído del esquema vivo). Dos son enlaces
     * que el examen posee —las asociaciones de preguntas y los lotes de
     * importación que lo alimentaron, que cascadean con él— y las otras seis son
     * **historial de usuarios**: intentos, respuestas, abandonos, opiniones,
     * desbloqueos de contenido (compras) y eventos de conversión.
     *
     * El comportamiento `on delete` de esas seis claves ajenas **NO está
     * verificado en la base viva**, y las dos posibilidades son malas sin guarda:
     * si cascadean, borrar un examen destruye en silencio el historial de gente
     * que pagó por él; si no, el borrado falla con un 23503 que sin mapeo era un
     * 500. Así que no se delega la decisión en la base: se cuenta antes, y si hay
     * algo NO se borra nada y se responde 409 con
     * [examHasUserDataException] —que dice qué hay colgando y que lo que se
     * quiere es archivar—.
     *
     * ### Cómo se cuenta
     *
     * Una lectura por tabla, `select=id&limit=1`, y sólo importa si vuelve vacía.
     * No se usa `Prefer: count=exact` a propósito: el número exacto no cambia
     * ninguna decisión —uno solo ya bloquea— y `transport.count` LANZA si la
     * respuesta no trae `Content-Range` utilizable, así que costaría lo mismo y
     * añadiría un modo de fallo. Se consultan las seis aunque la primera ya
     * bloquee, porque el mensaje enumera TODAS las categorías que cuelgan y «hay
     * intentos» cuando además hay compras informa de menos.
     *
     * Si una de esas lecturas falla, **no se borra**: sale
     * [examDeleteGuardUnavailableException]. Un fallo de la comprobación no puede
     * volverse un borrado «por si acaso».
     *
     * ### El borrado: UNA sentencia (EX-19)
     *
     * Un solo `DELETE /examenes?id=eq.<id>`. Las dos tablas que el examen posee
     * —`preguntas_examen` (la asociación, no las preguntas) y
     * `lotes_importacion_preguntas`— hacen `ON DELETE CASCADE`, verificado en la base
     * viva por la revisión de C. Antes se borraban a mano y en tres peticiones, y eso
     * tenía dos defectos: no era atómico (un fallo a mitad dejaba el examen sin
     * preguntas y en pie) y, con la guarda Q4 —«un examen publicado no puede quedarse
     * sin preguntas activas»—, borrar primero las asociaciones de un examen publicado
     * fallaría siempre. En una sola sentencia la base borra el examen y lo que cuelga
     * de él a la vez.
     *
     * **Lo que este método NO borra, y es deliberado**: las `preguntas` en sí.
     * `preguntas_examen` es una M:N, así que la pregunta puede estar en otros
     * exámenes y en el banco; borrar la asociación es exactamente lo que hace
     * `desvincularPreguntaExamen` y lo correcto aquí. Igual que la portada, que
     * sigue en Storage — ver el comentario de `clearExamenPortada`.
     */
    fun deleteExamen(id: String): Boolean {
        val existe = request("GET", "/rest/v1/examenes?select=id&id=eq.$id&limit=1", null)
            .decodeList<JsonObject>()
            .isNotEmpty()
        if (!existe) return false

        val conDatos = examenUserDataCategories(id)
        if (conDatos.isNotEmpty()) throw examHasUserDataException(conDatos)

        request("DELETE", "/rest/v1/examenes?id=eq.$id", null)
        registrarAuditoria("examen_eliminado", "examenes", id)
        return true
    }

    /**
     * Las categorías de datos de usuario que cuelgan del examen, en etiquetas
     * HUMANAS y en el orden del inventario. Vacía = el examen se puede borrar.
     *
     * Devuelve etiquetas y no nombres de tabla porque lo que devuelve acaba en el
     * mensaje del 409: la regla de `BackendErrors.kt` —ni esquema, ni columnas, ni
     * identificadores— se cumple aquí, en el origen, y no confiando en que el
     * consumidor recorte.
     */
    private fun examenUserDataCategories(id: String): List<String> = EXAMEN_USER_DATA_TABLES
        .filter { (tabla, _) ->
            val respuesta = runCatching {
                request("GET", "/rest/v1/$tabla?select=id&examen_id=eq.$id&limit=1", null)
            }.getOrElse { throw examDeleteGuardUnavailableException() }
            runCatching { respuesta.decodeList<JsonObject>() }
                .getOrElse { throw examDeleteGuardUnavailableException() }
                .isNotEmpty()
        }
        .map { (_, etiqueta) -> etiqueta }

    // PREGUNTAS (+ opciones + solucion + recursos + asociacion a examen)
    /**
     * Una PÁGINA del banco y su total exacto, en UNA petición (KQ0-3, QN-07).
     *
     * Antes era `select=*` sin cota y todo lo demás —buscar, filtrar por tipo o
     * estado, ordenar, paginar— se hacía en el cliente sobre lo cargado, que además
     * eran sólo las activas: el filtro «Inactivas» del banco no podía encontrar
     * nada. Ahora el servidor filtra, ordena y corta, y el total sale de
     * `Content-Range`.
     *
     * - Sin examen: raíz `preguntas`, sólo las columnas del listado.
     * - Con examen: raíz `preguntas_examen` con `preguntas!inner(…)`; los filtros de
     *   la pregunta van como `preguntas.<filtro>` y el orden es la posición. El total
     *   cuenta los vínculos que cumplen los filtros.
     *
     * Todo lo que entra en la URL ya viene neutralizado por
     * [toPreguntaListQuery]: literales, UUID canónicos y la búsqueda saneada.
     */
    internal fun listPreguntasPagina(q: AdminPreguntaListQuery): AdminPreguntasPageResponse {
        val filtros = buildList {
            q.activa?.let { add("activa=eq.$it") }
            q.tipo?.let { add("tipo_pregunta=eq.$it") }
            q.texto?.let(::preguntaSearchFilter)?.let { add("or=($it)") }
        }
        val rango = "&limit=${q.limit}&offset=${q.offset}"
        return if (q.examenId == null) {
            val page = transport.getPage(
                "/rest/v1/preguntas?select=$PREGUNTA_LIST_COLUMNS" + filtros.joinToString("") { "&$it" } +
                    "&order=${q.orden.postgrest}$rango",
            )
            AdminPreguntasPageResponse(
                data = page.body.decodeList<AdminPreguntaRow>().map { it.toDtoSinDetalle() },
                total = page.total,
                limit = q.limit,
                offset = q.offset,
            )
        } else {
            val page = transport.getPage(
                "/rest/v1/preguntas_examen?select=*,preguntas!inner($PREGUNTA_LIST_COLUMNS)" +
                    "&examen_id=eq.${q.examenId}" + filtros.joinToString("") { "&preguntas.$it" } +
                    "&order=posicion.asc$rango",
            )
            AdminPreguntasPageResponse(
                data = page.body.decodeList<AdminPreguntaExamenListRow>().map {
                    it.preguntas.toDtoSinDetalle().copy(
                        examenId = q.examenId,
                        posicion = it.posicion,
                        anulada = it.anulada,
                        motivoAnulacion = it.motivoAnulacion,
                    )
                },
                total = page.total,
                limit = q.limit,
                offset = q.offset,
            )
        }
    }

    /**
     * Preguntas de un examen, con opciones, solución y recursos, en **coste
     * constante**.
     *
     * Antes esto llamaba a [getPregunta] dentro de un bucle y cada vuelta
     * disparaba 4 peticiones a Supabase (pregunta, opciones, solución,
     * recursos): un examen de 120 preguntas costaba 1 + 4×120 = **481**
     * peticiones, 120 de ellas sólo de recursos. Ahora son **5 como máximo**,
     * sea cual sea el número de preguntas, agrupando por `id=in.(...)` — el
     * mismo patrón que ya usaba [SupabaseV1Gateway.getPreguntasExamenRafaga].
     *
     * El filtro `activa=eq.true` sustituye al `takeIf { it.activa }` posterior:
     * una pregunta archivada que conserve su fila en `preguntas_examen` no debe
     * viajar, y descartarla en el servidor evita traérsela para tirarla.
     */
    fun listPreguntasExamen(examenId: String): List<AdminPreguntaDto> {
        val links = request(
            "GET",
            "/rest/v1/preguntas_examen?select=*&examen_id=eq.$examenId&order=posicion.asc",
            null,
        ).decodeList<AdminPreguntaExamenRow>()
        if (links.isEmpty()) return emptyList()

        val idsFilter = links.map { it.preguntaId }.distinct().joinToString(",")

        val preguntasById = request("GET", "/rest/v1/preguntas?select=*&id=in.($idsFilter)&activa=eq.true", null)
            .decodeList<AdminPreguntaRow>()
            .associateBy { it.id.orEmpty() }
        val opcionesByPregunta = request(
            "GET",
            "/rest/v1/opciones_pregunta?select=*&pregunta_id=in.($idsFilter)&order=posicion.asc",
            null,
        ).decodeList<AdminOpcionRow>().groupBy { it.preguntaId }
        val solucionByPregunta = request(
            "GET",
            "/rest/v1/soluciones_pregunta?select=*&pregunta_id=in.($idsFilter)",
            null,
        ).decodeList<AdminSolucionRow>().associateBy { it.preguntaId }
        // Se firma UNA vez para el examen entero y después se agrupa: firmar por
        // pregunta serían N peticiones a Storage en una pantalla que ya carga 120.
        val recursosByPregunta = request(
            "GET",
            "/rest/v1/recursos_pregunta?select=*&pregunta_id=in.($idsFilter)&activo=eq.true&order=posicion.asc",
            null,
        ).decodeList<AdminRecursoRow>().firmados().groupBy { it.preguntaId }

        return links.mapNotNull { link ->
            val pregunta = preguntasById[link.preguntaId] ?: return@mapNotNull null
            pregunta.ensamblar(
                opciones = opcionesByPregunta[link.preguntaId].orEmpty(),
                solucion = solucionByPregunta[link.preguntaId],
                recursos = recursosByPregunta[link.preguntaId].orEmpty(),
            ).copy(
                examenId = examenId,
                posicion = link.posicion,
                anulada = link.anulada,
                motivoAnulacion = link.motivoAnulacion,
            )
        }.sortedBy { it.posicion ?: Int.MAX_VALUE }
    }

    fun getPregunta(id: String): AdminPreguntaDto? {
        val pregunta = request("GET", "/rest/v1/preguntas?select=*&id=eq.$id&limit=1", null)
            .decodeList<AdminPreguntaRow>().firstOrNull() ?: return null
        val opciones = request("GET", "/rest/v1/opciones_pregunta?select=*&pregunta_id=eq.$id&order=posicion.asc", null)
            .decodeList<AdminOpcionRow>()
        val solucion = request("GET", "/rest/v1/soluciones_pregunta?select=*&pregunta_id=eq.$id&limit=1", null)
            .decodeList<AdminSolucionRow>().firstOrNull()
        return pregunta.ensamblar(opciones, solucion, listRecursos(id))
    }

    /**
     * Une las cuatro piezas de una pregunta en su DTO.
     *
     * Existe para que la lectura de una sola pregunta y la del examen entero no
     * puedan divergir: si sólo una de las dos supiera marcar la opción correcta,
     * el editor y el listado mostrarían cosas distintas de la misma fila.
     */
    private fun AdminPreguntaRow.ensamblar(
        opciones: List<AdminOpcionRow>,
        solucion: AdminSolucionRow?,
        recursos: List<AdminRecursoDto>,
    ): AdminPreguntaDto {
        val correctaId = solucion?.opcionCorrectaId
        return toDtoSinDetalle().copy(
            razonamiento = solucion?.razonamiento.orEmpty(),
            opciones = opciones.map {
                AdminOpcionDto(
                    id = it.id.orEmpty(),
                    letra = it.letra,
                    texto = it.textoOpcion,
                    posicion = it.posicion,
                    esCorrecta = it.id == correctaId,
                )
            },
            // Llegan ya FIRMADOS: sin la URL firmada, el material de una pregunta
            // inmersiva viajaría con la ruta de un bucket privado y nada que abrir.
            recursos = recursos,
        )
    }

    /**
     * Da de alta una pregunta con `admin_create_question` (Q2, KQ2-1) y la devuelve
     * leída entera, diciendo si ESTA llamada la creó.
     *
     * - **Idempotente por id.** El id lo genera el cliente una vez por alta y lo
     *   conserva en los reintentos ([AdminPreguntaDto.id]). Un reintento con el mismo
     *   contenido responde `creada:false` sin escribir ni auditar; con otro contenido,
     *   `QUESTION_ID_CONFLICT`. Si el cliente no manda id (un llamador interno), se
     *   genera aquí: sin reintento posible, no hay nada que proteger.
     * - **Todo o nada.** La RPC valida (enunciado, 4 opciones, 1 correcta, tipo,
     *   dificultad y, si hay examen, el vínculo por tipo) ANTES de escribir, e
     *   inserta pregunta, opciones, solución y vínculo en una transacción. Ya no hay
     *   `require` aquí: las reglas y sus códigos son de la base, y duplicarlas en
     *   Kotlin sólo las haría divergir.
     * - **Sin `onCreated`.** Existía para que el bucle de importación anotara el id
     *   antes de las lecturas posteriores; la importación publica ahora con UNA RPC
     *   (KI3-1) y `/rafagas/import` se retiró (DEC-IMP-05), así que no queda llamador.
     */
    fun createPregunta(
        input: AdminPreguntaDto,
        actorId: String? = null,
        loteImportacionId: String? = null,
    ): AdminPreguntaCreada {
        val preguntaId = input.id.takeIf { it.isNotBlank() }?.validatedUuid("id") ?: UUID.randomUUID().toString()
        val body = request(
            "POST", "/rest/v1/rpc/admin_create_question",
            sbJson.encodeToString(
                AdminCreateQuestionRpc(
                    preguntaId = preguntaId,
                    tipoPregunta = input.tipoPregunta,
                    titulo = input.titulo,
                    opciones = input.opciones.map { AdminRpcOpcionInput(it.letra, it.texto, it.esCorrecta) },
                    razonamiento = input.razonamiento.takeIf { it.isNotEmpty() },
                    fuenteTexto = input.fuenteTexto?.takeIf { it.isNotBlank() },
                    dificultad = input.dificultad.takeIf { it.isNotBlank() },
                    activa = input.activa,
                    examenId = input.examenId?.takeIf { it.isNotBlank() },
                    posicion = input.posicion,
                    loteImportacionId = loteImportacionId,
                    creadoPor = actorId,
                ),
            ),
        )
        val resultado = json.decodeFromString<AdminCreateQuestionResult>(body)
        // Un reintento que no escribió nada no es otra alta: sin auditoría.
        if (resultado.creada) registrarAuditoria("pregunta_creada", "preguntas", resultado.preguntaId)
        val leida = getPregunta(resultado.preguntaId) ?: error("No se pudo leer la pregunta creada.")
        return AdminPreguntaCreada(leida, resultado.creada)
    }

    /**
     * Edita una pregunta con `admin_update_question` (Q2, KQ2-2): sólo las claves
     * PRESENTES en el cuerpo ([preguntaCambiosFrom]), en una transacción, con las
     * guardas de tipo (`QUESTION_TYPE_LOCKED_BY_*`) bajo el cerrojo de la pregunta.
     *
     * Lo que ya NO hace, y por qué:
     * - Leer antes: una pregunta que no existe la rechaza la RPC (`QUESTION_NOT_FOUND`).
     * - Escribir la fila, las opciones y la solución en tres peticiones: una edición a
     *   medias dejaba el tipo cambiado y la explicación sin guardar.
     * - Vincular (D-03/D-04): `examenId`/`posicion` del cuerpo se IGNORAN. La única
     *   puerta para vincular es `POST /preguntas/{id}/examenes`, y el editor de
     *   Exámenes vincula al CREAR (misma transacción que el alta).
     * - Tocar `recursos[]` (KQ0-8), el código (Q1) o la especialidad.
     */
    fun updatePregunta(id: String, raw: JsonObject): AdminPreguntaDto {
        val cambios = preguntaCambiosFrom(raw)
        request(
            "POST",
            "/rest/v1/rpc/admin_update_question",
            // Serializado a mano y NO con `sbJson`: un `null` presente en `p_cambios`
            // tiene que viajar («vacía la fuente»), y `explicitNulls = false` lo borraría.
            buildJsonObject {
                put("p_pregunta_id", JsonPrimitive(id))
                put("p_cambios", cambios)
            }.toString(),
        )
        registrarAuditoria("pregunta_actualizada", "preguntas", id)
        return getPregunta(id) ?: throw domainError("QUESTION_NOT_FOUND")
    }

    /**
     * Archiva una pregunta. Una que no existe es un 404, no un 500.
     *
     * Misma corrección que E-2 hizo en las cuatro rutas de examen, por el mismo
     * motivo: un `IllegalStateException` sale por el handler de upstream como
     * «Error al consultar el servicio de datos. Referencia: …», que dice «el
     * servidor está roto» cuando lo que pasa es que otro administrador archivó
     * esa pregunta antes, y lo hace indistinguible de un Supabase caído — que es
     * exactamente el caso que esa referencia existe para poder investigar.
     *
     * Y el `$id` sale del texto de la excepción: apoyarse en que el handler lo
     * redacte es peor que no ponerlo, según decía ya el KDoc de `updateExamen`.
     */
    fun archivePregunta(id: String): AdminPreguntaDto {
        val saved = request(
            "PATCH",
            "/rest/v1/preguntas?id=eq.$id",
            """{"activa":false}""",
            prefer = "return=representation",
        ).decodeList<AdminPreguntaRow>().firstOrNull()
            ?: throw domainError("QUESTION_NOT_FOUND")
        registrarAuditoria("pregunta_archivada", "preguntas", id)
        // Return full DTO with opciones/solucion/recursos for consistency with other handlers.
        return getPregunta(id) ?: saved.toDtoSinDetalle()
    }

    /**
     * Llama a una RPC de ESCRITURA que puede no existir todavía en la base y
     * convierte ese caso —y sólo ése— en un error de dominio legible.
     *
     * Sin esto, la falta de una migración sale como `IllegalStateException` → 500
     * redactado («el servidor está roto»), que es a la vez falso y un callejón:
     * el administrador reintenta, y reintentar no puede arreglar una función que
     * no existe. Con [codigoNoDisponible] la consola dice qué falta y que no ha
     * cambiado nada.
     *
     * Se mira `code == PGRST202` ([saysWriteRpcIsMissing]) y NO el texto: el
     * mensaje del transporte arrastra 300 caracteres del cuerpo de PostgREST, que
     * pueden llevar contenido escrito por el administrador.
     *
     * Cualquier otro fallo —incluidos los `BackendDomainException` que la propia
     * RPC levanta con `raise`— sale intacto: aquí no se traduce nada más.
     */
    private fun rpcOrUnavailable(funcion: String, body: String, codigoNoDisponible: String): String =
        try {
            request("POST", "/rest/v1/rpc/$funcion", body)
        } catch (error: Exception) {
            if (error.saysWriteRpcIsMissing()) throw domainError(codigoNoDisponible)
            throw error
        }

    /**
     * Reordena las preguntas de un examen con UNA sentencia (M4, K3-1).
     *
     * Era un bucle de PATCH, uno por pregunta: un intercambio de dos posiciones
     * chocaba contra el índice único a mitad del bucle y dejaba el examen con el
     * orden a medias, y el conjunto no se validaba contra nada. La RPC valida
     * bajo cerrojo del examen (`EXAM_REORDER_SET_MISMATCH` si la lista no es
     * exactamente la del examen) y escribe con la restricción diferida.
     */
    fun reordenarPreguntasExamen(examenId: String, input: AdminPreguntaOrdenRequest): List<AdminPreguntaDto> {
        rpcOrUnavailable(
            "admin_reorder_exam_questions",
            buildJsonObject {
                put("p_examen_id", JsonPrimitive(examenId))
                put("p_pregunta_ids", JsonArray(input.ids.map { JsonPrimitive(it) }))
            }.toString(),
            "EXAM_QUESTION_REORDER_UNAVAILABLE",
        )
        registrarAuditoria("preguntas_examen_reordenadas", "preguntas_examen", examenId)
        return listPreguntasExamen(examenId)
    }

    /**
     * Vincula (M:N) de forma atómica e idempotente con `admin_link_question_to_exam`
     * (M4, K3-3). `posicion == null` = al final; ya vinculada = no se mueve.
     *
     * Sustituye a `upsertPreguntaExamen`, que leía `max(numero_en_examen)+1` y
     * escribía después: sin cerrojo, dos altas simultáneas se daban el mismo
     * número, y `posicion ?: 1` chocaba con la pregunta que ya ocupaba la 1 en un
     * examen no vacío. La RPC valida tipo, archivado y límite ANTES
     * de insertar, y recoloca el resto en la misma transacción.
     */
    internal fun vincularPreguntaExamen(examenId: String, preguntaId: String, posicion: Int?) {
        rpcOrUnavailable(
            "admin_link_question_to_exam",
            buildJsonObject {
                put("p_examen_id", JsonPrimitive(examenId))
                put("p_pregunta_id", JsonPrimitive(preguntaId))
                put("p_posicion", posicion?.let { JsonPrimitive(it) } ?: JsonNull)
            }.toString(),
            "QUESTION_EXAM_LINK_UNAVAILABLE",
        )
    }

    /**
     * Quita una pregunta de un examen **sin tocarla en el banco** y recompacta
     * `posicion` en la misma transacción (M4, K3-2).
     *
     * `DELETE /preguntas/{id}` archiva (`activa=false`) la fila global, y como
     * `preguntas_examen` es M:N eso la saca de todos los exámenes que la
     * compartan. Aquí sólo desaparece la asociación con ESTE examen.
     *
     * Era un DELETE + una lectura + un PATCH por fila restante. Entre el DELETE y
     * la recompactación cabía cualquier otra escritura, y un fallo a mitad dejaba
     * el examen con huecos (1,2,4,5) que descolocaban la numeración mostrada. Si
     * la pregunta no estaba vinculada, la RPC levanta `QUESTION_NOT_LINKED_TO_EXAM`
     * (404 de dominio) en vez de responder éxito sobre cero filas.
     */
    fun quitarVinculoPreguntaExamen(examenId: String, preguntaId: String) {
        rpcOrUnavailable(
            "admin_unlink_question_from_exam",
            buildJsonObject {
                put("p_examen_id", JsonPrimitive(examenId))
                put("p_pregunta_id", JsonPrimitive(preguntaId))
            }.toString(),
            "QUESTION_EXAM_UNLINK_UNAVAILABLE",
        )
        registrarAuditoria("pregunta_desvinculada", "preguntas_examen", examenId)
    }

    /** La misma operación vista desde el examen: quita el vínculo y devuelve las preguntas que quedan. */
    fun desvincularPreguntaExamen(examenId: String, preguntaId: String): List<AdminPreguntaDto> {
        quitarVinculoPreguntaExamen(examenId = examenId, preguntaId = preguntaId)
        return listPreguntasExamen(examenId)
    }

    /**
     * Los exámenes a los que está vinculada una pregunta (M:N), en UNA petición
     * embebida (K3-4).
     *
     * Es la consulta que no existía: el banco de Preguntas infería los vínculos de
     * la sesión —sólo veías uno si habías llegado filtrando por ese examen— y por
     * eso «Desvincular» estaba bloqueado y el diálogo de archivar no podía avisar
     * de un examen publicado.
     *
     * `mapNotNull`: una fila cuyo examen no venga embebido (borrado concurrente) se
     * omite en vez de inventar un examen vacío.
     */
    fun listExamenesDePregunta(preguntaId: String): List<AdminPreguntaExamenVinculoDto> =
        request(
            "GET",
            "/rest/v1/preguntas_examen?select=*,examenes(id,codigo_examen,titulo,tipo_examen,activo,publicado_en,total_preguntas)" +
                "&pregunta_id=eq.$preguntaId&order=examen_id.asc",
            null,
        ).decodeList<AdminPreguntaExamenVinculoRow>().mapNotNull { it.toDto() }

    /**
     * La misma regla, pública, para imponerla ANTES de leer el cuerpo de una subida.
     *
     * Con 50 MB de margen, comprobar después de recibir el archivo significa gastar la
     * red del administrador para responderle que no se podía.
     */
    fun requireQuestionAcceptsResources(preguntaId: String) = requireStoredQuestionAcceptsResources(preguntaId)

    /**
     * Impone la regla contra el tipo GUARDADO de la pregunta, leyéndolo.
     *
     * Una pregunta que no existe es un 404 y no un 201 sobre la nada: antes esta
     * ruta insertaba una fila en `recursos_pregunta` con un `pregunta_id`
     * inventado sin comprobar nada, y la clave ajena era la única red.
     */
    private fun requireStoredQuestionAcceptsResources(preguntaId: String) {
        val tipo = request(
            "GET",
            "/rest/v1/preguntas?select=tipo_pregunta&id=eq.$preguntaId&limit=1",
            null,
        ).decodeList<AdminPreguntaTipoRow>().firstOrNull()?.tipoPregunta
            ?: throw domainError("QUESTION_NOT_FOUND")
        requireTypeAcceptsResources(tipo)
    }

    /**
     * La regla, sobre un tipo que ya se conoce.
     *
     * El mensaje es una CONSTANTE: no devuelve el tipo que mandó el cliente ni el
     * que tiene la fila.
     */
    private fun requireTypeAcceptsResources(tipoPregunta: String) {
        if (!tipoPregunta.trim().equals(QUESTION_TYPE_WITH_RESOURCES, ignoreCase = true)) {
            throw BackendDomainException(
                "QUESTION_TYPE_REJECTS_RESOURCES",
                HttpStatusCode.UnprocessableEntity,
                "Sólo las preguntas inmersivas admiten recursos. Cambia el tipo de la pregunta " +
                    "a inmersiva antes de adjuntar material.",
            )
        }
    }

    fun listRecursos(preguntaId: String): List<AdminRecursoDto> =
        listRecursosRows(preguntaId).firmados()

    private fun listRecursosRows(preguntaId: String): List<AdminRecursoRow> =
        request(
            "GET",
            "/rest/v1/recursos_pregunta?select=*&pregunta_id=eq.$preguntaId&activo=eq.true&order=posicion.asc",
            null,
        ).decodeList()

    /**
     * Sube el ARCHIVO de un recurso y crea su fila (KQ4-2/KQ4-3).
     *
     * Es la única forma honesta de adjuntar material: hasta ahora el cliente mandaba
     * `urlExterna` con una ruta LOCAL de su disco (`C:\…\audio.mp3`), que se guardaba
     * tal cual y no existía para nadie más. El archivo viaja al bucket PRIVADO
     * `recursos-pregunta` y la app lo lee con una URL firmada que caduca.
     *
     * ### El orden importa, y el fallo también
     *
     * 1. El id del recurso lo trae el cliente (o se genera): es la clave de
     *    idempotencia. Si ya hay fila con ese id, se devuelve esa y NO se vuelve a
     *    subir — un reintento tras un timeout no duplica ni el objeto ni la fila.
     * 2. La ruta es `preguntas/<pregunta>/<recurso>.<ext>`, que el CHECK de Q3 exige.
     *    Como incluye el id del recurso, ningún otro recurso puede apuntar ahí, y por
     *    eso `x-upsert` es seguro: sólo puede pisar un intento anterior de ESTE mismo.
     * 3. Si la fila falla después de subir, **se borra el objeto**. Sin esto cada
     *    intento fallido dejaría un archivo pagando almacenamiento y sin dueño.
     *
     * El MIME lo decide la parte multipart y se valida contra [QUESTION_RESOURCE_MIME]:
     * la extensión sale de ahí y nunca del nombre del fichero del cliente.
     */
    fun addRecursoArchivo(
        preguntaId: String,
        recursoId: String,
        tipoRecurso: String,
        titulo: String?,
        descripcion: String?,
        posicion: Int?,
        mimeType: String,
        bytes: ByteArray,
    ): AdminRecursoDto {
        val tipo = tipoRecurso.trim().lowercase()
        val mime = mimeType.substringBefore(';').trim().lowercase()
        val extension = QUESTION_RESOURCE_MIME[tipo]?.get(mime)
            ?: throw domainError("QUESTION_RESOURCE_TYPE_INVALID")
        requireStoredQuestionAcceptsResources(preguntaId)
        findRecursoRow(preguntaId, recursoId)?.let { return listOf(it).firmados().first() }
        val objectPath = "preguntas/$preguntaId/$recursoId.$extension"
        transport.storagePut(QUESTION_RESOURCES_BUCKET, objectPath, bytes, mime, upsert = true)
        val saved = try {
            request(
                "POST", "/rest/v1/recursos_pregunta",
                buildJsonObject {
                    put("id", JsonPrimitive(recursoId))
                    put("pregunta_id", JsonPrimitive(preguntaId))
                    put("codigo_recurso", JsonPrimitive("R-" + shortId()))
                    put("tipo_recurso", JsonPrimitive(tipo))
                    put("titulo", titulo?.trim()?.ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("descripcion", descripcion?.trim()?.ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("bucket_storage", JsonPrimitive(QUESTION_RESOURCES_BUCKET))
                    put("ruta_storage", JsonPrimitive(objectPath))
                    // CHECK recursos_pregunta posicion > 0.
                    put("posicion", JsonPrimitive(posicion?.takeIf { it > 0 } ?: 1))
                    put(
                        "metadatos",
                        buildJsonObject {
                            put("mime", JsonPrimitive(mime))
                            put("bytes", JsonPrimitive(bytes.size))
                        },
                    )
                }.toString(),
                prefer = "return=representation",
            ).decodeList<AdminRecursoRow>().first()
        } catch (error: Exception) {
            // Un reintento simultáneo con el mismo id pudo confirmar la fila que apunta
            // a ESTE objeto: entonces el objeto tiene dueño y no se borra.
            runCatching { findRecursoRow(preguntaId, recursoId) }.getOrNull()
                ?.let { return listOf(it).firmados().first() }
            runCatching { transport.storageDelete(QUESTION_RESOURCES_BUCKET, objectPath) }
                .onFailure { PdfImportLogger.warn("Recurso huérfano sin limpiar {}: {}", objectPath, it::class.simpleName) }
            throw error
        }
        registrarAuditoria("recurso_pregunta_creado", "recursos_pregunta", saved.id)
        return listOf(saved).firmados().first()
    }

    private fun findRecursoRow(preguntaId: String, recursoId: String): AdminRecursoRow? =
        request(
            "GET",
            "/rest/v1/recursos_pregunta?select=*&id=eq.$recursoId&pregunta_id=eq.$preguntaId&limit=1",
            null,
        ).decodeList<AdminRecursoRow>().firstOrNull()

    /**
     * Firma las rutas de Storage de estos recursos: UNA petición de firma por lote.
     *
     * Un fallo al firmar NO rompe la lectura: `urlAcceso` queda a `null` (o al enlace
     * externo, si lo hay) y la pantalla enseña el recurso sin poder reproducirlo. La
     * alternativa —propagar— convertiría un problema de Storage en un 500 del detalle
     * de la pregunta, que es una pantalla entera por un icono.
     */
    private fun List<AdminRecursoRow>.firmados(): List<AdminRecursoDto> {
        val paths = mapNotNull { it.rutaStorage?.takeIf(String::isNotBlank) }.distinct()
        val firmas = if (paths.isEmpty()) {
            emptyMap()
        } else {
            runCatching {
                transport.storageSignedUrls(QUESTION_RESOURCES_BUCKET, paths, QUESTION_RESOURCE_URL_TTL_SECONDS)
            }.getOrElse { error ->
                PdfImportLogger.warn("Firma de recursos fallida: {}", error::class.simpleName)
                emptyMap()
            }
        }
        return map { row ->
            row.toDto().copy(
                rutaStorage = row.rutaStorage,
                mimeType = (row.metadatos["mime"] as? JsonPrimitive)?.contentOrNull,
                urlAcceso = row.rutaStorage?.let { firmas[it] } ?: row.urlExterna,
            )
        }
    }

    /**
     * Adjunta un recurso a una pregunta.
     *
     * ### La regla, y por qué vive en el servidor
     *
     * **Sólo una pregunta INMERSIVA puede tener recursos.** Hasta ahora esa regla
     * vivía únicamente en el cliente, y como decisión de PINTADO
     * (`questionShowsResources(type) = type == INMERSIVA`), que no es una
     * defensa: esta función no miraba el tipo de la pregunta —de hecho no leía la
     * pregunta en absoluto— así que cualquier llamada directa adjuntaba un audio
     * a una Ráfaga y el servidor respondía 201.
     *
     * Importa por dos motivos, y el segundo es el que cuesta dinero: el flujo de
     * Ráfaga de la app no tiene dónde pintar un recurso, así que se pagaría el
     * almacenamiento de un material que ningún opositor llega a ver.
     *
     * ### La regla ya NO tiene excepción (DEC-IMP-05)
     *
     * Existía un parámetro `enforceImmersiveOnly` para un único llamador:
     * `POST /rafagas/import` guardaba las etiquetas de cada pregunta como un
     * «recurso» de tipo `etiquetas` —un índice de búsqueda disfrazado de recurso—
     * sobre preguntas de Ráfaga. Esa ruta está retirada, así que la excepción se
     * retira con ella: una puerta abierta que nadie cruza sólo espera a que
     * alguien la cruce por error.
     *
     * ### Coste
     *
     * Una lectura extra de UNA columna. Desde KQ0-8 ésta es la ÚNICA puerta por la
     * que una pregunta gana un recurso: `updatePregunta` ya no recorre `recursos[]`,
     * así que la regla se comprueba siempre contra el tipo GUARDADO y no contra el
     * que traiga un parche.
     */
    fun addRecurso(preguntaId: String, input: AdminRecursoDto): AdminRecursoDto {
        require(input.tipoRecurso.isNotBlank()) { "El recurso necesita tipo." }
        val tipo = input.tipoRecurso.trim().lowercase()
        // Un `imagen` sin archivo es una fila que la app no puede pintar: lo que llegaba
        // por aquí era la RUTA LOCAL del disco del administrador metida en `url_externa`.
        if (tipo in QUESTION_RESOURCE_FILE_TYPES) throw domainError("QUESTION_RESOURCE_UPLOAD_REQUIRED")
        if (tipo == "enlace") {
            val url = input.urlExterna?.trim().orEmpty()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw domainError("QUESTION_RESOURCE_URL_INVALID")
            }
        }
        requireStoredQuestionAcceptsResources(preguntaId)
        val saved = request(
            "POST", "/rest/v1/recursos_pregunta",
            sbJson.encodeToString(
                listOf(
                    AdminRecursoRow(
                        id = UUID.randomUUID().toString(),
                        preguntaId = preguntaId,
                        codigoRecurso = "R-" + shortId(),
                        tipoRecurso = input.tipoRecurso,
                        titulo = input.titulo,
                        descripcion = input.descripcion,
                        urlExterna = input.urlExterna,
                        contenidoTexto = input.contenidoTexto,
                        // CHECK recursos_pregunta posicion > 0.
                        posicion = if (input.posicion > 0) input.posicion else 1,
                    ),
                ),
            ),
            prefer = "return=representation",
        ).decodeList<AdminRecursoRow>().let { listOf(it.first()).firmados().first() }
        registrarAuditoria("recurso_pregunta_creado", "recursos_pregunta", saved.id)
        return saved
    }

    /**
     * Edita un recurso. **Editar no es desarchivar, y no es reasignar.**
     *
     * ### El agujero que esto cierra
     *
     * Esto reconstruía la fila ENTERA con `AdminRecursoRow`, y `sbJson` tiene
     * `encodeDefaults = true`, así que cada edición mandaba `activo = true` sin
     * condición. Como `listRecursos` sólo lee `activo=eq.true`, archivar es lo
     * que hace desaparecer un recurso de una pregunta; y entonces, sobre una
     * pregunta de RÁFAGA: (1) el administrador archiva el recurso y deja de
     * verse; (2) cualquier PATCH posterior que incluya ese `id` en `recursos[]`
     * lo pone `activo = true` otra vez. **La pregunta no inmersiva ha ganado un
     * recurso visible**, que es exactamente lo que la regla de `updatePregunta`
     * dice que no puede pasar — y por la puerta que esa regla deja abierta a
     * propósito para poder editar los huérfanos.
     *
     * La línea de la regla era defendible; lo que la contradecía era esta
     * función. Se cierra aquí y no bloqueando la edición, porque bloquearla
     * dejaría los huérfanos sin arreglo posible.
     *
     * ### Qué viaja ahora
     *
     * Sólo los campos que [AdminRecursoDto] puede expresar, es decir lo que el
     * cliente puede editar de verdad. Lo demás se queda fuera a propósito:
     *
     *  · `activo` — el ciclo de archivado es de `archiveRecurso`, no de un PATCH
     *    de contenido. **Consecuencia declarada: hoy no hay forma de desarchivar
     *    un recurso.** Antes la había, pero era accidental y se saltaba la regla;
     *    si hace falta, es una capacidad propia con su ruta y su comprobación de
     *    tipo, no un efecto colateral de guardar un título.
     *  · `codigo_recurso` — se fabricaba como `"R-" + recursoId.take(8)` y
     *    SOBRESCRIBÍA el código real que la fila tuviera desde su alta, en cada
     *    edición, sin que nadie lo pidiera.
     *  · `pregunta_id` — ya va en el filtro; reescribirlo es lo que convertía un
     *    filtro truncado en un cambio de dueño, que es el daño que describe el
     *    comentario de `patch("/preguntas/{id}/recursos/{recursoId}")`.
     *  · `metadatos` — el default es `{}` y se estaba VACIANDO en cada edición.
     *  · `tipo_recurso`, `url_externa` y `ruta_storage` (KQ4-2) — el CHECK de Q3 ata
     *    el tipo a la UBICACIÓN del archivo: cambiar el tipo de un `audio` ya subido
     *    a `imagen` dejaría la fila apuntando a un `.mp3` que la app intentaría pintar.
     *    Para cambiar el archivo se sube otro; para cambiar el tipo, otro recurso.
     *
     * Un recurso que no existe, o que no es de esa pregunta, es un 404 y no un
     * 500 (misma corrección que E-2 en los exámenes).
     */
    fun updateRecurso(preguntaId: String, recursoId: String, input: AdminRecursoDto): AdminRecursoDto {
        val actual = findRecursoRow(preguntaId, recursoId) ?: throw domainError("QUESTION_RESOURCE_NOT_FOUND")
        return request(
            "PATCH",
            "/rest/v1/recursos_pregunta?id=eq.$recursoId&pregunta_id=eq.$preguntaId",
            sbJson.encodeToString(
                AdminRecursoPatch(
                    titulo = input.titulo,
                    descripcion = input.descripcion,
                    // El texto sólo lo edita el recurso que ES texto: en los demás, la
                    // fuente del contenido es el archivo del bucket.
                    contenidoTexto = input.contenidoTexto?.takeIf { actual.tipoRecurso.equals("texto", ignoreCase = true) },
                    posicion = if (input.posicion > 0) input.posicion else 1,
                ),
            ),
            prefer = "return=representation",
        ).decodeList<AdminRecursoRow>().firstOrNull()?.let { listOf(it).firmados().first() }
            ?: throw domainError("QUESTION_RESOURCE_NOT_FOUND")
    }

    fun archiveRecurso(preguntaId: String, recursoId: String): AdminRecursoDto {
        val saved = request(
            "PATCH",
            "/rest/v1/recursos_pregunta?id=eq.$recursoId&pregunta_id=eq.$preguntaId",
            """{"activo":false}""",
            prefer = "return=representation",
        ).decodeList<AdminRecursoRow>().firstOrNull()?.let { listOf(it).firmados().first() }
            ?: throw domainError("QUESTION_RESOURCE_NOT_FOUND")
        registrarAuditoria("recurso_pregunta_archivado", "recursos_pregunta", recursoId)
        return saved
    }

    private fun AdminPreguntaRow.toDtoSinDetalle() = AdminPreguntaDto(
        id = id.orEmpty(),
        codigoPregunta = codigoPregunta,
        tipoPregunta = tipoPregunta,
        titulo = titulo,
        // NULL en la base → texto vacío en la respuesta (KQ0-2): el contrato de
        // lectura no cambia de forma, y un NULL ya no tumba el listado.
        fuenteTexto = fuenteTexto.orEmpty(),
        dificultad = dificultad.orEmpty(),
        activa = activa,
    )

    // IMPORTACIONES (lote + staging -> importar)
    /**
     * El historial de lotes, con su [AdminLoteDto.resumen].
     *
     * Antes cada lote salia con `staging = []` y el cliente derivaba el resumen de esa lista, asi que el
     * historial ensenaba SIEMPRE 0/0. Ahora el recuento por estado se pide al servidor
     * ([resumenesDeLotes]); si no se puede calcular, `resumen = null` y la pantalla lo dice: un 0
     * inventado seria peor que no decir nada.
     */
    fun listLotes(): List<AdminLoteDto> {
        val lotes = request("GET", "/rest/v1/lotes_importacion_preguntas?select=*&order=creado_en.desc", null)
            .decodeList<LoteRow>()
        val resumenes = resumenesDeLotes(lotes.mapNotNull { it.id })
        return lotes.map { lote -> lote.toDto(emptyList()).copy(resumen = lote.id?.let { resumenes?.get(it) }) }
    }

    /**
     * Recuento por estado de las filas de staging de [loteIds], o `null` si no se pudo calcular.
     *
     * Lee SOLO `lote_id,estado_parseo`, en bloques de [LOTE_SUMMARY_IDS_PER_REQUEST] lotes y PAGINANDO
     * hasta llegar al total que dice `Content-Range` (`getPage` lo exige): PostgREST corta en silencio en
     * su `max-rows`, y un recuento sobre la primera pagina seria un resumen falso sin ningun error.
     * Un lote sin filas devuelve ceros de verdad (se leyo y no hay nada).
     */
    private fun resumenesDeLotes(loteIds: List<String>): Map<String, AdminLoteResumenDto>? {
        val ids = loteIds.mapNotNull { runCatching { it.validatedUuid("loteId") }.getOrNull() }
        if (ids.isEmpty()) return emptyMap()
        return try {
            val estados = HashMap<String, MutableList<String>>()
            ids.chunked(LOTE_SUMMARY_IDS_PER_REQUEST).forEach { chunk ->
                var offset = 0
                while (true) {
                    val page = transport.getPage(
                        "/rest/v1/preguntas_parseadas_staging?select=lote_id,estado_parseo" +
                            "&lote_id=in.(${chunk.joinToString(",")})&order=lote_id.asc,posicion.asc" +
                            "&limit=$LOTE_SUMMARY_PAGE_SIZE&offset=$offset",
                    )
                    val rows = page.body.decodeList<StagingEstadoRow>()
                    rows.forEach { estados.getOrPut(it.loteId) { mutableListOf() } += it.estadoParseo }
                    offset += rows.size
                    if (rows.isEmpty() || offset >= page.total) break
                }
            }
            ids.associateWith { id -> resumenDeEstados(estados[id].orEmpty()) }
        } catch (error: Exception) {
            PdfImportLogger.warn("Resumen de lotes no calculable: {}", error::class.simpleName)
            null
        }
    }

    private fun resumenDeEstados(estados: List<String>) = AdminLoteResumenDto(
        listas = estados.count { it == "ok" || it == "aprobada" },
        conError = estados.count { it == "error" },
        advertencias = estados.count { it == "advertencia" },
        omitidas = estados.count { it == "omitida" },
        importadas = estados.count { it == "importada" },
    )

    /**
     * El lote con sus filas y, si todavía no está publicado, los duplicados
     * detectados (KI3-2).
     *
     * La detección corre en la base (`admin_import_lote_duplicados`) porque compara
     * cada fila contra el banco entero y contra el resto del lote: traerse eso a
     * Kotlin serían dos consultas sin índice y una comparación de texto por fila.
     *
     * Si la RPC no está aprovisionada, [AdminLoteDto.duplicadosComprobados] viaja
     * `false` y las filas van SIN `duplicado`. La pantalla dice «no se ha podido
     * comprobar». Lo que no puede pasar es que un lote sin comprobar se vea igual
     * que un lote comprobado y limpio: el revisor publicaría repetidas creyendo que
     * alguien lo miró.
     */
    fun getLote(loteId: String): AdminLoteDto? {
        val lote = request("GET", "/rest/v1/lotes_importacion_preguntas?select=*&id=eq.$loteId&limit=1", null)
            .decodeList<LoteRow>().firstOrNull() ?: return null
        val staging = request("GET", "/rest/v1/preguntas_parseadas_staging?select=*&lote_id=eq.$loteId&order=posicion.asc", null)
            .decodeList<StagingRow>()
        val duplicados: Map<String, AdminImportDuplicadoDto>? =
            if (lote.estado == "importado") emptyMap() else duplicadosDeLote(loteId)
        return lote.toDto(
            staging = staging.map { row -> row.toDto().copy(duplicado = duplicados?.get(row.id.orEmpty())) },
            duplicadosComprobados = lote.estado == "importado" || duplicados != null,
            // Derivado de las filas leidas; si faltan filas (max-rows), no se afirma nada.
            resumen = resumenDeEstados(staging.map { it.estadoParseo }).takeIf { staging.size >= lote.totalDetectadas },
        )
    }

    /**
     * Duplicados por `staging_id`, o `null` si la RPC todavía no existe.
     *
     * `null` y mapa vacío significan cosas distintas —«no se ha mirado» y «no
     * hay»— y por eso el tipo es anulable: colapsarlos es exactamente el error
     * que [getLote] evita.
     */
    private fun duplicadosDeLote(loteId: String): Map<String, AdminImportDuplicadoDto>? {
        val body = try {
            request(
                "POST",
                "/rest/v1/rpc/admin_import_lote_duplicados",
                buildJsonObject { put("p_lote_id", JsonPrimitive(loteId)) }.toString(),
            )
        } catch (error: Exception) {
            if (error.saysWriteRpcIsMissing()) return null
            throw error
        }
        // Una fila de staging puede casar con varias: se queda la PRIMERA, que es la
        // que la RPC ordena como mejor candidata. Enseñar varias no ayuda a decidir.
        return body.decodeList<AdminImportDuplicadoRow>()
            .groupBy { it.stagingId }
            .mapValues { (_, filas) ->
                val fila = filas.first()
                AdminImportDuplicadoDto(
                    origen = fila.origen,
                    preguntaId = fila.preguntaId,
                    codigoPregunta = fila.codigoPregunta.orEmpty(),
                    stagingOriginalId = fila.stagingOriginalId,
                )
            }
    }

    /**
     * Crea el lote y sus filas de staging, con el tipo del EXAMEN y compensando si la
     * segunda escritura falla (KI0-3).
     *
     * Tres cosas que antes no hacía, y las tres dejaban basura o trabajo perdido:
     *
     * - **El tipo lo decide el examen.** El cliente mandaba `tipoExamen` y se escribía
     *   tal cual, así que un lote podía declararse `razonado` sobre un examen de
     *   ráfaga; la publicación fallaría mucho después, con el PDF ya subido y revisado.
     *   Ahora, si no coinciden, es un 422 antes de escribir nada.
     * - **`respuesta_correcta` se sanea.** El CHECK vivo exige A–D o NULL, y el parser
     *   deja `""` (o una letra inventada) justo en el bloque con error, que es el caso
     *   más común. Ese INSERT fallaba DESPUÉS del lote y dejaba un lote huérfano con
     *   `total_detectadas = N` y cero filas: el error más frecuente del PDF rompía
     *   precisamente la revisión que existe para arreglarlo.
     * - **Se valida TODO antes de escribir.** Las filas de staging se construyen (y por tanto se
     *   validan: motivo sin anulada, estado desconocido…) antes del INSERT del lote; si alguna
     *   lanza, no se ha escrito nada y no queda lote huérfano.
     * - **Se compensa.** Si el INSERT de staging falla igualmente, se borra el lote
     *   (las filas caen por FK). Un lote vacío en el historial no se puede publicar ni
     *   corregir, sólo confundir.
     *
     * [actorId] es el `sub` del administrador (D-11): `subido_por` no se escribía nunca
     * y ningún lote tenía autor.
     */
    fun crearLote(req: CrearLoteRequest, actorId: String? = null): AdminLoteDto {
        require(req.examenId.isNotBlank()) { "El lote necesita examen." }
        // Quinto identificador de cliente de esta vertical. Este NO compone hoy
        // ningún filtro —va como valor a la columna `examen_id` del INSERT— así
        // que no entra por la regla de arriba; se valida porque es una clave
        // ajena que luego SÍ acaba en filtros y porque un lote con un `examen_id`
        // que no es un uuid es una fila que nadie podrá importar nunca.
        val examenId = req.examenId.validatedUuid("examenId")
        val examen = getExamen(examenId) ?: throw domainError("IMPORT_EXAM_NOT_FOUND")
        val tipo = canonicalExamType(examen.tipoExamen)
        if (req.tipoExamen.isNotBlank() && canonicalExamType(req.tipoExamen) != tipo) {
            throw domainError("IMPORT_TYPE_MISMATCH")
        }
        val loteId = UUID.randomUUID().toString()
        // Se construyen y VALIDAN todas las filas de staging ANTES de escribir nada: una fila que
        // lanza (motivo sin anulada, estado desconocido...) no puede dejar un lote huérfano ya insertado.
        val rows = req.preguntas.mapIndexed { i, p ->
            StagingRow(
                id = UUID.randomUUID().toString(),
                loteId = loteId,
                posicion = if (p.posicion > 0) p.posicion else i + 1,
                bloqueOriginal = p.bloqueOriginal.ifBlank { p.titulo },
                titulo = p.titulo,
                opcionA = p.opcionA,
                opcionB = p.opcionB,
                opcionC = p.opcionC,
                opcionD = p.opcionD,
                // NULL si no es una de las cuatro letras: el CHECK vivo sólo admite
                // A–D o NULL, y la fila que trae una respuesta ilegible es justo la
                // que tiene que llegar a revisión.
                respuestaCorrecta = p.respuestaCorrecta.trim().uppercase().takeIf { it in RAFAGA_OPTION_LETTERS },
                razonamiento = p.razonamiento,
                fuenteTexto = p.fuenteTexto,
                etiquetaTexto = p.etiquetaTexto,
                // Ya no es siempre `"ok"`: `crearLoteDesdePdf` manda ahora
                // filas en `"error"` o `"advertencia"` para que exista la etapa
                // de revisión. Un estado en blanco sigue significando `"ok"`.
                estadoParseo = requireKnownStagingState(p.estadoParseo.ifBlank { "ok" }),
                errorParseo = p.errorParseo?.takeIf { it.isNotBlank() },
                anulada = p.anulada,
                // Una fila `error` puede traer un motivo ilegible (sin ESTADO: ANULADA, o de mas de 500):
                // viaja al staging SIN el, que sigue en `bloque_original` y en `error_parseo`. Romper aqui el
                // INSERT tiraria el lote entero por la errata de una sola fila. Las demas filas son estrictas.
                motivoAnulacion = if (p.estadoParseo == "error") {
                    runCatching { normalizedAnnulmentReason(p.anulada, p.motivoAnulacion) }.getOrNull()
                } else {
                    normalizedAnnulmentReason(p.anulada, p.motivoAnulacion)
                },
            )
        }
        request(
            "POST", "/rest/v1/lotes_importacion_preguntas",
            sbJson.encodeToString(
                listOf(
                    LoteRow(
                        id = loteId,
                        examenId = examenId,
                        nombreArchivoOriginal = req.nombreArchivo.ifBlank { "importacion.pdf" },
                        tipoExamen = tipo,
                        estado = "parseado",
                        totalDetectadas = req.preguntas.size,
                        subidoPor = actorId,
                    ),
                ),
            ),
            prefer = "return=minimal",
        )
        if (req.preguntas.isNotEmpty()) {
            try {
                request("POST", "/rest/v1/preguntas_parseadas_staging", sbJsonBulk.encodeToString(rows), prefer = "return=minimal")
            } catch (error: Exception) {
                runCatching {
                    request("DELETE", "/rest/v1/lotes_importacion_preguntas?id=eq.$loteId", null, prefer = "return=minimal")
                }.onFailure { PdfImportLogger.warn("Lote huérfano sin compensar {}: {}", loteId, it::class.simpleName) }
                throw error
            }
        }
        return getLote(loteId) ?: error("No se pudo leer el lote creado.")
    }

    fun crearLoteDesdePdf(
        examenId: String,
        tipoExamen: String,
        nombreArchivo: String,
        pdfBytes: ByteArray,
        actorId: String? = null,
    ): AdminLoteDto {
        // F-05: los rechazos DEL PDF son `PdfImportRejectedException`, con un
        // mensaje escrito aquí para el administrador. Cualquier otra cosa —un
        // fallo de Supabase, de red— sigue su camino normal hasta StatusPages y
        // se redacta. Antes todo era `error(...)` y la ruta respondía 422 con el
        // `message` de lo que fuera, incluido el cuerpo crudo de PostgREST.
        val examen = getExamen(examenId) ?: throw PdfImportRejectedException("El examen del lote ya no existe.")
        val rawText = try {
            extractTextFromPdf(pdfBytes)
        } catch (unreadable: Exception) {
            // Aquí sólo corre PDFBox: cualquier excepción es "este PDF no se puede
            // leer" (normalmente `IOException`, pero un PDF dañado también saca
            // `RuntimeException`s de sus parsers). Su texto —"Header doesn't
            // contain versioninfo", offsets…— va al log con el request_id del MDC,
            // no al administrador.
            PdfImportLogger.warn(
                "PDF ilegible al importar ({}): {}",
                unreadable::class.simpleName,
                unreadable.redactedMessage("PDF ilegible", 200),
            )
            throw PdfImportRejectedException("No se pudo leer el PDF. Comprueba que el archivo es un PDF válido y no está dañado.")
        }
        if (rawText.isBlank()) {
            throw PdfImportRejectedException("El PDF no contiene texto seleccionable. No se admite OCR en este flujo.")
        }
        // UNA ERRATA EN LA PREGUNTA 297 NO PUEDE TIRAR LAS OTRAS 299.
        //
        // Esto era `if (errores.isNotEmpty()) error(...)`, y esa línea sola
        // vaciaba de sentido la vertical entera: si a staging no llega nunca una
        // fila mala, todas nacen `ok` y **la etapa de revisión que la pantalla
        // promete no puede existir**. El administrador tenía dos salidas —editar
        // el PDF a ciegas y volver a subirlo, o rendirse— y la primera crea un
        // lote duplicado.
        //
        // Ahora las filas con error llegan MARCADAS (`estado_parseo = "error"`,
        // con `error_parseo` explicando qué le falta), que es justamente lo que
        // hace falta para que haya algo que corregir. `updateStaging` ya existe
        // para corregirlas y para pasarlas a `aprobada`.
        //
        // Vocabulario: se usa `error`, que el backend YA escribe en esta columna.
        // No se inventa ningún valor nuevo a propósito — el CHECK real de
        // `estado_parseo` es desconocido (0 filas en MAIN) y un valor inventado
        // rompería la inserción del lote entero, que es peor que el defecto.
        val parsed = parseQuestionBlocks(rawText, canonicalQuestionType(examen.tipoExamen))
        if (parsed.preguntas.isEmpty() && parsed.bloquesConError.isEmpty()) {
            // Lo único que sigue siendo un rechazo total: no se reconoció NI UN
            // bloque. Un lote de cero filas no es revisable, es un PDF que no
            // tiene el formato de este importador.
            throw PdfImportRejectedException(
                "El PDF no contiene ningun bloque de pregunta reconocible. Revisa el separador de preguntas.",
            )
        }
        val filas = (
            parsed.preguntas.map { p ->
                AdminStagingItemDto(
                    posicion = p.numero,
                    // El bloque CRUDO, no una copia del título. Sin esto la
                    // revisión humana no puede comparar con lo que decía el PDF.
                    bloqueOriginal = p.bloqueOriginal.ifBlank { p.titulo },
                    titulo = p.titulo,
                    opcionA = p.opciones.getOrNull(0)?.second.orEmpty(),
                    opcionB = p.opciones.getOrNull(1)?.second.orEmpty(),
                    opcionC = p.opciones.getOrNull(2)?.second.orEmpty(),
                    opcionD = p.opciones.getOrNull(3)?.second.orEmpty(),
                    respuestaCorrecta = p.respuesta,
                    razonamiento = p.razonamiento,
                    fuenteTexto = p.fuente,
                    etiquetaTexto = p.etiquetas.joinToString(","),
                    // Una advertencia NO bloquea la publicación, pero la fila tiene que
                    // verse distinta de una limpia: es lo único que avisa de que la
                    // explicación de una Ráfaga no se va a publicar.
                    estadoParseo = if (p.advertencia != null) "advertencia" else "ok",
                    errorParseo = p.advertencia,
                    // La anulacion es ortogonal al estado de parseo: una anulada valida es `ok`.
                    anulada = p.anulada,
                    motivoAnulacion = p.motivoAnulacion,
                )
            } + parsed.bloquesConError.map { b ->
                AdminStagingItemDto(
                    posicion = b.numero,
                    bloqueOriginal = b.bloqueOriginal,
                    titulo = b.titulo,
                    opcionA = b.opcionA,
                    opcionB = b.opcionB,
                    opcionC = b.opcionC,
                    opcionD = b.opcionD,
                    respuestaCorrecta = b.respuesta,
                    razonamiento = b.razonamiento,
                    fuenteTexto = b.fuente,
                    etiquetaTexto = b.etiquetas,
                    estadoParseo = "error",
                    errorParseo = b.detalle,
                    anulada = b.anulada,
                    motivoAnulacion = b.motivoAnulacion,
                )
            }
            ).sortedBy { it.posicion }
        return crearLote(
            CrearLoteRequest(
                examenId = examenId,
                nombreArchivo = nombreArchivo,
                // El tipo lo decide `crearLote` leyendo el examen; aquí se manda el que
                // llegó para que, si no coincide, salga el 422 y no un lote mal tipado.
                tipoExamen = tipoExamen,
                preguntas = filas,
            ),
            actorId = actorId,
        )
    }

    /**
     * Guarda la revisión humana de una fila de staging (KI0-4, KI2).
     *
     * ### Aprobar u omitir BORRA el diagnóstico
     *
     * `error_parseo` no estaba en el parche, así que una fila que el administrador
     * acababa de corregir seguía enseñando «falta la opción D» para siempre, y la
     * lista de revisión no distinguía lo arreglado de lo pendiente. Se manda
     * explícitamente a `null` al aprobar u omitir — el único momento en que ese texto
     * deja de ser cierto— y en cualquier otro estado no se toca.
     *
     * ### `vincularPreguntaId` exige `aprobada`
     *
     * Vincular es una forma de aprobar la fila («esta pregunta ya existe, usa ésa»).
     * Con cualquier otro estado el puntero quedaría guardado sin que nadie lo use, y
     * una fila omitida con destino de vínculo es una contradicción que la RPC tendría
     * que resolver adivinando.
     *
     * Por la misma razón, OMITIR una fila escribe `vincular_pregunta_id = NULL` explícito
     * (igual que aprobar sin vínculo): el cliente envía `vincularPreguntaId: null` y afirma que
     * el puntero se borra; antes quedaba «no tocar» y la fila omitida conservaba el vínculo
     * de una aprobación anterior. Con `ok`/`error`/`advertencia` el puntero no se toca.
     *
     * ### El PATCH reemplaza la fila ENTERA (decisión aceptada)
     *
     * Un cliente antiguo que omita `anulada` desanula la fila; el cliente actual manda siempre
     * `anulada`. Orden de despliegue: MIGRACIÓN M10 PRIMERO en MAIN y después Ktor.
     */
    fun updateStaging(id: String, item: AdminStagingItemDto): AdminStagingItemDto {
        // Dos de los estados los elige el CLIENTE (`aprobada`, `omitida`) y esto los
        // pasaba sin mirar: cualquier cadena acababa en la columna. El conjunto válido
        // vive ahora en el servidor, que es de quien tiene que ser.
        val estado = requireKnownStagingState(item.estadoParseo.ifBlank { "ok" })
        val decidida = estado == "aprobada" || estado == "omitida"
        val respuesta = item.respuestaCorrecta.trim().uppercase()
        require(respuesta.isEmpty() || respuesta in RAFAGA_OPTION_LETTERS) {
            "La respuesta correcta tiene que ser A, B, C o D."
        }
        val vincular = item.vincularPreguntaId?.takeIf { it.isNotBlank() }?.validatedUuid("vincularPreguntaId")
        val motivoAnulacion = normalizedAnnulmentReason(item.anulada, item.motivoAnulacion)
        require(vincular == null || estado == "aprobada") {
            "Para vincular con una pregunta existente, la fila tiene que quedar aprobada."
        }
        val patch = StagingPatch(
            titulo = item.titulo,
            opcionA = item.opcionA,
            opcionB = item.opcionB,
            opcionC = item.opcionC,
            opcionD = item.opcionD,
            // Vacía = NULL, no `""`: el CHECK vivo exige A–D o NULL.
            respuestaCorrecta = respuesta.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull,
            razonamiento = item.razonamiento,
            fuenteTexto = item.fuenteTexto,
            etiquetaTexto = item.etiquetaTexto,
            estadoParseo = estado,
            errorParseo = if (decidida) JsonNull else null,
            // Ausente = no se toca; `null` explícito = deshacer el vínculo elegido. Aprobar sin
            // vínculo y OMITIR son decisiones finales: ambas borran el puntero (una fila omitida
            // con el vínculo de cuando estaba aprobada sería la contradicción que el KDoc prohíbe).
            vincularPreguntaId = when {
                vincular != null -> JsonPrimitive(vincular)
                decidida -> JsonNull
                else -> null
            },
            // La fila se reemplaza entera: anulada siempre viaja, y el motivo con ella (`null` lo borra).
            anulada = item.anulada,
            motivoAnulacion = motivoAnulacion?.let { JsonPrimitive(it) } ?: JsonNull,
        )
        return request(
            "PATCH", "/rest/v1/preguntas_parseadas_staging?id=eq.$id",
            patch.toJson().toString(),
            prefer = "return=representation",
        ).decodeList<StagingRow>().firstOrNull()?.toDto()
            ?: throw domainError("IMPORT_STAGING_NOT_FOUND")
    }

    /**
     * Publica un lote entero con UNA llamada a `admin_import_lote_preguntas` (IM3, KI3-1).
     *
     * ### Lo que esto sustituye, que no era un detalle de eficiencia
     *
     * Era un bucle por fila: leer el lote, crear cada pregunta por HTTP, marcar la
     * fila, y al final calcular un estado de lote en Kotlin. Todos sus problemas
     * venían de lo mismo —publicar no era una operación, eran N—:
     *
     * - **Duplicaba.** No tomaba posesión del lote, así que dos clics en «Importar»
     *   creaban todas las preguntas dos veces; el propio KDoc anterior lo admitía.
     * - **Dejaba el lote a medias.** Un fallo en la fila 150 dejaba 149 preguntas
     *   publicadas y el lote en un estado (`parcial`) que el CHECK de la base ni
     *   siquiera admite.
     * - **Mentía sobre la causa.** Un fallo de infraestructura marcaba las 300 filas
     *   como `error` con el cuerpo crudo de PostgREST dentro, y mandaba al
     *   administrador a revisar 300 filas correctas.
     * - **No cabía en el tiempo.** ~7 peticiones por fila contra un
     *   `statement_timeout` de 8 s por sentencia y un cliente esperando 300 s.
     *
     * Ahora la transacción es de la base: o el lote queda `importado` con sus
     * preguntas creadas y vinculadas, o no cambia NADA. Kotlin ya no escribe en
     * `preguntas_parseadas_staging` ni en `lotes_importacion_preguntas`.
     *
     * ### Idempotencia
     *
     * La RPC toma posesión del lote (`estado`), así que una segunda llamada
     * responde `ejecutado = false` sin escribir. Sólo se audita cuando de verdad se
     * ejecutó: un apunte «lote importado» por cada reintento haría inútil la pista.
     *
     * [confirmarExamenPublicado] es la respuesta del administrador a
     * `IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED` (las preguntas se verían al
     * instante), y viaja sólo porque él lo confirmó en un diálogo.
     */
    fun importarLote(
        loteId: String,
        confirmarExamenPublicado: Boolean = false,
        actorId: String? = null,
    ): AdminLoteImportResponse {
        val body = rpcOrUnavailable(
            "admin_import_lote_preguntas",
            sbJson.encodeToString(AdminImportLoteRpc(loteId, confirmarExamenPublicado, actorId)),
            "IMPORT_WRITE_RPC_NOT_PROVISIONED",
        )
        val resultado = json.decodeFromString<AdminImportLoteResultRow>(body)
        if (resultado.ejecutado) registrarAuditoria("lote_importado", "lotes_importacion_preguntas", loteId)
        val lote = getLote(loteId) ?: throw domainError("IMPORT_LOTE_NOT_FOUND")
        return AdminLoteImportResponse(
            data = lote,
            resultado = AdminImportResultadoDto(
                ejecutado = resultado.ejecutado,
                nuevas = resultado.nuevas,
                vinculadas = resultado.vinculadas,
                omitidas = resultado.omitidas,
                totalImportadas = resultado.totalImportadas,
                anuladas = resultado.anuladas,
            ),
        )
    }

    private fun canonicalExamType(value: String): String = when (value.lowercase()) {
        "razonado", "guiado" -> "razonado"
        "inmersivo" -> "inmersivo"
        else -> "rafaga"
    }

    private fun canonicalQuestionType(value: String): String = when (canonicalExamType(value)) {
        "razonado" -> "razonada"
        "inmersivo" -> "inmersiva"
        else -> "rafaga"
    }

    private fun LoteRow.toDto(
        staging: List<AdminStagingItemDto>,
        duplicadosComprobados: Boolean = true,
        resumen: AdminLoteResumenDto? = null,
    ) = AdminLoteDto(
        id = id.orEmpty(),
        examenId = examenId,
        nombreArchivo = nombreArchivoOriginal,
        tipoExamen = tipoExamen,
        estado = estado,
        totalDetectadas = totalDetectadas,
        totalImportadas = totalImportadas,
        staging = staging,
        duplicadosComprobados = duplicadosComprobados,
        resumen = resumen,
    )

    // FEEDBACK + AUDITORIA
    /**
     * Listado paginado (keyset, nunca OFFSET) de feedback de preguntas — la
     * fuente real de "incidencias de OposiTester" (pantalla móvil OposiTester):
     * el endpoint original devolvía TODAS las filas sin límite; [cursor]/[limit]
     * son aditivos y opcionales, así que una llamada sin ellos sigue
     * funcionando (ahora acotada a [limit], nunca "todas" — ninguna llamada
     * real dependía de recibir el histórico completo, solo el chequeo de
     * conectividad que ignora el cuerpo). Resuelve el nombre de autor en un
     * único batch (sin N+1).
     */
    fun listFeedback(estado: String?, tipoFeedback: String? = null, cursor: String? = null, limit: Int = 20): AdminFeedbacksResponse {
        // `estado` es el UNICO de los tres parametros que entraba crudo:
        // `tipoFeedback` ya pasaba por `urlEncode()` y el `cursor` lo valida
        // `decodeFeedbackCursor` (exige un Instant y un UUID). Con
        // `?estado=pendiente#` la peticion salia SIN `order` y sin `limit` —
        // `URI.create` corta en el `#` y todo lo que va detras viaja como
        // fragmento, que no llega al upstream—, es decir `feedback_pregunta`
        // COMPLETA a una peticion de distancia y sin nada raro en el log.
        //
        // Se CODIFICA en vez de validar contra una lista cerrada a proposito: el
        // vocabulario real de `feedback_pregunta.estado` no esta verificado
        // contra MAIN (`resolverFeedback` conoce tres valores de ESCRITURA
        // —resuelto, descartado, revisado— pero el cliente FILTRA ademas por
        // `pendiente`, que nadie escribe desde aqui). Una lista blanca inventada
        // aqui rechazaria filtros legitimos; codificar cierra el vector entero
        // —`#`, `&`, `,`, parentesis— sin decidir un vocabulario que no es mio.
        val filtro = if (estado.isNullOrBlank()) "" else "&estado=eq.${estado.urlEncode()}"
        val tipoFiltro = if (tipoFeedback.isNullOrBlank()) "" else "&tipo_feedback=eq.${tipoFeedback.urlEncode()}"
        val cursorFilter = cursor?.let { raw ->
            val (creadoEn, id) = decodeFeedbackCursor(raw)
            "&or=(creado_en.lt.${creadoEn.urlEncode()},and(creado_en.eq.${creadoEn.urlEncode()},id.lt.$id))"
        }.orEmpty()

        val rows = request(
            "GET",
            "/rest/v1/feedback_pregunta?select=*$filtro$tipoFiltro$cursorFilter&order=creado_en.desc,id.desc&limit=${limit + 1}",
            null,
        ).decodeList<FeedbackRow>()

        val hasMore = rows.size > limit
        val page = rows.take(limit)
        if (page.isEmpty()) return AdminFeedbacksResponse(data = emptyList(), nextCursor = null)
        val nextCursor = if (hasMore) {
            val last = page.last()
            encodeFeedbackCursor(last.creadoEn.orEmpty(), last.id.orEmpty())
        } else {
            null
        }

        val authorNames = resolveUserDisplayNames(page.mapNotNull { it.usuarioId })
        return AdminFeedbacksResponse(
            data = page.map { row -> row.toDto(autorNombre = row.usuarioId?.let { authorNames[it] }.orEmpty()) },
            nextCursor = nextCursor,
        )
    }

    fun resolverFeedback(id: String, req: ResolverFeedbackRequest): AdminFeedbackDto {
        val estado = req.estado.ifBlank { "resuelto" }
        require(estado in setOf("resuelto", "descartado", "revisado")) {
            "estado debe ser uno de: resuelto, descartado, revisado."
        }
        val body = if (req.nota.isBlank()) {
            """{"estado":"$estado"}"""
        } else {
            """{"estado":"$estado","descripcion":${sbJson.encodeToString(req.nota)}}"""
        }
        val saved = request(
            "PATCH", "/rest/v1/feedback_pregunta?id=eq.$id", body, prefer = "return=representation",
        ).decodeList<FeedbackRow>().firstOrNull() ?: error("Feedback no encontrado.")
        val autorNombre = saved.usuarioId?.let { resolveUserDisplayNames(listOf(it))[it] }.orEmpty()
        registrarAuditoria("feedback_$estado", "feedback_pregunta", id)
        logStrategicEvent(
            StrategicEvent.FEEDBACK_RESOLVED,
            operation = "resolver_feedback",
            result = estado,
            entityType = "feedback_pregunta",
            entityId = id,
        )
        return saved.toDto(autorNombre)
    }

    /** Detalle real de una incidencia para Screen 07: la fila de
     * feedback_pregunta más el contexto de su pregunta asociada (código
     * humano y título), sin N+1 (esto es un detalle de un único id, no un
     * listado). [pregunta] es null si la pregunta ya no existe. La pregunta no
     * tiene especialidad propia (M8), así que `especialidadNombre` va siempre null. */
    fun getFeedbackDetail(id: String): AdminFeedbackDetailDto? {
        val row = request("GET", "/rest/v1/feedback_pregunta?select=*&id=eq.$id&limit=1", null)
            .decodeList<FeedbackRow>().firstOrNull() ?: return null
        val autorNombre = row.usuarioId?.let { resolveUserDisplayNames(listOf(it))[it] }.orEmpty()
        val pregunta = runCatching { getPregunta(row.preguntaId) }.getOrNull()
        // M8: la pregunta no tiene especialidad, así que el contexto ya no la lleva.
        return buildFeedbackDetailDto(row, autorNombre, pregunta, especialidadNombre = null)
    }

    // USUARIOS (perfiles_usuario + roles_usuario)
    /**
     * Página de usuarios.
     *
     * Antes traía `limit=200` fijo y, para enriquecerlos, pedía **todas** las
     * filas de `roles_usuario` y `carteras_usuario` sin filtro: con cien mil
     * usuarios, mostrar los primeros veinte descargaba doscientas mil filas. Ahora
     * el enriquecimiento se limita a los ids de la página con `in.(...)`, así que
     * el coste depende del tamaño de página y no del tamaño de la tabla.
     *
     * El total se pide aparte con un conteo exacto para que la UI pueda paginar
     * de verdad en lugar de adivinar si hay más.
     */
    fun listUsuarios(limit: Int?, offset: Int?): AdminUsersPage {
        val page = AdminPageRequest.of(limit, offset)
        val perfiles = request(
            "GET",
            "/rest/v1/perfiles_usuario?select=*&order=creado_en.desc" +
                "&limit=${page.limit}&offset=${page.offset}",
            null,
        ).decodeList<PerfilUsuarioRow>()

        val total = runCatching { exactCount("/rest/v1/perfiles_usuario?select=usuario_id") }.getOrNull()

        if (perfiles.isEmpty()) {
            return AdminUsersPage(data = emptyList(), total = total, limit = page.limit, offset = page.offset)
        }

        // Sólo los usuarios de esta página: el filtro es lo que evita el escaneo
        // completo de las tablas de roles y carteras.
        val ids = perfiles.joinToString(",") { it.usuarioId }
        val rolesByUser = request("GET", "/rest/v1/roles_usuario?select=*&usuario_id=in.($ids)", null)
            .decodeList<RolUsuarioRow>()
            .groupBy({ it.usuarioId }, { it.rolId })
        val balanceByUser = request(
            "GET",
            "/rest/v1/carteras_usuario?select=usuario_id,saldo_tickets&usuario_id=in.($ids)",
            null,
        ).decodeList<CarteraRow>().associate { it.usuarioId to it.saldoTickets }

        return AdminUsersPage(
            data = perfiles.map { p ->
                AdminV1UserDto(
                    id = p.usuarioId,
                    fullName = p.nombreMostrar.orEmpty(),
                    email = p.username.orEmpty(),
                    roleIds = rolesByUser[p.usuarioId].orEmpty(),
                    isActive = !p.esInvitado,
                    createdAt = p.creadoEn.orEmpty(),
                    ticketsBalance = balanceByUser[p.usuarioId] ?: 0,
                )
            },
            total = total,
            limit = page.limit,
            offset = page.offset,
        )
    }

    fun getUsuario(usuarioId: String): AdminV1UserDto? {
        // El identificador se interpola en la consulta de PostgREST: sin validar,
        // un segmento con `&` inyecta parámetros propios (`select`, `order`,
        // filtros). `requiredPath` sólo comprueba que no venga vacío.
        usuarioId.validatedUuid("usuarioId")
        val perfil = request("GET", "/rest/v1/perfiles_usuario?select=*&usuario_id=eq.$usuarioId&limit=1", null)
            .decodeList<PerfilUsuarioRow>().firstOrNull() ?: return null
        val roleIds = request("GET", "/rest/v1/roles_usuario?select=rol_id&usuario_id=eq.$usuarioId", null)
            .decodeList<RolUsuarioRow>().map { it.rolId }
        return AdminV1UserDto(
            id = perfil.usuarioId,
            fullName = perfil.nombreMostrar.orEmpty(),
            email = perfil.username.orEmpty(),
            roleIds = roleIds,
            isActive = !perfil.esInvitado,
            createdAt = perfil.creadoEn.orEmpty(),
        )
    }

    fun updateUsuario(usuarioId: String, input: AdminV1UserDto): AdminV1UserDto {
        // Ver `getUsuario`: se interpola en la URL de PostgREST, y aquí además en
        // un PATCH que escribe.
        usuarioId.validatedUuid("usuarioId")
        request(
            "PATCH",
            "/rest/v1/perfiles_usuario?usuario_id=eq.$usuarioId",
            """{"nombre_mostrar":${kotlinx.serialization.json.Json.encodeToString(input.fullName)}}""",
            prefer = "return=minimal",
        )
        registrarAuditoria("usuario_actualizado", "perfiles_usuario", usuarioId)
        return getUsuario(usuarioId) ?: input.copy(id = usuarioId)
    }

    fun syncRoles(usuarioId: String, roleIds: List<String>) {
        // Misma apertura que `grantTickets`, `getUsuarioDetalle` y
        // `grantTicketsAdmin`: el identificador se valida ANTES de interpolarse
        // en ninguna URL ni de bajar a la RPC. Sin esto, el segmento del path
        // llegaba crudo a PostgREST y su eco de error se convertía en un 409 de
        // dominio falsificado desde el cliente.
        usuarioId.validatedUuid("usuarioId")
        request(
            "POST",
            "/rest/v1/rpc/admin_sync_roles",
            sbJson.encodeToString(AdminSyncRolesRpc(usuarioId = usuarioId, roleIds = roleIds.distinct())),
            prefer = "return=minimal",
        )
        registrarAuditoria("roles_sincronizados", "roles_usuario", usuarioId)
    }

    // MODERACIÓN FORO (reportes_contenido + acciones_moderacion)
    // `estado` es opcional y aditivo: sin valor (o cualquier valor distinto de
    // "todos") mantiene el comportamiento histórico (solo pendientes, orden
    // ascendente) del que ya depende la cola de moderación de escritorio.
    // "todos" quita el filtro de estado para la nueva pantalla móvil de Informes.
    fun listReportes(estado: String? = null): List<AdminReporteDto> {
        val estadoFilter = if (estado == "todos") "" else "&estado=eq.pendiente"
        val reportes = request(
            "GET",
            "/rest/v1/reportes_contenido?select=*$estadoFilter&order=creado_en.asc&limit=200",
            null,
        ).decodeList<ReporteRow>()
        val reporterIds = reportes.mapNotNull { it.reportadoPor }.filter { it.isNotBlank() }.distinct()
        val names = if (reporterIds.isEmpty()) {
            emptyMap()
        } else {
            request(
                "GET",
                "/rest/v1/perfiles_usuario?select=usuario_id,nombre_mostrar&usuario_id=in.(${reporterIds.joinToString(",")})",
                null,
            ).decodeList<PerfilUsuarioRow>().associate { it.usuarioId to it.nombreMostrar.orEmpty() }
        }
        return reportes.map { r ->
            AdminReporteDto(
                id = r.id,
                tipoEntidad = r.tipoEntidad,
                entidadId = r.entidadId.orEmpty(),
                motivo = r.motivo.orEmpty(),
                descripcion = r.descripcion.orEmpty(),
                estado = r.estado.orEmpty(),
                creadoEn = r.creadoEn.orEmpty(),
                reportadoPor = r.reportadoPor.orEmpty(),
                reporterNombre = names[r.reportadoPor].orEmpty(),
                revisadoEn = r.revisadoEn.orEmpty(),
            )
        }
    }

    /**
     * Listado paginado (keyset, nunca OFFSET) de hilos reales de OposiRed para la
     * pantalla móvil de Foro. Orden estable: creado_en desc, id desc (más nuevo
     * primero); [cursor] continúa exactamente donde terminó la página anterior.
     * Sin N+1: like [listReportes], resuelve autores/categorías/estado-de-reporte
     * en un puñado de consultas por lote, independientes del tamaño de la página
     * (nunca una consulta por fila).
     */
    fun listForoHilos(limit: Int, cursor: String?, reportedOnly: Boolean, categoriaId: String?): AdminForoHilosResponse {
        val cursorFilter = cursor?.let { raw ->
            val (creadoEn, id) = decodeForoCursor(raw)
            "&or=(creado_en.lt.${creadoEn.urlEncode()},and(creado_en.eq.${creadoEn.urlEncode()},id.lt.$id))"
        }.orEmpty()
        val categoriaFilter = categoriaId?.let { "&categoria_id=eq.$it" }.orEmpty()

        val idFilter = if (reportedOnly) {
            val reportedIds = resolveReportedThreadIds()
            if (reportedIds.isEmpty()) return AdminForoHilosResponse(items = emptyList(), nextCursor = null)
            "&id=in.(${reportedIds.joinToString(",")})"
        } else {
            ""
        }

        val rows = request(
            "GET",
            "/rest/v1/publicaciones_oposired?select=*,comentarios_oposired(count)$categoriaFilter$idFilter$cursorFilter&order=creado_en.desc,id.desc&limit=${limit + 1}",
            null,
        ).decodeList<PublicacionOposiredListRow>()

        val hasMore = rows.size > limit
        val page = rows.take(limit)
        if (page.isEmpty()) return AdminForoHilosResponse(items = emptyList(), nextCursor = null)
        val nextCursor = if (hasMore) {
            val last = page.last()
            encodeForoCursor(last.creadoEn.orEmpty(), last.id)
        } else {
            null
        }

        val authorNames = resolveUserDisplayNames(page.mapNotNull { it.usuarioId })
        val categoriaIds = page.mapNotNull { it.categoriaId }.filter { it.isNotBlank() }.distinct()
        val categoryNames = if (categoriaIds.isEmpty()) {
            emptyMap()
        } else {
            request(
                "GET",
                "/rest/v1/categorias_oposired?select=id,nombre&id=in.(${categoriaIds.joinToString(",")})",
                null,
            ).decodeList<CategoriaOposiredRow>().associate { it.id to it.nombre.orEmpty() }
        }
        // En "reportados" cada hilo de la página ya viene de resolveReportedThreadIds()
        // (que incluye hilos reportados directamente Y hilos con un comentario
        // reportado); en "recientes" solo se marca el reporte directo sobre el
        // propio hilo, para no pagar la consulta extra de comentarios en la
        // pestaña de alto volumen (ver nota en AdminForoHiloDto).
        val reportedThreadIds = if (reportedOnly) page.map { it.id }.toSet() else resolveDirectlyReportedThreadIds(page.map { it.id })

        return AdminForoHilosResponse(
            items = page.map { row ->
                AdminForoHiloDto(
                    id = row.id,
                    categoriaId = row.categoriaId,
                    categoriaNombre = row.categoriaId?.let { categoryNames[it] }.orEmpty(),
                    titulo = row.titulo.orEmpty(),
                    fragmento = row.contenido.orEmpty().toForoSnippet(),
                    estado = row.estado.orEmpty(),
                    fijada = row.fijada ?: false,
                    autorNombre = row.usuarioId?.let { authorNames[it] }.orEmpty(),
                    creadoEn = row.creadoEn.orEmpty(),
                    actualizadoEn = row.actualizadoEn.orEmpty(),
                    respuestas = row.comentarios.firstOrNull()?.count ?: 0,
                    tieneReportePendiente = row.id in reportedThreadIds,
                )
            },
            nextCursor = nextCursor,
        )
    }

    private fun resolveUserDisplayNames(ids: Collection<String>): Map<String, String> {
        val distinctIds = ids.filter { it.isNotBlank() }.distinct()
        if (distinctIds.isEmpty()) return emptyMap()
        return request(
            "GET",
            "/rest/v1/perfiles_usuario?select=usuario_id,nombre_mostrar&usuario_id=in.(${distinctIds.joinToString(",")})",
            null,
        ).decodeList<PerfilUsuarioRow>().associate { it.usuarioId to it.nombreMostrar.orEmpty() }
    }

    /** IDs de publicaciones_oposired con un reporte PENDIENTE real, ya sea sobre el
     * propio hilo o sobre alguno de sus comentarios. Dos consultas como mucho,
     * ambas independientes del número de hilos que termine mostrando la página
     * (nunca N+1): reportes_contenido pendientes, y (solo si hay reportes de tipo
     * comentario/respuesta) sus publicacion_id reales. */
    private fun resolveReportedThreadIds(): Set<String> {
        val reports = request(
            "GET",
            "/rest/v1/reportes_contenido?select=id,tipo_entidad,entidad_id&estado=eq.pendiente&tipo_entidad=in.(hilo,publicacion,comentario,respuesta)&limit=500",
            null,
        ).decodeList<ReporteRow>()
        val directThreadIds = reports
            .filter { it.tipoEntidad.lowercase() in setOf("hilo", "publicacion") }
            .mapNotNull { it.entidadId }
            .filter { it.isNotBlank() }
            .toSet()
        val commentIds = reports
            .filter { it.tipoEntidad.lowercase() in setOf("comentario", "respuesta") }
            .mapNotNull { it.entidadId }
            .filter { it.isNotBlank() }
            .distinct()
        val threadIdsFromComments = if (commentIds.isEmpty()) {
            emptySet()
        } else {
            request(
                "GET",
                "/rest/v1/comentarios_oposired?select=id,publicacion_id&id=in.(${commentIds.joinToString(",")})",
                null,
            ).decodeList<ComentarioOposiredRow>().mapNotNull { it.publicacionId }.filter { it.isNotBlank() }.toSet()
        }
        return directThreadIds + threadIdsFromComments
    }

    /** Igual que [resolveReportedThreadIds] pero acotado a [threadIds] (la página
     * actual) y sin mirar comentarios: usado solo para el indicador discreto de
     * "recientes", donde una consulta adicional de comentarios por página no se
     * justifica para un hint visual (ver nota en AdminForoHiloDto). */
    private fun resolveDirectlyReportedThreadIds(threadIds: List<String>): Set<String> {
        if (threadIds.isEmpty()) return emptySet()
        return request(
            "GET",
            "/rest/v1/reportes_contenido?select=id,entidad_id&estado=eq.pendiente&tipo_entidad=in.(hilo,publicacion)&entidad_id=in.(${threadIds.joinToString(",")})",
            null,
        ).decodeList<ReporteRow>().mapNotNull { it.entidadId }.filter { it.isNotBlank() }.toSet()
    }

    /**
     * Detalle completo de un hilo (pantalla de detalle móvil de Foro). Reutiliza
     * el mismo embed de PostgREST que [listForoHilos] para el count de
     * respuestas (sin N+1), pero sin truncar el contenido. `null` si el hilo no
     * existe — la ruta lo traduce a un 404 real (a diferencia de
     * [getReporte], que sigue devolviendo 500 vía `error(...)`; no se toca esa
     * ruta ya existente, pero esta nueva sí usa el patrón correcto que ya
     * siguen Examenes/Preguntas/Lotes/Usuarios en este mismo archivo).
     */
    fun getForoHilo(id: String): AdminForoHiloDetailDto? {
        val row = request(
            "GET",
            "/rest/v1/publicaciones_oposired?select=*,comentarios_oposired(count)&id=eq.$id&limit=1",
            null,
        ).decodeList<PublicacionOposiredListRow>().firstOrNull() ?: return null

        val autorNombre = row.usuarioId?.let { resolveUserDisplayNames(listOf(it))[it] }.orEmpty()
        val categoriaNombre = row.categoriaId?.let { categoriaId ->
            request("GET", "/rest/v1/categorias_oposired?select=id,nombre&id=eq.$categoriaId&limit=1", null)
                .decodeList<CategoriaOposiredRow>().firstOrNull()?.nombre
        }.orEmpty()

        return AdminForoHiloDetailDto(
            id = row.id,
            categoriaId = row.categoriaId,
            categoriaNombre = categoriaNombre,
            titulo = row.titulo.orEmpty(),
            contenido = row.contenido.orEmpty(),
            estado = row.estado.orEmpty(),
            fijada = row.fijada ?: false,
            autorNombre = autorNombre,
            creadoEn = row.creadoEn.orEmpty(),
            actualizadoEn = row.actualizadoEn.orEmpty(),
            respuestas = row.comentarios.firstOrNull()?.count ?: 0,
            reportesAsociados = countAssociatedPendingReports(id),
        )
    }

    /**
     * Página de respuestas de un hilo, en orden natural de lectura (más
     * antiguas primero: `creado_en asc, id asc`). Mismo cursor keyset opaco que
     * [listForoHilos] (compatible: solo cambia el sentido de la comparación).
     * Resuelve autores en un único batch (sin N+1).
     */
    fun getForoHiloRespuestas(hiloId: String, limit: Int, cursor: String?): AdminForoRespuestasResponse {
        val cursorFilter = cursor?.let { raw ->
            val (creadoEn, id) = decodeForoCursor(raw)
            "&or=(creado_en.gt.${creadoEn.urlEncode()},and(creado_en.eq.${creadoEn.urlEncode()},id.gt.$id))"
        }.orEmpty()

        val rows = request(
            "GET",
            "/rest/v1/comentarios_oposired?select=*&publicacion_id=eq.$hiloId$cursorFilter&order=creado_en.asc,id.asc&limit=${limit + 1}",
            null,
        ).decodeList<ComentarioOposiredRow>()

        val hasMore = rows.size > limit
        val page = rows.take(limit)
        val nextCursor = if (hasMore) {
            val last = page.last()
            encodeForoCursor(last.creadoEn.orEmpty(), last.id)
        } else {
            null
        }

        val authorNames = resolveUserDisplayNames(page.mapNotNull { it.usuarioId })
        return AdminForoRespuestasResponse(
            items = page.map { c ->
                AdminForoRespuestaDto(
                    id = c.id,
                    autorNombre = c.usuarioId?.let { authorNames[it] }.orEmpty(),
                    contenido = c.contenido.orEmpty(),
                    estado = c.estado.orEmpty(),
                    creadoEn = c.creadoEn.orEmpty(),
                )
            },
            nextCursor = nextCursor,
        )
    }

    /**
     * Reportes PENDIENTES asociados a un hilo: directos sobre el hilo +
     * sobre cualquiera de sus comentarios. Mismo criterio "pendiente" que
     * [resolveReportedThreadIds]/Informes — nunca cuenta reportes ya resueltos
     * o descartados, para que esta card nunca discrepe con lo que Informes
     * consideraría accionable. Dos consultas como mucho, independientes del
     * número de respuestas del hilo (sin N+1).
     */
    private fun countAssociatedPendingReports(threadId: String): Int {
        val direct = request(
            "GET",
            "/rest/v1/reportes_contenido?select=id&estado=eq.pendiente&tipo_entidad=in.(hilo,publicacion)&entidad_id=eq.$threadId",
            null,
        ).decodeList<ReporteRow>().size

        val commentIds = request(
            "GET",
            "/rest/v1/comentarios_oposired?select=id&publicacion_id=eq.$threadId",
            null,
        ).decodeList<ComentarioOposiredRow>().map { it.id }
        val onComments = if (commentIds.isEmpty()) {
            0
        } else {
            request(
                "GET",
                "/rest/v1/reportes_contenido?select=id&estado=eq.pendiente&tipo_entidad=in.(comentario,respuesta)&entidad_id=in.(${commentIds.joinToString(",")})",
                null,
            ).decodeList<ReporteRow>().size
        }
        return direct + onComments
    }

    /** Reporte único para la pantalla de detalle móvil. Mismo lookup que ya
     * hacía [resolverReporte] internamente, solo que expuesto como lectura;
     * además resuelve el contenido real reportado (tipoEntidad + entidadId). */
    fun getReporte(id: String): AdminReporteDto {
        val r = request("GET", "/rest/v1/reportes_contenido?select=*&id=eq.$id&limit=1", null)
            .decodeList<ReporteRow>().firstOrNull() ?: error("Reporte no encontrado.")
        val reporterNombre = resolveUserDisplayName(r.reportadoPor)
        return AdminReporteDto(
            id = r.id,
            tipoEntidad = r.tipoEntidad,
            entidadId = r.entidadId.orEmpty(),
            motivo = r.motivo.orEmpty(),
            descripcion = r.descripcion.orEmpty(),
            estado = r.estado.orEmpty(),
            creadoEn = r.creadoEn.orEmpty(),
            reportadoPor = r.reportadoPor.orEmpty(),
            reporterNombre = reporterNombre,
            revisadoEn = r.revisadoEn.orEmpty(),
            contentPreview = resolveContentPreview(r.tipoEntidad, r.entidadId),
        )
    }

    private fun resolveUserDisplayName(usuarioId: String?): String {
        if (usuarioId.isNullOrBlank()) return ""
        return request("GET", "/rest/v1/perfiles_usuario?select=usuario_id,nombre_mostrar&usuario_id=eq.$usuarioId&limit=1", null)
            .decodeList<PerfilUsuarioRow>().firstOrNull()?.nombreMostrar.orEmpty()
    }

    /** Resuelve el hilo/comentario real reportado. Devuelve null (nunca datos
     * inventados) si falta el id, la entidad no existe o el tipo no se reconoce.
     * "hilo"/"publicacion" y "comentario"/"respuesta" cubren ambas variantes de
     * nombre plausibles para tipo_entidad, ya que no hay filas reales hoy para
     * confirmar cuál usa el productor de reportes. */
    private fun resolveContentPreview(tipoEntidad: String, entidadId: String?): AdminReporteContentPreviewDto? {
        if (entidadId.isNullOrBlank()) return null
        return when (tipoEntidad.lowercase()) {
            "hilo", "publicacion" -> {
                val row = request("GET", "/rest/v1/publicaciones_oposired?select=*&id=eq.$entidadId&limit=1", null)
                    .decodeList<PublicacionOposiredRow>().firstOrNull() ?: return null
                AdminReporteContentPreviewDto(
                    type = "hilo",
                    text = row.contenido.orEmpty(),
                    author = resolveUserDisplayName(row.usuarioId),
                    createdAt = row.creadoEn.orEmpty(),
                    threadTitle = row.titulo,
                    currentState = row.estado.orEmpty(),
                )
            }
            "comentario", "respuesta" -> {
                val row = request("GET", "/rest/v1/comentarios_oposired?select=*&id=eq.$entidadId&limit=1", null)
                    .decodeList<ComentarioOposiredRow>().firstOrNull() ?: return null
                val threadTitle = row.publicacionId?.takeIf { it.isNotBlank() }?.let { pid ->
                    request("GET", "/rest/v1/publicaciones_oposired?select=titulo&id=eq.$pid&limit=1", null)
                        .decodeList<PublicacionTituloRow>().firstOrNull()?.titulo
                }
                AdminReporteContentPreviewDto(
                    type = "comentario",
                    text = row.contenido.orEmpty(),
                    author = resolveUserDisplayName(row.usuarioId),
                    createdAt = row.creadoEn.orEmpty(),
                    threadTitle = threadTitle,
                    currentState = row.estado.orEmpty(),
                )
            }
            else -> null
        }
    }

    /**
     * Separación explícita reporte/contenido:
     * - Este método SOLO cambia el estado del reporte (`reportes_contenido.estado`)
     *   y dispara la recompensa opcional al reportante. NUNCA toca
     *   `publicaciones_oposired`/`comentarios_oposired` — no existe ninguna acción
     *   de moderación de contenido implementada hoy (ni RPC, ni valores de estado
     *   de contenido confirmados), así que no se inventa ninguna.
     * - [actorId] es el uuid real del admin autenticado por JWT (sub), nunca el
     *   principal legacy de token estático ("oposicontrol-admin", que no es un
     *   uuid válido) — si no hay JWT real, se guarda sin actor en vez de fabricar uno.
     */
    fun resolverReporte(id: String, req: ResolverReporteBody, actorId: String?) {
        require(req.accion.isNotBlank()) { "La acción es obligatoria." }
        val reporte = request("GET", "/rest/v1/reportes_contenido?select=*&id=eq.$id&limit=1", null)
            .decodeList<ReporteRow>().firstOrNull() ?: error("Reporte no encontrado.")
        val estado = if (req.accion == "descartar") "descartado" else "resuelto"
        val accion = AccionModeracionInsert(reporteId = id, tipoAccion = req.accion, notas = req.notas, moderadorId = actorId)
        request("POST", "/rest/v1/acciones_moderacion", sbJson.encodeToString(listOf(accion)), prefer = "return=minimal")
        val reward = req.ticketsReporter > 0
        if (reward && !reporte.reportadoPor.isNullOrBlank()) {
            grantTickets(
                reporte.reportadoPor,
                req.ticketsReporter,
                "Recompensa por reporte",
                tipo = "reporte_validado",
                idempotencyKey = opositesterRewardIdempotencyKey(id),
            )
        }
        val patch = ReportePatch(
            estado = estado,
            revisadoEn = Instant.now().toString(),
            ticketsOtorgados = req.ticketsReporter,
            recompensaEntregada = reward,
            revisadoPor = actorId,
        )
        request("PATCH", "/rest/v1/reportes_contenido?id=eq.$id", sbJson.encodeToString(patch), prefer = "return=minimal")
        registrarAuditoria("reporte_$estado", "reportes_contenido", id)
        logStrategicEvent(
            StrategicEvent.REPORT_RESOLVED,
            operation = "resolver_reporte",
            result = estado,
            entityType = "reportes_contenido",
            entityId = id,
            userId = actorId,
        )
    }

    // TICKETS MANUALES (carteras_usuario + transacciones_cartera)
    // tipo debe ser un valor permitido por el CHECK: "ajuste" (manual) / "reporte_validado" (recompensa)
    fun grantTickets(
        usuarioId: String,
        cantidad: Int,
        motivo: String,
        tipo: String = "ajuste",
        idempotencyKey: String?,
    ): Int {
        usuarioId.validatedUuid("usuarioId")
        val key = idempotencyKey?.trim().orEmpty()
        if (key.isBlank()) {
            throw BackendDomainException(
                "WALLET_IDEMPOTENCY_KEY_REQUIRED",
                HttpStatusCode.UnprocessableEntity,
                "La clave de idempotencia es obligatoria.",
            )
        }
        val result = request(
            "POST",
            "/rest/v1/rpc/admin_grant_tickets",
            sbJson.encodeToString(AdminGrantTicketsRpc(usuarioId, cantidad, motivo, key, tipo)),
        ).decodeList<AdminGrantTicketsResult>().firstOrNull()
            ?: error("La operación de cartera no devolvió resultado.")
        registrarAuditoria("tickets_grant", "carteras_usuario", usuarioId)
        return result.saldoResultante
    }

    fun listTicketWallets(): List<AdminTicketWalletDto> {
        val usersById = perfilesUsuario().associateBy { it.usuarioId }
        val specialtiesById = request("GET", "/rest/v1/especialidades?select=id,nombre", null)
            .decodeList<EspecialidadNombreRow>()
            .associateBy { it.id }
        return request("GET", "/rest/v1/carteras_usuario?select=*&order=actualizado_en.desc", null)
            .decodeList<CarteraRow>()
            .map { row ->
                val user = usersById[row.usuarioId]
                AdminTicketWalletDto(
                    userId = row.usuarioId,
                    userName = user.displayName(row.usuarioId),
                    specialty = user?.especialidadPrincipalId?.let { specialtiesById[it]?.nombre }.orEmpty(),
                    balance = row.saldoTickets.toDouble(),
                    status = "active",
                )
            }
    }

    fun listTicketTransactions(usuarioId: String): List<AdminTicketTransactionDto> {
        // Misma apertura que `getUsuario`, `updateUsuario`, `syncRoles`,
        // `getUsuarioDetalle` y `grantTickets`: el identificador se valida ANTES
        // de interpolarse en la consulta. Era el último identificador de usuario
        // que llegaba crudo a PostgREST; un segmento con `&` añadía parámetros
        // propios (`select`, `order`, filtros) a una petición hecha con la clave
        // elevada. `requiredPath` sólo comprueba que no venga vacío.
        usuarioId.validatedUuid("usuarioId")
        return request(
            "GET",
            "/rest/v1/transacciones_cartera?select=*&usuario_id=eq.$usuarioId&order=creado_en.desc&limit=100",
            null,
        ).decodeList<TransaccionCarteraRow>().map { it.toDto() }
    }

    fun appendTicketAdjustment(usuarioId: String, input: AdminTicketAdjustmentDto): AdminTicketTransactionDto {
        val amount = input.amount.toInt()
        require(input.amount == amount.toDouble()) { "Los opositiquets deben ser un numero entero." }
        val idempotencyKey = input.idempotencyKey ?: throw BackendDomainException(
            "WALLET_IDEMPOTENCY_KEY_REQUIRED",
            HttpStatusCode.UnprocessableEntity,
            "La clave de idempotencia es obligatoria.",
        )
        val saldo = grantTickets(usuarioId, amount, input.reason, idempotencyKey = idempotencyKey)
        return listTicketTransactions(usuarioId).firstOrNull()
            ?: AdminTicketTransactionDto(
                id = "",
                userId = usuarioId,
                type = "ajuste",
                amount = amount.toDouble(),
                reason = input.reason,
                occurredAt = Instant.now().toString(),
            ).also { check(saldo >= 0) }
    }

    fun listSupportConversations(): List<AdminSupportConversationDto> {
        val usersById = perfilesUsuario().associateBy { it.usuarioId }
        val latestByConversation = request(
            "GET",
            "/rest/v1/mensajes_soporte?select=*&order=creado_en.desc&limit=1000",
            null,
        ).decodeList<SoporteMensajeRow>().groupBy { it.conversacionId }
        return request("GET", "/rest/v1/conversaciones_soporte?select=*&order=actualizado_en.desc", null)
            .decodeList<SoporteConversacionRow>()
            .map { row ->
                val latest = latestByConversation[row.id].orEmpty().maxByOrNull { it.creadoEn.orEmpty() }
                AdminSupportConversationDto(
                    id = row.id,
                    userName = usersById[row.usuarioId].displayName(row.usuarioId),
                    subject = row.asunto,
                    status = row.estado,
                    assignedTo = "",
                    lastMessage = latest?.cuerpo.orEmpty(),
                )
            }
    }

    fun listSupportMessages(conversationId: String): List<AdminSupportMessageDto> {
        val usersById = perfilesUsuario().associateBy { it.usuarioId }
        return request(
            "GET",
            "/rest/v1/mensajes_soporte?select=*&conversacion_id=eq.$conversationId&order=creado_en.asc",
            null,
        ).decodeList<SoporteMensajeRow>().map { row ->
            AdminSupportMessageDto(
                id = row.id,
                conversationId = row.conversacionId,
                authorName = usersById[row.remitenteId].displayName(row.remitenteId),
                text = row.cuerpo,
                timestamp = row.creadoEn.orEmpty(),
                internal = row.esStaff,
            )
        }
    }

    fun sendSupportMessage(conversationId: String, input: AdminSupportReplyDto): AdminSupportMessageDto {
        require(input.text.isNotBlank()) { "El mensaje no puede estar vacio." }
        val sender = input.assignee.takeIf { it.isNotBlank() }?.let(::findPerfil)
            ?: perfilesUsuario().firstOrNull()
            ?: throw IllegalArgumentException("No hay usuarios reales para enviar el mensaje.")
        val saved = request(
            "POST",
            "/rest/v1/mensajes_soporte",
            sbJson.encodeToString(
                listOf(
                    SoporteMensajeInsertRow(
                        conversacionId = conversationId,
                        remitenteId = sender.usuarioId,
                        cuerpo = input.text,
                        esStaff = true,
                    ),
                ),
            ),
            prefer = "return=representation",
        ).decodeList<SoporteMensajeRow>().first()
        return AdminSupportMessageDto(
            id = saved.id,
            conversationId = saved.conversacionId,
            authorName = sender.displayName(sender.usuarioId),
            text = saved.cuerpo,
            timestamp = saved.creadoEn.orEmpty(),
            internal = saved.esStaff,
        )
    }

    fun listExamAccesses(): List<AdminExamAccessDto> {
        val usersById = perfilesUsuario().associateBy { it.usuarioId }
        val examsById = request("GET", "/rest/v1/examenes?select=id,titulo", null)
            .decodeList<ExamenTituloRow>()
            .associateBy { it.id }
        return request(
            "GET",
            "/rest/v1/contenido_desbloqueado_usuario?select=*&examen_id=not.is.null&order=creado_en.desc",
            null,
        ).decodeList<ContenidoDesbloqueadoRow>().map { row ->
            AdminExamAccessDto(
                id = row.id,
                userName = usersById[row.usuarioId].displayName(row.usuarioId),
                examTitle = row.examenId?.let { examsById[it]?.titulo }.orEmpty().ifBlank { "Examen ${row.examenId}" },
                accessType = row.motivo.orEmpty().ifBlank { "manual" },
                isActive = row.validoHasta?.let { expiresAt -> runCatching { Instant.parse(expiresAt).isAfter(Instant.now()) }.getOrDefault(true) } ?: true,
            )
        }
    }

    /**
     * Concede (o actualiza) un acceso manual a un examen.
     *
     * `accessType` se normaliza contra el CHECK vivo de
     * `contenido_desbloqueado_usuario.motivo` ([CANONICAL_UNLOCK_REASONS]) ANTES de
     * cualquier lectura o escritura: un valor fuera del conjunto se rechaza con 422
     * y no sale ni una petición hacia PostgREST.
     *
     * DEUDA P2 conocida (no se toca aquí): `userName` y `examTitle` se resuelven por
     * nombre/título ([findPerfil]/[findExamen]) recorriendo la tabla completa; un
     * título duplicado o un nombre ambiguo elige el primero. Debería recibir ids.
     */
    fun upsertExamAccess(input: AdminExamAccessDto): AdminExamAccessDto {
        val motivo = canonicalUnlockReasonOrReject(input.accessType)
        val user = findPerfil(input.userName)
        val exam = findExamen(input.examTitle)
        val row = ContenidoDesbloqueadoInsertRow(
            id = input.id.ifBlank { UUID.randomUUID().toString() },
            usuarioId = user.usuarioId,
            examenId = exam.id,
            motivo = motivo,
            validoHasta = null,
        )
        return request(
            "POST",
            "/rest/v1/contenido_desbloqueado_usuario?on_conflict=id",
            sbJson.encodeToString(listOf(row)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<ContenidoDesbloqueadoRow>().first().let {
            AdminExamAccessDto(
                id = it.id,
                userName = user.displayName(user.usuarioId),
                examTitle = exam.titulo,
                accessType = it.motivo.orEmpty().ifBlank { "manual" },
                isActive = true,
            )
        }
    }

    fun listModerationActions(): List<AdminModerationActionDto> {
        val usersById = perfilesUsuario().associateBy { it.usuarioId }
        val reportsById = request("GET", "/rest/v1/reportes_contenido?select=*", null)
            .decodeList<ReporteRow>()
            .associateBy { it.id }
        return request("GET", "/rest/v1/acciones_moderacion?select=*&order=creado_en.desc&limit=200", null)
            .decodeList<AccionModeracionRow>()
            .map { row ->
                val report = reportsById[row.reporteId]
                AdminModerationActionDto(
                    id = row.id,
                    actionType = row.tipoAccion,
                    targetUser = report?.reportadoPor?.let { usersById[it] }.displayName(report?.reportadoPor.orEmpty()),
                    executor = row.moderadorId?.let { usersById[it] }.displayName(row.moderadorId.orEmpty()),
                    reason = row.notas.orEmpty(),
                    createdAt = row.creadoEn.orEmpty(),
                )
            }
    }

    fun settings(): AdminSettingsDto = adminSettingsFromEnvironment()

    // ETIQUETAS
    fun listEtiquetas(): List<AdminEtiquetaDto> =
        request("GET", "/rest/v1/etiquetas?select=*&order=nombre.asc", null)
            .decodeList<V1EtiquetaRow>().map { AdminEtiquetaDto(it.id.orEmpty(), it.nombre, it.slug, it.descripcion, it.colorHex, it.activa) }

    fun createEtiqueta(input: AdminEtiquetaDto): AdminEtiquetaDto {
        require(input.nombre.isNotBlank()) { "La etiqueta necesita nombre." }
        val row = V1EtiquetaRow(
            id = UUID.randomUUID().toString(),
            nombre = input.nombre.trim(),
            slug = input.slug.ifBlank { input.nombre.toSlug() },
            descripcion = input.descripcion,
            colorHex = input.colorHex,
            activa = input.activa,
        )
        return request("POST", "/rest/v1/etiquetas", sbJson.encodeToString(listOf(row)), prefer = "return=representation")
            .decodeList<V1EtiquetaRow>().first().let { AdminEtiquetaDto(it.id.orEmpty(), it.nombre, it.slug, it.descripcion, it.colorHex, it.activa) }
    }

    fun updateEtiqueta(id: String, input: AdminEtiquetaDto): AdminEtiquetaDto {
        require(input.nombre.isNotBlank()) { "La etiqueta necesita nombre." }
        val row = V1EtiquetaRow(id = id, nombre = input.nombre.trim(), slug = input.slug.ifBlank { input.nombre.toSlug() }, descripcion = input.descripcion, colorHex = input.colorHex, activa = input.activa)
        return request("PATCH", "/rest/v1/etiquetas?id=eq.$id", sbJson.encodeToString(row), prefer = "return=representation")
            .decodeList<V1EtiquetaRow>().firstOrNull()?.let { AdminEtiquetaDto(it.id.orEmpty(), it.nombre, it.slug, it.descripcion, it.colorHex, it.activa) }
            ?: error("Etiqueta no encontrada para actualizar: $id")
    }

    fun deleteEtiqueta(id: String) {
        request("DELETE", "/rest/v1/etiquetas?id=eq.$id", null)
        registrarAuditoria("etiqueta_eliminada", "etiquetas", id)
    }

    // COMUNIDADES AUTÓNOMAS
    fun listComunidades(): List<AdminComunidadDto> =
        request("GET", "/rest/v1/comunidades_autonomas?select=*&order=posicion.asc,nombre.asc", null)
            .decodeList<V1ComunidadRow>().map { AdminComunidadDto(it.id.orEmpty(), it.nombre, it.codigo, it.slug, it.activa, it.posicion) }

    fun createComunidad(input: AdminComunidadDto): AdminComunidadDto {
        require(input.nombre.isNotBlank()) { "La comunidad necesita nombre." }
        val row = V1ComunidadRow(id = UUID.randomUUID().toString(), nombre = input.nombre.trim(), codigo = input.codigo.trim(), slug = input.slug.ifBlank { input.nombre.toSlug() }, activa = input.activa, posicion = input.posicion)
        return request("POST", "/rest/v1/comunidades_autonomas", sbJson.encodeToString(listOf(row)), prefer = "return=representation")
            .decodeList<V1ComunidadRow>().first().let { AdminComunidadDto(it.id.orEmpty(), it.nombre, it.codigo, it.slug, it.activa, it.posicion) }
    }

    fun updateComunidad(id: String, input: AdminComunidadDto): AdminComunidadDto {
        require(input.nombre.isNotBlank()) { "La comunidad necesita nombre." }
        val row = V1ComunidadRow(id = id, nombre = input.nombre.trim(), codigo = input.codigo.trim(), slug = input.slug.ifBlank { input.nombre.toSlug() }, activa = input.activa, posicion = input.posicion)
        return request("PATCH", "/rest/v1/comunidades_autonomas?id=eq.$id", sbJson.encodeToString(row), prefer = "return=representation")
            .decodeList<V1ComunidadRow>().firstOrNull()?.let { AdminComunidadDto(it.id.orEmpty(), it.nombre, it.codigo, it.slug, it.activa, it.posicion) }
            ?: error("Comunidad no encontrada para actualizar: $id")
    }

    fun deleteComunidad(id: String) {
        request("DELETE", "/rest/v1/comunidades_autonomas?id=eq.$id", null)
        registrarAuditoria("comunidad_eliminada", "comunidades_autonomas", id)
    }

    // DASHBOARD — conteos reales de tablas del esquema activo
    /**
     * Métricas del dashboard admin.
     *
     * Dos reglas que esta función respeta y la versión anterior no:
     *
     * 1. **Un cero es un cero, no un fallo.** Antes cada métrica terminaba en
     *    `catch { 0 }` / `getOrDefault(0)`, así que "no pude leer la tabla" y
     *    "hay cero filas" llegaban a la UI idénticos. Ahora una métrica que no se
     *    puede calcular se nombra en `unavailableMetrics` y el cliente pinta
     *    NO_DATA en vez de un número inventado.
     * 2. **Contar se cuenta en el servidor.** Antes se traían todas las filas
     *    (`select=id` y `.size`), que PostgREST recorta en silencio al llegar a su
     *    `max-rows`: por encima de ese techo el número mostrado era sencillamente
     *    falso, y además crecía sin límite en memoria. Ahora se usa
     *    `Prefer: count=exact` con `Range: 0-0`, que devuelve el total exacto sin
     *    transferir ni una fila.
     *
     * `activeUsers30d`, `avgScore` y `attemptsPerDay` necesitan DISTINCT, AVG y
     * GROUP BY, que PostgREST no expone. Calcularlos trayendo filas crudas tenía
     * el mismo techo silencioso, así que se declaran no disponibles hasta que
     * exista el read model/RPC agregado. Preferimos un hueco honesto a un número
     * que parece cierto y no lo es.
     */
    fun getDashboardMetrics(): AdminDashboardDto {
        val unavailable = linkedSetOf<String>()

        /** Conteo exacto server-side; null (y métrica marcada) si no se puede leer. */
        fun count(metricKey: String, table: String, filter: String = ""): Int? {
            val total = runCatching {
                exactCount("/rest/v1/$table?select=id${if (filter.isNotBlank()) "&$filter" else ""}")
            }.getOrNull()
            if (total == null) unavailable += metricKey
            return total
        }

        val now = Instant.now()
        val totalSpecialties = count(DashboardMetricKey.TOTAL_SPECIALTIES, "especialidades")
        val totalExams = count(DashboardMetricKey.TOTAL_EXAMS, "examenes")
        val published = count(DashboardMetricKey.PUBLISHED_EXAMS, "examenes", "publicado_en=not.is.null&activo=eq.true")
        val totalQuestions = count(DashboardMetricKey.TOTAL_QUESTIONS, "preguntas")
        val totalAttempts = count(DashboardMetricKey.TOTAL_ATTEMPTS, "intentos_examen", "estado=eq.completado")

        // draftExams se deriva de dos conteos: sólo es fiable si ambos lo son.
        val draftExams = if (totalExams != null && published != null) {
            (totalExams - published).coerceAtLeast(0)
        } else {
            unavailable += DashboardMetricKey.DRAFT_EXAMS
            null
        }

        // Tasa de error global: dos conteos exactos en lugar de una muestra
        // arbitraria de 5.000 respuestas, que no era representativa de nada.
        val totalAnswers = runCatching { exactCount("/rest/v1/respuestas_intento?select=id") }.getOrNull()
        val wrongAnswers = runCatching {
            exactCount("/rest/v1/respuestas_intento?select=id&es_correcta=eq.false")
        }.getOrNull()
        val globalErrorRate = if (totalAnswers != null && wrongAnswers != null && totalAnswers > 0) {
            wrongAnswers * 100.0 / totalAnswers
        } else {
            unavailable += DashboardMetricKey.GLOBAL_ERROR_RATE
            null
        }

        // Requieren DISTINCT / AVG / GROUP BY: sin agregación server-side no hay
        // forma honesta de calcularlos. Ver ADMIN_DASHBOARD_AGGREGATES en TECH_DEBT.
        unavailable += DashboardMetricKey.ACTIVE_USERS_30D
        unavailable += DashboardMetricKey.AVG_SCORE
        unavailable += DashboardMetricKey.ATTEMPTS_PER_DAY

        return AdminDashboardDto(
            totalSpecialties = totalSpecialties ?: 0,
            totalExams = totalExams ?: 0,
            publishedExams = published ?: 0,
            draftExams = draftExams ?: 0,
            totalQuestions = totalQuestions ?: 0,
            lastUpdatedAt = now.toString(),
            totalAttempts = totalAttempts ?: 0,
            activeUsers30d = 0,
            avgScore = 0.0,
            globalErrorRate = globalErrorRate ?: 0.0,
            attemptsPerDay = emptyList(),
            unavailableMetrics = unavailable.toList(),
        )
    }

    /**
     * Ficha completa de un usuario.
     *
     * Se compone en el backend porque la pantalla de detalle necesita siempre
     * todas las secciones; hacerlo aquí evita ocho round-trips desde el cliente.
     * Todas las consultas van acotadas a un único `usuario_id`, así que el coste
     * no crece con el tamaño de las tablas.
     *
     * Cada sección se lee por separado y de forma tolerante: si una tabla no está
     * aprovisionada, esa sección se marca como no disponible y el resto de la
     * ficha se sirve igualmente. Una ficha parcial es útil; un 500 no.
     */
    fun getUsuarioDetalle(usuarioId: String): AdminUserDetailDto? {
        usuarioId.validatedUuid("usuarioId")
        val perfil = request(
            "GET",
            "/rest/v1/perfiles_usuario?select=*&usuario_id=eq.$usuarioId&limit=1",
            null,
        ).decodeList<AdminUserProfileDto>().firstOrNull() ?: return null

        val unavailable = linkedSetOf<String>()

        /** Lee una sección; si falla, la marca y devuelve null en vez de tumbar la ficha. */
        fun <T> section(key: String, read: () -> T?): T? =
            runCatching(read).getOrElse {
                unavailable += key
                null
            }

        val detalle = section(AdminUserDetailSection.DETALLE) {
            request(
                "GET",
                "/rest/v1/perfiles_usuario_detalle?select=*&usuario_id=eq.$usuarioId&limit=1",
                null,
            ).decodeList<AdminUserProfileDetailDto>().firstOrNull()
        }

        val rendimiento = section(AdminUserDetailSection.RENDIMIENTO) {
            request(
                "GET",
                "/rest/v1/estadisticas_usuario?select=*&usuario_id=eq.$usuarioId&limit=1",
                null,
            ).decodeList<AdminUserPerformanceDto>().firstOrNull()
        }

        val competitivo = section(AdminUserDetailSection.COMPETITIVO) {
            val row = request(
                "GET",
                "/rest/v1/estado_competitivo_usuario?select=*&usuario_id=eq.$usuarioId&limit=1",
                null,
            ).decodeList<AdminUserCompetitiveDto>().firstOrNull()
            // El nivel de liga se resuelve aparte: la fila competitiva sólo guarda
            // el id, y el panel necesita el nombre para que signifique algo.
            row?.ligaNivelId?.let { nivelId ->
                val nivel = request(
                    "GET",
                    "/rest/v1/ligas_nivel?select=codigo,nombre&id=eq.$nivelId&limit=1",
                    null,
                ).decodeList<LigaNivelRow>().firstOrNull()
                row.copy(ligaCodigo = nivel?.codigo, ligaNombre = nivel?.nombre)
            } ?: row
        }

        val racha = section(AdminUserDetailSection.RACHA) {
            request(
                "GET",
                "/rest/v1/rachas_usuario?select=*&usuario_id=eq.$usuarioId&limit=1",
                null,
            ).decodeList<AdminUserStreakDto>().firstOrNull()
        }

        val economia = section(AdminUserDetailSection.ECONOMIA) {
            val cartera = request(
                "GET",
                "/rest/v1/carteras_usuario?select=*&usuario_id=eq.$usuarioId&limit=1",
                null,
            ).decodeList<CarteraRow>().firstOrNull()
            val premium = request(
                "GET",
                "/rest/v1/estado_premium_usuario?select=*&usuario_id=eq.$usuarioId&limit=1",
                null,
            ).decodeList<EstadoPremiumRow>().firstOrNull()
            if (cartera == null && premium == null) {
                null
            } else {
                AdminUserEconomyDto(
                    saldoTickets = cartera?.saldoTickets ?: 0,
                    esPremium = premium?.esPremium ?: false,
                    estadoSuscripcion = premium?.estadoSuscripcion,
                    productoCodigo = premium?.productoCodigo,
                    proveedor = premium?.proveedor,
                    renovacionEn = premium?.renovacionEn,
                    finEn = premium?.finEn,
                )
            }
        }

        val suscripciones = section(AdminUserDetailSection.SUSCRIPCIONES) {
            request(
                "GET",
                "/rest/v1/suscripciones?select=*&usuario_id=eq.$usuarioId&order=inicio_en.desc&limit=20",
                null,
            ).decodeList<AdminUserSubscriptionDto>()
        }.orEmpty()

        val opositester = section(AdminUserDetailSection.OPOSITESTER) {
            request(
                "GET",
                "/rest/v1/estado_opositester_usuario?select=*&usuario_id=eq.$usuarioId&limit=1",
                null,
            ).decodeList<AdminUserTesterStateDto>().firstOrNull()
        }

        val debilidades = section(AdminUserDetailSection.DEBILIDADES) {
            // Peor porcentaje primero: la vista existe justo para esto, y el orden
            // hace que el recorte deje lo accionable arriba.
            request(
                "GET",
                "/rest/v1/v_usuario_debilidades_etiqueta?select=*&usuario_id=eq.$usuarioId" +
                    "&order=porcentaje_acierto.asc&limit=$ADMIN_USER_WEAKNESSES_LIMIT",
                null,
            ).decodeList<AdminUserWeaknessDto>()
        }.orEmpty()

        return AdminUserDetailDto(
            perfil = perfil,
            detalle = detalle,
            rendimiento = rendimiento,
            competitivo = competitivo,
            racha = racha,
            economia = economia,
            suscripciones = suscripciones,
            opositester = opositester,
            debilidades = debilidades,
            seccionesNoDisponibles = unavailable.toList(),
        )
    }

    // ---------- CATÁLOGO COMERCIAL (productos_comerciales + tarifas) ----------
    // OposiBot ya lee este catálogo; esto es la mitad de escritura que faltaba,
    // para que cambiar un precio o los OT de un pack no requiera tocar código.

    fun listProductosComerciales(): List<AdminProductoComercialDto> {
        val productos = request(
            "GET",
            "/rest/v1/productos_comerciales?select=*&order=posicion.asc,codigo.asc",
            null,
        ).decodeList<ProductoComercialRow>()
        if (productos.isEmpty()) return emptyList()

        // Una sola consulta para todas las tarifas en lugar de una por producto:
        // el catálogo es pequeño pero N+1 sobre PostgREST se nota igual.
        val tarifasPorProducto = request(
            "GET",
            "/rest/v1/tarifas_producto_comercial?select=*&order=posicion.asc",
            null,
        ).decodeList<TarifaProductoRow>()
            .groupBy { it.productoId }

        return productos.map { producto ->
            producto.toDto(tarifasPorProducto[producto.id].orEmpty().map { it.toDto() })
        }
    }

    fun upsertProductoComercial(input: AdminProductoComercialDto): AdminProductoComercialDto {
        val valid = input.validated()
        val id = valid.id.ifBlank { UUID.randomUUID().toString() }
        val row = ProductoComercialRow(
            id = id,
            codigo = valid.codigo,
            tipoProducto = valid.tipoProducto,
            nombre = valid.nombre,
            descripcion = valid.descripcion,
            planId = valid.planId?.takeIf { it.isNotBlank() },
            precioCentimos = valid.precioCentimos,
            moneda = valid.moneda,
            cantidadTickets = valid.cantidadTickets,
            activo = valid.activo,
            visible = valid.visible,
            destacado = valid.destacado,
            etiquetaPromocional = valid.etiquetaPromocional,
            posicion = valid.posicion,
        )
        val saved = request(
            "POST",
            "/rest/v1/productos_comerciales",
            sbJson.encodeToString(listOf(row)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<ProductoComercialRow>().firstOrNull()
            ?: error("El producto comercial no se pudo guardar.")

        registrarAuditoria("producto_comercial_guardado", "productos_comerciales", id)
        return saved.toDto(tarifas = listTarifas(id))
    }

    fun listTarifas(productoId: String): List<AdminTarifaProductoDto> {
        productoId.validatedUuid("productoId")
        return request(
            "GET",
            "/rest/v1/tarifas_producto_comercial?select=*&producto_id=eq.$productoId&order=posicion.asc",
            null,
        ).decodeList<TarifaProductoRow>().map { it.toDto() }
    }

    fun upsertTarifa(productoId: String, input: AdminTarifaProductoDto): AdminTarifaProductoDto {
        productoId.validatedUuid("productoId")
        val valid = input.validated()
        val id = valid.id.ifBlank { UUID.randomUUID().toString() }

        // Comprobado contra las tarifas ya guardadas más ésta: dos activas para el
        // mismo periodo y fase dejarían el precio indefinido para la app.
        val resulting = listTarifas(productoId).filterNot { it.id == id } + valid.copy(id = id, productoId = productoId)
        resulting.findAmbiguousTariff()?.let { clash ->
            throw BackendDomainException(
                "TARIFF_AMBIGUOUS",
                HttpStatusCode.Conflict,
                "Ya existe una tarifa activa y visible para ${clash.periodoFacturacion}/${clash.fasePrecio}. " +
                    "Desactiva la anterior antes de crear otra, o el precio mostrado sería indeterminado.",
            )
        }

        val row = TarifaProductoRow(
            id = id,
            productoId = productoId,
            periodoFacturacion = valid.periodoFacturacion,
            fasePrecio = valid.fasePrecio,
            precioCentimos = valid.precioCentimos,
            moneda = valid.moneda,
            activo = valid.activo,
            visible = valid.visible,
            posicion = valid.posicion,
        )
        val saved = request(
            "POST",
            "/rest/v1/tarifas_producto_comercial",
            sbJson.encodeToString(listOf(row)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<TarifaProductoRow>().firstOrNull()
            ?: error("La tarifa no se pudo guardar.")

        registrarAuditoria("tarifa_producto_guardada", "tarifas_producto_comercial", id)
        return saved.toDto()
    }

    // ---------- ESTADO DEL SISTEMA (agregación en Ktor) ----------

    /**
     * Resumen del estado de los servicios.
     *
     * Ktor y Supabase se comprueban directamente. CPU/RAM del host, p95 HTTP y
     * errores 5xx proceden de Prometheus; el disco de datos, del endpoint
     * privilegiado de Supabase. Cada serie ausente conserva `null` y se declara
     * en `notInstrumented` en vez de inventar un cero.
     */
    fun getSystemStatus(): AdminSystemStatusDto {
        val startedAt = System.nanoTime()
        val supabaseReachable = runCatching {
            // Lectura deliberadamente mínima: comprobar que responde, no medir la
            // base de datos ni cargarla con una consulta cara.
            exactCount("/rest/v1/especialidades?select=id")
            true
        }.getOrDefault(false)
        val latencyMs = (System.nanoTime() - startedAt) / 1_000_000

        val supabaseLevel = supabaseLevelFrom(
            reachable = supabaseReachable,
            latencyMs = latencyMs,
            slowThresholdMs = SUPABASE_SLOW_THRESHOLD_MS,
        )
        // Si esta línea se ejecuta, Ktor está sirviendo la petición.
        val ktorLevel = SystemStatusLevelWire.OPERATIVE
        val metrics = systemMetrics.snapshot()
        // finiteOrNull() en la frontera real de serialización: un SystemMetricsProvider
        // distinto del gateway de Prometheus (test doble, implementación futura) no
        // puede colar un NaN/Infinity que kotlinx.serialization rechazaría al
        // codificar `respond`. Se calcula una sola vez y se reutiliza también en
        // `notInstrumented`, para que el DTO y su propia lista de ausencias nunca
        // se contradigan.
        val cpuPercent = metrics.cpuPercent.finiteOrNull()
        val ramPercent = metrics.ramPercent.finiteOrNull()
        // Fuente distinta (endpoint privilegiado de Supabase) y por tanto fallo
        // independiente: si no se puede medir, el resto del estado se sirve
        // igual. El `runCatching` cubre un proveedor que lance en vez de
        // devolver `null`; el `finiteOrNull()`, uno que devuelva NaN/Infinity.
        val supabaseDiskPercent = runCatching { supabaseDisk.diskPercent() }.getOrNull().finiteOrNull()

        return AdminSystemStatusDto(
            overallStatus = aggregateSystemStatus(listOf(ktorLevel, supabaseLevel)),
            ktorStatus = ktorLevel,
            supabaseStatus = supabaseLevel,
            workerStatus = SystemStatusLevelWire.UNKNOWN,
            cpuPercent = cpuPercent,
            ramPercent = ramPercent,
            supabaseDiskPercent = supabaseDiskPercent,
            apiLatencyMs = metrics.apiLatencyMs,
            errors24h = metrics.errors24h,
            updatedAt = Instant.now().toString(),
            notInstrumented = listOfNotNull(
                SystemStatusNotInstrumented.WORKER,
                SystemStatusNotInstrumented.CPU.takeIf { cpuPercent == null },
                SystemStatusNotInstrumented.MEMORY.takeIf { ramPercent == null },
                SystemStatusNotInstrumented.SUPABASE_DISK.takeIf { supabaseDiskPercent == null },
                SystemStatusNotInstrumented.API_LATENCY.takeIf { metrics.apiLatencyMs == null },
                SystemStatusNotInstrumented.API_ERRORS.takeIf { metrics.errors24h == null },
                SystemStatusNotInstrumented.MOBILE_CRASHES,
                SystemStatusNotInstrumented.QUEUE_DEPTH,
            ),
        )
    }

    // ---------- PLANES DE SUSCRIPCIÓN (planes_suscripcion + beneficios_plan) ----------

    fun listPlanes(): List<AdminPlanDto> {
        val planes = request(
            "GET",
            "/rest/v1/planes_suscripcion?select=*&order=posicion.asc,nivel.asc",
            null,
        ).decodeList<PlanRow>()
        if (planes.isEmpty()) return emptyList()

        // Una sola consulta para todos los beneficios: el catálogo es pequeño,
        // pero un N+1 sobre PostgREST se nota igual y no cuesta nada evitarlo.
        val beneficiosPorPlan = request(
            "GET",
            "/rest/v1/beneficios_plan?select=*&order=codigo_beneficio.asc",
            null,
        ).decodeList<AdminPlanBenefitDto>()
            .groupBy { it.planId }

        return planes.map { plan -> plan.toDto(beneficiosPorPlan[plan.id].orEmpty()) }
    }

    fun upsertPlan(input: AdminPlanDto): AdminPlanDto {
        val valid = input.validated()
        val id = valid.id.ifBlank { UUID.randomUUID().toString() }
        val saved = request(
            "POST",
            "/rest/v1/planes_suscripcion",
            sbJson.encodeToString(listOf(valid.copy(id = id).toRow())),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<PlanRow>().firstOrNull() ?: error("El plan no se pudo guardar.")
        registrarAuditoria("plan_suscripcion_guardado", "planes_suscripcion", id)
        return saved.toDto(listPlanBenefits(id))
    }

    fun listPlanBenefits(planId: String): List<AdminPlanBenefitDto> {
        planId.validatedUuid("planId")
        return request(
            "GET",
            "/rest/v1/beneficios_plan?select=*&plan_id=eq.$planId&order=codigo_beneficio.asc",
            null,
        ).decodeList()
    }

    fun upsertPlanBenefit(planId: String, input: AdminPlanBenefitDto): AdminPlanBenefitDto {
        planId.validatedUuid("planId")
        val valid = input.validated()
        val row = valid.copy(
            id = valid.id.ifBlank { UUID.randomUUID().toString() },
            // El plan lo fija la ruta: si el cuerpo trajera otro, se estaría
            // editando el plan equivocado.
            planId = planId,
        )
        val saved = request(
            "POST",
            "/rest/v1/beneficios_plan",
            sbJson.encodeToString(listOf(row)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<AdminPlanBenefitDto>().firstOrNull() ?: error("El beneficio no se pudo guardar.")
        registrarAuditoria("beneficio_plan_guardado", "beneficios_plan", row.id)
        return saved
    }

    // ---------- OPOSITICKETS (concesión administrativa + resumen) ----------

    /**
     * Concesión administrativa de OposiTickets.
     *
     * Delega íntegramente en `admin_grant_tickets`: aquí no se calcula ni se
     * escribe ningún saldo. El RPC es atómico, idempotente y el único escritor
     * de cartera y ledger.
     */
    fun grantTicketsAdmin(input: AdminGrantTicketsRequestDto): AdminGrantTicketsResultDto {
        val valid = input.validated()
        valid.usuarioId.validatedUuid("usuarioId")
        // El motivo lleva la referencia embebida cuando existe: el ledger guarda
        // descripción, no un campo de referencia libre, así que es la única forma
        // de conservar el origen sin inventar columna.
        val descripcion = valid.referencia
            ?.let { "${valid.motivo} [ref: $it]" }
            ?: valid.motivo
        val saldo = grantTickets(
            usuarioId = valid.usuarioId,
            cantidad = valid.cantidad,
            motivo = descripcion,
            tipo = valid.tipo,
            idempotencyKey = valid.idempotencyKey,
        )
        return AdminGrantTicketsResultDto(
            usuarioId = valid.usuarioId,
            cantidad = valid.cantidad,
            saldoResultante = saldo,
            tipo = valid.tipo,
            idempotencyKey = valid.idempotencyKey.orEmpty(),
        )
    }

    /**
     * Resumen de la economía de OposiTickets.
     *
     * Sólo conteos exactos server-side. Los totales que exigen SUM (OT en
     * circulación, emitidos, consumidos) no se calculan trayendo filas: se
     * declaran no disponibles hasta que exista un agregado, porque sumarlos aquí
     * daría un número recortado en silencio y con pinta de correcto.
     */
    fun getTicketsOverview(): AdminTicketsOverviewDto {
        val unavailable = linkedSetOf(
            AdminTicketsMetricKey.OT_EN_CIRCULACION,
            AdminTicketsMetricKey.OT_EMITIDOS,
            AdminTicketsMetricKey.OT_CONSUMIDOS,
        )

        fun count(key: String, path: String): Int? =
            runCatching { exactCount(path) }.getOrElse {
                unavailable += key
                null
            }

        return AdminTicketsOverviewDto(
            carteras = count(AdminTicketsMetricKey.CARTERAS, "/rest/v1/carteras_usuario?select=usuario_id"),
            movimientos = count(AdminTicketsMetricKey.MOVIMIENTOS, "/rest/v1/transacciones_cartera?select=id"),
            movimientosCredito = count(
                AdminTicketsMetricKey.MOVIMIENTOS_CREDITO,
                "/rest/v1/transacciones_cartera?select=id&cantidad=gt.0",
            ),
            movimientosDebito = count(
                AdminTicketsMetricKey.MOVIMIENTOS_DEBITO,
                "/rest/v1/transacciones_cartera?select=id&cantidad=lt.0",
            ),
            unavailableMetrics = unavailable.toList(),
        )
    }

    // ---------- OPOSIMARKET (packs, servicios, promociones, reglas de pop-up) ----------
    // Las cuatro tablas ya existen; esto es la superficie de administración que
    // faltaba para poblarlas sin desplegar código.

    fun listTicketPacks(): List<AdminTicketPackDto> =
        request("GET", "/rest/v1/paquetes_opositickets?select=*&order=posicion.asc,cantidad_tickets.asc", null)
            .decodeList()

    fun upsertTicketPack(input: AdminTicketPackDto): AdminTicketPackDto {
        val row = input.validated().let { it.copy(id = it.id.ifBlank { UUID.randomUUID().toString() }) }
        val saved = request(
            "POST",
            "/rest/v1/paquetes_opositickets",
            sbJson.encodeToString(listOf(row)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<AdminTicketPackDto>().firstOrNull() ?: error("El pack no se pudo guardar.")
        registrarAuditoria("pack_opositickets_guardado", "paquetes_opositickets", row.id)
        return saved
    }

    fun listServices(): List<AdminServiceDto> =
        request("GET", "/rest/v1/servicios?select=*&order=tipo_servicio.asc,codigo.asc", null).decodeList()

    fun upsertService(input: AdminServiceDto): AdminServiceDto {
        val row = input.validated().let { it.copy(id = it.id.ifBlank { UUID.randomUUID().toString() }) }
        val saved = request(
            "POST",
            "/rest/v1/servicios",
            sbJson.encodeToString(listOf(row)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<AdminServiceDto>().firstOrNull() ?: error("El servicio no se pudo guardar.")
        registrarAuditoria("servicio_market_guardado", "servicios", row.id)
        return saved
    }

    fun listPromotions(): List<AdminPromotionDto> =
        request("GET", "/rest/v1/promociones?select=*&order=codigo.asc", null).decodeList()

    fun upsertPromotion(input: AdminPromotionDto): AdminPromotionDto {
        val row = input.validated().let { it.copy(id = it.id.ifBlank { UUID.randomUUID().toString() }) }
        val saved = request(
            "POST",
            "/rest/v1/promociones",
            sbJson.encodeToString(listOf(row)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<AdminPromotionDto>().firstOrNull() ?: error("La promoción no se pudo guardar.")
        registrarAuditoria("promocion_guardada", "promociones", row.id)
        return saved
    }

    fun listPopupRules(): List<AdminPopupRuleDto> =
        request(
            "GET",
            "/rest/v1/reglas_popup_comercial?select=*&order=prioridad.desc,codigo.asc",
            null,
        ).decodeList()

    fun upsertPopupRule(input: AdminPopupRuleDto): AdminPopupRuleDto {
        val row = input.validated().let { it.copy(id = it.id.ifBlank { UUID.randomUUID().toString() }) }
        val saved = request(
            "POST",
            "/rest/v1/reglas_popup_comercial",
            sbJson.encodeToString(listOf(row)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<AdminPopupRuleDto>().firstOrNull() ?: error("La regla no se pudo guardar.")
        registrarAuditoria("regla_popup_guardada", "reglas_popup_comercial", row.id)
        return saved
    }

    // ROLES (roles_app — catálogo, sin permisos granulares por ahora)
    fun listRoles(): List<AdminV1RoleDto> =
        request("GET", "/rest/v1/roles_app?select=*&order=nombre.asc", null)
            .decodeList<RolAppRow>()
            .map { AdminV1RoleDto(id = it.id, name = it.nombre, code = it.codigo, description = it.descripcion.orEmpty()) }

    fun listAuditoria(limit: Int): List<AdminAuditoriaDto> =
        request("GET", "/rest/v1/auditoria_log?select=*&order=creado_en.desc&limit=$limit", null)
            .decodeList<AuditoriaRow>()
            .map { it.toDto() }

    fun warnUser(userId: String, actionType: String, reason: String) {
        userId.validatedUuid("userId")
        registrarAuditoria("${actionType}_usuario", "perfiles_usuario", userId)
    }

    // Registro de acciones sensibles. No debe tumbar la operacion principal si falla.
    // TODO(fase4): rellenar usuario_id desde el JWT de Supabase.
    fun registrarAuditoria(accion: String, tabla: String?, registroId: String?) {
        // Punto unico: toda escritura admin sensible pasa por aqui, asi que
        // es el sitio correcto para el evento estrategico generico
        // ADMIN_ACTION sin tener que tocar cada llamador (~20 sitios).
        logStrategicEvent(
            StrategicEvent.ADMIN_ACTION,
            operation = accion,
            entityType = tabla,
            entityId = registroId,
        )
        try {
            request(
                "POST", "/rest/v1/auditoria_log",
                sbJson.encodeToString(
                    listOf(AuditoriaRow(id = UUID.randomUUID().toString(), accion = accion, tablaAfectada = tabla, registroId = registroId)),
                ),
                prefer = "return=minimal",
            )
        } catch (error: Exception) {
            // F-12b: `System.err.println` no llevaba `request_id` ni evento, así
            // que una auditoría perdida era invisible para quien buscara la
            // referencia de la petición. El logger sale con el MDC (`%mdc`) y el
            // evento estratégico hace que la pérdida se pueda contar.
            AuditLogger.warn(
                "auditoria fallida ({}): {}",
                accion,
                error.redactedMessage("Fallo al escribir auditoria", 160),
            )
            logStrategicEvent(
                StrategicEvent.SUPABASE_REQUEST_FAILED,
                operation = "auditoria_$accion",
                result = "audit_write_failed",
                entityType = tabla,
                entityId = registroId,
                errorType = error::class.simpleName,
            )
        }
    }

    // TIENDA (Store) -- proxies what the Desktop client used to call directly
    // on Supabase with the anon key + user JWT. Same tables/columns/ids
    // (legacy serial Int, not UUID) as before; only the origin of the HTTP
    // call moves behind the Ktor boundary. See KtorCommerceRepositoryImpl.
    fun listArticulosTienda(): List<AdminArticuloTiendaDto> =
        request("GET", "/rest/v1/articulo_tienda?select=*&order=nombre.asc", null).decodeList()

    fun upsertArticuloTienda(input: AdminArticuloTiendaDto): AdminArticuloTiendaDto =
        request(
            "POST",
            "/rest/v1/articulo_tienda?on_conflict=id",
            sbJson.encodeToString(listOf(input)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<AdminArticuloTiendaDto>().first()

    fun listOfertasTienda(): List<AdminOfertaTiendaDto> =
        request("GET", "/rest/v1/oferta_tienda?select=*&order=starts_at.desc", null).decodeList()

    fun upsertOfertaTienda(input: AdminOfertaTiendaDto): AdminOfertaTiendaDto =
        request(
            "POST",
            "/rest/v1/oferta_tienda?on_conflict=id",
            sbJson.encodeToString(listOf(input)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<AdminOfertaTiendaDto>().first()

    fun listRecursosTemporalesTienda(): List<AdminRecursoTemporalTiendaDto> =
        request("GET", "/rest/v1/recurso_temporal_tienda?select=*&order=starts_at.desc", null).decodeList()

    fun upsertRecursoTemporalTienda(input: AdminRecursoTemporalTiendaDto): AdminRecursoTemporalTiendaDto =
        request(
            "POST",
            "/rest/v1/recurso_temporal_tienda?on_conflict=id",
            sbJson.encodeToString(listOf(input)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<AdminRecursoTemporalTiendaDto>().first()

    fun listCampanasRafaga(): List<AdminCampanaRafagaDto> =
        request("GET", "/rest/v1/campana_rafaga?select=*&order=starts_at.desc", null).decodeList()

    fun upsertCampanaRafaga(input: AdminCampanaRafagaDto): AdminCampanaRafagaDto =
        request(
            "POST",
            "/rest/v1/campana_rafaga?on_conflict=id",
            sbJson.encodeToString(listOf(input)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<AdminCampanaRafagaDto>().first()

    private fun perfilesUsuario(): List<PerfilUsuarioRow> =
        request("GET", "/rest/v1/perfiles_usuario?select=*&order=creado_en.desc&limit=1000", null)
            .decodeList()

    private fun findPerfil(query: String): PerfilUsuarioRow {
        val normalized = query.trim().lowercase()
        return perfilesUsuario().firstOrNull {
            it.usuarioId == query ||
                it.username.orEmpty().lowercase() == normalized ||
                it.nombreMostrar.orEmpty().lowercase() == normalized
        } ?: throw IllegalArgumentException("No se encontro usuario real para '$query'.")
    }

    private fun findExamen(query: String): ExamenTituloRow {
        val normalized = query.trim().lowercase()
        return request("GET", "/rest/v1/examenes?select=id,titulo", null)
            .decodeList<ExamenTituloRow>()
            .firstOrNull { it.id == query || it.titulo.lowercase() == normalized }
            ?: throw IllegalArgumentException("No se encontro examen real para '$query'.")
    }

    /**
     * Sube una portada y devuelve el examen con su URL firmada (K5, PW-8 opción A).
     * `null` = el examen no existe (404 en la ruta), y entonces Storage no se toca.
     *
     * ### El nombre del objeto
     *
     * `exam-covers/<id>/<uuid>.<ext>`, en minúsculas, **único por subida** (C-13,
     * `upsert = false`): una re-subida nunca sobrescribe la portada vigente, así que
     * si algo falla a mitad la fila sigue apuntando a un objeto que existe. Es además
     * la forma EXACTA que exige el CHECK de M6 (`examenes_portada_coherente_chk`,
     * con expresión regular): por eso el id sale de la FILA leída —el texto
     * canónico con el que compara el CHECK— y no del path de la petición.
     *
     * ### Orden y limpieza
     *
     * 1. Tipo antes que nada: sólo JPG, PNG o WEBP (422 sin tocar la base ni Storage).
     * 2. El examen existe (lectura mínima) antes de subir: sin huérfanos por un id malo.
     * 3. Subida → PATCH de la fila. Si el PATCH falla, se borra el objeto NUEVO: nadie
     *    apunta a él.
     * 4. Si el PATCH va bien, se borra el ANTERIOR (best-effort: un fallo deja un
     *    huérfano privado y una línea de log, nunca una fila apuntando a nada).
     */
    fun uploadExamenPortada(examId: String, bytes: ByteArray, mimeType: String): AdminExamenDto? {
        val declaredMime = mimeType.substringBefore(';').trim().lowercase()
        // El tipo DECLARADO se valida antes de comprimir: comprimir no puede
        // ampliar lo que se admite, igual que en los recursos de pregunta.
        EXAM_COVER_EXTENSIONS[declaredMime] ?: throw examCoverTypeInvalidException()

        // Si el resultado saliera con un MIME que esta ruta no sabe extensionar,
        // gana el original: la extension del objeto y su `Content-Type` tienen
        // que corresponder a los bytes que se suben.
        val optimized = MediaCompression.compress(bytes, declaredMime, allowFormatChange = true)
        val useOptimized = EXAM_COVER_EXTENSIONS.containsKey(optimized.mimeType)
        val finalBytes = if (useOptimized) optimized.bytes else bytes
        val mime = if (useOptimized) optimized.mimeType else declaredMime
        val extension = EXAM_COVER_EXTENSIONS.getValue(mime)

        val existing = request("GET", "/rest/v1/examenes?select=id,portada_storage_path&id=eq.$examId&limit=1", null)
            .decodeList<AdminExamenPortadaRow>().firstOrNull() ?: return null
        val bucket = examResourcesBucket()
        ensureStorageBucketExists(bucket)
        val objectPath = "exam-covers/${existing.id.lowercase()}/${UUID.randomUUID().toString().lowercase()}.$extension"
        transport.storagePut(bucket, objectPath, finalBytes, mime, upsert = false)
        val saved = try {
            // Concurrencia (Nuclear Review Finding 1): el PATCH sólo se aplica si la
            // portada sigue siendo la que se leyó arriba. Si otra escritura ganó la
            // carrera entre medias, `decodeList` viene vacío en vez de pisarla en
            // silencio, y el catch de abajo limpia el objeto que nadie va a referenciar.
            val condicion = existing.portadaStoragePath
                ?.let { "portada_storage_path=eq.${it.urlEncode()}" }
                ?: "portada_storage_path=is.null"
            // Por el serializador, nunca por plantilla: ver la regla del comentario
            // de `delete("/{id}/preguntas/{preguntaId}")`.
            request(
                "PATCH", "/rest/v1/examenes?id=eq.$examId&$condicion",
                buildJsonObject {
                    put("portada_storage_path", JsonPrimitive(objectPath))
                    put("portada_mime_type", JsonPrimitive(mime))
                }.toString(),
                prefer = "return=representation",
            ).decodeList<AdminExamenRow>().firstOrNull()
                ?: throw domainError("EXAM_COVER_CHANGED_CONCURRENTLY")
        } catch (error: Exception) {
            // Nadie apunta al objeto nuevo: se borra. Su fallo no puede tapar `error`,
            // que es el que el administrador tiene que ver.
            runCatching { transport.storageDelete(bucket, objectPath) }
                .onFailure { ExamCoverLogger.warn("limpieza de portada huérfana fallida: {}", it.redactedMessage("Fallo al borrar", 160)) }
            throw error
        }
        existing.portadaStoragePath?.takeIf { it.isNotBlank() && it != objectPath }?.let { previous ->
            runCatching { transport.storageDelete(bucket, previous) }
                .onFailure { ExamCoverLogger.warn("borrado de portada anterior fallido: {}", it.redactedMessage("Fallo al borrar", 160)) }
        }
        return saved.toDto().withSignedCover()
    }

    /**
     * Quita la portada: primero la fila, DESPUÉS el objeto (K5). `null` = el examen
     * no existe (404 en la ruta).
     *
     * El orden es la garantía: si el borrado del objeto falla, queda un huérfano
     * privado que nadie referencia; al revés, quedaría una fila apuntando a nada y
     * una portada rota en la app.
     */
    fun clearExamenPortada(examId: String): AdminExamenDto? {
        val existing = request("GET", "/rest/v1/examenes?select=id,portada_storage_path&id=eq.$examId&limit=1", null)
            .decodeList<AdminExamenPortadaRow>().firstOrNull() ?: return null
        // Concurrencia (Nuclear Review Finding 1): mismo condicional que en la subida.
        // El examen YA existía en la lectura de arriba, así que una lista vacía aquí
        // sólo puede ser una escritura rival que ganó la carrera, nunca "no existe".
        val condicion = existing.portadaStoragePath
            ?.let { "portada_storage_path=eq.${it.urlEncode()}" }
            ?: "portada_storage_path=is.null"
        val saved = request(
            "PATCH", "/rest/v1/examenes?id=eq.$examId&$condicion",
            """{"portada_storage_path":null,"portada_mime_type":null}""",
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().firstOrNull()
            ?: throw domainError("EXAM_COVER_CHANGED_CONCURRENTLY")
        existing.portadaStoragePath?.takeIf { it.isNotBlank() }?.let { previous ->
            runCatching { transport.storageDelete(examResourcesBucket(), previous) }
                .onFailure { ExamCoverLogger.warn("borrado de portada quitada fallido: {}", it.redactedMessage("Fallo al borrar", 160)) }
        }
        return saved.toDto()
    }

    /**
     * El bucket de recursos se crea **PRIVADO**.
     *
     * Esto era `public = true`, y en MAIN no rompía nada por accidente: el bucket ya
     * existe y Storage responde 409 a la creación. La bomba estaba en un entorno
     * nuevo —una preproducción, una restauración—: ahí la primera subida CREARÍA
     * `exam-resources` público, sin que nadie lo viera.
     *
     * La portada ya no depende de que el bucket sea público: se guarda la ruta del
     * objeto y la URL se FIRMA en cada lectura (K5, [withSignedCovers]).
     */
    private fun ensureStorageBucketExists(bucket: String) = transport.storageEnsureBucket(bucket, public = false)

    /** Bucket de portadas: `SUPABASE_BUCKET_EXAM_RESOURCES` o `exam-resources`. */
    private fun examResourcesBucket(): String =
        (System.getenv("SUPABASE_BUCKET_EXAM_RESOURCES") ?: "exam-resources").toStorageBucketId()

    private fun shortId(): String = UUID.randomUUID().toString().take(8)

    /**
     * Conteo exacto sin traer filas.
     *
     * `Prefer: count=exact` hace que PostgREST devuelva el total en la cabecera
     * `Content-Range`, y `Range: 0-0` limita el cuerpo a una fila. Así el conteo
     * no depende del `max-rows` del servidor ni carga la tabla entera en memoria.
     *
     * Lanza si la respuesta no trae un total utilizable: quien llama decide si eso
     * significa "métrica no disponible", pero nunca puede confundirse con un cero.
     */
    private fun exactCount(path: String): Int = transport.count(path)

    /**
     * Se conserva como envoltorio de una línea para no tocar los ~150
     * llamadores: el transporte cambia, el contrato interno no.
     */
    private fun request(method: String, path: String, body: String?, prefer: String? = null): String =
        transport.send(method = method, path = path, body = body, prefer = prefer)

    private inline fun <reified T> String.decodeList(): List<T> = json.decodeFromString(this)

    private fun String.toSlug(): String =
        lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "item" }

    private fun String.toStorageBucketId(): String {
        return trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .ifBlank { "exam-resources" }
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        fun fromEnvironment(json: Json): AdminV1Gateway? {
            val url = System.getenv("SUPABASE_URL")?.trimEnd('/').orEmpty()
            // Misma firma y mismo contrato de `null`: `Application.kt` no cambia.
            // Lo único que cambia es QUÉ variable se lee, y eso lo decide un
            // solo sitio (`SUPABASE_SECRET_KEY`, con la legacy como fallback).
            val secretKey = SupabaseRequestAuthPolicy.secretFromEnvironment()
            return if (url.isBlank() || secretKey.isBlank()) null else AdminV1Gateway(url, secretKey, json)
        }

        /**
         * Sólo para tests: inyecta el `HttpClient` para poder observar lo que
         * sale hacia el upstream.
         *
         * El constructor es privado para que producción no pueda saltarse
         * [fromEnvironment]; el coste de las peticiones —cuántas y a qué tablas—
         * es parte del contrato y necesita quedar bajo prueba.
         */
        internal fun forTesting(
            supabaseUrl: String,
            serviceRoleKey: String,
            json: Json,
            client: HttpClient,
            systemMetrics: SystemMetricsProvider = SystemMetricsProvider {
                SystemMetricsSnapshot(null, null, null, null)
            },
            supabaseDisk: SupabaseDiskUsageProvider = SupabaseDiskUsageProvider { null },
        ): AdminV1Gateway = AdminV1Gateway(supabaseUrl, serviceRoleKey, json, client, systemMetrics, supabaseDisk)
    }
}
