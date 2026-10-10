package com.oposicontrol.admin.features.news.editor

import androidx.compose.ui.input.key.Key

/** Lo que una tecla pide al lienzo. Funcion pura sobre la tecla y sus modificadores, probada sin Compose. */
sealed interface NewsCanvasKeyAction {
    data class Nudge(val dx: Int, val dy: Int) : NewsCanvasKeyAction
    data object DeleteLayer : NewsCanvasKeyAction
    data object Duplicate : NewsCanvasKeyAction
    data object Deselect : NewsCanvasKeyAction
    data object Undo : NewsCanvasKeyAction
    data object Redo : NewsCanvasKeyAction
    data object Save : NewsCanvasKeyAction
    data object NextLayer : NewsCanvasKeyAction
    data object PreviousLayer : NewsCanvasKeyAction
}

/** Flechas, 1 px; con Mayus, 10 px. */
const val NEWS_NUDGE_STEP: Int = 1
const val NEWS_NUDGE_STEP_LARGE: Int = 10

/**
 * Mapa de teclado del lienzo. `null` = la tecla no es nuestra y sigue su camino (navegacion por teclado del panel).
 * Ctrl y Cmd valen lo mismo: el panel corre en Windows, macOS y Linux.
 */
fun newsCanvasKeyAction(key: Key, shift: Boolean, control: Boolean): NewsCanvasKeyAction? {
    val step = if (shift) NEWS_NUDGE_STEP_LARGE else NEWS_NUDGE_STEP
    return when {
        control && key == Key.Z && shift -> NewsCanvasKeyAction.Redo
        control && key == Key.Z -> NewsCanvasKeyAction.Undo
        control && key == Key.Y -> NewsCanvasKeyAction.Redo
        control && key == Key.S -> NewsCanvasKeyAction.Save
        control && key == Key.D -> NewsCanvasKeyAction.Duplicate
        control -> null
        key == Key.Delete || key == Key.Backspace -> NewsCanvasKeyAction.DeleteLayer
        key == Key.Escape -> NewsCanvasKeyAction.Deselect
        key == Key.Tab && shift -> NewsCanvasKeyAction.PreviousLayer
        key == Key.Tab -> NewsCanvasKeyAction.NextLayer
        key == Key.DirectionLeft -> NewsCanvasKeyAction.Nudge(-step, 0)
        key == Key.DirectionRight -> NewsCanvasKeyAction.Nudge(step, 0)
        key == Key.DirectionUp -> NewsCanvasKeyAction.Nudge(0, -step)
        key == Key.DirectionDown -> NewsCanvasKeyAction.Nudge(0, step)
        else -> null
    }
}
