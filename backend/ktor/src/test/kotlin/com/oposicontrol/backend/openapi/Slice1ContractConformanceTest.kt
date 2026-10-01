package com.oposicontrol.backend.openapi

import com.oposicontrol.backend.AccountDeletionAccepted
import com.oposicontrol.backend.AccountDeletionRequest
import com.oposicontrol.backend.AdminV1Gateway
import com.oposicontrol.backend.AppV1Gateway
import com.oposicontrol.backend.SupabaseJwtVerifier
import com.oposicontrol.backend.SupabaseV1Gateway
import com.oposicontrol.backend.appV1Routes
import com.oposicontrol.backend.v1Routes
import com.oposicontrol.backend.AttributionHints
import com.oposicontrol.backend.CanonicalCommand
import com.oposicontrol.backend.CanonicalErrorCode
import com.oposicontrol.backend.CanonicalTestEnv
import com.oposicontrol.backend.CommandAudience
import com.oposicontrol.backend.LegalAcceptanceRequest
import com.oposicontrol.backend.LegalAcceptanceResponse
import com.oposicontrol.backend.LegalDocument
import com.oposicontrol.backend.LegalDocumentList
import com.oposicontrol.backend.Me
import com.oposicontrol.backend.MeProfile
import com.oposicontrol.backend.OnboardingRequest
import com.oposicontrol.backend.OposiBotsAuthRuntime
import com.oposicontrol.backend.PendingLegalDocument
import com.oposicontrol.backend.ProfileDetailPatch
import com.oposicontrol.backend.ProfilePatchRequest
import com.oposicontrol.backend.RETIRED_ERROR_CODES
import com.oposicontrol.backend.ServerAuthoritativeRules
import com.oposicontrol.backend.TEST_DOCUMENT
import com.oposicontrol.backend.TEST_SPECIALTY
import com.oposicontrol.backend.TEST_TOKEN
import com.oposicontrol.backend.TEST_UID
import com.oposicontrol.backend.TestCountingLimiter
import com.oposicontrol.backend.V1StubHttpClient
import com.oposicontrol.backend.V1StubResponse
import com.oposicontrol.backend.canonicalProfileRoutes
import com.oposicontrol.backend.healthLiveRoute
import com.oposicontrol.backend.installBackendStatusPages
import com.oposicontrol.backend.installRequestCorrelation
import com.oposicontrol.backend.observabilityHealthRoutes
import com.oposicontrol.backend.oposiBotsAuthRoutes
import com.oposicontrol.backend.adminV1Routes
import com.oposicontrol.backend.profileRowJson
import com.oposicontrol.backend.profileRpcJson
import com.oposicontrol.backend.prometheusMetricsRoute
import com.oposicontrol.backend.rpcOk
import com.oposicontrol.backend.testJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SLICE 1 · conformidad estatica y de contrato del Ktor frente al OpenAPI CONGELADO (`openapi/`).
 *
 * Sin base de datos ni red: lee los YAML (mismo lector que [OpenApiContractTest]), monta los modulos de rutas reales
 * con Supabase/GoTrue falsos y comprueba que el codigo cumple lo que el contrato promete. El OpenAPI nunca se toca:
 * una divergencia es un defecto del producto.
 *
 * Alcance (las 20 operaciones `x-slice: 1`): D1-01..D1-10 (auth), D1-11/12/13/16/17/19 (perfil, legal y borrado)
 * y D1-22/23/24/26 (admin). Las aplazadas (D1-14/15/18/21/25/27..30) no deben estar registradas.
 */
class Slice1ContractConformanceTest {

    private val docs = OpenApiDocs.locate()
    private val operations = docs.operations()
    private val byId = operations.associateBy { it.contractId }

    private val authIds = (1..10).map { "D1-%02d".format(it) }
    private val appIds = listOf("D1-11", "D1-12", "D1-13", "D1-16", "D1-17", "D1-19")
    private val adminIds = listOf("D1-22", "D1-23", "D1-24", "D1-26")
    private val slice1Ids = authIds + appIds + adminIds
    private val deferredIds = listOf("D1-14", "D1-15", "D1-18", "D1-21", "D1-25", "D1-27", "D1-28", "D1-29", "D1-30")
    private val slice1 get() = slice1Ids.map { op(it) }

    private fun op(id: String): OpenApiDocs.Operation = byId[id] ?: fail("contract $id is not in openapi/root.yaml")

    // ------------------------------------------------------------------ rutas registradas

    /** `"METHOD /path/{}"` de cada ruta hoja realmente montada por los modulos de rutas de Slice 1 (+ salud/metricas). */
    private val registered: Set<String> by lazy { collectRegisteredRoutes() }

    private fun template(path: String) = path.replace(Regex("\\{[^}]+}"), "{}")
    private fun key(o: OpenApiDocs.Operation) = "${o.method.uppercase()} ${template(o.path)}"

    private fun collectRegisteredRoutes(): Set<String> {
        var out: Set<String> = emptySet()
        testApplication {
            application {
                install(ContentNegotiation) { json(testJson) }
                installRequestCorrelation()
                installBackendStatusPages()
                val runtime: OposiBotsAuthRuntime = CanonicalTestEnv().runtime()
                val stubClient = V1StubHttpClient({ }, { V1StubResponse(200, "[]") })
                val gateway = AdminV1Gateway.forTesting(
                    supabaseUrl = "https://stub.supabase.test",
                    serviceRoleKey = "stub-key",
                    json = Json { ignoreUnknownKeys = true },
                    client = V1StubHttpClient({ }, { V1StubResponse(200, "[]") }),
                )
                val root = routing {
                    oposiBotsAuthRoutes(runtime)
                    canonicalProfileRoutes(runtime)
                    adminV1Routes(gateway)
                    // Cableado real de produccion (Application.kt): las rutas legacy /v1 y /api/v1/app tambien cuentan.
                    v1Routes(SupabaseV1Gateway.forTesting("https://stub.supabase.test", "stub-key", Json { ignoreUnknownKeys = true }, stubClient))
                    appV1Routes(
                        AppV1Gateway.forTesting("https://stub.supabase.test", "stub-key", Json { ignoreUnknownKeys = true }, stubClient),
                        SupabaseJwtVerifier("https://stub.supabase.test", Json { ignoreUnknownKeys = true }),
                        allowDevUser = false,
                    )
                    healthLiveRoute()
                    observabilityHealthRoutes()
                    prometheusMetricsRoute()
                }
                out = com.oposicontrol.backend.inventory.RouteTreeWalker.keys(root as RoutingNode)
            }
            startApplication()
        }
        return out
    }

    @Test
    fun theScopeIsExactlyTwentyOperationsAndTheDeferredOnesAreNotSlice1() {
        assertEquals(20, slice1Ids.size)
        val declared = operations.filter { it.op["x-slice"] == 1L }.map { it.contractId }.sorted()
        assertEquals(slice1Ids.sorted(), declared, "x-slice: 1 operations in the frozen OpenAPI")
        deferredIds.forEach { id ->
            assertEquals(null, op(id).op["x-slice"], "$id is deferred: x-slice must stay null")
        }
    }

    @Test
    fun everySlice1OperationHasARegisteredKtorRouteWithTheSameMethodAndPath() {
        val missing = slice1.map { key(it) to it.label }.filter { it.first !in registered }
        assertTrue(missing.isEmpty(), "OpenAPI operations without a Ktor route:\n" + missing.joinToString("\n") { it.second })
    }

    @Test
    fun deferredOperationsAreNotRegistered() {
        // D1-25 (PATCH /admin/usuarios/{id}) and D1-29 (GET /admin/roles) are LIVE legacy admin routes that exist at
        // HEAD and stay untouched (scope: "live /api/v1/admin/roles keeps working untouched"); DEFERRED only means Slice 1
        // does not adapt them to the canonical contract. Everything else deferred must not be served at all.
        // Documented deviation (MASTER4-OPENAPI-001): D1-25 is `x-slice: null`, so the response sweeps here skip it; its 200
        // body is validated against the frozen AdminUserResponse in KtorRestPart2AuditTest (PATCH usuarios test).
        val legacyLiveUntouched = setOf("D1-25", "D1-29")
        legacyLiveUntouched.forEach { assertTrue(key(op(it)) in registered, "$it: the legacy live route must still be served") }
        val leaked = deferredIds.filter { it !in legacyLiveUntouched }.map { op(it) }.filter { key(it) in registered }.map { it.label }
        assertTrue(leaked.isEmpty(), "DEFERRED operations must not be served in Slice 1:\n" + leaked.joinToString("\n"))
    }

    @Test
    fun implementationLiveIsDeclaredOnlyForOperationsKtorReallyServes() {
        val live = operations.filter { it.op["x-implementation"] == "LIVE" }
        assertTrue(live.isNotEmpty())
        val missing = live.filter { key(it) !in registered }.map { it.label }
        assertTrue(missing.isEmpty(), "LIVE without a registered route: $missing")
        // The frozen contract keeps every Slice 1 operation TARGET (it is never edited to ease the code).
        slice1.forEach { assertEquals("TARGET", it.op["x-implementation"], "${it.label} x-implementation") }
    }

    @Test
    fun theRouteWalkMountsTheProductionLegacyModulesToo() {
        // MASTER-OPENAPI-011: sin v1Routes/appV1Routes las comprobaciones de rutas no documentadas no ven el legacy real.
        assertTrue("GET /api/v1/app/home" in registered, "appV1Routes must be mounted in the route walk")
        assertTrue("GET /v1/especialidades" in registered, "v1Routes must be mounted in the route walk")
    }

    @Test
    fun theCommentStripperKeepsStringsAndDropsComments() {
        val out = stripKotlinComments("val a = \"https://x\" // \"GHOST_CODE\"\n/*\"BLOCK_CODE\" */ val b = \"REAL_CODE\"")
        assertTrue("REAL_CODE" in out && "https://x" in out)
        assertTrue("GHOST_CODE" !in out && "BLOCK_CODE" !in out)
    }

    @Test
    fun noUndocumentedRouteAppearsUnderTheCanonicalAuthAndAppPrefixes() {
        val documented = operations.map { key(it) }.toSet()
        // Legacy /v1/auth routes kept for the released OposiBot client (see ARCHITECTURE_DELTA_S1).
        val legacyAllowed = setOf("GET /v1/auth/me", "POST /v1/auth/register/profile")
        // Rutas legacy vivas de /api/v1/app (AppV1Routes.kt, cableadas en Application.kt) que el contrato congelado aun no
        // declara; deny-by-default via activeIdentity. Toda ruta nueva bajo el prefijo debe declararse o entrar aqui a proposito.
        val legacyAppAllowed = setOf(
            "GET /api/v1/app/commercial/catalog", "GET /api/v1/app/commercial/purchases", "GET /api/v1/app/commercial/subscriptions",
            "GET /api/v1/app/estadisticas", "GET /api/v1/app/home", "POST /api/v1/app/eventos",
            "POST /api/v1/app/examenes/{}/iniciar", "POST /api/v1/app/feedback/pregunta", "POST /api/v1/app/intentos/{}/responder",
        )
        val stale = (legacyAllowed + legacyAppAllowed).filter { it !in registered }
        assertTrue(stale.isEmpty(), "Allowlisted legacy routes that are no longer served (remove them): $stale")
        val extra = registered
            .filter { it.substringAfter(' ').let { p -> p.startsWith("/v1/auth") || p.startsWith("/api/v1/app") } }
            .filter { it !in documented && it !in legacyAllowed && it !in legacyAppAllowed }
        assertTrue(extra.isEmpty(), "Routes served under /v1/auth or /api/v1/app that the contract does not declare: $extra")
    }

    // ------------------------------------------------------------------ DTOs de peticion

    private val commandOps: Map<String, Pair<String, KSerializer<*>>> = mapOf(
        "D1-12" to ("OnboardingRequest" to serializer<OnboardingRequest>()),
        "D1-13" to ("ProfilePatchRequest" to serializer<ProfilePatchRequest>()),
        "D1-17" to ("LegalAcceptanceRequest" to serializer<LegalAcceptanceRequest>()),
        "D1-19" to ("AccountDeletionRequest" to serializer<AccountDeletionRequest>()),
    )

    private val commandClasses = listOf(
        OnboardingRequest::class.java, ProfilePatchRequest::class.java,
        LegalAcceptanceRequest::class.java, AccountDeletionRequest::class.java,
    )

    private fun requestSchema(o: OpenApiDocs.Operation): OpenApiDocs.Node =
        docs.requestSchemas(o).getValue("application/json")

    private fun successSchema(o: OpenApiDocs.Operation, status: String): OpenApiDocs.Node {
        val response = docs.responses(o).getValue(status)
        val content = (response.value as Map<*, *>)["content"] as? Map<*, *>
        val json = content?.get("application/json") as? Map<*, *>
        return OpenApiDocs.Node(json?.get("schema"), response.file)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun compareRequest(d: SerialDescriptor, schema: OpenApiDocs.Node, at: String, problems: MutableList<String>) {
        val resolved = docs.deref(schema)
        val map = resolved.value as? Map<*, *> ?: return
        val props = (map["properties"] as? Map<*, *>)?.keys?.map { it as String }?.toSet() ?: return
        val required = (map["required"] as? List<*>).orEmpty().map { it as String }.toSet()
        val names = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
        val requiredKotlin = (0 until d.elementsCount).filterNot { d.isElementOptional(it) }.map { d.getElementName(it) }.toSet()
        if (names != props) problems += "$at: Kotlin fields $names != schema properties $props"
        if (requiredKotlin != required) problems += "$at: Kotlin required $requiredKotlin != schema required $required"
        if (map["additionalProperties"] != false) problems += "$at: schema must be additionalProperties: false"
        (0 until d.elementsCount).forEach { i ->
            val child = d.getElementDescriptor(i)
            val name = d.getElementName(i)
            val sub = (map["properties"] as Map<*, *>)[name] ?: return@forEach
            val target = if (child.kind == StructureKind.LIST && child.elementsCount > 0) child.getElementDescriptor(0) else child
            if (target.kind == StructureKind.CLASS) {
                val subSchema = if (child.kind == StructureKind.LIST) {
                    OpenApiDocs.Node((docs.deref(OpenApiDocs.Node(sub, resolved.file)).value as Map<*, *>)["items"], resolved.file)
                } else OpenApiDocs.Node(sub, resolved.file)
                compareRequest(target, subSchema, "$at.$name", problems)
            }
        }
    }

    @Test
    fun canonicalCommandDtosMatchTheFrozenRequestSchemasFieldForField() {
        val problems = mutableListOf<String>()
        commandOps.forEach { (id, pair) ->
            compareRequest(pair.second.descriptor, requestSchema(op(id)), "$id ${pair.first}", problems)
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun everyCanonicalCommandIsMarkedAndCarriesOnlyUserWritableFields() {
        commandClasses.forEach {
            assertTrue(it.isAnnotationPresent(CanonicalCommand::class.java), "${it.simpleName} must be @CanonicalCommand")
            assertEquals(CommandAudience.APP, it.getAnnotation(CanonicalCommand::class.java).audience)
        }
        val violations = commandOps.values.flatMap { (name, s) -> ServerAuthoritativeRules.violations(s.descriptor, CommandAudience.APP).map { "$name.$it" } } +
            ServerAuthoritativeRules.violations(serializer<AttributionHints>().descriptor, CommandAudience.APP).map { "AttributionHints.$it" } +
            ServerAuthoritativeRules.violations(serializer<ProfileDetailPatch>().descriptor, CommandAudience.APP).map { "ProfileDetailPatch.$it" }
        assertEquals(emptyList(), violations, "server-owned fields in Slice 1 command DTOs")
        // Identity is the JWT sub: no command names a user, actor or account state.
        val forbidden = Regex("(?i)^(usuario_?id|user_?id|actor.*|id|accountstatus|onboardingcompleted|estadocuenta|roles?)$")
        commandOps.values.forEach { (name, s) ->
            (0 until s.descriptor.elementsCount).map { s.descriptor.getElementName(it) }.forEach {
                assertTrue(!forbidden.matches(it), "$name carries '$it'")
            }
        }
    }

    // ------------------------------------------------------------------ DTOs de respuesta

    @Test
    fun responseDtosCarryEveryRequiredSchemaFieldAndNothingUndeclared() {
        data class Pair2(val id: String, val status: String, val descriptor: SerialDescriptor)
        val checks = listOf(
            Pair2("D1-11", "200", serializer<Me>().descriptor),
            Pair2("D1-12", "200", serializer<Me>().descriptor),
            Pair2("D1-13", "200", serializer<Me>().descriptor),
            Pair2("D1-16", "200", serializer<LegalDocumentList>().descriptor),
            Pair2("D1-17", "200", serializer<LegalAcceptanceResponse>().descriptor),
            Pair2("D1-19", "202", serializer<AccountDeletionAccepted>().descriptor),
        )
        val problems = mutableListOf<String>()
        fun compare(d: SerialDescriptor, schema: OpenApiDocs.Node, at: String) {
            val resolved = docs.deref(schema)
            val merged = mutableMapOf<String, Any?>()
            val requiredAll = mutableSetOf<String>()
            fun collect(n: OpenApiDocs.Node) {
                val m = docs.deref(n).value as? Map<*, *> ?: return
                (m["properties"] as? Map<*, *>)?.forEach { (k, v) -> merged[k as String] = v }
                (m["required"] as? List<*>)?.forEach { requiredAll += it as String }
                (m["allOf"] as? List<*>)?.forEach { collect(OpenApiDocs.Node(it, docs.deref(n).file)) }
            }
            collect(resolved)
            val names = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
            if (!names.containsAll(requiredAll)) problems += "$at: schema-required ${requiredAll - names} missing in Kotlin DTO"
            if (!merged.keys.containsAll(names)) problems += "$at: Kotlin emits undeclared ${names - merged.keys}"
            (0 until d.elementsCount).forEach { i ->
                val child = d.getElementDescriptor(i)
                val sub = merged[d.getElementName(i)] ?: return@forEach
                val target = if (child.kind == StructureKind.LIST && child.elementsCount > 0) child.getElementDescriptor(0) else child
                if (target.kind == StructureKind.CLASS) {
                    val subNode = docs.deref(OpenApiDocs.Node(sub, resolved.file))
                    val schemaNode = if (child.kind == StructureKind.LIST) OpenApiDocs.Node((subNode.value as Map<*, *>)["items"], subNode.file) else subNode
                    compare(target, schemaNode, "$at.${d.getElementName(i)}")
                }
            }
        }
        checks.forEach { compare(it.descriptor, successSchema(op(it.id), it.status), "${it.id} ${it.status}") }
        // Nested DTOs the schemas reference by name.
        listOf(serializer<MeProfile>().descriptor, serializer<LegalDocument>().descriptor, serializer<PendingLegalDocument>().descriptor)
            .forEach { assertTrue(it.elementsCount > 0) }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun accountDeletionSuccessSchemaIsThe202AcceptedShapeOnly() {
        val o = op("D1-19")
        val responses = docs.responses(o)
        assertEquals(setOf("202", "400", "401", "403", "429", "503"), responses.keys.toSet())
        val accepted = docs.deref(successSchema(o, "202")).value as Map<*, *>
        assertEquals(listOf("accountStatus", "requestedAt"), accepted["required"])
        val d = serializer<AccountDeletionAccepted>().descriptor
        assertEquals(setOf("accountStatus", "requestedAt"), (0 until d.elementsCount).map { d.getElementName(it) }.toSet())
        assertEquals("eliminacion_pendiente", ((accepted["properties"] as Map<*, *>)["accountStatus"] as Map<*, *>)["const"])
    }

    // ------------------------------------------------------------------ auth: superconjunto de compatibilidad

    @Test
    fun legacyAuthRequestDtosAcceptEveryCanonicalFieldOfTheirContract() {
        val dtos = mapOf(
            "D1-01" to "com.oposicontrol.backend.OposiBotsRegisterRequest",
            "D1-02" to "com.oposicontrol.backend.OposiBotsEmailExchangeRequest",
            "D1-03" to "com.oposicontrol.backend.OposiBotsEmailResendRequest",
            "D1-04" to "com.oposicontrol.backend.OposiBotsLoginRequest",
            "D1-05" to "com.oposicontrol.backend.OposiBotsAppleRequest",
            "D1-06" to "com.oposicontrol.backend.OposiBotsGoogleRequest",
            "D1-07" to "com.oposicontrol.backend.OposiBotsRefreshRequest",
            "D1-09" to "com.oposicontrol.backend.OposiBotsRecoverRequest",
            "D1-10" to "com.oposicontrol.backend.OposiBotsResetPasswordRequest",
        )
        val problems = mutableListOf<String>()
        dtos.forEach { (id, className) ->
            val d = serializer(Class.forName(className)).descriptor
            val names = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
            val schema = docs.deref(requestSchema(op(id))).value as Map<*, *>
            val canonical = (schema["properties"] as Map<*, *>).keys.map { it as String }
            val missing = canonical - names
            if (missing.isNotEmpty()) problems += "$id: $className does not accept canonical $missing"
            val requiredCanonical = (schema["required"] as List<*>).map { it as String }
            val extraRequired = (0 until d.elementsCount).filterNot { d.isElementOptional(it) }.map { d.getElementName(it) }.filter { it !in requiredCanonical }
            if (extraRequired.isNotEmpty()) problems += "$id: $className requires $extraRequired which the canonical body does not send"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun authSessionAndUserDtosCarryTheCanonicalSessionFields() {
        val session = Class.forName("com.oposicontrol.backend.OposiBotsAuthSessionDto").let { serializer(it).descriptor }
        val user = Class.forName("com.oposicontrol.backend.OposiBotsAuthUserDto").let { serializer(it).descriptor }
        fun names(d: SerialDescriptor) = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
        val authSchema = File(docs.dir, "auth/auth.yaml")
        assertTrue(authSchema.isFile)
        fun required(name: String) =
            ((docs.resolveRef("auth/auth.yaml#/components/schemas/$name", docs.rootFile).value as Map<*, *>)["required"] as List<*>).map { it as String }
        assertTrue(names(session).containsAll(required("AuthSession")), "session fields ${names(session)}")
        assertTrue(names(user).containsAll(required("AuthUser")), "user fields ${names(user)}")
    }

    // ------------------------------------------------------------------ codigos de error

    /**
     * Fuentes de `main` SIN comentarios (MASTER-OPENAPI-012): un codigo citado solo en un comentario/KDoc no cuenta como
     * existente. Las cadenas se conservan (el patron las consume enteras, asi un `//` dentro de una URL no se toma por comentario).
     */
    private val knownSources: String by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "backend/ktor/src/main/kotlin") }.firstOrNull { it.isDirectory }
            ?: File("src/main/kotlin")
        root.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { stripKotlinComments(it.readText()) }
    }

    private fun stripKotlinComments(source: String): String =
        Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])'|//[^\\n]*|/\\*[\\s\\S]*?\\*/")
            .replace(source) { m -> if (m.value.startsWith("//") || m.value.startsWith("/*")) "" else m.value }

    @Test
    fun everyErrorCodeCitedBySlice1OperationsExistsInTheKotlinCatalogAndIsNotRetired() {
        val canonical = CanonicalErrorCode.entries.map { it.name }.toSet()
        val problems = mutableListOf<String>()
        slice1.forEach { o ->
            (o.op["x-error-codes"] as List<*>).map { it as String }.forEach { code ->
                if (code in RETIRED_ERROR_CODES) problems += "${o.label}: retired code $code"
                val known = code in canonical || (o.path.startsWith("/api/v1/admin/") && Regex("\"$code\"").containsMatchIn(knownSources))
                if (!known) problems += "${o.label}: $code is not in CanonicalErrorCode"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun theKotlinStatusOfEveryCitedCodeIsADocumentedResponseOfTheOperation() {
        val problems = mutableListOf<String>()
        (authIds + appIds).map { op(it) }.forEach { o ->
            val statuses = docs.responses(o).keys
            (o.op["x-error-codes"] as List<*>).map { it as String }.forEach { code ->
                val kotlin = CanonicalErrorCode.entries.firstOrNull { it.name == code } ?: return@forEach
                if (kotlin.status.value.toString() !in statuses) problems += "${o.label}: $code is ${kotlin.status.value} but the contract documents $statuses"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // ------------------------------------------------------------------ vocabulario, especialidad e idempotencia

    @Test
    fun slice1SchemasCarryNoLegacyVocabularyNoQuestionAndNoSpecialtyOutsideTheUserProfile() {
        val problems = mutableListOf<String>()
        slice1.forEach { o ->
            val schemas = docs.requestSchemas(o).values + docs.responses(o).keys.mapNotNull { st ->
                if (st.startsWith("2")) runCatching { successSchema(o, st) }.getOrNull() else null
            }
            schemas.forEach { s ->
                docs.walkSchema(s) { m, _ ->
                    (m["enum"] as? List<*>)?.filter { it == "guiado" || it == "guiada" }?.forEach { problems += "${o.label}: legacy enum $it" }
                    if (m["x-entity"] == "pregunta") problems += "${o.label}: question schema in a Slice 1 operation"
                    val props = (m["properties"] as? Map<*, *>)?.keys.orEmpty().map { it as String }
                    props.filter { it in setOf("tipoPregunta", "tipoExamen", "modoEstudio") }.forEach { problems += "${o.label}: $it" }
                    // DEC-002: a specialty is a scalar, never a list, and only the profile owns it.
                    props.filter { it.contains("especialidad", ignoreCase = true) }.forEach { name ->
                        val type = (docs.deref(OpenApiDocs.Node((m["properties"] as Map<*, *>)[name], o.file)).value as? Map<*, *>)?.get("type")
                        if (type == "array" || (type is List<*> && "array" in type)) problems += "${o.label}: $name is an array"
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        // The Kotlin side never spells the retired synonyms in Slice 1 sources.
        val slice1Sources = listOf(
            "CanonicalProfileContracts.kt", "CanonicalProfileService.kt", "CanonicalProfileRoutes.kt",
            "AccountDeletionService.kt", "SpecialtyResolver.kt", "AdminUserProfileMapper.kt",
        ).map { name ->
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "backend/ktor/src/main/kotlin/com/oposicontrol/backend/$name") }.firstOrNull { it.isFile }
                ?: File("src/main/kotlin/com/oposicontrol/backend/$name")
        }
        slice1Sources.forEach { file ->
            assertTrue(file.isFile, "Slice 1 source ${file.path} not found")
            assertTrue(!Regex("(?i)\\bguiad[oa]s?\\b").containsMatchIn(file.readText()), "${file.name} writes a legacy type synonym")
        }
    }

    @Test
    fun noSlice1OperationRequiresAnIdempotencyKeyAndTheOnesThatDoAreDeferred() {
        slice1.forEach { o ->
            assertTrue(o.op["x-idempotency"] in setOf("NATURAL", "NONE"), "${o.label} x-idempotency=${o.op["x-idempotency"]}")
            assertTrue(
                docs.parameters(o).none { (it.value as Map<*, *>)["name"].toString().equals("Idempotency-Key", ignoreCase = true) },
                "${o.label} must not declare Idempotency-Key",
            )
        }
        val required = operations.filter { it.op["x-idempotency"] == "REQUIRED" }.filter { it.path.startsWith("/api/v1/app/me") || it.path.startsWith("/v1/auth") }
        assertEquals(listOf("D1-18"), required.map { it.contractId }, "the only /me operation with a required key is D1-18 (deferred)")
        assertTrue(key(required.single()) !in registered, "D1-18 is deferred and must not be served")
    }

    @Test
    fun patchProfileDeclaresIfMatchAndTheOtherWritesDoNot() {
        val patch = docs.parameters(op("D1-13")).map { it.value as Map<*, *> }
        val ifMatch = patch.singleOrNull { it["name"].toString().equals("If-Match", ignoreCase = true) } ?: fail("D1-13 must declare If-Match")
        assertEquals("header", ifMatch["in"])
        assertEquals(false, ifMatch["required"], "If-Match is optional on D1-13")
        listOf("D1-12", "D1-17", "D1-19").forEach {
            assertTrue(docs.parameters(op(it)).none { p -> (p.value as Map<*, *>)["name"].toString().equals("If-Match", ignoreCase = true) }, it)
        }
    }

    @Test
    fun paginationSchemasOfTheAdminUserListAreTheCanonicalOffsetEnvelope() {
        val o = op("D1-22")
        assertEquals("OFFSET", o.op["x-pagination"])
        val names = docs.parameters(o).map { it.value as Map<*, *> }.filter { it["in"] == "query" }.map { it["name"] as String }
        assertTrue("offset" in names && "limit" in names && "cursor" !in names, "D1-22 query params $names")
        val limit = docs.parameters(o).map { it.value as Map<*, *> }.single { it["name"] == "limit" }["schema"] as Map<*, *>
        assertEquals(listOf(1L, 200L, 50L), listOf(limit["minimum"], limit["maximum"], limit["default"]))
        val schema = successSchema(o, "200")
        assertTrue(schema.value.toString().contains("OffsetPageEnvelope"), "D1-22 200 must use OffsetPageEnvelope")
        listOf("D1-11", "D1-12", "D1-13", "D1-16", "D1-17", "D1-19", "D1-23", "D1-24", "D1-26").forEach {
            assertEquals("NONE", op(it).op["x-pagination"], "$it is not paginated")
        }
    }

    @Test
    fun openApiIsFrozenNothingUnderOpenapiDiffersFromHead() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "openapi/root.yaml").isFile } ?: fail("repo root not found")
        val result = runCatching {
            val p = ProcessBuilder("git", "status", "--porcelain", "--", "openapi").directory(root).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor() to text
        }.getOrNull()
        if (result == null || result.first != 0) return // git unavailable in this environment: freeze is also checked by the coordinator's git diff
        assertEquals("", result.second.trim(), "openapi/ is FROZEN and must be untouched")
    }

    // ------------------------------------------------------------------ conformidad en ejecucion (respuestas y errores)

    private val checker = SchemaChecker(docs)

    private fun env(setup: CanonicalTestEnv.() -> Unit = {}) = CanonicalTestEnv().apply {
        upstream.onGet = { path -> if ("perfiles_usuario" in path) profileRowJson() else "[]" }
        setup()
    }

    private fun ApplicationTestBuilder.mount(env: CanonicalTestEnv) = application { env.mount(this) }

    private suspend fun HttpClient.call(
        method: HttpMethod,
        path: String,
        body: String? = null,
        token: String? = TEST_TOKEN,
    ): HttpResponse = request(path) {
        this.method = method
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        if (body != null && method != HttpMethod.Get) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun json(response: HttpResponse): JsonElement = Json.parseToJsonElement(response.bodyAsText())

    private fun tokensOf(text: String?): Set<String> =
        Regex("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b|\\b(?:VALIDATION|UNAUTHENTICATED|FORBIDDEN|CONFLICT|INTERNAL|NOT_FOUND)\\b")
            .findAll(text.orEmpty()).map { it.value }.toSet()

    /** Codes a response may carry: `x-error-codes` plus every code the operation or the shared response names for that status. */
    private fun allowedCodes(o: OpenApiDocs.Operation, status: String): Set<String> {
        val raw = (o.op["responses"] as Map<*, *>)[status] as? Map<*, *>
        val shared = docs.responses(o)[status]?.value as? Map<*, *>
        return (o.op["x-error-codes"] as List<*>).map { it as String }.toSet() + tokensOf(raw?.get("description") as? String) + tokensOf(shared?.get("description") as? String)
    }

    private suspend fun assertDocumentedError(o: OpenApiDocs.Operation, response: HttpResponse, scenario: String) {
        val status = response.status.value.toString()
        // Contract gap A10 (ARCHITECTURE_DELTA_S1): D1-17 omits the 403 that deny-by-default account state requires.
        val knownGap = o.contractId == "D1-17" && status == "403"
        assertTrue(status in docs.responses(o) || knownGap, "$scenario: ${o.label} answered $status but documents ${docs.responses(o).keys}")
        val body = json(response)
        val problems = mutableListOf<String>()
        checker.validate(
            docs.resolveRef("common/errors.yaml#/components/schemas/ErrorEnvelope", docs.rootFile),
            body, "$", problems,
        )
        assertTrue(problems.isEmpty(), "$scenario: error body violates ErrorEnvelope: $problems ($body)")
        val code = body.jsonObject["code"]!!.jsonPrimitive.content
        assertTrue(code in allowedCodes(o, status) || (knownGap && code in setOf("ACCOUNT_BLOCKED", "ACCOUNT_DELETION_PENDING", "FORBIDDEN", "PROFILE_MISSING")), "$scenario: ${o.label} emitted $code (status $status); allowed ${allowedCodes(o, status)}")
        assertEquals(body.jsonObject["requestId"]!!.jsonPrimitive.content, response.headers["X-Request-Id"], "$scenario: X-Request-Id == requestId")
        if (status == "401") assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate], "$scenario: WWW-Authenticate")
        if (status == "429") assertTrue(response.headers[HttpHeaders.RetryAfter] != null, "$scenario: Retry-After")
    }

    private data class Cmd(val id: String, val method: HttpMethod, val path: String, val body: String?)

    private val commands = listOf(
        Cmd("D1-11", HttpMethod.Get, "/api/v1/app/me", null),
        Cmd(
            "D1-12", HttpMethod.Post, "/api/v1/app/me/onboarding",
            """{"username":"ada_l","nombre":"Ada","apellidos":"Lovelace","especialidadId":"$TEST_SPECIALTY","acceptedLegalDocumentIds":["$TEST_DOCUMENT"],"attribution":{"fuente":"google","plataforma":"android"},"referralCode":"AMIGO-1"}""",
        ),
        Cmd("D1-13", HttpMethod.Patch, "/api/v1/app/me/profile", """{"nombre":"Ada","especialidadId":"$TEST_SPECIALTY"}"""),
        Cmd("D1-17", HttpMethod.Post, "/api/v1/app/me/legal-acceptances", """{"documentIds":["$TEST_DOCUMENT"],"plataforma":"android"}"""),
        Cmd("D1-19", HttpMethod.Delete, "/api/v1/app/me", """{"confirmation":"ELIMINAR","currentPassword":"the-password-123"}"""),
    )

    @Test
    fun theRequestBodiesUsedByTheSweepAreThemselvesValidAgainstTheFrozenSchemas() {
        commands.filter { it.body != null }.forEach { c ->
            val problems = mutableListOf<String>()
            checker.validate(requestSchema(op(c.id)), Json.parseToJsonElement(c.body!!), "$", problems)
            assertTrue(problems.isEmpty(), "${c.id}: $problems")
        }
    }

    @Test
    fun happyPathResponsesValidateAgainstTheFrozenSuccessSchemas() = testApplication {
        val e = env {
            upstream.onRpc = { function, _ ->
                when (function) {
                    "app_completar_onboarding", "app_actualizar_perfil" -> rpcOk(profileRpcJson())
                    "app_aceptar_documentos_legales" -> rpcOk("""{"pendingLegalDocuments":[{"id":"$TEST_DOCUMENT","tipo":"privacidad","version":"2026-09"}]}""")
                    "app_solicitar_eliminacion_cuenta" -> rpcOk("""[{"estado_cuenta":"eliminacion_pendiente","solicitada_en":"2026-09-30T11:00:00.654321+00:00","ya_pendiente":false}]""")
                    "app_registrar_revocacion_sesiones" -> rpcOk("""{"revocacionSesiones":"hecha"}""")
                    else -> error("unexpected RPC $function")
                }
            }
        }
        mount(e)
        val expected = mapOf("D1-11" to "200", "D1-12" to "200", "D1-13" to "200", "D1-17" to "200", "D1-19" to "202")
        commands.forEach { c ->
            val response = client.call(c.method, c.path, c.body)
            assertEquals(expected.getValue(c.id), response.status.value.toString(), "${c.id} success status")
            val problems = mutableListOf<String>()
            checker.validate(successSchema(op(c.id), expected.getValue(c.id)), json(response), "$", problems)
            assertTrue(problems.isEmpty(), "${c.id} response violates its schema: $problems")
            assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl], "${c.id} Cache-Control")
        }
        // D1-16 is public.
        e.upstream.onGet = { """[{"id":"$TEST_DOCUMENT","tipo_documento":"terminos","version":"2026-09","titulo":"Terminos","contenido":"# md","publicado_en":"2026-09-01T00:00:00+00:00"}]""" }
        val legal = client.call(HttpMethod.Get, "/api/v1/app/legal/documents", token = null)
        assertEquals("200", legal.status.value.toString())
        val problems = mutableListOf<String>()
        checker.validate(successSchema(op("D1-16"), "200"), json(legal), "$", problems)
        assertTrue(problems.isEmpty(), "D1-16 response violates its schema: $problems")
    }

    @Test
    fun canonicalCommandsRejectUnknownAndServerOwnedFieldsWith400ValidationAndUnknownField() = testApplication {
        mount(env())
        val injected = listOf("zzUnknown", "usuarioId", "userId", "id", "accountStatus", "estadoCuenta", "onboardingCompleted", "roles", "saldo", "esCorrecta")
        commands.filter { it.body != null }.forEach { c ->
            injected.forEach { field ->
                val body = c.body!!.removeSuffix("}") + ""","$field":"$TEST_UID"}"""
                val response = client.call(c.method, c.path, body)
                assertEquals(400, response.status.value, "${c.id} + $field")
                val parsed = json(response).jsonObject
                assertEquals("VALIDATION", parsed["code"]!!.jsonPrimitive.content, "${c.id} + $field")
                val issues = parsed["details"]!!.jsonObject["issues"]!!.jsonArray.map { it.jsonObject["issue"]!!.jsonPrimitive.content }
                assertTrue("UNKNOWN_FIELD" in issues, "${c.id} + $field: $issues")
                val message = parsed["message"]!!.jsonPrimitive.content
                assertTrue(TEST_UID !in message && (field.length < 6 || field !in message), "${c.id}: message must not echo the client input ($field)")
            }
        }
        // Nested objects are strict too.
        val nested = commands.single { it.id == "D1-12" }.body!!.replace("\"fuente\":\"google\"", "\"fuente\":\"google\",\"usuarioId\":\"$TEST_UID\"")
        assertEquals(400, client.call(HttpMethod.Post, "/api/v1/app/me/onboarding", nested).status.value)
        val nestedPatch = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"detail":{"edad":30,"zz":1}}""")
        assertEquals(400, nestedPatch.status.value)
    }

    @Test
    fun errorResponsesOfTheCanonicalOperationsAreDocumentedAndUseOnlyCatalogCodes() = testApplication {
        val e = env()
        mount(e)
        val states = listOf("bloqueada", "eliminacion_pendiente", "DISABLED", "deleted", "")
        commands.forEach { c ->
            val o = op(c.id)
            e.status = "activa"; e.profileExists = true
            assertDocumentedError(o, client.call(c.method, c.path, c.body, token = null), "${c.id} no bearer")
            assertDocumentedError(o, client.call(c.method, c.path, c.body, token = "x.y.z"), "${c.id} bad bearer")
            states.forEach { state ->
                e.status = state
                val response = client.call(c.method, c.path, c.body)
                if (c.id == "D1-19" && state == "eliminacion_pendiente") return@forEach // a repeat is 202 (documented)
                assertDocumentedError(o, response, "${c.id} state '$state'")
                assertEquals(403, response.status.value, "${c.id} state '$state' deny-by-default")
            }
            e.status = "activa"; e.profileExists = false
            val missing = client.call(c.method, c.path, c.body)
            assertDocumentedError(o, missing, "${c.id} missing profile")
            assertEquals("PROFILE_MISSING", json(missing).jsonObject["code"]!!.jsonPrimitive.content)
            e.profileExists = true
            if (c.body != null) {
                assertDocumentedError(o, client.call(c.method, c.path, "{nope"), "${c.id} malformed")
                assertDocumentedError(o, client.call(c.method, c.path, "[1]"), "${c.id} array body")
            }
        }
        // D1-16 is public: only 400 (bad tipo) and 429 are reachable without side effects.
        assertDocumentedError(op("D1-16"), client.call(HttpMethod.Get, "/api/v1/app/legal/documents?tipo=cookies", token = null), "D1-16 bad tipo")
    }

    @Test
    fun rateLimitedResponsesAre429WithRetryAfterAndTheCanonicalEnvelope() = testApplication {
        val e = env { limiter = TestCountingLimiter(subjectAllowed = false, ipAllowed = false) }
        mount(e)
        commands.forEach { c ->
            val response = client.call(c.method, c.path, c.body)
            assertEquals(429, response.status.value, c.id)
            assertDocumentedError(op(c.id), response, "${c.id} rate limit")
            assertEquals("RATE_LIMITED", json(response).jsonObject["code"]!!.jsonPrimitive.content)
        }
        val legal = client.call(HttpMethod.Get, "/api/v1/app/legal/documents", token = null)
        assertEquals(429, legal.status.value)
        assertDocumentedError(op("D1-16"), legal, "D1-16 rate limit")
    }

    @Test
    fun accountDeletionNeverReportsSynchronousCompletionAndKeepsTheRepeatContract() = testApplication {
        val calls = CopyOnWriteArrayList<String>()
        val e = env {
            upstream.onRpc = { function, _ ->
                calls += function
                when (function) {
                    "app_solicitar_eliminacion_cuenta" -> rpcOk("""[{"estado_cuenta":"eliminacion_pendiente","solicitada_en":"2026-09-30T11:00:00.654321+00:00","ya_pendiente":false}]""")
                    else -> rpcOk("""{"revocacionSesiones":"hecha"}""")
                }
            }
        }
        mount(e)
        val c = commands.single { it.id == "D1-19" }
        val response = client.call(c.method, c.path, c.body)
        assertEquals(202, response.status.value)
        assertEquals("eliminacion_pendiente", json(response).jsonObject["accountStatus"]!!.jsonPrimitive.content)
        // Exactly the DB command + the recorded session revocation: no cascade, no worker, no physical erase.
        assertTrue(calls.all { it in setOf("app_solicitar_eliminacion_cuenta", "app_registrar_revocacion_sesiones") }, "RPCs called: $calls")
        assertTrue(e.upstream.log.none { it.startsWith("PATCH") || it.startsWith("DELETE") }, "no direct table writes: ${e.upstream.log}")
        // Wrong confirmation: 400 VALIDATION with issue BAD_CONFIRMATION, nothing changes.
        calls.clear()
        val wrong = client.call(c.method, c.path, """{"confirmation":"eliminar","currentPassword":"x"}""")
        assertDocumentedError(op("D1-19"), wrong, "D1-19 bad confirmation")
        assertEquals(400, wrong.status.value)
        assertTrue(calls.isEmpty(), "a bad confirmation must not reach the database: $calls")
    }
}

/**
 * Validador minimo de JSON contra el subconjunto de esquema que usa `openapi/`: tipo (con `null`), `const`, `enum`,
 * `required`, `properties`, `additionalProperties: false`, `items`, `allOf`/`oneOf`, `minLength`/`maxLength`/`pattern`,
 * `minimum`/`maximum` y `format` uuid / date-time / email. No sustituye a un validador completo: cubre lo que
 * las respuestas de Slice 1 pueden violar.
 */
internal class SchemaChecker(private val docs: OpenApiDocs) {

    fun validate(node: OpenApiDocs.Node, value: JsonElement, at: String, out: MutableList<String>) {
        val n = docs.deref(node)
        val s = n.value as? Map<*, *> ?: return
        (s["allOf"] as? List<*>)?.forEach { validate(OpenApiDocs.Node(it, n.file), value, at, out) }
        (s["oneOf"] as? List<*>)?.let { alts ->
            val passes = alts.any { a -> mutableListOf<String>().also { validate(OpenApiDocs.Node(a, n.file), value, at, it) }.isEmpty() }
            if (!passes) out += "$at matches no oneOf branch"
        }
        val types = when (val t = s["type"]) {
            is String -> listOf(t)
            is List<*> -> t.map { it.toString() }
            else -> null
        }
        if (value is JsonNull) {
            if (types != null && "null" !in types) out += "$at is null but type is $types"
            return
        }
        if (types != null && !matchesType(value, types)) {
            out += "$at has the wrong type (expected $types): $value"
            return
        }
        if (s.containsKey("const") && (value !is JsonPrimitive || value.content != s["const"].toString())) out += "$at != const ${s["const"]}"
        (s["enum"] as? List<*>)?.let { e ->
            if (value !is JsonPrimitive || e.none { it?.toString() == value.content }) out += "$at not in enum $e: $value"
        }
        when (value) {
            is JsonObject -> {
                val props = (s["properties"] as? Map<*, *>).orEmpty()
                (s["required"] as? List<*>).orEmpty().forEach { r -> if (!value.containsKey(r as String)) out += "$at missing required '$r'" }
                value.forEach { (k, v) ->
                    val sub = props[k]
                    when {
                        sub != null -> validate(OpenApiDocs.Node(sub, n.file), v, "$at.$k", out)
                        s["additionalProperties"] == false && !allowedByAllOf(s, k) -> out += "$at has undeclared property '$k'"
                    }
                }
            }
            is JsonArray -> {
                val items = s["items"]
                if (items != null) value.forEachIndexed { i, v -> validate(OpenApiDocs.Node(items, n.file), v, "$at[$i]", out) }
            }
            is JsonPrimitive -> primitive(s, value, at, out)
        }
    }

    /** `additionalProperties: false` next to an `allOf`: sibling branches contribute their own properties. */
    private fun allowedByAllOf(s: Map<*, *>, key: String): Boolean =
        (s["allOf"] as? List<*>).orEmpty().any { b ->
            (docs.deref(OpenApiDocs.Node(b, docs.rootFile)).value as? Map<*, *>)?.let { (it["properties"] as? Map<*, *>)?.containsKey(key) } == true
        }

    private fun matchesType(v: JsonElement, types: List<String>): Boolean = when (v) {
        is JsonNull -> "null" in types
        is JsonObject -> "object" in types
        is JsonArray -> "array" in types
        is JsonPrimitive -> when {
            v.isString -> "string" in types
            v.content == "true" || v.content == "false" -> "boolean" in types
            else -> ("integer" in types && v.content.toLongOrNull() != null) || "number" in types
        }
    }

    private fun primitive(s: Map<*, *>, v: JsonPrimitive, at: String, out: MutableList<String>) {
        if (v.isString) {
            val text = v.content
            (s["minLength"] as? Long)?.let { if (text.length < it) out += "$at shorter than $it" }
            (s["maxLength"] as? Long)?.let { if (text.length > it) out += "$at longer than $it" }
            (s["pattern"] as? String)?.let { if (!Regex(it).containsMatchIn(text)) out += "$at does not match /$it/" }
            when (s["format"]) {
                "uuid" -> if (!Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(text)) out += "$at is not a uuid: $text"
                "date-time" -> if (runCatching { OffsetDateTime.parse(text) }.isFailure) out += "$at is not a date-time: $text"
                "email" -> if (!text.contains('@')) out += "$at is not an email"
            }
        } else {
            v.content.toLongOrNull()?.let { n ->
                (s["minimum"] as? Long)?.let { if (n < it) out += "$at below minimum $it" }
                (s["maximum"] as? Long)?.let { if (n > it) out += "$at above maximum $it" }
            }
        }
    }
}
