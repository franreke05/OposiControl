package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.oposicontrol.admin.core.ui.theme.Sizing
import oposicontrol.core.ui.generated.resources.Res
import oposicontrol.core.ui.generated.resources.ic_activity
import oposicontrol.core.ui.generated.resources.ic_alert
import oposicontrol.core.ui.generated.resources.ic_arrow_left
import oposicontrol.core.ui.generated.resources.ic_audit
import oposicontrol.core.ui.generated.resources.ic_ban
import oposicontrol.core.ui.generated.resources.ic_chart_line
import oposicontrol.core.ui.generated.resources.ic_check
import oposicontrol.core.ui.generated.resources.ic_chevron_down
import oposicontrol.core.ui.generated.resources.ic_chevron_left
import oposicontrol.core.ui.generated.resources.ic_chevron_right
import oposicontrol.core.ui.generated.resources.ic_clock
import oposicontrol.core.ui.generated.resources.ic_close
import oposicontrol.core.ui.generated.resources.ic_credit_card
import oposicontrol.core.ui.generated.resources.ic_download
import oposicontrol.core.ui.generated.resources.ic_edit
import oposicontrol.core.ui.generated.resources.ic_exams
import oposicontrol.core.ui.generated.resources.ic_eye
import oposicontrol.core.ui.generated.resources.ic_filter
import oposicontrol.core.ui.generated.resources.ic_forum
import oposicontrol.core.ui.generated.resources.ic_help_circle
import oposicontrol.core.ui.generated.resources.ic_home
import oposicontrol.core.ui.generated.resources.ic_image
import oposicontrol.core.ui.generated.resources.ic_inbox
import oposicontrol.core.ui.generated.resources.ic_info
import oposicontrol.core.ui.generated.resources.ic_key
import oposicontrol.core.ui.generated.resources.ic_layers
import oposicontrol.core.ui.generated.resources.ic_list
import oposicontrol.core.ui.generated.resources.ic_map_pin
import oposicontrol.core.ui.generated.resources.ic_more
import oposicontrol.core.ui.generated.resources.ic_news
import oposicontrol.core.ui.generated.resources.ic_package
import oposicontrol.core.ui.generated.resources.ic_panel_left
import oposicontrol.core.ui.generated.resources.ic_paperclip
import oposicontrol.core.ui.generated.resources.ic_plus
import oposicontrol.core.ui.generated.resources.ic_pulse
import oposicontrol.core.ui.generated.resources.ic_refresh
import oposicontrol.core.ui.generated.resources.ic_reports
import oposicontrol.core.ui.generated.resources.ic_roles
import oposicontrol.core.ui.generated.resources.ic_search
import oposicontrol.core.ui.generated.resources.ic_settings
import oposicontrol.core.ui.generated.resources.ic_shield
import oposicontrol.core.ui.generated.resources.ic_sort
import oposicontrol.core.ui.generated.resources.ic_store
import oposicontrol.core.ui.generated.resources.ic_support
import oposicontrol.core.ui.generated.resources.ic_tester
import oposicontrol.core.ui.generated.resources.ic_theme
import oposicontrol.core.ui.generated.resources.ic_ticket
import oposicontrol.core.ui.generated.resources.ic_trash
import oposicontrol.core.ui.generated.resources.ic_trending_up
import oposicontrol.core.ui.generated.resources.ic_trophy
import oposicontrol.core.ui.generated.resources.ic_upload
import oposicontrol.core.ui.generated.resources.ic_users
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Juego de iconos de la aplicación.
 *
 * Un único set (Lucide, ISC, lineal, grosor 2) y un único punto de entrada. Antes
 * de esto el escritorio tenía cinco iconos reales —los cinco de la barra inferior
 * del móvil— y los otros veintiuno eran caracteres Unicode (`☷`, `⚿`, `⌁`, `▦`)
 * que el fallback de `AppShell` pintaba como texto. Manrope no contiene esos
 * glifos, así que se resolvían con la fuente del sistema o como caja vacía, con
 * una métrica distinta en cada fila del menú.
 *
 * `key` es el identificador estable que usa el catálogo de navegación.
 */
enum class AppIcon(val key: String) {
    // Navegación
    HOME("home"),
    LAYERS("layers"),
    EXAMS("exams"),
    QUESTIONS("questions"),
    RESOURCES("resources"),
    IMPORT("import"),
    USERS("users"),
    RANKINGS("rankings"),
    PLANS("plans"),
    CATALOG("catalog"),
    TICKETS("tickets"),
    MARKET("market"),
    ACCESS("access"),
    FORUM("forum"),
    MODERATION("moderation"),
    REPORTS("reports"),
    TESTER("tester"),
    ANALYTICS("analytics"),
    PERFORMANCE("performance"),
    EVENTS("events"),
    NEWS("news"),
    NEWS_TYPES("news-types"),
    ASSETS("assets"),
    SUPPORT("support"),
    AUDIT("audit"),
    ROLES("roles"),
    SETTINGS("settings"),
    STATUS("status"),

    // Acciones y estados
    INFO("info"),
    HELP("help"),

    /** Cambiar entre tema claro y oscuro. Sol y luna a la vez: es el control, no el estado. */
    THEME("theme"),
    SEARCH("search"),
    FILTER("filter"),
    REFRESH("refresh"),
    EDIT("edit"),
    DELETE("delete"),
    VIEW("view"),
    ADD("add"),
    CLOSE("close"),
    CHECK("check"),
    CHEVRON_RIGHT("chevron-right"),
    CHEVRON_LEFT("chevron-left"),
    CHEVRON_DOWN("chevron-down"),
    MORE("more"),
    WARNING("warning"),
    DOWNLOAD("download"),
    UPLOAD("upload"),
    COLLAPSE("collapse"),
    BACK("back"),
    SORT("sort"),
    CLOCK("clock"),
    BLOCKED("blocked"),

    /** Dónde vive algo: ámbito geográfico, comunidad, sede. */
    LOCATION("location"),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun fromKey(key: String): AppIcon? = byKey[key]
    }
}

private val AppIcon.drawable: DrawableResource
    get() = when (this) {
        AppIcon.HOME -> Res.drawable.ic_home
        AppIcon.LAYERS -> Res.drawable.ic_layers
        AppIcon.EXAMS -> Res.drawable.ic_exams
        AppIcon.QUESTIONS -> Res.drawable.ic_help_circle
        AppIcon.RESOURCES -> Res.drawable.ic_paperclip
        AppIcon.IMPORT -> Res.drawable.ic_upload
        AppIcon.USERS -> Res.drawable.ic_users
        AppIcon.RANKINGS -> Res.drawable.ic_trophy
        AppIcon.PLANS -> Res.drawable.ic_credit_card
        AppIcon.CATALOG -> Res.drawable.ic_package
        AppIcon.TICKETS -> Res.drawable.ic_ticket
        AppIcon.MARKET -> Res.drawable.ic_store
        AppIcon.ACCESS -> Res.drawable.ic_key
        AppIcon.FORUM -> Res.drawable.ic_forum
        AppIcon.MODERATION -> Res.drawable.ic_shield
        AppIcon.REPORTS -> Res.drawable.ic_reports
        AppIcon.TESTER -> Res.drawable.ic_tester
        AppIcon.ANALYTICS -> Res.drawable.ic_chart_line
        AppIcon.PERFORMANCE -> Res.drawable.ic_trending_up
        AppIcon.EVENTS -> Res.drawable.ic_activity
        AppIcon.NEWS -> Res.drawable.ic_news
        AppIcon.NEWS_TYPES -> Res.drawable.ic_list
        AppIcon.ASSETS -> Res.drawable.ic_image
        AppIcon.SUPPORT -> Res.drawable.ic_support
        AppIcon.AUDIT -> Res.drawable.ic_audit
        AppIcon.ROLES -> Res.drawable.ic_roles
        AppIcon.SETTINGS -> Res.drawable.ic_settings
        AppIcon.STATUS -> Res.drawable.ic_pulse
        AppIcon.INFO -> Res.drawable.ic_info
        AppIcon.HELP -> Res.drawable.ic_help_circle
        AppIcon.THEME -> Res.drawable.ic_theme
        AppIcon.SEARCH -> Res.drawable.ic_search
        AppIcon.FILTER -> Res.drawable.ic_filter
        AppIcon.REFRESH -> Res.drawable.ic_refresh
        AppIcon.EDIT -> Res.drawable.ic_edit
        AppIcon.DELETE -> Res.drawable.ic_trash
        AppIcon.VIEW -> Res.drawable.ic_eye
        AppIcon.ADD -> Res.drawable.ic_plus
        AppIcon.CLOSE -> Res.drawable.ic_close
        AppIcon.CHECK -> Res.drawable.ic_check
        AppIcon.CHEVRON_RIGHT -> Res.drawable.ic_chevron_right
        AppIcon.CHEVRON_LEFT -> Res.drawable.ic_chevron_left
        AppIcon.CHEVRON_DOWN -> Res.drawable.ic_chevron_down
        AppIcon.MORE -> Res.drawable.ic_more
        AppIcon.WARNING -> Res.drawable.ic_alert
        AppIcon.DOWNLOAD -> Res.drawable.ic_download
        AppIcon.UPLOAD -> Res.drawable.ic_upload
        AppIcon.COLLAPSE -> Res.drawable.ic_panel_left
        AppIcon.BACK -> Res.drawable.ic_arrow_left
        AppIcon.SORT -> Res.drawable.ic_sort
        AppIcon.CLOCK -> Res.drawable.ic_clock
        AppIcon.BLOCKED -> Res.drawable.ic_ban
        AppIcon.LOCATION -> Res.drawable.ic_map_pin
    }

/**
 * Pinta un icono del set.
 *
 * `contentDescription` es null por defecto a propósito: casi siempre el icono
 * acompaña a un texto o vive dentro de un [DesktopIconButton] que ya expone su
 * propia etiqueta, y duplicarla haría que un lector de pantalla la leyese dos
 * veces. Cuando el icono va solo y sin contenedor etiquetado, pásala.
 */
@Composable
fun AppIconImage(
    icon: AppIcon,
    modifier: Modifier = Modifier,
    size: Dp = Sizing.icon,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(icon.drawable),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}
