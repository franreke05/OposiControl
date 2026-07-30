package com.oposicontrol.backend

import com.oposicontrol.admin.core.api.AdminApiJson
import com.oposicontrol.admin.core.api.ExamAccessType
import com.oposicontrol.admin.core.api.ExamQuestionResponse
import com.oposicontrol.admin.core.api.ExamResponse
import com.oposicontrol.admin.core.api.ExamType
import com.oposicontrol.admin.core.api.PublicationStatus
import com.oposicontrol.admin.core.api.QuestionOptionInput
import com.oposicontrol.admin.core.api.QuestionResourceResponse
import com.oposicontrol.admin.core.api.QuestionResourceType
import com.oposicontrol.admin.core.api.SpecialtyResponse
import com.oposicontrol.admin.core.api.UpsertExamRequest
import com.oposicontrol.admin.core.api.UpsertQuestionRequest
import com.oposicontrol.admin.core.api.UpsertQuestionResourceRequest
import com.oposicontrol.admin.core.api.UpsertSpecialtyRequest
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
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
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
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
import kotlinx.coroutines.runBlocking
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
        encodeDefaults = false
    }
    val configuration = BackendConfiguration.fromEnvironment()
    val supabaseHttp = SupabaseHttp(configuration, AdminApiJson.format)
    val bootstrapAdmin = BootstrapAdmin(configuration, supabaseHttp)
    runBlocking {
        bootstrapAdmin.initialize()
    }
    val productionAuthenticator = ProductionAdminTokenAuthenticator(
        jwtVerifier = SupabaseJwtVerifier(configuration, supabaseHttp),
        sessionValidator = SupabaseAdminSessionValidator(supabaseHttp),
        authorizationResolver = SupabaseAuthorizationResolver(supabaseHttp),
    )
    val authenticator = if (configuration.developmentOpenAccess) {
        log.warn(
            "Development open access is enabled for local testing; Supabase JWT remains required outside APP_ENV=dev.",
        )
        DevelopmentOpenAccessAdminTokenAuthenticator(configuration, productionAuthenticator)
    } else {
        productionAuthenticator
    }
    val canonicalGateway = SupabaseCanonicalAdminGateway(configuration, supabaseHttp)
    val operationalGateway = OperationalAdminGateway(supabaseHttp)
    val readinessProvider = ProductionReadinessProvider(bootstrapAdmin, supabaseHttp)
    val newsGateway = SupabaseNewsGateway.fromEnvironment(json)
        ?: error("SUPABASE_URL y SUPABASE_SERVICE_ROLE_KEY son obligatorios para servir noticias desde Ktor.")
    val tagSearchGateway = SupabaseTagSearchGateway.fromEnvironment(json)
        ?: error("SUPABASE_URL y SUPABASE_SECRET_KEY son obligatorios para servir busqueda desde Ktor.")
    val adminGateway = SupabaseAdminGateway.fromEnvironment(json)
        ?: error("SUPABASE_URL y SUPABASE_SECRET_KEY son obligatorios para servir admin desde Ktor.")
    val v1Gateway = SupabaseV1Gateway.fromEnvironment(json)
        ?: error("SUPABASE_URL y SUPABASE_SECRET_KEY son obligatorios para servir API V1 desde Ktor.")
    val oposibotsAuthGateway = SupabaseOposiBotsAuthGateway(
        configuration = configuration,
        json = AdminApiJson.format,
    )
    val oposibotsAuthRateLimiter = InMemoryOposiBotsAuthRateLimiter()
    configureBackendPlatform(
        authenticator = authenticator,
        trustProxyHeaders = configuration.trustProxyHeaders,
    )
    routing {
        liveRoute(configuration.serviceVersion)
        // Consumer authentication is deliberately outside the administrative
        // bearer/RBAC perimeter. No other /v1 route becomes public.
        oposiBotsAuthRoutes(oposibotsAuthGateway, oposibotsAuthRateLimiter)
        protectedAdminRoutes {
            readinessRoute(configuration.serviceVersion, readinessProvider)
            canonicalAdminRoutes(canonicalGateway)
            operationalAdminRoutes(operationalGateway)
            v1Routes(v1Gateway)
            adminRoutes(adminGateway)
            post("/admin/backend/restart") {
                call.requirePermission("maintenance")
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
            post("/admin/dev/restart-connection") {
                call.requirePermission("maintenance")
                val restart = BackendRestartRequest.fromEnvironment(ngrokHost = call.ngrokHostForRestart())
                if (restart == null) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorDto(
                            error = "backend_restart_disabled",
                            message = "Reconexion desactivada. Define OPOSICONTROL_ENABLE_BACKEND_RESTART=true en desarrollo.",
                        ),
                    )
                } else {
                    call.respond(HttpStatusCode.Accepted, mapOf("status" to "reconnecting"))
                    restart.schedule()
                }
            }
            route("/content") {
                enforcePermission { securedCall ->
                    if (securedCall.request.httpMethod == io.ktor.http.HttpMethod.Get) {
                        "content.read"
                    } else {
                        "content.write"
                    }
                }
                route("/especialidades") {
                    get {
                        val actor = call.requirePermission("content.read")
                        call.respond(
                            canonicalGateway.listSpecialties(actor, limit = 100)
                                .items
                                .map(SpecialtyResponse::toLegacyDto),
                        )
                    }
                    post {
                        val actor = call.requirePermission("content.write")
                        val input = call.receive<EspecialidadDto>()
                        val saved = canonicalGateway.saveSpecialty(
                            id = null,
                            request = input.toCanonicalRequest(),
                            actor = actor,
                        ).toLegacyDto()
                        call.respond(HttpStatusCode.Created, saved)
                    }
                    put {
                        val actor = call.requirePermission("content.write")
                        val input = call.receive<EspecialidadDto>()
                        val id = input.id.toLongOrNull()?.takeIf { it > 0 }
                            ?: throw BadRequestProblem("Id de especialidad no válido.")
                        val saved = canonicalGateway.saveSpecialty(
                            id = id,
                            request = input.toCanonicalRequest(
                                expectedVersion = canonicalGateway.currentContentVersion("specialty", id, actor),
                            ),
                            actor = actor,
                        ).toLegacyDto()
                        call.respond(saved)
                    }
                    delete("/{especialidadId}") {
                        val actor = call.requirePermission("content.write")
                        canonicalGateway.softDeleteContent(
                            resourceType = "specialty",
                            resourceId = call.requiredPositiveLongPath("especialidadId"),
                            expectedVersion = null,
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
                route("/solucionarios") {
                    get {
                        val actor = call.requirePermission("content.read")
                        call.respond(
                            canonicalGateway.listExams(ExamType.IMMERSIVE, actor, limit = 100)
                                .items
                                .map(ExamResponse::toLegacyDto),
                        )
                    }
                    post {
                        val actor = call.requirePermission("content.write")
                        val input = call.receive<SolucionarioDto>()
                        val request = input.toCanonicalRequest()
                        actor.requirePublishPermission(request.status)
                        val saved = canonicalGateway.saveExam(null, request, actor).toLegacyDto()
                        call.respond(HttpStatusCode.Created, saved)
                    }
                    put {
                        val actor = call.requirePermission("content.write")
                        val input = call.receive<SolucionarioDto>()
                        val id = input.id.toLongOrNull()?.takeIf { it > 0 }
                            ?: throw BadRequestProblem("Id de examen no válido.")
                        val request = input.toCanonicalRequest()
                        actor.requirePublishPermission(request.status)
                        val saved = canonicalGateway.saveExam(id, request, actor).toLegacyDto()
                        call.respond(saved)
                    }
                    delete("/{solucionarioId}") {
                        val actor = call.requirePermission("content.write")
                        canonicalGateway.softDeleteContent(
                            resourceType = "exam",
                            resourceId = call.requiredPositiveLongPath("solucionarioId"),
                            expectedVersion = null,
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/{solucionarioId}/portada") {
                        call.respondBackendSupabaseResult("/content/solucionarios/{id}/portada") {
                            val actor = call.requirePermission("content.write")
                            val file = call.receiveSingleFile(
                                maxBytes = COVER_MAX_BYTES,
                                requiredMimePrefix = "image/",
                                defaultFileName = "cover",
                            )
                            canonicalGateway.uploadExamCover(
                                examId = call.requiredPositiveLongPath("solucionarioId"),
                                fileName = file.fileName,
                                mimeType = file.mimeType,
                                bytes = file.bytes,
                                actor = actor,
                            ).toLegacyDto()
                        }
                    }
                    delete("/{solucionarioId}/portada") {
                        call.respondBackendSupabaseResult("/content/solucionarios/{id}/portada") {
                            canonicalGateway.clearExamCover(
                                examId = call.requiredPositiveLongPath("solucionarioId"),
                                actor = call.requirePermission("content.write"),
                            ).toLegacyDto()
                        }
                    }
                    get("/{solucionarioId}/preguntas") {
                        val actor = call.requirePermission("content.read")
                        call.respond(
                            canonicalGateway.listQuestions(
                                call.requiredPositiveLongPath("solucionarioId"),
                                actor,
                            ).map(ExamQuestionResponse::toLegacyDto),
                        )
                    }
                    post("/{solucionarioId}/preguntas/import") {
                        val actor = call.requirePermission("content.write")
                        val examId = call.requiredPath("solucionarioId").toLongOrNull()
                            ?.takeIf { it > 0 }
                            ?: throw BadRequestProblem("Id de examen no válido.")
                        val imported = canonicalGateway.importQuestions(
                            examId = examId,
                            questions = call.receive<List<PreguntaDto>>().map(PreguntaDto::toCanonicalImport),
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        )
                        call.respond(HttpStatusCode.Created, imported.map(ExamQuestionResponse::toLegacyDto))
                    }
                    post("/{solucionarioId}/preguntas") {
                        val actor = call.requirePermission("content.write")
                        val examId = call.requiredPositiveLongPath("solucionarioId")
                        val input = call.receive<PreguntaDto>()
                        val saved = canonicalGateway.saveQuestion(
                            examId = examId,
                            questionId = null,
                            request = input.toCanonicalImport(),
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        ).toLegacyDto()
                        call.respond(
                            HttpStatusCode.Created,
                            saved,
                        )
                    }
                    put("/{solucionarioId}/preguntas") {
                        val actor = call.requirePermission("content.write")
                        val examId = call.requiredPositiveLongPath("solucionarioId")
                        val input = call.receive<PreguntaDto>()
                        val questionId = input.id.toLongOrNull()?.takeIf { it > 0 }
                            ?: throw BadRequestProblem("Id de pregunta no válido.")
                        val saved = canonicalGateway.saveQuestion(
                            examId = examId,
                            questionId = questionId,
                            request = input.toCanonicalImport(),
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        ).toLegacyDto()
                        call.respond(saved)
                    }
                    delete("/{solucionarioId}/preguntas/{preguntaId}") {
                        val actor = call.requirePermission("content.write")
                        canonicalGateway.softDeleteContent(
                            resourceType = "question",
                            resourceId = call.requiredPositiveLongPath("preguntaId"),
                            expectedVersion = null,
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }
                    put("/{solucionarioId}/preguntas/order") {
                        val actor = call.requirePermission("content.write")
                        val examId = call.requiredPath("solucionarioId").toLongOrNull()
                            ?.takeIf { it > 0 }
                            ?: throw BadRequestProblem("Id de examen no válido.")
                        val order = call.receive<OrderDto>()
                        call.respond(
                            canonicalGateway.reorderQuestions(
                                examId = examId,
                                questionIds = order.ids.map { id ->
                                    id.toLongOrNull()?.takeIf { it > 0 }
                                        ?: throw BadRequestProblem("Id de pregunta no válido.")
                                },
                                idempotencyKey = call.requiredIdempotencyKey(),
                                actor = actor,
                            ).map(ExamQuestionResponse::toLegacyDto),
                        )
                    }
                }
                route("/rafaga") {
                    get {
                        val actor = call.requirePermission("content.read")
                        call.respond(
                            canonicalGateway.listExams(ExamType.BURST, actor, limit = 100)
                                .items
                                .map(ExamResponse::toLegacyRafagaDto),
                        )
                    }
                    post {
                        val actor = call.requirePermission("content.write")
                        val request = call.receive<RafagaDto>().toCanonicalRequest()
                        actor.requirePublishPermission(request.status)
                        call.respond(
                            HttpStatusCode.Created,
                            canonicalGateway.saveExam(null, request, actor).toLegacyRafagaDto(),
                        )
                    }
                    put {
                        val actor = call.requirePermission("content.write")
                        val input = call.receive<RafagaDto>()
                        val id = input.id.toLongOrNull()?.takeIf { it > 0 }
                            ?: throw BadRequestProblem("Id de ráfaga no válido.")
                        val request = input.toCanonicalRequest()
                        actor.requirePublishPermission(request.status)
                        call.respond(canonicalGateway.saveExam(id, request, actor).toLegacyRafagaDto())
                    }
                    delete("/{rafagaId}") {
                        val actor = call.requirePermission("content.write")
                        canonicalGateway.softDeleteContent(
                            resourceType = "exam",
                            resourceId = call.requiredPositiveLongPath("rafagaId"),
                            expectedVersion = null,
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }
                    get("/{rafagaId}/preguntas") {
                        val actor = call.requirePermission("content.read")
                        call.respond(
                            canonicalGateway.listQuestions(
                                call.requiredPositiveLongPath("rafagaId"),
                                actor,
                            ).map(ExamQuestionResponse::toLegacyRafagaQuestionDto),
                        )
                    }
                    post("/{rafagaId}/preguntas") {
                        val actor = call.requirePermission("content.write")
                        val examId = call.requiredPositiveLongPath("rafagaId")
                        val saved = canonicalGateway.saveQuestion(
                            examId = examId,
                            questionId = null,
                            request = call.receive<RafagaPreguntaDto>().toCanonicalRequest(),
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        )
                        call.respond(HttpStatusCode.Created, saved.toLegacyRafagaQuestionDto())
                    }
                    put("/{rafagaId}/preguntas") {
                        val actor = call.requirePermission("content.write")
                        val examId = call.requiredPositiveLongPath("rafagaId")
                        val input = call.receive<RafagaPreguntaDto>()
                        val questionId = input.id.toLongOrNull()?.takeIf { it > 0 }
                            ?: throw BadRequestProblem("Id de pregunta no válido.")
                        call.respond(
                            canonicalGateway.saveQuestion(
                                examId = examId,
                                questionId = questionId,
                                request = input.toCanonicalRequest(),
                                idempotencyKey = call.requiredIdempotencyKey(),
                                actor = actor,
                            ).toLegacyRafagaQuestionDto(),
                        )
                    }
                    delete("/{rafagaId}/preguntas/{preguntaId}") {
                        val actor = call.requirePermission("content.write")
                        canonicalGateway.softDeleteContent(
                            resourceType = "question",
                            resourceId = call.requiredPositiveLongPath("preguntaId"),
                            expectedVersion = null,
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }
                    put("/{rafagaId}/preguntas/order") {
                        val actor = call.requirePermission("content.write")
                        val examId = call.requiredPath("rafagaId").toLongOrNull()
                            ?.takeIf { it > 0 }
                            ?: throw BadRequestProblem("Id de ráfaga no válido.")
                        val order = call.receive<OrderDto>()
                        call.respond(
                            canonicalGateway.reorderQuestions(
                                examId = examId,
                                questionIds = order.ids.map { id ->
                                    id.toLongOrNull()?.takeIf { it > 0 }
                                        ?: throw BadRequestProblem("Id de pregunta no válido.")
                                },
                                idempotencyKey = call.requiredIdempotencyKey(),
                                actor = actor,
                            ).map { question ->
                                RafagaPreguntaDto(
                                    id = question.id.toString(),
                                    rafagaId = question.examId.toString(),
                                    enunciado = question.statement,
                                    orden = question.sortOrder,
                                    version = question.version,
                                )
                            },
                        )
                    }
                }
                route("/preguntas") {
                    post("/{preguntaId}/recursos/{resourceId}/upload") {
                        call.respondBackendSupabaseResult("/content/preguntas/{id}/recursos/{id}/upload") {
                            val actor = call.requirePermission("content.write")
                            val questionId = call.requiredPositiveLongPath("preguntaId")
                            val file = call.receiveSingleFile(
                                maxBytes = RESOURCE_MAX_BYTES,
                                requiredMimePrefix = null,
                                defaultFileName = "resource",
                            )
                            canonicalGateway.uploadQuestionResource(
                                questionId = questionId,
                                resourceId = call.requiredPositiveLongPath("resourceId"),
                                type = call.request.queryParameters["tipoRecurso"].orEmpty()
                                    .toCanonicalResourceType(),
                                title = call.request.queryParameters["titulo"].orEmpty(),
                                sortOrder = call.request.queryParameters["orden"]?.toIntOrNull() ?: 0,
                                fileName = file.fileName,
                                mimeType = file.mimeType,
                                bytes = file.bytes,
                                actor = actor,
                            ).toLegacyDto(questionId)
                        }
                    }
                    post("/{preguntaId}/recursos") {
                        val actor = call.requirePermission("content.write")
                        val questionId = call.requiredPositiveLongPath("preguntaId")
                        val input = call.receive<RecursoPreguntaDto>()
                        val resourceId = input.id.toLongOrNull()?.takeIf { it > 0 }
                        val expectedVersion = resourceId?.let {
                            canonicalGateway.currentContentVersion("resource", it, actor)
                        }
                        val saved = canonicalGateway.saveResource(
                            questionId = questionId,
                            resourceId = resourceId,
                            request = input.toCanonicalRequest(expectedVersion),
                            actor = actor,
                        ).toLegacyDto(questionId)
                        call.respond(
                            if (resourceId == null) HttpStatusCode.Created else HttpStatusCode.OK,
                            saved,
                        )
                    }
                    delete("/{preguntaId}/recursos/{resourceId}") {
                        val actor = call.requirePermission("content.write")
                        canonicalGateway.softDeleteContent(
                            resourceType = "resource",
                            resourceId = call.requiredPositiveLongPath("resourceId"),
                            expectedVersion = null,
                            idempotencyKey = call.requiredIdempotencyKey(),
                            actor = actor,
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }
                    put("/{preguntaId}/recursos/order") {
                        throw ApiProblem(
                            status = HttpStatusCode.Gone,
                            code = "legacy_write_disabled",
                            message = "La reordenación de recursos requiere la ruta canónica transaccional.",
                        )
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
                call.requirePermission("search.read")
                call.respondBackendSupabaseResult("/tags") {
                    tagSearchGateway.listTags(call.request.queryParameters["q"].orEmpty())
                }
            }
            get("/search") {
                call.requirePermission("search.read")
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
                call.requirePermission("maintenance")
                call.respond(tagSearchGateway.reindexAll())
            }
        }
    }
}

fun io.ktor.server.application.ApplicationCall.requiredPath(name: String): String {
    return parameters[name]?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing path parameter $name")
}

private fun ApplicationCall.requiredPositiveLongPath(name: String): Long =
    parameters[name]?.toLongOrNull()?.takeIf { it > 0 }
        ?: throw BadRequestProblem("Parámetro $name no válido.")

@Serializable
data class ErrorDto(val error: String, val message: String)

private class BackendRestartRequest(
    private val projectRoot: File,
    private val ngrokHost: String? = null,
) {
    fun schedule() {
        thread(name = "oposicontrol-backend-restart", isDaemon = false) {
            Thread.sleep(750)
            restartNgrokIfNeeded()
            startBackend()
            Thread.sleep(250)
            exitProcess(0)
        }
    }

    private fun restartNgrokIfNeeded() {
        val host = ngrokHost ?: return
        val script = File(projectRoot, "scripts/restart-ngrok-dev.ps1")
        if (!script.exists()) {
            System.err.println("[ktor] ngrok restart skipped: ${script.path} not found.")
            return
        }
        val command = if (isWindows()) {
            listOf(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.absolutePath,
                "-Domain",
                host,
                "-Port",
                "8080",
            )
        } else {
            listOf("pwsh", "-NoProfile", "-File", script.absolutePath, "-Domain", host, "-Port", "8080")
        }
        runCatching {
            ProcessBuilder(command)
                .directory(projectRoot)
                .redirectOutput(File(projectRoot, "ngrok-restart.out.log"))
                .redirectError(File(projectRoot, "ngrok-restart.err.log"))
                .start()
            Thread.sleep(1_500)
        }.onFailure { error ->
            System.err.println("[ktor] ngrok restart failed: ${error.safeBackendMessage("ngrok restart error", 180)}")
        }
    }

    private fun startBackend() {
        val script = File(projectRoot, "scripts/run-backend-dev.ps1")
        val command = if (script.exists() && isWindows()) {
            listOf(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.absolutePath,
            )
        } else if (script.exists()) {
            listOf("pwsh", "-NoProfile", "-File", script.absolutePath)
        } else if (isWindows()) {
            listOf("cmd", "/c", "gradlew.bat", "--no-daemon", ":backend:ktor:run")
        } else {
            listOf("./gradlew", "--no-daemon", ":backend:ktor:run")
        }
        ProcessBuilder(command)
            .directory(projectRoot)
            .redirectOutput(File(projectRoot, "backend-restart.out.log"))
            .redirectError(File(projectRoot, "backend-restart.err.log"))
            .start()
    }

    companion object {
        fun fromEnvironment(ngrokHost: String? = null): BackendRestartRequest? {
            if (!isEnabled()) return null
            return findProjectRoot(File(System.getProperty("user.dir")))
                ?.let { BackendRestartRequest(projectRoot = it, ngrokHost = ngrokHost) }
        }

        fun isEnabled(): Boolean {
            val explicit = System.getenv("OPOSICONTROL_ENABLE_BACKEND_RESTART")?.toBooleanStrictOrNull()
            if (explicit != null) return explicit
            val appEnv = System.getenv("APP_ENV").orEmpty().ifBlank { "dev" }
            return !appEnv.equals("prod", ignoreCase = true) &&
                !appEnv.equals("production", ignoreCase = true)
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

        private fun isWindows(): Boolean {
            return System.getProperty("os.name").contains("Windows", ignoreCase = true)
        }
    }
}

private fun ApplicationCall.ngrokHostForRestart(): String? {
    val forwardedHost = request.headers["X-Forwarded-Host"]
        ?.substringBefore(',')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val host = (forwardedHost ?: request.host())
        .substringBefore(':')
        .trim()
        .lowercase()
    return host.takeIf {
        it.endsWith(".ngrok-free.dev") ||
            it.endsWith(".ngrok.app") ||
            it.endsWith(".ngrok.io")
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
        System.err.println("[ktor] $routeName request failure type=${cause::class.simpleName}")
        throw BadRequestProblem()
    } catch (cause: ApiProblem) {
        throw cause
    } catch (cause: Throwable) {
        if (cause is kotlinx.coroutines.CancellationException) throw cause
        System.err.println("[ktor] $routeName supabase failure type=${cause::class.simpleName}")
        throw UpstreamProblem(cause = cause)
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
    val codigo: String = "",
    val titulo: String,
    val especialidadId: String,
    val descripcion: String = "",
    val tipoExamen: String = "inmersivo",
    val tipoAcceso: String = "premium",
    val precioTickets: Int = 0,
    val estado: String = "draft",
    val publicarDesde: String? = null,
    val publishedAt: String? = null,
    val orden: Int = 0,
    val duracionMinutos: Int? = null,
    val anio: Int? = null,
    val esOficial: Boolean = false,
    val portadaUrl: String? = null,
    val portadaStoragePath: String? = null,
    val portadaMimeType: String? = null,
    val version: Long = 1,
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
    val version: Long = 1,
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
    val version: Long = 1,
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
    val opciones: List<OpcionDto> = emptyList(),
    val recursos: List<RecursoPreguntaDto> = emptyList(),
    val version: Long = 1,
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

private fun PreguntaDto.toCanonicalImport() = UpsertQuestionRequest(
    title = titulo,
    statement = enunciado,
    sortOrder = orden,
    options = opciones.map { option ->
        QuestionOptionInput(
            id = option.id.toLongOrNull(),
            label = option.label,
            content = option.contenido,
            correct = option.esCorrecta,
            sortOrder = option.orden,
        )
    },
    expectedVersion = id.takeIf(String::isNotBlank)?.let { version },
)

private fun ExamQuestionResponse.toLegacyDto() = PreguntaDto(
    id = id.toString(),
    solucionarioId = examId.toString(),
    titulo = title,
    enunciado = statement,
    orden = sortOrder,
    opciones = options.map { option ->
        OpcionDto(
            id = option.id.toString(),
            label = option.label,
            contenido = option.content,
            esCorrecta = option.correct,
            orden = option.sortOrder,
        )
    },
    recursos = resources.map { resource ->
        RecursoPreguntaDto(
            id = resource.id.toString(),
            preguntaId = id.toString(),
            tipoRecurso = resource.type.name.lowercase(),
            titulo = resource.title,
            url = resource.downloadUrl.orEmpty(),
            storagePath = resource.storagePath,
            publicUrl = resource.downloadUrl,
            mimeType = resource.mimeType,
            orden = resource.sortOrder,
        )
    },
    version = version,
)

private fun SpecialtyResponse.toLegacyDto() = EspecialidadDto(
    id = id.toString(),
    nombre = name,
    slug = slug,
    activa = active,
    linkedExams = linkedExamCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun EspecialidadDto.toCanonicalRequest(expectedVersion: Long? = null) = UpsertSpecialtyRequest(
    name = nombre,
    slug = slug.ifBlank { nombre.toSlug() },
    active = activa,
    expectedVersion = expectedVersion,
)

private fun ExamResponse.toLegacyDto() = SolucionarioDto(
    id = id.toString(),
    codigo = code,
    titulo = title,
    especialidadId = specialtyId.toString(),
    descripcion = description,
    tipoExamen = if (type == ExamType.BURST) "rafaga" else "inmersivo",
    tipoAcceso = if (accessType == ExamAccessType.FREE) "gratuito" else "premium",
    precioTickets = priceTickets.toInt(),
    estado = status.databaseValue(),
    publicarDesde = publishFrom,
    publishedAt = publishedAt,
    orden = sortOrder,
    duracionMinutos = durationMinutes,
    anio = year,
    esOficial = official,
    portadaUrl = coverUrl,
    version = version,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun SolucionarioDto.toCanonicalRequest() = UpsertExamRequest(
    specialtyId = especialidadId.toLongOrNull()?.takeIf { it > 0 }
        ?: throw BadRequestProblem("Especialidad no válida."),
    code = codigo.ifBlank { "exam-${id.ifBlank { UUID.randomUUID().toString().take(8) }}" },
    title = titulo,
    description = descripcion,
    type = if (tipoExamen == "rafaga") ExamType.BURST else ExamType.IMMERSIVE,
    accessType = if (tipoAcceso == "gratuito") ExamAccessType.FREE else ExamAccessType.PREMIUM,
    priceTickets = precioTickets.toLong(),
    status = estado.toPublicationStatus(),
    publishFrom = publicarDesde,
    sortOrder = orden,
    durationMinutes = duracionMinutos,
    year = anio,
    official = esOficial,
    expectedVersion = id.takeIf(String::isNotBlank)?.let { version },
)

private fun ExamResponse.toLegacyRafagaDto() = RafagaDto(
    id = id.toString(),
    titulo = title,
    especialidadId = specialtyId.toString(),
    precioTickets = priceTickets.toInt(),
    estado = status.databaseValue(),
    publicarDesde = publishFrom,
    publishedAt = publishedAt,
    questionCount = questionCount,
    version = version,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun RafagaDto.toCanonicalRequest() = UpsertExamRequest(
    specialtyId = especialidadId.toLongOrNull()?.takeIf { it > 0 }
        ?: throw BadRequestProblem("Especialidad no válida."),
    code = "rafaga-${id.ifBlank { UUID.randomUUID().toString().take(8) }}",
    title = titulo,
    type = ExamType.BURST,
    accessType = ExamAccessType.PREMIUM,
    priceTickets = precioTickets.toLong(),
    status = estado.toPublicationStatus(),
    publishFrom = publicarDesde,
    expectedVersion = id.takeIf(String::isNotBlank)?.let { version },
)

private fun ExamQuestionResponse.toLegacyRafagaQuestionDto() = RafagaPreguntaDto(
    id = id.toString(),
    rafagaId = examId.toString(),
    enunciado = statement,
    orden = sortOrder,
    opciones = options.map { option ->
        OpcionDto(
            id = option.id.toString(),
            label = option.label,
            contenido = option.content,
            esCorrecta = option.correct,
            orden = option.sortOrder,
        )
    },
    recursos = resources.map { resource -> resource.toLegacyDto(id) },
    version = version,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun RafagaPreguntaDto.toCanonicalRequest() = UpsertQuestionRequest(
    title = "Pregunta ${orden + 1}",
    statement = enunciado,
    sortOrder = orden,
    options = opciones.map { option ->
        QuestionOptionInput(
            id = option.id.toLongOrNull(),
            label = option.label,
            content = option.contenido,
            correct = option.esCorrecta,
            sortOrder = option.orden,
        )
    },
    expectedVersion = id.takeIf(String::isNotBlank)?.let { version },
)

private fun RecursoPreguntaDto.toCanonicalRequest(expectedVersion: Long?) = UpsertQuestionResourceRequest(
    type = tipoRecurso.toCanonicalResourceType(),
    title = titulo,
    textBody = url.takeIf { tipoRecurso.toCanonicalResourceType() == QuestionResourceType.TEXT },
    storagePath = storagePath,
    mimeType = mimeType,
    sortOrder = orden,
    expectedVersion = expectedVersion,
)

private fun QuestionResourceResponse.toLegacyDto(questionId: Long) = RecursoPreguntaDto(
    id = id.toString(),
    preguntaId = questionId.toString(),
    tipoRecurso = type.name.lowercase(),
    titulo = title,
    url = downloadUrl ?: textBody.orEmpty(),
    storagePath = storagePath,
    publicUrl = downloadUrl,
    mimeType = mimeType,
    orden = sortOrder,
)

private fun String.toCanonicalResourceType(): QuestionResourceType = when (lowercase()) {
    "imagen", "image" -> QuestionResourceType.IMAGE
    "audio" -> QuestionResourceType.AUDIO
    "video" -> QuestionResourceType.VIDEO
    "texto", "text" -> QuestionResourceType.TEXT
    "documento", "document" -> QuestionResourceType.DOCUMENT
    else -> throw BadRequestProblem("Tipo de recurso no válido.")
}

private fun String.toPublicationStatus(): PublicationStatus = when (lowercase()) {
    "published" -> PublicationStatus.PUBLISHED
    "archived" -> PublicationStatus.ARCHIVED
    "draft" -> PublicationStatus.DRAFT
    else -> throw BadRequestProblem("Estado de publicación no válido.")
}

private fun PublicationStatus.databaseValue(): String = when (this) {
    PublicationStatus.DRAFT -> "draft"
    PublicationStatus.PUBLISHED -> "published"
    PublicationStatus.ARCHIVED -> "archived"
}

private fun AdminPrincipal.requirePublishPermission(status: PublicationStatus) {
    if (status == PublicationStatus.PUBLISHED && "content.publish" !in permissions) {
        throw ForbiddenProblem("content.publish")
    }
}

private class SupabaseContentGateway private constructor(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val examCoversBucket: String,
    private val examResourcesBucket: String,
    private val json: Json,
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    fun listEspecialidades(): List<EspecialidadDto> {
        val rows = request("GET", "/rest/v1/especialidad?select=*&order=nombre.asc", null)
            .decodeList<EspecialidadRow>()
        val linkedCounts = request("GET", "/rest/v1/examen?select=id,especialidad_id", null)
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
        request("DELETE", "/rest/v1/especialidad?id=eq.${especialidadId.toIntId()}", null)
    }

    fun listSolucionarios(): List<SolucionarioDto> {
        val specialties = listEspecialidades().associateBy { it.id }
        val counts = request("GET", "/rest/v1/pregunta?select=id,examen_id", null)
            .decodeList<PreguntaCountRow>()
            .groupingBy { it.examenId.toString() }
            .eachCount()
        return request("GET", "/rest/v1/examen?select=*&order=id.desc", null)
            .decodeList<ExamenRow>()
            .map { row ->
                row.toDto(
                    specialties[row.especialidadId.toString()]?.nombre.orEmpty(),
                    counts[row.id?.toString().orEmpty()] ?: 0,
                ).copy(
                    portadaStoragePath = row.portadaStoragePath
                        ?.removePrefix("$examCoversBucket/")
                        ?.removePrefix("/"),
                    portadaUrl = row.portadaStoragePath
                        ?.takeIf(String::isNotBlank)
                        ?.let { storageSignedUrl(examCoversBucket, it) }
                        ?: row.portadaUrl,
                )
            }
    }

    fun listRafagas(): List<RafagaDto> {
        val counts = request("GET", "/rest/v1/pregunta?select=id,examen_id&deleted_at=is.null", null)
            .decodeList<PreguntaCountRow>()
            .groupingBy { it.examenId.toString() }
            .eachCount()
        return listSolucionarios()
            .filter { it.tipoExamen == "rafaga" }
            .map { exam ->
                RafagaDto(
                    id = exam.id,
                    titulo = exam.titulo,
                    especialidadId = exam.especialidadId,
                    precioTickets = exam.precioTickets,
                    estado = exam.estado,
                    publicarDesde = exam.publicarDesde,
                    publishedAt = exam.publishedAt,
                    questionCount = counts[exam.id] ?: 0,
                    version = exam.version,
                    createdAt = exam.createdAt,
                    updatedAt = exam.updatedAt,
                    deletedAt = exam.deletedAt,
                )
            }
    }

    fun saveSolucionario(input: SolucionarioDto): SolucionarioDto {
        require(input.titulo.isNotBlank()) { "El solucionario necesita un nombre." }
        require(input.especialidadId.isNotBlank()) { "El solucionario necesita una especialidad." }
        require(input.tipoExamen in setOf("inmersivo", "rafaga")) { "Tipo de examen no valido." }
        require(input.tipoAcceso in setOf("gratuito", "premium")) { "Tipo de acceso no valido." }
        require(input.estado in setOf("draft", "published", "archived")) { "Estado de solucionario no valido." }
        if (input.estado == "published" && input.tipoExamen != "rafaga") {
            require(!input.portadaUrl.isNullOrBlank() || !input.portadaStoragePath.isNullOrBlank()) {
                "Anade una portada antes de publicar el examen."
            }
        }
        require(input.precioTickets >= 0) { "El precio en tickets no puede ser negativo." }
        require(input.duracionMinutos == null || input.duracionMinutos > 0) { "Duracion no valida." }
        require(input.anio == null || input.anio in 1900..2200) { "Anio no valido." }
        val normalized = input.normalizedForSave()
        val saved = if (normalized.id.isBlank()) {
            request(
                "POST",
                "/rest/v1/examen",
                json.encodeToString(listOf(normalized.toRow())),
                prefer = "return=representation",
            ).decodeList<ExamenRow>().first()
        } else {
            request(
                "PATCH",
                "/rest/v1/examen?id=eq.${normalized.id.toIntId()}&version=eq.${normalized.version}",
                json.encodeToString(normalized.toRow().copy(id = null)),
                prefer = "return=representation",
            ).decodeList<ExamenRow>().firstOrNull() ?: throw ConflictProblem()
        }
        return saved.toDto("", 0)
    }

    fun saveRafaga(input: RafagaDto): RafagaDto {
        require(input.titulo.isNotBlank()) { "La rafaga necesita un nombre." }
        require(input.especialidadId.isNotBlank()) { "La rafaga necesita una especialidad." }
        require(input.estado in setOf("draft", "published", "archived")) { "Estado de rafaga no valido." }
        require(input.precioTickets >= 0) { "El precio en tickets no puede ser negativo." }
        val saved = saveSolucionario(
            SolucionarioDto(
                id = input.id,
                codigo = "rafaga-${input.id.ifBlank { UUID.randomUUID().toString().take(8) }}",
                titulo = input.titulo,
                especialidadId = input.especialidadId,
                tipoExamen = "rafaga",
                tipoAcceso = "gratuito",
                precioTickets = input.precioTickets,
                estado = input.estado,
                publicarDesde = input.publicarDesde,
                publishedAt = input.publishedAt,
                version = input.version,
            ),
        )
        return input.copy(
            id = saved.id,
            publicarDesde = saved.publicarDesde,
            publishedAt = saved.publishedAt,
            version = saved.version,
            createdAt = saved.createdAt,
            updatedAt = saved.updatedAt,
        )
    }

    fun uploadPortada(solucionarioId: String, file: BackendUploadedFile): SolucionarioDto {
        require(file.mimeType.startsWith("image/")) { "La portada debe ser una imagen." }
        val examId = solucionarioId.toIntId()
        val previous = request(
            "GET",
            "/rest/v1/examen?select=portada_storage_path&id=eq.$examId&limit=1",
            null,
        ).decodeList<ExamenCoverPathRow>().firstOrNull()
        val objectPath = "$examId/${file.fileName.sanitizeStorageSegment()}"
        storageUpload(examCoversBucket, objectPath, file.mimeType, file.bytes)
        val saved = try {
            request(
                "PATCH",
                "/rest/v1/examen?id=eq.$examId",
                json.encodeToString(
                    mapOf(
                        "portada_url" to null,
                        "portada_storage_path" to objectPath,
                        "portada_mime_type" to file.mimeType,
                    ),
                ),
                prefer = "return=representation",
            ).decodeList<ExamenRow>().firstOrNull()
                ?: throw IllegalArgumentException("No existe el solucionario $solucionarioId.")
        } catch (error: Throwable) {
            storageDelete(examCoversBucket, objectPath, "cover-database-write-failed")
            if (error is IllegalStateException && error.isMissingCoverColumnError()) {
                throw IllegalArgumentException(
                    "Faltan columnas de portada en public.examen. Ejecuta docs/supabase/20260507_exam_cover_resource_uploads.sql en Supabase.",
                )
            }
            throw error
        }
        previous?.portadaStoragePath
            ?.takeIf { it.isNotBlank() && it != objectPath }
            ?.let { storageDelete(examCoversBucket, it, "cover-replaced") }
        return saved.toDto("", 0).copy(portadaUrl = storageSignedUrl(examCoversBucket, objectPath))
    }

    fun clearPortada(solucionarioId: String): SolucionarioDto {
        val examId = solucionarioId.toIntId()
        val previous = request(
            "GET",
            "/rest/v1/examen?select=portada_storage_path&id=eq.$examId&limit=1",
            null,
        ).decodeList<ExamenCoverPathRow>().firstOrNull()
            ?: throw IllegalArgumentException("No existe el solucionario $solucionarioId.")
        val saved = request(
            "PATCH",
            "/rest/v1/examen?id=eq.$examId",
            json.encodeToString(
                mapOf(
                    "portada_url" to null,
                    "portada_storage_path" to null,
                    "portada_mime_type" to null,
                ),
            ),
            prefer = "return=representation",
        ).decodeList<ExamenRow>().firstOrNull()
            ?: throw IllegalArgumentException("No existe el solucionario $solucionarioId.")
        previous.portadaStoragePath?.takeIf { it.isNotBlank() }
            ?.let { storageDelete(examCoversBucket, it, "cover-cleared") }
        return saved.toDto("", 0)
    }

    fun deleteSolucionario(solucionarioId: String) {
        val examId = solucionarioId.toIntId()
        val activeExam = request(
            "GET",
            "/rest/v1/examen?select=portada_storage_path&id=eq.$examId&limit=1",
            null,
        ).decodeList<ExamenCoverPathRow>().firstOrNull()
            ?: throw IllegalArgumentException("El solucionario no existe en la base de datos.")
        val coverPath = activeExam.portadaStoragePath
        val questions = request(
            "GET",
            "/rest/v1/pregunta?select=id&examen_id=eq.$examId",
            null,
        ).decodeList<PreguntaIdRow>()
        val questionIds = questions.mapNotNull { it.id }
        val resourcePaths = if (questionIds.isEmpty()) {
            emptyList()
        } else {
            val idsFilter = questionIds.joinToString(",")
            request(
                "GET",
                "/rest/v1/recurso_pregunta?select=storage_path&pregunta_id=in.($idsFilter)",
                null,
            ).decodeList<RecursoStoragePathRow>().mapNotNull { it.storagePath?.takeIf(String::isNotBlank) }
        }
        if (questionIds.isNotEmpty()) {
            val idsFilter = questionIds.joinToString(",")
            request(
                "DELETE",
                "/rest/v1/recurso_pregunta?pregunta_id=in.($idsFilter)",
                null,
            )
            requestIfRelationExists(
                "DELETE",
                "/rest/v1/opcion_pregunta?pregunta_id=in.($idsFilter)",
                null,
            )
            request(
                "DELETE",
                "/rest/v1/pregunta?id=in.($idsFilter)",
                null,
            )
        }
        request("DELETE", "/rest/v1/examen?id=eq.$examId", null)
        requestIfRelationExists(
            "DELETE",
            "/rest/v1/acceso_examen_usuario?examen_id=eq.$examId",
            null,
        )
        coverPath?.takeIf(String::isNotBlank)
            ?.let { storageDelete(examCoversBucket, it, "exam-deleted") }
        resourcePaths.distinct().forEach { storageDelete(examResourcesBucket, it, "exam-deleted") }
    }

    fun deleteRafaga(rafagaId: String) {
        val activeRafaga = request(
            "GET",
            "/rest/v1/examen?select=id&id=eq.${rafagaId.toIntId()}&tipo_examen=eq.rafaga&limit=1",
            null,
        ).decodeList<IdRow>().firstOrNull()
            ?: throw IllegalArgumentException("La rafaga no existe en la base de datos.")
        deleteSolucionario(activeRafaga.id.toString())
    }

    fun listPreguntas(solucionarioId: String): List<PreguntaDto> {
        val questions = request(
            "GET",
            "/rest/v1/pregunta?select=*&examen_id=eq.${solucionarioId.toIntId()}&order=orden.asc",
            null,
        ).decodeList<PreguntaRow>()
        if (questions.isEmpty()) return emptyList()
        val ids = questions.mapNotNull { it.id }.joinToString(",")
        val resources = request(
            "GET",
            "/rest/v1/recurso_pregunta?select=*&pregunta_id=in.($ids)&order=id.asc",
            null,
        ).decodeList<RecursoPreguntaRow>()
            .map { row ->
                row.toDto().let { resource ->
                    val normalizedPath = resource.storagePath
                        ?.removePrefix("$examResourcesBucket/")
                        ?.removePrefix("/")
                    val signedUrl = normalizedPath
                        ?.takeIf(String::isNotBlank)
                        ?.let { storageSignedUrl(examResourcesBucket, it) }
                    resource.copy(
                        storagePath = normalizedPath,
                        url = signedUrl ?: resource.url,
                        publicUrl = signedUrl ?: resource.publicUrl,
                    )
                }
            }
            .groupBy { it.preguntaId.toIntOrNull() }
        return questions.map { row -> row.toDto(resources[row.id].orEmpty()) }
    }

    fun listRafagaPreguntas(rafagaId: String): List<RafagaPreguntaDto> {
        return listPreguntas(rafagaId).map { question ->
            RafagaPreguntaDto(
                id = question.id,
                rafagaId = question.solucionarioId,
                enunciado = question.enunciado,
                orden = question.orden,
                version = question.version,
            )
        }
    }

    fun savePregunta(solucionarioId: String, input: PreguntaDto): PreguntaDto {
        validatePregunta(input)
        val row = input.toRow(solucionarioId, json)
        val saved = if (input.id.isBlank()) {
            request(
                "POST",
                "/rest/v1/pregunta",
                json.encodeToString(listOf(row)),
                prefer = "return=representation",
            ).decodeList<PreguntaRow>().first()
        } else {
            request(
                "PATCH",
                "/rest/v1/pregunta?id=eq.${input.id.toIntId()}&examen_id=eq.${solucionarioId.toIntId()}&version=eq.${input.version}",
                json.encodeToString(row.copy(id = null, version = 1)),
                prefer = "return=representation",
            ).decodeList<PreguntaRow>().firstOrNull() ?: throw ConflictProblem()
        }
        val savedId = saved.id ?: error("Supabase no devolvio id de pregunta.")
        replaceResources(savedId, input.recursos)
        return saved.toDto(input.recursos.map { it.copy(preguntaId = savedId.toString()) })
    }

    fun saveRafagaPregunta(rafagaId: String, input: RafagaPreguntaDto): RafagaPreguntaDto {
        require(input.enunciado.isNotBlank()) { "La pregunta de rafaga necesita enunciado." }
        val saved = savePregunta(
            solucionarioId = rafagaId,
            input = PreguntaDto(
                id = input.id,
                solucionarioId = rafagaId,
                titulo = "Pregunta ${input.orden + 1}",
                enunciado = input.enunciado,
                orden = input.orden,
                version = input.version,
            ),
        )
        return input.copy(
            id = saved.id,
            rafagaId = saved.solucionarioId,
            orden = saved.orden,
            version = saved.version,
        )
    }

    fun deletePregunta(solucionarioId: String, preguntaId: String) {
        val resourcePaths = request(
            "GET",
            "/rest/v1/recurso_pregunta?select=storage_path&pregunta_id=eq.${preguntaId.toIntId()}",
            null,
        ).decodeList<RecursoStoragePathRow>().mapNotNull { it.storagePath?.takeIf(String::isNotBlank) }
        request("DELETE", "/rest/v1/recurso_pregunta?pregunta_id=eq.${preguntaId.toIntId()}", null)
        requestIfRelationExists("DELETE", "/rest/v1/opcion_pregunta?pregunta_id=eq.${preguntaId.toIntId()}", null)
        request("DELETE", "/rest/v1/pregunta?id=eq.${preguntaId.toIntId()}&examen_id=eq.${solucionarioId.toIntId()}", null)
        resourcePaths.distinct().forEach { storageDelete(examResourcesBucket, it, "question-deleted") }
    }

    fun deleteRafagaPregunta(rafagaId: String, preguntaId: String) {
        deletePregunta(rafagaId, preguntaId)
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
        return reorderPreguntas(rafagaId, order).map { question ->
            RafagaPreguntaDto(
                id = question.id,
                rafagaId = rafagaId,
                enunciado = question.enunciado,
                orden = question.orden,
                version = question.version,
            )
        }
    }

    fun saveRecurso(preguntaId: String, input: RecursoPreguntaDto): RecursoPreguntaDto {
        validateRecurso(input)
        val saved = request(
            "POST",
            "/rest/v1/recurso_pregunta?on_conflict=id",
            json.encodeToString(listOf(input.toRow(preguntaId))),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<RecursoPreguntaRow>().first()
        return saved.toDto().let { resource ->
            val signedUrl = resource.storagePath
                ?.takeIf(String::isNotBlank)
                ?.let { storageSignedUrl(examResourcesBucket, it) }
            if (signedUrl == null) resource else resource.copy(url = signedUrl, publicUrl = signedUrl)
        }
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
        storageUpload(examResourcesBucket, objectPath, file.mimeType, file.bytes)
        return try {
            val signedUrl = storageSignedUrl(examResourcesBucket, objectPath)
            saveRecurso(
                preguntaId = preguntaId,
                input = RecursoPreguntaDto(
                    id = resourceId,
                    preguntaId = preguntaId,
                    tipoRecurso = databaseType,
                    titulo = titulo.ifBlank { file.fileName },
                    url = "",
                    storagePath = objectPath,
                    publicUrl = null,
                    mimeType = file.mimeType,
                    orden = orden,
                ),
            ).copy(url = signedUrl, publicUrl = signedUrl)
        } catch (error: Throwable) {
            storageDelete(examResourcesBucket, objectPath, "resource-database-write-failed")
            throw error
        }
    }

    fun deleteRecurso(preguntaId: String, resourceId: String) {
        val resource = request(
            "GET",
            "/rest/v1/recurso_pregunta?select=*&id=eq.${resourceId.toIntId()}&pregunta_id=eq.${preguntaId.toIntId()}&limit=1",
            null,
        ).decodeList<RecursoPreguntaRow>().firstOrNull()
            ?: throw IllegalArgumentException("El recurso no existe.")
        request(
            "DELETE",
            "/rest/v1/recurso_pregunta?id=eq.${resourceId.toIntId()}&pregunta_id=eq.${preguntaId.toIntId()}",
            null,
        )
        resource.storagePath?.takeIf { it.isNotBlank() }
            ?.let { storageDelete(examResourcesBucket, it, "resource-deleted") }
    }

    fun reorderRecursos(preguntaId: String, order: OrderDto): List<RecursoPreguntaDto> {
        val current = request(
            "GET",
            "/rest/v1/recurso_pregunta?select=*&pregunta_id=eq.${preguntaId.toIntId()}&order=id.asc",
            null,
        ).decodeList<RecursoPreguntaRow>().map { it.toDto() }
        require(order.ids.toSet() == current.map { it.id }.toSet()) { "El orden debe contener los mismos recursos." }
        return order.ids.mapNotNull { id -> current.firstOrNull { it.id == id } }
    }

    private fun replaceResources(questionId: Int, resources: List<RecursoPreguntaDto>) {
        val previousStoragePaths = request(
            "GET",
            "/rest/v1/recurso_pregunta?select=storage_path&pregunta_id=eq.$questionId",
            null,
        ).decodeList<RecursoStoragePathRow>().mapNotNull { it.storagePath?.takeIf(String::isNotBlank) }
        val nextStoragePaths = resources.mapNotNull { it.storagePath?.takeIf(String::isNotBlank) }.toSet()
        request("DELETE", "/rest/v1/recurso_pregunta?pregunta_id=eq.$questionId", null)
        if (resources.isNotEmpty()) {
            request(
                "POST",
                "/rest/v1/recurso_pregunta",
                json.encodeToString(resources.map { it.toRow(questionId.toString()) }),
                prefer = "return=minimal",
            )
        }
        previousStoragePaths.filterNot { it in nextStoragePaths }.distinct()
            .forEach { storageDelete(examResourcesBucket, it, "resource-replaced") }
    }

    private fun validatePregunta(pregunta: PreguntaDto) {
        require(pregunta.enunciado.isNotBlank()) { "La pregunta necesita enunciado." }
        if (pregunta.opciones.isNotEmpty()) {
            require(pregunta.opciones.size == 4) { "Una pregunta tipo test debe tener exactamente 4 opciones." }
            require(pregunta.opciones.count { it.esCorrecta } == 1) { "Una pregunta tipo test debe tener exactamente una opcion correcta." }
        }
    }

    private fun validateRecurso(recurso: RecursoPreguntaDto) {
        require(recurso.tipoRecurso in setOf("imagen", "audio", "video", "documento", "texto")) { "Tipo de recurso no valido." }
        require(recurso.url.isNotBlank() || !recurso.storagePath.isNullOrBlank() || !recurso.publicUrl.isNullOrBlank()) {
            "El recurso necesita una URL o ruta de archivo."
        }
    }

    private fun validateResourceMime(tipoRecurso: String, mimeType: String) {
        val valid = when (tipoRecurso) {
            "imagen" -> mimeType.startsWith("image/")
            "audio" -> mimeType.startsWith("audio/")
            "video" -> mimeType.startsWith("video/")
            "texto" -> mimeType.startsWith("text/")
            "documento" -> mimeType.startsWith("application/") || mimeType.startsWith("text/")
            else -> false
        }
        require(valid) { "Tipo MIME $mimeType no permitido para $tipoRecurso." }
    }

    private fun storageUpload(bucket: String, objectPath: String, mimeType: String, bytes: ByteArray) {
        val bucketId = bucket.toStorageBucketId()
        val normalizedPath = objectPath.removePrefix("$bucketId/").removePrefix("/")
        val encodedPath = normalizedPath.split('/').joinToString("/") { it.urlEncode() }
        val path = "/storage/v1/object/$bucketId/$encodedPath"
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

    private fun storageDelete(bucket: String, objectPath: String, reason: String) {
        val bucketId = bucket.toStorageBucketId()
        val normalizedPath = objectPath.removePrefix("$bucketId/").removePrefix("/")
        val encodedPath = normalizedPath.split('/').joinToString("/") { it.urlEncode() }
        val path = "/storage/v1/object/$bucketId/$encodedPath"
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .header("apikey", serviceRoleKey)
            .header("Authorization", "Bearer $serviceRoleKey")
        val failure = runCatching {
            val response = client.send(builder.DELETE().build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299 && response.statusCode() != 404) {
                error("Storage delete status ${response.statusCode()}")
            }
        }.exceptionOrNull()
        if (failure != null) {
            enqueueStorageCleanup(bucketId, normalizedPath, reason)
        }
    }

    private fun storageSignedUrl(bucket: String, objectPath: String): String {
        val bucketId = bucket.toStorageBucketId()
        val normalizedPath = objectPath.removePrefix("$bucketId/").removePrefix("/")
        val encodedPath = normalizedPath.split('/').joinToString("/") { it.urlEncode() }
        val response = request(
            "POST",
            "/storage/v1/object/sign/$bucketId/$encodedPath",
            """{"expiresIn":600}""",
        ).let { json.decodeFromString<LegacySignedStorageUrl>(it) }
        return if (response.signedUrl.startsWith("http")) {
            response.signedUrl
        } else {
            "$supabaseUrl/storage/v1${response.signedUrl}"
        }
    }

    private fun enqueueStorageCleanup(bucket: String, objectPath: String, reason: String) {
        runCatching {
            request(
                "POST",
                "/rest/v1/rpc/admin_enqueue_storage_cleanup",
                json.encodeToString(
                    mapOf(
                        "p_bucket" to bucket,
                        "p_object_path" to objectPath,
                        "p_reason" to reason.take(120),
                    ),
                ),
            )
        }.onFailure { error ->
            System.err.println(
                "[ktor] storage cleanup enqueue failed bucket=$bucket type=${error::class.simpleName}",
            )
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
            val serviceRoleKey = System.getenv("SUPABASE_SECRET_KEY").orEmpty()
            val coversBucket = System.getenv("SUPABASE_BUCKET_EXAM_COVERS").orEmpty().ifBlank { "exam-covers" }
            val resourcesBucket = System.getenv("SUPABASE_BUCKET_EXAM_RESOURCES").orEmpty().ifBlank { "exam-resources" }
            return if (url.isBlank() || serviceRoleKey.isBlank()) {
                null
            } else {
                SupabaseContentGateway(url, serviceRoleKey, coversBucket, resourcesBucket, json)
            }
        }
    }
}

@Serializable
private data class LegacySignedStorageUrl(
    @kotlinx.serialization.SerialName("signedURL") val signedUrl: String,
)

@Serializable
private data class EspecialidadRow(
    val id: Int? = null,
    val nombre: String,
    val slug: String = "",
    val activa: Boolean = true,
)

@Serializable
private data class ExamenRow(
    val id: Int? = null,
    val codigo: String = "",
    val titulo: String,
    val descripcion: String = "",
    @kotlinx.serialization.SerialName("precio_tickets") val precioTickets: Int = 0,
    @kotlinx.serialization.SerialName("especialidad_id") val especialidadId: Int,
    @kotlinx.serialization.SerialName("tipo_examen") val tipoExamen: String = "inmersivo",
    @kotlinx.serialization.SerialName("tipo_acceso") val tipoAcceso: String = "premium",
    val estado: String = "draft",
    @kotlinx.serialization.SerialName("publicar_desde") val publicarDesde: String? = null,
    @kotlinx.serialization.SerialName("published_at") val publishedAt: String? = null,
    val orden: Int = 0,
    @kotlinx.serialization.SerialName("duracion_minutos") val duracionMinutos: Int? = null,
    val anio: Int? = null,
    val oficial: Boolean = false,
    @kotlinx.serialization.SerialName("portada_url") val portadaUrl: String? = null,
    @kotlinx.serialization.SerialName("portada_storage_path") val portadaStoragePath: String? = null,
    @kotlinx.serialization.SerialName("portada_mime_type") val portadaMimeType: String? = null,
    val version: Long = 1,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String = "",
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: String = "",
    @kotlinx.serialization.SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
private data class ExamenCountRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("especialidad_id") val especialidadId: Int,
)

@Serializable
private data class ExamenCoverPathRow(
    @kotlinx.serialization.SerialName("portada_storage_path") val portadaStoragePath: String? = null,
)

@Serializable
private data class PreguntaRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("examen_id") val examenId: Int,
    val titulo: String = "",
    val enunciado: String,
    val orden: Int = 0,
    val version: Long = 1,
)

@Serializable
private data class PreguntaIdRow(
    val id: Int? = null,
)

@Serializable
private data class IdRow(
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
)

@Serializable
private data class RafagaPreguntaRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("rafaga_id") val rafagaId: Int,
    val enunciado: String,
    val orden: Int = 0,
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
private data class RecursoStoragePathRow(
    @kotlinx.serialization.SerialName("storage_path") val storagePath: String? = null,
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
    createdAt = "",
    updatedAt = "",
)

private fun EspecialidadDto.toRow() = EspecialidadRow(
    id = id.toIntOrNull(),
    nombre = nombre,
    slug = slug.ifBlank { nombre.toSlug() },
    activa = activa,
)

private fun ExamenRow.toDto(specialtyName: String, questionCount: Int) = SolucionarioDto(
    id = id?.toString().orEmpty(),
    codigo = codigo,
    titulo = titulo,
    especialidadId = especialidadId.toString(),
    descripcion = descripcion.ifBlank { specialtyName },
    tipoExamen = tipoExamen,
    tipoAcceso = tipoAcceso,
    precioTickets = precioTickets,
    estado = estado,
    publicarDesde = publicarDesde,
    publishedAt = publishedAt,
    orden = orden,
    duracionMinutos = duracionMinutos,
    anio = anio,
    esOficial = oficial,
    portadaUrl = portadaUrl,
    portadaStoragePath = portadaStoragePath,
    portadaMimeType = portadaMimeType,
    version = version,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

private fun SolucionarioDto.toRow() = ExamenRow(
    id = id.toIntOrNull(),
    codigo = codigo.ifBlank { "exam-${id.ifBlank { UUID.randomUUID().toString().take(8) }}" },
    titulo = titulo,
    descripcion = descripcion,
    precioTickets = precioTickets,
    especialidadId = especialidadId.toIntOrNull() ?: 1,
    tipoExamen = tipoExamen,
    tipoAcceso = tipoAcceso,
    estado = estado,
    publicarDesde = publicarDesde,
    publishedAt = publishedAt,
    orden = orden,
    duracionMinutos = duracionMinutos,
    anio = anio,
    oficial = esOficial,
    portadaUrl = portadaUrl,
    portadaStoragePath = portadaStoragePath,
    portadaMimeType = portadaMimeType,
    version = 1,
)

private fun RafagaRow.toDto(questionCount: Int) = RafagaDto(
    id = id?.toString().orEmpty(),
    titulo = titulo,
    especialidadId = especialidadId.toString(),
    precioTickets = precioTickets,
    estado = estado,
    publicarDesde = null,
    publishedAt = null,
    questionCount = questionCount,
    createdAt = "",
    updatedAt = "",
    deletedAt = null,
)

private fun RafagaDto.toRow() = RafagaRow(
    id = id.toIntOrNull(),
    titulo = titulo,
    precioTickets = precioTickets,
    especialidadId = especialidadId.toIntOrNull() ?: 1,
    estado = estado,
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
        version = version,
    )
}

private fun PreguntaDto.toRow(examId: String, json: Json) = PreguntaRow(
    id = id.toIntOrNull(),
    examenId = examId.toIntOrNull() ?: solucionarioId.toIntOrNull() ?: 1,
    titulo = titulo,
    enunciado = STORED_QUESTION_PREFIX + json.encodeToString(StoredQuestion(titulo = titulo, enunciado = enunciado, opciones = opciones)),
    orden = orden,
    version = 1,
)

private fun RafagaPreguntaRow.toDto() = RafagaPreguntaDto(
    id = id?.toString().orEmpty(),
    rafagaId = rafagaId.toString(),
    enunciado = enunciado,
    orden = orden,
    createdAt = "",
    updatedAt = "",
    deletedAt = null,
)

private fun RafagaPreguntaDto.toRow(parentRafagaId: String) = RafagaPreguntaRow(
    id = id.toIntOrNull(),
    rafagaId = parentRafagaId.toIntOrNull() ?: rafagaId.toIntOrNull() ?: 1,
    enunciado = enunciado,
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
        "text" -> "texto"
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
        require(recurso.tipoRecurso in setOf("imagen", "audio", "video", "documento", "texto")) {
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
