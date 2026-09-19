package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendErrorsTest {
    @Test
    fun mapsKnownSupabaseDomainErrorsWithoutLeakingDbDetails() {
        val result = mapSupabaseDomainError(400, "{\"message\":\"WALLET_IDEMPOTENCY_CONFLICT: payload mismatch\"}")

        assertEquals("WALLET_IDEMPOTENCY_CONFLICT", result?.errorCode)
        assertEquals(HttpStatusCode.Conflict, result?.status)
        assertEquals("La clave de idempotencia ya fue utilizada con otros datos.", result?.message)
    }

    @Test
    fun mapsAnswerIntegrityErrorsToUnprocessableEntity() {
        val result = mapSupabaseDomainError(400, "RECORD_ANSWER_OPTION_NOT_IN_QUESTION")

        assertEquals(HttpStatusCode.UnprocessableEntity, result?.status)
        assertEquals("record_answer_option_not_in_question", result?.errorCode?.lowercase())
    }

    @Test
    fun leavesUnknownSupabaseErrorsForInternalHandling() {
        assertNull(mapSupabaseDomainError(500, "unexpected database failure"))
    }

    @Test
    fun mapsTheLastSuperadminGuardToAConflictAnOperatorCanUnderstand() {
        // Lo que levanta `public.admin_sync_roles`: el cuerpo de PostgREST trae el
        // código desnudo y algo de ruido alrededor.
        val result = mapSupabaseDomainError(
            400,
            "{\"code\":\"P0001\",\"message\":\"ROLE_SYNC_LAST_SUPERADMIN\",\"hint\":null}",
        )

        assertEquals(HttpStatusCode.Conflict, result?.status)
        assertEquals(
            "No puedes retirar este rol porque debe existir al menos un superadministrador.",
            result?.message,
        )
    }

    @Test
    fun mapsTheEmptyRolesGuardToAnUnprocessableEntity() {
        val result = mapSupabaseDomainError(400, "ROLE_SYNC_EMPTY_ROLES")

        assertEquals(HttpStatusCode.UnprocessableEntity, result?.status)
        assertEquals("El usuario debe conservar al menos un rol.", result?.message)
    }

    @Test
    fun anEchoedClientStringCannotFabricateADomainError() {
        // El vector real: `POST /usuarios/ROLE_SYNC_LAST_SUPERADMIN/roles` hacía
        // que Postgres devolviera el identificador dentro de su mensaje de
        // sintaxis, y el backend respondía un 409 con el texto de la guarda sin
        // que ninguna guarda se hubiera ejecutado. Un 409 tiene que seguir
        // significando que la invariante saltó.
        val eco = "{\"code\":\"22P02\",\"message\":\"invalid input syntax for type uuid: " +
            "\\\"ROLE_SYNC_LAST_SUPERADMIN\\\"\"}"

        // Desde F-07 el 22P02 SÍ se traduce, y eso no debilita este caso: lo que
        // tiene que seguir siendo imposible es que el eco fabrique el 409 de la
        // guarda. Sale el 422 de «identificador mal formado», que es lo que pasó.
        val result = mapSupabaseDomainError(400, eco)
        assertEquals("INVALID_TEXT_REPRESENTATION", result?.errorCode)
        assertEquals(HttpStatusCode.UnprocessableEntity, result?.status)
        assertFalse(result?.message.orEmpty().contains("superadministrador"), "${result?.message}")
    }

    @Test
    fun aRealGuardRejectionIsStillTranslated() {
        // Forma que produce `raise exception 'CODIGO'` en PostgREST.
        val real = "{\"code\":\"P0001\",\"message\":\"ROLE_SYNC_LAST_SUPERADMIN\",\"hint\":null}"

        assertEquals(HttpStatusCode.Conflict, mapSupabaseDomainError(400, real)?.status)
    }

    @Test
    fun successfulResponsesAreNeverTranslatedAsDomainErrors() {
        // `httpStatus` era un parámetro sin usar: un 200 cuyo cuerpo contuviera
        // el texto se traducía igual.
        assertNull(mapSupabaseDomainError(200, "{\"message\":\"ROLE_SYNC_LAST_SUPERADMIN\"}"))
    }

    // -----------------------------------------------------------------
    // SQLSTATE: restricciones de la base, no códigos levantados por una RPC
    // -----------------------------------------------------------------

    /**
     * **La prueba de mutación de EXAM-005.**
     *
     * Un código de examen repetido llegaba a la consola como «El servidor no está
     * respondiendo bien»: `23505` no estaba mapeado, salía como
     * `IllegalStateException` y el handler lo redactaba a un 500. El
     * administrador no tenía forma de saber que lo único que pasaba es que
     * tenía que cambiar un campo.
     *
     * Ojo al mecanismo, porque es el motivo de que esto necesitara un camino
     * NUEVO y no una entrada más en el mapa de códigos de dominio: aquél casa por
     * **prefijo del `message`**, y un SQLSTATE no aparece ahí nunca — va en el
     * campo `code`. Reutilizar el `startsWith` no funciona.
     */
    @Test
    fun aDuplicateExamCodeIsAReadableConflictAndNotAFiveHundred() {
        val body = "{\"code\":\"23505\",\"details\":\"Key (codigo_examen)=(EX-1) already exists.\"," +
            "\"hint\":null,\"message\":\"duplicate key value violates unique constraint " +
            "\\\"examenes_codigo_examen_key\\\"\"}"

        val result = mapSupabaseDomainError(409, body)

        assertEquals("EXAM_CODE_DUPLICATE", result?.errorCode)
        assertEquals(HttpStatusCode.Conflict, result?.status)
    }

    /**
     * Y no se escapa nada del cuerpo de Postgres al cliente.
     *
     * El cuerpo de PostgREST trae la restricción, la columna **y el valor
     * repetido**. El mensaje que se publica es una constante: sin ruta, sin id,
     * sin nombre de tabla y sin lo que escribió el administrador (EXAM-004).
     */
    @Test
    fun theDuplicateMessageLeaksNeitherSchemaNorTheValueThatWasSent() {
        val body = "{\"code\":\"23505\",\"details\":\"Key (codigo_examen)=(EX-SECRETO) already exists.\"," +
            "\"message\":\"duplicate key value violates unique constraint \\\"examenes_codigo_examen_key\\\"\"}"

        val message = mapSupabaseDomainError(409, body)?.message.orEmpty()

        assertEquals(false, message.isBlank())
        listOf("EX-SECRETO", "examenes", "codigo_examen", "constraint", "23505", "duplicate key")
            .forEach { leak ->
                assertEquals(
                    false,
                    message.contains(leak, ignoreCase = true),
                    "el mensaje filtra '$leak': $message",
                )
            }
    }

    /**
     * Un 23505 de otra restricción sigue siendo un 409 legible, sólo que
     * genérico.
     *
     * Es la degradación buscada: el nombre real de la restricción en MAIN no se
     * ha leído (`NOT_VERIFIED`), así que si no menciona `codigo_examen` esto cae
     * al mensaje genérico. Lo que NO puede pasar en ningún caso es volver al 500.
     */
    @Test
    fun aUniqueViolationOnAnotherConstraintIsStillAConflict() {
        val body = "{\"code\":\"23505\",\"message\":\"duplicate key value violates unique constraint " +
            "\\\"perfiles_usuario_username_key\\\"\"}"

        val result = mapSupabaseDomainError(409, body)

        assertEquals("UNIQUE_VIOLATION", result?.errorCode)
        assertEquals(HttpStatusCode.Conflict, result?.status)
    }

    /**
     * El CHECK del recuento de preguntas tiene que decir DE QUÉ CAMPO habla.
     *
     * `examenes_total_preguntas_check` exige `>= 0` desde PW-2 (sólo un negativo
     * se rechaza, y desde M1 la API ya no escribe la columna). El mensaje genérico de 23514 —«Alguno de
     * los valores enviados no está permitido para este campo»— deja al
     * administrador delante de un formulario de doce campos sin saber cuál mirar,
     * y encima el campo culpable es de sólo lectura.
     *
     * El código de dominio existe para que la consola pueda pintar EL campo en
     * rojo en vez de una línea al pie. Se distingue del 23514 genérico igual que
     * `EXAM_CODE_DUPLICATE` del 23505: mirando el nombre de la restricción, que lo
     * pone el servidor y no el cliente.
     */
    @Test
    fun theQuestionCountCheckNamesTheFieldInsteadOfSayingSomeValue() {
        val body = "{\"code\":\"23514\",\"details\":\"Failing row contains (…, 0, …).\"," +
            "\"message\":\"new row for relation \\\"examenes\\\" violates " +
            "check constraint \\\"examenes_total_preguntas_check\\\"\"}"

        val result = mapSupabaseDomainError(400, body)

        assertEquals("EXAM_QUESTION_COUNT_INVALID", result?.errorCode)
        assertEquals(HttpStatusCode.UnprocessableEntity, result?.status)
        assertTrue(
            result?.message?.contains("preguntas", ignoreCase = true) == true,
            "El mensaje no nombra el campo: ${result?.message}",
        )
    }

    /**
     * Y sigue sin filtrar esquema ni el valor recibido, igual que el de duplicado.
     */
    @Test
    fun theQuestionCountMessageLeaksNeitherSchemaNorTheValueThatWasSent() {
        val body = "{\"code\":\"23514\",\"details\":\"Failing row contains (06644d1b, ZZ-SECRETO, …, 0, …).\"," +
            "\"message\":\"new row for relation \\\"examenes\\\" violates " +
            "check constraint \\\"examenes_total_preguntas_check\\\"\"}"

        val message = mapSupabaseDomainError(400, body)?.message.orEmpty()

        listOf("examenes", "examenes_total_preguntas_check", "ZZ-SECRETO", "06644d1b", "Failing row").forEach {
            assertFalse(message.contains(it, ignoreCase = true), "El mensaje filtra «$it»: $message")
        }
    }

    /**
     * `23514` es un CHECK de la base rechazando un valor: 422, no 500.
     *
     * Importa para Exámenes porque el vocabulario vivo de `tipo_examen` es
     * desconocido: si el CHECK de MAIN sigue siendo el anterior, guardar un
     * examen «Razonado» se rechaza. Con este mapeo el rechazo se VE, en vez de
     * aparecer como una avería del servidor.
     */
    @Test
    fun aCheckViolationIsAnUnprocessableEntityAndNotAServerFailure() {
        val body = "{\"code\":\"23514\",\"message\":\"new row for relation \\\"examenes\\\" violates " +
            "check constraint \\\"examenes_tipo_examen_check\\\"\"}"

        val result = mapSupabaseDomainError(422, body)

        assertEquals("CHECK_VIOLATION", result?.errorCode)
        assertEquals(HttpStatusCode.UnprocessableEntity, result?.status)
    }

    /**
     * Control de no-regresión: un SQLSTATE que no está en el mapa sigue yendo al
     * camino interno. Sin esto, «todo 4xx de Postgres es un error de dominio» y
     * los fallos de verdad dejarían de tener referencia que investigar.
     */
    @Test
    fun anUnmappedSqlStateIsStillLeftForInternalHandling() {
        val body = "{\"code\":\"42P01\",\"message\":\"relation \\\"inexistente\\\" does not exist\"}"

        assertNull(mapSupabaseDomainError(400, body))
    }

    /**
     * Y el camino de los códigos de dominio sigue teniendo prioridad: un cuerpo
     * que trae los dos —`code` de Postgres y un código levantado con `raise`— se
     * traduce por el segundo, que es el que lleva la intención.
     */
    @Test
    fun aRaisedDomainCodeStillWinsOverItsSqlState() {
        val body = "{\"code\":\"23505\",\"message\":\"WALLET_IDEMPOTENCY_CONFLICT: payload mismatch\"}"

        assertEquals("WALLET_IDEMPOTENCY_CONFLICT", mapSupabaseDomainError(409, body)?.errorCode)
    }

    @Test
    fun theRoleSyncMessagesNeverLeakSqlOrSchemaNames() {
        // §55: ni SQL, ni nombres de tabla, ni PostgREST, ni stacktrace.
        val leaks = listOf(
            "roles_usuario", "roles_app", "admin_sync_roles", "postgrest",
            "P0001", "select", "raise", "public.",
        )

        listOf(
            "ROLE_SYNC_LAST_SUPERADMIN",
            "ROLE_SYNC_EMPTY_ROLES",
            "ROLE_SYNC_UNKNOWN_ROLE",
            "ROLE_SYNC_INVALID_USER",
        ).forEach { code ->
            val message = mapSupabaseDomainError(400, code)?.message.orEmpty()
            assertEquals(false, message.isBlank(), "El código $code no tiene mensaje humano.")
            leaks.forEach { leak ->
                assertEquals(
                    false,
                    message.contains(leak, ignoreCase = true),
                    "El mensaje de $code filtra '$leak': $message",
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // 23503 · foreign_key_violation — la red del borrado real de examen
    // -----------------------------------------------------------------

    /**
     * **La prueba de mutación de la red de seguridad del borrado.**
     *
     * `DELETE /api/v1/admin/examenes/{id}` borra de verdad, y antes cuenta los
     * dependientes de las seis tablas de datos de usuario. Esa guarda cubre lo
     * que conocemos; `23503` cubre lo que no: un dependiente que nadie
     * inventarió —otra tabla, un `on delete restrict` que no esperábamos— hace
     * que la base rechace el borrado, y sin este mapeo el rechazo salía como
     * `IllegalStateException` → 500 redactado. «Error al consultar el servicio de
     * datos» ante un examen que simplemente tiene cosas colgando dice «el
     * servidor está roto», y encima es indistinguible de un Supabase caído de
     * verdad.
     *
     * Va por SQLSTATE y no por prefijo del mensaje por lo mismo que 23505 y
     * 23514: el código lo pone el SERVIDOR, en el campo `code`.
     */
    @Test
    fun aForeignKeyViolationIsAReadableConflictAndNotAFiveHundred() {
        val body = "{\"code\":\"23503\",\"details\":\"Key (id)=(3f1e) is still referenced from table " +
            "\\\"intentos_examen\\\".\",\"message\":\"update or delete on table \\\"examenes\\\" violates " +
            "foreign key constraint \\\"intentos_examen_examen_id_fkey\\\"\"}"

        val result = mapSupabaseDomainError(409, body)

        assertEquals("FOREIGN_KEY_VIOLATION", result?.errorCode)
        assertEquals(HttpStatusCode.Conflict, result?.status)
    }

    /**
     * Y no filtra el esquema: el cuerpo de un 23503 trae la tabla que referencia,
     * el nombre de la restricción **y el identificador de la fila**. Lo que se
     * publica es una constante (EXAM-004).
     */
    @Test
    fun theForeignKeyMessageLeaksNeitherSchemaNorTheReferencedIdentifier() {
        val body = "{\"code\":\"23503\",\"details\":\"Key (id)=(SECRETO-42) is still referenced from table " +
            "\\\"intentos_examen\\\".\",\"message\":\"update or delete on table \\\"examenes\\\" violates " +
            "foreign key constraint \\\"intentos_examen_examen_id_fkey\\\"\"}"

        val message = mapSupabaseDomainError(409, body)?.message.orEmpty()

        assertEquals(false, message.isBlank())
        listOf("SECRETO-42", "examenes", "intentos_examen", "constraint", "23503", "foreign key")
            .forEach { leak ->
                assertEquals(
                    false,
                    message.contains(leak, ignoreCase = true),
                    "el mensaje filtra '$leak': $message",
                )
            }
    }

    /** Y dice qué hacer en su lugar, que es lo único accionable: archivar. */
    @Test
    fun theForeignKeyMessageSaysWhatToDoInstead() {
        val message = mapSupabaseDomainError(
            409,
            "{\"code\":\"23503\",\"message\":\"violates foreign key constraint\"}",
        )?.message.orEmpty()

        assertEquals(true, message.contains("Archívalo", ignoreCase = true), message)
    }

    // ---------------------------------------------------------------
    // La CLASE, no sólo la instancia. Los cuatro huecos que quedaban.
    // ---------------------------------------------------------------

    @Test
    fun noSuccessStatusIsEverTranslatedNotJustTwoHundred() {
        // La puerta de estado sólo estaba fijada en 200. Cambiarla por
        // `httpStatus == 200` dejaba pasar 201, 204 y 304 sin que nada fallara.
        val cuerpo = "{\"message\":\"ROLE_SYNC_LAST_SUPERADMIN\"}"

        listOf(200, 201, 202, 204, 301, 304, 399).forEach { status ->
            assertNull(
                mapSupabaseDomainError(status, cuerpo),
                "Un $status no puede convertirse en error de dominio.",
            )
        }
        // Y el primer estado de error sí traduce: la frontera está en 400.
        assertEquals(HttpStatusCode.Conflict, mapSupabaseDomainError(400, cuerpo)?.status)
    }

    @Test
    fun aDomainCodeWrittenInLowercaseByTheDatabaseIsStillTranslated() {
        // `ignoreCase = true` no lo ejercitaba ningún test: cambiarlo a `false`
        // dejaba de traducir en silencio y el operador veía un 500 genérico en
        // lugar del mensaje de la guarda. Esto fija el comportamiento actual.
        val result = mapSupabaseDomainError(400, "{\"message\":\"role_sync_last_superadmin\"}")

        assertEquals(HttpStatusCode.Conflict, result?.status)
        assertEquals("ROLE_SYNC_LAST_SUPERADMIN", result?.errorCode)
    }

    @Test
    fun aDomainCodePlantedInANonCanonicalFieldIsNotTranslated() {
        // `details` y `hint` sí los escribe Postgres, pero pueden arrastrar la
        // entrada del cliente. El código sólo cuenta si ENCABEZA `message`.
        val cuerpo = "{\"code\":\"22P02\"," +
            "\"details\":\"ROLE_SYNC_LAST_SUPERADMIN\"," +
            "\"hint\":\"ROLE_SYNC_EMPTY_ROLES\"," +
            "\"message\":\"invalid input syntax for type uuid\"}"

        // Ni ROLE_SYNC_LAST_SUPERADMIN ni ROLE_SYNC_EMPTY_ROLES: sólo el 22P02
        // que de verdad devolvió la base (mapeado desde F-07).
        assertEquals("INVALID_TEXT_REPRESENTATION", mapSupabaseDomainError(400, cuerpo)?.errorCode)
    }

    // ---------------------------------------------------------------
    // 22P02 · identificador mal formado (F-07)
    // ---------------------------------------------------------------

    /**
     * Un id que no es UUID llegaba a una columna `uuid` y Postgres respondía
     * 22P02, que no estaba en el mapa: `IllegalStateException` → 500. Es un error
     * de la petición, no del servidor.
     */
    @Test
    fun aMalformedIdentifierIsAnUnprocessableEntityAndNotAServerFailure() {
        val body = "{\"code\":\"22P02\",\"details\":null,\"hint\":null," +
            "\"message\":\"invalid input syntax for type uuid: \\\"no-soy-un-uuid\\\"\"}"

        val result = mapSupabaseDomainError(400, body)

        assertEquals("INVALID_TEXT_REPRESENTATION", result?.errorCode)
        assertEquals(HttpStatusCode.UnprocessableEntity, result?.status)
        assertNull(result?.retryAfterSeconds, "Repetir un id mal formado da el mismo 422: no se invita a reintentar.")
        listOf("no-soy-un-uuid", "uuid", "22P02", "invalid input").forEach { leak ->
            assertFalse(result?.message.orEmpty().contains(leak, ignoreCase = true), "filtra '$leak': ${result?.message}")
        }
    }

    // ---------------------------------------------------------------
    // Rechazos transitorios: 40P01 · 55P03 · 57014
    // ---------------------------------------------------------------

    /**
     * Bloqueo mutuo, cerrojo no disponible y sentencia cancelada por tiempo.
     *
     * MAIN corre PostgREST con `statement_timeout = 8s` y `lock_timeout = 8s`, así
     * que los tres ocurren de verdad. Antes eran un 500 genérico —«el servidor
     * está roto»— cuando el servicio funciona y repetir la petición puede salir
     * bien. Tienen que llegar tipados, como 503 y con cuánto esperar.
     */
    @Test
    fun theThreeTransientDatabaseRejectionsAreRetryable503sWithAHumanMessage() {
        mapOf(
            "40P01" to "DATABASE_DEADLOCK",
            "55P03" to "DATABASE_LOCK_TIMEOUT",
            "57014" to "DATABASE_STATEMENT_TIMEOUT",
        ).forEach { (sqlState, expectedCode) ->
            val body = "{\"code\":\"$sqlState\",\"details\":null,\"hint\":null," +
                "\"message\":\"canceling statement due to lock timeout on relation \\\"examenes\\\"\"}"

            val result = mapSupabaseDomainError(500, body)

            assertEquals(expectedCode, result?.errorCode, "SQLSTATE $sqlState")
            assertEquals(HttpStatusCode.ServiceUnavailable, result?.status, "SQLSTATE $sqlState")
            val retryAfter = result?.retryAfterSeconds
            assertTrue(retryAfter != null && retryAfter > 0, "$sqlState tiene que decir cuándo reintentar: $retryAfter")
            val message = result?.message.orEmpty()
            assertTrue(message.contains("Vuelve a intentarlo"), "$sqlState no invita a reintentar: $message")
            listOf("examenes", sqlState, "canceling", "lock timeout", "relation").forEach { leak ->
                assertFalse(message.contains(leak, ignoreCase = true), "$sqlState filtra '$leak': $message")
            }
        }
    }

    /**
     * Con los cuerpos REALES de PostgREST y con cualquier estado HTTP: la decisión la
     * toma el campo `code`, no el estado ni el texto. El 40P01 real trae `details`
     * con números de proceso y `hint` con una pista del servidor; nada de eso puede
     * llegar al mensaje. Y el mismo texto con otro código no se traduce.
     */
    @Test
    fun theTransientRejectionsAreDecidedByTheCodeFieldWithRealBodiesAndAnyStatus() {
        val cuerpos = mapOf(
            "40P01" to "{\"code\":\"40P01\",\"details\":\"Process 123 waits for ShareLock on transaction 456; " +
                "blocked by process 789.\",\"hint\":\"See server log for query details.\",\"message\":\"deadlock detected\"}",
            "55P03" to "{\"code\":\"55P03\",\"details\":null,\"hint\":null," +
                "\"message\":\"canceling statement due to lock timeout\"}",
            "57014" to "{\"code\":\"57014\",\"details\":null,\"hint\":null," +
                "\"message\":\"canceling statement due to statement timeout\"}",
        )
        cuerpos.forEach { (sqlState, cuerpo) ->
            listOf(500, 503, 504).forEach { status ->
                val result = mapSupabaseDomainError(status, cuerpo)
                assertEquals(HttpStatusCode.ServiceUnavailable, result?.status, "$sqlState con HTTP $status")
                val message = result?.message.orEmpty()
                listOf("Process 123", "ShareLock", "server log", "deadlock detected", "canceling statement").forEach { leak ->
                    assertFalse(message.contains(leak, ignoreCase = true), "$sqlState filtra '$leak': $message")
                }
            }
        }
        assertNull(
            mapSupabaseDomainError(500, "{\"code\":\"XX000\",\"message\":\"canceling statement due to statement timeout\"}"),
            "el texto de un timeout con otro código no es un timeout: se decide por `code`",
        )
    }

    /** Y los errores de dominio siguen sin `Retry-After`: repetir un 409 da el mismo 409. */
    @Test
    fun aDefinitiveRejectionCarriesNoRetryAfter() {
        val unique = "{\"code\":\"23505\",\"message\":\"duplicate key value violates unique constraint \\\"x\\\"\"}"

        assertNull(mapSupabaseDomainError(409, unique)?.retryAfterSeconds)
        assertNull(mapSupabaseDomainError(400, "ROLE_SYNC_LAST_SUPERADMIN")?.retryAfterSeconds)
    }

    @Test
    fun aCodeBuriedInsideANonJsonBodyIsNotTranslated() {
        // Cuando no hay campo `message`, el sujeto es el cuerpo entero. Eso es
        // deliberado —PostgREST no siempre devuelve JSON— pero sigue exigiendo
        // que el código vaya al principio, no en cualquier parte.
        assertNull(
            mapSupabaseDomainError(500, "upstream failed: ROLE_SYNC_LAST_SUPERADMIN was mentioned"),
        )
        assertEquals(
            HttpStatusCode.Conflict,
            mapSupabaseDomainError(500, "ROLE_SYNC_LAST_SUPERADMIN")?.status,
        )
    }

    // ---------------------------------------------------------------
    // K0-4 · KQ0-1 · Códigos de Exámenes y Preguntas
    // ---------------------------------------------------------------

    /**
     * Cada código que levantarán M3, M4, Q1, Q2 y Q4, con el cuerpo REAL que
     * PostgREST devuelve para un `raise exception '<CÓDIGO>'` (P0001). Se declaran
     * antes de aplicar esas migraciones: sin esto, su rechazo salía como un 500
     * redactado —«el servidor está roto»— en vez de decir qué corregir.
     *
     * La lista es la de los contratos, a mano y no leída del mapa: un código que
     * se borrara del mapa tiene que poner esto en rojo.
     */
    @Test
    fun everyCodeTheExamAndQuestionMigrationsRaiseIsAReadableRejection() {
        val esperados = mapOf(
            // M3 · guardas en los padres
            "EXAM_TYPE_LOCKED_BY_QUESTIONS" to HttpStatusCode.Conflict,
            "QUESTION_TYPE_LOCKED_BY_EXAMS" to HttpStatusCode.Conflict,
            "QUESTION_TYPE_LOCKED_BY_RESOURCES" to HttpStatusCode.Conflict,
            "QUESTION_EXAM_TYPE_MISMATCH" to HttpStatusCode.UnprocessableEntity,
            // M4 · vínculo, orden y desvínculo
            "EXAM_NOT_FOUND" to HttpStatusCode.NotFound,
            "QUESTION_NOT_FOUND" to HttpStatusCode.NotFound,
            "QUESTION_NOT_LINKED_TO_EXAM" to HttpStatusCode.NotFound,
            "QUESTION_ARCHIVED" to HttpStatusCode.UnprocessableEntity,
            "EXAM_QUESTION_LIMIT_REACHED" to HttpStatusCode.UnprocessableEntity,
            "EXAM_QUESTION_POSITION_INVALID" to HttpStatusCode.UnprocessableEntity,
            "EXAM_REORDER_SET_MISMATCH" to HttpStatusCode.Conflict,
            // Q1 · código de pregunta
            "QUESTION_CODE_IMMUTABLE" to HttpStatusCode.Conflict,
            // Q2 · alta y edición de preguntas
            "QUESTION_ID_REQUIRED" to HttpStatusCode.UnprocessableEntity,
            "QUESTION_ID_CONFLICT" to HttpStatusCode.Conflict,
            "QUESTION_STATEMENT_REQUIRED" to HttpStatusCode.UnprocessableEntity,
            "QUESTION_TYPE_UNKNOWN" to HttpStatusCode.UnprocessableEntity,
            "QUESTION_DIFFICULTY_UNKNOWN" to HttpStatusCode.UnprocessableEntity,
            "QUESTION_OPTIONS_INVALID" to HttpStatusCode.UnprocessableEntity,
            "QUESTION_CORRECT_ANSWER_INVALID" to HttpStatusCode.UnprocessableEntity,
            "QUESTION_PATCH_INVALID" to HttpStatusCode.BadRequest,
            "QUESTION_SOLUTION_MISSING" to HttpStatusCode.UnprocessableEntity,
            // «Todavía no disponible» (falta M4 / IM3): 409 y NO 5xx a propósito, para
            // que el cuerpo llegue al administrador (el adaptador de red del cliente
            // descarta el cuerpo de cualquier 5xx). Ver BackendErrors.kt.
            "QUESTION_EXAM_LINK_UNAVAILABLE" to HttpStatusCode.Conflict,
            "QUESTION_EXAM_UNLINK_UNAVAILABLE" to HttpStatusCode.Conflict,
            "EXAM_QUESTION_REORDER_UNAVAILABLE" to HttpStatusCode.Conflict,
            "IMPORT_WRITE_RPC_NOT_PROVISIONED" to HttpStatusCode.Conflict,
            "IMPORT_REVIEW_STATE_UNAVAILABLE" to HttpStatusCode.Conflict,
            // Q4 · examen publicado vacío
            "EXAM_PUBLISHED_WOULD_BE_EMPTY" to HttpStatusCode.Conflict,
            // Guarda de la base de DEC-EX-01 (acompaña a K-DEC01)
            "EXAM_PUBLISH_REQUIRES_QUESTIONS" to HttpStatusCode.Conflict,
            // Q3 / KQ4 · recursos de pregunta
            "QUESTION_RESOURCE_NOT_FOUND" to HttpStatusCode.NotFound,
            "QUESTION_RESOURCE_FILE_REQUIRED" to HttpStatusCode.BadRequest,
            "QUESTION_RESOURCE_TYPE_INVALID" to HttpStatusCode.UnprocessableEntity,
            "QUESTION_RESOURCE_TOO_LARGE" to HttpStatusCode.PayloadTooLarge,
            "QUESTION_RESOURCE_UPLOAD_REQUIRED" to HttpStatusCode.UnprocessableEntity,
            "QUESTION_RESOURCE_URL_INVALID" to HttpStatusCode.UnprocessableEntity,
            // IM2 / IM3 · importaciones (KI0-6)
            "IMPORT_LOTE_ID_REQUIRED" to HttpStatusCode.BadRequest,
            "IMPORT_LOTE_NOT_FOUND" to HttpStatusCode.NotFound,
            "IMPORT_LOTE_STATE_INVALID" to HttpStatusCode.Conflict,
            "IMPORT_STAGING_NOT_FOUND" to HttpStatusCode.NotFound,
            "IMPORT_PENDING_REVIEW" to HttpStatusCode.Conflict,
            "IMPORT_ROW_INCOMPLETE" to HttpStatusCode.UnprocessableEntity,
            "IMPORT_DUPLICATES_UNRESOLVED" to HttpStatusCode.Conflict,
            "IMPORT_LINK_TARGET_INVALID" to HttpStatusCode.UnprocessableEntity,
            "IMPORT_TYPE_MISMATCH" to HttpStatusCode.UnprocessableEntity,
            "IMPORT_EXAM_NOT_FOUND" to HttpStatusCode.NotFound,
            "IMPORT_EXAM_TYPE_UNKNOWN" to HttpStatusCode.UnprocessableEntity,
            "IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED" to HttpStatusCode.Conflict,
            "STAGING_ROW_ALREADY_IMPORTED" to HttpStatusCode.Conflict,
        )
        esperados.forEach { (codigo, estado) ->
            val cuerpo = "{\"code\":\"P0001\",\"details\":null,\"hint\":null,\"message\":\"$codigo\"}"

            val result = mapSupabaseDomainError(400, cuerpo)

            assertEquals(codigo, result?.errorCode, "no se traduce $codigo")
            assertEquals(estado, result?.status, codigo)
            val mensaje = result?.message.orEmpty()
            assertTrue(mensaje.isNotBlank(), "$codigo sin mensaje")
            listOf(codigo, "P0001", "raise", "preguntas_examen", "public.").forEach { leak ->
                assertFalse(mensaje.contains(leak, ignoreCase = true), "$codigo filtra '$leak': $mensaje")
            }
        }
    }

    /**
     * DEC-EX-01 llega por dos puertas —el filtro de `publicarExamen` y la guarda de la
     * base— y la consola tiene que ver UNA sola respuesta: mismo código, mismo 409 y
     * mismo texto. También el texto de C para un id de pregunta con otro contenido.
     */
    @Test
    fun anEmptyExamIsTheSameConflictWhicheverDoorRejectsIt() {
        val deLaBase = mapSupabaseDomainError(
            400,
            "{\"code\":\"P0001\",\"details\":null,\"hint\":null,\"message\":\"EXAM_PUBLISH_REQUIRES_QUESTIONS\"}",
        )
        val deKotlin = examPublishRequiresQuestionsException()

        assertEquals(deKotlin.errorCode, deLaBase?.errorCode)
        assertEquals(HttpStatusCode.Conflict, deKotlin.status)
        assertEquals(deKotlin.status, deLaBase?.status)
        assertEquals("No se puede publicar un examen sin preguntas activas.", deKotlin.message)
        assertEquals(deKotlin.message, deLaBase?.message)
        assertEquals(
            "Esta pregunta ya existe con otro contenido; recarga antes de volver a guardar.",
            mapSupabaseDomainError(400, "{\"code\":\"P0001\",\"message\":\"QUESTION_ID_CONFLICT\"}")?.message,
        )
    }

    /**
     * Q2-4 · Un 23503 al ESCRIBIR no es un 23503 al BORRAR. Una pregunta con un autor
     * o un lote que ya no existen salía con «No se puede borrar este registro…», que
     * no tiene nada que ver con lo que pasó. Se distingue por el texto de Postgres y,
     * dentro, por el NOMBRE de la restricción.
     */
    @Test
    fun aMissingReferenceOnWriteIsNotTheDeletionConflict() {
        fun alEscribir(restriccion: String) =
            "{\"code\":\"23503\",\"details\":\"Key (x)=(SECRETO-99) is not present in table \\\"users\\\".\"," +
                "\"hint\":null,\"message\":\"insert or update on table \\\"preguntas\\\" violates foreign key " +
                "constraint \\\"$restriccion\\\"\"}"

        mapOf(
            "preguntas_creado_por_fkey" to "AUTHOR_NOT_FOUND",
            "examenes_creado_por_fkey" to "AUTHOR_NOT_FOUND",
            "preguntas_lote_importacion_id_fkey" to "IMPORT_BATCH_NOT_FOUND",
            "examenes_especialidad_id_fkey" to "EXAM_SPECIALTY_NOT_FOUND",
            "otra_restriccion_fkey" to "REFERENCE_NOT_FOUND",
        ).forEach { (restriccion, codigo) ->
            val result = mapSupabaseDomainError(409, alEscribir(restriccion))

            assertEquals(codigo, result?.errorCode, restriccion)
            assertEquals(HttpStatusCode.UnprocessableEntity, result?.status, restriccion)
            val mensaje = result?.message.orEmpty()
            listOf("borrar", "Archívalo", "SECRETO-99", restriccion, "preguntas", "foreign key").forEach { leak ->
                assertFalse(mensaje.contains(leak, ignoreCase = true), "$restriccion filtra '$leak': $mensaje")
            }
        }
        // Control: el borrado con dependientes sigue siendo el 409 de siempre.
        val alBorrar = "{\"code\":\"23503\",\"message\":\"update or delete on table \\\"examenes\\\" violates " +
            "foreign key constraint \\\"intentos_examen_examen_id_fkey\\\" on table \\\"intentos_examen\\\"\"}"
        assertEquals("FOREIGN_KEY_VIOLATION", mapSupabaseDomainError(409, alBorrar)?.errorCode)
    }

    /**
     * KI0-4: mientras IM1 no esté aplicada, el CHECK vivo de `estado_parseo` rechaza
     * `aprobada`/`omitida` con un 23514. Se traduce por el NOMBRE de la restricción
     * en `message`, nunca por `details`, que trae la fila entera con texto del PDF.
     */
    @Test
    fun theLiveStagingStateCheckIsAReadableUnavailableError() {
        val body = "{\"code\":\"23514\",\"details\":\"Failing row contains (x-probe-c, SECRETO).\"," +
            "\"hint\":null,\"message\":\"new row for relation \\\"preguntas_parseadas_staging\\\" violates " +
            "check constraint \\\"preguntas_parseadas_staging_estado_parseo_check\\\"\"}"

        val result = mapSupabaseDomainError(400, body)

        assertEquals("IMPORT_REVIEW_STATE_UNAVAILABLE", result?.errorCode)
        assertEquals(HttpStatusCode.Conflict, result?.status)
        listOf("estado_parseo_check", "x-probe-c", "SECRETO", "Failing row").forEach { leak ->
            assertFalse(result?.message.orEmpty().contains(leak, ignoreCase = true), "filtra '$leak': ${result?.message}")
        }
        // Control: otro CHECK cualquiera sigue siendo la violación genérica.
        val otro = body.replace("preguntas_parseadas_staging_estado_parseo_check", "otra_restriccion_chk")
        assertEquals("CHECK_VIOLATION", mapSupabaseDomainError(400, otro)?.errorCode)
    }

    /** `domainError(code)` devuelve exactamente lo que devolvería la base para ese código; uno desconocido revienta. */
    @Test
    fun domainErrorMirrorsTheMapAndRejectsUnknownCodes() {
        val fromMap = mapSupabaseDomainError(400, "{\"code\":\"P0001\",\"message\":\"IMPORT_PENDING_REVIEW\"}")
        val direct = domainError("IMPORT_PENDING_REVIEW")

        assertEquals(fromMap?.errorCode, direct.errorCode)
        assertEquals(fromMap?.status, direct.status)
        assertEquals(fromMap?.message, direct.message)
        assertFailsWith<IllegalStateException> { domainError("CODIGO_QUE_NO_EXISTE") }
    }

    /**
     * La invariante de la que depende el mapeo: ninguna clave es prefijo de otra.
     * El mapeo casa por `startsWith`, así que un prefijo traduciría el código
     * largo con el mensaje del corto sin que nada avisara.
     */
    @Test
    fun noDomainCodeIsAPrefixOfAnother() {
        val codigos = supabaseDomainErrorCodes()

        val prefijos = codigos.flatMap { corto -> codigos.filter { largo -> largo != corto && largo.startsWith(corto) }.map { corto to it } }

        assertTrue(prefijos.isEmpty(), "códigos que son prefijo de otro: $prefijos")
    }

    /**
     * M5: `examenes_codigo_examen_no_vacio_chk`. Mismo código y mismo mensaje que
     * el rechazo previo del PATCH ([examCodeRequiredException]), para que la consola
     * marque el campo Código venga por la puerta que venga.
     */
    @Test
    fun aBlankExamCodeRejectedByTheDatabaseIsTheSameReadableError() {
        val body = "{\"code\":\"23514\",\"details\":\"Failing row contains (336071a5, , ZZ-SECRETO).\"," +
            "\"hint\":null,\"message\":\"new row for relation \\\"examenes\\\" violates " +
            "check constraint \\\"examenes_codigo_examen_no_vacio_chk\\\"\"}"

        val result = mapSupabaseDomainError(400, body)

        assertEquals(EXAM_CODE_REQUIRED_CODE, result?.errorCode)
        assertEquals(HttpStatusCode.UnprocessableEntity, result?.status)
        assertEquals(examCodeRequiredException().message, result?.message)
        listOf("examenes_codigo_examen_no_vacio_chk", "ZZ-SECRETO", "336071a5", "Failing row").forEach { leak ->
            assertFalse(result?.message.orEmpty().contains(leak, ignoreCase = true), "filtra '$leak': ${result?.message}")
        }
    }
}
