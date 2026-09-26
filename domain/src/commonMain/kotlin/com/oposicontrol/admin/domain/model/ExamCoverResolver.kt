package com.oposicontrol.admin.domain.model

/** Qué familia de portada corresponde a un examen. */
enum class ExamCoverKind {
    /** Portada común de Ráfaga, igual para todas las comunidades. */
    RAFAGA,

    /** Portada común de Razonado, igual para todas las comunidades. */
    RAZONADO,

    /** Vector específico de la comunidad autónoma (sólo Inmersivo). */
    COMMUNITY_VECTOR,

    /** Sin regla aplicable (tipo desconocido, o Inmersivo sin comunidad reconocible). PROVISIONAL. */
    GENERIC,
}

/**
 * La portada de un examen, ya resuelta.
 *
 * [assetKey] identifica el recurso visual (`cover_rafaga`, `cover_razonado`, `vector_madrid`…). Es
 * lo que OposiControl usa para la previsualización y lo que OposiMarket resuelve a su vector local.
 * [isFallback] es `true` sólo para [ExamCoverKind.GENERIC].
 */
data class ExamCover(
    val kind: ExamCoverKind,
    val assetKey: String,
    /** Código ISO 3166-2:ES de la comunidad, sólo en [ExamCoverKind.COMMUNITY_VECTOR]. */
    val communityCode: String? = null,
) {
    val isFallback: Boolean get() = kind == ExamCoverKind.GENERIC
}

/**
 * Resolver CENTRAL de la portada de un examen: `(tipo + comunidad autónoma) → recurso visual`.
 *
 * La portada NO se sube ni se elige ni se guarda: se deduce siempre de dos datos que el examen ya
 * tiene, así que cambiar el tipo o la comunidad cambia la portada sin migración ni escritura.
 *
 *  - Ráfaga   → [RAFAGA_COVER], común a todas las comunidades.
 *  - Razonado → [RAZONADO_COVER], común a todas las comunidades.
 *  - Inmersivo → el vector de su comunidad ([COMMUNITY_VECTORS]).
 *
 * La comunidad se identifica por su código ISO 3166-2:ES (`ES-MD`): es lo que guarda
 * `comunidades_autonomas.codigo` y la clave de unión con los vectores (ver la siembra
 * `20260917084439_seed_comunidades_autonomas`). El uuid no sirve: cambia entre entornos.
 *
 * PROVISIONAL: los nombres de recurso salen del patrón `vector_<comunidad>` y se sustituirán por la
 * correspondencia definitiva cuando lleguen los vectores; ese cambio es SÓLO esta tabla. Existe una
 * copia del mismo resolver en OposiBot (`ExamCoverResolver.kt`): las dos tienen que decir lo mismo.
 *
 * Sin decidir todavía (ver preguntas de producto): un Inmersivo sin comunidad (nacional) o con una
 * comunidad que no está en la tabla cae a [GENERIC_COVER], que nunca rompe la pantalla.
 */
object ExamCoverResolver {
    val RAFAGA_COVER = ExamCover(ExamCoverKind.RAFAGA, "cover_rafaga")
    val RAZONADO_COVER = ExamCover(ExamCoverKind.RAZONADO, "cover_razonado")
    val GENERIC_COVER = ExamCover(ExamCoverKind.GENERIC, "cover_generica")

    /** Código ISO 3166-2:ES → recurso del vector. Las 19 comunidades y ciudades autónomas sembradas. */
    val COMMUNITY_VECTORS: Map<String, String> = linkedMapOf(
        "ES-AN" to "vector_andalucia",
        "ES-AR" to "vector_aragon",
        "ES-AS" to "vector_asturias",
        "ES-IB" to "vector_illes_balears",
        "ES-CN" to "vector_canarias",
        "ES-CB" to "vector_cantabria",
        "ES-CL" to "vector_castilla_y_leon",
        "ES-CM" to "vector_castilla_la_mancha",
        "ES-CT" to "vector_cataluna",
        "ES-VC" to "vector_comunitat_valenciana",
        "ES-EX" to "vector_extremadura",
        "ES-GA" to "vector_galicia",
        "ES-MD" to "vector_madrid",
        "ES-MC" to "vector_murcia",
        "ES-NC" to "vector_navarra",
        "ES-PV" to "vector_pais_vasco",
        "ES-RI" to "vector_la_rioja",
        "ES-CE" to "vector_ceuta",
        "ES-ML" to "vector_melilla",
    )

    /**
     * La portada de un examen de tipo [typeExam] (vocabulario canónico o alias legacy) y de la
     * comunidad [communityCode] (`null` = nacional / sin comunidad).
     */
    fun resolve(typeExam: String, communityCode: String?): ExamCover =
        when (ExamType.fromExternal(typeExam)) {
            ExamType.RAFAGA -> RAFAGA_COVER
            ExamType.RAZONADO -> RAZONADO_COVER
            ExamType.INMERSIVO -> communityVector(communityCode) ?: GENERIC_COVER
            null -> GENERIC_COVER
        }

    private fun communityVector(communityCode: String?): ExamCover? {
        val code = communityCode?.trim()?.uppercase().orEmpty()
        val asset = COMMUNITY_VECTORS[code] ?: return null
        return ExamCover(ExamCoverKind.COMMUNITY_VECTOR, asset, communityCode = code)
    }
}
