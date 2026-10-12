package com.oposicontrol.admin.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.LocalMotionEnabled
import com.oposicontrol.admin.core.ui.theme.OposiMotion
import kotlin.math.roundToInt

/** Lo que [motionHighlight] «ha visto» antes de ver nada: distinto de cualquier disparador real, incluido `null`. */
private object NotSeenYet

/** Opacidad maxima del velo de [motionHighlight]: se ve que algo cambio sin tapar lo que dice la fila. */
private const val HIGHLIGHT_PEAK_ALPHA = 0.16f

/**
 * Entrada de contenido: aparece y sube [OposiMotion.EnterOffset]. Se repite cuando cambia [key] —otra pagina, otra
 * ficha, otra pestana— y NO cuando cambia lo que hay dentro: recargar datos no vuelve a animar nada.
 *
 * [order] desfasa la entrada ([OposiMotion.Stagger] por paso): 0 la cabecera, 1 lo que va debajo. Con [rise] a
 * `false` solo aparece, sin subir: para una cara que sustituye a otra en el mismo sitio (cargando por datos).
 *
 * Solo alfa y traslacion dentro de una capa grafica: no hay relayout ni recomposicion del contenido por frame, y la
 * capa se retira al terminar. El contenido es interactivo desde el primer frame; nada espera a la animacion.
 */
@Composable
fun Modifier.motionEntrance(key: Any?, order: Int = 0, rise: Boolean = true): Modifier {
    if (!LocalMotionEnabled.current) return this
    // `remember(key)`: al cambiar la clave el progreso nace ya en 0 en esa misma composicion. Con un `snapTo` dentro
    // del efecto, el contenido nuevo se pintaria un frame a opacidad completa antes de empezar a entrar.
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(OposiMotion.Emphasis, delayMillis = order * OposiMotion.Stagger, easing = OposiMotion.Enter),
        )
    }
    val settled by remember(progress) { derivedStateOf { progress.value >= 1f } }
    if (settled) return this
    val offsetPx = if (rise) with(LocalDensity.current) { OposiMotion.EnterOffset.toPx() } else 0f
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * offsetPx
    }
}

/**
 * Entrada de un dialogo: aparece y crece desde [OposiMotion.OverlayStartScale]. Cerrar es inmediato: quien confirma
 * o cancela no espera a que nada termine de irse.
 */
@Composable
fun Modifier.motionOverlayEntrance(): Modifier {
    if (!LocalMotionEnabled.current) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(OposiMotion.Overlay, easing = OposiMotion.Enter))
    }
    val settled by remember(progress) { derivedStateOf { progress.value >= 1f } }
    if (settled) return this
    return graphicsLayer {
        alpha = progress.value
        val scale = OposiMotion.OverlayStartScale + (1f - OposiMotion.OverlayStartScale) * progress.value
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Resalte de «esto acaba de cambiar»: un velo de [color] que aparece de golpe y se apaga solo en
 * [OposiMotion.Highlight]. Se dispara cuando cambia [trigger]; el valor con el que la fila nace no cuenta, asi que
 * una lista recien cargada no parpadea entera. Con [onAppear] la fila se resalta tambien al nacer: para la que se
 * anade a una lista que ya estaba en pantalla (quien llama decide cual es esa).
 *
 * Es un dibujo encima del contenido: no cambia el tamano ni mueve nada alrededor.
 */
@Composable
fun Modifier.motionHighlight(trigger: Any?, color: Color, cornerRadius: Dp = 0.dp, onAppear: Boolean = false): Modifier {
    if (!LocalMotionEnabled.current) return this
    val veil = remember { Animatable(0f) }
    var seen by remember { mutableStateOf(if (onAppear) NotSeenYet else trigger) }
    LaunchedEffect(trigger) {
        if (trigger == seen) return@LaunchedEffect
        seen = trigger
        veil.snapTo(1f)
        veil.animateTo(0f, tween(OposiMotion.Highlight, easing = OposiMotion.Standard))
    }
    val radiusPx = with(LocalDensity.current) { cornerRadius.toPx() }
    return drawWithContent {
        drawContent()
        val strength = veil.value
        if (strength > 0f) {
            drawRoundRect(color = color.copy(alpha = HIGHLIGHT_PEAK_ALPHA * strength), cornerRadius = CornerRadius(radiusPx, radiusPx))
        }
    }
}

/**
 * Un entero que cuenta desde su valor anterior hasta [target]. [owner] dice DE QUIEN es el numero: mientras no
 * cambie, un valor nuevo es un cambio real y se cuenta; si cambia (otra cartera, otro usuario), el numero nuevo
 * aparece tal cual, porque no es que haya subido o bajado: es otro dato.
 *
 * Solo para cifras que cambian de verdad delante de quien mira (un saldo tras un ajuste). Un total que se acaba de
 * cargar no se «cuenta».
 */
@Composable
fun animatedCount(target: Int, owner: Any?): Int {
    if (!LocalMotionEnabled.current) return target
    val value = remember(owner) { Animatable(target.toFloat()) }
    LaunchedEffect(owner, target) {
        if (value.targetValue != target.toFloat()) {
            value.animateTo(target.toFloat(), tween(OposiMotion.Emphasis * 2, easing = OposiMotion.Standard))
        }
    }
    return value.value.roundToInt()
}
