package com.oposicontrol.backend.openapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests estaticos del subconjunto OpenAPI canonico (`openapi/`, SLICE 0, contratos FROZEN).
 *
 * No arrancan el backend: leen los YAML con [OpenApiMiniYaml], resuelven `$ref` entre ficheros
 * y comprueban las reglas congeladas del rediseno (rutas, operationId, vocabulario canonico,
 * autoridad del servidor, idempotencia, paginacion y sobre de error).
 */
class OpenApiContractTest {

    private val docs = OpenApiDocs.locate()
    private val operations = docs.operations()

    // --- Frozen input -------------------------------------------------------------------------

    /** contractId -> "METHOD path" de todos los contratos FROZEN con ruta HTTP (D7-25 es in-process). */
    private val frozenRoutes = mapOf(
        "D8-01" to "GET /api/v1/admin/auditoria",
        "D8-06" to "GET /api/v1/admin/estado-sistema",
        "D8-07" to "GET /health/live",
        "D8-08" to "GET /health/ready",
        "D8-09" to "GET /metrics",
        "D1-01" to "POST /v1/auth/register",
        "D1-02" to "POST /v1/auth/email/exchange",
        "D1-03" to "POST /v1/auth/email/resend",
        "D1-04" to "POST /v1/auth/login",
        "D1-05" to "POST /v1/auth/oauth/apple",
        "D1-06" to "POST /v1/auth/oauth/google",
        "D1-07" to "POST /v1/auth/refresh",
        "D1-08" to "POST /v1/auth/logout",
        "D1-09" to "POST /v1/auth/recover",
        "D1-10" to "POST /v1/auth/reset-password",
        "D1-11" to "GET /api/v1/app/me",
        "D1-12" to "POST /api/v1/app/me/onboarding",
        "D1-13" to "PATCH /api/v1/app/me/profile",
        "D1-14" to "PUT /api/v1/app/me/avatar",
        "D1-15" to "DELETE /api/v1/app/me/avatar",
        "D1-16" to "GET /api/v1/app/legal/documents",
        "D1-17" to "POST /api/v1/app/me/legal-acceptances",
        "D1-18" to "POST /api/v1/app/me/credentials",
        "D1-19" to "DELETE /api/v1/app/me",
        "D1-21" to "GET /api/v1/admin/me",
        "D1-22" to "GET /api/v1/admin/usuarios",
        "D1-23" to "GET /api/v1/admin/usuarios/{id}",
        "D1-24" to "GET /api/v1/admin/usuarios/{id}/detalle",
        "D1-25" to "PATCH /api/v1/admin/usuarios/{id}",
        "D1-26" to "POST /api/v1/admin/usuarios/{id}/roles",
        "D1-27" to "POST /api/v1/admin/usuarios/{id}/bloqueo",
        "D1-28" to "DELETE /api/v1/admin/usuarios/{id}/bloqueo",
        "D1-29" to "GET /api/v1/admin/roles",
        "D1-30" to "POST /api/v1/admin/legal/documents",
        "D2-01" to "GET /api/v1/app/examenes",
        "D2-02" to "GET /api/v1/app/examenes/{examId}",
        "D2-13" to "GET /api/v1/app/me/examenes",
        "D2-15" to "GET /api/v1/public/examenes",
        "D2-16" to "GET /api/v1/public/examenes/{examId}",
        "D2-17" to "GET /api/v1/public/especialidades",
        "D2-20" to "GET /api/v1/admin/examenes",
        "D2-21" to "GET /api/v1/admin/examenes/{examId}",
        "D2-22" to "POST /api/v1/admin/examenes",
        "D2-23" to "PATCH /api/v1/admin/examenes/{examId}",
        "D2-24" to "DELETE /api/v1/admin/examenes/{examId}",
        "D2-25" to "POST /api/v1/admin/examenes/{examId}/publicar",
        "D2-26" to "POST /api/v1/admin/examenes/{examId}/despublicar",
        "D2-27" to "POST /api/v1/admin/examenes/{examId}/archivar",
        "D2-28" to "POST /api/v1/admin/examenes/{examId}/portada",
        "D2-29" to "DELETE /api/v1/admin/examenes/{examId}/portada",
        "D2-30" to "GET /api/v1/admin/examenes/{examId}/preguntas",
        "D2-31" to "POST /api/v1/admin/preguntas/{preguntaId}/examenes",
        "D2-32" to "DELETE /api/v1/admin/preguntas/{preguntaId}/examenes/{examId}",
        "D2-33" to "PATCH /api/v1/admin/examenes/{examId}/preguntas/{preguntaId}",
        "D2-34" to "PATCH /api/v1/admin/examenes/{examId}/preguntas/orden",
        "D2-35" to "GET /api/v1/admin/preguntas",
        "D2-36" to "GET /api/v1/admin/preguntas/{preguntaId}",
        "D2-37" to "GET /api/v1/admin/preguntas/{preguntaId}/examenes",
        "D2-38" to "POST /api/v1/admin/preguntas",
        "D2-39" to "PATCH /api/v1/admin/preguntas/{preguntaId}",
        "D2-40" to "DELETE /api/v1/admin/preguntas/{preguntaId}",
        "D2-41" to "GET /api/v1/admin/especialidades",
        "D2-42" to "POST /api/v1/admin/especialidades",
        "D2-43" to "PATCH /api/v1/admin/especialidades/{especialidadId}",
        "D2-44" to "DELETE /api/v1/admin/especialidades/{especialidadId}",
        "D2-45" to "GET /api/v1/admin/especialidades/{especialidadId}/capacidades",
        "D2-46" to "PUT /api/v1/admin/especialidades/{especialidadId}/capacidades",
        "D2-47" to "GET /api/v1/admin/comunidades-autonomas",
        "D2-48" to "POST /api/v1/admin/comunidades-autonomas",
        "D2-49" to "PATCH /api/v1/admin/comunidades-autonomas/{comunidadId}",
        "D2-50" to "DELETE /api/v1/admin/comunidades-autonomas/{comunidadId}",
        "D2-51" to "GET /api/v1/admin/etiquetas",
        "D2-52" to "POST /api/v1/admin/etiquetas",
        "D2-53" to "PATCH /api/v1/admin/etiquetas/{etiquetaId}",
        "D2-54" to "DELETE /api/v1/admin/etiquetas/{etiquetaId}",
        "D2-55" to "GET /api/v1/admin/importaciones/preguntas",
        "D2-56" to "GET /api/v1/admin/importaciones/preguntas/{loteId}",
        "D2-57" to "POST /api/v1/admin/importaciones/preguntas",
        "D2-58" to "POST /api/v1/admin/importaciones/preguntas/pdf",
        "D2-59" to "PATCH /api/v1/admin/importaciones/staging/{stagingId}",
        "D2-60" to "POST /api/v1/admin/importaciones/{loteId}/importar",
        "D2-61" to "GET /api/v1/admin/accesos",
        "D2-62" to "POST /api/v1/admin/accesos",
        "D2-63" to "POST /api/v1/admin/accesos/{grantId}/revocar",
        "D7-02" to "GET /api/v1/app/me/entitlement",
        "D2-03" to "POST /api/v1/app/examenes/{examId}/intentos",
        "D2-05" to "GET /api/v1/app/reto-diario",
        "D2-06" to "POST /api/v1/app/reto-diario/{retoId}/intentos",
        "D2-07" to "GET /api/v1/app/intentos/{attemptId}",
        "D2-08" to "GET /api/v1/app/intentos/{attemptId}/preguntas",
        "D2-09" to "PUT /api/v1/app/intentos/{attemptId}/respuestas/{preguntaId}",
        "D2-10" to "POST /api/v1/app/intentos/{attemptId}/finalizar",
        "D2-11" to "POST /api/v1/app/intentos/{attemptId}/abandonar",
        "D2-12" to "GET /api/v1/app/intentos/{attemptId}/resultado",
        "D2-65" to "GET /api/v1/app/intentos/{attemptId}/revision",
    )

    /** Contratos con `Idempotency-Key!` (REQUIRED) o `Idempotency-Key?` (OPTIONAL). */
    private val idempotencyRequired = setOf("D1-18", "D2-22", "D2-38", "D2-57", "D2-58", "D2-62", "D2-03", "D2-09")
    private val idempotencyOptional = setOf("D2-06", "D2-10")

    /** contractId -> (max, default) del limit de las listas paginadas. */
    private val cursorLists = mapOf(
        "D8-01" to (200L to 50L),
        "D2-01" to (50L to 20L),
        "D2-13" to (50L to 20L),
        "D2-15" to (50L to 20L),
        "D2-41" to (100L to 50L),
        "D2-08" to (20L to 10L),
        "D2-65" to (50L to 20L),
    )
    private val offsetLists = mapOf(
        "D1-22" to (200L to 50L),
        "D2-20" to (100L to 25L),
        "D2-35" to (100L to 25L),
        "D2-37" to (100L to 50L),
        "D2-51" to (200L to 100L),
        "D2-55" to (100L to 25L),
        "D2-61" to (100L to 25L),
    )
    private val nestedCursorLists = setOf("D2-56")

    private val examTypes = listOf("rafaga", "razonado", "inmersivo")
    private val questionTypes = listOf("rafaga", "razonada", "inmersiva")

    // --- Structure ------------------------------------------------------------------------------

    @Test
    fun everyRefResolvesAndEveryFileParses() {
        val problems = mutableListOf<String>()
        docs.walkAllMaps { map, file ->
            val ref = map["\$ref"] as? String ?: return@walkAllMaps
            runCatching { docs.resolveRef(ref, file) }.onFailure { problems += it.message.orEmpty() }
        }
        assertTrue(problems.isEmpty(), "Unresolvable \$ref:\n" + problems.joinToString("\n"))
    }

    @Test
    fun pathsAndMethodsAreUniqueAndMatchTheFrozenRoutes() {
        val normalized = operations.map { "${it.method.uppercase()} ${it.path.replace(Regex("\\{[^}]+}"), "{}")}" }
        val duplicates = normalized.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicates.isEmpty(), "Duplicate method+path (after template normalization): $duplicates")

        val actual = operations.associate { it.contractId to "${it.method.uppercase()} ${it.path}" }
        assertEquals(operations.size, actual.size, "x-contract-id must be unique per operation")
        assertEquals(frozenRoutes.toSortedMap(), actual.toSortedMap(), "Operations must be exactly the frozen HTTP contracts")

        val forbiddenSegments = listOf("/iniciar", "/pausar", "/reanudar")
        operations.forEach { op ->
            forbiddenSegments.forEach { seg -> assertTrue(!op.path.endsWith(seg), "Legacy verb route ${op.label}") }
        }
        assertTrue(
            operations.none { it.method == "post" && it.path == "/api/v1/app/intentos" },
            "The polymorphic POST /api/v1/app/intentos start is not canonical (CF-01)",
        )
    }

    @Test
    fun operationIdsAreUniqueAndEveryOperationIsAnnotated() {
        val ids = operations.map { op ->
            val id = op.op["operationId"] as? String ?: fail("Missing operationId on ${op.label}")
            assertTrue(Regex("^[a-z][A-Za-z0-9]*$").matches(id), "operationId '$id' must be lowerCamelCase")
            listOf("x-roles", "x-pagination", "x-idempotency", "x-error-codes", "security", "responses").forEach { key ->
                assertTrue(op.op.containsKey(key), "${op.label} is missing '$key'")
            }
            id
        }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicates.isEmpty(), "Duplicate operationIds: $duplicates")
    }

    /**
     * RA-1-05: every operation says whether live Ktor serves it (LIVE) or it is the post-migration
     * target (TARGET), and which slice of 17_IMPLEMENTATION_PLAN implements it (null = unscheduled).
     * Only SLICE 0 contracts are LIVE; the others describe a wire format Ktor does not serve yet.
     */
    @Test
    fun everyOperationDeclaresImplementationStatusAndSlice() {
        val problems = mutableListOf<String>()
        operations.forEach { op ->
            if (!op.op.containsKey("x-implementation")) problems += "${op.label} is missing x-implementation"
            if (!op.op.containsKey("x-slice")) problems += "${op.label} is missing x-slice"
            val impl = op.op["x-implementation"]
            val slice = op.op["x-slice"]
            if (impl !in setOf("LIVE", "TARGET")) problems += "${op.label} x-implementation '$impl' not in {LIVE, TARGET}"
            if (slice != null && slice !is Long) problems += "${op.label} x-slice '$slice' must be an integer or null"
            if (impl == "LIVE" && slice != 0L) problems += "${op.label} is LIVE but x-slice is $slice (only SLICE 0 is live)"
            if (impl == "TARGET" && slice == 0L) problems += "${op.label} is TARGET but x-slice is 0"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        assertEquals(
            setOf("D8-07", "D8-08", "D8-09"),
            operations.filter { it.op["x-implementation"] == "LIVE" }.map { it.contractId }.toSet(),
            "Only the SLICE 0 contracts are served by live Ktor",
        )
    }

    @Test
    fun securityMatchesRoles() {
        operations.forEach { op ->
            val roles = op.op["x-roles"] as List<*>
            val security = op.op["security"] as List<*>
            when {
                "ANON" in roles -> assertTrue(security.isEmpty(), "${op.label} is public and must declare security: []")
                "SYSTEM" in roles -> assertTrue(security.any { (it as Map<*, *>).containsKey("metricsToken") }, op.label)
                else -> assertEquals(listOf(mapOf("bearerAuth" to emptyList<Any?>())), security, "${op.label} needs bearerAuth")
            }
        }
    }

    @Test
    fun objectSchemasDeclareRequiredExplicitly() {
        val missing = mutableListOf<String>()
        docs.walkAllMaps { map, file ->
            if (map["properties"] is Map<*, *> && !map.containsKey("required")) {
                missing += "${file.name}: {${(map["properties"] as Map<*, *>).keys.joinToString()}}"
            }
        }
        assertTrue(missing.isEmpty(), "Object schemas without an explicit required list:\n" + missing.joinToString("\n"))
    }

    // --- Canonical vocabulary --------------------------------------------------------------------

    @Test
    fun enumsUseOnlyCanonicalVocabulary() {
        val legacyWord = Regex("(?i)\\bguiad[oa]s?\\b")
        val retired = Regex("\\b(EXAM_ACCESS_DENIED|OPTION_MISMATCH|ATTEMPT_NOT_OPEN|ATTEMPT_FINISHED)\\b")
        docs.yamlFiles().forEach { file ->
            val text = file.readText()
            assertTrue(!legacyWord.containsMatchIn(text), "${file.name} writes the legacy exam/question type synonym")
            assertTrue(!retired.containsMatchIn(text), "${file.name} uses a retired error code synonym")
        }

        val problems = mutableListOf<String>()
        docs.walkAllMaps { map, file ->
            val values = (map["enum"] as? List<*>)?.filterNotNull() ?: return@walkAllMaps
            val typeLike = values.filter { it in examTypes || it in questionTypes }
            if (typeLike.isEmpty()) return@walkAllMaps
            if (values != examTypes && values != questionTypes) problems += "${file.name}: non-canonical type enum $values"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))

        val (examEnum, _) = enumOf(docs.resolveRef("common/identifiers.yaml#/components/schemas/TipoExamen", docs.rootFile))
        val (questionEnum, _) = enumOf(docs.resolveRef("common/identifiers.yaml#/components/schemas/TipoPregunta", docs.rootFile))
        assertEquals(examTypes, examEnum)
        assertEquals(questionTypes, questionEnum)
    }

    @Test
    fun typePropertiesAreBoundToTheirCanonicalEnums() {
        val problems = mutableListOf<String>()
        docs.walkAllMaps { map, file ->
            val props = map["properties"] as? Map<*, *> ?: return@walkAllMaps
            fun check(name: String, expected: List<String>) {
                val prop = props[name] ?: return
                val (values, where) = enumOf(docs.deref(OpenApiDocs.Node(prop, file)))
                if (values != expected) problems += "${file.name}: '$name' resolves to $values ($where)"
            }
            check("tipoExamen", examTypes)
            check("tipoPregunta", questionTypes)
            if (map["x-entity"] == "pregunta") check("tipo", questionTypes)
        }
        operations.forEach { op ->
            docs.parameters(op).forEach { p ->
                val param = p.value as Map<*, *>
                val expected = when (param["name"]) {
                    "tipoExamen" -> examTypes
                    "tipoPregunta" -> questionTypes
                    "tipo" -> if (op.path.contains("/preguntas")) questionTypes else null
                    else -> null
                } ?: return@forEach
                val (values, _) = enumOf(docs.deref(OpenApiDocs.Node(param["schema"], p.file)))
                if (values != expected) problems += "${op.label}: parameter ${param["name"]} resolves to $values"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun questionSchemasNeverCarrySpecialty() {
        val specialty = Regex("(?i)especialidad|specialty")
        val problems = mutableListOf<String>()
        // Every schema tagged as the question aggregate, anywhere.
        docs.walkAllMaps { map, file ->
            if (map["x-entity"] != "pregunta") return@walkAllMaps
            (map["properties"] as? Map<*, *>)?.keys?.filter { specialty.containsMatchIn(it as String) }
                ?.forEach { problems += "${file.name}: question schema has '$it'" }
        }
        // Every request body of a question-scoped operation (and any body carrying tipoPregunta).
        operations.forEach { op ->
            docs.requestSchemas(op).values.forEach { schema ->
                val questionScoped = op.path.contains("/preguntas") || op.path.contains("/respuestas") ||
                    op.path.contains("/importaciones")
                docs.walkSchema(schema) { m, _ ->
                    val props = (m["properties"] as? Map<*, *>)?.keys.orEmpty().map { it as String }
                    val isQuestion = m["x-entity"] == "pregunta" || "tipoPregunta" in props
                    if (isQuestion || (questionScoped && m["x-entity"] == "pregunta")) {
                        props.filter { specialty.containsMatchIn(it) }.forEach { problems += "${op.label}: request has '$it'" }
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun requestsCarryNoLegacyWriteValuesAndDecodeStrictly() {
        val legacyValues = setOf("guiado", "guiada")
        val legacyFields = setOf("modoEstudio", "error", "data", "source")
        val problems = mutableListOf<String>()
        operations.forEach { op ->
            docs.parameters(op).forEach { p ->
                val param = p.value as Map<*, *>
                docs.walkSchema(OpenApiDocs.Node(param["schema"], p.file)) { m, _ ->
                    (m["enum"] as? List<*>)?.filter { it in legacyValues }
                        ?.forEach { problems += "${op.label}: parameter ${param["name"]} accepts legacy '$it'" }
                }
            }
            docs.requestSchemas(op).forEach { (media, schema) ->
                val top = docs.deref(schema).value as Map<*, *>
                if (media == "application/json" && top["additionalProperties"] != false) {
                    problems += "${op.label}: JSON command DTO must be strict (additionalProperties: false)"
                }
                docs.walkSchema(schema) { m, _ ->
                    (m["enum"] as? List<*>)?.filter { it in legacyValues }?.forEach { problems += "${op.label}: body enum '$it'" }
                    (m["properties"] as? Map<*, *>)?.keys?.filter { it in legacyFields }
                        ?.forEach { problems += "${op.label}: legacy request field '$it'" }
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // --- Server authority -----------------------------------------------------------------------

    @Test
    fun serverOwnedFieldsAreAbsentFromRequestSchemas() {
        val appForbidden = setOf(
            "id", "usuarioId", "userId", "usuario_id", "actorId", "esCorrecta", "esPrimeraRespuesta", "nota",
            "calificacion", "saldo", "balance", "cantidad", "tickets", "isPremium", "accessState", "roles", "roleIds",
            "esStaff", "revision", "deadline", "duracion", "duracionSegundos", "onboardingCompleted", "accountStatus",
            "codigoPregunta", "anulada", "motivoAnulacion", "correctas", "falladas", "respondidas",
        )
        val appForbiddenPatterns = listOf(
            Regex("^estado.*"), Regex("^[a-z][A-Za-z0-9]*En$"), Regex("^precio.*"), Regex("(?i).*balance$"),
            Regex("^saldo.*"), Regex("^tickets.*"), Regex("^primera.*"), Regex("^liga.*"), Regex("^racha.*"),
        )
        // D1-13 profile detail: estadoLaboral is USER_INPUT (employment status), not a state transition.
        val appAllowed = setOf("estadoLaboral")
        val adminForbidden = setOf(
            "id", "actorId", "actor", "creadoPor", "otorgadoPor", "bloqueadoPor", "revocadoPor", "saldo", "balance",
            "ticketsBalance", "isPremium", "nota", "calificacion", "accessState", "esCorrecta", "totalPreguntas",
            "codigoPregunta",
        )
        val adminForbiddenPatterns = listOf(Regex("^[a-z][A-Za-z0-9]*En$"), Regex("(?i).*balance$"))

        val problems = mutableListOf<String>()
        operations.forEach { op ->
            val isAdmin = op.path.startsWith("/api/v1/admin/")
            docs.requestSchemas(op).values.forEach { schema ->
                docs.walkSchema(schema) { m, _ ->
                    (m["properties"] as? Map<*, *>)?.keys?.map { it as String }?.forEach { name ->
                        val bad = if (isAdmin) {
                            name in adminForbidden || adminForbiddenPatterns.any { it.matches(name) }
                        } else {
                            name !in appAllowed && (name in appForbidden || appForbiddenPatterns.any { it.matches(name) })
                        }
                        if (bad) problems += "${op.label}: server-owned field '$name' in request schema"
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // --- Idempotency ----------------------------------------------------------------------------

    @Test
    fun idempotencyHeaderIsPresentExactlyWhereTheContractDeclaresIt() {
        operations.forEach { op ->
            val declared = op.op["x-idempotency"]
            val headers = docs.parameters(op).map { it.value as Map<*, *> }.filter {
                it["in"] == "header" && (it["name"] as String).equals("Idempotency-Key", ignoreCase = true)
            }
            val codes = op.op["x-error-codes"] as List<*>
            val responses = docs.responses(op)
            when (op.contractId) {
                in idempotencyRequired -> {
                    assertEquals("REQUIRED", declared, op.label)
                    assertEquals(1, headers.size, "${op.label} must declare Idempotency-Key once")
                    assertEquals(true, headers.single()["required"], "${op.label} Idempotency-Key must be required")
                    assertTrue(
                        docs.rawParameterRefs(op).any { it.endsWith("common/idempotency.yaml#/components/parameters/IdempotencyKeyRequired") },
                        "${op.label} must reuse the shared IdempotencyKeyRequired parameter",
                    )
                    listOf("IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID", "IDEMPOTENCY_KEY_IN_PROGRESS", "IDEMPOTENCY_KEY_REUSED")
                        .forEach { assertTrue(it in codes, "${op.label} must list $it") }
                    listOf("400", "409", "422").forEach { assertTrue(it in responses, "${op.label} must document $it") }
                }
                in idempotencyOptional -> {
                    assertEquals("OPTIONAL", declared, op.label)
                    assertEquals(1, headers.size, "${op.label} must declare Idempotency-Key once")
                    assertEquals(false, headers.single()["required"], "${op.label} Idempotency-Key must be optional")
                    assertTrue("IDEMPOTENCY_KEY_INVALID" in codes, "${op.label} must list IDEMPOTENCY_KEY_INVALID")
                    assertTrue("400" in responses, "${op.label} must document 400")
                }
                else -> {
                    assertTrue(declared == "NATURAL" || declared == "NONE", "${op.label} x-idempotency=$declared")
                    assertTrue(headers.isEmpty(), "${op.label} must not declare Idempotency-Key")
                }
            }
            if (op.method == "get") assertEquals("NONE", declared, "${op.label}: safe reads use no idempotency")
        }
    }

    // --- Pagination -----------------------------------------------------------------------------

    @Test
    fun paginatedOperationsUseTheCanonicalPaginationSchema() {
        operations.forEach { op ->
            val declared = op.op["x-pagination"]
            val params = docs.parameters(op).map { it.value as Map<*, *> }.filter { it["in"] == "query" }
            val names = params.map { it["name"] as String }
            val refs = docs.rawParameterRefs(op)
            val envelope = pageEnvelopeRef(op)
            when (op.contractId) {
                in cursorLists -> {
                    assertEquals("CURSOR", declared, op.label)
                    assertTrue(refs.any { it.endsWith("common/pagination.yaml#/components/parameters/Cursor") }, "${op.label} cursor param")
                    assertTrue("offset" !in names, "${op.label} must not take offset")
                    assertLimit(op, params, cursorLists.getValue(op.contractId))
                    assertTrue(envelope?.endsWith("common/pagination.yaml#/components/schemas/CursorPageEnvelope") == true, "${op.label} CursorPageEnvelope")
                    assertTrue("INVALID_CURSOR" in (op.op["x-error-codes"] as List<*>), "${op.label} must list INVALID_CURSOR")
                    assertTrue("400" in docs.responses(op), "${op.label} must document 400")
                }
                in offsetLists -> {
                    assertEquals("OFFSET", declared, op.label)
                    assertTrue(refs.any { it.endsWith("common/pagination.yaml#/components/parameters/Offset") }, "${op.label} offset param")
                    assertTrue("cursor" !in names, "${op.label} must not take cursor")
                    assertLimit(op, params, offsetLists.getValue(op.contractId))
                    assertTrue(envelope?.endsWith("common/pagination.yaml#/components/schemas/OffsetPageEnvelope") == true, "${op.label} OffsetPageEnvelope")
                }
                in nestedCursorLists -> {
                    assertEquals("CURSOR_NESTED", declared, op.label)
                    val body = docs.deref(docs.responses(op).getValue("200").let { successSchema(it) }).value as Map<*, *>
                    assertTrue(body.toString().contains("CursorPageEnvelope"), "${op.label} nested pages must use CursorPageEnvelope")
                }
                else -> {
                    assertEquals("NONE", declared, op.label)
                    assertTrue(names.none { it in setOf("cursor", "offset", "limit") }, "${op.label} is not paginated")
                }
            }
        }
    }

    // --- Errors ---------------------------------------------------------------------------------

    @Test
    fun errorResponsesUseTheCanonicalEnvelope() {
        val envelopeRef = "common/errors.yaml#/components/schemas/ErrorEnvelope"
        val upperSnake = Regex("^[A-Z][A-Z0-9_]*$")
        val problems = mutableListOf<String>()
        operations.forEach { op ->
            (op.op["x-error-codes"] as List<*>).forEach { code ->
                if (code !is String || !upperSnake.matches(code)) problems += "${op.label}: error code '$code' is not UPPER_SNAKE"
            }
            if (op.op.containsKey("x-error-envelope-exempt")) return@forEach
            (op.op["responses"] as Map<*, *>).forEach { (status, raw) ->
                if (!(status as String).startsWith("4") && !status.startsWith("5")) return@forEach
                val refToShared = ((raw as Map<*, *>)["\$ref"] as? String)?.contains("common/errors.yaml#/components/responses/") == true
                val response = docs.deref(OpenApiDocs.Node(raw, op.file))
                val content = (response.value as Map<*, *>)["content"] as? Map<*, *>
                val schemaRef = ((content?.get("application/json") as? Map<*, *>)?.get("schema") as? Map<*, *>)?.get("\$ref") as? String
                if (!refToShared) problems += "${op.label} $status must reference a shared error response"
                if (content?.keys != setOf("application/json") || schemaRef?.endsWith(envelopeRef.substringAfter("common/errors.yaml")) != true) {
                    problems += "${op.label} $status must return application/json ErrorEnvelope"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))

        val envelope = docs.resolveRef(envelopeRef, docs.rootFile).value as Map<*, *>
        assertEquals(listOf("code", "message", "requestId"), envelope["required"])
        val props = envelope["properties"] as Map<*, *>
        assertTrue((props["error"] as Map<*, *>)["deprecated"] == true, "legacy error field stays additive and deprecated")

        // Legacy top-level issues: the wire (ErrorDto.issues: List<String>, OB AuthErrorWire Set<String>) is a string array.
        val issueItems = (props["issues"] as Map<*, *>)["items"] as Map<*, *>
        assertEquals("string", issueItems["type"], "ErrorEnvelope.issues items are strings, not ValidationIssue")
        val issuesDescriptor = com.oposicontrol.backend.ErrorDto.serializer().descriptor.let { d ->
            d.getElementDescriptor(d.getElementIndex("issues"))
        }
        assertEquals("kotlin.String", issuesDescriptor.getElementDescriptor(0).serialName, "Kotlin ErrorDto.issues must stay List<String>")
    }

    @Test
    fun attemptContractsFollowTheFrozenDecisions() {
        val start = operations.single { it.contractId == "D2-03" }
        val startResponses = docs.responses(start)
        assertTrue("201" in startResponses && "200" in startResponses, "D2-03 returns 201 new and 200 existing")
        val location = ((startResponses.getValue("201").value as Map<*, *>)["headers"] as Map<*, *>)["Location"] as Map<*, *>
        assertEquals(true, location["required"], "D2-03 201 carries Location")
        val body = docs.requestSchemas(start).getValue("application/json")
        val modo = ((docs.deref(body).value as Map<*, *>)["properties"] as Map<*, *>)["modo"] as Map<*, *>
        assertEquals(listOf("NORMAL", "DEMO"), modo["enum"])

        val review = operations.single { it.contractId == "D2-65" }
        val reviewCodes = review.op["x-error-codes"] as List<*>
        listOf("ATTEMPT_NOT_FOUND", "ATTEMPT_NOT_FINISHED", "INVALID_CURSOR").forEach { assertTrue(it in reviewCodes, "D2-65 lists $it") }
        val noStore = ((docs.responses(review).getValue("200").value as Map<*, *>)["headers"] as Map<*, *>)["Cache-Control"]
        assertTrue(noStore != null, "D2-65 is Cache-Control: no-store")
    }

    // --- DEC-002: one active specialty per user ---------------------------------------------------

    private fun schemaProps(ref: String): Map<*, *> {
        val schema = docs.resolveRef(ref, docs.rootFile).value as Map<*, *>
        return schema["properties"] as Map<*, *>
    }

    @Test
    fun userHasExactlyOneActiveSpecialtyAndNoSpecialtyArray() {
        // Existing canonical field: perfiles_usuario.especialidad_principal_id -> profile.especialidadPrincipalId.
        val profile = schemaProps("profiles/profiles.yaml#/components/schemas/MeProfile")
        val active = profile["especialidadPrincipalId"] as? Map<*, *> ?: fail("MeProfile must expose especialidadPrincipalId")
        assertEquals(listOf("string", "null"), active["type"], "single nullable uuid, never an array")
        assertEquals("uuid", active["format"])
        assertTrue((active["description"] as String).contains("DEC-002"), "MeProfile.especialidadPrincipalId documents DEC-002")

        // Registration (D1-12) and change-specialty (D1-13) send one scalar id.
        listOf("D1-12", "D1-13").forEach { id ->
            val op = operations.single { it.contractId == id }
            val body = docs.deref(docs.requestSchemas(op).getValue("application/json")).value as Map<*, *>
            val prop = (body["properties"] as Map<*, *>)["especialidadId"] as? Map<*, *> ?: fail("$id must accept especialidadId")
            val resolved = docs.deref(OpenApiDocs.Node(prop, op.file)).value as Map<*, *>
            assertEquals("string", resolved["type"], "$id especialidadId is one uuid, not a list")
            assertEquals("uuid", resolved["format"], "$id especialidadId is a uuid")
            assertEquals(false, body["additionalProperties"], "$id is strict: a specialties array would be rejected")
        }
        assertTrue(
            (operations.single { it.contractId == "D1-13" }.op["description"] as String).contains("REPLACES the single active specialty"),
            "D1-13 is the change-specialty operation and says it replaces the single specialty",
        )

        // No specialty list anywhere: no array-typed property or parameter named after a specialty.
        val problems = mutableListOf<String>()
        val specialty = Regex("(?i)especialidad|specialt")
        docs.walkAllMaps { map, file ->
            (map["properties"] as? Map<*, *>)?.forEach { (name, raw) ->
                if (!specialty.containsMatchIn(name as String)) return@forEach
                val type = (docs.deref(OpenApiDocs.Node(raw, file)).value as? Map<*, *>)?.get("type")
                if (type == "array" || (type is List<*> && "array" in type)) problems += "${file.name}: '$name' is an array"
            }
        }
        operations.forEach { op ->
            docs.parameters(op).filter { specialty.containsMatchIn((it.value as Map<*, *>)["name"] as String) }.forEach { p ->
                val param = p.value as Map<*, *>
                val type = (param["schema"] as? Map<*, *>)?.let { docs.deref(OpenApiDocs.Node(it, p.file)).value as? Map<*, *> }?.get("type")
                if (type == "array") problems += "${op.label}: parameter ${param["name"]} is an array"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun attemptsAndResultsCarryTheExamSpecialtyAndNeverAcceptOne() {
        fun assertExamSpecialty(where: String, props: Map<*, *>) {
            val p = props["especialidadId"] as? Map<*, *> ?: fail("$where must carry the exam especialidadId")
            assertEquals(listOf("string", "null"), p["type"], where)
            assertEquals("uuid", p["format"], where)
            assertTrue("examenId" in props, "$where keeps examenId beside the specialty")
            val text = p["description"] as String
            assertTrue(text.contains("DEC-002") && text.contains("exam"), "$where documents that it is the exam specialty (DEC-002)")
        }
        assertExamSpecialty("Attempt", schemaProps("attempts/attempts.yaml#/components/schemas/Attempt"))
        assertExamSpecialty("ReviewAttempt", schemaProps("attempts/attempts.yaml#/components/schemas/ReviewAttempt"))
        val resumen = schemaProps("attempts/attempts.yaml#/components/schemas/AttemptResult")["resumen"] as Map<*, *>
        assertExamSpecialty("AttemptResult.resumen", resumen["properties"] as Map<*, *>)

        // The client never chooses the specialty of an attempt: it derives from the exam.
        operations.filter { it.path.startsWith("/api/v1/app/") && (it.path.contains("/intentos") || it.path.contains("/reto-diario")) }.forEach { op ->
            docs.requestSchemas(op).values.forEach { schema ->
                docs.walkSchema(schema) { m, _ ->
                    val names = (m["properties"] as? Map<*, *>)?.keys.orEmpty().map { it as String }
                    assertTrue(names.none { it.contains("especialidad", ignoreCase = true) }, "${op.label} request must not carry a specialty")
                }
            }
            assertTrue(docs.parameters(op).none { (it.value as Map<*, *>)["name"].toString().contains("especialidad", ignoreCase = true) })
        }
    }

    // --- DEC-008: account deletion ----------------------------------------------------------------

    @Test
    fun accountDeletionContractIsFrozen() {
        val op = operations.single { it.contractId == "D1-19" }
        assertEquals("DELETE /api/v1/app/me", "${op.method.uppercase()} ${op.path}")
        assertEquals("TARGET", op.op["x-implementation"])
        assertEquals(1L, op.op["x-slice"])
        assertEquals("NATURAL", op.op["x-idempotency"], "state-idempotent, no Idempotency-Key")
        assertEquals(listOf("USER"), op.op["x-roles"])

        val responses = docs.responses(op)
        assertTrue("202" in responses, "asynchronous acknowledgement")
        assertTrue("200" !in responses && "204" !in responses, "deletion is never reported as finished synchronously")
        assertTrue("409" !in responses, "repeat is 202 with the same requestedAt: no ALREADY_PENDING conflict")
        listOf("400", "401", "403", "429", "503").forEach { assertTrue(it in responses, "D1-19 documents $it") }
        val codes = op.op["x-error-codes"] as List<*>
        listOf("VALIDATION", "UNAUTHENTICATED", "REAUTH_FAILED", "STALE_AUTH", "ACCOUNT_BLOCKED", "RATE_LIMITED", "UPSTREAM_UNAVAILABLE")
            .forEach { assertTrue(it in codes, "D1-19 lists $it") }
        assertTrue("ALREADY_PENDING" !in codes)

        val request = docs.deref(docs.requestSchemas(op).getValue("application/json")).value as Map<*, *>
        assertEquals(listOf("confirmation"), request["required"])
        val requestProps = request["properties"] as Map<*, *>
        assertEquals(setOf("confirmation", "currentPassword"), requestProps.keys, "no caller id and no extra input")
        assertEquals("ELIMINAR", (requestProps["confirmation"] as Map<*, *>)["const"])

        val accepted = docs.deref(successSchema(responses.getValue("202"))).value as Map<*, *>
        assertEquals(listOf("accountStatus", "requestedAt"), accepted["required"])
        assertEquals("eliminacion_pendiente", ((accepted["properties"] as Map<*, *>)["accountStatus"] as Map<*, *>)["const"])

        // The nine DEC-008 steps are spelled out, in order, in the description.
        val description = op.op["description"] as String
        assertTrue(description.contains("DEC-008") && description.contains("no indiscriminate cascade"))
        var last = -1
        (1..9).forEach { n ->
            val at = description.indexOf("($n) ")
            assertTrue(at > last, "description must list step ($n) after step (${n - 1})")
            last = at
        }
        // Atomicity (RB-1-01): the GoTrue revoke cannot share a Postgres transaction, so the DB commit
        // (state + requestedAt + audit) is step 1 and the revoke is step 2, after it.
        val step1 = description.substring(description.indexOf("(1) "), description.indexOf("(2) "))
        val step2 = description.substring(description.indexOf("(2) "), description.indexOf("(3) "))
        assertTrue(step1.contains("DB transaction") && step1.contains("estado_cuenta eliminacion_pendiente") && step1.contains("requestedAt") && step1.contains("audit"), "step (1) is the atomic DB commit")
        assertTrue(step2.contains("revoke all sessions") && step2.contains("Auth admin API") && step2.contains("retried by the executor"), "step (2) is the idempotent post-commit revoke")
        assertTrue(description.contains("the only atomic part is the DB commit"), "only the DB part is atomic")
        assertTrue(!description.contains("steps 1 and 2"), "no atomic claim over the revoke")
        assertTrue(description.contains("If the DB transaction fails nothing changes"), "retry promise matches the atomic part")
        assertTrue(description.contains("revoke failures are retried by the executor"))
        assertTrue((responses.getValue("202").value as Map<*, *>)["description"].toString().contains("DB committed"), "202 is returned after the DB commit")
        // RA-1-01: step (3) disables the Auth identity; a hard delete is forbidden while FKs still cascade.
        val step3 = description.substring(description.indexOf("(3) "), description.indexOf("(4) "))
        assertTrue(step3.contains("disable the Auth identity") && step3.contains("hard delete of auth.users is forbidden") && step3.contains("ON DELETE CASCADE"), "step (3) disables, never hard-deletes")
        assertTrue(!description.contains("delete or disable"), "no hard-delete alternative")
        // PF2-2-02: the banned Auth identity is scrubbed of PII (auth class ANONYMIZE), not left intact.
        assertTrue(step3.contains("scrub") && step3.contains("tombstone") && step3.contains("phone cleared") && step3.contains("user_metadata cleared") && step3.contains("identities"), "step (3) scrubs the Auth identity PII")
        assertTrue(!description.contains("satisfied by disabling"), "disabling alone does not satisfy a DELETE class")
        // PF2-2-01: access tokens verify locally against the JWKS, so a ban does not turn them into 401.
        assertTrue(!step3.contains("answers 401 UNAUTHENTICATED") && !description.contains("a repeat is 401"), "no false immediate-401 claim")
        assertTrue(step3.contains("refresh fails with 401 from GoTrue") && step3.contains("keeps verifying locally") && step3.contains("403 ACCOUNT_DELETION_PENDING"), "step (3) states the real token behavior")
        assertTrue(description.contains("returns 202; 401 appears only after token expiry or a failed refresh"), "repeat semantics match the local JWKS verifier")
        // No legal retention period is invented (only the 5-minute re-auth window is a duration here).
        assertTrue(!Regex("(?i)\\b\\d+\\s*(days?|months?|years?|dias|meses|anos)\\b").containsMatchIn(description), "no invented retention period")
        assertTrue(description.contains("RETENTION_POLICY_PENDING"))

        val classes = op.op["x-deletion-classes"] as? Map<*, *> ?: fail("D1-19 must declare x-deletion-classes")
        assertEquals(
            mapOf(
                "perfiles_usuario" to "ANONYMIZE",
                "auth" to "ANONYMIZE",
                "preferences" to "DELETE",
                "attempts_answers" to "RETAIN_ANONYMIZED",
                "analytics_events" to "ANONYMIZE",
                "social_content" to "ANONYMIZE",
                "wallet_ledger_purchases" to "RETENTION_POLICY_PENDING",
                "audit" to "RETENTION_POLICY_PENDING",
            ),
            classes.entries.associate { it.key as String to it.value as String },
        )
        val allowed = setOf("DELETE", "ANONYMIZE", "RETAIN_ANONYMIZED", "RETENTION_POLICY_PENDING")
        assertTrue(classes.values.all { it in allowed }, "data classes are one of $allowed")
        allowed.forEach { assertTrue(description.contains(it), "description names data class $it") }

        // The only deletion route: no legacy or duplicate account-deletion path.
        assertTrue(operations.count { it.method == "delete" && (it.path == "/api/v1/app/me" || it.path.contains("/account")) } == 1)
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun enumOf(node: OpenApiDocs.Node): Pair<List<Any?>?, String> {
        val m = docs.deref(node).value as? Map<*, *> ?: return null to "not an object"
        return (m["enum"] as? List<*>)?.toList() to node.file.name
    }

    private fun successSchema(response: OpenApiDocs.Node): OpenApiDocs.Node {
        val content = (response.value as Map<*, *>)["content"] as? Map<*, *>
        val json = content?.get("application/json") as? Map<*, *>
        return OpenApiDocs.Node(json?.get("schema"), response.file)
    }

    /** `$ref` of the page envelope in the 200 response `allOf`, or null. */
    private fun pageEnvelopeRef(op: OpenApiDocs.Operation): String? {
        val ok = docs.responses(op)["200"] ?: return null
        val schema = successSchema(ok).value as? Map<*, *> ?: return null
        val allOf = schema["allOf"] as? List<*> ?: return null
        val itemsPart = allOf.filterIsInstance<Map<*, *>>().firstOrNull { it["properties"] is Map<*, *> } ?: return null
        val items = (itemsPart["properties"] as Map<*, *>)["items"] as? Map<*, *>
        val required = itemsPart["required"] as? List<*> ?: return null
        if (items?.get("type") != "array" || "items" !in required) return null
        return allOf.filterIsInstance<Map<*, *>>().mapNotNull { it["\$ref"] as? String }.firstOrNull()
    }

    private fun assertLimit(op: OpenApiDocs.Operation, params: List<Map<*, *>>, bounds: Pair<Long, Long>) {
        val limit = params.singleOrNull { it["name"] == "limit" } ?: fail("${op.label} must take limit")
        val schema = limit["schema"] as Map<*, *>
        assertEquals(1L, schema["minimum"], "${op.label} limit minimum")
        assertEquals(bounds.first, schema["maximum"], "${op.label} limit maximum")
        assertEquals(bounds.second, schema["default"], "${op.label} limit default")
    }
}
