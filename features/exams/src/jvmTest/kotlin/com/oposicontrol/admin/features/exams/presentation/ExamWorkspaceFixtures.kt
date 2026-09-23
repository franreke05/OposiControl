package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import kotlinx.datetime.Instant

/**
 * Estados de PRESENTACIÓN del workspace de Exámenes, en un solo sitio.
 *
 * Los usan los bancos de pruebas visuales y los tests de UI de las cinco pestañas
 * que no son Información básica —Configuración, Preguntas, Recursos, Acceso y
 * Publicación—. Información básica conserva los suyos
 * (`ExamBasicInfoVisualHarness`): está cerrada y sus capturas no se tocan.
 *
 * **Qué garantizan.** Cada estado es uno que el ViewModel PUEDE producir: el
 * editor sembrado desde el examen abierto como lo deja `selectExam`, el examen
 * reemplazado dentro de `catalogExams` cuando se modifica (la cabecera y
 * Publicación resuelven su identidad ahí), y recuentos coherentes con las listas
 * de preguntas —`total_preguntas` y la lista no pueden discrepar en un estado
 * limpio—. Una captura con datos imposibles no comprueba la pantalla, comprueba
 * el fixture.
 *
 * **Qué NO garantizan.** Nada de aquí toca un repositorio, una ruta ni un
 * contrato. Que un estado exista aquí no dice que el backend lo sirva así.
 *
 * **Tipo de pregunta (B2a).** `ExamQuestion.type` ya existe y estos estados lo
 * usan: los tres tipos canónicos y una pregunta con el tipo SIN IDENTIFICAR
 * (`null`), que es lo que llega cuando el servidor manda un valor que no
 * reconocemos. Lo que ningún fixture puede reproducir es el campo AUSENTE: el DTO
 * lo rellena con `"rafaga"` y llega indistinguible de una Ráfaga real (ver el
 * KDoc de `ExamQuestion.type`).
 *
 * Sólo en jvmTest: las fuentes de test no se empaquetan, así que estos exámenes
 * de mentira no pueden llegar a la aplicación por construcción.
 */
internal object ExamWorkspaceFixtures {

    val NOW: Instant = Instant.parse("2026-04-15T09:00:00Z")

    /** Fecha de publicación distinta de la de creación: así se ve cuál es cuál. */
    val PUBLISHED_AT: Instant = Instant.parse("2026-04-02T08:30:00Z")

    // -----------------------------------------------------------------------
    // Catálogos
    // -----------------------------------------------------------------------

    fun specialty(id: String, name: String, order: Int) = Specialty(
        id = id,
        name = name,
        description = "",
        isActive = true,
        sortOrder = order,
        linkedExams = 4,
        createdAt = NOW,
        updatedAt = NOW,
    )

    val SPECIALTIES: List<Specialty> = listOf(
        specialty("esp-tcae", "TCAE", 0),
        specialty("esp-enfermeria", "Enfermería", 1),
        specialty("esp-celador", "Celador", 2),
    )

    val COMMUNITIES: List<Community> = listOf(
        Community("com-an", "Andalucía", "AN", "andalucia", true, 0, NOW, NOW),
        Community("com-md", "Madrid", "MD", "madrid", true, 1, NOW, NOW),
        Community("com-ct", "Cataluña", "CT", "cataluna", true, 2, NOW, NOW),
    )

    /** Texto de limitación real de una especialidad que sólo tiene material de ráfaga. */
    const val SOLO_RAFAGA_LIMITATION: String =
        "Esta especialidad todavía no tiene material razonado ni inmersivo cargado."

    fun capabilities(
        rafagas: Boolean = true,
        razonados: Boolean = true,
        inmersivos: Boolean = true,
        configured: Boolean = true,
        limitation: String? = null,
    ) = SpecialtyCapabilities(
        permiteRafagas = rafagas,
        permiteRazonados = razonados,
        permiteInmersivos = inmersivos,
        permiteDemos = false,
        permiteOposimarket = false,
        specialtyId = "esp-tcae",
        limitationMessage = limitation,
        configured = configured,
    )

    fun exam(
        id: String,
        code: String,
        title: String,
        specialtyId: String = "esp-tcae",
        specialtyName: String = "TCAE",
        type: String = "rafaga",
        access: String = "gratuito",
        price: Int = 0,
        status: ExamStatus = ExamStatus.DRAFT,
        questionCount: Int = 5,
        year: Int? = 2026,
        duration: Int? = 45,
        official: Boolean = false,
        communityId: String? = null,
    ) = Exam(
        id = id,
        code = code,
        specialtyId = specialtyId,
        specialtyName = specialtyName,
        communityId = communityId,
        title = title,
        description = "Simulacro completo con el temario de la última convocatoria.",
        typeExam = type,
        typeAccess = access,
        priceTickets = price,
        status = status,
        publishFrom = null,
        publishedAt = if (status == ExamStatus.PUBLISHED) PUBLISHED_AT else null,
        sortOrder = 0,
        questionCount = questionCount,
        durationMinutes = duration,
        year = year,
        isOfficial = official,
        coverUrl = null,
        coverStoragePath = null,
        coverMimeType = null,
        createdAt = NOW,
        updatedAt = NOW,
    )

    /**
     * Catorce exámenes: dos páginas de doce, tres especialidades, los tres tipos y
     * los tres estados. Los que se abren llevan el `questionCount` que de verdad
     * tienen sus listas.
     */
    val CATALOGO: List<Exam> = listOf(
        exam("ex-1", "EX-2026-0001", "TCAE · Simulacro marzo", status = ExamStatus.PUBLISHED, questionCount = 5),
        exam("ex-2", "EX-2026-0002", "TCAE · Simulacro abril", type = "razonado", questionCount = 5),
        exam("ex-3", "EX-2026-0003", "TCAE · Convocatoria 2024", official = true, year = 2024, status = ExamStatus.PUBLISHED),
        exam("ex-4", "EX-2026-0004", "TCAE · Higiene hospitalaria", type = "inmersivo", access = "premium", price = 1300),
        exam("ex-5", "EX-2026-0005", "TCAE · Repaso exprés", questionCount = 0, duration = 15),
        exam("ex-6", "EX-2026-0006", "Enfermería · Farmacología", specialtyId = "esp-enfermeria", specialtyName = "Enfermería", type = "razonado"),
        exam("ex-7", "EX-2026-0007", "Enfermería · Urgencias", specialtyId = "esp-enfermeria", specialtyName = "Enfermería", status = ExamStatus.PUBLISHED),
        exam("ex-8", "EX-2026-0008", "Enfermería · Convocatoria 2023", specialtyId = "esp-enfermeria", specialtyName = "Enfermería", official = true, year = 2023, status = ExamStatus.ARCHIVED, questionCount = 5),
        exam("ex-9", "EX-2026-0009", "Celador · Movilización de pacientes", specialtyId = "esp-celador", specialtyName = "Celador", type = "inmersivo"),
        exam("ex-10", "EX-2026-0010", "Celador · Normativa básica", specialtyId = "esp-celador", specialtyName = "Celador", status = ExamStatus.PUBLISHED),
        exam("ex-11", "EX-2026-0011", "Celador · Convocatoria 2025", specialtyId = "esp-celador", specialtyName = "Celador", official = true, year = 2025),
        exam("ex-12", "EX-2026-0012", "TCAE · Andalucía 2026", communityId = "com-an", status = ExamStatus.PUBLISHED),
        exam("ex-13", "EX-2026-0013", "TCAE · Madrid 2026", communityId = "com-md"),
        exam("ex-14", "EX-2026-0014", "TCAE · Cataluña 2026", communityId = "com-ct", status = ExamStatus.ARCHIVED),
    )

    // -----------------------------------------------------------------------
    // Preguntas y recursos
    // -----------------------------------------------------------------------

    fun option(questionId: String, label: String, correct: Boolean, index: Int) = QuestionOption(
        id = "$questionId-$label",
        questionId = questionId,
        label = label,
        content = "Opción $label",
        isCorrect = correct,
        sortOrder = index,
    )

    /** Un recurso servido: URL pública para los de fichero, cuerpo para los de texto. */
    fun resource(
        id: String,
        questionId: String,
        type: QuestionResourceType,
        title: String,
        text: String? = null,
        publicUrl: String? = if (text == null) "https://cdn.oposicontrol.dev/recursos/$id.bin" else null,
    ) = QuestionResource(
        id = id,
        questionId = questionId,
        type = type,
        title = title,
        textBody = text,
        storagePath = null,
        publicUrl = publicUrl,
        mimeType = null,
        sortOrder = 0,
    )

    /**
     * [type] es `QuestionType?` y `null` significa **tipo sin identificar**: el
     * servidor mandó un valor que no es ninguno de los tres. No significa «campo
     * ausente», que es indistinguible de Ráfaga por contrato.
     */
    fun question(
        id: String,
        order: Int,
        title: String,
        correct: String = "A",
        resources: List<QuestionResource> = emptyList(),
        examId: String = "ex-1",
        type: QuestionType? = QuestionType.RAFAGA,
    ) = ExamQuestion(
        id = id,
        examId = examId,
        title = title,
        statement = title,
        sortOrder = order,
        type = type,
        options = listOf("A", "B", "C", "D").mapIndexed { index, label ->
            option(id, label, label == correct, index)
        },
        resources = resources,
        createdAt = NOW,
        updatedAt = NOW,
    )

    /**
     * Cinco preguntas, dos de ellas con recursos de cuatro tipos distintos.
     *
     * Los tipos son los tres canónicos más uno SIN IDENTIFICAR (`q-5`): en una
     * instalación real conviven, y la pestaña tiene que enseñar el que no
     * reconoce como lo que es en vez de darlo por Ráfaga.
     */
    val PREGUNTAS_CON_RECURSOS: List<ExamQuestion> = listOf(
        question(
            "q-1",
            0,
            "¿Cuál es la posición correcta para la higiene bucal de un paciente encamado?",
            type = QuestionType.INMERSIVA,
            resources = listOf(
                resource("r-1", "q-1", QuestionResourceType.IMAGE, "Posición de Fowler"),
                resource("r-2", "q-1", QuestionResourceType.DOCUMENT, "Protocolo de higiene"),
            ),
        ),
        question(
            "q-2",
            1,
            "Orden correcto en la retirada del equipo de protección individual",
            correct = "C",
            type = QuestionType.RAZONADA,
        ),
        question(
            "q-3",
            2,
            "Identifica el sonido de auscultación de esta grabación",
            correct = "B",
            type = QuestionType.INMERSIVA,
            resources = listOf(
                resource("r-3", "q-3", QuestionResourceType.AUDIO, "Auscultación pulmonar"),
                resource("r-4", "q-3", QuestionResourceType.TEXT, "Contexto clínico", text = "Paciente de 68 años, disnea de esfuerzo."),
            ),
        ),
        question("q-4", 3, "Temperatura de conservación de las vacunas en el frigorífico", correct = "D"),
        question("q-5", 4, "Actuación ante una caída presenciada en la habitación", correct = "A", type = null),
    )

    val PREGUNTAS_SIN_RECURSOS: List<ExamQuestion> = PREGUNTAS_CON_RECURSOS.map { it.copy(resources = emptyList()) }

    /**
     * La ruta LOCAL que el cliente guarda hoy como URL de un recurso cuya subida
     * falló (P0-4 / EXAM-008). Existe para comprobar que la UI nunca la enseña ni la
     * trata como un fichero servido.
     */
    const val LOCAL_RESOURCE_PATH: String = "C:\\Users\\admin\\Desktop\\auscultacion-pulmonar.mp3"

    /** Las mismas preguntas, con el audio de `q-3` apuntando a una ruta local. */
    val PREGUNTAS_CON_RUTA_LOCAL: List<ExamQuestion> = PREGUNTAS_CON_RECURSOS.map { pregunta ->
        pregunta.copy(
            resources = pregunta.resources.map { recurso ->
                if (recurso.id == "r-3") recurso.copy(publicUrl = LOCAL_RESOURCE_PATH, storagePath = null) else recurso
            },
        )
    }

    // -----------------------------------------------------------------------
    // Base
    // -----------------------------------------------------------------------

    /**
     * Un examen abierto tal y como lo deja `selectExam`: los campos del editor
     * copiados del examen, así que no hay cambios sin guardar.
     *
     * [exam] se REEMPLAZA en el catálogo si no está tal cual: la cabecera y
     * Publicación resuelven el examen abierto en `catalogExams`, y un `copy()`
     * suelto enseñaría el estado del examen original.
     */
    fun estadoBase(
        exam: Exam,
        questions: UiAsync<List<ExamQuestion>>,
        capabilities: SpecialtyCapabilities = capabilities(),
        catalog: List<Exam> = CATALOGO,
        exams: UiAsync<List<Exam>>? = null,
        searchQuery: String = "",
    ): ExamsState {
        val catalogo = if (catalog.any { it.id == exam.id }) {
            catalog.map { if (it.id == exam.id) exam else it }
        } else {
            catalog + exam
        }
        return ExamsState(
            specialties = UiAsync.Success(SPECIALTIES),
            exams = exams ?: UiAsync.Success(catalogo),
            catalogExams = catalogo,
            questions = questions,
            communities = UiAsync.Success(COMMUNITIES),
            specialtyCapabilities = UiAsync.Success(capabilities),
            searchQuery = searchQuery,
            statusFilter = "all",
            selectedExamId = exam.id,
            editorTitle = exam.title,
            editorDescription = exam.description,
            editorCode = exam.code,
            editorSpecialtyId = exam.specialtyId,
            editorCommunityId = exam.communityId,
            editorTypeExam = exam.typeExam,
            editorStatus = exam.status.name.lowercase(),
            editorTypeAccess = exam.typeAccess,
            editorPriceTickets = exam.priceTickets.toString(),
            editorYear = exam.year?.toString().orEmpty(),
            editorDurationMinutes = exam.durationMinutes?.toString().orEmpty(),
            editorIsOfficial = exam.isOfficial,
        )
    }

    // -----------------------------------------------------------------------
    // Ciclo de vida: nuevo · existente · publicado · archivado
    // -----------------------------------------------------------------------

    /**
     * Examen nuevo, todavía sin guardar, exactamente como lo deja `resetExamEditor`:
     * sin especialidad (D15), capacidades en `Idle` y el editor en blanco.
     */
    fun examenNuevo(): ExamsState = ExamsState(
        specialties = UiAsync.Success(SPECIALTIES),
        exams = UiAsync.Success(CATALOGO),
        catalogExams = CATALOGO,
        questions = UiAsync.Empty,
        communities = UiAsync.Success(COMMUNITIES),
        specialtyCapabilities = UiAsync.Idle,
        statusFilter = "all",
        selectedExamId = null,
        editorSpecialtyId = "",
        editorTypeExam = "rafaga",
        editorStatus = "draft",
        editorTypeAccess = "gratuito",
        editorPriceTickets = "0",
    )

    /** Borrador existente (Razonado), con preguntas y sin cambios pendientes. */
    fun borradorConContenido(): ExamsState = estadoBase(CATALOGO[1], UiAsync.Success(PREGUNTAS_CON_RECURSOS))

    /** Examen publicado (Ráfaga gratuito), con preguntas y recursos y sin cambios pendientes. */
    fun publicadoConContenido(): ExamsState = estadoBase(CATALOGO[0], UiAsync.Success(PREGUNTAS_CON_RECURSOS))

    /** Examen archivado: `activo=false` y sin fecha de publicación (archivar la borra). */
    fun archivado(): ExamsState = estadoBase(CATALOGO[7], UiAsync.Success(PREGUNTAS_SIN_RECURSOS))

    /**
     * Borrador con cambios sin guardar: duración y comunidad tocadas. La barra es
     * prominente, «Antes de publicar» enseña «Cambios sin guardar» y Publicación
     * avisa de que se publicaría la versión guardada.
     */
    fun borradorConCambiosSinGuardar(): ExamsState =
        borradorConContenido().copy(editorDurationMinutes = "90", editorCommunityId = "com-an")

    /** Publicar en curso. */
    fun publicando(): ExamsState = borradorConContenido().copy(lifecycleActionState = UiAsync.Loading)

    /** Recién publicado: el examen ya está publicado en el catálogo y el resultado es `Success`. */
    fun recienPublicado(): ExamsState = publicadoConContenido().copy(lifecycleActionState = UiAsync.Success(Unit))

    /** Publicar falló: el examen sigue en borrador y el resultado lleva el error humano. */
    fun errorAlPublicar(): ExamsState = borradorConContenido().copy(
        lifecycleActionState = UiAsync.Error(AppError.Network("timeout")),
    )

    /** Búsqueda sin resultados con un examen abierto: su identidad no depende del filtro. */
    fun catalogoSinResultados(): ExamsState = estadoBase(
        exam = CATALOGO[0],
        questions = UiAsync.Success(PREGUNTAS_CON_RECURSOS),
        exams = UiAsync.Empty,
        searchQuery = "oftalmología",
    )

    // -----------------------------------------------------------------------
    // Configuración: capacidades de la especialidad
    // -----------------------------------------------------------------------

    /** Especialidad que sólo permite Ráfaga, con su `mensaje_limitacion` real. Ráfaga elegido. */
    fun soloRafagaPermitida(): ExamsState = estadoBase(
        exam = CATALOGO[0],
        questions = UiAsync.Success(PREGUNTAS_CON_RECURSOS),
        capabilities = capabilities(rafagas = true, razonados = false, inmersivos = false, limitation = SOLO_RAFAGA_LIMITATION),
    )

    /** El tipo elegido (Inmersivo) no lo permite la especialidad: bloqueo con su porqué. */
    fun tipoNoPermitido(): ExamsState = estadoBase(
        exam = CATALOGO[3],
        questions = UiAsync.Success(PREGUNTAS_CON_RECURSOS),
        capabilities = capabilities(rafagas = true, razonados = false, inmersivos = false, limitation = SOLO_RAFAGA_LIMITATION),
    )

    /** No se han podido comprobar las capacidades: ningún tipo decidido, «Reintentar». */
    fun capacidadesCaidas(): ExamsState = publicadoConContenido().copy(
        specialtyCapabilities = UiAsync.Error(AppError.Network("timeout")),
    )

    /** Comprobando las capacidades. */
    fun capacidadesComprobando(): ExamsState = publicadoConContenido().copy(specialtyCapabilities = UiAsync.Loading)

    /** La especialidad no tiene fila propia: son los valores por defecto. */
    fun especialidadSinConfigurar(): ExamsState = estadoBase(
        exam = CATALOGO[0],
        questions = UiAsync.Success(PREGUNTAS_CON_RECURSOS),
        capabilities = capabilities(configured = false),
    )

    // -----------------------------------------------------------------------
    // Acceso: gratuito · premium con precio · premium sin precio
    // -----------------------------------------------------------------------

    /** Ráfaga gratuito y publicado: el único caso que la app sirve también sin sesión. */
    fun gratuito(): ExamsState = publicadoConContenido()

    /** Inmersivo premium publicado, con precio en OposiTickets. */
    fun premiumConPrecio(): ExamsState = estadoBase(
        exam = CATALOGO[3].copy(status = ExamStatus.PUBLISHED, publishedAt = PUBLISHED_AT),
        questions = UiAsync.Success(PREGUNTAS_CON_RECURSOS),
    )

    /** Premium sin precio: nadie ha puesto un precio de examen suelto. */
    fun premiumSinPrecio(): ExamsState = estadoBase(
        exam = CATALOGO[3].copy(status = ExamStatus.PUBLISHED, publishedAt = PUBLISHED_AT, priceTickets = 0),
        questions = UiAsync.Success(PREGUNTAS_CON_RECURSOS),
    )

    /**
     * Gratuito con un precio GUARDADO (dato heredado): `precio_tickets` y
     * `tipo_acceso` son columnas independientes y nada impide que convivan.
     */
    fun gratuitoConPrecioSinEfecto(): ExamsState = estadoBase(
        exam = CATALOGO[0].copy(priceTickets = 500),
        questions = UiAsync.Success(PREGUNTAS_CON_RECURSOS),
    )

    // -----------------------------------------------------------------------
    // Preguntas: vacías · cargando · error · con contenido (para B2)
    // -----------------------------------------------------------------------

    /** Borrador existente sin preguntas: `ex-5` nace con `questionCount = 0`. */
    fun preguntasVacias(): ExamsState = estadoBase(CATALOGO[4], UiAsync.Empty)

    /** Contando: la cifra es la servida (5), un dato desde M1 (K2-2). */
    fun preguntasCargando(): ExamsState = estadoBase(CATALOGO[0], UiAsync.Loading)

    /** No se han podido cargar: «Sin contar», sin cifra. */
    fun preguntasError(): ExamsState = estadoBase(CATALOGO[0], UiAsync.Error(AppError.Network("timeout")))

    fun conPreguntas(): ExamsState = publicadoConContenido()

    // -----------------------------------------------------------------------
    // Recursos (para B2). Ver el límite del KDoc: no hay tipo de pregunta.
    // -----------------------------------------------------------------------

    /** Examen Inmersivo (premium, borrador) cuyas preguntas llevan recursos. */
    fun inmersivoConRecursos(): ExamsState = estadoBase(CATALOGO[3], UiAsync.Success(PREGUNTAS_CON_RECURSOS))

    /** Examen Inmersivo cuyas preguntas no llevan ningún recurso. */
    fun inmersivoSinRecursos(): ExamsState = estadoBase(CATALOGO[3], UiAsync.Success(PREGUNTAS_SIN_RECURSOS))

    /** Un recurso con la ruta LOCAL que deja una subida fallida (P0-4) como URL. */
    fun recursoConRutaLocal(): ExamsState = estadoBase(CATALOGO[3], UiAsync.Success(PREGUNTAS_CON_RUTA_LOCAL))

    /** Preguntas sí, recursos no, en un borrador Ráfaga: el reparto normal de una instalación real. */
    fun borradorSinRecursos(): ExamsState = estadoBase(CATALOGO[4], UiAsync.Success(PREGUNTAS_SIN_RECURSOS))

    /**
     * La anomalía: un recurso colgado de una pregunta que NO consta como
     * inmersiva.
     *
     * Sólo las inmersivas pueden llevar recursos, pero el backend no lo impide,
     * así que el caso existe en los datos. `q-2` es RAZONADA y aquí se le cuelga
     * un documento, para comprobar que la pestaña lo enseña como anomalía en vez
     * de esconderlo o de dar por bueno el tipo.
     */
    val PREGUNTAS_CON_ANOMALIA: List<ExamQuestion> = PREGUNTAS_CON_RECURSOS.map { pregunta ->
        if (pregunta.id == "q-2") {
            pregunta.copy(
                resources = listOf(
                    resource("r-5", "q-2", QuestionResourceType.DOCUMENT, "Cartel de retirada del EPI"),
                ),
            )
        } else {
            pregunta
        }
    }

    fun recursoEnPreguntaNoInmersiva(): ExamsState =
        estadoBase(CATALOGO[3], UiAsync.Success(PREGUNTAS_CON_ANOMALIA))
}
