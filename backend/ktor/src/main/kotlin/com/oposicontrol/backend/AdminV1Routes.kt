package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
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
import java.util.UUID

/**
 * Superficie admin v1 contra el ESQUEMA REAL de Supabase (tablas plural, PK uuid,
 * columnas en espanol). Convive con /v1 (lecturas publicas) y /content (legacy).
 * Montado bajo authenticate("gateway-auth").
 *
 * Fase 1a: especialidades + examenes (incl. publicar/despublicar).
 */
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
                    if (part is PartData.FileItem && part.name == "file") {
                        fileBytes = part.streamProvider().readBytes()
                        fileName = part.originalFileName ?: fileName
                        mimeType = part.contentType?.toString() ?: mimeType
                    }
                    part.dispose()
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
        route("/feedback/preguntas") {
            get {
                call.respond(AdminFeedbacksResponse(gateway.listFeedback(call.request.queryParameters["estado"])))
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
                call.respond(AdminUsersResponse(gateway.listUsuarios()))
            }
            get("/{id}") {
                val user = gateway.getUsuario(call.requiredPath("id"))
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Usuario no encontrado."))
                call.respond(AdminUserResponse(user))
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
                val saldo = gateway.grantTickets(call.requiredPath("id"), body.cantidad, body.motivo)
                call.respond(GrantTicketsResponse(saldo))
            }
        }
        route("/reportes") {
            get { call.respond(AdminReportesResponse(gateway.listReportes())) }
            post("/{id}/resolver") {
                gateway.resolverReporte(call.requiredPath("id"), call.receive())
                call.respond(OkDto())
            }
        }
        route("/roles") {
            get {
                call.respond(AdminRolesV1Response(gateway.listRoles()))
            }
        }
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
    }
}

// ---------- Respuestas API (camelCase, contrato con el cliente) ----------

@Serializable
data class AdminEspecialidadesResponse(val data: List<AdminEspecialidadDto>)

@Serializable
data class AdminEspecialidadResponse(val data: AdminEspecialidadDto)

@Serializable
data class AdminExamenesResponse(val data: List<AdminExamenDto>)

@Serializable
data class AdminExamenResponse(val data: AdminExamenDto)

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
    preguntaId = preguntaId,
)

// ----- Feedback de preguntas + Auditoria -----

@Serializable
data class AdminFeedbacksResponse(val data: List<AdminFeedbackDto>)

@Serializable
data class AdminFeedbackResponse(val data: AdminFeedbackDto)

@Serializable
data class AdminFeedbackDto(
    val id: String = "",
    val usuarioId: String? = null,
    val preguntaId: String = "",
    val intentoId: String? = null,
    val tipoFeedback: String = "",
    val descripcion: String = "",
    val estado: String = "pendiente",
)

@Serializable
data class ResolverFeedbackRequest(
    val estado: String = "resuelto", // resuelto | descartado | revisado
    val nota: String = "",
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

@Serializable
private data class FeedbackRow(
    val id: String? = null,
    @SerialName("usuario_id") val usuarioId: String? = null,
    @SerialName("pregunta_id") val preguntaId: String,
    @SerialName("intento_id") val intentoId: String? = null,
    @SerialName("tipo_feedback") val tipoFeedback: String,
    val descripcion: String? = null,
    val estado: String,
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

private fun FeedbackRow.toDto() = AdminFeedbackDto(
    id = id.orEmpty(),
    usuarioId = usuarioId,
    preguntaId = preguntaId,
    intentoId = intentoId,
    tipoFeedback = tipoFeedback,
    descripcion = descripcion.orEmpty(),
    estado = estado,
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
)
@Serializable data class AdminReportesResponse(val data: List<AdminReporteDto>)
@Serializable data class ResolverReporteBody(val accion: String = "descartar", val notas: String? = null, val ticketsReporter: Int = 0)
@Serializable data class GrantTicketsBody(val cantidad: Int, val motivo: String = "Ajuste manual")
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

@Serializable data class AdminUsersResponse(val data: List<AdminV1UserDto>)
@Serializable data class AdminUserResponse(val data: AdminV1UserDto)
@Serializable data class AdminRolesV1Response(val data: List<AdminV1RoleDto>)
@Serializable data class OkDto(val ok: Boolean = true)

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
)

@Serializable
private data class AccionModeracionInsert(
    @SerialName("reporte_id") val reporteId: String,
    @SerialName("tipo_accion") val tipoAccion: String,
    val notas: String? = null,
)

@Serializable
private data class ReportePatch(
    val estado: String,
    @SerialName("revisado_en") val revisadoEn: String,
    @SerialName("tickets_otorgados") val ticketsOtorgados: Int,
    @SerialName("recompensa_entregada") val recompensaEntregada: Boolean,
)

@Serializable
private data class CarteraRow(
    @SerialName("usuario_id") val usuarioId: String = "",
    @SerialName("saldo_tickets") val saldoTickets: Int = 0,
)

@Serializable
private data class TransaccionInsert(
    @SerialName("usuario_id") val usuarioId: String,
    val tipo: String,
    val cantidad: Int,
    @SerialName("saldo_anterior") val saldoAnterior: Int,
    @SerialName("saldo_resultante") val saldoResultante: Int,
    val descripcion: String? = null,
    @SerialName("referencia_tipo") val referenciaTipo: String? = null,
)

@Serializable
private data class CarteraUpsert(
    @SerialName("usuario_id") val usuarioId: String,
    @SerialName("saldo_tickets") val saldoTickets: Int,
    @SerialName("actualizado_en") val actualizadoEn: String,
)

// Rows privados para etiquetas y comunidades
@Serializable
private data class ScoreRow(@SerialName("porcentaje_acierto") val puntuacion: Double? = null)

@Serializable
private data class UsuarioIdRow(@SerialName("usuario_id") val usuarioId: String = "")

@Serializable
private data class EsCorrectaRow(@SerialName("es_correcta") val esCorrecta: Boolean = true)

@Serializable
private data class IniciadoEnRow(@SerialName("iniciado_en") val iniciadoEn: String = "")

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

// ---------- Gateway Supabase (esquema real) ----------

class AdminV1Gateway private constructor(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    private var storageBucketChecked = false

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
        ).decodeList<AdminEspecialidadDto>().first()
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
        ).decodeList<AdminExamenRow>().first().toDto()
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
        request(
            "POST", "/rest/v1/preguntas",
            sbJson.encodeToString(
                listOf(
                    AdminPreguntaRow(
                        id = preguntaId,
                        codigoPregunta = input.codigoPregunta.ifBlank { "P-" + shortId() },
                        especialidadId = input.especialidadId,
                        tipoPregunta = input.tipoPregunta,
                        titulo = input.titulo,
                        fuenteTexto = input.fuenteTexto,
                        dificultad = input.dificultad,
                        activa = input.activa,
                    ),
                ),
            ),
            prefer = "return=minimal",
        )

        val opcionRows = input.opciones.mapIndexed { i, o ->
            AdminOpcionRow(
                id = UUID.randomUUID().toString(),
                preguntaId = preguntaId,
                letra = o.letra,
                textoOpcion = o.texto,
                posicion = if (o.posicion > 0) o.posicion else i + 1,
            )
        }
        request("POST", "/rest/v1/opciones_pregunta", sbJson.encodeToString(opcionRows), prefer = "return=minimal")

        val correctaId = opcionRows[input.opciones.indexOfFirst { it.esCorrecta }].id!!
        request(
            "POST", "/rest/v1/soluciones_pregunta",
            sbJson.encodeToString(
                listOf(
                    AdminSolucionRow(
                        id = UUID.randomUUID().toString(),
                        preguntaId = preguntaId,
                        opcionCorrectaId = correctaId,
                        razonamiento = input.razonamiento,
                    ),
                ),
            ),
            prefer = "return=minimal",
        )

        if (!input.examenId.isNullOrBlank()) {
            request(
                "POST", "/rest/v1/preguntas_examen",
                sbJson.encodeToString(
                    listOf(AdminPreguntaExamenRow(examenId = input.examenId, preguntaId = preguntaId, posicion = input.posicion ?: 1)),
                ),
                prefer = "return=minimal",
            )
        }
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
        request(
            "PATCH",
            "/rest/v1/preguntas?id=eq.$id",
            """{"activa":false}""",
            prefer = "return=minimal",
        )
        registrarAuditoria("pregunta_archivada", "preguntas", id)
        return getPregunta(id) ?: error("No se pudo leer la pregunta archivada.")
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
        request("DELETE", "/rest/v1/soluciones_pregunta?pregunta_id=eq.$preguntaId", null, prefer = "return=minimal")
        request("DELETE", "/rest/v1/opciones_pregunta?pregunta_id=eq.$preguntaId", null, prefer = "return=minimal")
        val opcionRows = opciones.mapIndexed { i, o ->
            AdminOpcionRow(
                id = UUID.randomUUID().toString(),
                preguntaId = preguntaId,
                letra = o.letra.ifBlank { ('A' + i).toString() },
                textoOpcion = o.texto,
                posicion = if (o.posicion > 0) o.posicion else i + 1,
            )
        }
        request("POST", "/rest/v1/opciones_pregunta", sbJson.encodeToString(opcionRows), prefer = "return=minimal")
        val correctaIndex = opciones.indexOfFirst { it.esCorrecta }.coerceAtLeast(0)
        request(
            "POST",
            "/rest/v1/soluciones_pregunta",
            sbJson.encodeToString(
                listOf(
                    AdminSolucionRow(
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
        ).decodeList<StagingRow>().first().toDto()
    }

    fun importarLote(loteId: String): AdminLoteDto {
        val lote = getLote(loteId) ?: error("Lote no encontrado.")
        val examen = getExamen(lote.examenId) ?: error("El examen del lote ya no existe.")
        val tipoPregunta = if (examen.tipoExamen == "inmersivo") "inmersiva" else "rafaga"
        var importadas = 0
        for (s in lote.staging) {
            if (s.preguntaId != null || s.estadoParseo == "importada") continue
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
                val msg = (error.message ?: "error").replace("\"", "'").take(300)
                request(
                    "PATCH", "/rest/v1/preguntas_parseadas_staging?id=eq.${s.id}",
                    """{"estado_parseo":"error","error_parseo":${sbJson.encodeToString(msg)}}""",
                    prefer = "return=minimal",
                )
            }
        }
        request(
            "PATCH", "/rest/v1/lotes_importacion_preguntas?id=eq.$loteId",
            """{"estado":"importado","total_importadas":$importadas}""",
            prefer = "return=minimal",
        )
        registrarAuditoria("lote_importado", "lotes_importacion_preguntas", loteId)
        return getLote(loteId) ?: error("No se pudo leer el lote importado.")
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
    fun listFeedback(estado: String?): List<AdminFeedbackDto> {
        val filtro = if (estado.isNullOrBlank()) "" else "&estado=eq.$estado"
        return request("GET", "/rest/v1/feedback_pregunta?select=*$filtro&order=creado_en.desc", null)
            .decodeList<FeedbackRow>()
            .map { it.toDto() }
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
        ).decodeList<FeedbackRow>().firstOrNull()?.toDto() ?: error("Feedback no encontrado.")
        registrarAuditoria("feedback_$estado", "feedback_pregunta", id)
        return saved
    }

    // USUARIOS (perfiles_usuario + roles_usuario)
    fun listUsuarios(): List<AdminV1UserDto> {
        val perfiles = request("GET", "/rest/v1/perfiles_usuario?select=*&order=creado_en.desc&limit=200", null)
            .decodeList<PerfilUsuarioRow>()
        val rolAsignaciones = request("GET", "/rest/v1/roles_usuario?select=*", null)
            .decodeList<RolUsuarioRow>()
        val rolesByUser = rolAsignaciones.groupBy({ it.usuarioId }, { it.rolId })
        val balanceByUser = request("GET", "/rest/v1/carteras_usuario?select=usuario_id,saldo_tickets", null)
            .decodeList<CarteraRow>().associate { it.usuarioId to it.saldoTickets }
        return perfiles.map { p ->
            AdminV1UserDto(
                id = p.usuarioId,
                fullName = p.nombreMostrar.orEmpty(),
                email = p.username.orEmpty(),
                roleIds = rolesByUser[p.usuarioId].orEmpty(),
                isActive = !p.esInvitado,
                createdAt = p.creadoEn.orEmpty(),
                ticketsBalance = balanceByUser[p.usuarioId] ?: 0,
            )
        }
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
        return getUsuario(usuarioId) ?: input.copy(id = usuarioId)
    }

    fun syncRoles(usuarioId: String, roleIds: List<String>) {
        request("DELETE", "/rest/v1/roles_usuario?usuario_id=eq.$usuarioId", null)
        if (roleIds.isNotEmpty()) {
            val rows = roleIds.joinToString(",") { """{"usuario_id":"$usuarioId","rol_id":"$it"}""" }
            request("POST", "/rest/v1/roles_usuario", "[$rows]", prefer = "return=minimal")
        }
    }

    // MODERACIÓN FORO (reportes_contenido + acciones_moderacion)
    fun listReportes(): List<AdminReporteDto> {
        val reportes = request(
            "GET",
            "/rest/v1/reportes_contenido?select=*&estado=eq.pendiente&order=creado_en.asc&limit=200",
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
            )
        }
    }

    fun resolverReporte(id: String, req: ResolverReporteBody) {
        require(req.accion.isNotBlank()) { "La acción es obligatoria." }
        val reporte = request("GET", "/rest/v1/reportes_contenido?select=*&id=eq.$id&limit=1", null)
            .decodeList<ReporteRow>().firstOrNull() ?: error("Reporte no encontrado.")
        val estado = if (req.accion == "descartar") "descartado" else "resuelto"
        val accion = AccionModeracionInsert(reporteId = id, tipoAccion = req.accion, notas = req.notas)
        request("POST", "/rest/v1/acciones_moderacion", sbJson.encodeToString(listOf(accion)), prefer = "return=minimal")
        val reward = req.ticketsReporter > 0
        val patch = ReportePatch(
            estado = estado,
            revisadoEn = Instant.now().toString(),
            ticketsOtorgados = req.ticketsReporter,
            recompensaEntregada = reward,
        )
        request("PATCH", "/rest/v1/reportes_contenido?id=eq.$id", sbJson.encodeToString(patch), prefer = "return=minimal")
        if (reward && !reporte.reportadoPor.isNullOrBlank()) {
            grantTickets(reporte.reportadoPor, req.ticketsReporter, "Recompensa por reporte", tipo = "reporte_validado")
        }
        registrarAuditoria("reporte_$estado", "reportes_contenido", id)
    }

    // TICKETS MANUALES (carteras_usuario + transacciones_cartera)
    // tipo debe ser un valor permitido por el CHECK: "ajuste" (manual) / "reporte_validado" (recompensa)
    fun grantTickets(usuarioId: String, cantidad: Int, motivo: String, tipo: String = "ajuste"): Int {
        require(cantidad != 0) { "La cantidad de tickets no puede ser 0." }
        val saldoAnterior = request(
            "GET", "/rest/v1/carteras_usuario?select=saldo_tickets&usuario_id=eq.$usuarioId&limit=1", null,
        ).decodeList<CarteraRow>().firstOrNull()?.saldoTickets ?: 0
        val saldoResultante = saldoAnterior + cantidad
        require(saldoResultante >= 0) { "El saldo resultante no puede ser negativo (actual: $saldoAnterior)." }
        val tx = TransaccionInsert(
            usuarioId = usuarioId,
            tipo = tipo,
            cantidad = cantidad,
            saldoAnterior = saldoAnterior,
            saldoResultante = saldoResultante,
            descripcion = motivo,
            referenciaTipo = "admin",
        )
        request("POST", "/rest/v1/transacciones_cartera", sbJson.encodeToString(listOf(tx)), prefer = "return=minimal")
        val cartera = CarteraUpsert(usuarioId = usuarioId, saldoTickets = saldoResultante, actualizadoEn = Instant.now().toString())
        request(
            "POST",
            "/rest/v1/carteras_usuario?on_conflict=usuario_id",
            sbJson.encodeToString(listOf(cartera)),
            prefer = "resolution=merge-duplicates,return=minimal",
        )
        registrarAuditoria("tickets_grant", "carteras_usuario", usuarioId)
        return saldoResultante
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
            .decodeList<V1EtiquetaRow>().first().let { AdminEtiquetaDto(it.id.orEmpty(), it.nombre, it.slug, it.descripcion, it.colorHex, it.activa) }
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
            .decodeList<V1ComunidadRow>().first().let { AdminComunidadDto(it.id.orEmpty(), it.nombre, it.codigo, it.slug, it.activa, it.posicion) }
    }

    fun deleteComunidad(id: String) {
        request("DELETE", "/rest/v1/comunidades_autonomas?id=eq.$id", null)
        registrarAuditoria("comunidad_eliminada", "comunidades_autonomas", id)
    }

    // DASHBOARD — conteos reales de tablas del esquema activo
    fun getDashboardMetrics(): AdminDashboardDto {
        fun count(table: String, filter: String = "") = try {
            val body = request("GET", "/rest/v1/$table?select=id${if (filter.isNotBlank()) "&$filter" else ""}", null)
            json.decodeFromString<List<kotlinx.serialization.json.JsonObject>>(body).size
        } catch (_: Exception) { 0 }
        val now = Instant.now()
        val today = LocalDate.now(ZoneOffset.UTC)
        val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS).toString()
        val totalExams = count("examenes")
        val published = count("examenes", "publicado_en=not.is.null&activo=eq.true")
        val totalAttempts = count("intentos_examen", "estado=eq.completado")
        val activeUsers30d = runCatching {
            request(
                "GET",
                "/rest/v1/intentos_examen?select=usuario_id&estado=eq.completado&iniciado_en=gte.$thirtyDaysAgo",
                null,
            ).decodeList<UsuarioIdRow>()
                .map { it.usuarioId }
                .filter { it.isNotBlank() }
                .distinct()
                .size
        }.getOrDefault(0)
        val avgScore = runCatching {
            val values = request(
                "GET",
                "/rest/v1/intentos_examen?select=porcentaje_acierto&estado=eq.completado",
                null,
            ).decodeList<ScoreRow>().mapNotNull { it.puntuacion }
            if (values.isEmpty()) 0.0 else values.average()
        }.getOrDefault(0.0)
        val attemptsPerDay = runCatching {
            val grouped = request(
                "GET",
                "/rest/v1/intentos_examen?select=iniciado_en&estado=eq.completado&iniciado_en=gte.$thirtyDaysAgo&order=iniciado_en.asc&limit=500",
                null,
            ).decodeList<IniciadoEnRow>()
                .mapNotNull { row -> row.iniciadoEn.toUtcDateOrNull() }
                .groupingBy { it }
                .eachCount()
            (29 downTo 0).map { daysAgo ->
                val date = today.minusDays(daysAgo.toLong())
                DayCountDto(date = date.toString(), count = grouped[date] ?: 0)
            }
        }.getOrDefault(emptyList())
        val globalErrorRate = runCatching {
            val answers = request("GET", "/rest/v1/respuestas_intento?select=es_correcta&limit=5000", null)
                .decodeList<EsCorrectaRow>()
            if (answers.isEmpty()) 0.0 else answers.count { !it.esCorrecta } * 100.0 / answers.size
        }.getOrDefault(0.0)
        return AdminDashboardDto(
            totalSpecialties = count("especialidades"),
            totalExams = totalExams,
            publishedExams = published,
            draftExams = totalExams - published,
            totalQuestions = count("preguntas"),
            lastUpdatedAt = now.toString(),
            totalAttempts = totalAttempts,
            activeUsers30d = activeUsers30d,
            avgScore = avgScore,
            globalErrorRate = globalErrorRate,
            attemptsPerDay = attemptsPerDay,
        )
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

    // Registro de acciones sensibles. No debe tumbar la operacion principal si falla.
    // TODO(fase4): rellenar usuario_id desde el JWT de Supabase.
    fun registrarAuditoria(accion: String, tabla: String?, registroId: String?) {
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

    fun uploadExamenPortada(examId: String, bytes: ByteArray, fileName: String, mimeType: String): AdminExamenDto {
        require(mimeType.startsWith("image/")) { "La portada debe ser una imagen." }
        val bucket = (System.getenv("SUPABASE_BUCKET_EXAM_RESOURCES") ?: "exam-resources").toStorageBucketId()
        ensureStorageBucketExists(bucket)
        val sanitized = fileName.sanitizeStorageSegment()
        val objectPath = "exam-covers/$examId/$sanitized"
        val encodedPath = objectPath.split('/').joinToString("/") { it.urlEncode() }
        requestBinary("POST", "$supabaseUrl/storage/v1/object/$bucket/$encodedPath", bytes, mimeType, upsert = true)
        val publicUrl = "$supabaseUrl/storage/v1/object/public/$bucket/$encodedPath"
        return request(
            "PATCH", "/rest/v1/examenes?id=eq.$examId",
            """{"portada_url":"$publicUrl","portada_storage_path":"$objectPath","portada_mime_type":"$mimeType"}""",
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().first().toDto()
    }

    fun clearExamenPortada(examId: String): AdminExamenDto {
        return request(
            "PATCH", "/rest/v1/examenes?id=eq.$examId",
            """{"portada_url":null,"portada_storage_path":null,"portada_mime_type":null}""",
            prefer = "return=representation",
        ).decodeList<AdminExamenRow>().first().toDto()
    }

    private fun ensureStorageBucketExists(bucket: String) {
        if (storageBucketChecked) return
        val body = """{"id":"$bucket","name":"$bucket","public":true}"""
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl/storage/v1/bucket"))
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
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", contentType)
        if (upsert) builder.header("x-upsert", "true")
        val response = try {
            client.send(builder.method(method, HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw IllegalStateException("Supabase storage error: ${e.message}", e)
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Supabase storage $method -> ${response.statusCode()}: ${response.body().take(200)}")
        }
        return response.body()
    }

    private fun request(method: String, path: String, body: String?, prefer: String? = null): String {
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
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
            throw IllegalStateException("Supabase $method ${path.substringBefore('?')} -> ${response.statusCode()}: ${response.body().take(300)}")
        }
        return response.body()
    }

    private inline fun <reified T> String.decodeList(): List<T> = json.decodeFromString(this)

    private fun String.toUtcDateOrNull(): LocalDate? =
        runCatching { Instant.parse(this).atZone(ZoneOffset.UTC).toLocalDate() }.getOrNull()

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
