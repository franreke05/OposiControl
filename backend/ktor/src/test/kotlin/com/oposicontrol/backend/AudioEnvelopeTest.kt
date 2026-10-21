package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EX14: algoritmo de la envolvente con fixtures sinteticos (sin ficheros binarios en el repo) y las MEDIDAS que justifican
 * la representacion compacta (UInt8 + base64 frente a una lista JSON de decimales).
 */
class AudioEnvelopeTest {

    /** Seno de 440 Hz: amplitud [first] hasta [switchMs] y [second] despues (el fixture QA de E4: 0.2 -> 0.9 a los 500 ms). */
    private fun tone(durationMs: Int, sampleRate: Int, first: Float, second: Float, switchMs: Int): FloatArray =
        FloatArray((sampleRate.toLong() * durationMs / 1_000).toInt()) { i ->
            val amplitude = if (i * 1_000L / sampleRate < switchMs) first else second
            amplitude * sin(2 * PI * 440 * i / sampleRate).toFloat()
        }

    private fun wav(pcm: FloatArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val raw = ByteArray(pcm.size * 2 * channels)
        pcm.forEachIndexed { i, v ->
            val s = (v * 32_767).toInt().coerceIn(-32_768, 32_767)
            repeat(channels) { c ->
                raw[(i * channels + c) * 2] = (s and 0xff).toByte()
                raw[(i * channels + c) * 2 + 1] = (s shr 8).toByte()
            }
        }
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val out = ByteArrayOutputStream()
        AudioSystem.write(AudioInputStream(raw.inputStream(), format, pcm.size.toLong()), AudioFileFormat.Type.WAVE, out)
        return out.toByteArray()
    }

    @Test
    fun theQaFixtureGivesALowThenFullEnvelopeNormalisedToTheResourceMaximum() {
        val rate = 16_000
        val pcm = tone(1_000, rate, 0.2f, 0.9f, 500)
        val env = AudioEnvelope.compute(pcm, rate).map { it.toInt() and 0xff }
        assertEquals(25, env.size, "1 s a 40 ms = 25 muestras")
        // RMS de un seno = A / sqrt(2): la proporcion 0.2 / 0.9 se conserva tras normalizar al maximo.
        assertTrue(env[2] in 55..59, "t=80ms ~ 0.2/0.9*255 = 57: ${env[2]}")
        assertTrue(env[20] >= 253, "t=800ms ~ 255: ${env[20]}")
        assertEquals(255, env.max())
    }

    @Test
    fun silenceIsAllZeroAndAnEmptySignalHasNoSamples() {
        assertTrue(AudioEnvelope.compute(FloatArray(16_000), 16_000).all { it.toInt() == 0 })
        assertEquals(0, AudioEnvelope.compute(FloatArray(0), 16_000).size)
    }

    @Test
    fun aWavFixtureDecodesToTheSameEnvelopeAndExactDuration() {
        val rate = 22_050
        val pcm = tone(2_000, rate, 0.2f, 0.9f, 500)
        val (decoded, decodedRate) = AudioEnvelope.decodeWav(wav(pcm, rate, channels = 2))
        assertEquals(rate, decodedRate)
        assertEquals(pcm.size, decoded.size)
        assertEquals(2_000L, AudioEnvelope.durationMs(decoded.size, decodedRate))
        val fromWav = AudioEnvelope.compute(decoded, decodedRate).map { it.toInt() and 0xff }
        val direct = AudioEnvelope.compute(pcm, rate).map { it.toInt() and 0xff }
        fromWav.zip(direct).forEach { (a, b) -> assertTrue(kotlin.math.abs(a - b) <= 1, "estereo -> mono sin desviarse") }
        val meta = AudioEnvelope.metadata(decoded, decodedRate)
        assertEquals(2_000L, meta["durationMs"]!!.jsonPrimitive.content.toLong())
        assertNotNull(AudioEnvelope.fromStored(meta["envelope"]))
    }

    @Test
    fun measuredSizeAndCostJustifyUInt8Base64() {
        val rate = 44_100
        val pcm = tone(60_000, rate, 0.3f, 0.8f, 30_000)
        val started = System.nanoTime()
        val env = AudioEnvelope.compute(pcm, rate)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val base64 = Base64.getEncoder().encodeToString(env)
        val floats = buildJsonArray { env.forEach { add(JsonPrimitive(((it.toInt() and 0xff) / 255.0 * 1_000).toInt() / 1_000.0)) } }.toString()
        println("ENVELOPE_MEASURE 60s@44.1kHz stepMs=40 samples=${env.size} base64Chars=${base64.length} jsonFloatChars=${floats.length} computeMs=$elapsedMs")
        assertEquals(1_500, env.size)
        assertEquals(2_000, base64.length)
        assertTrue(floats.length > base64.length * 2, "la lista JSON de decimales ocupa mas del doble: ${floats.length}")
        assertTrue(elapsedMs < 2_000, "una vez en la ingesta, barato: $elapsedMs ms")
    }

    @Test
    fun ingestDecodesWavInTheJvmAndOtherFormatsOnlyThroughFfmpegAndNeverThrows() {
        val rate = 16_000
        val wavBytes = wav(tone(1_000, rate, 0.2f, 0.9f, 500), rate)
        val fromWav = AudioEnvelope.ingestMetadata(wavBytes, "audio/wav")
        assertEquals(1_000L, fromWav!!["durationMs"]!!.jsonPrimitive.content.toLong())
        assertNotNull(AudioEnvelope.fromStored(fromWav["envelope"]))
        val noFfmpeg = object : FfmpegRunner {
            override fun isAvailable() = false
            override fun run(args: List<String>, timeoutMs: Long) = error("no se llama")
        }
        assertNull(AudioEnvelope.ingestMetadata(byteArrayOf(1, 2, 3), "audio/mpeg", noFfmpeg), "mp3 sin ffmpeg: sin envolvente")
        // ffmpeg simulado: escribe el WAV en el fichero de salida (ultimo argumento), como hace el real.
        val fakeFfmpeg = object : FfmpegRunner {
            override fun isAvailable() = true
            override fun run(args: List<String>, timeoutMs: Long): Boolean {
                assertTrue(listOf("-ac", "1", "-ar", "16000", "-f", "wav").all { it in args })
                java.nio.file.Files.write(java.nio.file.Path.of(args.last()), wavBytes)
                return true
            }
        }
        assertEquals(fromWav, AudioEnvelope.ingestMetadata(byteArrayOf(9, 9, 9), "audio/mp4", fakeFfmpeg), "m4a via ffmpeg = mismo resultado")
        val failing = object : FfmpegRunner {
            override fun isAvailable() = true
            override fun run(args: List<String>, timeoutMs: Long) = false
        }
        assertNull(AudioEnvelope.ingestMetadata(byteArrayOf(9), "audio/mpeg", failing))
        assertNull(AudioEnvelope.ingestMetadata(byteArrayOf(1, 2, 3), "audio/wav"), "WAV corrupto: null, nunca una excepcion")
    }

    @Test
    fun aStoredEnvelopeIsReadDefensively() {
        val ok = buildJsonObject { put("stepMs", 40); put("samples", Base64.getEncoder().encodeToString(byteArrayOf(0, 127, -1))) }
        assertEquals(AudioEnvelopeDto(40, "AH//"), AudioEnvelope.fromStored(ok))
        assertNull(AudioEnvelope.fromStored(null))
        assertNull(AudioEnvelope.fromStored(JsonPrimitive("x")))
        assertNull(AudioEnvelope.fromStored(buildJsonObject { put("stepMs", 5); put("samples", "AH//") }), "stepMs fuera de rango")
        assertNull(AudioEnvelope.fromStored(buildJsonObject { put("stepMs", "40"); put("samples", "AH//") }), "stepMs como texto")
        assertNull(AudioEnvelope.fromStored(buildJsonObject { put("stepMs", 40); put("samples", "%%%") }), "base64 invalido")
        assertNull(AudioEnvelope.fromStored(buildJsonObject { put("stepMs", 40); put("samples", "") }), "vacio")
        val huge = Base64.getEncoder().encodeToString(ByteArray(AudioEnvelope.MAX_SAMPLES + 1))
        assertNull(AudioEnvelope.fromStored(buildJsonObject { put("stepMs", 40); put("samples", huge) }), "por encima del tope")
        assertEquals(40, Json.parseToJsonElement("""{"stepMs":40,"samples":"AH//"}""").jsonObject.let { AudioEnvelope.fromStored(it)!!.stepMs })
    }
}
