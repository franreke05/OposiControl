package com.oposicontrol.backend.inventory

import com.oposicontrol.backend.AdminAnalyticsGateway
import com.oposicontrol.backend.AdminDashboardOverviewGateway
import com.oposicontrol.backend.AdminRankingsGateway
import com.oposicontrol.backend.AdminV1Gateway
import com.oposicontrol.backend.AppV1Gateway
import com.oposicontrol.backend.CanonicalTestEnv
import com.oposicontrol.backend.CombinedRoutingDeps
import com.oposicontrol.backend.ServerProfile
import com.oposicontrol.backend.configureServerProfile
import com.oposicontrol.backend.SupabaseAdminGateway
import com.oposicontrol.backend.SupabaseContentGateway
import com.oposicontrol.backend.SupabaseJwtVerifier
import com.oposicontrol.backend.SupabaseNewsGateway
import com.oposicontrol.backend.SupabaseOposiBotsUserAdminGateway
import com.oposicontrol.backend.SupabaseTagSearchGateway
import com.oposicontrol.backend.SupabaseV1Gateway
import com.oposicontrol.backend.V1StubHttpClient
import com.oposicontrol.backend.V1StubResponse
import com.oposicontrol.backend.configureConsumerAuthOnly
import com.oposicontrol.backend.gatewayBearerAuth
import com.oposicontrol.backend.installBackendStatusPages
import com.oposicontrol.backend.installCombinedRoutes
import com.oposicontrol.backend.installRequestCorrelation
import com.oposicontrol.backend.testJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json

/** Monta el arbol de rutas REAL (perfiles combined y consumer) con gateways de prueba que no hacen IO. */
internal object CombinedRouteFixture {
    /** Dependencias de prueba. En `bot` los gateways de administracion son nulos, como en produccion (no se construyen). */
    fun stubDeps(profile: ServerProfile = ServerProfile.COMBINED, readinessCheck: () -> String = { "UP" }): CombinedRoutingDeps {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val url = "https://stub.invalid"
        val env = mapOf("SUPABASE_URL" to url, "SUPABASE_SECRET_KEY" to "sb_secret_inventory_stub")
        val lookup: (String) -> String? = { env[it] }
        val stubClient = V1StubHttpClient({ }, { V1StubResponse(200, "[]") })
        val admin = profile.mountsControl
        return CombinedRoutingDeps(
            contentGateway = SupabaseContentGateway.fromEnvironment(json, lookup)!!.takeIf { admin },
            newsGateway = SupabaseNewsGateway.fromEnvironment(json, lookup)!!.takeIf { admin },
            tagSearchGateway = SupabaseTagSearchGateway.fromEnvironment(json, lookup)!!.takeIf { admin },
            adminGateway = SupabaseAdminGateway.fromEnvironment(json, lookup)!!.takeIf { admin },
            adminAnalyticsGateway = AdminAnalyticsGateway.fromEnvironment(json, lookup)!!.takeIf { admin },
            adminRankingsGateway = AdminRankingsGateway.fromEnvironment(json, lookup)!!.takeIf { admin },
            adminDashboardOverviewGateway = AdminDashboardOverviewGateway.fromEnvironment(json, lookup)!!.takeIf { admin },
            v1Gateway = SupabaseV1Gateway.forTesting(url, "stub-key", json, stubClient),
            adminV1Gateway = AdminV1Gateway.forTesting(url, "stub-key", json, stubClient).takeIf { admin },
            oposiBotsUserAdminGateway = SupabaseOposiBotsUserAdminGateway.fromEnvironment(json, lookup)!!.takeIf { admin },
            appV1Gateway = AppV1Gateway.forTesting(url, "stub-key", json, stubClient),
            jwtVerifier = SupabaseJwtVerifier(url, json),
            allowDevUser = false,
            appEnv = "production",
            authRuntime = CanonicalTestEnv().runtime().takeIf { profile != ServerProfile.CONTROL },
            identityResolver = null,
            readinessCheck = readinessCheck,
        )
    }

    fun walkCombined(extra: Route.() -> Unit = {}): List<WalkedRoute> {
        var walked = emptyList<WalkedRoute>()
        testApplication {
            application {
                install(ContentNegotiation) { json(testJson) }
                installRequestCorrelation()
                installBackendStatusPages()
                install(Authentication) { gatewayBearerAuth("production", { it }) }
                val root = routing {
                    installCombinedRoutes(stubDeps())
                    extra()
                }
                walked = RouteTreeWalker.walk(root as RoutingNode)
            }
            startApplication()
        }
        return walked
    }

    /** Recorre el arbol REAL que monta [configureServerProfile] (los MISMOS plugins y rutas que `module()`) para [profile]. */
    fun walkProfile(profile: ServerProfile): List<WalkedRoute> {
        var walked = emptyList<WalkedRoute>()
        testApplication {
            application {
                configureServerProfile(profile, testJson, stubDeps(profile))
                walked = RouteTreeWalker.walk(routing { } as RoutingNode)
            }
            startApplication()
        }
        return walked
    }

    fun walkConsumer(): List<WalkedRoute> {
        var walked = emptyList<WalkedRoute>()
        testApplication {
            application {
                configureConsumerAuthOnly(CanonicalTestEnv().runtime())
                walked = RouteTreeWalker.walk(routing { } as RoutingNode)
            }
            startApplication()
        }
        return walked
    }

}
