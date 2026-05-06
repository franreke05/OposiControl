package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val STORED_QUESTION_PREFIX = "__OPOSICONTROL_QUESTION_V1__\n"

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST").orEmpty().ifBlank { "0.0.0.0" }
    embeddedServer(Netty, host = host, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = false
    }
    val gateway = SupabaseContentGateway.fromEnvironment(json)
        ?: error("SUPABASE_URL y SUPABASE_SERVICE_ROLE_KEY son obligatorios para servir contenido desde Ktor.")
    val newsGateway = SupabaseNewsGateway.fromEnvironment(json)
        ?: error("SUPABASE_URL y SUPABASE_SERVICE_ROLE_KEY son obligatorios para servir noticias desde Ktor.")
    val tagSearchGateway = SupabaseTagSearchGateway.fromEnvironment(json)
        ?: error("SUPABASE_URL y SUPABASE_SERVICE_ROLE_KEY son obligatorios para servir busqueda desde Ktor.")
    val adminGateway = SupabaseAdminGateway.fromEnvironment(json)
        ?: error("SUPABASE_URL y SUPABASE_SERVICE_ROLE_KEY son obligatorios para servir admin desde Ktor.")
    install(ContentNegotiation) {
        json(json)
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(cause.message ?: "Invalid request"))
        }
        exception<IllegalStateException> { call, cause ->
            System.err.println("[ktor] backend/supabase error: ${cause.message}")
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorDto(cause.message?.take(300) ?: "Backend error", error = "backend_supabase_error"),
            )
        }
        exception<Throwable> { call, cause ->
            System.err.println("[ktor] unexpected error ${cause::class.simpleName}: ${cause.message?.take(200)}")
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorDto(
                    "${cause::class.simpleName}: ${cause.message?.take(200)}",
                    error = "backend_internal_error",
                ),
            )
        }
    }
    install(Authentication) {
        bearer("gateway-auth") {
            authenticate { credentials ->
                val expected = System.getenv("OPOSICONTROL_BACKEND_TOKEN").orEmpty()
                val allowDevToken = System.getenv("OPOSICONTROL_ALLOW_DEV_TOKEN")
                    ?.toBooleanStrictOrNull()
                    ?: false
                if (expected.isNotBlank() && (credentials.token == expected || (allowDevToken && credentials.token == "dev-local"))) {
                    UserIdPrincipal("oposicontrol-admin")
                } else {
                    null
                }
            }
        }
    }
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        authenticate("gateway-auth") {
            adminRoutes(adminGateway)
            route("/content") {
                route("/especialidades") {
                    get { call.respond(gateway.listEspecialidades()) }
                    post {
                        val saved = gateway.saveEspecialidad(call.receive())
                        tagSearchGateway.indexEspecialidad(saved)
                        call.respond(HttpStatusCode.Created, saved)
                    }
                    put {
                        val saved = gateway.saveEspecialidad(call.receive())
                        tagSearchGateway.indexEspecialidad(saved)
                        call.respond(saved)
                    }
                    delete("/{especialidadId}") {
                        val id = call.requiredPath("especialidadId")
                        gateway.deleteEspecialidad(id)
                        tagSearchGateway.remove("especialidad", id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
                route("/solucionarios") {
                    get { call.respond(gateway.listSolucionarios()) }
                    post {
                        val saved = gateway.saveSolucionario(call.receive())
                        tagSearchGateway.indexSolucionario(saved)
                        call.respond(HttpStatusCode.Created, saved)
                    }
                    put {
                        val saved = gateway.saveSolucionario(call.receive())
                        tagSearchGateway.indexSolucionario(saved)
                        call.respond(saved)
                    }
                    delete("/{solucionarioId}") {
                        val id = call.requiredPath("solucionarioId")
                        gateway.deleteSolucionario(id)
                        tagSearchGateway.remove("examen", id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    get("/{solucionarioId}/preguntas") {
                        call.respond(gateway.listPreguntas(call.requiredPath("solucionarioId")))
                    }
                    post("/{solucionarioId}/preguntas") {
                        val saved = gateway.savePregunta(call.requiredPath("solucionarioId"), call.receive())
                        tagSearchGateway.indexPregunta(saved)
                        call.respond(
                            HttpStatusCode.Created,
                            saved,
                        )
                    }
                    put("/{solucionarioId}/preguntas") {
                        val saved = gateway.savePregunta(call.requiredPath("solucionarioId"), call.receive())
                        tagSearchGateway.indexPregunta(saved)
                        call.respond(saved)
                    }
                    delete("/{solucionarioId}/preguntas/{preguntaId}") {
                        val id = call.requiredPath("preguntaId")
                        gateway.deletePregunta(call.requiredPath("solucionarioId"), id)
                        tagSearchGateway.remove("pregunta", id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    put("/{solucionarioId}/preguntas/order") {
                        call.respond(gateway.reorderPreguntas(call.requiredPath("solucionarioId"), call.receive()))
                    }
                }
                route("/preguntas") {
                    post("/{preguntaId}/recursos") {
                        val saved = gateway.saveRecurso(call.requiredPath("preguntaId"), call.receive())
                        tagSearchGateway.indexRecurso(saved)
                        call.respond(
                            HttpStatusCode.Created,
                            saved,
                        )
                    }
                    put("/{preguntaId}/recursos/order") {
                        call.respond(gateway.reorderRecursos(call.requiredPath("preguntaId"), call.receive()))
                    }
                }
                route("/noticias") {
                    get { call.respond(newsGateway.listNoticias()) }
                    post {
                        val saved = newsGateway.saveNoticia(call.receive())
                        tagSearchGateway.indexNoticia(saved)
                        call.respond(HttpStatusCode.Created, saved)
                    }
                    put {
                        val saved = newsGateway.saveNoticia(call.receive())
                        tagSearchGateway.indexNoticia(saved)
                        call.respond(saved)
                    }
                    delete("/{noticiaId}") {
                        val id = call.requiredPath("noticiaId")
                        newsGateway.deleteNoticia(id)
                        tagSearchGateway.remove("noticia", id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
            get("/tags") {
                call.respond(tagSearchGateway.listTags(call.request.queryParameters["q"].orEmpty()))
            }
            get("/search") {
                call.respond(
                    tagSearchGateway.search(
                        query = call.request.queryParameters["q"].orEmpty(),
                        tagSlugs = call.request.queryParameters["tags"].orEmpty()
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotBlank() },
                        section = call.request.queryParameters["section"],
                    ),
                )
            }
            post("/search/reindex") {
                call.respond(tagSearchGateway.reindexAll())
            }
        }
    }
}

fun io.ktor.server.application.ApplicationCall.requiredPath(name: String): String {
    return parameters[name]?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing path parameter $name")
}

@Serializable
data class ErrorDto(val message: String, val error: String = "error")

@Serializable
data class SolucionarioDto(
    val id: String = "",
    val titulo: String,
    val especialidadId: String,
    val descripcion: String = "",
    val precioTickets: Int = 0,
    val estado: String = "draft",
    val orden: Int = 0,
)

@Serializable
data class PreguntaDto(
    val id: String = "",
    val solucionarioId: String = "",
    val titulo: String = "",
    val enunciado: String,
    val orden: Int = 0,
    val opciones: List<OpcionDto> = emptyList(),
    val recursos: List<RecursoPreguntaDto> = emptyList(),
)

@Serializable
data class OpcionDto(
    val id: String = "",
    val label: String,
    val contenido: String,
    val esCorrecta: Boolean,
    val orden: Int,
)

@Serializable
data class RecursoPreguntaDto(
    val id: String = "",
    val preguntaId: String = "",
    val tipoRecurso: String,
    val titulo: String = "",
    val url: String = "",
    val storagePath: String? = null,
    val publicUrl: String? = null,
    val orden: Int = 0,
)

@Serializable
data class OrderDto(val ids: List<String>)

@Serializable
data class EspecialidadDto(
    val id: String = "",
    val nombre: String,
    val slug: String = "",
    val activa: Boolean = true,
    val linkedExams: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
)

private class SupabaseContentGateway private constructor(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    fun listEspecialidades(): List<EspecialidadDto> {
        val rows = request("GET", "/rest/v1/especialidad?select=*&deleted_at=is.null&order=nombre.asc", null)
            .decodeList<EspecialidadRow>()
        val linkedCounts = request("GET", "/rest/v1/examen?select=id,especialidad_id&deleted_at=is.null", null)
            .decodeList<ExamenCountRow>()
            .groupingBy { it.especialidadId.toString() }
            .eachCount()
        return rows.map { it.toDto(linkedCounts[it.id?.toString().orEmpty()] ?: 0) }
    }

    fun saveEspecialidad(input: EspecialidadDto): EspecialidadDto {
        require(input.nombre.isNotBlank()) { "La especialidad necesita nombre." }
        val saved = request(
            "POST",
            "/rest/v1/especialidad?on_conflict=id",
            json.encodeToString(listOf(input.toRow())),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<EspecialidadRow>().first()
        return saved.toDto(linkedExams = 0)
    }

    fun deleteEspecialidad(especialidadId: String) {
        request("PATCH", "/rest/v1/especialidad?id=eq.${especialidadId.toIntId()}", """{"deleted_at":"${java.time.Instant.now()}"}""")
    }

    fun listSolucionarios(): List<SolucionarioDto> {
        val specialties = listEspecialidades().associateBy { it.id }
        val counts = request("GET", "/rest/v1/pregunta?select=id,examen_id&deleted_at=is.null", null)
            .decodeList<PreguntaCountRow>()
            .groupingBy { it.examenId.toString() }
            .eachCount()
        return request("GET", "/rest/v1/examen?select=*&deleted_at=is.null&order=created_at.desc", null)
            .decodeList<ExamenRow>()
            .map { it.toDto(specialties[it.especialidadId.toString()]?.nombre.orEmpty(), counts[it.id?.toString().orEmpty()] ?: 0) }
    }

    fun saveSolucionario(input: SolucionarioDto): SolucionarioDto {
        require(input.titulo.isNotBlank()) { "El solucionario necesita un nombre." }
        require(input.especialidadId.isNotBlank()) { "El solucionario necesita una especialidad." }
        require(input.estado in setOf("draft", "published", "archived")) { "Estado de solucionario no valido." }
        val saved = request(
            "POST",
            "/rest/v1/examen?on_conflict=id",
            json.encodeToString(listOf(input.toRow())),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<ExamenRow>().first()
        return saved.toDto("", 0)
    }

    fun deleteSolucionario(solucionarioId: String) {
        request("PATCH", "/rest/v1/examen?id=eq.${solucionarioId.toIntId()}", """{"deleted_at":"${java.time.Instant.now()}"}""")
    }

    fun listPreguntas(solucionarioId: String): List<PreguntaDto> {
        val questions = request(
            "GET",
            "/rest/v1/pregunta?select=*&examen_id=eq.${solucionarioId.toIntId()}&deleted_at=is.null&order=orden.asc",
            null,
        ).decodeList<PreguntaRow>()
        if (questions.isEmpty()) return emptyList()
        val ids = questions.mapNotNull { it.id }.joinToString(",")
        val resources = request(
            "GET",
            "/rest/v1/recurso_pregunta?select=*&pregunta_id=in.($ids)&deleted_at=is.null&order=id.asc",
            null,
        ).decodeList<RecursoPreguntaRow>().groupBy { it.preguntaId }
        return questions.map { row -> row.toDto(resources[row.id].orEmpty().map { it.toDto() }) }
    }

    fun savePregunta(solucionarioId: String, input: PreguntaDto): PreguntaDto {
        validatePregunta(input)
        val saved = request(
            "POST",
            "/rest/v1/pregunta?on_conflict=id",
            json.encodeToString(listOf(input.toRow(solucionarioId, json))),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<PreguntaRow>().first()
        val savedId = saved.id ?: error("Supabase no devolvio id de pregunta.")
        replaceResources(savedId, input.recursos)
        return saved.toDto(input.recursos.map { it.copy(preguntaId = savedId.toString()) })
    }

    fun deletePregunta(solucionarioId: String, preguntaId: String) {
        request("PATCH", "/rest/v1/pregunta?id=eq.${preguntaId.toIntId()}&examen_id=eq.${solucionarioId.toIntId()}", """{"deleted_at":"${java.time.Instant.now()}"}""")
    }

    fun reorderPreguntas(solucionarioId: String, order: OrderDto): List<PreguntaDto> {
        val current = listPreguntas(solucionarioId)
        require(order.ids.toSet() == current.map { it.id }.toSet()) { "El orden debe contener las mismas preguntas." }
        val byId = current.associateBy { it.id }
        val reordered = order.ids.mapIndexed { index, id -> byId.getValue(id).copy(orden = index) }
        val rows = reordered.map { it.toRow(solucionarioId, json) }
        request(
            "POST",
            "/rest/v1/pregunta?on_conflict=id",
            json.encodeToString(rows),
            prefer = "resolution=merge-duplicates,return=minimal",
        )
        return reordered
    }

    fun saveRecurso(preguntaId: String, input: RecursoPreguntaDto): RecursoPreguntaDto {
        validateRecurso(input)
        val saved = request(
            "POST",
            "/rest/v1/recurso_pregunta?on_conflict=id",
            json.encodeToString(listOf(input.toRow(preguntaId))),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<RecursoPreguntaRow>().first()
        return saved.toDto()
    }

    fun reorderRecursos(preguntaId: String, order: OrderDto): List<RecursoPreguntaDto> {
        val current = request(
            "GET",
            "/rest/v1/recurso_pregunta?select=*&pregunta_id=eq.${preguntaId.toIntId()}&deleted_at=is.null&order=id.asc",
            null,
        ).decodeList<RecursoPreguntaRow>().map { it.toDto() }
        require(order.ids.toSet() == current.map { it.id }.toSet()) { "El orden debe contener los mismos recursos." }
        return order.ids.mapNotNull { id -> current.firstOrNull { it.id == id } }
    }

    private fun replaceResources(questionId: Int, resources: List<RecursoPreguntaDto>) {
        request("DELETE", "/rest/v1/recurso_pregunta?pregunta_id=eq.$questionId", null)
        if (resources.isNotEmpty()) {
            request(
                "POST",
                "/rest/v1/recurso_pregunta",
                json.encodeToString(resources.map { it.toRow(questionId.toString()) }),
                prefer = "return=minimal",
            )
        }
    }

    private fun validatePregunta(pregunta: PreguntaDto) {
        require(pregunta.enunciado.isNotBlank()) { "La pregunta necesita enunciado." }
        if (pregunta.opciones.isNotEmpty()) {
            require(pregunta.opciones.size == 4) { "Una pregunta tipo test debe tener exactamente 4 opciones." }
            require(pregunta.opciones.count { it.esCorrecta } == 1) { "Una pregunta tipo test debe tener exactamente una opcion correcta." }
        }
    }

    private fun validateRecurso(recurso: RecursoPreguntaDto) {
        require(recurso.tipoRecurso in setOf("imagen", "audio", "video", "documento")) { "Tipo de recurso no valido." }
        require(recurso.url.isNotBlank() || !recurso.storagePath.isNullOrBlank() || !recurso.publicUrl.isNullOrBlank()) {
            "El recurso necesita una URL o ruta de archivo."
        }
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
        } catch (e: Exception) {
            throw IllegalStateException("Supabase network error on $method ${path.substringBefore('?')}: ${e::class.simpleName}: ${e.message}", e)
        }
        if (response.statusCode() !in 200..299) {
            val preview = response.body().take(300)
            throw IllegalStateException("Supabase $method ${path.substringBefore('?')} -> ${response.statusCode()}: $preview")
        }
        return response.body()
    }

    private inline fun <reified T> String.decodeList(): List<T> = json.decodeFromString(this)

    companion object {
        fun fromEnvironment(json: Json): SupabaseContentGateway? {
            val url = System.getenv("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY").orEmpty()
            return if (url.isBlank() || serviceRoleKey.isBlank()) null else SupabaseContentGateway(url, serviceRoleKey, json)
        }
    }
}

@Serializable
private data class EspecialidadRow(
    val id: Int? = null,
    val nombre: String,
    val slug: String = "",
    val activa: Boolean = true,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String? = null,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
private data class ExamenRow(
    val id: Int? = null,
    val titulo: String,
    @kotlinx.serialization.SerialName("precio_tickets") val precioTickets: Int = 0,
    @kotlinx.serialization.SerialName("especialidad_id") val especialidadId: Int,
    val estado: String = "draft",
    @kotlinx.serialization.SerialName("publicar_desde") val publicarDesde: String? = null,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String? = null,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
private data class ExamenCountRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("especialidad_id") val especialidadId: Int,
)

@Serializable
private data class PreguntaRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("examen_id") val examenId: Int,
    val enunciado: String,
    val orden: Int = 0,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String? = null,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
private data class PreguntaCountRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("examen_id") val examenId: Int,
)

@Serializable
private data class RecursoPreguntaRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("pregunta_id") val preguntaId: Int,
    @kotlinx.serialization.SerialName("tipo_recurso") val tipoRecurso: String,
    val url: String,
)

@Serializable
private data class StoredQuestion(
    val titulo: String = "",
    val enunciado: String = "",
    val opciones: List<OpcionDto> = emptyList(),
)

private fun EspecialidadRow.toDto(linkedExams: Int) = EspecialidadDto(
    id = id?.toString().orEmpty(),
    nombre = nombre,
    slug = slug,
    activa = activa,
    linkedExams = linkedExams,
    createdAt = createdAt.orEmpty(),
    updatedAt = updatedAt.orEmpty(),
)

private fun EspecialidadDto.toRow() = EspecialidadRow(
    id = id.toIntOrNull(),
    nombre = nombre,
    slug = slug.ifBlank { nombre.toSlug() },
    activa = activa,
)

private fun ExamenRow.toDto(specialtyName: String, questionCount: Int) = SolucionarioDto(
    id = id?.toString().orEmpty(),
    titulo = titulo,
    especialidadId = especialidadId.toString(),
    descripcion = specialtyName,
    precioTickets = precioTickets,
    estado = estado,
    orden = id ?: 0,
)

private fun SolucionarioDto.toRow() = ExamenRow(
    id = id.toIntOrNull(),
    titulo = titulo,
    precioTickets = precioTickets,
    especialidadId = especialidadId.toIntOrNull() ?: 1,
    estado = estado,
)

private fun PreguntaRow.toDto(resources: List<RecursoPreguntaDto>): PreguntaDto {
    val stored = enunciado.decodeStoredQuestion()
    return PreguntaDto(
        id = id?.toString().orEmpty(),
        solucionarioId = examenId.toString(),
        titulo = stored.titulo.ifBlank { "Pregunta ${orden + 1}" },
        enunciado = stored.enunciado,
        orden = orden,
        opciones = stored.opciones,
        recursos = resources,
    )
}

private fun PreguntaDto.toRow(examId: String, json: Json) = PreguntaRow(
    id = id.toIntOrNull(),
    examenId = examId.toIntOrNull() ?: solucionarioId.toIntOrNull() ?: 1,
    enunciado = STORED_QUESTION_PREFIX + json.encodeToString(StoredQuestion(titulo = titulo, enunciado = enunciado, opciones = opciones)),
    orden = orden,
)

private fun String.decodeStoredQuestion(): StoredQuestion {
    if (!startsWith(STORED_QUESTION_PREFIX)) return StoredQuestion(enunciado = this)
    return runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<StoredQuestion>(removePrefix(STORED_QUESTION_PREFIX)) }
        .getOrElse { StoredQuestion(enunciado = this) }
}

private fun RecursoPreguntaRow.toDto() = RecursoPreguntaDto(
    id = id?.toString().orEmpty(),
    preguntaId = preguntaId.toString(),
    tipoRecurso = tipoRecurso,
    titulo = url.substringAfterLast('/').ifBlank { "Recurso" },
    url = url,
    storagePath = url.takeUnless { it.startsWith("http://") || it.startsWith("https://") },
    publicUrl = url.takeIf { it.startsWith("http://") || it.startsWith("https://") },
)

private fun RecursoPreguntaDto.toRow(questionId: String) = RecursoPreguntaRow(
    id = id.toIntOrNull(),
    preguntaId = questionId.toIntOrNull() ?: preguntaId.toIntOrNull() ?: 1,
    tipoRecurso = tipoRecurso.toDatabaseResourceType(),
    url = publicUrl ?: storagePath ?: url,
)

private fun String.toDatabaseResourceType(): String {
    return when (lowercase()) {
        "image" -> "imagen"
        "document" -> "documento"
        "text" -> "documento"
        else -> lowercase()
    }
}

private fun String.toSlug(): String = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "item" }

private fun String.toIntId(): Int = toIntOrNull() ?: throw IllegalArgumentException("Id no valido.")

private class InMemoryGateway {
    private val solucionarios = linkedMapOf<String, SolucionarioDto>()
    private val preguntasBySolucionario = linkedMapOf<String, MutableList<PreguntaDto>>()

    fun listSolucionarios(): List<SolucionarioDto> = solucionarios.values.sortedBy { it.orden }

    fun saveSolucionario(input: SolucionarioDto): SolucionarioDto {
        require(input.titulo.isNotBlank()) { "El solucionario necesita un nombre." }
        require(input.especialidadId.isNotBlank()) { "El solucionario necesita una especialidad." }
        require(input.estado in setOf("draft", "published", "archived")) { "Estado de solucionario no valido." }
        val id = input.id.ifBlank { UUID.randomUUID().toString() }
        return input.copy(id = id).also { solucionarios[id] = it }
    }

    fun deleteSolucionario(solucionarioId: String) {
        check(preguntasBySolucionario[solucionarioId].orEmpty().isEmpty()) {
            "No se puede borrar un solucionario con preguntas."
        }
        solucionarios.remove(solucionarioId)
    }

    fun listPreguntas(solucionarioId: String): List<PreguntaDto> {
        requireSolucionario(solucionarioId)
        return preguntasBySolucionario[solucionarioId].orEmpty().sortedBy { it.orden }
    }

    fun savePregunta(solucionarioId: String, input: PreguntaDto): PreguntaDto {
        requireSolucionario(solucionarioId)
        validatePregunta(input)
        val id = input.id.ifBlank { UUID.randomUUID().toString() }
        val saved = input.copy(id = id, solucionarioId = solucionarioId)
        val preguntas = preguntasBySolucionario.getOrPut(solucionarioId) { mutableListOf() }
        preguntas.removeAll { it.id == id }
        preguntas += saved
        return saved
    }

    fun deletePregunta(solucionarioId: String, preguntaId: String) {
        requireSolucionario(solucionarioId)
        preguntasBySolucionario[solucionarioId]?.removeAll { it.id == preguntaId }
    }

    fun reorderPreguntas(solucionarioId: String, order: OrderDto): List<PreguntaDto> {
        requireSolucionario(solucionarioId)
        val current = preguntasBySolucionario[solucionarioId].orEmpty()
        require(order.ids.toSet() == current.map { it.id }.toSet()) {
            "El orden debe contener las mismas preguntas."
        }
        val byId = current.associateBy { it.id }
        val reordered = order.ids.mapIndexed { index, id -> byId.getValue(id).copy(orden = index) }
        preguntasBySolucionario[solucionarioId] = reordered.toMutableList()
        return reordered
    }

    fun saveRecurso(preguntaId: String, input: RecursoPreguntaDto): RecursoPreguntaDto {
        val located = findPregunta(preguntaId)
        validateRecurso(input)
        val id = input.id.ifBlank { UUID.randomUUID().toString() }
        val recurso = input.copy(id = id, preguntaId = preguntaId)
        val updatedPregunta = located.pregunta.copy(
            recursos = located.pregunta.recursos.filterNot { it.id == id } + recurso,
        )
        replacePregunta(located.solucionarioId, updatedPregunta)
        return recurso
    }

    fun reorderRecursos(preguntaId: String, order: OrderDto): List<RecursoPreguntaDto> {
        val located = findPregunta(preguntaId)
        require(order.ids.toSet() == located.pregunta.recursos.map { it.id }.toSet()) {
            "El orden debe contener los mismos recursos."
        }
        val byId = located.pregunta.recursos.associateBy { it.id }
        val recursos = order.ids.mapIndexed { index, id -> byId.getValue(id).copy(orden = index) }
        replacePregunta(located.solucionarioId, located.pregunta.copy(recursos = recursos))
        return recursos
    }

    private fun validatePregunta(pregunta: PreguntaDto) {
        require(pregunta.enunciado.isNotBlank()) { "La pregunta necesita enunciado." }
        if (pregunta.opciones.isNotEmpty()) {
            require(pregunta.opciones.size == 4) { "Una pregunta tipo test debe tener exactamente 4 opciones." }
            require(pregunta.opciones.count { it.esCorrecta } == 1) {
                "Una pregunta tipo test debe tener exactamente una opcion correcta."
            }
        }
    }

    private fun validateRecurso(recurso: RecursoPreguntaDto) {
        require(recurso.tipoRecurso in setOf("imagen", "audio", "video", "documento")) {
            "Tipo de recurso no valido."
        }
        require(recurso.url.isNotBlank() || !recurso.storagePath.isNullOrBlank() || !recurso.publicUrl.isNullOrBlank()) {
            "El recurso necesita una URL o ruta de archivo."
        }
    }

    private fun requireSolucionario(solucionarioId: String) {
        require(solucionarios.containsKey(solucionarioId)) { "El solucionario no existe." }
    }

    private fun findPregunta(preguntaId: String): LocatedPregunta {
        preguntasBySolucionario.forEach { (solucionarioId, preguntas) ->
            preguntas.firstOrNull { it.id == preguntaId }?.let { return LocatedPregunta(solucionarioId, it) }
        }
        throw IllegalArgumentException("La pregunta no existe.")
    }

    private fun replacePregunta(solucionarioId: String, pregunta: PreguntaDto) {
        val preguntas = preguntasBySolucionario.getValue(solucionarioId)
        preguntasBySolucionario[solucionarioId] = preguntas.map {
            if (it.id == pregunta.id) pregunta else it
        }.toMutableList()
    }
}

private data class LocatedPregunta(val solucionarioId: String, val pregunta: PreguntaDto)
