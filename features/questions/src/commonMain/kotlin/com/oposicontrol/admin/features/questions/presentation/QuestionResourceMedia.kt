package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Miniatura de un recurso de imagen ya subido, para comprobar QUÉ se subió sin salir del panel. Solo se carga una URL
 * de nuestro Storage (`MediaUrlPolicy.isOpenableStorageObject`); cualquier otra se queda en el aviso.
 */
@Composable
internal expect fun QuestionResourceImagePreview(url: String, modifier: Modifier)

/** Abre [url] en el navegador o reproductor del sistema. Devuelve null si se abrió, o el motivo legible si no. */
internal expect fun openQuestionResourceExternally(url: String): String?
