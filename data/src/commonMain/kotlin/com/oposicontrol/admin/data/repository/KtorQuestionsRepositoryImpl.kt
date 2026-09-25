package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.QuestionDetail
import com.oposicontrol.admin.domain.model.QuestionDraft
import com.oposicontrol.admin.domain.model.QuestionExamLink
import com.oposicontrol.admin.domain.model.QuestionListItem
import com.oposicontrol.admin.domain.model.QuestionListQuery
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionPage
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.repository.QuestionResourceDraft
import com.oposicontrol.admin.domain.repository.QuestionResourceFile
import com.oposicontrol.admin.domain.repository.QuestionsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * Adapter Ktor propio del banco global de preguntas (pantalla Preguntas,
 * JVM/Desktop).
 *
 * A propósito NO reutiliza los DTOs/mappers privados de
 * [KtorAdminV1ContentRepositoryImpl], para no acoplar este flujo al que usa
 * Exámenes. Este adapter declara sus propios DTOs mínimos con el mismo contrato
 * real (`/api/v1/admin/preguntas`), y su propio mapeo hacia
 * [QuestionListItem]/[QuestionDetail] — tipos que tampoco comparte con
 * `ExamQuestion`. Las reglas que comparten (tipos de recurso, defaults de
 * dificultad) se fijan con un test a cada lado.
 */
class KtorQuestionsRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : QuestionsRepository {

    /**
     * Una página del banco, resuelta en el servidor (KQ0-4, QN-07).
     *
     * Sólo viajan los parámetros con valor: el cliente Ktor los codifica, así que
     * una búsqueda con `&`, `#` o espacios no puede partir la URL. `activa` y `orden`
     * van SIEMPRE, explícitos, aunque coincidan con el default del servidor: la
     * petición dice lo que pide en vez de apoyarse en el otro lado. La búsqueda se
     * recorta a 100 caracteres, el máximo que admite el servidor (más sería un 400).
     */
    override suspend fun listQuestions(query: QuestionListQuery): AppResult<QuestionPage> = adapter.safeCall {
        val endpoint = BackendRoutes.ADMIN_V1_PREGUNTAS
        val response = client.get(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            query.search.trim().take(QUESTION_SEARCH_MAX_LENGTH).takeIf { it.isNotEmpty() }?.let { parameter("q", it) }
            query.type?.let { parameter("tipo", it.wireValue) }
            parameter("activa", query.active.wireValue)
            query.examId?.takeIf { it.isNotBlank() }?.let { parameter("examenId", it) }
            parameter("orden", query.sort.wireValue)
            parameter("limit", query.limit)
            parameter("offset", query.offset)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        val page = response.body<QPreguntasPageResponse>()
        QuestionPage(
            items = page.data.map { it.toListItem() },
            total = page.total,
            offset = page.offset,
            limit = page.limit,
        )
    }

    override suspend fun getQuestionDetail(questionId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1Pregunta(questionId)
        val response = client.get(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QPreguntaResponse>().data.toDetail()
    }

    /**
     * Alta (KQ2-4): el `id` es el que generó la pantalla y se REPITE en cada
     * reintento, así que un timeout no puede acabar en dos preguntas. Sin lectura
     * previa del examen: la pregunta no tiene especialidad (M8), y el examen
     * inicial —si lo hay— viaja en el mismo cuerpo y se vincula en la misma
     * transacción del servidor.
     */
    override suspend fun createQuestion(
        questionId: String,
        draft: QuestionDraft,
        examId: String?,
    ) = adapter.safeCall {
        val endpoint = BackendRoutes.ADMIN_V1_PREGUNTAS
        val response = client.post(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(
                QPreguntaCreateDto(
                    id = questionId,
                    tipoPregunta = draft.type.wireValue,
                    titulo = draft.statement,
                    fuenteTexto = draft.source,
                    dificultad = draft.difficulty,
                    activa = draft.active,
                    razonamiento = draft.explanation,
                    opciones = draft.options.toDto(),
                    examenId = examId?.takeIf { it.isNotBlank() },
                ),
            )
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QPreguntaResponse>().data.toDetail()
    }

    /**
     * Edición (KQ2-2): sólo las claves del cuerpo viajan, y el servidor escribe
     * exactamente ésas. Por eso los campos que SIEMPRE se editan desde esta pantalla
     * van marcados `@EncodeDefault`: con el `Json` del cliente sin `encodeDefaults`,
     * un `activa = true` o una explicación vaciada se omitirían y el servidor los
     * leería como «no los toques».
     *
     * No lleva `examenId`/`posicion` (D-03: el PATCH no vincula), ni `id`, ni
     * `especialidadId`, ni `recursos`: no existen en el tipo, así que ningún
     * guardado puede volver a mandarlos.
     */
    override suspend fun updateQuestion(
        questionId: String,
        draft: QuestionDraft,
        optionsChanged: Boolean,
    ) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1Pregunta(questionId)
        val response = client.patch(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(
                QPreguntaPatchDto(
                    tipoPregunta = draft.type.wireValue,
                    titulo = draft.statement,
                    fuenteTexto = draft.source,
                    dificultad = draft.difficulty,
                    activa = draft.active,
                    razonamiento = draft.explanation,
                    opciones = if (optionsChanged) draft.options.toDto() else emptyList(),
                ),
            )
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QPreguntaResponse>().data.toDetail()
    }

    override suspend fun archiveQuestion(questionId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1Pregunta(questionId)
        val response = client.delete(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
    }

    // -----------------------------------------------------------------
    // Vínculos con exámenes (KQ3-2): la única puerta es /preguntas/{id}/examenes
    // -----------------------------------------------------------------

    override suspend fun listQuestionExams(questionId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1PreguntaExamenes(questionId)
        val response = client.get(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QPreguntaExamenesResponse>().data.map { it.toLink() }
    }

    override suspend fun linkQuestionToExam(questionId: String, examId: String, position: Int?) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1PreguntaExamenes(questionId)
        val response = client.post(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(QVincularExamenDto(examenId = examId, posicion = position))
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QPreguntaExamenesResponse>().data.map { it.toLink() }
    }

    override suspend fun unlinkQuestionFromExam(questionId: String, examId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1PreguntaExamen(questionId, examId)
        val response = client.delete(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QPreguntaExamenesResponse>().data.map { it.toLink() }
    }

    // -----------------------------------------------------------------
    // Recursos de la pregunta (KQ4): `/preguntas/{id}/recursos…`
    //
    // Ninguna de las cinco comprueba «sólo inmersiva» ni los campos por tipo. No es
    // un olvido: el servidor impone la regla contra el tipo GUARDADO de la pregunta
    // —que es el único que no puede estar desactualizado— y redacta el mensaje que
    // se lee. `requireSuccess` deja ese mensaje dentro del `IllegalStateException`,
    // y `NetworkResultAdapter` lo saca al `AppError.Validation` que llega a la
    // pantalla. Un 422 del backend no puede acabar en un éxito silencioso.
    // -----------------------------------------------------------------

    override suspend fun listQuestionResources(questionId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1PreguntaRecursos(questionId)
        val response = client.get(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QRecursosResponse>().data.map { it.toResource() }.sortedBy { it.sortOrder }
    }


    /**
     * Subida del archivo, con el MISMO patrón multipart que `uploadExamCover` y que
     * la importación de PDF: `MultiPartFormDataContent(formData { … })`, el fichero
     * como parte `file` con su `Content-Disposition` y su `Content-Type` propios, y
     * `expectSuccess = false` + [requireSuccess] para que el cuerpo del error llegue
     * entero al adapter.
     *
     * Las partes de texto van ANTES que el fichero a propósito: el servidor lee el
     * multipart en orden y `tipoRecurso` es lo que decide si el MIME vale, así que
     * mandarlo después de 40 MB sería pedirle que espere a todo el cuerpo para poder
     * rechazarlo.
     *
     * El presupuesto de tiempo se amplía como en la portada, y por el mismo motivo:
     * con el de 30 s del cliente, una subida grande por una línea lenta se cancela
     * desde este lado mientras el servidor sigue escribiendo en Storage.
     */
    override suspend fun uploadQuestionResourceFile(
        questionId: String,
        resourceId: String,
        draft: QuestionResourceDraft,
        file: QuestionResourceFile,
    ) = adapter.safeCall {
        val endpoint = adminV1PreguntaRecursosArchivo(questionId)
        val response = client.post(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
            timeout {
                requestTimeoutMillis = QUESTION_RESOURCE_UPLOAD_TIMEOUT_MILLIS
                socketTimeoutMillis = QUESTION_RESOURCE_UPLOAD_TIMEOUT_MILLIS
            }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("tipoRecurso", draft.kind.wireValue)
                        append("titulo", draft.title)
                        append("descripcion", draft.description)
                        draft.position?.let { append("posicion", it.toString()) }
                        // La clave de idempotencia. Si el reintento la pierde, el
                        // servidor trata la segunda llamada como un primer intento y
                        // el mismo archivo acaba subido dos veces.
                        append("recursoId", resourceId)
                        append(
                            key = "file",
                            value = file.bytes,
                            headers = Headers.build {
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "form-data; name=\"file\"; filename=\"${file.fileName.sanitizeQuestionResourceFilename()}\"",
                                )
                                append(HttpHeaders.ContentType, file.mimeType)
                            },
                        )
                    },
                ),
            )
        }
        response.requireSuccess(endpoint)
        response.body<QRecursoResponse>().data.toResource()
    }

    override suspend fun updateQuestionResource(
        questionId: String,
        resourceId: String,
        draft: QuestionResourceDraft,
    ) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1PreguntaRecurso(questionId, resourceId)
        val response = client.patch(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(draft.toCreateDto())
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QRecursoResponse>().data.toResource()
    }

    override suspend fun deleteQuestionResource(questionId: String, resourceId: String) = adapter.safeCall {
        val endpoint = BackendRoutes.adminV1PreguntaRecurso(questionId, resourceId)
        val response = client.delete(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<QRecursoResponse>().data.toResource()
    }

    private fun url(path: String): String = BackendRoutes.url(appConfig.baseUrl, path)

    private suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) {
            error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
        }
    }
}

/** `AdminPreguntasPageResponse` del servidor: la página y el total exacto de la consulta (KQ0-3). */
@Serializable
private data class QPreguntasPageResponse(
    val data: List<QPreguntaDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
private data class QPreguntaResponse(val data: QPreguntaDto)

@Serializable
private data class QPreguntaDto(
    val id: String = "",
    val codigoPregunta: String = "",
    val tipoPregunta: String = "rafaga",
    val titulo: String = "",
    val fuenteTexto: String = "",
    /**
     * Mismo default que el servidor, `""` (KQ0-2): el servidor omite del JSON un
     * valor igual a su default, así que una dificultad NULL llega como clave ausente
     * y con `"media"` aquí se leía —y se reenviaba al guardar— un valor inventado.
     */
    val dificultad: String = "",
    val activa: Boolean = true,
    val razonamiento: String = "",
    val opciones: List<QOpcionDto> = emptyList(),
    val recursos: List<QRecursoDto> = emptyList(),
    /**
     * Asociación al examen. **Sólo llegan con contenido desde
     * `GET /examenes/{id}/preguntas`**; el banco global no las trae porque no
     * tiene un examen del que hablar. Un `null` significa «esta fila no viene de
     * un examen», no «posición desconocida».
     */
    val examenId: String? = null,
    val posicion: Int? = null,
)

@Serializable
private data class QOpcionDto(
    val id: String = "",
    val letra: String = "",
    val texto: String = "",
    val posicion: Int = 0,
    val esCorrecta: Boolean = false,
)

@Serializable
private data class QRecursoDto(
    val id: String = "",
    val preguntaId: String = "",
    val tipoRecurso: String = "",
    val titulo: String = "",
    /**
     * El backend la guarda y la devuelve desde siempre; este DTO no la leía, así
     * que el pie que se escribía en el formulario no se veía en ninguna parte.
     */
    val descripcion: String = "",
    val urlExterna: String? = null,
    val contenidoTexto: String? = null,
    val posicion: Int = 0,
    /** KQ4: dónde vive el archivo, con qué MIME y la URL FIRMADA (caduca) para abrirlo. */
    val rutaStorage: String? = null,
    val mimeType: String? = null,
    val urlAcceso: String? = null,
)

/**
 * Cuerpo del ALTA (KQ2-4). El `id` es la clave de idempotencia y viaja siempre.
 *
 * Sin `codigoPregunta` (KQ1: lo genera la base y es inmutable) y sin `recursos`
 * (KQ0-8): no existen en el tipo, así que ningún guardado puede volver a mandarlos.
 */
@Serializable
private data class QPreguntaCreateDto(
    val id: String,
    val tipoPregunta: String,
    val titulo: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val fuenteTexto: String = "",
    /** `""` = sin definir; el servidor no la escribe (KQ0-2). Nunca un `"media"` inventado. */
    val dificultad: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val activa: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val razonamiento: String = "",
    val opciones: List<QOpcionDto> = emptyList(),
    val examenId: String? = null,
)

/**
 * Cuerpo de la EDICIÓN (KQ2-2): cada clave presente se escribe; cada ausente se
 * conserva. `opciones` vacía = «no las toques»; `dificultad` vacía = no viaja
 * (el servidor rechazaría `""`: sólo fácil/media/difícil o NULL).
 */
@Serializable
private data class QPreguntaPatchDto(
    val tipoPregunta: String,
    val titulo: String,
    /**
     * SIEMPRE en el cable. Esta pantalla es la dueña de la fuente, y vaciarla es una
     * edición: con el `Json` del cliente sin `encodeDefaults`, un `""` se omitía y el
     * servidor lo leía como «no la toques» (QN-17), así que borrar la fuente no se
     * guardaba nunca.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val fuenteTexto: String = "",
    val dificultad: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val activa: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val razonamiento: String = "",
    val opciones: List<QOpcionDto> = emptyList(),
)

/** `POST /preguntas/{id}/examenes`: `posicion` ausente = al final. */
@Serializable
private data class QVincularExamenDto(val examenId: String, val posicion: Int? = null)

@Serializable
private data class QPreguntaExamenesResponse(val data: List<QPreguntaExamenVinculoDto> = emptyList())

/**
 * Un vínculo tal y como lo responde el servidor (K3-4). Los defaults casan con los
 * del servidor, que omite del JSON los valores iguales a su default.
 */
@Serializable
private data class QPreguntaExamenVinculoDto(
    val examenId: String = "",
    val codigoExamen: String = "",
    val titulo: String = "",
    val tipoExamen: String = "",
    val estado: String = "borrador",
    val posicion: Int = 0,
    val anulada: Boolean = false,
    val motivoAnulacion: String? = null,
)

private fun QPreguntaExamenVinculoDto.toLink() = QuestionExamLink(
    examId = examenId,
    examCode = codigoExamen,
    examTitle = titulo,
    position = posicion,
    examType = tipoExamen,
    status = estado,
    annulled = anulada,
    annulmentReason = motivoAnulacion,
)

private fun List<QuestionOption>.toDto(): List<QOpcionDto> = mapIndexed { index, option ->
    QOpcionDto(
        letra = option.label,
        texto = option.content,
        posicion = index + 1,
        esCorrecta = option.isCorrect,
    )
}

private fun QPreguntaDto.toListItem() = QuestionListItem(
    id = id,
    code = codigoPregunta,
    type = QuestionType.fromExternal(tipoPregunta),
    statement = titulo,
    active = activa,
    // Se propagan tal cual, null incluido: sólo vienen con valor en la consulta de
    // un examen (`QuestionListQuery.examId`), y el panel de vínculos distingue.
    examId = examenId,
    position = posicion,
)

private fun QPreguntaDto.toDetail() = QuestionDetail(
    id = id,
    code = codigoPregunta,
    type = QuestionType.fromExternal(tipoPregunta),
    statement = titulo,
    options = opciones.mapIndexed { index, option ->
        QuestionOption(
            id = option.id,
            questionId = id,
            label = option.letra,
            content = option.texto,
            isCorrect = option.esCorrecta,
            sortOrder = if (option.posicion > 0) option.posicion - 1 else index,
        )
    }.sortedBy { it.sortOrder },
    explanation = razonamiento,
    source = fuenteTexto,
    active = activa,
    difficulty = dificultad,
    // Mismo mapeo que las rutas de recursos (`toResource`): si el detalle y la
    // lista divergieran, un recurso recién subido cambiaría de aspecto al recargar
    // la pregunta.
    resources = recursos.map { it.toResource() }.sortedBy { it.sortOrder },
    // El contrato V1 de preguntas no trae fecha de creación/actualización —
    // se deja null a propósito, nunca "ahora mismo" como si fuera real.
    createdAt = null,
    updatedAt = null,
)

/**
 * `recursos_pregunta.tipo_recurso` → tipo de dominio (KQ0-6, QN-01). Mismo mapeo que
 * el repositorio de Exámenes: el vocabulario vivo del CHECK es `imagen, audio,
 * video, texto, pdf, enlace`. Antes `imagen`, `pdf` y `enlace` caían al `else` y se
 * pintaban como IMAGEN —acertando sólo con `imagen`, por casualidad—. Un PDF, un
 * enlace o un tipo desconocido se abren por URL (DOCUMENT): abrirlos como imagen es
 * lo único que no se puede hacer.
 */
private fun String.toQuestionResourceType() = when (trim().lowercase()) {
    "imagen" -> QuestionResourceType.IMAGE
    "audio" -> QuestionResourceType.AUDIO
    "video" -> QuestionResourceType.VIDEO
    "texto" -> QuestionResourceType.TEXT
    else -> QuestionResourceType.DOCUMENT
}

/** El máximo que el servidor admite en `?q=` (KQ0-3); más sería un 400. */
private const val QUESTION_SEARCH_MAX_LENGTH = 100

// ---------------------------------------------------------------------------
// Recursos (KQ4): escritura
// ---------------------------------------------------------------------------

/**
 * `POST /preguntas/{id}/recursos/archivo`.
 *
 * Se compone aquí y no en `BackendRoutes` porque es la única ruta de recursos que
 * faltaba allí; cuando se añada, esta función desaparece y la llamada no cambia.
 */
private fun adminV1PreguntaRecursosArchivo(preguntaId: String): String =
    "${BackendRoutes.adminV1PreguntaRecursos(preguntaId)}/archivo"

@Serializable
private data class QRecursosResponse(val data: List<QRecursoDto> = emptyList())

@Serializable
private data class QRecursoResponse(val data: QRecursoDto)

/**
 * Cuerpo de escritura de un recurso (`AdminRecursoDto` del servidor, sólo lo que
 * este cliente puede decidir).
 *
 * Sin `id`, sin `preguntaId` —van en la ruta— y sin los tres campos de sólo
 * lectura (`rutaStorage`, `mimeType`, `urlAcceso`): no existen en el tipo, así que
 * ningún guardado puede mandarlos. Ya no lleva `urlExterna` ni `contenidoTexto`:
 * los recursos que se crean son archivos, y esos dos campos son de `enlace` y
 * `texto`.
 *
 * `titulo`, `descripcion` y `posicion` van marcados `@EncodeDefault`: con el `Json`
 * del cliente sin `encodeDefaults`, vaciar una descripción no viajaría y el PATCH
 * la conservaría — el mismo defecto que ya costó una corrección en `fuenteTexto`.
 */
@Serializable
private data class QRecursoWriteDto(
    val tipoRecurso: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val titulo: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val descripcion: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val posicion: Int = 0,
)

/** `posicion = 0` es «no la fijo»: el servidor escribe 1, que es lo que su `CHECK > 0` pide. */
private fun QuestionResourceDraft.toCreateDto() = QRecursoWriteDto(
    tipoRecurso = kind.wireValue,
    titulo = title.trim(),
    descripcion = description.trim(),
    posicion = position?.takeIf { it > 0 } ?: 0,
)

private fun QRecursoDto.toResource() = QuestionResource(
    id = id,
    questionId = preguntaId,
    type = tipoRecurso.toQuestionResourceType(),
    title = titulo,
    // Vacía significa «sin pie», no «no lo sé»: se normaliza a null para que la
    // pantalla no tenga que distinguir entre cadena vacía y ausencia.
    description = descripcion.takeIf { it.isNotBlank() },
    textBody = contenidoTexto,
    storagePath = rutaStorage,
    // KQ4: la URL firmada del archivo, o el enlace externo. Nunca una ruta local.
    publicUrl = urlAcceso ?: urlExterna,
    mimeType = mimeType,
    sortOrder = (posicion - 1).coerceAtLeast(0),
)

/**
 * Las comillas rompen el `Content-Disposition` de la parte, así que se quitan; el
 * nombre no decide nada más —la extensión del objeto en Storage la elige el
 * servidor a partir del MIME—, y por eso un nombre vacío puede caer en uno neutro.
 */
private fun String.sanitizeQuestionResourceFilename(): String =
    replace("\"", "").trim().ifBlank { "recurso.bin" }


/**
 * Presupuesto de tiempo de la subida: 50 MB no caben en los 30 s por defecto del
 * cliente. El de socket se amplía igual porque entre paquetes de una línea lenta
 * puede haber más de 30 s sin actividad.
 */
private const val QUESTION_RESOURCE_UPLOAD_TIMEOUT_MILLIS: Long = 180_000
