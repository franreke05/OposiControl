package com.oposicontrol.backend

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.roundToInt
import org.slf4j.LoggerFactory

private val EnvelopeLogger = LoggerFactory.getLogger("OC-KTOR-MEDIA")

/**
 * EX14 / decision del dueno 3: envolvente de audio calculada UNA VEZ en la ingesta y servida como metadato del recurso.
 *
 * Representacion (medida en AudioEnvelopeTest): `stepMs` fijo (40 ms, propuesta medida de E4) + una muestra UInt8 por
 * ventana = RMS de la ventana centrada, normalizado al maximo del recurso (0..255), en base64. 60 s = 1 500 bytes =
 * 2 000 caracteres base64; la misma serie como lista JSON de decimales ocupa varias veces mas (medido en el test).
 *
 * Donde se guarda: `recursos_pregunta.metadatos.envelope = {stepMs, samples}` (y `metadatos.durationMs`), que la revision
 * del intento ya copia (20261016100000). Lo escribe la INGESTA ([ingestMetadata] desde `AdminV1Gateway.addRecursoArchivo`,
 * autorizado por el dueno 2026-10-14); un audio subido antes, o que no se pudo decodificar, sale con `envelope = null`.
 */
internal object AudioEnvelope {
    const val DEFAULT_STEP_MS = 40
    const val MIN_STEP_MS = 10
    const val MAX_STEP_MS = 1_000

    /** Tope de muestras servidas: 24 min a 40 ms. Mas que eso no es un recurso de pregunta: se descarta (null). */
    const val MAX_SAMPLES = 36_000

    /**
     * [pcm] mono en [-1, 1]. Muestra k = RMS de la ventana de [stepMs] CENTRADA en k*stepMs (recortada a la senal),
     * normalizada al maximo del recurso y cuantizada a 0..255 (propuesta medida de E4, `decodeQuantizedEnvelope`).
     * k va de 0 a ceil(duracion / stepMs) - 1.
     */
    fun compute(pcm: FloatArray, sampleRate: Int, stepMs: Int = DEFAULT_STEP_MS): ByteArray {
        require(sampleRate > 0) { "sampleRate" }
        require(stepMs in MIN_STEP_MS..MAX_STEP_MS) { "stepMs" }
        val window = maxOf(1, (sampleRate.toLong() * stepMs / 1_000).toInt())
        val count = (pcm.size + window - 1) / window
        val rms = FloatArray(count)
        var max = 0f
        for (k in 0 until count) {
            val from = maxOf(0, k * window - window / 2)
            val to = minOf(pcm.size, k * window + (window - window / 2))
            var sum = 0.0
            for (i in from until to) sum += pcm[i].toDouble() * pcm[i]
            val value = if (to > from) kotlin.math.sqrt(sum / (to - from)).toFloat() else 0f
            rms[k] = value
            if (value > max) max = value
        }
        return ByteArray(count) { k -> if (max <= 0f) 0 else (rms[k] / max * 255f).roundToInt().coerceIn(0, 255).toByte() }
    }

    /** `durationMs` exacto de la senal decodificada. */
    fun durationMs(sampleCount: Int, sampleRate: Int): Long = sampleCount.toLong() * 1_000 / sampleRate

    /** WAV PCM (lo unico que la JVM decodifica sin ffmpeg) -> mono [-1, 1] + frecuencia de muestreo. */
    fun decodeWav(bytes: ByteArray): Pair<FloatArray, Int> {
        AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use { source ->
            val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, source.format.sampleRate, 16, source.format.channels, source.format.channels * 2, source.format.sampleRate, false)
            AudioSystem.getAudioInputStream(target, source).use { pcm ->
                val raw = pcm.readAllBytes()
                val channels = target.channels
                val frames = raw.size / (2 * channels)
                val mono = FloatArray(frames)
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channels) {
                        val i = (f * channels + c) * 2
                        val sample = ((raw[i + 1].toInt() shl 8) or (raw[i].toInt() and 0xff)).toShort()
                        sum += sample / 32_768f
                    }
                    mono[f] = sum / channels
                }
                return mono to target.sampleRate.roundToInt()
            }
        }
    }

    /** Lo que la ingesta guardaria en `metadatos` (`envelope` + `durationMs`). */
    fun metadata(pcm: FloatArray, sampleRate: Int, stepMs: Int = DEFAULT_STEP_MS): JsonObject = buildJsonObject {
        put("durationMs", durationMs(pcm.size, sampleRate))
        put(
            "envelope",
            buildJsonObject {
                put("stepMs", stepMs)
                put("samples", Base64.getEncoder().encodeToString(compute(pcm, sampleRate, stepMs)))
            },
        )
    }

    /** Tope de decodificacion en la ingesta (proceso externo): un audio que tarda mas no bloquea la subida. */
    private const val DECODE_TIMEOUT_MS = 30_000L

    /**
     * INGESTA (unica llamada: `AdminV1Gateway.addRecursoArchivo`, autorizada por el dueno 2026-10-14): `durationMs` +
     * `envelope` de los bytes YA definitivos de un audio, o `null` si no se pueden decodificar. NUNCA lanza: la subida del
     * material no depende de la envolvente (el cliente soporta su ausencia). WAV se decodifica en la JVM; el resto (mp3,
     * m4a, que es lo que deja la compresion) con `ffmpeg` a WAV mono 16 kHz si esta instalado.
     */
    fun ingestMetadata(bytes: ByteArray, mime: String, ffmpeg: FfmpegRunner = SystemFfmpegRunner): JsonObject? = try {
        val wav = when {
            mime == "audio/wav" || mime == "audio/x-wav" -> bytes
            ffmpeg.isAvailable() -> decodeWithFfmpeg(bytes, ffmpeg)
            else -> null
        }
        wav?.let { decodeWav(it) }?.takeIf { (pcm, rate) -> pcm.isNotEmpty() && rate > 0 }?.let { (pcm, rate) ->
            metadata(pcm, rate).takeIf { durationMs(pcm.size, rate) / DEFAULT_STEP_MS < MAX_SAMPLES }
        }
    } catch (error: Exception) {
        EnvelopeLogger.warn("envolvente de audio no calculada: {}", error::class.simpleName)
        null
    }

    private fun decodeWithFfmpeg(bytes: ByteArray, ffmpeg: FfmpegRunner): ByteArray? {
        val input = java.nio.file.Files.createTempFile("oc-env-in-", ".bin")
        val output = java.nio.file.Files.createTempFile("oc-env-out-", ".wav")
        return try {
            java.nio.file.Files.write(input, bytes)
            val args = listOf("-hide_banner", "-loglevel", "error", "-y", "-i", input.toString(), "-vn", "-ac", "1", "-ar", "16000", "-f", "wav", output.toString())
            if (!ffmpeg.run(args, DECODE_TIMEOUT_MS)) null else java.nio.file.Files.readAllBytes(output).takeIf { it.isNotEmpty() }
        } finally {
            runCatching { java.nio.file.Files.deleteIfExists(input) }
            runCatching { java.nio.file.Files.deleteIfExists(output) }
        }
    }

    /** Lectura defensiva de `metadatos.envelope` congelado en la revision: cualquier forma inesperada -> `null`. */
    fun fromStored(stored: JsonElement?): AudioEnvelopeDto? {
        val obj = stored as? JsonObject ?: return null
        val step = (obj["stepMs"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
        if (step !in MIN_STEP_MS..MAX_STEP_MS) return null
        val samples = (obj["samples"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val decoded = runCatching { Base64.getDecoder().decode(samples) }.getOrNull() ?: return null
        if (decoded.isEmpty() || decoded.size > MAX_SAMPLES) return null
        return AudioEnvelopeDto(stepMs = step, samples = samples)
    }
}
