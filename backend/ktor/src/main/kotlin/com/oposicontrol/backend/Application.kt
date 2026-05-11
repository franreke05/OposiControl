package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
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
import io.ktor.server.request.receiveMultipart
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
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val STORED_QUESTION_PREFIX = "__OPOSICONTROL_QUESTION_V1__\n"
private const val COVER_MAX_BYTES = 10 * 1024 * 1024
private const val RESOURCE_MAX_BYTES = 50 * 1024 * 1024

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
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorDto(error = "bad_request", message = cause.safeBackendMessage("Invalid request")),
            )
        }
        exception<IllegalStateException> { call, cause ->
            val message = cause.safeBackendMessage("Backend error")
            System.err.println("[ktor] backend/supabase error: $message")
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorDto(error = "backend_supabase_error", message = message),
            )
        }
        exception<Throwable> { call, cause ->
            val message = "${cause::class.simpleName}: ${cause.safeBackendMessage("Unexpected backend error", 200)}"
            System.err.println("[ktor] unexpected error $message")
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorDto(error = "backend_internal_error", message = message),
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
            post("/admin/backend/restart") {
                val restart = BackendRestartRequest.fromEnvironment()
                if (restart == null) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorDto(
                            error = "backend_restart_disabled",
                            message = "Reinicio backend desactivado. Define OPOSICONTROL_ENABLE_BACKEND_RESTART=true en desarrollo.",
                        ),
                    )
                } else {
                    call.respond(HttpStatusCode.Accepted, mapOf("status" to "restarting"))
                    restart.schedule()
                }
            }
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
                    post("/{solucionarioId}/portada") {
                        call.respondBackendSupabaseResult("/content/solucionarios/{id}/portada") {
                            val saved = gateway.uploadPortada(
                                solucionarioId = call.requiredPath("solucionarioId"),
                                file = call.receiveSingleFile(
                                    maxBytes = COVER_MAX_BYTES,
                                    requiredMimePrefix = "image/",
                                    defaultFileName = "cover",
                                ),
                            )
                            runCatching { tagSearchGateway.indexSolucionario(saved) }
                                .onFailure { error ->
                                    System.err.println("[ktor] optional tag index skipped on cover upload: ${error.safeBackendMessage("index error", 160)}")
                                }
                            saved
                        }
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
                route("/rafaga") {
                    get { call.respond(gateway.listRafagas()) }
                    post {
                        call.respond(HttpStatusCode.Created, gateway.saveRafaga(call.receive()))
                    }
                    put {
                        call.respond(gateway.saveRafaga(call.receive()))
                    }
                    delete("/{rafagaId}") {
                        gateway.deleteRafaga(call.requiredPath("rafagaId"))
                        call.respond(HttpStatusCode.NoContent)
                    }
                    get("/{rafagaId}/preguntas") {
                        call.respond(gateway.listRafagaPreguntas(call.requiredPath("rafagaId")))
                    }
                    post("/{rafagaId}/preguntas") {
                        call.respond(
                            HttpStatusCode.Created,
                            gateway.saveRafagaPregunta(call.requiredPath("rafagaId"), call.receive()),
                        )
                    }
                    put("/{rafagaId}/preguntas") {
                        call.respond(gateway.saveRafagaPregunta(call.requiredPath("rafagaId"), call.receive()))
                    }
                    delete("/{rafagaId}/preguntas/{preguntaId}") {
                        gateway.deleteRafagaPregunta(call.requiredPath("rafagaId"), call.requiredPath("preguntaId"))
                        call.respond(HttpStatusCode.NoContent)
                    }
                    put("/{rafagaId}/preguntas/order") {
                        call.respond(gateway.reorderRafagaPreguntas(call.requiredPath("rafagaId"), call.receive()))
                    }
                }
                route("/preguntas") {
                    post("/{preguntaId}/recursos/{resourceId}/upload") {
                        call.respondBackendSupabaseResult("/content/preguntas/{id}/recursos/{id}/upload") {
                            val saved = gateway.uploadRecurso(
                                preguntaId = call.requiredPath("preguntaId"),
                                resourceId = call.requiredPath("resourceId"),
                                tipoRecurso = call.request.queryParameters["tipoRecurso"].orEmpty(),
                                titulo = call.request.queryParameters["titulo"].orEmpty(),
                                orden = call.request.queryParameters["orden"]?.toIntOrNull() ?: 0,
                                file = call.receiveSingleFile(
                                    maxBytes = RESOURCE_MAX_BYTES,
                                    requiredMimePrefix = null,
                                    defaultFileName = "resource",
                                ),
                            )
                            tagSearchGateway.indexRecurso(saved)
                            saved
                        }
                    }
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
                    post("/assets") {
                        call.respondBackendSupabaseResult("/content/noticias/assets") {
                            val file = call.receiveSingleFile(
                                maxBytes = COVER_MAX_BYTES,
                                requiredMimePrefix = "image/",
                                defaultFileName = "news-asset",
                            )
                            newsGateway.uploadAsset(
                                NewsUploadedFile(
                                    fileName = file.fileName,
                                    mimeType = file.mimeType,
                                    bytes = file.bytes,
                                ),
                            )
                        }
                    }
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
                call.respondBackendSupabaseResult("/tags") {
                    tagSearchGateway.listTags(call.request.queryParameters["q"].orEmpty())
                }
            }
            get("/search") {
                call.respondBackendSupabaseResult("/search") {
                    tagSearchGateway.search(
                        query = call.request.queryParameters["q"].orEmpty(),
                        tagSlugs = call.request.queryParameters["tags"].orEmpty()
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotBlank() },
                        section = call.request.queryParameters["section"],
                    )
                }
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
data class ErrorDto(val error: String, val message: String)

private class BackendRestartRequest(
    private val projectRoot: File,
) {
    fun schedule() {
        thread(name = "oposicontrol-backend-restart", isDaemon = false) {
            Thread.sleep(750)
            val command = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                listOf("cmd", "/c", "gradlew.bat", ":backend:ktor:run")
            } else {
                listOf("./gradlew", ":backend:ktor:run")
            }
            ProcessBuilder(command)
                .directory(projectRoot)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            Thread.sleep(250)
            exitProcess(0)
        }
    }

    companion object {
        fun fromEnvironment(): BackendRestartRequest? {
            val enabled = System.getenv("OPOSICONTROL_ENABLE_BACKEND_RESTART")
                ?.toBooleanStrictOrNull()
                ?: false
            if (!enabled) return null
            return findProjectRoot(File(System.getProperty("user.dir")))?.let(::BackendRestartRequest)
        }

        private fun findProjectRoot(start: File): File? {
            var current: File? = start.absoluteFile
            while (current != null) {
                if (File(current, "settings.gradle.kts").exists() && File(current, "gradlew.bat").exists()) {
                    return current
                }
                current = current.parentFile
            }
            return null
        }
    }
}

private data class BackendUploadedFile(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

private suspend inline fun <reified T : Any> ApplicationCall.respondBackendSupabaseResult(
    routeName: String,
    block: () -> T,
) {
    try {
        respond(block())
    } catch (cause: IllegalArgumentException) {
        val message = cause.safeBackendMessage("Supabase backend request error")
        System.err.println("[ktor] $routeName request failure ${cause::class.simpleName}: $message")
        respond(
            HttpStatusCode.BadRequest,
            ErrorDto(error = "bad_request", message = message),
        )
    } catch (cause: Throwable) {
        val message = cause.safeBackendMessage("Supabase backend error")
        System.err.println("[ktor] $routeName supabase failure ${cause::class.simpleName}: $message")
        respond(
            HttpStatusCode.InternalServerError,
            ErrorDto(error = "backend_supabase_error", message = message),
        )
    }
}

private fun Throwable.safeBackendMessage(defaultMessage: String, maxLength: Int = 300): String {
    return message
        ?.replace(Regex("Bearer\\s+[A-Za-z0-9._~+/-]+=*"), "Bearer [redacted]")
        ?.replace(Regex("apikey[=:]\\s*[A-Za-z0-9._~+/-]+=*"), "apikey=[redacted]")
        ?.take(maxLength)
        ?.ifBlank { null }
        ?: defaultMessage
}

private suspend fun ApplicationCall.receiveSingleFile(
    maxBytes: Int,
    requiredMimePrefix: String?,
    defaultFileName: String,
): BackendUploadedFile {
    var uploaded: BackendUploadedFile? = null
    val multipart = receiveMultipart()
    var part = multipart.readPart()
    while (part != null) {
        try {
            if (part is PartData.FileItem && uploaded == null) {
                val bytes = part.provider().readRemaining(maxBytes.toLong() + 1).readByteArray()
                require(bytes.size <= maxBytes) { "El archivo supera el limite permitido." }
                val mimeType = part.contentType?.toString().orEmpty().ifBlank { "application/octet-stream" }
                if (requiredMimePrefix != null) {
                    require(mimeType.startsWith(requiredMimePrefix)) { "Tipo de archivo no permitido: $mimeType." }
                }
                uploaded = BackendUploadedFile(
                    fileName = part.originalFileName?.sanitizeStorageSegment()?.ifBlank { null } ?: defaultFileName,
                    mimeType = mimeType,
                    bytes = bytes,
                )
            }
        } finally {
            part.dispose.invoke()
        }
        part = multipart.readPart()
    }
    return uploaded ?: throw IllegalArgumentException("Falta el campo multipart file.")
}

@Serializable
data class SolucionarioDto(
    val id: String = "",
    val titulo: String,
    val especialidadId: String,
    val descripcion: String = "",
    val precioTickets: Int = 0,
    val estado: String = "draft",
    val publicarDesde: String? = null,
    val publishedAt: String? = null,
    val orden: Int = 0,
    val portadaUrl: String? = null,
    val portadaStoragePath: String? = null,
    val portadaMimeType: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val deletedAt: String? = null,
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
data class RafagaDto(
    val id: String = "",
    val titulo: String,
    val especialidadId: String,
    val precioTickets: Int = 0,
    val estado: String = "draft",
    val publicarDesde: String? = null,
    val publishedAt: String? = null,
    val questionCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val deletedAt: String? = null,
)

@Serializable
data class RafagaPreguntaDto(
    val id: String = "",
    val rafagaId: String = "",
    val enunciado: String,
    val orden: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val deletedAt: String? = null,
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
    val mimeType: String? = null,
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
    private val examResourcesBucket: String,
    private val json: Json,
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    @Volatile
    private var storageBucketChecked = false

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

    fun listRafagas(): List<RafagaDto> {
        val counts = request("GET", "/rest/v1/rafaga_pregunta?select=id,rafaga_id&deleted_at=is.null", null)
            .decodeList<RafagaPreguntaCountRow>()
            .groupingBy { it.rafagaId.toString() }
            .eachCount()
        return request("GET", "/rest/v1/rafaga?select=*&deleted_at=is.null&order=created_at.desc", null)
            .decodeList<RafagaRow>()
            .map { it.toDto(counts[it.id?.toString().orEmpty()] ?: 0) }
    }

    fun saveSolucionario(input: SolucionarioDto): SolucionarioDto {
        require(input.titulo.isNotBlank()) { "El solucionario necesita un nombre." }
        require(input.especialidadId.isNotBlank()) { "El solucionario necesita una especialidad." }
        require(input.estado in setOf("draft", "published", "archived")) { "Estado de solucionario no valido." }
        if (input.estado == "published") {
            require(!input.portadaUrl.isNullOrBlank() || !input.portadaStoragePath.isNullOrBlank()) {
                "Anade una portada antes de publicar el examen."
            }
        }
        require(input.precioTickets >= 0) { "El precio en tickets no puede ser negativo." }
        val saved = request(
            "POST",
            "/rest/v1/examen?on_conflict=id",
            json.encodeToString(listOf(input.normalizedForSave().toRow())),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<ExamenRow>().first()
        return saved.toDto("", 0)
    }

    fun saveRafaga(input: RafagaDto): RafagaDto {
        require(input.titulo.isNotBlank()) { "La rafaga necesita un nombre." }
        require(input.especialidadId.isNotBlank()) { "La rafaga necesita una especialidad." }
        require(input.estado in setOf("draft", "published", "archived")) { "Estado de rafaga no valido." }
        require(input.precioTickets >= 0) { "El precio en tickets no puede ser negativo." }
        val normalized = input.normalizedForSave()
        val saved = if (normalized.id.isBlank()) {
            request(
                "POST",
                "/rest/v1/rafaga",
                json.encodeToString(listOf(normalized.toRow())),
                prefer = "return=representation",
            ).decodeList<RafagaRow>().first()
        } else {
            request(
                "PATCH",
                "/rest/v1/rafaga?id=eq.${normalized.id.toIntId()}",
                json.encodeToString(normalized.toRow()),
                prefer = "return=representation",
            ).decodeList<RafagaRow>().first()
        }
        return saved.toDto(questionCount = 0)
    }

    fun uploadPortada(solucionarioId: String, file: BackendUploadedFile): SolucionarioDto {
        require(file.mimeType.startsWith("image/")) { "La portada debe ser una imagen." }
        val objectPath = "exam-covers/${solucionarioId.toIntId()}/${file.fileName}"
        storageUpload(objectPath, file.mimeType, file.bytes)
        val publicUrl = storagePublicUrl(objectPath)
        val saved = try {
            request(
                "PATCH",
                "/rest/v1/examen?id=eq.${solucionarioId.toIntId()}",
                json.encodeToString(
                    mapOf(
                        "portada_url" to publicUrl,
                        "portada_storage_path" to objectPath,
                        "portada_mime_type" to file.mimeType,
                        "updated_at" to java.time.Instant.now().toString(),
                    ),
                ),
                prefer = "return=representation",
            ).decodeList<ExamenRow>().firstOrNull()
                ?: throw IllegalArgumentException("No existe el solucionario $solucionarioId.")
        } catch (error: IllegalStateException) {
            if (error.isMissingCoverColumnError()) {
                throw IllegalArgumentException(
                    "Faltan columnas de portada en public.examen. Ejecuta docs/supabase/20260507_exam_cover_resource_uploads.sql en Supabase.",
                )
            }
            throw error
        }
        return saved.toDto("", 0)
    }

    fun deleteSolucionario(solucionarioId: String) {
        val examId = solucionarioId.toIntId()
        val deletedAt = java.time.Instant.now().toString()
        val deleteBody = json.encodeToString(mapOf("deleted_at" to deletedAt))
        val questions = request(
            "GET",
            "/rest/v1/pregunta?select=id&examen_id=eq.$examId&deleted_at=is.null",
            null,
        ).decodeList<PreguntaIdRow>()
        val questionIds = questions.mapNotNull { it.id }
        if (questionIds.isNotEmpty()) {
            val idsFilter = questionIds.joinToString(",")
            request(
                "PATCH",
                "/rest/v1/recurso_pregunta?pregunta_id=in.($idsFilter)&deleted_at=is.null",
                deleteBody,
            )
            requestIfRelationExists(
                "PATCH",
                "/rest/v1/opcion_pregunta?pregunta_id=in.($idsFilter)&deleted_at=is.null",
                deleteBody,
            )
            request(
                "PATCH",
                "/rest/v1/pregunta?id=in.($idsFilter)&deleted_at=is.null",
                deleteBody,
            )
        }
        request("PATCH", "/rest/v1/examen?id=eq.$examId", deleteBody)
        requestIfRelationExists(
            "PATCH",
            "/rest/v1/acceso_examen_usuario?examen_id=eq.$examId",
            """{"activo":false,"updated_at":"$deletedAt"}""",
        )
    }

    fun deleteRafaga(rafagaId: String) {
        val deletedAt = java.time.Instant.now().toString()
        val deleteBody = json.encodeToString(mapOf("deleted_at" to deletedAt))
        request(
            "PATCH",
            "/rest/v1/rafaga_pregunta?rafaga_id=eq.${rafagaId.toIntId()}&deleted_at=is.null",
            deleteBody,
        )
        request("PATCH", "/rest/v1/rafaga?id=eq.${rafagaId.toIntId()}", deleteBody)
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

    fun listRafagaPreguntas(rafagaId: String): List<RafagaPreguntaDto> {
        return request(
            "GET",
            "/rest/v1/rafaga_pregunta?select=*&rafaga_id=eq.${rafagaId.toIntId()}&deleted_at=is.null&order=orden.asc",
            null,
        ).decodeList<RafagaPreguntaRow>().map { it.toDto() }
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

    fun saveRafagaPregunta(rafagaId: String, input: RafagaPreguntaDto): RafagaPreguntaDto {
        require(input.enunciado.isNotBlank()) { "La pregunta de rafaga necesita enunciado." }
        val saved = request(
            "POST",
            "/rest/v1/rafaga_pregunta?on_conflict=id",
            json.encodeToString(listOf(input.toRow(rafagaId))),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<RafagaPreguntaRow>().first()
        return saved.toDto()
    }

    fun deletePregunta(solucionarioId: String, preguntaId: String) {
        request("PATCH", "/rest/v1/pregunta?id=eq.${preguntaId.toIntId()}&examen_id=eq.${solucionarioId.toIntId()}", """{"deleted_at":"${java.time.Instant.now()}"}""")
    }

    fun deleteRafagaPregunta(rafagaId: String, preguntaId: String) {
        request("PATCH", "/rest/v1/rafaga_pregunta?id=eq.${preguntaId.toIntId()}&rafaga_id=eq.${rafagaId.toIntId()}", """{"deleted_at":"${java.time.Instant.now()}"}""")
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

    fun reorderRafagaPreguntas(rafagaId: String, order: OrderDto): List<RafagaPreguntaDto> {
        val current = listRafagaPreguntas(rafagaId)
        require(order.ids.toSet() == current.map { it.id }.toSet()) { "El orden debe contener las mismas preguntas." }
        val byId = current.associateBy { it.id }
        val reordered = order.ids.mapIndexed { index, id -> byId.getValue(id).copy(orden = index) }
        request(
            "POST",
            "/rest/v1/rafaga_pregunta?on_conflict=id",
            json.encodeToString(reordered.map { it.toRow(rafagaId) }),
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

    fun uploadRecurso(
        preguntaId: String,
        resourceId: String,
        tipoRecurso: String,
        titulo: String,
        orden: Int,
        file: BackendUploadedFile,
    ): RecursoPreguntaDto {
        val databaseType = tipoRecurso.toDatabaseResourceType()
        validateResourceMime(databaseType, file.mimeType)
        val objectPath = "questions/${preguntaId.toIntId()}/${resourceId.sanitizeStorageSegment()}-${file.fileName}"
        storageUpload(objectPath, file.mimeType, file.bytes)
        val publicUrl = storagePublicUrl(objectPath)
        return saveRecurso(
            preguntaId = preguntaId,
            input = RecursoPreguntaDto(
                id = resourceId,
                preguntaId = preguntaId,
                tipoRecurso = databaseType,
                titulo = titulo.ifBlank { file.fileName },
                url = publicUrl,
                storagePath = objectPath,
                publicUrl = publicUrl,
                mimeType = file.mimeType,
                orden = orden,
            ),
        )
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

    private fun validateResourceMime(tipoRecurso: String, mimeType: String) {
        val valid = when (tipoRecurso) {
            "imagen" -> mimeType.startsWith("image/")
            "audio" -> mimeType.startsWith("audio/")
            "video" -> mimeType.startsWith("video/")
            "documento" -> mimeType.startsWith("application/") || mimeType.startsWith("text/")
            else -> false
        }
        require(valid) { "Tipo MIME $mimeType no permitido para $tipoRecurso." }
    }

    private fun storageUpload(objectPath: String, mimeType: String, bytes: ByteArray) {
        ensureStorageBucketExists()
        val encodedPath = objectPath.split('/').joinToString("/") { it.urlEncode() }
        val path = "/storage/v1/object/$examResourcesBucket/$encodedPath"
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", mimeType)
            .header("x-upsert", "true")
        val response = try {
            client.send(
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Supabase Storage network error on upload: ${e::class.simpleName}: ${e.message}", e)
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Supabase Storage upload -> ${response.statusCode()}: ${response.body().take(300)}")
        }
    }

    private fun ensureStorageBucketExists() {
        if (storageBucketChecked) return
        val bucketId = examResourcesBucket.toStorageBucketId()
        val body = """{"id":"$bucketId","name":"$bucketId","public":true}"""
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl/storage/v1/bucket"))
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
            .header("Content-Type", "application/json")
        val response = try {
            client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException("Supabase Storage bucket check network error: ${error::class.simpleName}: ${error.message}", error)
        }
        if (response.statusCode() in 200..299 || response.statusCode() == 409) {
            storageBucketChecked = true
            return
        }
        throw IllegalStateException("Supabase Storage bucket check -> ${response.statusCode()}: ${response.body().take(300)}")
    }

    private fun storagePublicUrl(objectPath: String): String {
        val encodedPath = objectPath.split('/').joinToString("/") { it.urlEncode() }
        return "$supabaseUrl/storage/v1/object/public/$examResourcesBucket/$encodedPath"
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

    private fun requestIfRelationExists(method: String, path: String, body: String?, prefer: String? = null) {
        try {
            request(method, path, body, prefer)
        } catch (error: IllegalStateException) {
            val message = error.message.orEmpty()
            val missingRelation = listOf("PGRST205", "42P01", "42703", "Could not find the table", "relation", "column").any {
                message.contains(it, ignoreCase = true)
            }
            if (!missingRelation) throw error
            System.err.println("[ktor] optional Supabase relation skipped on ${path.substringBefore('?')}: ${message.take(160)}")
        }
    }

    private inline fun <reified T> String.decodeList(): List<T> = json.decodeFromString(this)

    companion object {
        fun fromEnvironment(json: Json): SupabaseContentGateway? {
            val url = System.getenv("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY").orEmpty()
            val bucket = System.getenv("SUPABASE_BUCKET_EXAM_RESOURCES").orEmpty().ifBlank { "exam-resources" }
            return if (url.isBlank() || serviceRoleKey.isBlank()) null else SupabaseContentGateway(url, serviceRoleKey, bucket, json)
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
    @kotlinx.serialization.SerialName("portada_url") val portadaUrl: String? = null,
    @kotlinx.serialization.SerialName("portada_storage_path") val portadaStoragePath: String? = null,
    @kotlinx.serialization.SerialName("portada_mime_type") val portadaMimeType: String? = null,
    @kotlinx.serialization.SerialName("publicar_desde") val publicarDesde: String? = null,
    @kotlinx.serialization.SerialName("published_at") val publishedAt: String? = null,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String? = null,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: String? = null,
    @kotlinx.serialization.SerialName("deleted_at") val deletedAt: String? = null,
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
private data class PreguntaIdRow(
    val id: Int? = null,
)

@Serializable
private data class PreguntaCountRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("examen_id") val examenId: Int,
)

@Serializable
private data class RafagaRow(
    val id: Int? = null,
    val titulo: String,
    @kotlinx.serialization.SerialName("precio_tickets") val precioTickets: Int = 0,
    @kotlinx.serialization.SerialName("especialidad_id") val especialidadId: Int,
    val estado: String = "draft",
    @kotlinx.serialization.SerialName("publicar_desde") val publicarDesde: String? = null,
    @kotlinx.serialization.SerialName("published_at") val publishedAt: String? = null,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String? = null,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: String? = null,
    @kotlinx.serialization.SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
private data class RafagaPreguntaRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("rafaga_id") val rafagaId: Int,
    val enunciado: String,
    val orden: Int = 0,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String? = null,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: String? = null,
    @kotlinx.serialization.SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
private data class RafagaPreguntaCountRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("rafaga_id") val rafagaId: Int,
)

@Serializable
private data class RecursoPreguntaRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("pregunta_id") val preguntaId: Int,
    @kotlinx.serialization.SerialName("tipo_recurso") val tipoRecurso: String,
    val url: String,
    val titulo: String? = null,
    @kotlinx.serialization.SerialName("storage_path") val storagePath: String? = null,
    @kotlinx.serialization.SerialName("public_url") val publicUrl: String? = null,
    @kotlinx.serialization.SerialName("mime_type") val mimeType: String? = null,
    val orden: Int = 0,
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
    publicarDesde = publicarDesde,
    publishedAt = publishedAt,
    orden = id ?: 0,
    portadaUrl = portadaUrl,
    portadaStoragePath = portadaStoragePath,
    portadaMimeType = portadaMimeType,
    createdAt = createdAt.orEmpty(),
    updatedAt = updatedAt.orEmpty(),
    deletedAt = deletedAt,
)

private fun SolucionarioDto.toRow() = ExamenRow(
    id = id.toIntOrNull(),
    titulo = titulo,
    precioTickets = precioTickets,
    especialidadId = especialidadId.toIntOrNull() ?: 1,
    estado = estado,
    portadaUrl = portadaUrl,
    portadaStoragePath = portadaStoragePath,
    portadaMimeType = portadaMimeType,
    publicarDesde = publicarDesde,
    publishedAt = publishedAt,
    updatedAt = updatedAt.ifBlank { java.time.Instant.now().toString() },
)

private fun RafagaRow.toDto(questionCount: Int) = RafagaDto(
    id = id?.toString().orEmpty(),
    titulo = titulo,
    especialidadId = especialidadId.toString(),
    precioTickets = precioTickets,
    estado = estado,
    publicarDesde = publicarDesde,
    publishedAt = publishedAt,
    questionCount = questionCount,
    createdAt = createdAt.orEmpty(),
    updatedAt = updatedAt.orEmpty(),
    deletedAt = deletedAt,
)

private fun RafagaDto.toRow() = RafagaRow(
    id = id.toIntOrNull(),
    titulo = titulo,
    precioTickets = precioTickets,
    especialidadId = especialidadId.toIntOrNull() ?: 1,
    estado = estado,
    publicarDesde = publicarDesde,
    publishedAt = publishedAt,
    updatedAt = updatedAt.ifBlank { java.time.Instant.now().toString() },
)

private fun RafagaDto.normalizedForSave(): RafagaDto {
    val now = java.time.Instant.now().toString()
    return copy(
        publicarDesde = publicarDesde?.takeIf { it.isNotBlank() },
        publishedAt = when {
            estado == "published" && publishedAt.isNullOrBlank() -> now
            estado == "published" -> publishedAt
            else -> publishedAt?.takeIf { it.isNotBlank() }
        },
        updatedAt = now,
    )
}

private fun SolucionarioDto.normalizedForSave(): SolucionarioDto {
    val now = java.time.Instant.now().toString()
    return copy(
        publicarDesde = publicarDesde?.takeIf { it.isNotBlank() },
        publishedAt = when {
            estado == "published" && publishedAt.isNullOrBlank() -> now
            estado == "published" -> publishedAt
            else -> publishedAt?.takeIf { it.isNotBlank() }
        },
        updatedAt = now,
    )
}

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

private fun RafagaPreguntaRow.toDto() = RafagaPreguntaDto(
    id = id?.toString().orEmpty(),
    rafagaId = rafagaId.toString(),
    enunciado = enunciado,
    orden = orden,
    createdAt = createdAt.orEmpty(),
    updatedAt = updatedAt.orEmpty(),
    deletedAt = deletedAt,
)

private fun RafagaPreguntaDto.toRow(parentRafagaId: String) = RafagaPreguntaRow(
    id = id.toIntOrNull(),
    rafagaId = parentRafagaId.toIntOrNull() ?: rafagaId.toIntOrNull() ?: 1,
    enunciado = enunciado,
    orden = orden,
    updatedAt = updatedAt.ifBlank { java.time.Instant.now().toString() },
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
    titulo = titulo ?: publicUrl?.substringAfterLast('/') ?: storagePath?.substringAfterLast('/') ?: url.substringAfterLast('/').ifBlank { "Recurso" },
    url = url,
    storagePath = storagePath ?: url.takeUnless { it.startsWith("http://") || it.startsWith("https://") },
    publicUrl = publicUrl ?: url.takeIf { it.startsWith("http://") || it.startsWith("https://") },
    mimeType = mimeType,
    orden = orden,
)

private fun RecursoPreguntaDto.toRow(questionId: String) = RecursoPreguntaRow(
    id = id.toIntOrNull(),
    preguntaId = questionId.toIntOrNull() ?: preguntaId.toIntOrNull() ?: 1,
    tipoRecurso = tipoRecurso.toDatabaseResourceType(),
    url = publicUrl ?: storagePath ?: url,
    titulo = titulo,
    storagePath = storagePath,
    publicUrl = publicUrl,
    mimeType = mimeType,
    orden = orden,
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

private fun String.toStorageBucketId(): String {
    require(matches(Regex("[A-Za-z0-9._-]+"))) { "Bucket de storage no valido." }
    return this
}

private fun Throwable.isMissingCoverColumnError(): Boolean {
    val message = message.orEmpty()
    val coverColumn = listOf("portada_url", "portada_storage_path", "portada_mime_type").any {
        message.contains(it, ignoreCase = true)
    }
    val missingColumn = listOf("PGRST204", "PGRST205", "42703", "column").any {
        message.contains(it, ignoreCase = true)
    }
    return coverColumn && missingColumn
}

private fun String.sanitizeStorageSegment(): String {
    return lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .ifBlank { "file" }
}

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

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
