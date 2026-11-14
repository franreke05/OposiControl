package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Storage falso del bucket `avatars`: registra subidas, borrados y firmas. */
internal class FakeAvatarStorage : AvatarStorage {
    val puts = CopyOnWriteArrayList<Pair<String, ByteArray>>()
    val deletes = CopyOnWriteArrayList<String>()
    val signRequests = CopyOnWriteArrayList<Collection<String>>()
    var signFailure: Exception? = null
    var deleteFailure: Exception? = null

    override fun put(objectPath: String, bytes: ByteArray, contentType: String) {
        assertEquals("image/jpeg", contentType, "the stored object is always the re-encoded JPEG")
        puts += objectPath to bytes
    }

    override fun delete(objectPath: String): Boolean {
        deleteFailure?.let { throw it }
        deletes += objectPath
        return true
    }

    override fun signedUrls(objectPaths: Collection<String>, expiresInSeconds: Int): Map<String, String?> {
        signFailure?.let { throw it }
        signRequests += objectPaths.toList()
        return objectPaths.associateWith { "https://example.supabase.co/storage/v1/object/sign/avatars/$it?token=t$expiresInSeconds" }
    }
}

/**
 * FA42 (owner 2026-11-08): D1-14 PUT / D1-15 DELETE /api/v1/app/me/avatar, el avatar en GET /me y su exposicion a otros (ranking top3 y autores
 * del foro). Perfil canonico REAL sobre Supabase, GoTrue y Storage falsos.
 */
class AvatarRoutesTest {
    private val otherUid = "11111111-2222-4333-8444-555555555555"

    private fun image(type: String, w: Int = 64, h: Int = 48, paint: (java.awt.Graphics2D) -> Unit = { it.color = Color(200, 30, 30); it.fillRect(0, 0, w, h) }): ByteArray {
        val bi = BufferedImage(w, h, if (type == "png") BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = bi.createGraphics(); paint(g); g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(bi, type, it) }.toByteArray()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun env(setup: CanonicalTestEnv.() -> Unit = {}) = CanonicalTestEnv(avatarStorage = FakeAvatarStorage()).apply {
        upstream.onGet = { path -> if ("perfiles_usuario" in path) profileRowJson() else "[]" }
        upstream.onRpc = { function, args ->
            when (function) {
                "app_set_avatar" -> rpcOk("""{"avatarPath":"${args["p_path"]!!.jsonPrimitive.content}","previousPath":null,"changed":true}""")
                "app_clear_avatar" -> rpcOk("""{"previousPath":null,"changed":false}""")
                else -> rpcOk("{}")
            }
        }
        setup()
    }

    private val CanonicalTestEnv.storage get() = avatarStorage as FakeAvatarStorage

    private fun ApplicationTestBuilder.mount(env: CanonicalTestEnv) = application { env.mount(this) }

    private suspend fun HttpClient.putAvatar(bytes: ByteArray?, type: String? = "image/jpeg", token: String? = TEST_TOKEN, contentLength: Boolean = true): HttpResponse =
        request("/api/v1/app/me/avatar") {
            method = HttpMethod.Put
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (bytes != null) {
                setBody(
                    object : io.ktor.http.content.OutgoingContent.ByteArrayContent() {
                        override val contentType: ContentType? = type?.let(ContentType::parse)
                        override val contentLength: Long? = if (contentLength) bytes.size.toLong() else null
                        override fun bytes(): ByteArray = bytes
                    },
                )
            }
        }

    private suspend fun HttpClient.deleteAvatar(): HttpResponse = request("/api/v1/app/me/avatar") {
        method = HttpMethod.Delete
        header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    // ------------------------------------------------------------------ D1-14 PUT

    @Test
    fun aJpegIsSanitizedStoredUnderItsHashRecordedAndAnsweredWithASignedUrl() = testApplication {
        val env = env()
        mount(env)
        val response = client.putAvatar(image("jpg"))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])

        val (objectPath, stored) = env.storage.puts.single()
        assertEquals("$TEST_UID/${sha(stored)}.jpg", objectPath, "content-addressed object of the JWT subject")
        assertEquals("image/jpeg", ForoImageSanitizer.sniff(stored))
        val decoded = ImageIO.read(stored.inputStream())
        assertEquals(decoded.width, decoded.height, "square crop")
        assertEquals(48, decoded.width, "never upscaled: min(side, 512)")

        val args = env.upstream.lastArgs("app_set_avatar")
        assertEquals(TEST_UID, args["p_usuario_id"]!!.jsonPrimitive.content)
        assertEquals("avatars/$objectPath", args["p_path"]!!.jsonPrimitive.content)

        val body = response.json()
        assertEquals(setOf("avatarUrl", "expiresAt"), body.keys)
        assertTrue(body.str("avatarUrl")!!.startsWith("https://example.supabase.co/storage/v1/object/sign/avatars/$objectPath?token="))
        assertEquals("2026-09-30T13:00:00Z", body.str("expiresAt"), "TTL 1 h from the server clock")
        assertTrue(env.storage.deletes.isEmpty())
    }

    @Test
    fun aLargePngWithAlphaBecomesA512SquareJpeg() = testApplication {
        val env = env()
        mount(env)
        val response = client.putAvatar(image("png", 1200, 800), type = "image/png")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val stored = env.storage.puts.single().second
        assertEquals("image/jpeg", ForoImageSanitizer.sniff(stored))
        val decoded = ImageIO.read(stored.inputStream())
        assertEquals(512, decoded.width)
        assertEquals(512, decoded.height)
    }

    @Test
    fun theSameBytesTwiceAreTheSamePathAndNothingIsDeleted() = testApplication {
        val env = env()
        var current: String? = null
        env.upstream.onRpc = { function, args ->
            val path = args["p_path"]?.jsonPrimitive?.content
            if (function == "app_set_avatar") {
                val changed = current != path
                val previous = if (changed) current else null
                current = path
                rpcOk("""{"avatarPath":"$path","previousPath":${previous?.let { "\"$it\"" } ?: "null"},"changed":$changed}""")
            } else {
                rpcOk("{}")
            }
        }
        mount(env)
        val bytes = image("jpg")
        val first = client.putAvatar(bytes).json()
        val second = client.putAvatar(bytes).json()
        assertEquals(2, env.storage.puts.size)
        assertEquals(env.storage.puts[0].first, env.storage.puts[1].first, "idempotent: same bytes -> same object")
        assertEquals(first.str("avatarUrl"), second.str("avatarUrl"), "the cached signature is reused while fresh")
        assertEquals(1, env.storage.signRequests.size)
        assertTrue(env.storage.deletes.isEmpty(), "replacing an avatar with itself never deletes it")

        // a different picture replaces it and the previous object is deleted
        val other = image("jpg") { it.color = Color(10, 120, 200); it.fillRect(0, 0, 64, 48) }
        assertEquals(HttpStatusCode.OK, client.putAvatar(other).status)
        assertEquals(listOf(env.storage.puts[0].first), env.storage.deletes.toList())
    }

    @Test
    fun anExternalOrForeignPreviousValueIsNeverDeleted() = testApplication {
        val env = env()
        val previousValues = ArrayDeque(listOf("https://lh3.googleusercontent.com/a/photo.jpg", "avatars/$otherUid/${"b".repeat(64)}.jpg"))
        env.upstream.onRpc = { function, args ->
            if (function == "app_set_avatar") {
                rpcOk("""{"avatarPath":"${args["p_path"]!!.jsonPrimitive.content}","previousPath":"${previousValues.removeFirst()}","changed":true}""")
            } else {
                rpcOk("{}")
            }
        }
        mount(env)
        assertEquals(HttpStatusCode.OK, client.putAvatar(image("jpg")).status)
        assertEquals(HttpStatusCode.OK, client.putAvatar(image("jpg", 70, 70)).status)
        assertTrue(env.storage.deletes.isEmpty(), "only an own server-owned object is ever deleted: ${env.storage.deletes}")
    }

    @Test
    fun aDeleteFailureOfThePreviousObjectNeverFailsTheUpload() = testApplication {
        val env = env()
        env.upstream.onRpc = { _, args ->
            rpcOk("""{"avatarPath":"${args["p_path"]!!.jsonPrimitive.content}","previousPath":"avatars/$TEST_UID/${"c".repeat(64)}.jpg","changed":true}""")
        }
        env.storage.deleteFailure = IllegalStateException("storage down")
        mount(env)
        assertEquals(HttpStatusCode.OK, client.putAvatar(image("jpg")).status)
    }

    @Test
    fun wrongOrMissingContentTypeIs415AndNothingIsReadOrStored() = testApplication {
        val env = env()
        mount(env)
        // application/json is the FA53 preset body now (covered by the preset tests)
        listOf("image/webp", "text/plain", "image/gif", "multipart/form-data; boundary=x", null).forEach { type ->
            val response = client.putAvatar(image("jpg"), type = type)
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, "$type")
            assertEquals("UNSUPPORTED_MEDIA_TYPE", response.json().str("code"))
        }
        assertTrue(env.storage.puts.isEmpty() && env.upstream.rpcCalls.isEmpty())
    }

    @Test
    fun theSizeLimitsAreEnforcedOnContentLengthBeforeReading() = testApplication {
        val env = env()
        mount(env)
        val tooBig = client.putAvatar(ByteArray(AVATAR_MAX_BYTES + 1) { 1 })
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)
        assertEquals("PAYLOAD_TOO_LARGE", tooBig.json().str("code"))
        val empty = client.putAvatar(ByteArray(0))
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertEquals("VALIDATION", empty.json().str("code"))
        assertTrue(env.storage.puts.isEmpty() && env.upstream.rpcCalls.isEmpty())
        assertEquals(2_000_000, AVATAR_MAX_BYTES, "2 MB: the same number as Caddy max_size 2MB (SI) and under the Ktor 2 MiB cap")
        assertTrue(AVATAR_MAX_BYTES <= REQUEST_BODY_MAX_BYTES, "no RequestBodyLimit exemption is needed")
    }

    @Test
    fun bytesThatAreNotTheDeclaredFormatAreRejected422() = testApplication {
        val env = env()
        mount(env)
        val pngDeclaredJpeg = client.putAvatar(image("png"), type = "image/jpeg")
        assertEquals(HttpStatusCode.UnprocessableEntity, pngDeclaredJpeg.status)
        assertEquals("IMAGE_REJECTED", pngDeclaredJpeg.json().str("code"))
        val garbage = client.putAvatar("not an image at all".toByteArray(), type = "image/png")
        assertEquals("IMAGE_REJECTED", garbage.json().str("code"))
        val truncated = image("jpg").let { it.copyOfRange(0, it.size / 3) }
        assertEquals(HttpStatusCode.UnprocessableEntity, client.putAvatar(truncated).status)
        assertTrue(env.storage.puts.isEmpty() && env.upstream.rpcCalls.isEmpty(), "nothing reaches Storage or the database")
    }

    @Test
    fun aBlockedAccountIs403AndNothingIsStored() = testApplication {
        val env = env { status = "bloqueada" }
        mount(env)
        val response = client.putAvatar(image("jpg"))
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("ACCOUNT_BLOCKED", response.json().str("code"))
        assertEquals(HttpStatusCode.Unauthorized, client.putAvatar(image("jpg"), token = null).status)
        assertTrue(env.storage.puts.isEmpty())
    }

    @Test
    fun withoutStorageWiredTheRoutesAre503() = testApplication {
        val env = CanonicalTestEnv().apply { upstream.onGet = { profileRowJson() } }
        mount(env)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.putAvatar(image("jpg")).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.deleteAvatar().status)
    }

    // ------------------------------------------------------------------ D1-15 DELETE

    @Test
    fun deleteIs204IdempotentAndRemovesOnlyTheOwnObject() = testApplication {
        val env = env()
        val previous = ArrayDeque(listOf("\"avatars/$TEST_UID/${"d".repeat(64)}.jpg\"", "null", "\"https://lh3.googleusercontent.com/a/x.jpg\""))
        env.upstream.onRpc = { _, _ -> rpcOk("""{"previousPath":${previous.removeFirst()},"changed":true}""") }
        mount(env)
        repeat(3) {
            val response = client.deleteAvatar()
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("", response.bodyAsText())
        }
        assertEquals(listOf("$TEST_UID/${"d".repeat(64)}.jpg"), env.storage.deletes.toList())
        assertEquals(List(3) { "app_clear_avatar" }, env.upstream.rpcNames())
        assertEquals(TEST_UID, env.upstream.lastArgs("app_clear_avatar")["p_usuario_id"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ GET /me

    private fun rowWithAvatar(avatar: String?) =
        profileRowJson().replace("\"actualizado_en\"", "\"avatar_url\":${avatar?.let { "\"$it\"" } ?: "null"},\"actualizado_en\"")

    @Test
    fun meSignsOnlyTheOwnServerOwnedObject() = testApplication {
        val env = env()
        val own = "avatars/$TEST_UID/${"e".repeat(64)}.jpg"
        var stored: String? = own
        env.upstream.onGet = { rowWithAvatar(stored) }
        mount(env)
        val signed = client.request("/api/v1/app/me") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }.json()
        assertEquals(
            "https://example.supabase.co/storage/v1/object/sign/avatars/$TEST_UID/${"e".repeat(64)}.jpg?token=t3600",
            signed["profile"]!!.jsonObject.str("avatarUrl"),
        )
        assertTrue("avatar_url" in env.upstream.log.first(), "the column is read")

        listOf("https://lh3.googleusercontent.com/a/photo.jpg", "avatars/$otherUid/${"e".repeat(64)}.jpg", "avatars/$TEST_UID/../x.jpg", null).forEach { value ->
            stored = value
            val me = client.request("/api/v1/app/me") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
            val text = me.bodyAsText()
            assertNull(Json.parseToJsonElement(text).jsonObject["profile"]!!.jsonObject.str("avatarUrl"), "$value")
            if (value != null) assertFalse(value in text, "the stored value never travels: $text")
        }
        assertEquals(1, env.storage.signRequests.size, "an external URL is never sent to Storage: ${env.storage.signRequests}")
    }

    @Test
    fun aStorageOutageDegradesMeToANullAvatar() = testApplication {
        val env = env()
        env.upstream.onGet = { rowWithAvatar("avatars/$TEST_UID/${"f".repeat(64)}.jpg") }
        env.storage.signFailure = IllegalStateException("storage down")
        mount(env)
        val response = client.request("/api/v1/app/me") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.json()["profile"]!!.jsonObject.str("avatarUrl"))
    }

    // ------------------------------------------------------------------ exposicion a otros

    @Test
    fun avatarPathsAreRewrittenToSignedUrlsAndNeverForwarded() = runBlocking {
        val storage = FakeAvatarStorage()
        val signer = AvatarUrlSigner(storage)
        val own = "avatars/$otherUid/${"a".repeat(64)}.jpg"
        val raw = Json.parseToJsonElement(
            """{"items":[{"id":"p1","autor":{"handle":"ana","avatarPath":"$own"}},{"id":"p2","autor":{"handle":"bea","avatarPath":"https://evil.example/x.jpg"}},
               {"id":"p3","autor":{"handle":null,"avatarPath":null}},{"id":"p4","autor":{"handle":"cris"}}],"nextCursor":null}""",
        )
        val rewritten = rewriteAvatarPaths(raw, signer)
        val text = rewritten.toString()
        assertFalse("avatarPath" in text, text)
        assertFalse("evil.example" in text, text)
        val items = rewritten.jsonObject["items"]!!.jsonArray.map { it.jsonObject["autor"]!!.jsonObject }
        assertEquals("https://example.supabase.co/storage/v1/object/sign/avatars/$otherUid/${"a".repeat(64)}.jpg?token=t3600", items[0].str("avatarUrl"))
        assertEquals(JsonNull, items[1]["avatarUrl"])
        assertEquals(JsonNull, items[2]["avatarUrl"])
        assertFalse(items[3].containsKey("avatarUrl"), "nothing is invented when the database sent no avatarPath")
        assertEquals(listOf(listOf("$otherUid/${"a".repeat(64)}.jpg")), storage.signRequests.map { it.toList() }, "one batch, only server-owned paths")

        // without Storage the path still never leaves: null
        val unsigned = rewriteAvatarPaths(raw, null).toString()
        assertFalse("avatarPath" in unsigned || own in unsigned, unsigned)
    }

    @Test
    fun rankingTop3CarriesAvatarUrlOnlyWhereTheDatabaseAllowedIt() = runBlocking {
        val storage = FakeAvatarStorage()
        val upstream = object : EconomyUpstream {
            override fun get(path: String): String = "[]"
            override fun rpc(function: String, body: String) = SupabaseRawResponse(
                200,
                // the database strips the null avatarPath of a hidden / avatar-less user (jsonb_strip_nulls)
                """{"top3":[{"posicion":1,"handle":"ana","notaMedia":9.0,"avatarPath":"avatars/$otherUid/${"a".repeat(64)}.jpg"},{"posicion":2,"handle":"bea","notaMedia":8.0}],"visibilidad":{"publico":false},"probabilidadPlaza":{"disponible":false,"motivo":"sin_liga"},"liga":null}""",
            )
        }
        val view = RankingService(upstream, avatarSigner = AvatarUrlSigner(storage)).view(CanonicalUserId.fromJwtSubject(TEST_UID)).jsonObject
        val top3 = view["top3"]!!.jsonArray.map { it.jsonObject }
        assertNotNull(top3[0].str("avatarUrl"))
        assertTrue(top3[1].containsKey("avatarUrl") && top3[1]["avatarUrl"] == JsonNull, "a user without a servable avatar: explicit null")
        assertFalse("avatarPath" in view.toString())
        assertEquals(JsonNull, view["liga"], "the rest of the payload is untouched")
    }

    // ------------------------------------------------------------------ FA53 avatares predefinidos

    private suspend fun HttpClient.putPreset(body: String): HttpResponse = request("/api/v1/app/me/avatar") {
        method = HttpMethod.Put
        header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
        setBody(io.ktor.http.content.TextContent(body, ContentType.Application.Json))
    }

    private fun presetEnv(photoUploads: Boolean = false, vararg previous: String?) =
        CanonicalTestEnv(avatarStorage = FakeAvatarStorage(), avatarPhotoUploads = photoUploads).apply {
            val queue = ArrayDeque(previous.toList())
            upstream.onGet = { profileRowJson() }
            upstream.onRpc = { function, args ->
                when (function) {
                    "app_set_avatar_preset" -> {
                        val prev = queue.removeFirstOrNull()
                        rpcOk("""{"avatarId":${args["p_avatar_id"]},"previousPath":${prev?.let { "\"$it\"" } ?: "null"},"changed":true}""")
                    }
                    else -> rpcOk("{}")
                }
            }
        }

    @Test
    fun aPresetIsStoredAnsweredWithItsIdAndTheOwnPhotoIsDeleted() = testApplication {
        val own = "avatars/$TEST_UID/${"9".repeat(64)}.jpg"
        val env = presetEnv(false, own)
        mount(env)
        val response = client.putPreset("""{"avatarId":3}""")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(mapOf("avatarId" to "3"), response.json().mapValues { it.value.jsonPrimitive.content }, "only avatarId (nulls are omitted)")
        val args = env.upstream.lastArgs("app_set_avatar_preset")
        assertEquals(TEST_UID, args["p_usuario_id"]!!.jsonPrimitive.content, "the JWT subject, never the body")
        assertEquals("3", args["p_avatar_id"]!!.jsonPrimitive.content)
        assertEquals(listOf("$TEST_UID/${"9".repeat(64)}.jpg"), env.storage.deletes.toList(), "the replaced photo object is deleted")
        assertTrue(env.storage.puts.isEmpty())
    }

    @Test
    fun aPresetNeverDeletesAForeignOrExternalPreviousValue() = testApplication {
        val env = presetEnv(false, "https://lh3.googleusercontent.com/a/photo.jpg", "avatars/$otherUid/${"b".repeat(64)}.jpg")
        mount(env)
        assertEquals(HttpStatusCode.OK, client.putPreset("""{"avatarId":10}""").status)
        assertEquals(HttpStatusCode.OK, client.putPreset("""{"avatarId":1}""").status)
        assertTrue(env.storage.deletes.isEmpty(), "only an own server-owned object is ever deleted: ${env.storage.deletes}")
    }

    @Test
    fun anOutOfRangePresetIs422AndAMalformedBodyIs400() = testApplication {
        val env = presetEnv()
        mount(env)
        listOf("0", "11", "-1", "32767").forEach { id ->
            val response = client.putPreset("""{"avatarId":$id}""")
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, id)
            assertEquals("AVATAR_ID_INVALID", response.json().str("code"), id)
        }
        listOf("""{"avatarId":3.5}""", """{"avatarId":3,"usuarioId":"x"}""", "{}", "").forEach { body ->
            val response = client.putPreset(body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertEquals("VALIDATION", response.json().str("code"), body)
        }
        assertTrue(env.upstream.rpcCalls.isEmpty(), "nothing invalid reaches the database")
    }

    @Test
    fun photoUploadsAreOffByDefaultWith415ButThePresetStillWorks() = testApplication {
        val env = presetEnv(photoUploads = false)
        mount(env)
        listOf("image/jpeg", "image/png").forEach { type ->
            val response = client.putAvatar(image(if (type == "image/png") "png" else "jpg"), type = type)
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, type)
            assertEquals("UNSUPPORTED_MEDIA_TYPE", response.json().str("code"))
        }
        assertTrue(env.storage.puts.isEmpty() && env.upstream.rpcCalls.isEmpty(), "a photo never reaches Storage nor the database")
        assertEquals(HttpStatusCode.OK, client.putPreset("""{"avatarId":1}""").status)
        assertFalse(AvatarService({ _, _ -> error("unused") }, FakeAvatarStorage(), AvatarUrlSigner(FakeAvatarStorage())).photoUploadsEnabled, "default off")
    }

    @Test
    fun meCarriesThePresetId() = testApplication {
        val env = env()
        var id: String = "4"
        env.upstream.onGet = { profileRowJson().replace("\"actualizado_en\"", "\"avatar_url\":null,\"avatar_id\":$id,\"actualizado_en\"") }
        mount(env)
        val me = client.request("/api/v1/app/me") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }.json()["profile"]!!.jsonObject
        assertEquals("4", me["avatarId"]!!.jsonPrimitive.content)
        assertNull(me.str("avatarUrl"))
        assertTrue("avatar_id" in env.upstream.log.first(), "the column is read")
        id = "null"
        val none = client.request("/api/v1/app/me") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }.json()["profile"]!!.jsonObject
        assertTrue(none["avatarId"] == null || none["avatarId"] == JsonNull, "no preset: null or absent")
    }

    @Test
    fun rankingAndForoCarryAvatarIdNextToAvatarUrl() = runBlocking {
        val storage = FakeAvatarStorage()
        val upstream = object : EconomyUpstream {
            override fun get(path: String): String = "[]"
            override fun rpc(function: String, body: String) = SupabaseRawResponse(
                200,
                """{"top3":[{"posicion":1,"handle":"ana","notaMedia":9.0,"avatarId":7},{"posicion":2,"handle":"bea","notaMedia":8.0}],"visibilidad":{"publico":true},"liga":null}""",
            )
        }
        val top3 = RankingService(upstream, avatarSigner = AvatarUrlSigner(storage)).view(CanonicalUserId.fromJwtSubject(TEST_UID))
            .jsonObject["top3"]!!.jsonArray.map { it.jsonObject }
        assertEquals("7", top3[0]["avatarId"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, top3[0]["avatarUrl"])
        assertEquals(JsonNull, top3[1]["avatarId"], "always sent, explicit null when the database stripped it")

        val foro = rewriteAvatarPaths(Json.parseToJsonElement("""{"items":[{"id":"p1","autor":{"handle":"ana","avatarPath":null,"avatarId":2}}]}"""), AvatarUrlSigner(storage))
        val autor = foro.jsonObject["items"]!!.jsonArray[0].jsonObject["autor"]!!.jsonObject
        assertEquals("2", autor["avatarId"]!!.jsonPrimitive.content, "avatarId passes through untouched")
        assertEquals(JsonNull, autor["avatarUrl"])
    }

    // ------------------------------------------------------------------ saneado del avatar

    @Test
    fun theExifOrientationIsAppliedBeforeTheSquareCrop() {
        // 200x100: left half red, right half blue. Orientation 6 (rotate 90 deg clockwise) puts red on TOP of a 100x200 image.
        val jpeg = image("jpg", 200, 100) { g -> g.color = Color.RED; g.fillRect(0, 0, 100, 100); g.color = Color.BLUE; g.fillRect(100, 0, 100, 100) }
        val rotated = withExifOrientation(jpeg, 6)
        assertEquals(6, MediaCompression.exifOrientation(rotated))
        val ok = ForoImageSanitizer.sanitize(rotated, "image/jpeg", output = ImageOutputSpec.AVATAR, maxBytes = AVATAR_MAX_BYTES) as SanitizedImage.Ok
        val out = ImageIO.read(ok.bytes.inputStream())
        assertEquals(100, out.width)
        assertEquals(100, out.height)
        assertTrue(isRed(out.getRGB(50, 10)), "top is red after the rotation")
        assertTrue(isBlue(out.getRGB(50, 90)), "bottom is blue after the rotation")
        assertEquals(1, MediaCompression.exifOrientation(ok.bytes), "no EXIF survives the re-encode")
    }

    private fun isRed(rgb: Int) = (rgb shr 16 and 0xFF) > 150 && (rgb and 0xFF) < 100
    private fun isBlue(rgb: Int) = (rgb and 0xFF) > 150 && (rgb shr 16 and 0xFF) < 100

    /** APP1 Exif (TIFF little-endian, IFD0 con una sola entrada Orientation) justo tras el SOI. */
    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(), 42, 0, 8, 0, 0, 0, // header, IFD0 at 8
            1, 0, // one entry
            0x12, 0x01, 3, 0, 1, 0, 0, 0, orientation.toByte(), 0, 0, 0, // tag 0x0112 SHORT count 1 value
            0, 0, 0, 0, // next IFD
        )
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val len = payload.size + 2
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload + jpeg.copyOfRange(2, jpeg.size)
    }

}
