package com.oposicontrol.admin.features.news.presentation

data class NewsItem(
    val id: String,
    val title: String,
    val specialty: String,
    val type: String,
    val backgroundType: String,
    val backgroundPath: String? = null,
    val status: String,
    val publishFrom: String,
    val text: String,
    val imagePath: String? = null,
    val layers: List<NewsLayerDraft> = emptyList(),
    val isLocalDraft: Boolean = false,
)

data class NewsLayerDraft(
    val id: String,
    val name: String,
    val kind: String,
    val content: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val opacity: Int,
    val fontFamily: String = "sans",
    val textColor: String = "",
    val visible: Boolean,
    val locked: Boolean = false,
    val sortOrder: Int,
    val rotationDegrees: Int = 0,
    /**
     * Tamano de fuente en sp. `0` NO es un tamano: significa «derivado del tipo
     * de capa», que es exactamente lo que el lienzo hace hoy sin poder
     * configurarse (titulo -> 25sp, resto -> 16sp).
     *
     * Por que `0` y no `-1` ni `null`:
     * - Es el default de `Int` en JSON y en Postgres, asi que las capas ya
     *   guardadas en `capas jsonb` —que llegan sin el campo— deserializan a `0`
     *   y siguen pintandose igual que antes. La migracion es no-op.
     * - `-1` seria un tamano negativo valido a ojos del tipo: obligaria a
     *   filtrarlo en cada lectura y cualquier `coerceAtLeast` lo destruiria.
     * - `null` propagaria un `Int?` a los tres DTO, al JSON del borrador local
     *   y a cada punto de uso del lienzo, a cambio de nada: `0` ya es
     *   imposible como tamano real (el rango editable es 8..200).
     */
    val fontSizeSp: Int = 0,
    /**
     * Uno de `normal`, `medium`, `semibold`, `bold`. El ViewModel rechaza
     * cualquier otro valor y conserva el anterior, de modo que lo que llegue
     * del backend o de un borrador antiguo nunca deja la capa en un peso que
     * el lienzo no sepa pintar.
     */
    val fontWeight: String = "",
)

enum class NewsEditorSelection {
    ARTICLE,
    BACKGROUND,
    LAYER,
}
