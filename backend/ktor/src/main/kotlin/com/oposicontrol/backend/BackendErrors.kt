package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

class BackendDomainException(
    val errorCode: String,
    val status: HttpStatusCode,
    override val message: String,
    /**
     * Segundos tras los que tiene sentido reintentar, o `null` si reintentar no
     * cambia nada.
     *
     * Sólo lo llevan los rechazos TRANSITORIOS de la base (bloqueo mutuo, espera
     * de cerrojo agotada, sentencia cancelada por tiempo): son los únicos donde
     * la misma petición, repetida tal cual, puede salir bien. `StatusPages` lo
     * publica como cabecera `Retry-After`; un 409 de dominio no la lleva porque
     * repetirlo daría exactamente el mismo 409.
     */
    val retryAfterSeconds: Int? = null,
    /**
     * Detalle TIPADO del sobre canonico (`details`), o `null`. Solo admite las
     * claves de [ErrorDetails]; nunca PII ni trazas. Ver `specs.errors`.
     */
    val details: ErrorDetails? = null,
) : RuntimeException(message)

private data class DomainErrorDefinition(
    val status: HttpStatusCode,
    val message: String,
)

private val SUPABASE_DOMAIN_ERRORS = mapOf(
    "RECORD_ANSWER_INVALID_ATTEMPT" to DomainErrorDefinition(HttpStatusCode.NotFound, "El intento no existe o no pertenece al usuario."),
    "RECORD_ANSWER_QUESTION_NOT_IN_ATTEMPT" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La pregunta no pertenece al intento."),
    "RECORD_ANSWER_OPTION_NOT_IN_QUESTION" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La opción no pertenece a la pregunta."),
    "RECORD_ANSWER_MISSING_SOLUTION" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La pregunta no tiene solución configurada."),
    "EVENT_IDEMPOTENCY_CONFLICT" to DomainErrorDefinition(HttpStatusCode.Conflict, "La clave de idempotencia ya fue utilizada con otro evento."),
    "EVENT_VERSION_INVALID" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La versión del evento no es válida."),
    "EVENT_IDEMPOTENCY_KEY_REQUIRED" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La clave de idempotencia es obligatoria."),
    "WALLET_INVALID_USER" to DomainErrorDefinition(HttpStatusCode.NotFound, "El usuario de la cartera no existe."),
    "WALLET_AMOUNT_INVALID" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La cantidad de tickets no puede ser cero."),
    "WALLET_TYPE_INVALID" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "El tipo de operación de cartera no es válido."),
    "WALLET_DESCRIPTION_REQUIRED" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La descripción de la operación es obligatoria."),
    "WALLET_IDEMPOTENCY_KEY_REQUIRED" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "La clave de idempotencia es obligatoria."),
    "WALLET_GRANT_AMOUNT_MUST_BE_POSITIVE" to DomainErrorDefinition(HttpStatusCode.UnprocessableEntity, "Las recompensas de reporte deben ser positivas."),
    "WALLET_IDEMPOTENCY_CONFLICT" to DomainErrorDefinition(HttpStatusCode.Conflict, "La clave de idempotencia ya fue utilizada con otros datos."),
    "WALLET_INSUFFICIENT_BALANCE" to DomainErrorDefinition(HttpStatusCode.Conflict, "El saldo de tickets no puede quedar negativo."),
    // Sincronización de roles. Los levanta `public.admin_sync_roles` — ver
    // `supabase/canonical/role_sync_guard_candidate_v1/`. La garantía vive en la
    // RPC, dentro de su transacción; esto sólo traduce su código a algo que un
    // operador pueda leer, sin SQL, nombres de tabla ni identificadores.
    "ROLE_SYNC_LAST_SUPERADMIN" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "No puedes retirar este rol porque debe existir al menos un superadministrador.",
    ),
    "ROLE_SYNC_EMPTY_ROLES" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "El usuario debe conservar al menos un rol.",
    ),
    "ROLE_SYNC_UNKNOWN_ROLE" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "Alguno de los roles seleccionados ya no existe.",
    ),
    "ROLE_SYNC_INVALID_USER" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "El usuario indicado no es válido.",
    ),
    // D1-26: conceder o retirar admin/superadmin exige superadmin.
    "SUPERADMIN_REQUIRED" to DomainErrorDefinition(
        HttpStatusCode.Forbidden,
        "Esta acción requiere un superadministrador.",
    ),
    // ------------------------------------------------------------------
    // Exámenes y preguntas (K0-4 / KQ0-1). Los levantan con `raise exception
    // '<CÓDIGO>'` las migraciones M3 (guardas de tipo en los padres), M4 (RPC de
    // vínculo, orden y desvínculo), Q1 (código de pregunta inmutable), Q2 (RPC de
    // alta y edición de preguntas) y Q4 (examen publicado que se quedaría vacío).
    // Se declaran ANTES de que esas migraciones existan en MAIN a propósito: el
    // día que se apliquen, su rechazo sale como 4xx legible y no como el 500
    // redactado de un `IllegalStateException`.
    //
    // Ninguna clave es prefijo de otra, y no es estética: el mapeo casa por
    // `startsWith`, así que un prefijo capturaría el código más largo con el
    // mensaje del corto. Lo fija `BackendErrorsTest`.
    // ------------------------------------------------------------------
    "EXAM_NOT_FOUND" to DomainErrorDefinition(HttpStatusCode.NotFound, "El examen no existe."),
    "EXAM_QUESTION_LIMIT_REACHED" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "El examen ya tiene el máximo de 200 preguntas.",
    ),
    "EXAM_QUESTION_POSITION_INVALID" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "La posición tiene que ser 1 o mayor.",
    ),
    "EXAM_REORDER_SET_MISMATCH" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "Las preguntas del examen han cambiado mientras las ordenabas. Recarga y vuelve a ordenar.",
    ),
    // Nuclear Review Finding 1: `uploadExamenPortada`/`clearExamenPortada` leían la
    // portada vigente y luego escribían sin condición (ni versión ni `if-match`), así
    // que dos escrituras concurrentes sobre el mismo examen se pisaban en silencio y
    // dejaban un objeto de Storage huérfano. El PATCH ahora exige que la fila siga
    // teniendo la portada leída al empezar; si otra escritura ganó la carrera, este
    // código avisa en vez de perder el cambio del rival ni dejar un huérfano invisible.
    "EXAM_COVER_CHANGED_CONCURRENTLY" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "La portada del examen ha cambiado mientras la actualizabas. Recarga y vuelve a intentarlo.",
    ),
    "EXAM_TYPE_LOCKED_BY_QUESTIONS" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "No se puede cambiar el tipo de un examen con preguntas vinculadas. " +
            "Quita antes sus preguntas.",
    ),
    "EXAM_PUBLISHED_WOULD_BE_EMPTY" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "El examen está publicado y se quedaría sin preguntas activas. Despublícalo antes o vincula otra pregunta.",
    ),
    // DEC-EX-01 = A en la base: la guarda que acompaña a K-DEC01 rechaza publicar un
    // examen sin preguntas activas. El mismo código y el mismo texto los lanza
    // `publicarExamen` cuando su propio filtro no casa, así que la consola ve una
    // sola respuesta venga de donde venga.
    EXAM_PUBLISH_REQUIRES_QUESTIONS_CODE to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        EXAM_PUBLISH_REQUIRES_QUESTIONS_MESSAGE,
    ),
    "QUESTION_NOT_FOUND" to DomainErrorDefinition(HttpStatusCode.NotFound, "La pregunta no existe."),
    "QUESTION_NOT_LINKED_TO_EXAM" to DomainErrorDefinition(
        HttpStatusCode.NotFound,
        "La pregunta no está vinculada a este examen.",
    ),
    "QUESTION_ARCHIVED" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "La pregunta está archivada: restáurala antes de vincularla a un examen.",
    ),
    "QUESTION_EXAM_TYPE_MISMATCH" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "El tipo de la pregunta no corresponde al del examen: Ráfaga admite ráfaga, Razonado admite razonada " +
            "e Inmersivo admite inmersiva.",
    ),
    // M10 · pregunta anulada en un examen (la anulación es de la relación pregunta↔examen).
    "EXAM_QUESTION_ANNULMENT_REASON_REQUIRES_ANNULMENT" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "El motivo de anulación sólo se admite cuando la pregunta está anulada en el examen.",
    ),
    "EXAM_QUESTION_ANNULMENT_REASON_TOO_LONG" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "El motivo de anulación no puede superar los 500 caracteres.",
    ),
    "QUESTION_ANNULMENT_REQUIRES_EXAM" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "Una pregunta sólo puede anularse dentro de un examen: indica el examen o quita la anulación.",
    ),
    // LOS TRES «TODAVÍA NO ESTÁ DISPONIBLE» SON 409 A PROPÓSITO, Y NO UN 501/503.
    //
    // Son fallos de aprovisionamiento —falta la migración M4— y el código honesto
    // sería 501 o 503. Pero el cliente los pinta a través de `NetworkResultAdapter`,
    // que **descarta el cuerpo de cualquier 5xx** y enseña un genérico «el servidor
    // no está disponible»: el administrador leería «reinténtalo más tarde» de algo
    // que no se arregla reintentando, y quien lo reporte no traerá el código. Con un
    // 4xx el adaptador sí pasa `message` y `code` tal cual, así que estas frases
    // llegan enteras. Un 409 («este estado del servidor no admite la operación») es
    // lo más cerca que queda sin tocar `core/network`, que no es de esta fase.
    //
    // Se retiran en cuanto M4 esté aplicada: entonces la RPC existe y nadie las
    // lanza. Ver `B_ROUND3B_NOTES.md` (desviaciones declaradas).
    "QUESTION_EXAM_LINK_UNAVAILABLE" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "Vincular preguntas a exámenes todavía no está disponible en este entorno.",
    ),
    "QUESTION_EXAM_UNLINK_UNAVAILABLE" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "Quitar preguntas de un examen todavía no está disponible en este entorno.",
    ),
    "EXAM_QUESTION_REORDER_UNAVAILABLE" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "Reordenar las preguntas de un examen todavía no está disponible en este entorno.",
    ),
    "QUESTION_TYPE_LOCKED_BY_EXAMS" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "No se puede cambiar el tipo de una pregunta vinculada a exámenes. " +
            "Quítala antes de esos exámenes.",
    ),
    "QUESTION_TYPE_LOCKED_BY_RESOURCES" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "Una pregunta inmersiva con recursos no puede cambiar de tipo. Quita antes sus recursos.",
    ),
    "QUESTION_CODE_IMMUTABLE" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "El código de la pregunta lo genera el sistema y no se puede cambiar.",
    ),
    "QUESTION_ID_REQUIRED" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "Falta el identificador de la pregunta.",
    ),
    // Q2-1 (revisión de C): el mismo id con CUALQUIER diferencia de contenido. El caso
    // real es un reintento de alta que llega después de una edición legítima.
    "QUESTION_ID_CONFLICT" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "Esta pregunta ya existe con otro contenido; recarga antes de volver a guardar.",
    ),
    "QUESTION_STATEMENT_REQUIRED" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "El enunciado no puede quedar vacío.",
    ),
    "QUESTION_TYPE_UNKNOWN" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "El tipo tiene que ser ráfaga, razonada o inmersiva.",
    ),
    "QUESTION_DIFFICULTY_UNKNOWN" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "La dificultad tiene que ser fácil, media o difícil.",
    ),
    "QUESTION_OPTIONS_INVALID" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "La pregunta necesita exactamente cuatro opciones (A, B, C y D) y ninguna puede estar vacía.",
    ),
    "QUESTION_CORRECT_ANSWER_INVALID" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "Marca exactamente una opción correcta.",
    ),
    "QUESTION_PATCH_INVALID" to DomainErrorDefinition(
        HttpStatusCode.BadRequest,
        "Algún campo de la edición no tiene un valor válido.",
    ),
    "QUESTION_SOLUTION_MISSING" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "Esta pregunta todavía no tiene solución guardada, así que la explicación no se puede editar por " +
            "separado. Guarda primero las opciones y la respuesta correcta.",
    ),
    // RECURSOS DE PREGUNTA (Q3, KQ4). El material se sube; no se «apunta».
    "QUESTION_RESOURCE_NOT_FOUND" to DomainErrorDefinition(
        HttpStatusCode.NotFound,
        "El recurso no existe o no pertenece a esa pregunta.",
    ),
    "QUESTION_RESOURCE_FILE_REQUIRED" to DomainErrorDefinition(
        HttpStatusCode.BadRequest,
        "Falta el archivo del recurso.",
    ),
    "QUESTION_RESOURCE_TYPE_INVALID" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "Ese formato no se admite para este tipo de recurso: imagen (PNG, JPG, WEBP o GIF), audio (MP3, M4A " +
            "o WAV), vídeo (MP4) o PDF.",
    ),
    "QUESTION_RESOURCE_TOO_LARGE" to DomainErrorDefinition(
        HttpStatusCode.PayloadTooLarge,
        "El archivo supera el límite de 50 MB.",
    ),
    "QUESTION_RESOURCE_UPLOAD_REQUIRED" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "Las imágenes, audios, vídeos y PDF se adjuntan subiendo el archivo, no escribiendo una ruta.",
    ),
    "QUESTION_RESOURCE_URL_INVALID" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "El enlace tiene que empezar por http:// o https://.",
    ),
    // IMPORTACIONES (IM3, KI0-6). Los mensajes hablan de lo que el administrador
    // tiene que HACER, no de la fila ni de la restricción que saltó: el cuerpo de
    // PostgREST lleva dentro el texto del PDF, y ese texto no puede publicarse.
    "IMPORT_LOTE_ID_REQUIRED" to DomainErrorDefinition(
        HttpStatusCode.BadRequest,
        "Falta el lote que se quiere publicar.",
    ),
    "IMPORT_LOTE_NOT_FOUND" to DomainErrorDefinition(HttpStatusCode.NotFound, "El lote no existe."),
    "IMPORT_LOTE_STATE_INVALID" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "El lote no está en un estado que se pueda publicar.",
    ),
    "IMPORT_STAGING_NOT_FOUND" to DomainErrorDefinition(
        HttpStatusCode.NotFound,
        "Esa fila de la importación ya no existe.",
    ),
    "IMPORT_PENDING_REVIEW" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "Quedan filas por revisar: corrige y aprueba, u omite, antes de publicar.",
    ),
    "IMPORT_ROW_INCOMPLETE" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "Hay filas incompletas para su tipo (enunciado, cuatro opciones, respuesta correcta y, en razonadas " +
            "e inmersivas, explicación).",
    ),
    "IMPORT_DUPLICATES_UNRESOLVED" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "Hay preguntas repetidas sin decidir: omítelas o vincula la pregunta que ya existe.",
    ),
    "IMPORT_LINK_TARGET_INVALID" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "Alguna pregunta elegida para vincular ya no está activa, no es del mismo tipo o ya no coincide con lo revisado.",
    ),
    "IMPORT_TYPE_MISMATCH" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "El tipo del lote no coincide con el del examen.",
    ),
    "IMPORT_EXAM_NOT_FOUND" to DomainErrorDefinition(HttpStatusCode.NotFound, "El examen del lote no existe."),
    "IMPORT_EXAM_TYPE_UNKNOWN" to DomainErrorDefinition(
        HttpStatusCode.UnprocessableEntity,
        "El examen del lote tiene un tipo desconocido.",
    ),
    "IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "El examen está publicado: confirma que las preguntas se verán al instante.",
    ),
    // Mismo motivo que los tres «UNAVAILABLE» de arriba: 409 para que el cuerpo llegue
    // al administrador. Publicar no está disponible hasta que IM3 esté aplicada, y eso
    // no se arregla reintentando.
    "IMPORT_WRITE_RPC_NOT_PROVISIONED" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "Publicar importaciones todavía no está disponible en este entorno. No se ha importado ninguna fila " +
            "y el lote queda exactamente como estaba: no es un problema del contenido.",
    ),
    // El CHECK vivo de `estado_parseo` todavía no admite `aprobada` ni `omitida` (falta
    // IM1). Sin esta traducción, aprobar una fila devolvía el 23514 crudo de Postgres,
    // con el nombre de la restricción y la fila entera dentro.
    "IMPORT_REVIEW_STATE_UNAVAILABLE" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "Aprobar y omitir filas todavía no está disponible en este entorno. Puedes corregir el contenido y " +
            "guardarlo; la decisión de revisión se podrá marcar cuando se actualice la base.",
    ),
    "STAGING_ROW_ALREADY_IMPORTED" to DomainErrorDefinition(
        HttpStatusCode.Conflict,
        "Esta fila ya se publicó y no se puede modificar.",
    ),
)

/**
 * Los códigos de dominio que este backend sabe traducir.
 *
 * Existe sólo para que `BackendErrorsTest` compruebe la invariante de la que
 * depende el mapeo por `startsWith`: ninguna clave es prefijo de otra.
 */
internal fun supabaseDomainErrorCodes(): Set<String> = SUPABASE_DOMAIN_ERRORS.keys

/**
 * El error de dominio de [code] tal y como lo vería el cliente si lo hubiese
 * levantado la base.
 *
 * Existe para que un código que este backend decide POR SÍ MISMO —«la RPC no está
 * aprovisionada», «el lote no admite este estado»— salga con exactamente el mismo
 * estado y el mismo texto que cuando llega desde Postgres. La alternativa era
 * repetir la frase en el sitio donde se lanza, y dos copias de un mensaje
 * publicado divergen en cuanto alguien retoca una.
 *
 * Un código desconocido es un fallo de programación y revienta aquí, no en
 * producción con un texto vacío.
 */
internal fun domainError(code: String): BackendDomainException {
    val definition = SUPABASE_DOMAIN_ERRORS[code]
        ?: error("Código de dominio sin definición en SUPABASE_DOMAIN_ERRORS: $code")
    return BackendDomainException(code, definition.status, definition.message)
}

private data class SqlStateErrorDefinition(
    val errorCode: String,
    val status: HttpStatusCode,
    val message: String,
    val retryAfterSeconds: Int? = null,
)

/**
 * Errores de INTEGRIDAD de Postgres, traducidos por su SQLSTATE.
 *
 * Son el segundo camino de [mapSupabaseDomainError] y necesitan uno propio: el
 * primero casa por PREFIJO del campo `message`, y un SQLSTATE no aparece nunca
 * al principio del mensaje de PostgREST —va en su propio campo `code`—. Intentar
 * reutilizar el `startsWith` para esto no funciona, y conviene decirlo porque es
 * el error natural al leer el mapa de arriba.
 *
 * Los mensajes son deliberadamente de dominio y no de base de datos: sin nombre
 * de tabla, sin nombre de restricción, sin ruta, sin id y **sin el valor
 * recibido**. Un 500 con «Error al consultar el servicio de datos» ante un código
 * de examen repetido decía «el servidor está roto» cuando lo que pasaba es que
 * el administrador tenía que cambiar un campo.
 */
private val SUPABASE_SQLSTATE_ERRORS: Map<String, SqlStateErrorDefinition> = mapOf(
    // unique_violation
    "23505" to SqlStateErrorDefinition(
        "UNIQUE_VIOLATION",
        HttpStatusCode.Conflict,
        "Ese valor ya está en uso por otro registro. Cámbialo y vuelve a guardar.",
    ),
    // check_violation
    "23514" to SqlStateErrorDefinition(
        "CHECK_VIOLATION",
        HttpStatusCode.UnprocessableEntity,
        "Alguno de los valores enviados no está permitido para este campo.",
    ),
    // foreign_key_violation
    "23503" to SqlStateErrorDefinition(
        "FOREIGN_KEY_VIOLATION",
        HttpStatusCode.Conflict,
        FOREIGN_KEY_VIOLATION_MESSAGE,
    ),
    // invalid_text_representation: un identificador que no es un UUID (o un
    // número que no es número) llega a una columna tipada. Sin esto salía como
    // `IllegalStateException` → 500 «el servidor está roto», cuando lo único que
    // pasa es que la petición trae un valor mal formado. Es un 422 del cliente.
    //
    // El mensaje NO repite el valor: el cuerpo de PostgREST lo trae dentro
    // (`invalid input syntax for type uuid: "…"`) y ese eco es justo lo que un
    // mensaje publicado no puede llevar.
    "22P02" to SqlStateErrorDefinition(
        "INVALID_TEXT_REPRESENTATION",
        HttpStatusCode.UnprocessableEntity,
        "Algún identificador no tiene un formato válido.",
    ),
    // ------------------------------------------------------------------
    // Rechazos TRANSITORIOS: la misma petición, repetida, puede salir bien.
    //
    // Las sesiones PostgREST de MAIN corren con `statement_timeout = 8s` y
    // `lock_timeout = 8s` (rol `authenticator`), así que estos tres no son
    // teóricos: una escritura que coincide con otra sobre las mismas filas, o una
    // lectura pesada, los produce. Antes eran un 500 genérico indistinguible de
    // una avería. Son 503 con `Retry-After` porque el servicio SÍ funciona; lo
    // que no se pudo es atender ESTA petición ahora.
    //
    // Los mensajes no afirman «no se ha guardado nada»: dentro de una llamada a
    // PostgREST es cierto (su transacción se deshace entera), pero una operación
    // del panel puede encadenar varias llamadas y las anteriores sí quedaron.
    // ------------------------------------------------------------------
    // deadlock_detected
    "40P01" to SqlStateErrorDefinition(
        "DATABASE_DEADLOCK",
        HttpStatusCode.ServiceUnavailable,
        "La operación coincidió con otra sobre los mismos datos y la base de datos la canceló. " +
            "Vuelve a intentarlo en unos segundos.",
        retryAfterSeconds = TRANSIENT_LOCK_RETRY_AFTER_SECONDS,
    ),
    // lock_not_available (incluye `lock_timeout` agotado)
    "55P03" to SqlStateErrorDefinition(
        "DATABASE_LOCK_TIMEOUT",
        HttpStatusCode.ServiceUnavailable,
        "Los datos estaban bloqueados por otra operación en curso. Vuelve a intentarlo en unos segundos.",
        retryAfterSeconds = TRANSIENT_LOCK_RETRY_AFTER_SECONDS,
    ),
    // query_canceled (incluye `statement_timeout` agotado)
    "57014" to SqlStateErrorDefinition(
        "DATABASE_STATEMENT_TIMEOUT",
        HttpStatusCode.ServiceUnavailable,
        "La base de datos tardó demasiado en responder y canceló la operación. " +
            "Vuelve a intentarlo en unos segundos.",
        retryAfterSeconds = TRANSIENT_TIMEOUT_RETRY_AFTER_SECONDS,
    ),
)

/**
 * Un bloqueo se resuelve en cuanto termina la otra transacción: esperar poco
 * basta. Una sentencia que agotó 8 s necesita algo más de aire antes de volver a
 * pedirle lo mismo a una base que ya iba cargada.
 */
private const val TRANSIENT_LOCK_RETRY_AFTER_SECONDS = 2
private const val TRANSIENT_TIMEOUT_RETRY_AFTER_SECONDS = 5

/**
 * `23503`: la RED DE SEGURIDAD del borrado real de examen, no su guarda.
 *
 * La guarda vive en `AdminV1Gateway.deleteExamen`, que cuenta los dependientes de
 * las seis tablas de datos de usuario ANTES de borrar y responde
 * [EXAM_HAS_USER_DATA_CODE]. Esto de aquí es lo que queda: un dependiente que el
 * inventario no contempla —otra tabla, un `on delete restrict` que no
 * esperábamos, `preguntas.lote_importacion_id` al borrar un lote— hace que la
 * base rechace la escritura, y sin mapeo eso salía como `IllegalStateException` →
 * 500 redactado. Un 500 durante una acción destructiva es lo peor de los dos
 * mundos: no dice si borró.
 *
 * **El 409 vale para cualquier 23503 y no sólo para exámenes**, porque cualquier
 * 23503 significa lo mismo: hay algo apuntando a esta fila. El mensaje se redacta
 * en genérico por eso, y aun así dice qué hacer —archivar— porque el único sitio
 * del panel que hoy provoca un borrado con dependientes es el de exámenes.
 *
 * Misma regla que sus dos hermanos: sin nombre de tabla, sin restricción, sin
 * SQLSTATE y **sin el identificador de la fila**, que el cuerpo de PostgREST trae
 * dentro de `details` («Key (id)=(…) is still referenced from table …»).
 */
private const val FOREIGN_KEY_VIOLATION_MESSAGE: String =
    "No se puede borrar este registro porque hay otros que dependen de él. " +
        "Archívalo en su lugar: se retira sin perder lo que ya está asociado."

/**
 * El código de dominio del rechazo por datos de usuario, que la consola usa para
 * presentar el aviso de la acción.
 *
 * Se declara aquí, con el resto del vocabulario de errores, y no en el gateway:
 * la copia literal que la pantalla compara vive en
 * `features/exams/.../ExamPublicationPresentation.kt`, y tener el original
 * disperso es lo que hace que las dos divergan.
 */
internal const val EXAM_HAS_USER_DATA_CODE: String = "EXAM_HAS_USER_DATA"

/**
 * «Este examen ya lo han usado los usuarios»: 409 y NO se borra nada.
 *
 * [categorias] son etiquetas HUMANAS de lo que cuelga —«intentos de examen»,
 * «compras o desbloqueos de contenido»—, nunca nombres de tabla ni de columna:
 * las compone `AdminV1Gateway` a partir de su inventario y aquí sólo se
 * enumeran. Decir QUÉ hay es la mitad del mensaje que sirve para algo; la otra es
 * decir qué hacer en su lugar, y es archivar.
 *
 * Por qué 409 y no 422: no hay nada que el administrador pueda corregir en la
 * petición. El estado del recurso es el que impide la operación, y eso es un
 * conflicto.
 */
internal fun examHasUserDataException(categorias: List<String>): BackendDomainException =
    BackendDomainException(
        EXAM_HAS_USER_DATA_CODE,
        HttpStatusCode.Conflict,
        "No se puede borrar este examen porque los usuarios ya lo han usado: hay " +
            categorias.humanJoin() + ". Archívalo en su lugar: deja de aparecer en la app y " +
            "el historial de los usuarios se conserva.",
    )

/** Ver [examDeleteGuardUnavailableException]. */
internal const val EXAM_DELETE_GUARD_UNAVAILABLE_CODE: String = "EXAM_DELETE_GUARD_UNAVAILABLE"

/**
 * No se ha podido COMPROBAR si hay datos de usuario, así que no se ha borrado.
 *
 * Es el fallo cerrado de la guarda, y existe porque la alternativa es peor de las
 * dos maneras: borrar «porque la comprobación no respondió» destruye historial de
 * usuarios en silencio, y dejar subir el fallo del upstream produce el 500
 * redactado —«Error al consultar el servicio de datos. Referencia: …»— que en una
 * acción destructiva no permite saber si borró o no.
 *
 * Comparte el 409 con [examHasUserDataException] pero NO su código: para la
 * consola son dos situaciones distintas —una es definitiva y la otra se reintenta—
 * y un solo código obligaría a distinguirlas por el texto del mensaje.
 */
internal fun examDeleteGuardUnavailableException(): BackendDomainException =
    BackendDomainException(
        EXAM_DELETE_GUARD_UNAVAILABLE_CODE,
        HttpStatusCode.Conflict,
        "No se ha podido comprobar si este examen tiene datos de usuarios, así que no se ha " +
            "borrado nada. Vuelve a intentarlo en unos segundos; si sigue fallando, archívalo.",
    )

/** «a, b y c». Enumeración para leer, no para parsear. */
private fun List<String>.humanJoin(): String = when (size) {
    0 -> "datos asociados"
    1 -> first()
    else -> dropLast(1).joinToString(", ") + " y " + last()
}

/**
 * El caso concreto que motivó todo esto: `examenes.codigo_examen` repetido.
 *
 * Se distingue del 23505 genérico mirando si el rechazo habla de la columna
 * `codigo_examen`. Eso se lee de los campos que devuelve el SERVIDOR —el nombre
 * de la restricción en `message`, o el nombre de la columna en `details`—, que
 * son identificadores de esquema, no entrada del cliente: nada de lo que el
 * administrador escribió viaja de vuelta en la respuesta.
 *
 * **`NOT_VERIFIED`**: el nombre real de la restricción en MAIN no se ha leído.
 * Si no contiene `codigo_examen`, esto degrada al 409 genérico de arriba — que
 * sigue siendo un mensaje humano y sigue sin ser un 500. La degradación es la
 * conducta buscada, no un descuido.
 */
private val EXAM_CODE_DUPLICATE = SqlStateErrorDefinition(
    "EXAM_CODE_DUPLICATE",
    HttpStatusCode.Conflict,
    "Ya existe otro examen con ese código. Elige un código distinto y vuelve a guardar.",
)

/**
 * `examenes_total_preguntas_check`: la columna no admite un número negativo.
 *
 * Desde PW-2 (`20260917084454`, aplicada en MAIN) el CHECK es `>= 0`: un borrador
 * vacío es un examen válido. Sólo un negativo lo dispara, y desde M1
 * (`exams_total_preguntas_maintained`) ninguna escritura de la API llega a la
 * columna —la mantiene la base con triggers y una guarda neutraliza cualquier
 * otro escritor—, así que en la práctica esta definición es la red de un camino
 * que ya no existe.
 *
 * Se conserva con definición propia porque el 23514 genérico —«Alguno de los
 * valores enviados no está permitido para este campo»— no dice QUÉ campo, y el
 * código de dominio es lo que permite a la consola señalar ESE campo.
 *
 * El mensaje sigue la misma regla que el resto: dice qué pasa, sin nombre de
 * tabla, de restricción, ni el valor recibido.
 */
private val EXAM_QUESTION_COUNT_INVALID = SqlStateErrorDefinition(
    "EXAM_QUESTION_COUNT_INVALID",
    HttpStatusCode.UnprocessableEntity,
    "El número de preguntas no puede ser negativo.",
)

/** Código de dominio de [examCodeRequiredException]; la consola lo asocia al campo Código. */
internal const val EXAM_CODE_REQUIRED_CODE: String = "EXAM_CODE_REQUIRED"

/**
 * `examenes_codigo_examen_no_vacio_chk` (M5): un examen no puede quedarse sin código.
 *
 * Lo mismo sale por dos caminos, con el mismo código y el mismo mensaje: el PATCH
 * lo rechaza ANTES de llamar a la base ([examCodeRequiredException], K-C01) y la
 * base lo rechaza con 23514 si llegara por otra puerta.
 */
private val EXAM_CODE_REQUIRED = SqlStateErrorDefinition(
    EXAM_CODE_REQUIRED_CODE,
    HttpStatusCode.UnprocessableEntity,
    "El código del examen no puede quedar vacío.",
)

/** El PATCH de examen trae `codigoExamen` en blanco. Ver [EXAM_CODE_REQUIRED]. */
internal fun examCodeRequiredException(): BackendDomainException =
    BackendDomainException(EXAM_CODE_REQUIRED.errorCode, EXAM_CODE_REQUIRED.status, EXAM_CODE_REQUIRED.message)

/**
 * La portada no es JPG, PNG ni WEBP (K5).
 *
 * Son los tres formatos que promete la pantalla y que admite el CHECK de M6. El
 * mensaje no repite el tipo que mandó el cliente: esa cadena es entrada libre.
 */
internal fun examCoverTypeInvalidException(): BackendDomainException =
    BackendDomainException(
        "EXAM_COVER_TYPE_INVALID",
        HttpStatusCode.UnprocessableEntity,
        "La portada tiene que ser JPG, PNG o WEBP.",
    )

/** Código de dominio de [examPublishRequiresQuestionsException]. */
internal const val EXAM_PUBLISH_REQUIRES_QUESTIONS_CODE: String = "EXAM_PUBLISH_REQUIRES_QUESTIONS"

private const val EXAM_PUBLISH_REQUIRES_QUESTIONS_MESSAGE: String = "No se puede publicar un examen sin preguntas activas."

/**
 * DEC-EX-01 = A: un examen sin preguntas activas no se publica.
 *
 * Es la regla del propietario aplicada con PW-2 leída entera: «el examen, si no
 * está publicado, puede estar vacío». 409 como la guarda de la base que la
 * acompaña: el estado del examen es lo que impide publicarlo.
 */
internal fun examPublishRequiresQuestionsException(): BackendDomainException =
    BackendDomainException(
        EXAM_PUBLISH_REQUIRES_QUESTIONS_CODE,
        HttpStatusCode.Conflict,
        EXAM_PUBLISH_REQUIRES_QUESTIONS_MESSAGE,
    )

/**
 * Traduce un error de Supabase a un error tipado y humano. Dos caminos, y sólo
 * dos.
 *
 * **Sólo respuestas de error.** Antes `httpStatus` era un parámetro sin usar.
 *
 * ### Camino 1 — códigos de dominio que levanta una RPC
 *
 * **El código tiene que ENCABEZAR el mensaje, no aparecer en él.** Antes se
 * buscaba como subcadena en todo el cuerpo, y eso convertía cualquier eco de la
 * entrada del cliente en un error de dominio fabricado: un
 * `POST /usuarios/ROLE_SYNC_LAST_SUPERADMIN/roles` hacía que Postgres devolviera
 * `invalid input syntax for type uuid: "ROLE_SYNC_LAST_SUPERADMIN"`, y el
 * backend respondía un 409 con el mensaje de la guarda del último
 * superadministrador sin que ninguna guarda se hubiera ejecutado. Un 409 dejaba
 * de ser prueba de que la invariante saltó. `raise exception 'CODIGO'` produce un
 * `message` que empieza por el código; un eco lo lleva incrustado.
 *
 * ### Camino 2 — restricciones de la base, por SQLSTATE
 *
 * Estas no las levanta nadie con `raise`: las levanta Postgres, y su código vive
 * en el campo `code` del cuerpo. Por eso hace falta un camino aparte y no basta
 * con añadir entradas al mapa de arriba.
 *
 * El orden importa: primero los códigos de dominio. Un eco del cliente dentro
 * del mensaje no puede llegar al camino 2 porque el camino 2 no mira el mensaje,
 * mira `code`, y `code` lo pone el servidor.
 */
fun mapSupabaseDomainError(httpStatus: Int, body: String): BackendDomainException? {
    if (httpStatus < 400) return null
    return mapRaisedDomainCode(body) ?: mapSqlStateError(body)
}

private fun mapRaisedDomainCode(body: String): BackendDomainException? {
    val subject = supabaseErrorMessage(body).trimStart()
    val code = SUPABASE_DOMAIN_ERRORS.keys.firstOrNull { subject.startsWith(it, ignoreCase = true) }
        ?: return null
    val definition = SUPABASE_DOMAIN_ERRORS.getValue(code)
    return BackendDomainException(code, definition.status, definition.message)
}

private fun mapSqlStateError(body: String): BackendDomainException? {
    val sqlState = supabaseErrorField(body, "code") ?: return null
    val definition = when {
        sqlState == "23505" && mentionsExamCodeColumn(body) -> EXAM_CODE_DUPLICATE
        sqlState == "23514" && mentionsExamQuestionCountCheck(body) -> EXAM_QUESTION_COUNT_INVALID
        sqlState == "23514" && mentionsExamCodeNotBlankCheck(body) -> EXAM_CODE_REQUIRED
        sqlState == "23514" && mentionsStagingStateCheck(body) -> IMPORT_REVIEW_STATE_UNAVAILABLE
        sqlState == "23503" && isMissingReferenceViolation(body) -> missingReferenceDefinition(body)
        else -> SUPABASE_SQLSTATE_ERRORS[sqlState] ?: return null
    }
    return BackendDomainException(
        definition.errorCode,
        definition.status,
        definition.message,
        retryAfterSeconds = definition.retryAfterSeconds,
    )
}

/**
 * ¿El rechazo viene del CHECK de `preguntas_parseadas_staging.estado_parseo`?
 *
 * Sólo se mira `message`, que es donde PostgREST pone el nombre de la restricción.
 * `details` trae «Failing row contains (…)» con el texto del PDF dentro, y ese texto
 * no puede intervenir en una decisión de mapeo ni acercarse a una respuesta.
 *
 * Mientras IM1 no esté aplicada, este CHECK es lo que rechaza `aprobada` y `omitida`;
 * el día que lo esté, deja de saltar y esta rama simplemente no se usa.
 */
private fun mentionsStagingStateCheck(body: String): Boolean =
    supabaseErrorField(body, "message")
        ?.contains("preguntas_parseadas_staging_estado_parseo_check", ignoreCase = true) == true

private val IMPORT_REVIEW_STATE_UNAVAILABLE = SqlStateErrorDefinition(
    "IMPORT_REVIEW_STATE_UNAVAILABLE",
    HttpStatusCode.Conflict,
    "Aprobar y omitir filas todavía no está disponible en este entorno. Puedes corregir el contenido y " +
        "guardarlo; la decisión de revisión se podrá marcar cuando se actualice la base.",
)

/**
 * ¿El rechazo habla de `examenes.codigo_examen`?
 *
 * Se INSPECCIONAN `message` (que lleva el nombre de la restricción) y `details`
 * (que lleva el nombre de la columna). Inspeccionar no es devolver: de aquí sólo
 * sale un booleano, y el mensaje que llega al cliente es una constante.
 */
private fun mentionsExamCodeColumn(body: String): Boolean =
    listOfNotNull(supabaseErrorField(body, "message"), supabaseErrorField(body, "details"))
        .any { it.contains("codigo_examen", ignoreCase = true) }

/**
 * ¿El rechazo habla de `examenes_total_preguntas_check`?
 *
 * Sólo se mira `message`, que es donde PostgREST pone el nombre de la
 * restricción. `details` trae la fila entera —«Failing row contains (…)»— con el
 * código del examen y su id dentro, así que buscar ahí acercaría datos del
 * cliente a una decisión de mapeo sin necesidad. Igual que en
 * [mentionsExamCodeColumn], de aquí sale un booleano y nada más.
 */
private fun mentionsExamQuestionCountCheck(body: String): Boolean =
    supabaseErrorField(body, "message")
        ?.contains("total_preguntas", ignoreCase = true) == true

/**
 * `23503` al ESCRIBIR una fila que apunta a otra que no existe (Q2-4, revisión de C).
 *
 * El 23503 tiene dos sentidos opuestos y el mensaje genérico sólo servía para uno:
 * «no se puede BORRAR porque hay otros que dependen» ([FOREIGN_KEY_VIOLATION_MESSAGE]).
 * Un alta de pregunta con un autor o un lote que ya no existen salía con ese texto
 * de borrado, que no tiene nada que ver con lo que pasó. Postgres escribe el primer
 * caso como «insert or update on table … violates foreign key constraint …» y el
 * segundo como «update or delete on table …»; aquí se distinguen por eso y, dentro
 * del primero, por el NOMBRE de la restricción (leído de MAIN), que lo pone el
 * servidor. Ningún mensaje publicado lleva tabla, restricción ni identificador.
 */
private fun isMissingReferenceViolation(body: String): Boolean =
    supabaseErrorField(body, "message")?.trimStart()?.startsWith("insert or update on table", ignoreCase = true) == true

private fun missingReferenceDefinition(body: String): SqlStateErrorDefinition {
    val message = supabaseErrorField(body, "message").orEmpty()
    return when {
        message.contains("preguntas_creado_por_fkey") || message.contains("examenes_creado_por_fkey") -> AUTHOR_NOT_FOUND
        message.contains("preguntas_lote_importacion_id_fkey") -> IMPORT_BATCH_NOT_FOUND
        message.contains("examenes_especialidad_id_fkey") -> EXAM_SPECIALTY_NOT_FOUND
        else -> REFERENCE_NOT_FOUND
    }
}

private val AUTHOR_NOT_FOUND = SqlStateErrorDefinition(
    "AUTHOR_NOT_FOUND",
    HttpStatusCode.UnprocessableEntity,
    "La cuenta con la que has iniciado sesión ya no existe. Vuelve a iniciar sesión.",
)

private val IMPORT_BATCH_NOT_FOUND = SqlStateErrorDefinition(
    "IMPORT_BATCH_NOT_FOUND",
    HttpStatusCode.UnprocessableEntity,
    "El lote de importación ya no existe. Recarga las importaciones.",
)

private val EXAM_SPECIALTY_NOT_FOUND = SqlStateErrorDefinition(
    "EXAM_SPECIALTY_NOT_FOUND",
    HttpStatusCode.UnprocessableEntity,
    "La especialidad elegida ya no existe.",
)

private val REFERENCE_NOT_FOUND = SqlStateErrorDefinition(
    "REFERENCE_NOT_FOUND",
    HttpStatusCode.UnprocessableEntity,
    "Alguno de los datos relacionados ya no existe. Recarga y vuelve a intentarlo.",
)

/**
 * ¿El rechazo es `examenes_codigo_examen_no_vacio_chk` (M5)? Sólo `message`, por
 * lo mismo que [mentionsExamQuestionCountCheck]: `details` trae la fila entera.
 */
private fun mentionsExamCodeNotBlankCheck(body: String): Boolean =
    supabaseErrorField(body, "message")
        ?.contains("examenes_codigo_examen_no_vacio_chk", ignoreCase = true) == true

/**
 * El campo `message` del cuerpo de PostgREST, o el cuerpo entero si no lo trae.
 *
 * Se extrae con expresión regular y no deserializando porque aquí llegan cuerpos
 * que no siempre son JSON —y porque un cuerpo malformado no debe tumbar el
 * mapeo de errores.
 */
private fun supabaseErrorMessage(body: String): String = supabaseErrorField(body, "message") ?: body

/**
 * El campo `code` que puso el SERVIDOR, para quien necesite decidir por él fuera
 * de este fichero.
 *
 * Es el mismo camino 2 de [mapSupabaseDomainError] y existe por el mismo motivo:
 * un `contains` sobre el mensaje convierte cualquier eco de la entrada del
 * cliente en un diagnóstico fabricado, y `code` no lo escribe el cliente. Lo usa
 * `AdminV1Routes` para distinguir «falta la función» (PGRST202) de «esta fila
 * está mal», que es una decisión de PostgREST y no de un mapa de dominio — por
 * eso se expone el campo y no se añade una entrada a [SUPABASE_SQLSTATE_ERRORS],
 * que convertiría el fallo de infraestructura en un error de dominio para TODOS
 * los llamadores.
 *
 * Acepta el texto completo del `IllegalStateException` del transporte, que lleva
 * el cuerpo recortado a 300 caracteres detrás de un prefijo sin comillas: el
 * `code` de PostgREST va el primero de las cuatro claves, así que entra dentro
 * del recorte y es la primera aparición de `"code"` en el texto.
 */
internal fun supabaseErrorCode(body: String): String? = supabaseErrorField(body, "code")

/** Un campo de cadena del cuerpo de error, o `null` si no está o no es cadena. */
private fun supabaseErrorField(body: String, field: String): String? =
    Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        .find(body)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace("\\\"", "\"")

// =====================================================================
// SOBRE CANONICO DE ERROR (SLICE 0, `specs.errors`, FROZEN)
//
// Todo non-2xx JSON de app, admin y /v1/auth es
// `{code!, message!, requestId!, details?}` (+ campos legacy aditivos). Aqui
// vive el VOCABULARIO: el catalogo generico, los codigos de intento congelados
// (se implementan en SLICE 3) y el detalle tipado. La traduccion de excepciones
// sigue siendo `installBackendStatusPages()`; no hay un segundo DTO: el cuerpo
// es `ErrorDto` extendido.
// =====================================================================

/** Un problema de validacion de un campo: `details.issues[]`. */
@Serializable
data class ErrorIssue(val field: String, val issue: String)

/**
 * `details` del sobre. Solo estas claves (tipadas por codigo); nunca PII ni
 * trazas. Los nulos no se serializan con el `Json` del modulo.
 */
@Serializable
data class ErrorDetails(
    val issues: List<ErrorIssue>? = null,
    val accessState: String? = null,
    val currentRevision: Long? = null,
    val allowedValues: List<String>? = null,
)

/** Valores canonicos de `ErrorIssue.issue` genericos. Un contrato puede anadir los suyos (UPPER_SNAKE). */
object CanonicalIssue {
    const val UNKNOWN_FIELD = "UNKNOWN_FIELD"
    const val OUT_OF_RANGE = "OUT_OF_RANGE"
    const val REQUIRED = "REQUIRED"
    const val INVALID_FORMAT = "INVALID_FORMAT"
}

/**
 * Catalogo generico + codigos de intento congelados.
 *
 * Un estado HTTP "desnudo" en un contrato FROZEN significa el codigo generico de
 * ese estado. Los codigos especificos de entidad (`*_NOT_FOUND`) y los de
 * dominio de [SUPABASE_DOMAIN_ERRORS] conviven con este catalogo: el `code` de
 * un [BackendDomainException] sale tal cual.
 *
 * Sinonimos RETIRADOS (no se emiten nunca): EXAM_ACCESS_DENIED, OPTION_MISMATCH,
 * ATTEMPT_NOT_OPEN, ATTEMPT_FINISHED ([RETIRED_ERROR_CODES]).
 */
enum class CanonicalErrorCode(val status: HttpStatusCode, val defaultMessage: String) {
    VALIDATION(HttpStatusCode.BadRequest, "Algun dato de la peticion no es valido."),
    MALFORMED_BODY(HttpStatusCode.BadRequest, "El cuerpo de la peticion no es valido."),
    INVALID_CURSOR(HttpStatusCode.BadRequest, "El cursor de paginacion no es valido. Vuelve a cargar la lista desde el principio."),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatusCode.BadRequest, "Falta la cabecera Idempotency-Key."),
    IDEMPOTENCY_KEY_INVALID(HttpStatusCode.BadRequest, "La cabecera Idempotency-Key tiene que ser un UUID."),
    UNAUTHENTICATED(HttpStatusCode.Unauthorized, "Tu sesion no es valida o ha caducado. Vuelve a iniciar sesion."),
    FORBIDDEN(HttpStatusCode.Forbidden, "No tienes permisos para realizar esta accion."),
    ACCOUNT_BLOCKED(HttpStatusCode.Forbidden, "Tu cuenta esta bloqueada."),
    ACCOUNT_DELETION_PENDING(HttpStatusCode.Forbidden, "Tu cuenta esta pendiente de eliminacion."),
    PROFILE_MISSING(HttpStatusCode.Forbidden, "No se encontro el perfil de tu cuenta."),

    // SLICE 1: auth, perfil, especialidad y borrado de cuenta.
    INVALID_CREDENTIALS(HttpStatusCode.Unauthorized, "El email o la contrasena no son correctos."),
    AUTH_CODE_INVALID(HttpStatusCode.Unauthorized, "El codigo de verificacion no es valido o ya se uso."),
    REAUTH_FAILED(HttpStatusCode.Unauthorized, "No se ha podido confirmar tu identidad."),
    STALE_AUTH(HttpStatusCode.Unauthorized, "Tu sesion es demasiado antigua para esta accion. Vuelve a iniciar sesion."),
    EMAIL_UNVERIFIED(HttpStatusCode.Forbidden, "Tienes que verificar tu email antes de entrar."),
    SUPERADMIN_REQUIRED(HttpStatusCode.Forbidden, "Esta accion requiere un superadministrador."),
    USERNAME_TAKEN(HttpStatusCode.Conflict, "Ese nombre de usuario ya esta en uso."),
    ALREADY_ONBOARDED_CONFLICT(HttpStatusCode.Conflict, "Tu perfil ya estaba completado con otros datos."),
    UNKNOWN_SPECIALTY(HttpStatusCode.UnprocessableEntity, "La especialidad elegida no existe o no esta activa."),
    UNKNOWN_COMMUNITY(HttpStatusCode.UnprocessableEntity, "La comunidad elegida no existe."),
    LEGAL_VERSION_NOT_ACTIVE(HttpStatusCode.UnprocessableEntity, "La version del documento legal ya no esta vigente."),
    DOCUMENT_NOT_ACTIVE(HttpStatusCode.UnprocessableEntity, "Alguno de los documentos legales no existe o no esta vigente."),
    INVALID_REFERRAL_CODE(HttpStatusCode.UnprocessableEntity, "El codigo de referido no es valido."),
    SELF_REFERRAL(HttpStatusCode.UnprocessableEntity, "No puedes usar tu propio codigo de referido."),
    NOT_FOUND(HttpStatusCode.NotFound, "El recurso solicitado no existe."),
    CONFLICT(HttpStatusCode.Conflict, "La operacion entra en conflicto con el estado actual."),
    VERSION_CONFLICT(HttpStatusCode.Conflict, "El recurso ha cambiado mientras lo editabas. Recarga y vuelve a intentarlo."),
    IDEMPOTENCY_KEY_IN_PROGRESS(HttpStatusCode.Conflict, "Ya se esta procesando una peticion con esta clave. Vuelve a intentarlo en un momento."),
    PRECONDITION_FAILED(HttpStatusCode.PreconditionFailed, "El recurso ha cambiado desde la ultima lectura."),
    PAYLOAD_TOO_LARGE(HttpStatusCode.PayloadTooLarge, "La peticion es demasiado grande."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatusCode.UnsupportedMediaType, "El tipo de contenido de la peticion no esta admitido."),
    IDEMPOTENCY_KEY_REUSED(HttpStatusCode.UnprocessableEntity, "Esta clave de idempotencia ya se uso con una peticion distinta."),
    RATE_LIMITED(HttpStatusCode.TooManyRequests, "Demasiadas peticiones. Vuelve a intentarlo en unos segundos."),
    INTERNAL(HttpStatusCode.InternalServerError, "Error interno del servidor."),
    UPSTREAM_ERROR(HttpStatusCode.BadGateway, "Error al consultar el servicio de datos."),
    UPSTREAM_CONTRACT_ERROR(HttpStatusCode.BadGateway, "No se pudo interpretar la respuesta del servicio de datos."),
    UPSTREAM_UNAVAILABLE(HttpStatusCode.ServiceUnavailable, "El servicio no esta disponible ahora mismo. Vuelve a intentarlo en unos segundos."),
    DB_BUSY(HttpStatusCode.ServiceUnavailable, "La base de datos esta ocupada. Vuelve a intentarlo en unos segundos."),
    UPSTREAM_TIMEOUT(HttpStatusCode.GatewayTimeout, "El servicio de datos tardo demasiado en responder."),

    // Intentos: congelados en SLICE 0, implementados en SLICE 3.
    EXAM_NOT_FOUND(HttpStatusCode.NotFound, "El examen no existe."),
    EXAM_LOCKED(HttpStatusCode.Forbidden, "No tienes acceso a este examen."),
    SPECIALTY_TYPE_NOT_ALLOWED(HttpStatusCode.Forbidden, "Este tipo de examen no esta disponible para la especialidad."),
    EXAM_EMPTY(HttpStatusCode.Conflict, "El examen no tiene preguntas."),
    ATTEMPT_NOT_FOUND(HttpStatusCode.NotFound, "El intento no existe."),
    ATTEMPT_NOT_IN_PROGRESS(HttpStatusCode.Conflict, "El intento no esta en curso."),
    ATTEMPT_EXPIRED(HttpStatusCode.Conflict, "El tiempo del intento ha terminado."),
    ATTEMPT_ABANDONED(HttpStatusCode.Conflict, "El intento se abandono."),
    ATTEMPT_ALREADY_FINISHED(HttpStatusCode.Conflict, "El intento ya esta finalizado."),
    ATTEMPT_NOT_FINISHED(HttpStatusCode.Conflict, "El intento todavia no esta finalizado."),
    ANSWER_LOCKED(HttpStatusCode.Conflict, "La respuesta ya no se puede cambiar."),
    REVISION_CONFLICT(HttpStatusCode.Conflict, "La respuesta ha cambiado en otro dispositivo. Recarga el intento."),
    QUESTION_NOT_IN_ATTEMPT(HttpStatusCode.UnprocessableEntity, "La pregunta no pertenece al intento."),
    OPTION_NOT_IN_QUESTION(HttpStatusCode.UnprocessableEntity, "La opcion no pertenece a la pregunta."),
    ;

    fun exception(
        message: String = defaultMessage,
        details: ErrorDetails? = null,
        retryAfterSeconds: Int? = null,
    ): BackendDomainException = BackendDomainException(name, status, message, retryAfterSeconds, details)
}

/** Sinonimos retirados por el rediseno: no pueden emitirse. */
val RETIRED_ERROR_CODES: Set<String> = setOf("EXAM_ACCESS_DENIED", "OPTION_MISMATCH", "ATTEMPT_NOT_OPEN", "ATTEMPT_FINISHED")

/** 400 VALIDATION con `details.issues`. */
fun validationError(vararg issues: ErrorIssue): BackendDomainException =
    CanonicalErrorCode.VALIDATION.exception(details = ErrorDetails(issues = issues.toList()))

/**
 * Codigos que el `StatusPages` publica como el generico [CanonicalErrorCode.DB_BUSY]:
 * los rechazos TRANSITORIOS de la base ya mapeados con `retryAfterSeconds`.
 * Su `error` legacy (`database_deadlock`, ...) se conserva porque el adaptador de
 * OC lo reconoce por nombre para enseñar el mensaje de reintento.
 */
internal val TRANSIENT_DB_ERROR_CODES: Set<String> =
    setOf("DATABASE_DEADLOCK", "DATABASE_LOCK_TIMEOUT", "DATABASE_STATEMENT_TIMEOUT")

private val UPPER_SNAKE = Regex("^[A-Z][A-Z0-9_]*$")

/**
 * `error` legacy -> `code` canonico. Los valores legacy conocidos tienen su
 * traduccion congelada; cualquier otro se normaliza a UPPER_SNAKE.
 */
fun canonicalCodeForLegacyError(error: String): String = when (error) {
    "unauthorized" -> CanonicalErrorCode.UNAUTHENTICATED.name
    "forbidden" -> CanonicalErrorCode.FORBIDDEN.name
    "not_found" -> CanonicalErrorCode.NOT_FOUND.name
    "bad_request" -> CanonicalErrorCode.VALIDATION.name
    "backend_contract_error" -> CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.name
    "backend_supabase_error" -> CanonicalErrorCode.UPSTREAM_ERROR.name
    "backend_internal_error" -> CanonicalErrorCode.INTERNAL.name
    else -> error.uppercase().replace(Regex("[^A-Z0-9_]"), "_")
        .takeIf { it.isUpperSnakeCode() }
        ?: CanonicalErrorCode.INTERNAL.name
}

internal fun String.isUpperSnakeCode(): Boolean = UPPER_SNAKE.matches(this)
