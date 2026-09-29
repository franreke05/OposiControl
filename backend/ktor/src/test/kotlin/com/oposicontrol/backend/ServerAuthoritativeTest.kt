package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SLICE 0 · autoridad del servidor (`specs.serverAuthoritative`).
 *
 * El test de ARQUITECTURA recorre TODAS las clases compiladas de `main`, toma las
 * marcadas con [CanonicalCommand] y falla si alguna lleva un campo que decide el
 * servidor. Los fixtures de abajo prueban que el detector muerde.
 */
class ServerAuthoritativeTest {

    // --- test de arquitectura sobre el codigo de produccion ---

    @Test
    fun everyCanonicalCommandDtoInMainCarriesOnlyAllowedFields() {
        val commands = mainClasses().filter { it.isAnnotationPresent(CanonicalCommand::class.java) }
        // Tripwire de SLICE 0: aun no hay DTOs de comando en main, asi que este
        // test es vacuo HOY. En SLICE 1 se sustituye por assertTrue(commands.isNotEmpty()).
        if (commands.isEmpty()) println("WARN: 0 @CanonicalCommand DTOs in main (expected until Slice 1)")
        val violations = commands.flatMap { type ->
            val annotation = type.getAnnotation(CanonicalCommand::class.java)
            val descriptor = runCatching { serializer(type).descriptor }
                .getOrElse { fail("${type.name} lleva @CanonicalCommand pero no es @Serializable") }
            ServerAuthoritativeRules.violations(descriptor, annotation.audience).map { "${type.simpleName}.$it" }
        }
        assertEquals(emptyList(), violations, "campos del servidor en DTOs de comando canonicos")
    }

    /** RC-1-02: la decodificacion canonica exige la marca que recorre el test de arquitectura. */
    @Test
    fun receivingACanonicalCommandRequiresTheAnnotation() {
        requireCanonicalCommandType(GoodAppCommand::class.java)
        requireCanonicalCommandType(IdemProbeCommand::class.java)
        val failure = assertFailsWith<IllegalStateException> { requireCanonicalCommandType(UnmarkedCommand::class.java) }
        assertTrue(failure.message.orEmpty().contains("@CanonicalCommand"))
    }

    @Serializable
    data class UnmarkedCommand(val username: String)

    @Test
    fun theScanReallyFindsCompiledMainClasses() {
        val names = mainClasses().map { it.name }.toSet()
        assertTrue("com.oposicontrol.backend.ErrorDto" in names, "el escaneo no ve las clases de main")
        assertTrue(names.none { it.endsWith("Test") }, "el escaneo se ha colado en los tests")
    }

    private fun mainClasses(): List<Class<*>> {
        val root = File(ErrorDto::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(root.isDirectory, "clases de main esperadas en un directorio: $root")
        val loader = ErrorDto::class.java.classLoader
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '.').removeSuffix(".class") }
            .filterNot { it.endsWith("\$Companion") || it.endsWith("\$serializer") }
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .toList()
    }

    // --- el detector ---

    @CanonicalCommand
    @Serializable
    data class GoodAppCommand(
        val username: String,
        val nombre: String? = null,
        val apellidos: String? = null,
        val especialidadId: String? = null,
        val opcionSeleccionadaId: String? = null,
        val nivelConfianza: Int? = null,
        val posicionPregunta: Int? = null,
        val tiempoRespuestaMs: Long? = null,
        val occurredEn: String? = null,
        val estadoLaboral: String? = null,
        val modo: String? = null,
    )

    @Serializable
    data class NestedAnswer(val opcionId: String, val esCorrecta: Boolean)

    @CanonicalCommand
    @Serializable
    data class BadAppCommand(
        val usuarioId: String,
        @SerialName("user_id") val userId: String,
        val estado: String,
        val creadoEn: String,
        @SerialName("respondida_en") val respondidaEn: String,
        val esCorrecta: Boolean,
        val nota: Double,
        val calificacion: String,
        val saldo: Int,
        val precioTickets: Int,
        val cantidad: Int,
        val isPremium: Boolean,
        val accessState: String,
        val roles: List<String>,
        val esStaff: Boolean,
        val revision: Int,
        val posicion: Int,
        val respuestas: List<NestedAnswer>,
    )

    @CanonicalCommand(CommandAudience.ADMIN)
    @Serializable
    data class AdminCommand(
        val estado: String,
        val tipoAcceso: String,
        val roles: List<String>,
        val precioTickets: Int,
        val anulada: Boolean,
        val actorId: String? = null,
        val creadoEn: String? = null,
        val saldo: Int? = null,
    )

    @Test
    fun aClientCommandWithOnlyUserInputPasses() {
        assertEquals(emptyList(), ServerAuthoritativeRules.violations(serializer<GoodAppCommand>().descriptor, CommandAudience.APP))
    }

    @Test
    fun everyForbiddenCategoryIsCaughtIncludingNestedFields() {
        val violations = ServerAuthoritativeRules.violations(serializer<BadAppCommand>().descriptor, CommandAudience.APP)
        val fields = violations.map { it.substringBefore(" (") }.toSet()
        assertEquals(
            setOf(
                "usuarioId", "user_id", "estado", "creadoEn", "respondida_en", "esCorrecta", "nota", "calificacion",
                "saldo", "precioTickets", "cantidad", "isPremium", "accessState", "roles", "esStaff", "revision",
                "posicion", "respuestas.esCorrecta",
            ),
            fields,
        )
        assertTrue(violations.any { it == "saldo (FINANCIAL)" }, violations.toString())
        assertTrue(violations.any { it == "nota (RANKING_PERFORMANCE)" }, violations.toString())
        assertTrue(violations.any { it == "roles (ADMIN_DERIVED)" }, violations.toString())
        assertTrue(violations.any { it == "creadoEn (SERVER_DERIVED)" }, violations.toString())
    }

    @Test
    fun adminCommandsMayCarryAdminDerivedButNeverTheActorTimestampsOrBalances() {
        val violations = ServerAuthoritativeRules.violations(serializer<AdminCommand>().descriptor, CommandAudience.ADMIN)
            .map { it.substringBefore(" (") }
        assertEquals(listOf("actorId", "creadoEn", "saldo"), violations)
    }

    // --- decodificacion estricta ---

    @Test
    fun strictDecodingRejectsUnknownFieldsWithTheirPath() {
        val error = assertFailsWith<BackendDomainException> {
            decodeCanonicalCommand(serializer<GoodAppCommand>(), """{"username":"ana","usuarioId":"x","extra":1}""".toByteArray())
        }
        assertEquals("VALIDATION", error.errorCode)
        assertEquals(
            listOf(ErrorIssue("usuarioId", "UNKNOWN_FIELD"), ErrorIssue("extra", "UNKNOWN_FIELD")),
            error.details?.issues,
        )
        val weird = assertFailsWith<BackendDomainException> {
            decodeCanonicalCommand(serializer<GoodAppCommand>(), """{"username":"ana","<script>":1}""".toByteArray())
        }
        assertEquals(listOf(ErrorIssue("(desconocido)", "UNKNOWN_FIELD")), weird.details?.issues, "sin eco de la entrada")
    }

    @Test
    fun strictDecodingMapsMissingMalformedAndWrongTypes() {
        val missing = assertFailsWith<BackendDomainException> {
            decodeCanonicalCommand(serializer<GoodAppCommand>(), "{}".toByteArray())
        }
        assertEquals(listOf(ErrorIssue("username", "REQUIRED")), missing.details?.issues)

        listOf("{nope", "[1,2]", "\"texto\"").forEach {
            assertEquals("MALFORMED_BODY", assertFailsWith<BackendDomainException>(it) {
                decodeCanonicalCommand(serializer<GoodAppCommand>(), it.toByteArray())
            }.errorCode)
        }

        val wrongType = assertFailsWith<BackendDomainException> {
            decodeCanonicalCommand(serializer<GoodAppCommand>(), """{"username":"ana","nivelConfianza":"alto"}""".toByteArray())
        }
        assertEquals("VALIDATION", wrongType.errorCode)
        assertEquals("INVALID_FORMAT", wrongType.details?.issues?.single()?.issue)

        assertEquals(IdemProbeCommand("NORMAL"), decodeCanonicalCommand(serializer<IdemProbeCommand>(), ByteArray(0)), "cuerpo vacio = {}")
    }

    // --- Gate C: bolsas opacas y tope de issues ---

    @Serializable
    data class OpaqueBagCommand(
        val username: String,
        val extra: Map<String, String>? = null,
        val blob: kotlinx.serialization.json.JsonObject? = null,
        val any: kotlinx.serialization.json.JsonElement? = null,
        val lista: List<Map<String, Int>>? = null,
    )

    /** RB-1-03: Map / JsonObject / JsonElement dejan pasar cualquier campo del servidor: el escaneo por nombre no los ve. */
    @Test
    fun opaqueStructuresAreForbiddenInCanonicalCommands() {
        listOf(CommandAudience.APP, CommandAudience.ADMIN).forEach { audience ->
            val fields = ServerAuthoritativeRules.violations(serializer<OpaqueBagCommand>().descriptor, audience)
            assertEquals(
                listOf("extra (OPAQUE_STRUCTURE)", "blob (OPAQUE_STRUCTURE)", "any (OPAQUE_STRUCTURE)", "lista (OPAQUE_STRUCTURE)"),
                fields,
                "$audience",
            )
        }
    }

    @Serializable
    data class ManyFieldsCommand(val a: Int, val b: Int, val c: Int)

    /** RB-1-09: 1000 campos desconocidos o ausentes no se amplifican en `details.issues`. */
    @Test
    fun validationIssuesAreCapped() {
        val body = (1..1000).joinToString(",", "{", "}") { "\"f$it\":1" }
        val unknown = assertFailsWith<BackendDomainException> {
            decodeCanonicalCommand(serializer<GoodAppCommand>(), body.toByteArray())
        }
        assertEquals(MAX_VALIDATION_ISSUES, unknown.details?.issues?.size)
        assertEquals(20, MAX_VALIDATION_ISSUES, "igual que maxItems en openapi/common/errors.yaml")

        val missing = assertFailsWith<BackendDomainException> {
            decodeCanonicalCommand(serializer<ManyFieldsCommand>(), "{}".toByteArray())
        }
        assertEquals(3, missing.details?.issues?.size, "por debajo del tope no se recorta")
    }
}
