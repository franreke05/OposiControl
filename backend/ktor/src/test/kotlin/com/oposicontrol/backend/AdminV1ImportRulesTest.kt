package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Las reglas de la vertical de Importaciones que NO necesitan migración (KI0-3,
 * KI0-4, KI2): cómo nace un lote y cómo se revisa una fila.
 *
 * Lo que se fija aquí es **qué sale y qué no sale hacia Supabase**, nunca sólo un
 * código de estado. Un test que mirase únicamente el 422 daría por bueno un
 * rechazo que ya hubiera escrito medio lote, que es exactamente el defecto que
 * tenía la creación (IMP-04/IMP-05: lote huérfano con `total_detectadas = N` y
 * cero filas).
 *
 * La publicación (`importarLote`) vive en `AdminV1ImportPublishRpcTest`: desde
 * KI3-1 es UNA llamada a la RPC y ya no hay bucle que probar aquí.
 */
class AdminV1ImportRulesTest {

    private val loteId = "11111111-1111-4111-8111-111111111111"
    private val examenId = "22222222-2222-4222-8222-222222222222"
    private val especialidadId = "33333333-3333-4333-8333-333333333333"
    private val filaId = "44444444-4444-4444-8444-444444444444"
    private val preguntaExistenteId = "66666666-6666-4666-8666-666666666666"
    private val actorId = "77777777-7777-4777-8777-777777777777"

    // -----------------------------------------------------------------
    // KI0-3 · Creación de lote: tipo del examen, respuesta saneada, compensación
    // -----------------------------------------------------------------

    /**
     * El bloque al que le falta la RESPUESTA (o trae una letra inventada) es el
     * error más común del PDF, y era justo el que rompía la revisión: el CHECK
     * vivo exige A–D o NULL, el parser dejaba `""`, y el INSERT de staging fallaba
     * DESPUÉS de haber insertado el lote.
     */
    @Test fun unaRespuestaIlegibleViajaComoNullYNoRompeElStaging() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        gateway.crearLote(
            CrearLoteRequest(
                examenId = examenId,
                tipoExamen = "rafaga",
                preguntas = listOf(
                    AdminStagingItemDto(posicion = 1, titulo = "Sin respuesta", respuestaCorrecta = "", estadoParseo = "error"),
                    AdminStagingItemDto(posicion = 2, titulo = "Letra inventada", respuestaCorrecta = "E", estadoParseo = "error"),
                    AdminStagingItemDto(posicion = 3, titulo = "Bien", respuestaCorrecta = "b", estadoParseo = "ok"),
                ),
            ),
        )

        val insert = assertNotNull(
            upstream.firstOrNull { it.method() == "POST" && it.uri().rawPath.endsWith("/preguntas_parseadas_staging") },
            "no salió el INSERT de staging: " + upstream.describe(),
        ).bodyText()
        assertTrue(!insert.contains("\"respuesta_correcta\":\"\""), "una respuesta vacía viajó como cadena: $insert")
        assertTrue(!insert.contains("\"respuesta_correcta\":\"E\""), "una letra fuera de A–D viajó tal cual: $insert")
        assertTrue(insert.contains("\"respuesta_correcta\":\"B\""), "la respuesta válida se normaliza a mayúscula: $insert")
    }

    /**
     * Si el INSERT de staging falla igualmente, el lote recién creado se BORRA.
     * Sin esto quedaba un lote vacío en el historial que no se podía publicar ni
     * corregir, sólo confundir.
     */
    @Test fun siElStagingFallaSeBorraElLoteReciénCreado() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(
            upstream,
            stagingInsert = V1StubResponse(500, """{"code":"XX000","message":"x-probe-c"}"""),
        )

        assertFailsWith<IllegalStateException> {
            gateway.crearLote(CrearLoteRequest(examenId = examenId, preguntas = listOf(AdminStagingItemDto(titulo = "T"))))
        }

        val compensacion = assertNotNull(
            upstream.firstOrNull { it.method() == "DELETE" && it.uri().rawPath.endsWith("/lotes_importacion_preguntas") },
            "el lote huérfano tenía que borrarse: " + upstream.describe(),
        )
        assertTrue(compensacion.uri().rawQuery.orEmpty().startsWith("id=eq."), compensacion.uri().toString())
    }

    /**
     * El tipo lo decide el EXAMEN. Un lote declarado `razonado` sobre un examen
     * de ráfaga fallaría al publicar, con el PDF ya subido y revisado: es un 422
     * antes de escribir nada.
     */
    @Test fun unTipoQueNoCoincideConElExamenEsUn422SinEscribirNada() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        val error = assertFailsWith<BackendDomainException> {
            gateway.crearLote(CrearLoteRequest(examenId = examenId, tipoExamen = "razonado"))
        }

        assertEquals("IMPORT_TYPE_MISMATCH", error.errorCode)
        assertEquals(422, error.status.value)
        assertTrue(upstream.none { it.method() == "POST" }, "se escribió algo: " + upstream.describe())
    }

    /** D-11: el lote lleva autor, y el tipo escrito es el canónico del examen. */
    @Test fun elLoteLlevaSuAutorYElTipoCanonicoDelExamen() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        gateway.crearLote(CrearLoteRequest(examenId = examenId, tipoExamen = ""), actorId = actorId)

        val insert = assertNotNull(
            upstream.firstOrNull { it.method() == "POST" && it.uri().rawPath.endsWith("/lotes_importacion_preguntas") },
        ).bodyText()
        assertTrue(insert.contains("\"subido_por\":\"$actorId\""), insert)
        assertTrue(insert.contains("\"tipo_examen\":\"rafaga\""), insert)
    }

    // -----------------------------------------------------------------
    // KI0-4 · Revisar una fila
    // -----------------------------------------------------------------

    /**
     * Aprobar (u omitir) BORRA el diagnóstico del parser: `error_parseo` viaja como
     * `null` explícito. Antes no estaba en el parche, así que una fila recién
     * corregida seguía enseñando «falta la opción D» para siempre.
     */
    @Test fun aprobarUnaFilaLimpiaSuDiagnostico() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        gateway.updateStaging(filaId, AdminStagingItemDto(titulo = "Corregida", respuestaCorrecta = "B", estadoParseo = "aprobada"))

        val patch = stagingPatch(upstream)
        assertTrue(patch.contains("\"error_parseo\":null"), patch)
        assertTrue(patch.contains("\"estado_parseo\":\"aprobada\""), patch)
    }

    /** Corregir sin decidir todavía NO toca el diagnóstico: sigue siendo cierto. */
    @Test fun corregirSinAprobarConservaElDiagnostico() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        gateway.updateStaging(filaId, AdminStagingItemDto(titulo = "A medias", estadoParseo = "error"))

        val patch = stagingPatch(upstream)
        assertTrue(!patch.contains("error_parseo"), patch)
    }

    /**
     * `importada` la escribe SÓLO la publicación: desde el cliente es un 400 sin
     * petición.
     *
     * `"importada"` no está en [STAGING_PARSE_STATES] (que sólo admite `ok`,
     * `advertencia`, `error`, `aprobada`, `omitida`), así que el SEGUNDO `require`
     * de `requireKnownStagingState` —el que valida contra ese conjunto— también
     * la rechazaría aunque el PRIMERO (`require(normalized != "importada")`, con
     * su propio mensaje) se neutralizara (MUT-KI04b). `assertFailsWith` a secas no
     * distingue cuál de los dos disparó: se afirma el MENSAJE del primero, que es
     * el único rastro de que esa guarda concreta sigue viva.
     */
    @Test fun elClienteNoPuedeMarcarUnaFilaComoImportada() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        val error = assertFailsWith<IllegalArgumentException> {
            gateway.updateStaging(filaId, AdminStagingItemDto(titulo = "T", estadoParseo = "importada"))
        }
        assertTrue(
            error.message.orEmpty().contains("importada"),
            "el rechazo tiene que venir de la guarda específica de «importada», no de la genérica: ${error.message}",
        )
        assertTrue(upstream.isEmpty(), upstream.describe())
    }

    /** Una respuesta fuera de A–D es un 400 antes de salir; vacía viaja como NULL (CHECK vivo). */
    @Test fun laRespuestaCorrectaSeValidaYVaciaViajaComoNull() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        assertFailsWith<IllegalArgumentException> {
            gateway.updateStaging(filaId, AdminStagingItemDto(titulo = "T", respuestaCorrecta = "E"))
        }
        assertTrue(upstream.isEmpty(), upstream.describe())

        gateway.updateStaging(filaId, AdminStagingItemDto(titulo = "T", respuestaCorrecta = ""))
        assertTrue(stagingPatch(upstream).contains("\"respuesta_correcta\":null"), stagingPatch(upstream))
    }

    /**
     * Mientras IM1 no esté aplicada, el CHECK vivo rechaza `aprobada`/`omitida` con
     * un 23514. El administrador tiene que leer «todavía no está disponible», nunca
     * el nombre de la restricción ni la fila entera que Postgres devuelve.
     */
    @Test fun elCheckVivoDeEstadoSeTraduceAUnErrorLegible() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(
            upstream,
            stagingPatch = V1StubResponse(
                400,
                """{"code":"23514","details":"Failing row contains (x-probe-c, SECRETO).","hint":null,""" +
                    """"message":"new row for relation \"preguntas_parseadas_staging\" violates check constraint """ +
                    """\"preguntas_parseadas_staging_estado_parseo_check\""}""",
            ),
        )

        val error = assertFailsWith<BackendDomainException> {
            gateway.updateStaging(filaId, AdminStagingItemDto(titulo = "T", estadoParseo = "aprobada"))
        }

        assertEquals("IMPORT_REVIEW_STATE_UNAVAILABLE", error.errorCode)
        assertEquals(409, error.status.value)
        listOf("x-probe-c", "SECRETO", "estado_parseo_check", "Failing row").forEach { leak ->
            assertTrue(!error.message.contains(leak), "el mensaje filtra '$leak': ${error.message}")
        }
    }

    /** Una fila que ya no existe es un 404 de dominio, no un 500. */
    @Test fun editarUnaFilaQueYaNoExisteEsUn404() {
        val gateway = gatewayWith(mutableListOf(), stagingPatch = V1StubResponse(200, "[]"))

        val error = assertFailsWith<BackendDomainException> {
            gateway.updateStaging(filaId, AdminStagingItemDto(titulo = "T"))
        }

        assertEquals("IMPORT_STAGING_NOT_FOUND", error.errorCode)
        assertEquals(404, error.status.value)
    }

    // -----------------------------------------------------------------
    // KI2 · Vincular con una pregunta existente (IM2)
    // -----------------------------------------------------------------

    /** Vincular es una forma de aprobar: viaja con `aprobada`, y `null` explícito deshace el vínculo. */
    @Test fun vincularExigeAprobadaYNullExplicitoDeshace() {
        val upstream = mutableListOf<HttpRequest>()
        val gateway = gatewayWith(upstream)

        assertFailsWith<IllegalArgumentException> {
            gateway.updateStaging(
                filaId,
                AdminStagingItemDto(titulo = "T", estadoParseo = "omitida", vincularPreguntaId = preguntaExistenteId),
            )
        }
        assertTrue(upstream.isEmpty(), upstream.describe())

        gateway.updateStaging(
            filaId,
            AdminStagingItemDto(titulo = "T", estadoParseo = "aprobada", vincularPreguntaId = preguntaExistenteId),
        )
        assertTrue(stagingPatch(upstream).contains("\"vincular_pregunta_id\":\"$preguntaExistenteId\""), stagingPatch(upstream))

        upstream.clear()
        gateway.updateStaging(filaId, AdminStagingItemDto(titulo = "T", estadoParseo = "aprobada"))
        assertTrue(stagingPatch(upstream).contains("\"vincular_pregunta_id\":null"), stagingPatch(upstream))
    }

    /**
     * OMITIR una fila que estaba aprobada con vínculo BORRA el puntero: el cuerpo hacia PostgREST
     * lleva `vincular_pregunta_id: null` explícito (antes la clave iba ausente = «no tocar» y la
     * fila omitida conservaba el vínculo, aunque el cliente afirmaba borrarlo). Con `ok`/`error`
     * el puntero sigue sin tocarse.
     */
    @Test fun omitirUnaFilaConVinculoBorraElPuntero() {
        val upstream = mutableListOf<HttpRequest>()
        val filaVinculada = stagingRowJson().replace(
            "\"estado_parseo\":\"ok\"",
            "\"estado_parseo\":\"aprobada\",\"vincular_pregunta_id\":\"$preguntaExistenteId\"",
        )
        val gateway = gatewayWith(upstream, stagingPatch = V1StubResponse(200, "[$filaVinculada]"))

        // El cliente manda `vincularPreguntaId = null` al omitir.
        gateway.updateStaging(filaId, AdminStagingItemDto(titulo = "T", estadoParseo = "omitida", vincularPreguntaId = null))

        val patch = stagingPatch(upstream)
        assertTrue(patch.contains("\"estado_parseo\":\"omitida\""), patch)
        assertTrue(patch.contains("\"vincular_pregunta_id\":null"), patch)
        assertTrue(!patch.contains(preguntaExistenteId), patch)

        upstream.clear()
        gateway.updateStaging(filaId, AdminStagingItemDto(titulo = "T", estadoParseo = "error"))
        assertTrue(!stagingPatch(upstream).contains("vincular_pregunta_id"), stagingPatch(upstream))
    }

    /** IM2: una fila ya publicada no se toca; el trigger lo dice y aquí llega como 409 legible. */
    @Test fun unaFilaYaPublicadaNoSePuedeModificar() {
        val gateway = gatewayWith(
            mutableListOf(),
            stagingPatch = V1StubResponse(400, """{"code":"P0001","details":null,"hint":null,"message":"STAGING_ROW_ALREADY_IMPORTED"}"""),
        )

        val error = assertFailsWith<BackendDomainException> {
            gateway.updateStaging(filaId, AdminStagingItemDto(titulo = "T", estadoParseo = "ok"))
        }

        assertEquals("STAGING_ROW_ALREADY_IMPORTED", error.errorCode)
        assertEquals(409, error.status.value)
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun stagingPatch(upstream: List<HttpRequest>): String =
        assertNotNull(
            upstream.firstOrNull { it.method() == "PATCH" && it.uri().rawPath.endsWith("/preguntas_parseadas_staging") },
            "no salió el PATCH de la fila: " + upstream.describe(),
        ).bodyText()

    private fun stagingRowJson(): String =
        """{"id":"$filaId","lote_id":"$loteId","posicion":1,"bloque_original":"bloque crudo",""" +
            """"titulo":"Plazo de alegaciones","opcion_a":"a","opcion_b":"b","opcion_c":"c","opcion_d":"d",""" +
            """"respuesta_correcta":"B","razonamiento":"","fuente_texto":"","etiqueta_texto":"","estado_parseo":"ok"}"""

    private fun loteRowJson(): String =
        """{"id":"$loteId","examen_id":"$examenId","nombre_archivo_original":"t1.pdf",""" +
            """"tipo_examen":"rafaga","estado":"parseado","total_detectadas":1,"total_importadas":0}"""

    private fun examenRowJson(): String =
        """{"id":"$examenId","codigo_examen":"EX-1","especialidad_id":"$especialidadId",""" +
            """"titulo":"Examen","tipo_examen":"rafaga","tipo_acceso":"gratuito","activo":true}"""

    /**
     * El orden del `when` no es cosmético: `/rest/v1/preguntas_parseadas_staging`
     * CONTIENE `/rest/v1/preguntas` como prefijo, así que la rama del staging
     * tiene que ir antes o la mitad de las aserciones dejan de significar nada.
     */
    private fun gatewayWith(
        upstream: MutableList<HttpRequest>,
        stagingInsert: V1StubResponse = V1StubResponse(201, ""),
        stagingPatch: V1StubResponse = V1StubResponse(200, "[${stagingRowJson()}]"),
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            when {
                // La detección de duplicados aún no está aprovisionada en este doble.
                uri.contains("/rest/v1/rpc/admin_import_lote_duplicados") -> V1StubResponse(
                    404,
                    """{"code":"PGRST202","details":null,"hint":null,"message":"Could not find the function"}""",
                )
                uri.contains("/rest/v1/lotes_importacion_preguntas") -> V1StubResponse(200, "[${loteRowJson()}]")
                uri.contains("/rest/v1/preguntas_parseadas_staging") && upstream.lastOrNull()?.method() == "POST" -> stagingInsert
                uri.contains("/rest/v1/preguntas_parseadas_staging") && upstream.lastOrNull()?.method() == "PATCH" -> stagingPatch
                uri.contains("/rest/v1/preguntas_parseadas_staging") -> V1StubResponse(200, "[${stagingRowJson()}]")
                uri.contains("/rest/v1/examenes") -> V1StubResponse(200, "[${examenRowJson()}]")
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    private fun List<HttpRequest>.describe(): String = describeRequests(this)

    private fun HttpRequest.bodyText(): String = readRequestBody(this)
}

/**
 * El cuerpo que sale hacia Supabase, como texto.
 *
 * Vive fuera de la clase porque varios ficheros afirman sobre cuerpos, y cada
 * copia privada más de esto es una copia más que se puede quedar sutilmente
 * distinta. `HttpRequest.BodyPublisher` sólo se puede leer suscribiéndose: no hay
 * un `body()` que llamar.
 */
internal fun readRequestBody(request: HttpRequest): String {
    val publisher = request.bodyPublisher().orElse(null) ?: return ""
    val chunks = StringBuilder()
    publisher.subscribe(object : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        override fun onSubscribe(subscription: java.util.concurrent.Flow.Subscription) {
            subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(item: java.nio.ByteBuffer) {
            chunks.append(Charsets.UTF_8.decode(item))
        }

        override fun onError(throwable: Throwable) = Unit
        override fun onComplete() = Unit
    })
    return chunks.toString()
}

/**
 * Las peticiones que salieron, en una línea, para el mensaje de un fallo.
 *
 * Estaba copiada en diez ficheros, byte a byte. Vive aquí, al lado de
 * [readRequestBody], por la misma razón que dice el KDoc de aquélla: cada copia
 * privada es una copia que puede quedarse sutilmente distinta.
 *
 * Y ya pasó: `IndependentDashboardFanoutContractTest` tiene una versión que NO
 * imprime el verbo HTTP. Esa se ha dejado como estaba a propósito — unificarla
 * cambiaría el texto de sus mensajes de fallo, y eso es una decisión de quien
 * escribió ese test, no de una limpieza.
 */
internal fun describeRequests(requests: List<HttpRequest>): String =
    "(${requests.size}) " + requests.joinToString(", ") {
        "${it.method()} ${it.uri().rawPath}?${it.uri().rawQuery.orEmpty()}"
    }
