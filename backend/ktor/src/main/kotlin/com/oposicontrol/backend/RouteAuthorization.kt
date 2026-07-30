package com.oposicontrol.backend

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.routing.Route

internal class RouteAuthorizationConfiguration {
    internal var permissionResolver: (ApplicationCall) -> String = { "maintenance" }

    fun permission(permission: String) {
        permissionResolver = { permission }
    }

    fun permission(resolver: (ApplicationCall) -> String) {
        permissionResolver = resolver
    }
}

private val RouteAuthorization = createRouteScopedPlugin(
    name = "OposiControlRouteAuthorization",
    createConfiguration = ::RouteAuthorizationConfiguration,
) {
    val resolver = pluginConfig.permissionResolver
    on(AuthenticationChecked) { call ->
        call.requirePermission(resolver(call))
    }
}

internal fun Route.enforcePermission(permission: String) {
    install(RouteAuthorization) {
        permission(permission)
    }
}

internal fun Route.enforcePermission(resolver: (ApplicationCall) -> String) {
    install(RouteAuthorization) {
        permission(resolver)
    }
}
