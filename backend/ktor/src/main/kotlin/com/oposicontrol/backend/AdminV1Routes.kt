package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
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
import io.ktor.http.content.streamProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
internal fun String.toForoSnippet(maxLength: Int = 240): String {
    val trimmed = trim()
    if (trimmed.length <= maxLength) return trimmed
    return trimmed.take(maxLength).trimEnd() + "…"
}

fun Route.adminV1Routes(gateway: AdminV1Gateway) {
    route("/api/v1/admin") {
        route("/especialidades") {
            get {
                call.respond(AdminEspecialidadesResponse(gateway.listEspecialidades()))
            }
            post {
                val saved = gateway.createEspecialidad(call.receive())
                call.respond(HttpStatusCode.Created, AdminEspecialidadResponse(saved))
            }
            patch("/{id}") {
                val saved = gateway.updateEspecialidad(call.requiredPath("id"), call.receive())
                call.respond(AdminEspecialidadResponse(saved))
            }
            delete("/{id}") {
                val saved = gateway.archiveEspecialidad(call.requiredPath("id"))
                call.respond(AdminEspecialidadResponse(saved))
            }
            // Modos disponibles por especialidad (§ especialidades como configuración
            // de producto). PUT y no PATCH: el cuerpo es el estado completo de los
            // flags, así que enviar uno parcial no debe dejar el resto a medias.
            get("/{id}/capacidades") {
                call.respond(
                    AdminEspecialidadCapacidadesResponse(
                        gateway.getCapacidadesEspecialidad(call.requiredPath("id")),
                    ),
                )
            }
            put("/{id}/capacidades") {
                val saved = gateway.upsertCapacidadesEspecialidad(call.requiredPath("id"), call.receive())
                call.respond(AdminEspecialidadCapacidadesResponse(saved))
            }
        }
        route("/examenes") {
            get {
                call.respond(AdminExamenesResponse(gateway.listExamenes()))
            }
            post {
                val saved = gateway.createExamen(call.receive())
                call.respond(HttpStatusCode.Created, AdminExamenResponse(saved))
            }
            get("/{id}") {
                val examen = gateway.getExamen(call.requiredPath("id"))
                if (examen == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto(error = "not_found", message = "Examen no encontrado."))
                } else {
                    call.respond(AdminExamenResponse(examen))
                }
            }
            get("/{id}/preguntas") {
                call.respond(AdminPreguntasResponse(gateway.listPreguntasExamen(call.requiredPath("id"))))
            }
            patch("/{id}") {
                val saved = gateway.updateExamen(call.requiredPath("id"), call.receive())
                call.respond(AdminExamenResponse(saved))
            }
            patch("/{id}/preguntas/orden") {
                call.respond(AdminPreguntasResponse(gateway.reordenarPreguntasExamen(call.requiredPath("id"), call.receive())))
            }
            post("/{id}/publicar") {
                call.respond(AdminExamenResponse(gateway.publicarExamen(call.requiredPath("id"))))
            }
            post("/{id}/despublicar") {
                call.respond(AdminExamenResponse(gateway.despublicarExamen(call.requiredPath("id"))))
            }
            delete("/{id}") {
                call.respond(AdminExamenResponse(gateway.archiveExamen(call.requiredPath("id"))))
            }
            post("/{id}/portada") {
                val id = call.requiredPath("id")
                val multipart = call.receiveMultipart()
                var fileBytes: ByteArray? = null
                var fileName = "portada.jpg"
                var mimeType = "image/jpeg"
                multipart.forEachPart { part ->
                    try {
                        if (part is PartData.FileItem && part.name == "file") {
                            val stream = part.streamProvider()
                            val maxBytes = 10 * 1024 * 1024
                            val limited = stream.readNBytes(maxBytes + 1)
                            require(limited.size <= maxBytes) { "El archivo supera el limite de 10 MB." }
                            val partMime = part.contentType?.toString().orEmpty()
                            require(partMime.isBlank() || partMime.startsWith("image/")) {
                                "Tipo de archivo no permitido: $partMime. La portada debe ser una imagen."
                            }
                            fileBytes = limited
                            fileName = part.originalFileName ?: fileName
                            mimeType = partMime.ifBlank { mimeType }
                        }
                    } finally {
                        part.dispose()
                    }
                }
                val bytes = fileBytes
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("no_file", "No se recibió archivo."))
                call.respond(AdminExamenResponse(gateway.uploadExamenPortada(id, bytes, fileName, mimeType)))
            }
            delete("/{id}/portada") {
                call.respond(AdminExamenResponse(gateway.clearExamenPortada(call.requiredPath("id"))))
            }
        }
        route("/preguntas") {
            get {
                call.respond(AdminPreguntasResponse(gateway.listPreguntas()))
            }
            post {
                call.respond(HttpStatusCode.Created, AdminPreguntaResponse(gateway.createPregunta(call.receive())))
            }
            get("/{id}") {
                val pregunta = gateway.getPregunta(call.requiredPath("id"))
                if (pregunta == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto(error = "not_found", message = "Pregunta no encontrada."))
                } else {
                    call.respond(AdminPreguntaResponse(pregunta))
                }
            }
            patch("/{id}") {
                call.respond(AdminPreguntaResponse(gateway.updatePregunta(call.requiredPath("id"), call.receive())))
            }
            delete("/{id}") {
                call.respond(AdminPreguntaResponse(gateway.archivePregunta(call.requiredPath("id"))))
            }
            get("/{id}/recursos") {
                call.respond(AdminRecursosResponse(gateway.listRecursos(call.requiredPath("id"))))
            }
            post("/{id}/recursos") {
                call.respond(HttpStatusCode.Created, AdminRecursoResponse(gateway.addRecurso(call.requiredPath("id"), call.receive())))
            }
            patch("/{id}/recursos/{recursoId}") {
                call.respond(AdminRecursoResponse(gateway.updateRecurso(call.requiredPath("id"), call.requiredPath("recursoId"), call.receive())))
            }
            delete("/{id}/recursos/{recursoId}") {
                call.respond(AdminRecursoResponse(gateway.archiveRecurso(call.requiredPath("id"), call.requiredPath("recursoId"))))
            }
        }
        route("/importaciones") {
            route("/preguntas") {
                get {
                    call.respond(AdminLotesResponse(gateway.listLotes()))
                }
                post {
                    call.respond(HttpStatusCode.Created, AdminLoteResponse(gateway.crearLote(call.receive())))
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
                                    val bytes = part.streamProvider().readNBytes(20 * 1024 * 1024 + 1)
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
                    val bytes = pdfBytes
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Campo 'file' con el PDF es obligatorio"))
                    val lote = runCatching {
                        gateway.crearLoteDesdePdf(examenId, tipoExamen, nombreArchivo, bytes)
                    }.getOrElse { error ->
                        return@post call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorDto("pdf_unprocessable", error.message ?: "No se pudo procesar el PDF."),
                        )
                    }
                    call.respond(HttpStatusCode.Created, AdminLoteResponse(lote))
                }
                get("/{loteId}") {
                    val lote = gateway.getLote(call.requiredPath("loteId"))
                    if (lote == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorDto(error = "not_found", message = "Lote no encontrado."))
                    } else {
                        call.respond(AdminLoteResponse(lote))
                    }
                }
            }
            patch("/staging/{id}") {
                call.respond(AdminStagingResponse(gateway.updateStaging(call.requiredPath("id"), call.receive())))
            }
            post("/{loteId}/importar") {
                call.respond(AdminLoteResponse(gateway.importarLote(call.requiredPath("loteId"))))
            }
        }
        route("/rafagas") {
            post("/import") {
                val especialidadId = call.request.queryParameters["especialidadId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "especialidadId es obligatorio"))
                especialidadId.validatedUuid("especialidadId")
                val examenId = call.request.queryParameters["examenId"]
                    ?.takeIf { it.isNotBlank() }
                    ?.also { it.validatedUuid("examenId") }
                logStrategicEvent(
                    StrategicEvent.IMPORT_STARTED,
                    operation = "rafaga_import",
                    entityType = "especialidades",
                    entityId = especialidadId,
                )

                var pdfBytes: ByteArray? = null
                call.receiveMultipart().forEachPart { part ->
                    try {
                        if (part is PartData.FileItem && part.name == "file") {
                            val limited = part.streamProvider().readNBytes(20 * 1024 * 1024 + 1)
                            require(limited.size <= 20 * 1024 * 1024) { "El PDF supera el límite de 20 MB." }
                            pdfBytes = limited
                        }
                    } finally {
                        part.dispose()
                    }
                }

                val bytes = pdfBytes
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Campo 'file' con el PDF es obligatorio"))

                val rawText = extractTextFromPdf(bytes)
                val (preguntas, errores) = parseRafagaText(rawText)

                if (errores.isNotEmpty()) {
                    // count = numero de errores, nunca su contenido (podria
                    // llevar fragmentos del PDF -- ver redaction, prohibido).
                    logStrategicEvent(
                        StrategicEvent.IMPORT_VALIDATION_FAILED,
                        operation = "rafaga_import",
                        result = "validation_failed",
                        entityType = "especialidades",
                        entityId = especialidadId,
                        count = errores.size,
                    )
                    return@post call.respond(
                        HttpStatusCode.UnprocessableEntity,
                        RafagaImportErrorResponse(errores.map { RafagaParseErrorDto(it.numero, it.campo, it.detalle) }),
                    )
                }

                var importadas = 0
                preguntas.forEachIndexed { index, q ->
                    val dto = AdminPreguntaDto(
                        especialidadId = especialidadId,
                        titulo = q.titulo,
                        fuenteTexto = q.fuente,
                        razonamiento = q.razonamiento,
                        opciones = q.opciones.map { (letra, texto) ->
                            AdminOpcionDto(letra = letra, texto = texto, esCorrecta = letra == q.respuesta)
                        },
                        examenId = examenId,
                        posicion = if (examenId != null) index + 1 else null,
                    )
                    val created = gateway.createPregunta(dto)
                    if (q.etiquetas.isNotEmpty()) {
                        // ponytail: etiquetas stored as text resource; reindex via TagSearchGateway associates to entity_tag.
                        gateway.addRecurso(
                            created.id,
                            AdminRecursoDto(tipoRecurso = "etiquetas", contenidoTexto = q.etiquetas.joinToString(","), posicion = 999),
                        )
                    }
                    importadas++
                }

                logStrategicEvent(
                    StrategicEvent.IMPORT_PUBLISHED,
                    operation = "rafaga_import",
                    result = "success",
                    entityType = "especialidades",
                    entityId = especialidadId,
                    count = importadas,
                )
                call.respond(HttpStatusCode.Created, RafagaImportResponse(importadas))
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
                val detail = gateway.getFeedbackDetail(call.requiredPath("id"))
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Incidencia no encontrada."))
                call.respond(AdminFeedbackDetailResponse(detail))
            }
            post("/{id}/resolver") {
                val req = runCatching { call.receive<ResolverFeedbackRequest>() }.getOrDefault(ResolverFeedbackRequest())
                call.respond(AdminFeedbackResponse(gateway.resolverFeedback(call.requiredPath("id"), req)))
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
                gateway.syncRoles(call.requiredPath("id"), body.roleIds)
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
            get("/hilos/{id}/respuestas") {
                val params = call.request.queryParameters
                val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                val cursor = params["cursor"]?.takeIf { it.isNotBlank() }
                call.respond(gateway.getForoHiloRespuestas(call.requiredPath("id"), limit = limit, cursor = cursor))
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
        route("/comunidades-autonomas") {
            get { call.respond(AdminComunidadesResponse(gateway.listComunidades())) }
            post {
                val saved = gateway.createComunidad(call.receive())
                call.respond(HttpStatusCode.Created, AdminComunidadResponse(saved))
            }
            patch("/{id}") {
                val saved = gateway.updateComunidad(call.requiredPath("id"), call.receive())
                call.respond(AdminComunidadResponse(saved))
            }
            delete("/{id}") {
                gateway.deleteComunidad(call.requiredPath("id"))
                call.respond(OkDto())
            }
        }
        get("/dashboard") {
            call.respond(gateway.getDashboardMetrics())
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

// Examen: API en camelCase; la fila Supabase (snake_case) es AdminExamenRow.
@Serializable
data class AdminExamenDto(
    val id: String = "",
    val codigoExamen: String = "",
    val especialidadId: String,
    val comunidadId: String? = null,
    val titulo: String,
    val descripcion: String = "",
    val tipoExamen: String = "rafaga",
    val tipoAcceso: String = "gratuito",
    val anio: Int? = null,
    val esOficial: Boolean = false,
    val activo: Boolean = true,
    val publicadoEn: String? = null,
    val precioTickets: Int = 0,
    val totalPreguntas: Int = 0,
    val duracionMinutos: Int? = null,
    val portadaUrl: String? = null,
    val portadaStoragePath: String? = null,
    val portadaMimeType: String? = null,
)

@Serializable
private data class AdminExamenRow(
    val id: String? = null,
    @SerialName("codigo_examen") val codigoExamen: String = "",
    @SerialName("especialidad_id") val especialidadId: String,
    @SerialName("comunidad_id") val comunidadId: String? = null,
    val titulo: String,
    val descripcion: String = "",
    @SerialName("tipo_examen") val tipoExamen: String = "rafaga",
    @SerialName("tipo_acceso") val tipoAcceso: String = "gratuito",
    val anio: Int? = null,
    @SerialName("es_oficial") val esOficial: Boolean = false,
    val activo: Boolean = true,
    @SerialName("publicado_en") val publicadoEn: String? = null,
    @SerialName("precio_tickets") val precioTickets: Int? = null,
    @SerialName("total_preguntas") val totalPreguntas: Int = 0,
    @SerialName("duracion_minutos") val duracionMinutos: Int? = null,
    @SerialName("portada_url") val portadaUrl: String? = null,
    @SerialName("portada_storage_path") val portadaStoragePath: String? = null,
    @SerialName("portada_mime_type") val portadaMimeType: String? = null,
)

private fun AdminExamenRow.toDto() = AdminExamenDto(
    id = id.orEmpty(),
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
    precioTickets = precioTickets ?: 0,
    totalPreguntas = if (totalPreguntas > 0) totalPreguntas else 1,
    duracionMinutos = duracionMinutos,
    portadaUrl = portadaUrl,
    portadaStoragePath = portadaStoragePath,
    portadaMimeType = portadaMimeType,
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
    totalPreguntas = if (totalPreguntas > 0) totalPreguntas else 1,
    duracionMinutos = duracionMinutos,
)

// ----- Preguntas (pregunta + opciones + solucion + recursos) -----

@Serializable
data class AdminPreguntasResponse(val data: List<AdminPreguntaDto>)

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

@Serializable
data class AdminPreguntaDto(
    val id: String = "",
    val codigoPregunta: String = "",
    val especialidadId: String,
    val tipoPregunta: String = "rafaga",
    val titulo: String,
    val fuenteTexto: String = "",
    val dificultad: String = "media",
    val activa: Boolean = true,
    val razonamiento: String = "",
    val opciones: List<AdminOpcionDto> = emptyList(),
    val recursos: List<AdminRecursoDto> = emptyList(),
    // Asociacion opcional a un examen al crear.
    val examenId: String? = null,
    val posicion: Int? = null,
)

@Serializable
data class AdminRecursoDto(
    val id: String = "",
    val preguntaId: String = "",
    val tipoRecurso: String,
    val titulo: String = "",
    val descripcion: String = "",
    val urlExterna: String? = null,
    val contenidoTexto: String? = null,
    val posicion: Int = 0,
)

@Serializable
private data class AdminPreguntaRow(
    val id: String? = null,
    @SerialName("codigo_pregunta") val codigoPregunta: String,
    @SerialName("especialidad_id") val especialidadId: String,
    @SerialName("tipo_pregunta") val tipoPregunta: String = "rafaga",
    val titulo: String,
    @SerialName("fuente_texto") val fuenteTexto: String = "",
    val dificultad: String = "media",
    val activa: Boolean = true,
)

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

@Serializable
private data class AdminPreguntaExamenRow(
    @SerialName("examen_id") val examenId: String,
    @SerialName("pregunta_id") val preguntaId: String,
    val posicion: Int,
)

@Serializable
data class AdminPreguntaOrdenRequest(val ids: List<String> = emptyList())

@Serializable
private data class AdminRecursoRow(
    val id: String? = null,
    @SerialName("pregunta_id") val preguntaId: String,
    @SerialName("codigo_recurso") val codigoRecurso: String,
    @SerialName("tipo_recurso") val tipoRecurso: String,
    val titulo: String = "",
    val descripcion: String = "",
    @SerialName("url_externa") val urlExterna: String? = null,
    @SerialName("contenido_texto") val contenidoTexto: String? = null,
    val posicion: Int = 0,
    val activo: Boolean = true,
    val metadatos: JsonObject = JsonObject(emptyMap()),
)

private fun AdminRecursoRow.toDto() = AdminRecursoDto(
    id = id.orEmpty(),
    preguntaId = preguntaId,
    tipoRecurso = tipoRecurso,
    titulo = titulo,
    descripcion = descripcion,
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
    val estadoParseo: String = "ok",
    val errorParseo: String? = null,
    val preguntaId: String? = null,
)

@Serializable
data class AdminLoteDto(
    val id: String = "",
    val examenId: String,
    val nombreArchivo: String = "",
    val tipoExamen: String = "rafaga",
    val estado: String = "parseado",
    val totalDetectadas: Int = 0,
    val totalImportadas: Int = 0,
    val staging: List<AdminStagingItemDto> = emptyList(),
)

@Serializable
data class CrearLoteRequest(
    val examenId: String,
    val nombreArchivo: String = "",
    val tipoExamen: String = "rafaga",
    // El parseo PDF->preguntas es responsabilidad externa; aqui se reciben ya parseadas.
    val preguntas: List<AdminStagingItemDto> = emptyList(),
)

@Serializable
private data class LoteRow(
    val id: String? = null,
    @SerialName("examen_id") val examenId: String,
    @SerialName("nombre_archivo_original") val nombreArchivoOriginal: String,
    @SerialName("tipo_examen") val tipoExamen: String,
    val estado: String,
    @SerialName("total_detectadas") val totalDetectadas: Int,
    @SerialName("total_importadas") val totalImportadas: Int = 0,
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
    val metadatos: JsonObject = JsonObject(emptyMap()),
)

@Serializable
private data class StagingPatch(
    val titulo: String? = null,
    @SerialName("opcion_a") val opcionA: String? = null,
    @SerialName("opcion_b") val opcionB: String? = null,
    @SerialName("opcion_c") val opcionC: String? = null,
    @SerialName("opcion_d") val opcionD: String? = null,
    @SerialName("respuesta_correcta") val respuestaCorrecta: String? = null,
    val razonamiento: String? = null,
    @SerialName("fuente_texto") val fuenteTexto: String? = null,
    @SerialName("etiqueta_texto") val etiquetaTexto: String? = null,
    @SerialName("estado_parseo") val estadoParseo: String? = null,
)

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

/** [especialidadNombre] null si la especialidad no se pudo resolver — nunca
 * se muestra el uuid crudo en su lugar. */
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
@Serializable data class RafagaImportResponse(val importadas: Int)
@Serializable data class RafagaParseErrorDto(val pregunta: Int, val campo: String, val detalle: String)
@Serializable data class RafagaImportErrorResponse(val errores: List<RafagaParseErrorDto>)
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

@Serializable
private data class AdminCreateQuestionRpc(
    @SerialName("p_question") val question: AdminPreguntaRow,
    @SerialName("p_options") val options: List<AdminOpcionRow>,
    @SerialName("p_solution") val solution: AdminSolucionRow,
    @SerialName("p_exam_id") val examId: String? = null,
    @SerialName("p_position") val position: Int? = null,
)

@Serializable
private data class AdminReplaceQuestionOptionsRpc(
    @SerialName("p_question_id") val questionId: String,
    @SerialName("p_options") val options: List<AdminOpcionRow>,
    @SerialName("p_solution") val solution: AdminSolucionRow,
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
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    private val client: HttpClient = newUpstreamHttpClient(),
) {
    @Volatile private var storageBucketChecked = false

    // Para ESCRIBIR en Supabase: encodeDefaults=true (envia tipo_examen="rafaga" aunque
    // sea el default) + explicitNulls=false (omite nulls para que apliquen defaults de BD).
    private val sbJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
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
            sbJson.encodeToString(row),
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
    fun listExamenes(): List<AdminExamenDto> =
        request("GET", "/rest/v1/examenes?select=*&order=creado_en.desc", null)
            .decodeList<AdminExamenRow>()
            .map { it.toDto() }

    fun getExamen(id: String): AdminExamenDto? =
        request("GET", "/rest/v1/examenes?select=*&id=eq.$id&limit=1", null)
            .decodeList<AdminExamenRow>()
            .firstOrNull()
            ?.toDto()

    fun createExamen(input: AdminExamenDto): AdminExamenDto {
        require(input.titulo.isNotBlank()) { "El examen necesita titulo." }
        require(input.especialidadId.isNotBlank()) { "El examen necesita especialidad." }
        val row = input.toRow().let {
            if (it.codigoExamen.isBlank()) it.copy(codigoExamen = "EX-" + shortId()) else it
        }
        val saved = request(
            "POST",
            "/rest/v1/examenes",
            sbJson.encodeToString(listOf(row)),
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().first().toDto()
        registrarAuditoria("examen_creado", "examenes", saved.id)
        return saved
    }

    fun updateExamen(id: String, input: AdminExamenDto): AdminExamenDto {
        require(input.titulo.isNotBlank()) { "El examen necesita titulo." }
        return request(
            "PATCH",
            "/rest/v1/examenes?id=eq.$id",
            sbJson.encodeToString(input.toRow(forcedId = id)),
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().firstOrNull()?.toDto()
            ?: error("Examen no encontrado para actualizar: $id")
    }

    fun publicarExamen(id: String): AdminExamenDto {
        val saved = request(
            "PATCH",
            "/rest/v1/examenes?id=eq.$id",
            """{"publicado_en":"${Instant.now()}","activo":true}""",
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().firstOrNull()?.toDto()
            ?: error("Examen no encontrado para publicar.")
        registrarAuditoria("examen_publicado", "examenes", id)
        return saved
    }

    fun despublicarExamen(id: String): AdminExamenDto {
        val saved = request(
            "PATCH",
            "/rest/v1/examenes?id=eq.$id",
            """{"publicado_en":null}""",
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().firstOrNull()?.toDto()
            ?: error("Examen no encontrado para despublicar.")
        registrarAuditoria("examen_despublicado", "examenes", id)
        return saved
    }

    fun archiveExamen(id: String): AdminExamenDto {
        val saved = request(
            "PATCH",
            "/rest/v1/examenes?id=eq.$id",
            """{"activo":false,"publicado_en":null}""",
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().firstOrNull()?.toDto()
            ?: error("Examen no encontrado para archivar.")
        registrarAuditoria("examen_archivado", "examenes", id)
        return saved
    }

    // PREGUNTAS (+ opciones + solucion + recursos + asociacion a examen)
    fun listPreguntas(): List<AdminPreguntaDto> =
        request("GET", "/rest/v1/preguntas?select=*&activa=eq.true&order=creado_en.desc", null)
            .decodeList<AdminPreguntaRow>()
            .map { it.toDtoSinDetalle() }

    fun listPreguntasExamen(examenId: String): List<AdminPreguntaDto> {
        val links = request(
            "GET",
            "/rest/v1/preguntas_examen?select=*&examen_id=eq.$examenId&order=posicion.asc",
            null,
        ).decodeList<AdminPreguntaExamenRow>()
        return links.mapNotNull { link ->
            getPregunta(link.preguntaId)?.takeIf { it.activa }?.copy(
                examenId = examenId,
                posicion = link.posicion,
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
        val correctaId = solucion?.opcionCorrectaId
        return pregunta.toDtoSinDetalle().copy(
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
            recursos = listRecursos(id),
        )
    }

    fun createPregunta(input: AdminPreguntaDto): AdminPreguntaDto {
        require(input.titulo.isNotBlank()) { "La pregunta necesita titulo." }
        require(input.especialidadId.isNotBlank()) { "La pregunta necesita especialidad." }
        require(input.opciones.isNotEmpty()) { "La pregunta necesita opciones." }
        require(input.opciones.count { it.esCorrecta } == 1) { "Marca exactamente una opcion correcta." }

        val preguntaId = input.id.ifBlank { UUID.randomUUID().toString() }
        val opcionRows = input.opciones.mapIndexed { i, o ->
            AdminOpcionRow(
                id = UUID.randomUUID().toString(),
                preguntaId = preguntaId,
                letra = o.letra,
                textoOpcion = o.texto,
                posicion = if (o.posicion > 0) o.posicion else i + 1,
            )
        }
        val correctaId = opcionRows[input.opciones.indexOfFirst { it.esCorrecta }].id!!
        request(
            "POST", "/rest/v1/rpc/admin_create_question",
            sbJson.encodeToString(
                AdminCreateQuestionRpc(
                    question = AdminPreguntaRow(
                        id = preguntaId,
                        codigoPregunta = input.codigoPregunta.ifBlank { "P-" + shortId() },
                        especialidadId = input.especialidadId,
                        tipoPregunta = input.tipoPregunta,
                        titulo = input.titulo,
                        fuenteTexto = input.fuenteTexto,
                        dificultad = input.dificultad,
                        activa = input.activa,
                    ),
                    options = opcionRows,
                    solution = AdminSolucionRow(
                        id = UUID.randomUUID().toString(),
                        preguntaId = preguntaId,
                        opcionCorrectaId = correctaId,
                        razonamiento = input.razonamiento,
                    ),
                    examId = input.examenId?.takeIf { it.isNotBlank() },
                    position = input.posicion,
                ),
            ),
            prefer = "return=minimal",
        )
        registrarAuditoria("pregunta_creada", "preguntas", preguntaId)
        return getPregunta(preguntaId) ?: error("No se pudo leer la pregunta creada.")
    }

    fun updatePregunta(id: String, input: AdminPreguntaDto): AdminPreguntaDto {
        val existing = getPregunta(id) ?: error("Pregunta no encontrada.")
        require(input.titulo.isNotBlank()) { "La pregunta necesita titulo." }
        request(
            "PATCH", "/rest/v1/preguntas?id=eq.$id",
            sbJson.encodeToString(
                AdminPreguntaRow(
                    id = id,
                    codigoPregunta = input.codigoPregunta.ifBlank { existing.codigoPregunta },
                    especialidadId = input.especialidadId.ifBlank { existing.especialidadId },
                    tipoPregunta = input.tipoPregunta,
                    titulo = input.titulo,
                    fuenteTexto = input.fuenteTexto,
                    dificultad = input.dificultad,
                    activa = input.activa,
                ),
            ),
            prefer = "return=minimal",
        )
        if (input.opciones.isNotEmpty()) {
            replaceOpcionesAndSolucion(id, input.opciones, input.razonamiento)
        } else if (input.razonamiento.isNotBlank()) {
            request(
                "PATCH", "/rest/v1/soluciones_pregunta?pregunta_id=eq.$id",
                """{"razonamiento":${sbJson.encodeToString(input.razonamiento)}}""",
                prefer = "return=minimal",
            )
        }
        input.examenId?.takeIf { it.isNotBlank() }?.let { examenId ->
            upsertPreguntaExamen(examenId = examenId, preguntaId = id, posicion = input.posicion ?: 1)
        }
        input.recursos.forEach { recurso ->
            if (recurso.id.isBlank()) {
                addRecurso(id, recurso)
            } else {
                updateRecurso(id, recurso.id, recurso)
            }
        }
        return getPregunta(id) ?: error("No se pudo leer la pregunta actualizada.")
    }

    fun archivePregunta(id: String): AdminPreguntaDto {
        val saved = request(
            "PATCH",
            "/rest/v1/preguntas?id=eq.$id",
            """{"activa":false}""",
            prefer = "return=representation",
        ).decodeList<AdminPreguntaRow>().firstOrNull()
            ?: error("Pregunta no encontrada para archivar: $id")
        registrarAuditoria("pregunta_archivada", "preguntas", id)
        // Return full DTO with opciones/solucion/recursos for consistency with other handlers.
        return getPregunta(id) ?: saved.toDtoSinDetalle()
    }

    fun reordenarPreguntasExamen(examenId: String, input: AdminPreguntaOrdenRequest): List<AdminPreguntaDto> {
        input.ids.forEachIndexed { index, preguntaId ->
            request(
                "PATCH",
                "/rest/v1/preguntas_examen?examen_id=eq.$examenId&pregunta_id=eq.$preguntaId",
                """{"posicion":${index + 1}}""",
                prefer = "return=minimal",
            )
        }
        registrarAuditoria("preguntas_examen_reordenadas", "preguntas_examen", examenId)
        return listPreguntasExamen(examenId)
    }

    fun listRecursos(preguntaId: String): List<AdminRecursoDto> =
        request(
            "GET",
            "/rest/v1/recursos_pregunta?select=*&pregunta_id=eq.$preguntaId&activo=eq.true&order=posicion.asc",
            null,
        ).decodeList<AdminRecursoRow>().map { it.toDto() }

    fun addRecurso(preguntaId: String, input: AdminRecursoDto): AdminRecursoDto {
        require(input.tipoRecurso.isNotBlank()) { "El recurso necesita tipo." }
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
        ).decodeList<AdminRecursoRow>().first().toDto()
        registrarAuditoria("recurso_pregunta_creado", "recursos_pregunta", saved.id)
        return saved
    }

    fun updateRecurso(preguntaId: String, recursoId: String, input: AdminRecursoDto): AdminRecursoDto {
        require(input.tipoRecurso.isNotBlank()) { "El recurso necesita tipo." }
        return request(
            "PATCH",
            "/rest/v1/recursos_pregunta?id=eq.$recursoId&pregunta_id=eq.$preguntaId",
            sbJson.encodeToString(
                AdminRecursoRow(
                    id = recursoId,
                    preguntaId = preguntaId,
                    codigoRecurso = "R-" + recursoId.take(8),
                    tipoRecurso = input.tipoRecurso,
                    titulo = input.titulo,
                    descripcion = input.descripcion,
                    urlExterna = input.urlExterna,
                    contenidoTexto = input.contenidoTexto,
                    posicion = if (input.posicion > 0) input.posicion else 1,
                    activo = true,
                ),
            ),
            prefer = "return=representation",
        ).decodeList<AdminRecursoRow>().firstOrNull()?.toDto()
            ?: error("Recurso no encontrado.")
    }

    fun archiveRecurso(preguntaId: String, recursoId: String): AdminRecursoDto {
        val saved = request(
            "PATCH",
            "/rest/v1/recursos_pregunta?id=eq.$recursoId&pregunta_id=eq.$preguntaId",
            """{"activo":false}""",
            prefer = "return=representation",
        ).decodeList<AdminRecursoRow>().firstOrNull()?.toDto()
            ?: error("Recurso no encontrado.")
        registrarAuditoria("recurso_pregunta_archivado", "recursos_pregunta", recursoId)
        return saved
    }

    private fun AdminPreguntaRow.toDtoSinDetalle() = AdminPreguntaDto(
        id = id.orEmpty(),
        codigoPregunta = codigoPregunta,
        especialidadId = especialidadId,
        tipoPregunta = tipoPregunta,
        titulo = titulo,
        fuenteTexto = fuenteTexto,
        dificultad = dificultad,
        activa = activa,
    )

    private fun replaceOpcionesAndSolucion(preguntaId: String, opciones: List<AdminOpcionDto>, razonamiento: String) {
        require(opciones.count { it.esCorrecta } == 1) { "Marca exactamente una opcion correcta." }
        val opcionRows = opciones.mapIndexed { i, o ->
            AdminOpcionRow(
                id = UUID.randomUUID().toString(),
                preguntaId = preguntaId,
                letra = o.letra.ifBlank { ('A' + i).toString() },
                textoOpcion = o.texto,
                posicion = if (o.posicion > 0) o.posicion else i + 1,
            )
        }
        val correctaIndex = opciones.indexOfFirst { it.esCorrecta }.coerceAtLeast(0)
        request(
            "POST",
            "/rest/v1/rpc/admin_replace_question_options",
            sbJson.encodeToString(
                AdminReplaceQuestionOptionsRpc(
                    questionId = preguntaId,
                    options = opcionRows,
                    solution = AdminSolucionRow(
                        id = UUID.randomUUID().toString(),
                        preguntaId = preguntaId,
                        opcionCorrectaId = opcionRows[correctaIndex].id!!,
                        razonamiento = razonamiento,
                    ),
                ),
            ),
            prefer = "return=minimal",
        )
    }

    private fun upsertPreguntaExamen(examenId: String, preguntaId: String, posicion: Int) {
        val existing = request(
            "GET",
            "/rest/v1/preguntas_examen?select=*&examen_id=eq.$examenId&pregunta_id=eq.$preguntaId&limit=1",
            null,
        ).decodeList<AdminPreguntaExamenRow>().firstOrNull()
        if (existing == null) {
            request(
                "POST",
                "/rest/v1/preguntas_examen",
                sbJson.encodeToString(
                    listOf(AdminPreguntaExamenRow(examenId = examenId, preguntaId = preguntaId, posicion = if (posicion > 0) posicion else 1)),
                ),
                prefer = "return=minimal",
            )
        } else {
            request(
                "PATCH",
                "/rest/v1/preguntas_examen?examen_id=eq.$examenId&pregunta_id=eq.$preguntaId",
                """{"posicion":${if (posicion > 0) posicion else 1}}""",
                prefer = "return=minimal",
            )
        }
    }

    // IMPORTACIONES (lote + staging -> importar)
    fun listLotes(): List<AdminLoteDto> =
        request("GET", "/rest/v1/lotes_importacion_preguntas?select=*&order=creado_en.desc", null)
            .decodeList<LoteRow>()
            .map { it.toDto(emptyList()) }

    fun getLote(loteId: String): AdminLoteDto? {
        val lote = request("GET", "/rest/v1/lotes_importacion_preguntas?select=*&id=eq.$loteId&limit=1", null)
            .decodeList<LoteRow>().firstOrNull() ?: return null
        val staging = request("GET", "/rest/v1/preguntas_parseadas_staging?select=*&lote_id=eq.$loteId&order=posicion.asc", null)
            .decodeList<StagingRow>()
        return lote.toDto(staging.map { it.toDto() })
    }

    fun crearLote(req: CrearLoteRequest): AdminLoteDto {
        require(req.examenId.isNotBlank()) { "El lote necesita examen." }
        val loteId = UUID.randomUUID().toString()
        request(
            "POST", "/rest/v1/lotes_importacion_preguntas",
            sbJson.encodeToString(
                listOf(
                    LoteRow(
                        id = loteId,
                        examenId = req.examenId,
                        nombreArchivoOriginal = req.nombreArchivo.ifBlank { "importacion.pdf" },
                        tipoExamen = req.tipoExamen,
                        estado = "parseado",
                        totalDetectadas = req.preguntas.size,
                    ),
                ),
            ),
            prefer = "return=minimal",
        )
        if (req.preguntas.isNotEmpty()) {
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
                    respuestaCorrecta = p.respuestaCorrecta,
                    razonamiento = p.razonamiento,
                    fuenteTexto = p.fuenteTexto,
                    etiquetaTexto = p.etiquetaTexto,
                    estadoParseo = "ok",
                )
            }
            request("POST", "/rest/v1/preguntas_parseadas_staging", sbJson.encodeToString(rows), prefer = "return=minimal")
        }
        return getLote(loteId) ?: error("No se pudo leer el lote creado.")
    }

    fun crearLoteDesdePdf(
        examenId: String,
        tipoExamen: String,
        nombreArchivo: String,
        pdfBytes: ByteArray,
    ): AdminLoteDto {
        val examen = getExamen(examenId) ?: error("El examen del lote ya no existe.")
        val canonicalType = canonicalExamType(tipoExamen).takeIf { it == canonicalExamType(examen.tipoExamen) }
            ?: canonicalExamType(examen.tipoExamen)
        val rawText = extractTextFromPdf(pdfBytes)
        if (rawText.isBlank()) error("El PDF no contiene texto seleccionable. No se admite OCR en este flujo.")
        val (preguntas, errores) = parseRafagaText(rawText)
        if (errores.isNotEmpty()) {
            error(errores.joinToString(" ") { "Pregunta ${it.numero}: ${it.detalle}." })
        }
        return crearLote(
            CrearLoteRequest(
                examenId = examenId,
                nombreArchivo = nombreArchivo,
                tipoExamen = canonicalType,
                preguntas = preguntas.map { p ->
                    AdminStagingItemDto(
                        posicion = p.numero,
                        bloqueOriginal = p.titulo,
                        titulo = p.titulo,
                        opcionA = p.opciones.getOrNull(0)?.second.orEmpty(),
                        opcionB = p.opciones.getOrNull(1)?.second.orEmpty(),
                        opcionC = p.opciones.getOrNull(2)?.second.orEmpty(),
                        opcionD = p.opciones.getOrNull(3)?.second.orEmpty(),
                        respuestaCorrecta = p.respuesta,
                        razonamiento = p.razonamiento,
                        fuenteTexto = p.fuente,
                        etiquetaTexto = p.etiquetas.joinToString(","),
                    )
                },
            ),
        )
    }

    fun updateStaging(id: String, item: AdminStagingItemDto): AdminStagingItemDto {
        val patch = StagingPatch(
            titulo = item.titulo,
            opcionA = item.opcionA,
            opcionB = item.opcionB,
            opcionC = item.opcionC,
            opcionD = item.opcionD,
            respuestaCorrecta = item.respuestaCorrecta,
            razonamiento = item.razonamiento,
            fuenteTexto = item.fuenteTexto,
            etiquetaTexto = item.etiquetaTexto,
            estadoParseo = item.estadoParseo.ifBlank { "ok" },
        )
        return request(
            "PATCH", "/rest/v1/preguntas_parseadas_staging?id=eq.$id",
            sbJson.encodeToString(patch),
            prefer = "return=representation",
        ).decodeList<StagingRow>().firstOrNull()?.toDto()
            ?: error("Item de staging no encontrado: $id")
    }

    fun importarLote(loteId: String): AdminLoteDto {
        val lote = getLote(loteId) ?: error("Lote no encontrado.")
        val examen = getExamen(lote.examenId) ?: error("El examen del lote ya no existe.")
        val tipoPregunta = canonicalQuestionType(examen.tipoExamen)
        var importadas = 0
        var errores = 0
        val omitidas = lote.staging.count { it.estadoParseo == "omitida" }
        for (s in lote.staging) {
            if (s.preguntaId != null || s.estadoParseo !in setOf("ok", "aprobada")) continue
            try {
                val opciones = listOf("A" to s.opcionA, "B" to s.opcionB, "C" to s.opcionC, "D" to s.opcionD)
                    .filter { it.second.isNotBlank() }
                    .map { (letra, texto) ->
                        AdminOpcionDto(letra = letra, texto = texto, esCorrecta = letra.equals(s.respuestaCorrecta.trim(), ignoreCase = true))
                    }
                val pregunta = createPregunta(
                    AdminPreguntaDto(
                        especialidadId = examen.especialidadId,
                        titulo = s.titulo,
                        tipoPregunta = tipoPregunta,
                        fuenteTexto = s.fuenteTexto,
                        razonamiento = s.razonamiento,
                        opciones = opciones,
                        examenId = lote.examenId,
                        posicion = s.posicion,
                    ),
                )
                request(
                    "PATCH", "/rest/v1/preguntas_parseadas_staging?id=eq.${s.id}",
                    """{"pregunta_id":"${pregunta.id}","estado_parseo":"importada"}""",
                    prefer = "return=minimal",
                )
                importadas++
            } catch (error: Exception) {
                errores++
                val msg = (error.message ?: "error").replace("\"", "'").take(300)
                request(
                    "PATCH", "/rest/v1/preguntas_parseadas_staging?id=eq.${s.id}",
                    """{"estado_parseo":"error","error_parseo":${sbJson.encodeToString(msg)}}""",
                    prefer = "return=minimal",
                )
            }
        }
        val estadoFinal = when {
            errores > 0 || omitidas > 0 -> "parcial"
            importadas > 0 -> "importado"
            else -> "revisar"
        }
        request(
            "PATCH", "/rest/v1/lotes_importacion_preguntas?id=eq.$loteId",
            """{"estado":"$estadoFinal","total_importadas":$importadas}""",
            prefer = "return=minimal",
        )
        registrarAuditoria("lote_$estadoFinal", "lotes_importacion_preguntas", loteId)
        return getLote(loteId) ?: error("No se pudo leer el lote importado.")
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

    private fun LoteRow.toDto(staging: List<AdminStagingItemDto>) = AdminLoteDto(
        id = id.orEmpty(),
        examenId = examenId,
        nombreArchivo = nombreArchivoOriginal,
        tipoExamen = tipoExamen,
        estado = estado,
        totalDetectadas = totalDetectadas,
        totalImportadas = totalImportadas,
        staging = staging,
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
        val filtro = if (estado.isNullOrBlank()) "" else "&estado=eq.$estado"
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
     * humano, título y nombre de especialidad), resuelto en como máximo dos
     * llamadas extra — nunca N+1 (esto es un detalle de un único id, no un
     * listado). [pregunta] es null si la pregunta ya no existe; en ese caso
     * tampoco se resuelve especialidad. */
    fun getFeedbackDetail(id: String): AdminFeedbackDetailDto? {
        val row = request("GET", "/rest/v1/feedback_pregunta?select=*&id=eq.$id&limit=1", null)
            .decodeList<FeedbackRow>().firstOrNull() ?: return null
        val autorNombre = row.usuarioId?.let { resolveUserDisplayNames(listOf(it))[it] }.orEmpty()
        val pregunta = runCatching { getPregunta(row.preguntaId) }.getOrNull()
        val especialidadNombre = pregunta?.especialidadId
            ?.takeIf { it.isNotBlank() }
            ?.let { specId ->
                request("GET", "/rest/v1/especialidades?select=id,nombre&id=eq.$specId&limit=1", null)
                    .decodeList<EspecialidadNombreRow>().firstOrNull()?.nombre
            }
        return buildFeedbackDetailDto(row, autorNombre, pregunta, especialidadNombre)
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
                idempotencyKey = "report-reward:$id",
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

    fun listTicketTransactions(usuarioId: String): List<AdminTicketTransactionDto> =
        request(
            "GET",
            "/rest/v1/transacciones_cartera?select=*&usuario_id=eq.$usuarioId&order=creado_en.desc&limit=100",
            null,
        ).decodeList<TransaccionCarteraRow>().map { it.toDto() }

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

    fun upsertExamAccess(input: AdminExamAccessDto): AdminExamAccessDto {
        val user = findPerfil(input.userName)
        val exam = findExamen(input.examTitle)
        val row = ContenidoDesbloqueadoInsertRow(
            id = input.id.ifBlank { UUID.randomUUID().toString() },
            usuarioId = user.usuarioId,
            examenId = exam.id,
            motivo = input.accessType.ifBlank { "manual" },
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

    fun settings(): AdminSettingsDto {
        return AdminSettingsDto(
            displayName = System.getenv("OPOSICONTROL_ADMIN_DISPLAY_NAME").orEmpty().ifBlank { "Administrador" },
            email = System.getenv("OPOSICONTROL_ADMIN_EMAIL").orEmpty(),
            receiveReleaseNotes = false,
            defaultEnvironment = System.getenv("APP_ENV").orEmpty().ifBlank { "dev" },
            preferFakeData = false,
            enableVerboseLogs = System.getenv("ENABLE_VERBOSE_NETWORK_LOGS")?.toBooleanStrictOrNull() ?: false,
        )
    }

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
            System.err.println("[ktor] auditoria fallida ($accion): ${error.message?.take(160)}")
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

    fun uploadExamenPortada(examId: String, bytes: ByteArray, fileName: String, mimeType: String): AdminExamenDto {
        require(mimeType.startsWith("image/")) { "La portada debe ser una imagen." }
        // Validate exam exists BEFORE touching Storage to avoid orphaned objects.
        val existing = request("GET", "/rest/v1/examenes?select=id,portada_storage_path&id=eq.$examId&limit=1", null)
            .decodeList<AdminExamenRow>().firstOrNull()
            ?: error("Examen no encontrado: $examId")
        val bucket = (System.getenv("SUPABASE_BUCKET_EXAM_RESOURCES") ?: "exam-resources").toStorageBucketId()
        ensureStorageBucketExists(bucket)
        val sanitized = fileName.sanitizeStorageSegment()
        val objectPath = "exam-covers/$examId/$sanitized"
        val encodedPath = objectPath.split('/').joinToString("/") { it.urlEncode() }
        requestBinary("POST", "$supabaseUrl/storage/v1/object/$bucket/$encodedPath", bytes, mimeType, upsert = true)
        val publicUrl = "$supabaseUrl/storage/v1/object/public/$bucket/$encodedPath"
        val saved = try {
            request(
                "PATCH", "/rest/v1/examenes?id=eq.$examId",
                """{"portada_url":"$publicUrl","portada_storage_path":"$objectPath","portada_mime_type":"$mimeType"}""",
                prefer = "return=representation",
            ).decodeList<AdminExamenRow>().firstOrNull()
                ?: error("Examen desaparecio durante la actualizacion de portada: $examId")
        } catch (e: Exception) {
            // DB write failed after Storage upload — delete the orphaned object best-effort.
            runCatching {
                val req = HttpRequest.newBuilder(URI.create("$supabaseUrl/storage/v1/object/$bucket/$encodedPath"))
                    .timeout(UPSTREAM_REQUEST_TIMEOUT)
                    .header("apikey", serviceRoleKey)
                    .header("Authorization", "Bearer $serviceRoleKey")
                    .DELETE().build()
                client.send(req, HttpResponse.BodyHandlers.ofString())
            }.onFailure { System.err.println("[ktor] portada orphan cleanup failed: ${it.message?.take(160)}") }
            throw e
        }
        // Delete previous cover if it was a different storage object.
        existing.portadaStoragePath
            ?.takeIf { it.isNotBlank() && it != objectPath }
            ?.let { prev ->
                val prevEncoded = prev.split('/').joinToString("/") { it.urlEncode() }
                runCatching {
                    val req = HttpRequest.newBuilder(URI.create("$supabaseUrl/storage/v1/object/$bucket/$prevEncoded"))
                        .timeout(UPSTREAM_REQUEST_TIMEOUT)
                        .header("apikey", serviceRoleKey)
                        .header("Authorization", "Bearer $serviceRoleKey")
                        .DELETE().build()
                    client.send(req, HttpResponse.BodyHandlers.ofString())
                }.onFailure { System.err.println("[ktor] portada anterior cleanup failed: ${it.message?.take(160)}") }
            }
        return saved.toDto()
    }

    fun clearExamenPortada(examId: String): AdminExamenDto {
        return request(
            "PATCH", "/rest/v1/examenes?id=eq.$examId",
            """{"portada_url":null,"portada_storage_path":null,"portada_mime_type":null}""",
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().firstOrNull()?.toDto()
            ?: error("Examen no encontrado para borrar portada: $examId")
    }

    private fun ensureStorageBucketExists(bucket: String) {
        if (storageBucketChecked) return
        val body = """{"id":"$bucket","name":"$bucket","public":true}"""
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl/storage/v1/bucket"))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", "application/json")
        val response = try {
            client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw IllegalStateException("Supabase Storage bucket check error: ${e::class.simpleName}: ${e.message}", e)
        }
        if (response.statusCode() in 200..299 || response.statusCode() == 409) {
            storageBucketChecked = true
            return
        }
        throw IllegalStateException("Supabase Storage bucket check -> ${response.statusCode()}: ${response.body().take(300)}")
    }

    private fun shortId(): String = UUID.randomUUID().toString().take(8)

    private fun requestBinary(method: String, url: String, bytes: ByteArray, contentType: String, upsert: Boolean = false): String {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", contentType)
        if (upsert) builder.header("x-upsert", "true")
        val response = try {
            client.send(builder.method(method, HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            // Punto unico de subida a Storage (ver KDoc de requestBinary): un
            // solo sitio cubre cualquier upload futuro sin tocar cada llamador.
            logStrategicEvent(StrategicEvent.STORAGE_UPLOAD_FAILED, operation = method, errorType = e::class.simpleName)
            throw IllegalStateException("Supabase storage error: ${e.message}", e)
        }
        if (response.statusCode() !in 200..299) {
            logStrategicEvent(
                StrategicEvent.STORAGE_UPLOAD_FAILED,
                operation = method,
                result = response.statusCode().toString(),
            )
            throw IllegalStateException("Supabase storage $method -> ${response.statusCode()}: ${response.body().take(200)}")
        }
        return response.body()
    }

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
    private fun exactCount(path: String): Int {
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", "application/json")
            .header("Prefer", "count=exact")
            .header("Range-Unit", "items")
            .header("Range", "0-0")
        val response = try {
            client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException(
                "Supabase network error on COUNT ${path.substringBefore('?')}: ${error::class.simpleName}: ${error.message}",
                error,
            )
        }
        // 206 Partial Content es la respuesta normal a un Range acotado.
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "Supabase COUNT ${path.substringBefore('?')} -> ${response.statusCode()}: ${response.body().take(200)}",
            )
        }
        return parseContentRangeTotal(response.headers().firstValue("content-range").orElse(null))
            ?: throw IllegalStateException("Supabase COUNT ${path.substringBefore('?')} no devolvió Content-Range utilizable.")
    }

    private fun request(method: String, path: String, body: String?, prefer: String? = null): String {
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", "application/json")
        if (prefer != null) builder.header("Prefer", prefer)
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val response = try {
            client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException("Supabase network error on $method ${path.substringBefore('?')}: ${error::class.simpleName}: ${error.message}", error)
        }
        if (response.statusCode() !in 200..299) {
            mapSupabaseDomainError(response.statusCode(), response.body())?.let { throw it }
            throw IllegalStateException("Supabase $method ${path.substringBefore('?')} -> ${response.statusCode()}: ${response.body().take(300)}")
        }
        return response.body()
    }

    private inline fun <reified T> String.decodeList(): List<T> = json.decodeFromString(this)

    private fun String.toSlug(): String =
        lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "item" }

    private fun String.sanitizeStorageSegment(): String {
        return trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .ifBlank { "portada.jpg" }
    }

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
            val serviceRoleKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY").orEmpty()
            return if (url.isBlank() || serviceRoleKey.isBlank()) null else AdminV1Gateway(url, serviceRoleKey, json)
        }
    }
}
