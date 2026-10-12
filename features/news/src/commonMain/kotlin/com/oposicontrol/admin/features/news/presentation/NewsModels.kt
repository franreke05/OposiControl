package com.oposicontrol.admin.features.news.presentation

/**
 * Fila de la biblioteca. Viene del listado RESUMEN del servidor (sin texto ni capas), por eso [text] y [layers]
 * llegan vacios en una fila remota: la ficha completa se lee al abrirla. Los campos del resumen que el panel
 * anterior no tenia ([imageUrl], [publishedAt], [updatedAt]) son opcionales para que el editor movil, que
 * construye filas a mano, siga compilando.
 */
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
    val imageUrl: String? = null,
    val publishedAt: String? = null,
    val updatedAt: String = "",
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
     * Tamano de fuente en PX DEL LIENZO (4 por dp de movil; el nombre `Sp` es el de la clave del contrato).
     * `0` NO es un tamano: significa «derivado del tipo de capa» (titulo -> 72, resto -> 48; ver
     * `NEWS_DERIVED_TITLE_SIZE` / `NEWS_DERIVED_TEXT_SIZE`).
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

/** Las tres caras de la ficha. */
enum class NewsEditorTab(val id: String, val label: String) {
    CONTENT("content", "Contenido"),
    DESIGN("design", "Diseño"),
    PUBLICATION("publication", "Publicación"),
    ;

    companion object {
        fun fromId(id: String): NewsEditorTab = entries.firstOrNull { it.id == id } ?: CONTENT
    }
}

/** Que operacion remota esta en curso o acabo: la barra de guardado dice su nombre, no un «operacion completada». */
enum class NewsAction { SAVE, PUBLISH, SCHEDULE, ARCHIVE, DELETE }

/**
 * Una subida de imagen en marcha o fallida. [target] es `background`, `image` o `layer:<id>`; [uri] se conserva
 * para poder reintentar con el mismo fichero. Sin [error] esta subiendo; con el, fallo y espera reintento o cierre.
 */
data class NewsUploadState(
    val target: String,
    val uri: String,
    val fileName: String,
    val progress: Float = 0f,
    val error: String? = null,
) {
    val failed: Boolean get() = error != null
}

const val NEWS_UPLOAD_BACKGROUND = "background"
const val NEWS_UPLOAD_IMAGE = "image"

fun newsLayerUploadTarget(layerId: String): String = "layer:$layerId"
