package com.oposicontrol.backend

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

internal data class ParsedPregunta(
    val numero: Int,
    val titulo: String,
    val opciones: List<Pair<String, String>>,
    val respuesta: String,
    val razonamiento: String,
    val fuente: String,
    val etiquetas: List<String>,
)

internal data class RafagaParseError(
    val numero: Int,
    val campo: String,
    val detalle: String,
)

private val KNOWN_FIELDS = setOf("TITULO", "A", "B", "C", "D", "RESPUESTA", "RAZONAMIENTO", "FUENTE", "ETIQUETA")
private val BLOCK_SEPARATOR = Regex("---\\s*PREGUNTA\\s*---")

internal fun extractTextFromPdf(bytes: ByteArray): String =
    Loader.loadPDF(bytes).use { doc -> PDFTextStripper().getText(doc) }

internal fun parseRafagaText(text: String): Pair<List<ParsedPregunta>, List<RafagaParseError>> {
    val preguntas = mutableListOf<ParsedPregunta>()
    val errores = mutableListOf<RafagaParseError>()

    text.split(BLOCK_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEachIndexed { i, block ->
            val numero = i + 1
            val fields = extractFields(block)

            val titulo = fields["TITULO"]?.trim()
            if (titulo.isNullOrBlank()) {
                errores += RafagaParseError(numero, "TITULO", "Obligatorio y no puede estar vacío")
                return@forEachIndexed
            }

            val opciones = mutableListOf<Pair<String, String>>()
            var opcionError = false
            for (letra in listOf("A", "B", "C", "D")) {
                val texto = fields[letra]?.trim()
                if (texto.isNullOrBlank()) {
                    errores += RafagaParseError(numero, letra, "Opción $letra obligatoria")
                    opcionError = true
                } else {
                    opciones += letra to texto
                }
            }
            if (opcionError) return@forEachIndexed

            val respuesta = fields["RESPUESTA"]?.trim()?.uppercase()
            if (respuesta.isNullOrBlank() || respuesta !in setOf("A", "B", "C", "D")) {
                errores += RafagaParseError(numero, "RESPUESTA", "Debe ser A, B, C o D — recibido: '${respuesta.orEmpty()}'")
                return@forEachIndexed
            }

            val etiquetas = fields["ETIQUETA"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()

            preguntas += ParsedPregunta(
                numero = numero,
                titulo = titulo,
                opciones = opciones,
                respuesta = respuesta,
                razonamiento = fields["RAZONAMIENTO"]?.trim().orEmpty(),
                fuente = fields["FUENTE"]?.trim().orEmpty(),
                etiquetas = etiquetas,
            )
        }

    return preguntas to errores
}

private fun extractFields(block: String): Map<String, String> {
    val result = mutableMapOf<String, StringBuilder>()
    var current: String? = null
    for (line in block.lines()) {
        val trimmed = line.trim()
        if (trimmed.isBlank()) continue
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx > 0) {
            val key = trimmed.substring(0, colonIdx).trim()
            if (key in KNOWN_FIELDS) {
                current = key
                result[key] = StringBuilder(trimmed.substring(colonIdx + 1).trim())
                continue
            }
        }
        // Continuation line (e.g. multi-line RAZONAMIENTO)
        current?.let { result[it]?.append('\n')?.append(trimmed) }
    }
    return result.mapValues { it.value.toString() }
}
