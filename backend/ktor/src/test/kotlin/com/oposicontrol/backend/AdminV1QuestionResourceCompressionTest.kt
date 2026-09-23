package com.oposicontrol.backend

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Flow
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El cableado de la compresión en el punto de subida REAL, visto desde fuera:
 * lo que se afirma es lo que sale por el cable hacia Storage.
 *
 * Las tres cosas que pueden romperse de verdad aquí:
 *
 * 1. Que los bytes se compriman pero la extensión y el `Content-Type` del
 *    objeto sigan diciendo `png`. Storage guardaría un JPEG llamado `.png`.
 * 2. Que un PDF se toque. Se descarga directo desde Storage y no lo soporta.
 * 3. Que comprimir amplíe lo que se admite: un MIME que hoy es 422 no puede
 *    empezar a colarse porque ahora sepamos transcodificarlo.
 */
class AdminV1QuestionResourceCompressionTest {

    private val preguntaId = "77777777-7777-4777-8777-777777777777"
    private val recursoId = "99999999-9999-4999-8999-999999999999"

    @Test fun unPngGrandeSubeComoJpegConLaExtensionYElContentTypeDeAcuerdo() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val png = pngBytes(1600, 1200)
        application { resourcesModule(gatewayWith(upstream, tipo = "inmersiva")) }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/recursos/archivo") {
            setBody(multipart(png, "image/png", tipoRecurso = "imagen", recursoId = recursoId))
        }

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val subida = upstream.single { it.uri().rawPath.startsWith("/storage/v1/object/recursos-pregunta/") }
        assertEquals(
            "/storage/v1/object/recursos-pregunta/preguntas/$preguntaId/$recursoId.jpg",
            subida.uri().rawPath,
            "la extensión del objeto sale del MIME resultante, no del que llegó",
        )
        assertEquals("image/jpeg", subida.headers().firstValue("Content-Type").orElse(""))
        val subido = readRequestBodyBytes(subida)
        assertTrue(
            subido.size < png.size,
            "lo que sube pesa menos: ${subido.size} B frente a ${png.size} B",
        )
        assertTrue(
            (subido[0].toInt() and 0xFF) == 0xFF && (subido[1].toInt() and 0xFF) == 0xD8,
            "y los bytes que suben son de verdad un JPEG",
        )
        val fila = upstream.single { it.method() == "POST" && it.uri().rawPath.endsWith("/recursos_pregunta") }
        val cuerpo = readRequestBody(fila)
        assertTrue(cuerpo.contains("\"ruta_storage\":\"preguntas/$preguntaId/$recursoId.jpg\""), cuerpo)
        assertTrue(cuerpo.contains("\"mime\":\"image/jpeg\""), cuerpo)
        assertTrue(cuerpo.contains("\"bytes\":${subido.size}"), "los metadatos declaran el tamaño REAL: $cuerpo")
    }

    /** Un `gzip` o un reenvasado rompen la descarga directa: el PDF viaja tal cual. */
    @Test fun unPdfSubeByteAByte() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val pdf = ("%PDF-1.4\n" + "1 0 obj\n<< /Type /Page >>\nendobj\n".repeat(500) + "%%EOF")
            .toByteArray(Charsets.ISO_8859_1)
        application { resourcesModule(gatewayWith(upstream, tipo = "inmersiva")) }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/recursos/archivo") {
            setBody(multipart(pdf, "application/pdf", tipoRecurso = "pdf", recursoId = recursoId))
        }

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val subida = upstream.single { it.uri().rawPath.startsWith("/storage/v1/object/recursos-pregunta/") }
        assertEquals("/storage/v1/object/recursos-pregunta/preguntas/$preguntaId/$recursoId.pdf", subida.uri().rawPath)
        assertEquals("application/pdf", subida.headers().firstValue("Content-Type").orElse(""))
        assertContentEquals(pdf, readRequestBodyBytes(subida), "el PDF sube byte a byte")
    }

    /**
     * `video/quicktime` no está en el mapa de admisión: hoy es un 422 y tiene
     * que seguir siéndolo. Saber transcodificar a MP4 no es permiso para
     * aceptarlo — comprimir no es una política de admisión.
     */
    @Test fun comprimirNoAmpliaLoQueSeAdmite() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { resourcesModule(gatewayWith(upstream, tipo = "inmersiva")) }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/recursos/archivo") {
            setBody(multipart(ByteArray(2_048) { it.toByte() }, "video/quicktime", tipoRecurso = "video"))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        assertTrue(upstream.none { it.uri().rawPath.startsWith("/storage/") }, "no se toca Storage")
    }

    /** Una imagen ya diminuta no se hincha: sube el original y sigue siendo PNG. */
    @Test fun unaImagenDiminutaSubeIntactaYSigueSiendoPng() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val png = pngBytes(8, 8)
        application { resourcesModule(gatewayWith(upstream, tipo = "inmersiva")) }

        val response = client.post("/api/v1/admin/preguntas/$preguntaId/recursos/archivo") {
            setBody(multipart(png, "image/png", tipoRecurso = "imagen", recursoId = recursoId))
        }

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val subida = upstream.single { it.uri().rawPath.startsWith("/storage/v1/object/recursos-pregunta/") }
        assertEquals("/storage/v1/object/recursos-pregunta/preguntas/$preguntaId/$recursoId.png", subida.uri().rawPath)
        assertEquals("image/png", subida.headers().firstValue("Content-Type").orElse(""))
        assertContentEquals(png, readRequestBodyBytes(subida))
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun multipart(
        bytes: ByteArray,
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
                bytes,
                Headers.build {
                    append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, contentType)
                },
            )
        },
    )

    /** Degradado con ruido: se comporta como una foto, no como un color plano. */
    private fun pngBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        var seed = 0x2545F491
        for (y in 0 until height) {
            for (x in 0 until width) {
                seed = seed * 1_103_515_245 + 12_345
                val jitter = (seed ushr 24) % 16 - 8
                val red = ((x * 255 / width) + jitter).coerceIn(0, 255)
                val green = ((y * 255 / height) + jitter).coerceIn(0, 255)
                val blue = (((x + y) * 255 / (width + height)) - jitter).coerceIn(0, 255)
                image.setRGB(x, y, (red shl 16) or (green shl 8) or blue)
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /**
     * El cuerpo de la petición en BYTES.
     *
     * `readRequestBody` decodifica UTF-8 y aquí lo que viaja es binario: un
     * JPEG decodificado como texto dejaría de parecerse a sí mismo y la
     * comparación byte a byte del PDF no probaría nada.
     */
    private fun readRequestBodyBytes(request: HttpRequest): ByteArray {
        val publisher = request.bodyPublisher().orElse(null) ?: return ByteArray(0)
        val out = ByteArrayOutputStream()
        publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
            override fun onNext(item: ByteBuffer) {
                val chunk = ByteArray(item.remaining())
                item.get(chunk)
                out.write(chunk)
            }

            override fun onError(throwable: Throwable) = Unit
            override fun onComplete() = Unit
        })
        return out.toByteArray()
    }

    private fun recursoRow(extension: String, mime: String): String =
        """{"id":"$recursoId","pregunta_id":"$preguntaId","codigo_recurso":"R-1","tipo_recurso":"imagen",""" +
            """"titulo":"Material","bucket_storage":"recursos-pregunta","ruta_storage":"preguntas/$preguntaId/$recursoId.$extension",""" +
            """"posicion":1,"activo":true,"metadatos":{"mime":"$mime","bytes":1}}"""

    private fun gatewayWith(upstream: MutableList<HttpRequest>, tipo: String): AdminV1Gateway =
        AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ upstream += it }) { uri ->
                val ultimo = upstream.lastOrNull()?.method()
                when {
                    uri.contains("/storage/v1/object/sign/") -> V1StubResponse(
                        200,
                        """[{"path":"preguntas/$preguntaId/$recursoId.jpg","signedURL":"/object/sign/x?token=t","error":null}]""",
                    )
                    uri.contains("/storage/v1/") -> V1StubResponse(200, "{}")
                    uri.contains("/rest/v1/recursos_pregunta") && ultimo == "POST" ->
                        V1StubResponse(201, "[${recursoRow("jpg", "image/jpeg")}]")
                    uri.contains("/rest/v1/recursos_pregunta") && uri.contains("id=eq.") -> V1StubResponse(200, "[]")
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
}
