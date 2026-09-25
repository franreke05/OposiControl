package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.ContentExamType

/**
 * Ilustración de una portada. Una por cada vector del diseño de Figma
 * («icono/card»): las de comunidad para los inmersivos, y una por cada uno de
 * los otros dos tipos.
 *
 * Es un enum puro y no un recurso a propósito: qué ilustración toca a un examen
 * es una regla de producto que se prueba sin cargar ningún dibujo; el dibujo
 * concreto se resuelve aparte, en la capa que pinta.
 */
enum class ExamCoverArtwork {
    ANDALUCIA, ARAGON, ASTURIAS, BALEARES, BARCELONA, CANARIAS, CANTABRIA,
    CASTILLA_LA_MANCHA, CASTILLA_Y_LEON, CEUTA_MELILLA, EXTREMADURA, GALICIA,
    LA_RIOJA, MADRID, MURCIA, NAVARRA, PAIS_VASCO, VALENCIA,
    RAFAGAS, RAZONADOS,
}

/**
 * Qué ilustración lleva la portada de un examen, o `null` si no tiene una.
 *
 * - Ráfaga y razonado: siempre la suya, no dependen de la comunidad.
 * - Inmersivo: la de su comunidad. Sin comunidad (un examen nacional), o con una
 *   que el diseño no cubre, es `null` y la portada usa la ilustración genérica.
 *   No se inventa una comunidad para rellenar el hueco.
 */
fun coverArtworkFor(type: ContentExamType, community: Community?): ExamCoverArtwork? = when (type) {
    ContentExamType.RAFAGA -> ExamCoverArtwork.RAFAGAS
    ContentExamType.RAZONADO -> ExamCoverArtwork.RAZONADOS
    ContentExamType.INMERSIVO -> community?.let(::regionArtwork)
}

/** El diseño trae una sola ilustración para Ceuta y Melilla, y Barcelona representa a Cataluña. */
private val REGION_NEEDLES: List<Pair<String, ExamCoverArtwork>> = listOf(
    // «castilla-la-mancha» antes que «castilla-y-leon»: comparten prefijo.
    "castilla-la-mancha" to ExamCoverArtwork.CASTILLA_LA_MANCHA,
    "castilla-y-leon" to ExamCoverArtwork.CASTILLA_Y_LEON,
    "madrid" to ExamCoverArtwork.MADRID,
    "andalucia" to ExamCoverArtwork.ANDALUCIA,
    "aragon" to ExamCoverArtwork.ARAGON,
    "asturias" to ExamCoverArtwork.ASTURIAS,
    "balear" to ExamCoverArtwork.BALEARES,
    "canaria" to ExamCoverArtwork.CANARIAS,
    "cantabria" to ExamCoverArtwork.CANTABRIA,
    "catalu" to ExamCoverArtwork.BARCELONA,
    "valencia" to ExamCoverArtwork.VALENCIA,
    "extremadura" to ExamCoverArtwork.EXTREMADURA,
    "galicia" to ExamCoverArtwork.GALICIA,
    "murcia" to ExamCoverArtwork.MURCIA,
    "navarra" to ExamCoverArtwork.NAVARRA,
    "pais-vasco" to ExamCoverArtwork.PAIS_VASCO,
    "euskadi" to ExamCoverArtwork.PAIS_VASCO,
    "rioja" to ExamCoverArtwork.LA_RIOJA,
    "ceuta" to ExamCoverArtwork.CEUTA_MELILLA,
    "melilla" to ExamCoverArtwork.CEUTA_MELILLA,
)

private fun regionArtwork(community: Community): ExamCoverArtwork? {
    // El slug es lo canónico; el nombre cubre una comunidad creada a mano con
    // un slug distinto.
    val haystacks = listOf(normalize(community.slug), normalize(community.name))
    return haystacks.firstNotNullOfOrNull { text ->
        REGION_NEEDLES.firstOrNull { (needle, _) -> text.contains(needle) }?.second
    }
}

/** Minúsculas, sin tildes y con guiones: «Castilla y León» → «castilla-y-leon». */
internal fun normalize(text: String): String =
    text.lowercase()
        .map { ch ->
            when (ch) {
                'á', 'à', 'ä', 'â' -> 'a'
                'é', 'è', 'ë', 'ê' -> 'e'
                'í', 'ì', 'ï', 'î' -> 'i'
                'ó', 'ò', 'ö', 'ô' -> 'o'
                'ú', 'ù', 'ü', 'û' -> 'u'
                'ñ' -> 'n'
                else -> ch
            }
        }
        .joinToString("")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
