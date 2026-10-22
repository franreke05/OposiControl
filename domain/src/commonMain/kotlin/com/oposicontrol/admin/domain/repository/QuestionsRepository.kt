package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.QuestionDetail
import com.oposicontrol.admin.domain.model.QuestionDraft
import com.oposicontrol.admin.domain.model.QuestionExamLink
import com.oposicontrol.admin.domain.model.QuestionListQuery
import com.oposicontrol.admin.domain.model.QuestionPage
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionTag

/**
 * Qué preguntas quiere el banco: activas, inactivas o las dos.
 *
 * Existe porque el servidor filtraba `activa = true` incondicionalmente y el
 * filtro «Inactivas» de la pantalla no podía devolver nada nunca — filtraba un
 * conjunto del que las inactivas ya se habían ido. Y como archivar sí funciona,
 * una pregunta desactivada desaparecía del banco sin forma de volver a
 * encontrarla: reversible en la base, irreversible en la interfaz.
 *
 * [wireValue] es el valor exacto que entiende `GET /preguntas?activa=`.
 */
enum class QuestionActiveFilter(val wireValue: String) {
    ACTIVE("true"),
    INACTIVE("false"),
    ALL("todas"),
}

/**
 * Los TRES tipos de recurso que se pueden CREAR: imagen, audio y vídeo, y siempre
 * desde un archivo que se sube a Storage.
 *
 * Decisión de producto (2026-09-25): un recurso de pregunta es material que se ve
 * o se oye. PDF, texto y enlace ya no se pueden añadir. La base sigue admitiendo
 * esos valores en su `CHECK` y los recursos antiguos de esos tipos se siguen
 * LEYENDO —[com.oposicontrol.admin.domain.model.QuestionResourceType] los
 * conserva—, pero ninguna pantalla los crea y el servidor rechaza crearlos.
 *
 * Existe porque [com.oposicontrol.admin.domain.model.QuestionResourceType] es un tipo
 * de LECTURA y no sirve para escribir: colapsa `pdf`, `enlace` y lo desconocido en
 * un único `DOCUMENT`, que es lo correcto para decidir cómo se abre un recurso ya
 * guardado y es exactamente lo que no se puede mandar al crear uno —el servidor
 * necesita la palabra literal—.
 *
 * [wireValue] es el valor tal cual viaja, en `AdminRecursoDto.tipoRecurso` y en la
 * parte `tipoRecurso` del multipart.
 */
enum class QuestionResourceKind(val wireValue: String) {
    IMAGEN("imagen"),
    AUDIO("audio"),
    VIDEO("video"),
}

/**
 * Lo que describe un recurso nuevo, sin el archivo.
 *
 * [position] `null` = al final; el servidor escribe 1 si no hay nada que ordenar.
 */
data class QuestionResourceDraft(
    val kind: QuestionResourceKind,
    val title: String,
    val description: String = "",
    val position: Int? = null,
)

/**
 * El archivo de un recurso, ya leído.
 *
 * Los BYTES y no una ruta: quien sabe leer un fichero del disco es la plataforma
 * que abrió el selector, no el adapter de red. Así este contrato no arrastra
 * `PlatformFileReader` —ni, por debajo, un contexto de Android— hasta el
 * repositorio, y el multipart se puede probar con un `ByteArray` a pelo.
 *
 * [mimeType] es el que declara la parte multipart. Lo VALIDA el servidor contra su
 * propio mapa por tipo, y de ahí sale la extensión del objeto en Storage: un MIME
 * que no case es un `QUESTION_RESOURCE_TYPE_INVALID` suyo, no un acierto de aquí.
 *
 * Es `class` y no `data class` a propósito: con un `ByteArray` dentro, el `equals`
 * generado compararía la REFERENCIA del array y dos ficheros idénticos saldrían
 * distintos. Mejor no tener esa igualdad que tener una que miente.
 */
class QuestionResourceFile(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * Banco global de preguntas (pantalla Preguntas, JVM/Desktop). Deliberadamente
 * separado de [ContentRepository]: esta pantalla no comparte contrato con
 * Exámenes, así que ni [ContentRepository] ni [com.oposicontrol.admin.domain.model.ExamQuestion]
 * crecen por sus necesidades.
 */
interface QuestionsRepository {
    /**
     * Una página del banco (`GET /preguntas?q=&tipo=&activa=&examenId=&orden=&limit=&offset=`),
     * con el total exacto de la consulta (KQ0-4).
     *
     * Sustituye a `listQuestions()`, `listQuestions(filter)` y `listQuestionsForExam`.
     * Aquéllas descargaban el banco entero —o la lista completa de un examen— y la
     * pantalla filtraba y paginaba lo cargado; y la variante por estado tenía una
     * implementación por defecto que respondía un fallo a quien no la sobrescribía.
     * Ahora es un único método abstracto: ningún implementador puede olvidarse de un
     * filtro, porque todos viajan en [QuestionListQuery].
     */
    suspend fun listQuestions(query: QuestionListQuery): AppResult<QuestionPage>

    /** Detalle completo — opciones, solución, recursos (`GET /preguntas/{id}`). */
    suspend fun getQuestionDetail(questionId: String): AppResult<QuestionDetail>

    /**
     * Alta de pregunta nueva (`POST /preguntas`, KQ2-4).
     *
     * [questionId] lo genera la PANTALLA una sola vez por alta y lo repite en cada
     * reintento: es la clave de idempotencia de `admin_create_question`. Un reintento
     * tras un timeout con el mismo id no crea otra pregunta; con un id nuevo, sí.
     *
     * La pregunta NO tiene especialidad (M8): pertenece al banco y la especialidad es del
     * examen. El examen inicial es opcional ([examId]) y, si viene, se vincula en la misma
     * transacción; sólo se exige que su TIPO sea compatible.
     */
    suspend fun createQuestion(
        questionId: String,
        draft: QuestionDraft,
        examId: String? = null,
    ): AppResult<QuestionDetail>

    /**
     * Actualiza una pregunta existente (`PATCH /preguntas/{id}`, KQ2-2): sólo las
     * claves presentes viajan, y el servidor escribe en una transacción.
     *
     * [optionsChanged] importa: si el admin no tocó las opciones, no se envían, y el
     * servidor conserva opciones y explicación tal cual. El PATCH ya NO vincula (D-03):
     * para eso están [linkQuestionToExam] y [unlinkQuestionFromExam].
     */
    suspend fun updateQuestion(
        questionId: String,
        draft: QuestionDraft,
        optionsChanged: Boolean,
    ): AppResult<QuestionDetail>

    /** Archiva la pregunta (`DELETE /preguntas/{id}` → `activa=false` en backend, no borrado físico). */
    suspend fun archiveQuestion(questionId: String): AppResult<Unit>

    /** Los exámenes REALES en los que está la pregunta (`GET /preguntas/{id}/examenes`, K3-4). */
    suspend fun listQuestionExams(questionId: String): AppResult<List<QuestionExamLink>>

    /**
     * Vincula la pregunta a un examen (`POST /preguntas/{id}/examenes`, KQ3-1) y
     * devuelve la lista completa ya releída. [position] `null` = al final.
     */
    suspend fun linkQuestionToExam(questionId: String, examId: String, position: Int?): AppResult<List<QuestionExamLink>>

    /** Quita la pregunta de un examen sin tocarla en el banco (`DELETE /preguntas/{id}/examenes/{examId}`). */
    suspend fun unlinkQuestionFromExam(questionId: String, examId: String): AppResult<List<QuestionExamLink>>

    /** Las etiquetas (temas) asignadas a la pregunta (`GET /preguntas/{id}/etiquetas`). */
    suspend fun getQuestionTags(questionId: String): AppResult<List<QuestionTag>>

    /**
     * Fija el CONJUNTO de etiquetas de la pregunta (`PUT /preguntas/{id}/etiquetas`):
     * el servidor quita las que faltan, añade las nuevas y responde la lista releída.
     * `emptyList()` las quita todas.
     */
    suspend fun setQuestionTags(questionId: String, tagIds: List<String>): AppResult<List<QuestionTag>>

    // -----------------------------------------------------------------
    // Recursos de la pregunta (KQ4) — `/preguntas/{id}/recursos…`
    //
    // Cinco métodos para cinco rutas, sin componer: cada uno hace UNA petición y
    // devuelve lo que responde el servidor. La lista que se pinta se relee con
    // [listQuestionResources] después de cada escritura, porque es la única que
    // trae la `urlAcceso` firmada y la posición que la base acabó asignando.
    //
    // **La regla «sólo INMERSIVA admite recursos» no se reimplementa aquí.** La
    // impone el servidor (422 `QUESTION_TYPE_REJECTS_RESOURCES`, o 404 si la
    // pregunta no existe) y su mensaje es el que se enseña. La pantalla puede
    // ocultar el control para otros tipos, que es distinto de inventarse el
    // rechazo.
    // -----------------------------------------------------------------

    /** Los recursos vivos de la pregunta, ordenados (`GET /preguntas/{id}/recursos`). */
    suspend fun listQuestionResources(questionId: String): AppResult<List<QuestionResource>>


    /**
     * Alta CON ARCHIVO (`POST /preguntas/{id}/recursos/archivo`, multipart).
     *
     * [resourceId] lo genera la PANTALLA una vez y lo repite en cada reintento: es
     * la clave de idempotencia del servidor, que si ya encuentra esa fila devuelve
     * la que hay sin volver a subir el objeto. Un reintento tras un timeout de 40 MB
     * no duplica ni el fichero en Storage ni la fila.
     */
    suspend fun uploadQuestionResourceFile(
        questionId: String,
        resourceId: String,
        draft: QuestionResourceDraft,
        file: QuestionResourceFile,
    ): AppResult<QuestionResource>

    /**
     * Edita título, descripción y posición
     * (`PATCH /preguntas/{id}/recursos/{recursoId}`).
     *
     * No cambia el TIPO ni el archivo: el servidor no los escribe por esta ruta, y
     * prometerlo desde aquí sería prometer algo que no pasa.
     */
    suspend fun updateQuestionResource(
        questionId: String,
        resourceId: String,
        draft: QuestionResourceDraft,
    ): AppResult<QuestionResource>

    /**
     * Archiva el recurso (`DELETE /preguntas/{id}/recursos/{recursoId}` → `activo=false`).
     *
     * Como el archivado de la pregunta: la fila se queda, deja de listarse, y hoy
     * NO hay forma de desarchivarla —el PATCH de contenido no toca `activo` a
     * propósito—. Por eso la pantalla lo confirma antes.
     */
    suspend fun deleteQuestionResource(questionId: String, resourceId: String): AppResult<QuestionResource>
}
