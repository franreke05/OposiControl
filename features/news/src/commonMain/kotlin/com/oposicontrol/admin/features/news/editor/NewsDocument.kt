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

/** Tamano del lienzo con el que OposiBot pinta una noticia: 800 x 500. */
const val NEWS_CANVAS_DEFAULT_WIDTH: Int = 800
const val NEWS_CANVAS_DEFAULT_HEIGHT: Int = 500

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
