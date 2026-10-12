package com.oposicontrol.admin.core.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * El movimiento del panel: cinco duraciones con nombre y tres curvas. No es un framework; es el sitio donde vive el
 * numero, para que «cuanto tarda un hover» no se decida otra vez en cada fichero.
 *
 * Los valores no son nuevos: son los que el panel ya usaba (120-140 ms en colores, 180 en cambios de estado, 220 en
 * fundidos), redondeados a una escala. Las pantallas de Contenido escriben 140 a mano y no se tocan: por eso [Fast]
 * vale 140 y no otro numero parecido.
 *
 * Que duracion usar:
 *  - [Instant]  respuesta a una pulsacion.
 *  - [Fast]     color de hover, foco y seleccion de una fila o un boton.
 *  - [Normal]   cambio de estado de un control: chip, pestana, ancho del menu, barra de guardado.
 *  - [Emphasis] entrada de contenido: pagina, ficha, paso entre cargando, vacio, error y datos.
 *  - [Overlay]  dialogos y paneles superpuestos.
 *
 * Nada espera a una animacion: un clic actua en el acto y el movimiento lo acompana. Ninguna dura mas que [Overlay]
 * salvo el resalte de «esto acaba de cambiar» ([Highlight]), que se apaga solo y no bloquea nada.
 */
object OposiMotion {
    const val Instant: Int = 90
    const val Fast: Int = 140
    const val Normal: Int = 180
    const val Emphasis: Int = 220
    const val Overlay: Int = 260

    /** Desfase entre cabecera y contenido al entrar una pagina. Corto: la pagina entera esta en pantalla en 260 ms. */
    const val Stagger: Int = 40

    /** Lo que tarda en apagarse el resalte de una fila que acaba de cambiar. */
    const val Highlight: Int = 900

    /** Lo que sube el contenido al entrar. Poco: se nota que llega, no que se mueve. */
    val EnterOffset: Dp = 8.dp

    /** Escala de partida de un dialogo. */
    const val OverlayStartScale: Float = 0.97f

    /** Cambios de estado y de color: sale deprisa y frena. La curva que ya usaban todos los `tween` del panel. */
    val Standard: Easing = FastOutSlowInEasing

    /** Lo que entra: llega con velocidad y se posa. */
    val Enter: Easing = LinearOutSlowInEasing

    /** Lo que se va: arranca despacio y sale deprisa, para no entretener. */
    val Exit: Easing = FastOutLinearInEasing
}

/**
 * `false` apaga el movimiento no esencial en todo lo que cuelgue de aqui: las entradas y los resaltes dejan de
 * existir y los cambios de color son inmediatos. El panel no lee todavia la preferencia del sistema operativo
 * («reducir movimiento»); cuando lo haga, bastara con proveer este valor una vez en la raiz.
 *
 * El movimiento nunca es la unica senal de nada: cada estado que se anima tiene ademas su texto, su color o su icono.
 */
val LocalMotionEnabled = staticCompositionLocalOf { true }

/** Un `tween` de la escala de [OposiMotion] que respeta [LocalMotionEnabled]. */
@Composable
fun <T> motionTween(
    durationMillis: Int,
    easing: Easing = OposiMotion.Standard,
    delayMillis: Int = 0,
): FiniteAnimationSpec<T> =
    if (LocalMotionEnabled.current) tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = easing) else snap()
