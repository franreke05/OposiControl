package com.oposicontrol.backend.inventory

import com.oposicontrol.backend.LegacyRoute
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode

/** Una ruta hoja realmente montada: `"METHOD /path/{}"` (los parametros se normalizan a `{}`). */
internal data class WalkedRoute(val method: String, val path: String, val legacyMarked: Boolean) {
    val key: String get() = "$method $path"
}

/**
 * Recorre el arbol de rutas REAL de Ktor (sin parsear el codigo). Compartido por la conformidad con el OpenAPI
 * ([com.oposicontrol.backend.openapi.Slice1ContractConformanceTest]) y por el inventario ([RouteInventoryTest]).
 * `legacyMarked` = la hoja lleva el plugin `legacyRoute(...)` (contador `legacy_route_requests_total`).
 */
internal object RouteTreeWalker {
    fun walk(root: RoutingNode): List<WalkedRoute> {
        val out = mutableListOf<WalkedRoute>()
        fun visit(route: RoutingNode, path: String, method: String?) {
            val selector = route.selector
            val nextPath = when (selector) {
                is PathSegmentConstantRouteSelector -> "$path/${selector.value}"
                is PathSegmentParameterRouteSelector -> "$path/{}"
                else -> path
            }
            val nextMethod = (selector as? HttpMethodRouteSelector)?.method?.value ?: method
            if (selector is HttpMethodRouteSelector && route.children.isEmpty()) {
                out += WalkedRoute(nextMethod!!, nextPath.ifEmpty { "/" }, route.pluginOrNull(LegacyRoute) != null)
            }
            route.children.forEach { visit(it, nextPath, nextMethod) }
        }
        visit(root, "", null)
        return out
    }

    fun keys(root: RoutingNode): Set<String> = walk(root).map { it.key }.toSortedSet()
}
