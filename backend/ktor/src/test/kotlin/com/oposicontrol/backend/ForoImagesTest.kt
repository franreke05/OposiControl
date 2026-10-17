package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Imagenes del foro (M-F7/M-F8, N-F1): el saneado (bytes magicos, cabecera, bombas, animadas, recodificado) y las rutas D5-30..D5-33. */
class ForoImagesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val img = "5a5a5a5a-5a5a-4a5a-8a5a-5a5a5a5a5a5a"
    private val post = "6b6b6b6b-6b6b-4b6b-8b6b-6b6b6b6b6b6b"

    private fun image(type: String, w: Int = 40, h: Int = 30): ByteArray {
        val bi = BufferedImage(w, h, if (type == "png") BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = bi.createGraphics(); g.color = java.awt.Color(200, 30, 30); g.fillRect(0, 0, w, h); g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(bi, type, it) }.toByteArray()
    }

    /** Inserta un APP1 "Exif" tras el SOI de un JPEG (y un tEXt tras el IHDR de un PNG): lo que el recodificado debe quitar. */
    private fun withMetadata(jpeg: ByteArray): ByteArray {
        val payload = "Exif\u0000\u0000GPS-SECRET".toByteArray(Charsets.ISO_8859_1)
        val len = payload.size + 2
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun contains(hay: ByteArray, needle: String) = String(hay, Charsets.ISO_8859_1).contains(needle)

    @Test
    fun aValidJpegIsReencodedWithoutItsMetadata() {
        val dirty = withMetadata(image("jpg"))
        assertTrue(contains(dirty, "GPS-SECRET"))
        val r = ForoImageSanitizer.sanitize(dirty, "image/jpeg")
        assertTrue(r is SanitizedImage.Ok, "$r")
        r as SanitizedImage.Ok
        assertEquals("image/jpeg", r.mime)
        assertEquals(40, r.width); assertEquals(30, r.height)
        assertTrue(!contains(r.bytes, "GPS-SECRET") && !contains(r.bytes, "Exif"), "metadata must not survive")
        assertEquals(64, r.sha256.length)
        assertTrue(ImageIO.read(r.bytes.inputStream()) != null, "the result decodes")
    }

    @Test
    fun aValidPngIsReencodedAndLargeImagesAreScaledDown() {
        val ok = ForoImageSanitizer.sanitize(image("png"), "image/png")
        assertTrue(ok is SanitizedImage.Ok, "$ok")
        val big = ForoImageSanitizer.sanitize(image("png", 3000, 2000), "image/png")
        assertTrue(big is SanitizedImage.Ok && maxOf(big.width, big.height) == ForoImageSanitizer.OUT_MAX_SIDE, "scaled to the output cap: $big")
    }

    @Test
    fun everythingElseIsRejectedNeutrally() {
        fun reason(bytes: ByteArray, mime: String) = (ForoImageSanitizer.sanitize(bytes, mime) as? SanitizedImage.Rejected)?.reason
        assertEquals("formato", reason("GIF89a......".toByteArray(), "image/png"))
        assertEquals("formato", reason("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray(), "image/png"))
        assertEquals("formato", reason(ByteArray(64) { 7 }, "image/jpeg"))
        assertEquals("mime", reason(image("png"), "image/jpeg"))                         // magic says PNG, declared JPEG
        assertEquals("tamano", reason(ByteArray(ForoImageSanitizer.MAX_BYTES + 1), "image/png"))
        assertEquals("tamano", reason(ByteArray(0), "image/png"))
        val truncated = image("jpg").let { it.copyOfRange(0, it.size / 2) }
        assertTrue(reason(truncated, "image/jpeg") != null, "a truncated image is refused")
        assertTrue(reason("%PDF-1.4".toByteArray() + ByteArray(40), "image/png") != null)
    }

    @Test
    fun aDecompressionBombIsRefusedFromTheHeaderBeforeDecoding() {
        // PNG whose IHDR says 20000 x 20000 but carries almost no data: the dimensions come from the header, no decode is attempted
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        fun chunk(type: String, data: ByteArray): ByteArray {
            val crc = java.util.zip.CRC32().also { it.update(type.toByteArray()); it.update(data) }.value
            fun be(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
            return be(data.size.toLong()) + type.toByteArray() + data + be(crc)
        }
        fun be(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        val ihdr = be(20000) + be(20000) + byteArrayOf(8, 2, 0, 0, 0)
        val bomb = sig + chunk("IHDR", ihdr) + chunk("IDAT", ByteArray(16)) + chunk("IEND", ByteArray(0))
        val started = System.nanoTime()
        assertEquals("dimensiones", (ForoImageSanitizer.sanitize(bomb, "image/png") as? SanitizedImage.Rejected)?.reason)
        assertTrue((System.nanoTime() - started) < 2_000_000_000L, "refused fast (no decode)")
    }

    @Test
    fun anAnimatedPngIsRefused() {
        val png = image("png")
        // inserta un chunk acTL justo despues del IHDR (8 firma + 25 IHDR)
        val actl = byteArrayOf(0, 0, 0, 8) + "acTL".toByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0) + byteArrayOf(0, 0, 0, 0)
        val animated = png.copyOfRange(0, 33) + actl + png.copyOfRange(33, png.size)
        assertEquals("animada", (ForoImageSanitizer.sanitize(animated, "image/png") as? SanitizedImage.Rejected)?.reason)
    }

    // ---- service + routes with a fake database and a fake storage
    private class Upstream : EconomyUpstream {
        val rpcs = CopyOnWriteArrayList<Pair<String, String>>()
        var handler: (String, String) -> SupabaseRawResponse = { _, _ -> SupabaseRawResponse(200, "{}") }
        override fun get(path: String): String = "[]"
        override fun rpc(function: String, body: String): SupabaseRawResponse { rpcs += function to body; return handler(function, body) }
    }

    private class Storage : ForoImageStorage {
        val puts = CopyOnWriteArrayList<Triple<String, Int, String>>()
        val deletes = CopyOnWriteArrayList<String>()
        override fun put(path: String, bytes: ByteArray, contentType: String) { puts += Triple(path, bytes.size, contentType) }
        override fun delete(path: String): Boolean { deletes += path; return true }
        override fun signedUrl(path: String, expiresInSeconds: Int) = "https://storage.local/sign/$path?token=t&ttl=$expiresInSeconds"
    }

    private val upstream = Upstream()
    private val storage = Storage()
    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { uid -> CanonicalUserIdentity(uid, AccountStatus.fromWire("activa"), true) },
    )

    private fun Application.imagesModule() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        val images = ForoImageService(upstream, storage)
        routing { foroRoutes(ForoRuntime(resolver, ForoService(upstream), images = images)) }
    }

    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject
    private suspend fun HttpClient.putBytes(path: String, bytes: ByteArray, type: String) = put(path) {
        header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN"); header(HttpHeaders.ContentType, type); setBody(bytes)
    }
    private suspend fun HttpClient.getAs(path: String): HttpResponse = get(path) { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }

    @Test
    fun uploadVerifiesReencodesStoresAndCommits() = testApplication {
        application { imagesModule() }
        val client = createClient { }
        upstream.handler = { fn, _ -> when (fn) {
            "app_foro_image_status" -> SupabaseRawResponse(200, """{"estado":"subida","mime":"image/jpeg","path":"u/$img.jpg"}""")
            else -> SupabaseRawResponse(200, """{"estado":"verificada"}""")
        } }
        val ok = client.putBytes("/api/v1/app/oposired/imagenes/$img/contenido", withMetadata(image("jpg")), "image/jpeg")
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals("verificada", obj(ok.bodyAsText())["estado"]!!.jsonPrimitive.content)
        assertEquals(listOf("app_foro_image_status", "app_foro_image_commit"), upstream.rpcs.map { it.first })
        assertEquals("u/$img.jpg", storage.puts.single().first)
        val commit = obj(upstream.rpcs.last().second)
        assertEquals(setOf("p_usuario", "p_id", "p_sha256", "p_ancho", "p_alto", "p_bytes"), commit.keys)
        assertEquals(TEST_UID, commit["p_usuario"]!!.jsonPrimitive.content)
    }

    @Test
    fun badUploadsAreRejectedNeutrallyAndRecorded() = testApplication {
        application { imagesModule() }
        val client = createClient { }
        upstream.handler = { fn, _ -> if (fn == "app_foro_image_status") SupabaseRawResponse(200, """{"estado":"subida","mime":"image/png","path":"u/$img.png"}""") else SupabaseRawResponse(200, "{}") }
        val gif = client.putBytes("/api/v1/app/oposired/imagenes/$img/contenido", "GIF89a-not-an-image".toByteArray(), "image/png")
        assertEquals(HttpStatusCode.UnprocessableEntity, gif.status, gif.bodyAsText())
        assertEquals("IMAGE_REJECTED", obj(gif.bodyAsText())["code"]!!.jsonPrimitive.content)
        assertTrue(upstream.rpcs.any { it.first == "app_foro_image_reject" } && storage.puts.isEmpty(), "rejected: recorded, nothing stored")
        upstream.rpcs.clear()
        val wrongType = client.putBytes("/api/v1/app/oposired/imagenes/$img/contenido", image("png"), "image/jpeg")
        assertEquals(HttpStatusCode.UnprocessableEntity, wrongType.status)
        assertEquals(HttpStatusCode.BadRequest, client.putBytes("/api/v1/app/oposired/imagenes/not-a-uuid/contenido", image("png"), "image/png").status)
        // not in state subida => 404
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"estado":"verificada","mime":"image/png","path":"u/x.png"}""") }
        assertEquals(HttpStatusCode.NotFound, client.putBytes("/api/v1/app/oposired/imagenes/$img/contenido", image("png"), "image/png").status)
    }

    @Test
    fun initStatusAndSignedUrlNeverLeakThePath() = testApplication {
        application { imagesModule() }
        val client = createClient { }
        upstream.handler = { fn, _ -> when (fn) {
            "app_foro_image_init" -> SupabaseRawResponse(200, """{"imagenId":"$img","path":"secret/path.png","mime":"image/png","bytesMax":1000}""")
            "app_foro_image_status" -> SupabaseRawResponse(200, """{"estado":"verificada","mime":"image/png","path":"secret/path.png"}""")
            else -> SupabaseRawResponse(200, """{"path":"secret/path.png","version":2,"mime":"image/png"}""")
        } }
        val init = client.post("/api/v1/app/oposired/imagenes") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN"); contentType(ContentType.Application.Json); setBody("""{"mime":"image/png","bytes":1000}""") }
        assertEquals(HttpStatusCode.Created, init.status, init.bodyAsText())
        assertTrue(!init.bodyAsText().contains("secret"), "no path in the init answer")
        val st = client.getAs("/api/v1/app/oposired/imagenes/$img")
        assertEquals("""{"estado":"verificada"}""", st.bodyAsText())
        val url = client.getAs("/api/v1/app/oposired/publicaciones/$post/imagen")
        assertEquals(HttpStatusCode.OK, url.status, url.bodyAsText())
        assertTrue(obj(url.bodyAsText())["url"]!!.jsonPrimitive.content.contains("ttl=120"), "short TTL")
        assertEquals("no-store", url.headers[HttpHeaders.CacheControl])
        for (bad in listOf("""{"mime":"image/png"}""", """{"mime":"image/png","bytes":10,"usuarioId":"x"}""")) {
            val r = client.post("/api/v1/app/oposired/imagenes") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN"); contentType(ContentType.Application.Json); setBody(bad) }
            assertEquals(HttpStatusCode.BadRequest, r.status, bad)
        }
    }

    @Test
    fun theSweeperDeletesObjectsAndConfirms() {
        upstream.handler = { fn, _ -> if (fn == "app_foro_images_to_purge") SupabaseRawResponse(200, """[{"id":"$img","path":"u/a.png"},{"id":"$post","path":"u/b.png"}]""") else SupabaseRawResponse(200, "2") }
        val n = runBlocking { ForoImageService(upstream, storage).sweep() }
        assertEquals(2, n)
        assertEquals(listOf("u/a.png", "u/b.png"), storage.deletes.toList())
        assertEquals("app_foro_images_purged", upstream.rpcs.last().first)
    }
}
