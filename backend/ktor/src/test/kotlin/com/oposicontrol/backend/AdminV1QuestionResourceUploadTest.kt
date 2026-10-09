package com.oposicontrol.backend

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KQ4: el material de una pregunta se SUBE (multipart → Storage privado → fila),
 * no se apunta con una ruta local; y se lee con URL firmada.
 *
 * Lo que se fija es el orden y el fallo: la regla «sólo inmersiva» antes de leer
 * el cuerpo, la ruta del objeto con la forma que exige el CHECK de Q3, `x-upsert`
 * para que el reintento sea idempotente, y la limpieza del objeto cuando la fila
 * falla. Sin esa limpieza cada intento fallido pagaba almacenamiento sin dueño.
 */
class AdminV1QuestionResourceUploadTest {

    private val preguntaId = "77777777-7777-4777-8777-777777777777"
    private val recursoId = "99999999-9999-4999-8999-999999999999"

    @Test fun elArchivoSubeAlBucketPrivadoConLaRutaDelCheckYDespuesSeEscribeLaFila() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { resourcesModule(gatewayWith(upstream, tipo = "inmersiva")) }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/recursos/archivo") {
            setBody(multipart("image/png", tipoRecurso = "imagen", recursoId = recursoId))
        }

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val subida = upstream.single { it.uri().rawPath.startsWith("/storage/v1/object/recursos-pregunta/") }
        assertEquals("/storage/v1/object/recursos-pregunta/preguntas/$preguntaId/$recursoId.png", subida.uri().rawPath)
        assertTrue(subida.headers().firstValue("x-upsert").isPresent, "el reintento del mismo id tiene que poder pisar su propio objeto")
        val fila = upstream.single { it.method() == "POST" && it.uri().rawPath.endsWith("/recursos_pregunta") }
        assertTrue(upstream.indexOf(subida) < upstream.indexOf(fila), "el objeto sube ANTES de la fila: " + upstream.describe())
        val cuerpo = readRequestBody(fila)
        assertTrue(cuerpo.contains("\"ruta_storage\":\"preguntas/$preguntaId/$recursoId.png\""), cuerpo)
        assertTrue(cuerpo.contains("\"bucket_storage\":\"recursos-pregunta\""), cuerpo)
        assertTrue(cuerpo.contains("\"mime\":\"image/png\""), cuerpo)
        val body = response.bodyAsText()
        assertTrue(body.contains("token=") && body.contains("\"urlAcceso\""), "la respuesta trae la URL firmada: $body")
    }

    /** Una pregunta de ráfaga no admite material: 422 y NI UNA llamada a Storage. */
    @Test fun unaRafagaEsUn422SinTocarStorage() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { resourcesModule(gatewayWith(upstream, tipo = "rafaga")) }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/recursos/archivo") {
            setBody(multipart("image/png", tipoRecurso = "imagen"))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        assertTrue(upstream.none { it.uri().rawPath.startsWith("/storage/") }, upstream.describe())
        assertTrue(upstream.none { it.method() == "POST" && it.uri().rawPath.endsWith("/recursos_pregunta") }, upstream.describe())
    }

    /**
     * La regla «sólo inmersiva» se comprueba ANTES DE LEER EL CUERPO (comentario de
     * la ruta, `A:781`) — no sólo antes de tocar Storage. El caso de arriba con un
     * `file` presente no distingue las dos cosas: el gateway (`addRecursoArchivo`)
     * repite la MISMA comprobación antes de subir, así que quitar la del nivel de
     * ruta (`withUpstreamIo { gateway.requireQuestionAcceptsResources(id) }`) sigue
     * dando el mismo 422 sin tocar Storage — mutación superviviente MUT-KQ4b.
     *
     * Este caso manda una Ráfaga SIN parte `file`. Con la guarda de ruta, ni se mira
     * si hay archivo: sale `QUESTION_TYPE_REJECTS_RESOURCES` (422). Sin ella, el
     * handler llega a leer el multipart entero, no encuentra archivo y lanza
     * `QUESTION_RESOURCE_FILE_REQUIRED` (400) — un código y un estado distintos.
     */
    @Test fun unaRafagaSinArchivoSigueSiendoElRechazoDeTipoYNoElDeArchivoAusente() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { resourcesModule(gatewayWith(upstream, tipo = "rafaga")) }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/recursos/archivo") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("tipoRecurso", "imagen")
                        append("titulo", "Material")
                    },
                ),
            )
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("question_type_rejects_resources"), response.bodyAsText())
        assertTrue(upstream.none { it.uri().rawPath.startsWith("/storage/") }, upstream.describe())
    }

    /** Un MIME que no casa con el tipo es un 422 antes de subir; la extensión no sale del nombre del fichero. */
    @Test fun unMimeQueNoCasaConElTipoEsUn422SinSubir() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { resourcesModule(gatewayWith(upstream, tipo = "inmersiva")) }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/recursos/archivo") {
            setBody(multipart("application/x-php", tipoRecurso = "imagen", fileName = "foto.png"))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("question_resource_type_invalid"), response.bodyAsText())
        assertTrue(upstream.none { it.uri().rawPath.startsWith("/storage/") }, upstream.describe())
    }

    /** Si la fila falla después de subir, el objeto se BORRA. */
    @Test fun siLaFilaFallaSeBorraElObjetoSubido() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { resourcesModule(gatewayWith(upstream, tipo = "inmersiva", filaFalla = true)) }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/recursos/archivo") {
            setBody(multipart("audio/mpeg", tipoRecurso = "audio", recursoId = recursoId))
        }

        assertEquals(HttpStatusCode.BadGateway, response.status, response.bodyAsText())
        val borrado = upstream.single { it.method() == "DELETE" && it.uri().rawPath.startsWith("/storage/v1/object/recursos-pregunta/") }
        assertEquals("/storage/v1/object/recursos-pregunta/preguntas/$preguntaId/$recursoId.mp3", borrado.uri().rawPath)
    }

    /** Un reintento con el mismo id NO vuelve a subir: devuelve la fila que ya existe. */
    @Test fun unReintentoConElMismoIdNoVuelveASubir() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { resourcesModule(gatewayWith(upstream, tipo = "inmersiva", filaExiste = true)) }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/recursos/archivo") {
            setBody(multipart("image/png", tipoRecurso = "imagen", recursoId = recursoId))
        }

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertTrue(upstream.none { it.uri().rawPath.startsWith("/storage/v1/object/recursos-pregunta/") }, upstream.describe())
        assertTrue(upstream.none { it.method() == "POST" && it.uri().rawPath.endsWith("/recursos_pregunta") }, upstream.describe())
    }

    /** Por JSON no entra nada: los tres tipos llevan archivo y enlace, texto y PDF ya no existen. */
    @Test fun porJsonNoEntraNingunRecurso() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { resourcesModule(gatewayWith(upstream, tipo = "inmersiva")) }

        val imagen = client.post("/api/v1/admin/preguntas/$preguntaId/recursos") {
            contentType(ContentType.Application.Json)
            setBody("""{"tipoRecurso":"imagen","titulo":"t","urlExterna":"C:\\Users\\yo\\foto.png"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, imagen.status, imagen.bodyAsText())
        assertTrue(imagen.bodyAsText().contains("question_resource_upload_required"), imagen.bodyAsText())

        listOf("enlace", "texto", "pdf").forEach { tipo ->
            val rechazado = client.post("/api/v1/admin/preguntas/$preguntaId/recursos") {
                contentType(ContentType.Application.Json)
                setBody("""{"tipoRecurso":"$tipo","titulo":"t","urlExterna":"https://www.boe.es","contenidoTexto":"x"}""")
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, rechazado.status, "$tipo: " + rechazado.bodyAsText())
            assertTrue(rechazado.bodyAsText().contains("question_resource_type_invalid"), rechazado.bodyAsText())
        }
        assertFalse(upstream.any { it.method() == "POST" && it.uri().rawPath.endsWith("/recursos_pregunta") }, upstream.describe())
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun multipart(
        contentType: String,
        tipoRecurso: String,
        recursoId: String? = null,
        fileName: String = "material",
    ) = MultiPartFormDataContent(
        formData {
            append("tipoRecurso", tipoRecurso)
            append("titulo", "Material")
            recursoId?.let { append("recursoId", it) }
            append(
                "file",
                byteArrayOf(1, 2, 3),
                Headers.build {
                    append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, contentType)
                },
            )
        },
    )

    private fun recursoRow(): String =
        """{"id":"$recursoId","pregunta_id":"$preguntaId","codigo_recurso":"R-1","tipo_recurso":"imagen",""" +
            """"titulo":"Material","bucket_storage":"recursos-pregunta","ruta_storage":"preguntas/$preguntaId/$recursoId.png",""" +
            """"posicion":1,"activo":true,"metadatos":{"mime":"image/png","bytes":3}}"""

    private fun gatewayWith(
        upstream: MutableList<HttpRequest>,
        tipo: String,
        filaFalla: Boolean = false,
        filaExiste: Boolean = false,
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            val ultimo = upstream.lastOrNull()?.method()
            when {
                uri.contains("/storage/v1/object/sign/") -> V1StubResponse(
                    200,
                    """[{"path":"preguntas/$preguntaId/$recursoId.png","signedURL":"/object/sign/recursos-pregunta/preguntas/$preguntaId/$recursoId.png?token=t","error":null}]""",
                )
                uri.contains("/storage/v1/") -> V1StubResponse(200, "{}")
                uri.contains("/rest/v1/recursos_pregunta") && ultimo == "POST" ->
                    if (filaFalla) V1StubResponse(500, """{"code":"XX000","message":"x-probe-c"}""") else V1StubResponse(201, "[${recursoRow()}]")
                uri.contains("/rest/v1/recursos_pregunta") && uri.contains("id=eq.") ->
                    V1StubResponse(200, if (filaExiste) "[${recursoRow()}]" else "[]")
                uri.contains("/rest/v1/recursos_pregunta") -> V1StubResponse(200, "[]")
                uri.contains("/rest/v1/preguntas") -> V1StubResponse(
                    200,
                    """[{"id":"$preguntaId","codigo_pregunta":"PREG-000001",""" +
                        """"tipo_pregunta":"$tipo","titulo":"Titulo","activa":true}]""",
                )
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    private fun Application.resourcesModule(gateway: AdminV1Gateway) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing { adminV1Routes(gateway) }
    }

    private fun List<HttpRequest>.describe(): String = describeRequests(this)
}
