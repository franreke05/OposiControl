package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K5 · Portada de examen (PW-8 opción A, misma entrega que M6).
 *
 * La portada era un defecto P0 de punta a punta (EX-01): se leían columnas que no
 * existían y la URL guardada era pública sobre un bucket PRIVADO, así que nunca se
 * habría servido. El contrato nuevo tiene cuatro reglas, y cada una se afirma aquí
 * sobre lo que sale por el cable:
 *
 * 1. Se guarda la RUTA del objeto, nunca una URL; la URL se FIRMA en cada lectura,
 *    y en un listado con una sola petición a Storage.
 * 2. El nombre es único por subida y con la forma exacta del CHECK de M6:
 *    `exam-covers/<id>/<uuid>.<ext>` en minúsculas, sin sobrescribir nunca.
 * 3. Si la fila no llega a apuntar al objeto nuevo, el objeto se borra; si llega,
 *    se borra el anterior. Quitar la portada escribe la fila ANTES de borrar.
 * 4. Sólo JPG, PNG y WEBP, decidido antes de tocar la base o Storage.
 */
class AdminV1ExamCoverTest {

    private val examenId = "5a5a5a5a-5a5a-4a5a-8a5a-5a5a5a5a5a5a"
    private val otroExamenId = "7c7c7c7c-7c7c-4c7c-8c7c-7c7c7c7c7c7c"
    private val especialidadId = "6b6b6b6b-6b6b-4b6b-8b6b-6b6b6b6b6b6b"
    private val anterior = "exam-covers/$examenId/11111111-1111-4111-8111-111111111111.jpg"
    private val guardada = "exam-covers/$examenId/22222222-2222-4222-8222-222222222222.png"
    private val deOtroExamen = "exam-covers/$otroExamenId/33333333-3333-4333-8333-333333333333.webp"

    // -----------------------------------------------------------------
    // Subir
    // -----------------------------------------------------------------

    /**
     * **La prueba de mutación del nombre.** El CHECK de M6 compara con una
     * expresión regular exacta: un nombre con el `originalFileName` del cliente, un
     * uuid en mayúsculas o una extensión `PNG` harían fallar el PATCH en MAIN.
     */
    @Test
    fun anUploadUsesAUniqueLowercaseNameThatMatchesTheDatabaseCheckAndNeverOverwrites() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        val saved = gatewayWith(upstream).uploadExamenPortada(examenId, byteArrayOf(1, 2, 3), "image/PNG")

        val subida = upstream.single { it.method() == "POST" && it.uri().rawPath.startsWith(OBJECT_PREFIX) }
        val ruta = subida.uri().rawPath.removePrefix(OBJECT_PREFIX)
        assertTrue(M6_PATH_REGEX(examenId).matches(ruta), "la ruta no cumple el CHECK de M6: $ruta")
        assertFalse(subida.headers().firstValue("x-upsert").isPresent, "una portada nueva nunca sobrescribe otra")
        val patch = readRequestBody(upstream.single { it.method() == "PATCH" && it.uri().rawPath.endsWith("/rest/v1/examenes") })
        assertTrue(patch.contains("\"portada_storage_path\":\"$ruta\""), patch)
        assertTrue(patch.contains("\"portada_mime_type\":\"image/png\""), patch)
        assertFalse(patch.contains("portada_url"), "la URL no se persiste: $patch")
        assertEquals(
            "https://stub.supabase.test/storage/v1/object/sign/exam-resources/$guardada?token=t",
            assertNotNull(saved).portadaUrl,
            "la respuesta trae la portada FIRMADA",
        )
    }

    @Test
    fun twoUploadsNeverShareAnObjectName() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(upstream)

        gateway.uploadExamenPortada(examenId, byteArrayOf(1), "image/jpeg")
        gateway.uploadExamenPortada(examenId, byteArrayOf(2), "image/webp")

        val rutas = upstream.filter { it.method() == "POST" && it.uri().rawPath.startsWith(OBJECT_PREFIX) }
            .map { it.uri().rawPath.removePrefix(OBJECT_PREFIX) }
        assertEquals(2, rutas.size, rutas.toString())
        assertNotEquals(rutas[0], rutas[1])
        assertTrue(rutas[0].endsWith(".jpg") && rutas[1].endsWith(".webp"), rutas.toString())
    }

    /** Si la fila no llega a apuntar al objeto nuevo, nadie lo referencia: se borra, y el error llega. */
    @Test
    fun whenThePatchFailsTheNewObjectIsDeletedAndThePreviousOneIsKept() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        assertFailsWith<IllegalStateException> {
            gatewayWith(upstream, patchStatus = 500).uploadExamenPortada(examenId, byteArrayOf(1), "image/png")
        }

        val nueva = upstream.single { it.method() == "POST" && it.uri().rawPath.startsWith(OBJECT_PREFIX) }.uri().rawPath
        val borrados = upstream.filter { it.method() == "DELETE" }.map { it.uri().rawPath }
        assertEquals(listOf(nueva), borrados, "sólo se borra el objeto que nadie referencia")
    }

    @Test
    fun whenThePatchSucceedsThePreviousObjectIsDeleted() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        gatewayWith(upstream).uploadExamenPortada(examenId, byteArrayOf(1), "image/png")

        val borrados = upstream.filter { it.method() == "DELETE" }.map { it.uri().rawPath }
        assertEquals(listOf("$OBJECT_PREFIX$anterior"), borrados)
    }

    /** Tipo antes que nada: ni una lectura de la base ni un byte a Storage. */
    @Test
    fun anUnsupportedTypeIsRejectedBeforeTouchingTheDatabaseOrStorage() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        val error = assertFailsWith<BackendDomainException> {
            gatewayWith(upstream).uploadExamenPortada(examenId, byteArrayOf(1), "image/svg+xml")
        }

        assertEquals("EXAM_COVER_TYPE_INVALID", error.errorCode)
        assertEquals(HttpStatusCode.UnprocessableEntity, error.status)
        assertTrue(upstream.isEmpty(), "se llamó al upstream con un tipo inválido: ${upstream.map { it.uri() }}")
    }

    @Test
    fun aMissingExamIsNullAndStorageIsNeverTouched() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        val saved = gatewayWith(upstream, existe = false).uploadExamenPortada(examenId, byteArrayOf(1), "image/png")

        assertNull(saved)
        assertTrue(upstream.none { it.uri().rawPath.startsWith("/storage/") }, upstream.map { it.uri() }.toString())
    }

    // -----------------------------------------------------------------
    // Quitar
    // -----------------------------------------------------------------

    /**
     * Primero la fila, DESPUÉS el objeto: si el borrado falla queda un huérfano
     * privado; al revés quedaría una fila apuntando a nada.
     */
    @Test
    fun clearingWritesNullsFirstAndDeletesTheObjectAfterwards() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        gatewayWith(upstream, patchDevuelveSinPortada = true).clearExamenPortada(examenId)

        val patchIndex = upstream.indexOfFirst { it.method() == "PATCH" }
        val deleteIndex = upstream.indexOfFirst { it.method() == "DELETE" }
        assertTrue(patchIndex >= 0 && deleteIndex > patchIndex, "orden: ${upstream.map { "${it.method()} ${it.uri().rawPath}" }}")
        val patch = readRequestBody(upstream[patchIndex])
        assertTrue(patch.contains("\"portada_storage_path\":null") && patch.contains("\"portada_mime_type\":null"), patch)
        assertFalse(patch.contains("portada_url"), patch)
        assertEquals("$OBJECT_PREFIX$anterior", upstream[deleteIndex].uri().rawPath)
    }

    // -----------------------------------------------------------------
    // Leer
    // -----------------------------------------------------------------

    /** **La prueba de mutación del lote.** Firmar una a una serían N peticiones por abrir el catálogo. */
    @Test
    fun aListingWithTwoCoversSignsThemInOneRequest() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        val examenes = gatewayWith(upstream).listExamenes()

        val firmas = upstream.filter { it.uri().rawPath.startsWith("/storage/v1/object/sign/") }
        assertEquals(1, firmas.size, firmas.map { it.uri() }.toString())
        assertEquals("/storage/v1/object/sign/exam-resources", firmas.single().uri().rawPath)
        val porId = examenes.associateBy { it.id }
        assertTrue(porId.getValue(examenId).portadaUrl.orEmpty().contains("token="), porId.getValue(examenId).toString())
        assertTrue(porId.getValue(otroExamenId).portadaUrl.orEmpty().contains("token="), porId.getValue(otroExamenId).toString())
    }

    /** Un fallo al firmar nunca rompe la lectura: sin URL, con la ruta intacta. */
    @Test
    fun aSigningFailureNeverBreaksTheListing() {
        val examenes = gatewayWith(CopyOnWriteArrayList(), firmaStatus = 500).listExamenes()

        val conPortada = examenes.first { it.id == examenId }
        assertNull(conPortada.portadaUrl)
        assertEquals(guardada, conPortada.portadaStoragePath)
    }

    // -----------------------------------------------------------------
    // Rutas
    // -----------------------------------------------------------------

    @Test
    fun theRouteRejectsAnSvgAs422WithoutReadingItOrCallingAnything() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { coverModule(upstream) }

        val response = client.post("/api/v1/admin/examenes/$examenId/portada") { setBody(multipart("image/svg+xml")) }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("exam_cover_type_invalid"))
        assertTrue(upstream.isEmpty(), upstream.map { it.uri() }.toString())
    }

    /**
     * MUT-K5e: el caso de arriba con un archivo de 3 bytes no distingue si el
     * chequeo de tipo de la RUTA (`A:594`) sigue vivo, porque `uploadExamenPortada`
     * repite la MISMA comprobación de MIME (`A:6498`) antes de tocar Storage —
     * quitando el de la ruta, un SVG pequeño sigue saliendo 422 `exam_cover_type_invalid`
     * sin ninguna petición a Supabase. Es defensa en profundidad real, no un agujero.
     *
     * Lo que el chequeo de RUTA aporta —y lo único que lo distingue— es rechazar
     * ANTES de leer el cuerpo: con el chequeo quitado, un SVG que además supera el
     * límite de 10 MB deja de recibir el 422 de tipo y pasa a leer hasta agotar el
     * límite, disparando el `require` de tamaño en su lugar (`IllegalArgumentException`,
     * 400, "El archivo supera el limite de 10 MB."). Con el chequeo presente, ese
     * archivo nunca llega a leerse y sigue siendo un 422 de tipo.
     */
    @Test
    fun anOversizedSvgIsStillTheTypeRejectionNotTheSizeOne() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { coverModule(upstream) }
        val oversizedSvg = ByteArray(10 * 1024 * 1024 + 1)

        val response = client.post("/api/v1/admin/examenes/$examenId/portada") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            oversizedSvg,
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"portada\"")
                                append(HttpHeaders.ContentType, "image/svg+xml")
                            },
                        )
                    },
                ),
            )
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("exam_cover_type_invalid"), response.bodyAsText())
        assertTrue(upstream.isEmpty(), upstream.map { it.uri() }.toString())
    }

    @Test
    fun theRoutesAnswer404ForAMissingExam() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { coverModule(upstream, existe = false) }

        val subir = client.post("/api/v1/admin/examenes/$examenId/portada") { setBody(multipart("image/png")) }
        val quitar = client.delete("/api/v1/admin/examenes/$examenId/portada")

        assertEquals(HttpStatusCode.NotFound, subir.status, subir.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, quitar.status, quitar.bodyAsText())
        assertTrue(upstream.none { it.uri().rawPath.startsWith("/storage/") }, upstream.map { it.uri() }.toString())
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun io.ktor.server.application.Application.coverModule(upstream: MutableList<HttpRequest>, existe: Boolean = true) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installBackendStatusPages()
        routing { adminV1Routes(gatewayWith(upstream, existe = existe)) }
    }

    private fun multipart(contentType: String) = MultiPartFormDataContent(
        formData {
            append(
                "file",
                byteArrayOf(1, 2, 3),
                Headers.build {
                    append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"portada\"")
                    append(HttpHeaders.ContentType, contentType)
                },
            )
        },
    )

    private fun examenRow(id: String, ruta: String?): String {
        val rutaJson = ruta?.let { "\"$it\"" } ?: "null"
        val mimeJson = ruta?.let { "\"image/png\"" } ?: "null"
        return """{"id":"$id","codigo_examen":"EX-1","especialidad_id":"$especialidadId","titulo":"Examen",""" +
            """"tipo_examen":"inmersivo","tipo_acceso":"premium","activo":true,""" +
            """"portada_storage_path":$rutaJson,"portada_mime_type":$mimeJson}"""
    }

    private fun firmas(vararg rutas: String): String = rutas.joinToString(",", "[", "]") { ruta ->
        """{"path":"$ruta","signedURL":"/object/sign/exam-resources/$ruta?token=t","error":null}"""
    }

    /**
     * El doble decide por la URL, en este orden: firma, resto de Storage, lectura
     * mínima de portada (`select=id,portada_storage_path`), lecturas completas
     * (`select=*`) y, sin `select=`, el PATCH.
     */
    private fun gatewayWith(
        upstream: MutableList<HttpRequest>,
        existe: Boolean = true,
        patchStatus: Int = 200,
        firmaStatus: Int = 200,
        patchDevuelveSinPortada: Boolean = false,
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            when {
                uri.contains("/storage/v1/object/sign/") ->
                    if (firmaStatus == 200) V1StubResponse(200, firmas(anterior, guardada, deOtroExamen)) else V1StubResponse(firmaStatus, "{}")
                uri.contains("/storage/v1/") -> V1StubResponse(200, "{}")
                uri.contains("/rest/v1/examenes") && uri.contains("select=id,portada_storage_path") ->
                    V1StubResponse(200, if (existe) """[{"id":"$examenId","portada_storage_path":"$anterior"}]""" else "[]")
                uri.contains("/rest/v1/examenes") && uri.contains("select=") ->
                    V1StubResponse(200, "[${examenRow(examenId, guardada)},${examenRow(otroExamenId, deOtroExamen)}]")
                uri.contains("/rest/v1/examenes") ->
                    when {
                        patchStatus != 200 -> V1StubResponse(patchStatus, """{"code":"XX000","message":"fallo de prueba"}""")
                        patchDevuelveSinPortada -> V1StubResponse(200, "[${examenRow(examenId, null)}]")
                        else -> V1StubResponse(200, "[${examenRow(examenId, guardada)}]")
                    }
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    private companion object {
        const val OBJECT_PREFIX = "/storage/v1/object/exam-resources/"

        /** La expresión del CHECK de M6 (`examenes_portada_coherente_chk`), tal cual. */
        fun M6_PATH_REGEX(examenId: String) = Regex(
            "^exam-covers/$examenId/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|jpeg|png|webp|gif)$",
        )
    }
}
