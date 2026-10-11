package com.oposicontrol.admin.features.news.editor

import com.oposicontrol.admin.features.news.presentation.NewsLayerDraft

/**
 * Lo que se edita y se guarda de una noticia: el DOCUMENTO.
 *
 * Es el valor que viaja por el historial de deshacer/rehacer y el que se compara con la copia leida del servidor
 * para saber si hay cambios sin guardar. Lo que no esta aqui —seleccion, pestana, zoom, estado de red— es
 * estado de VISTA y no se deshace ni ensucia la ficha.
 */
data class NewsDocument(
    val title: String = "",
    val text: String = "",
    val specialty: String = "",
    val type: String = "",
    val backgroundType: String = "SKY",
    /** Ruta del fondo en el bucket (`news/<uuid>/...`), o vacia. Nunca una ruta local: lo local se sube antes. */
    val backgroundPath: String = "",
    val imagePath: String = "",
    val canvasWidth: Int = NEWS_CANVAS_DEFAULT_WIDTH,
    val canvasHeight: Int = NEWS_CANVAS_DEFAULT_HEIGHT,
    val layers: List<NewsLayerDraft> = emptyList(),
)

/**
 * El lienzo es el banner de Inicio de OposiBot, que es donde la app ensenara las noticias (decision DEC-030, contrato
 * D4-18). Ese banner mide 358 x 148 dp en movil y 704 x 291 dp en tableta, la misma proporcion (`HomeAdBanner.kt` de
 * OposiBot, `BannerAtomo`, nodo de Figma 59188:22663).
 *
 * Se edita a [NEWS_CANVAS_SCALE] px por dp: 1432 x 592. Una capa en `x = 64` cae a 16 dp del borde en el movil; la app
 * solo tiene que multiplicar por `anchoDelBanner / 1432`. El lienzo NO viaja al servidor (la fila no tiene columna para
 * el): es una constante del contrato de pintado, igual para todas las noticias.
 *
 * Antes era 800 x 500, una proporcion (1,6) que no existe en ninguna pantalla de la app: una noticia compuesta asi no
 * cabia en el banner (2,42) sin recortar un tercio de su altura.
 */
const val NEWS_CANVAS_SCALE: Int = 4
const val NEWS_BANNER_PHONE_WIDTH_DP: Int = 358
const val NEWS_BANNER_PHONE_HEIGHT_DP: Int = 148
const val NEWS_BANNER_TABLET_WIDTH_DP: Int = 704

/** Radio de esquina del banner en la app, en dp de movil (`OposiBotsPlaceholderDimens.cornerRadiusL`). */
const val NEWS_BANNER_CORNER_DP: Int = 8
const val NEWS_CANVAS_DEFAULT_WIDTH: Int = NEWS_BANNER_PHONE_WIDTH_DP * NEWS_CANVAS_SCALE
const val NEWS_CANVAS_DEFAULT_HEIGHT: Int = NEWS_BANNER_PHONE_HEIGHT_DP * NEWS_CANVAS_SCALE

/** Margen interior del texto de una capa, en px del lienzo (6 x 4 dp en el movil). Parte del contrato de pintado. */
const val NEWS_TEXT_INSET_X: Int = 6 * NEWS_CANVAS_SCALE
const val NEWS_TEXT_INSET_Y: Int = 4 * NEWS_CANVAS_SCALE

/** Tamano de letra de una capa sin tamano propio, en px del lienzo: titulo 18 dp de movil, el resto 12 dp. */
const val NEWS_DERIVED_TITLE_SIZE: Int = 18 * NEWS_CANVAS_SCALE
const val NEWS_DERIVED_TEXT_SIZE: Int = 12 * NEWS_CANVAS_SCALE

/** Por debajo de 24 px una capa deja de poder agarrarse y de pasar la validacion del dominio (`layer_too_small`). */
const val NEWS_LAYER_MIN_SIDE: Int = 24

/**
 * Un grupo de edicion: la unidad de deshacer.
 *
 * Dos mutaciones consecutivas del MISMO grupo se funden en un solo paso: arrastrar una capa son sesenta frames y
 * escribir un titulo son veinte pulsaciones, y nadie quiere deshacerlos de uno en uno. Cambiar de capa, de campo
 * o de tipo de edicion abre un grupo nuevo.
 */
data class NewsEditGroup(val kind: String, val target: String = "")
