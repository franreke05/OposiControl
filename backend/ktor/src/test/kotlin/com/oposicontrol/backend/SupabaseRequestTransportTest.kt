package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El transporte compartido: lo que sale por el cable y cómo se traduce lo que
 * vuelve. Estos casos son la base que reutilizan los tres gateways migrados, así
 * que se prueban aquí una vez en vez de tres veces por gateway.
 */
class SupabaseRequestTransportTest {

    private fun transportWith(
        secretKey: String = "sb_secret_test_fixture",
        record: (HttpRequest) -> Unit = {},
        responder: (String) -> V1StubResponse,
    ): SupabaseRequestTransport = SupabaseRequestTransport.forTesting(
        baseUrl = "https://stub.supabase.test",
        secretKey = secretKey,
        client = V1StubHttpClient(record, responder),
    )

    // ----- credenciales -----

    @Test fun aSecretKeyTravelsOnlyAsApiKey() {
        val sent = mutableListOf<HttpRequest>()
        transportWith(record = { sent += it }) { V1StubResponse(200, "[]") }.get("/rest/v1/examenes")

        assertEquals("sb_secret_test_fixture", sent.single().headers().firstValue("apikey").orElseThrow())
        assertTrue(sent.single().headers().firstValue("Authorization").isEmpty)
    }

    @Test fun aJwtShapedKeyAlsoTravelsAsBearer() {
        val sent = mutableListOf<HttpRequest>()
        transportWith(secretKey = "a.b.c", record = { sent += it }) { V1StubResponse(200, "[]") }
            .get("/rest/v1/examenes")

        assertEquals("Bearer a.b.c", sent.single().headers().firstValue("Authorization").orElseThrow())
    }

    /** En una petición user-scoped gana el usuario, nunca la credencial de servidor. */
    @Test fun aUserScopedRequestCarriesTheUserJwt() {
        val sent = mutableListOf<HttpRequest>()
        transportWith(secretKey = "server.key.jwt", record = { sent += it }) { V1StubResponse(200, "[]") }
            .get("/rest/v1/intentos", scope = SupabaseAuthScope.User("x.y.z"))

        assertEquals("Bearer x.y.z", sent.single().headers().firstValue("Authorization").orElseThrow())
        assertEquals("server.key.jwt", sent.single().headers().firstValue("apikey").orElseThrow())
    }

    // ----- errores: BackendErrors es el único sistema -----

    /**
     * Un código de dominio levantado por una RPC sale al cliente tal cual, con
     * el status y el mensaje humano de la tabla. Los gateways migrados ganan
     * esto por estar el mapeo en el único sitio por donde pasan todas las
     * respuestas: dos de los trece gateways lo llamaban, once no.
     */
    @Test fun aDomainErrorBecomesATypedBackendException() {
        val transport = transportWith {
            V1StubResponse(400, """{"message":"ROLE_SYNC_LAST_SUPERADMIN"}""")
        }

        val error = assertFailsWith<BackendDomainException> { transport.rpc("admin_sync_roles", "{}") }

        assertEquals("ROLE_SYNC_LAST_SUPERADMIN", error.errorCode)
        assertEquals(HttpStatusCode.Conflict, error.status)
    }

    /**
     * Y un error técnico sigue siendo `IllegalStateException`, que es lo que
     * `StatusPages` **redacta**. No se introduce ningún tipo de error nuevo:
     * un segundo tipo obligaría a un segundo handler y a duplicar la redacción,
     * que es justo la parte que no puede estar duplicada — el cuerpo de
     * PostgREST lleva `hint`s que son un oráculo de esquema.
     */
    @Test fun aTechnicalErrorStaysRedactable() {
        val transport = transportWith { V1StubResponse(500, """{"message":"boom"}""") }

        assertFailsWith<IllegalStateException> { transport.get("/rest/v1/examenes") }
    }

    /** Un eco de la entrada del cliente no fabrica un error de dominio. */
    @Test fun anEchoedCodeInsideAMessageDoesNotFabricateADomainError() {
        val transport = transportWith {
            V1StubResponse(
                400,
                """{"message":"invalid input syntax for type uuid: \"ROLE_SYNC_LAST_SUPERADMIN\""}""",
            )
        }

        assertFailsWith<IllegalStateException> { transport.get("/rest/v1/usuarios") }
    }

    // ----- rpc -----

    @Test fun rpcComposesThePathFromTheBareFunctionName() {
        val sent = mutableListOf<HttpRequest>()
        transportWith(record = { sent += it }) { V1StubResponse(200, "{}") }
            .rpc("admin_create_question", """{"titulo":"x"}""")

        assertEquals(
            "https://stub.supabase.test/rest/v1/rpc/admin_create_question",
            sent.single().uri().toString(),
        )
        assertEquals("POST", sent.single().method())
    }

    // ----- count -----

    @Test fun countReadsTheTotalFromContentRange() {
        val sent = mutableListOf<HttpRequest>()
        val transport = transportWith(record = { sent += it }) {
            V1StubResponse(206, "[]", contentRange = "0-0/42")
        }

        assertEquals(42, transport.count("/rest/v1/examenes?select=id"))
        val request = sent.single()
        assertEquals("count=exact", request.headers().firstValue("Prefer").orElseThrow())
        assertEquals("0-0", request.headers().firstValue("Range").orElseThrow())
    }

    /**
     * Sin `Content-Range` utilizable el conteo LANZA. Devolver 0 haría que
     * "no se pudo contar" y "hay cero filas" fueran indistinguibles, que es
     * exactamente la confusión que el dashboard existe para evitar.
     */
    @Test fun countRefusesToInventAZero() {
        val transport = transportWith { V1StubResponse(206, "[]", contentRange = "0-0/*") }

        assertFailsWith<IllegalStateException> { transport.count("/rest/v1/examenes?select=id") }
    }

    // ----- storage -----

    @Test fun deletingAMissingObjectIsFalseAndNotAnError() {
        val transport = transportWith { V1StubResponse(404, """{"error":"not_found"}""") }

        assertFalse(transport.storageDelete("exam-resources", "exam-covers/EX1/p.jpg"))
    }

    @Test fun deletingAnExistingObjectIsTrue() {
        val transport = transportWith { V1StubResponse(200, "{}") }

        assertTrue(transport.storageDelete("exam-resources", "exam-covers/EX1/p.jpg"))
    }

    /** Las barras del path son separadores; sólo se codifica dentro del segmento. */
    @Test fun objectPathSegmentsAreEncodedButSlashesAreNot() {
        val sent = mutableListOf<HttpRequest>()
        transportWith(record = { sent += it }) { V1StubResponse(200, "{}") }
            .storageDelete("exam-resources", "exam-covers/EX 1/portada final.jpg")

        assertEquals(
            "https://stub.supabase.test/storage/v1/object/exam-resources/exam-covers/EX%201/portada%20final.jpg",
            sent.single().uri().toString(),
        )
    }

    /** El memo evita repetir la comprobación de bucket, que es un hecho del transporte. */
    @Test fun theBucketCheckHappensOncePerBucket() {
        val sent = mutableListOf<HttpRequest>()
        val transport = transportWith(record = { sent += it }) { V1StubResponse(409, "{}") }

        transport.storageEnsureBucket("exam-resources", public = false)
        transport.storageEnsureBucket("exam-resources", public = false)
        transport.storageEnsureBucket("news-images", public = false)

        assertEquals(2, sent.size, "un bucket comprobado no se vuelve a comprobar; otro distinto sí")
    }

    /**
     * F-11: `public` es OBLIGATORIO. Con `= true` por defecto, olvidar el argumento
     * creaba un bucket público en un entorno limpio. Kotlin compila los parámetros
     * con valor por defecto en un método sintético `…$default`; si no existe, ningún
     * parámetro de la función tiene default. (Sin `kotlin-reflect`: la JVM basta.)
     */
    @Test fun theBucketVisibilityHasNoDefaultValue() {
        val defaults = SupabaseRequestTransport::class.java.declaredMethods
            .filter { it.name == "storageEnsureBucket\$default" }

        assertTrue(defaults.isEmpty(), "storageEnsureBucket volvió a tener un parámetro con valor por defecto")
    }

    /** Storage actual devuelve este conflicto como HTTP 400 aunque el cuerpo conserve 409. */
    @Test fun bucketAlreadyExistsInsideAStorageFourHundredIsSuccessAndIsMemoized() {
        val sent = mutableListOf<HttpRequest>()
        val transport = transportWith(record = { sent += it }) {
            V1StubResponse(
                400,
                """{"statusCode":"409","error":"Duplicate","message":"The resource already exists","code":"BucketAlreadyExists"}""",
            )
        }

        transport.storageEnsureBucket("exam-resources", public = false)
        transport.storageEnsureBucket("exam-resources", public = false)

        assertEquals(1, sent.size)
    }

    @Test fun anUnrelatedStorageFourHundredStillFailsAndIsNotMemoized() {
        val sent = mutableListOf<HttpRequest>()
        val transport = transportWith(record = { sent += it }) {
            V1StubResponse(400, """{"statusCode":"400","code":"InvalidRequest"}""")
        }

        repeat(2) {
            assertFailsWith<IllegalStateException> {
                transport.storageEnsureBucket("exam-resources", public = false)
            }
        }

        assertEquals(2, sent.size)
    }

    @Test fun aPrivateBucketIsRequestedAsPrivate() {
        val sent = mutableListOf<HttpRequest>()
        transportWith(record = { sent += it }) { V1StubResponse(200, "{}") }
            .storageEnsureBucket("exam-resources", public = false)

        assertTrue(readRequestBody(sent.single()).contains("\"public\":false"), readRequestBody(sent.single()))
    }

    /**
     * Una comprobación FALLIDA no se apunta como hecha. Lo contrario dejaría el
     * siguiente upload subiendo a un bucket que nadie confirmó que exista.
     */
    @Test fun aFailedBucketCheckIsNotMemoized() {
        val sent = mutableListOf<HttpRequest>()
        val transport = transportWith(record = { sent += it }) { V1StubResponse(500, "boom") }

        repeat(2) { assertFailsWith<IllegalStateException> { transport.storageEnsureBucket("exam-resources", public = false) } }

        assertEquals(2, sent.size)
    }

    /**
     * F-06. Un carácter ilegal en la ruta hacía que `URI.create` lanzara
     * `IllegalArgumentException` con la URL COMPLETA en el mensaje, y
     * `StatusPages` publica esos mensajes en el 400: host del proyecto, tabla y
     * filtro llegaban al cliente. Ahora sale un `IllegalStateException` (500
     * redactado) cuyo texto, que sólo va al log, no lleva ni el host ni la query.
     */
    @Test fun anIllegalCharacterInThePathNeverLeaksTheProjectUrl() {
        val sent = mutableListOf<HttpRequest>()
        val transport = transportWith(record = { sent += it }) { V1StubResponse(200, "[]") }

        val failure = assertFailsWith<IllegalStateException> {
            transport.get("/rest/v1/especialidades?id=eq.id con espacio")
        }

        val message = failure.message.orEmpty()
        assertFalse(message.contains("stub.supabase.test"), "el mensaje filtra el host del proyecto: $message")
        assertFalse(message.contains("con espacio"), "el mensaje repite el filtro del cliente: $message")
        assertTrue(message.contains("/rest/v1/especialidades"), "sin el path no se sabe qué llamada falló: $message")
        assertTrue(sent.isEmpty(), "una ruta inválida no puede llegar a salir por el cable")
    }

    // ----- K5-1 · URL firmada de Storage -----

    /**
     * Firmar es un POST elevado a `/storage/v1/object/sign/<bucket>/<ruta>` con
     * `{"expiresIn":N}`. La ruta se codifica por segmento, como en la subida.
     */
    @Test fun aSignedUrlIsAnElevatedPostWithTheExpiry() {
        val sent = mutableListOf<HttpRequest>()
        val transport = transportWith(record = { sent += it }) {
            V1StubResponse(200, """{"signedURL":"/object/sign/exam-resources/exam-covers/e1/a.png?token=t"}""")
        }

        transport.storageSignedUrl("exam-resources", "exam-covers/e1/a.png", 3600)

        val request = sent.single()
        assertEquals("POST", request.method())
        assertEquals("/storage/v1/object/sign/exam-resources/exam-covers/e1/a.png", request.uri().rawPath)
        assertTrue(readRequestBody(request).contains("\"expiresIn\":3600"), readRequestBody(request))
        assertEquals("sb_secret_test_fixture", request.headers().firstValue("apikey").orElseThrow())
    }

    /**
     * Las TRES formas de `signedURL` que puede devolver Storage acaban en la misma
     * URL absoluta del proyecto. La forma exacta de MAIN la confirma el bucle vivo;
     * hasta entonces ninguna de las tres puede producir una URL rota.
     */
    @Test fun everyShapeOfSignedUrlBecomesTheSameAbsoluteUrl() {
        val esperada = "https://stub.supabase.test/storage/v1/object/sign/exam-resources/a.png?token=t"
        listOf(
            "/object/sign/exam-resources/a.png?token=t",
            "/storage/v1/object/sign/exam-resources/a.png?token=t",
            esperada,
        ).forEach { forma ->
            val transport = transportWith { V1StubResponse(200, """{"signedURL":"$forma"}""") }

            assertEquals(esperada, transport.storageSignedUrl("exam-resources", "a.png", 60), "forma: $forma")
        }
    }

    /** Un error de Storage o una respuesta sin `signedURL` es un fallo, nunca una URL inventada. */
    @Test fun aSigningFailureIsAnErrorAndNotAnInventedUrl() {
        assertFailsWith<IllegalStateException> {
            transportWith { V1StubResponse(400, """{"error":"not_found"}""") }.storageSignedUrl("exam-resources", "a.png", 60)
        }
        assertFailsWith<IllegalStateException> {
            transportWith { V1StubResponse(200, """{"otra":"cosa"}""") }.storageSignedUrl("exam-resources", "a.png", 60)
        }
    }

    /**
     * La variante por lotes hace UNA petición para todas las rutas, y una ruta que
     * Storage no pudo firmar sale con `null` sin impedir las demás.
     */
    @Test fun batchSigningIsOneRequestAndAnUnsignedPathIsNull() {
        val sent = mutableListOf<HttpRequest>()
        val transport = transportWith(record = { sent += it }) {
            V1StubResponse(
                200,
                """[{"path":"exam-covers/e1/a.png","signedURL":"/object/sign/exam-resources/exam-covers/e1/a.png?token=1","error":null},""" +
                    """{"path":"exam-covers/e2/b.png","signedURL":null,"error":"Either the object does not exist"}]""",
            )
        }

        val firmadas = transport.storageSignedUrls("exam-resources", listOf("exam-covers/e1/a.png", "exam-covers/e2/b.png"), 3600)

        val request = sent.single()
        assertEquals("POST", request.method())
        assertEquals("/storage/v1/object/sign/exam-resources", request.uri().rawPath)
        val cuerpo = readRequestBody(request)
        assertTrue(cuerpo.contains("\"expiresIn\":3600") && cuerpo.contains("exam-covers/e1/a.png"), cuerpo)
        assertEquals(
            "https://stub.supabase.test/storage/v1/object/sign/exam-resources/exam-covers/e1/a.png?token=1",
            firmadas["exam-covers/e1/a.png"],
        )
        assertTrue(firmadas.containsKey("exam-covers/e2/b.png"))
        assertEquals(null, firmadas["exam-covers/e2/b.png"])
    }

    @Test fun batchSigningNothingMakesNoRequest() {
        val sent = mutableListOf<HttpRequest>()

        val firmadas = transportWith(record = { sent += it }) { V1StubResponse(200, "[]") }
            .storageSignedUrls("exam-resources", emptyList(), 3600)

        assertTrue(firmadas.isEmpty())
        assertTrue(sent.isEmpty())
    }

    // ----- KQ0-3 · página + total en una petición -----

    @Test fun aPageAsksForTheExactCountAndReadsTheTotalFromContentRange() {
        val sent = mutableListOf<HttpRequest>()
        val transport = transportWith(record = { sent += it }) {
            V1StubResponse(200, """[{"id":"a"},{"id":"b"}]""", contentRange = "0-1/3")
        }

        val page = transport.getPage("/rest/v1/preguntas?select=id&limit=2&offset=0")

        assertEquals(3, page.total)
        assertEquals("""[{"id":"a"},{"id":"b"}]""", page.body)
        assertEquals("count=exact", sent.single().headers().firstValue("Prefer").orElseThrow())
    }

    /** Un `offset` más allá del final (416) es una página vacía con su total, no un error. */
    @Test fun anOffsetBeyondTheEndIsAnEmptyPageWithItsTotal() {
        val page = transportWith { V1StubResponse(416, """{"message":"range not satisfiable"}""", contentRange = "*/3") }
            .getPage("/rest/v1/preguntas?select=id&limit=25&offset=100")

        assertEquals("[]", page.body)
        assertEquals(3, page.total)
    }

    /** Sin total utilizable no hay página: «no sé cuántas hay» no puede llegar como «hay 0». */
    @Test fun aPageWithoutAUsableTotalFails() {
        assertFailsWith<IllegalStateException> {
            transportWith { V1StubResponse(200, "[]") }.getPage("/rest/v1/preguntas?select=id&limit=25&offset=0")
        }
    }
}
