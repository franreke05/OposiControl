package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * EX16 (SOLO stack LOCAL, `ops/local-exam`): crea los recursos inmersivos de los fixtures con la INGESTA REAL
 * (`AdminV1Gateway.addRecursoArchivo`: Storage privado + fila + `metadatos` con `durationMs`/`envelope` del audio): imagen
 * 1200x800, audio, video y audio lento.
 * Se OMITE salvo que `LOCAL_EXAM_INGEST` apunte al `.local` del stack (lo pone `up.mjs`); nunca toca otro proyecto: la
 * URL debe ser `http://localhost:54321`.
 */
class LocalExamStackIngestFixture {
    @Test
    fun ingestTheLocalImmersiveResources() {
        val dir = System.getenv("LOCAL_EXAM_INGEST")?.let(::File)
        assumeTrue("solo con el stack local (LOCAL_EXAM_INGEST)", dir != null && File(dir, "ktor.env").isFile)
        val env = File(dir, "ktor.env").readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val url = env.getValue("SUPABASE_URL")
        require(url == "http://localhost:54321") { "la ingesta de fixtures solo corre contra el stack local" }
        val gateway = AdminV1Gateway.forTesting(url, env.getValue("SUPABASE_SERVICE_ROLE_KEY"), Json { ignoreUnknownKeys = true }, HttpClient.newHttpClient())

        // (pregunta, recurso, tipo, mime, fichero) -- ids fijos de sql/90_fixtures.sql
        val items = listOf(
            listOf("f3000000-0000-4000-8000-000000000001", "5e000000-0000-4000-8000-000000000007", "imagen", "image/png", "image.png"),
            listOf("f4000000-0000-4000-8000-000000000001", "5e000000-0000-4000-8000-000000000002", "audio", "audio/wav", "audio.wav"),
            listOf("f7000000-0000-4000-8000-000000000001", "5e000000-0000-4000-8000-000000000005", "video", "video/mp4", "video.mp4"),
            listOf("f8000000-0000-4000-8000-000000000001", "5e000000-0000-4000-8000-000000000006", "audio", "audio/wav", "slow.wav"),
        )
        items.forEach { (pregunta, recurso, tipo, mime, fichero) ->
            val file = File(dir, fichero)
            if (!file.isFile) {
                println("LOCAL_INGEST skip $tipo $recurso: falta $fichero")
                return@forEach
            }
            val saved = gateway.addRecursoArchivo(pregunta, recurso, tipo, "Fixture $tipo", null, 1, mime, file.readBytes())
            assertEquals(recurso, saved.id)
            println("LOCAL_INGEST ok $tipo $recurso ${file.length()} bytes")
        }
        val audio = File(dir, "audio.wav")
        if (audio.isFile) {
            val meta = AudioEnvelope.ingestMetadata(audio.readBytes(), "audio/wav")
            assertNotNull(meta)
            assertTrue(meta.containsKey("envelope"))
        }
    }
}
