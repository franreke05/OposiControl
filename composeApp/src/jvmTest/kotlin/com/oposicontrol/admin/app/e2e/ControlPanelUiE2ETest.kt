package com.oposicontrol.admin.app.e2e

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.app.bootstrap.appModules
import com.oposicontrol.admin.app.root.AppRoot
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * E2E de INTERFAZ: la app OposiControl entera (Koin real de producción, AppRoot, pantallas, ViewModels y repositorios
 * reales) contra el stack AISLADO `oposi-control-e2e`: login real por la pantalla de acceso (GoTrue local del stack),
 * Ktor `control` real (:18190) y PG17. Solo se sustituye la base local de la app de escritorio por una en memoria: la
 * prueba no toca nunca `%APPDATA%\OposiControl`.
 *
 * Sin `CONTROL_E2E_LOCAL` (carpeta `.local` del stack, con `secrets.json` y `e2e-passwords.json`) cada prueba dice
 * «UI E2E SKIPPED» y no hace nada. Las contraseñas son de usuarios SINTÉTICOS del stack y solo viven en esa carpeta.
 */
@OptIn(ExperimentalTestApi::class)
class ControlPanelUiE2ETest {
    private val local: File? = System.getenv("CONTROL_E2E_LOCAL")?.let(::File)?.takeIf { it.isDirectory }
    private val dbContainer = System.getenv("CONTROL_E2E_DB_CONTAINER") ?: "oposi-control-e2e-db-1"
    private val shots = File(System.getenv("CONTROL_E2E_SHOTS") ?: "build/e2e-ui-shots").apply { mkdirs() }

    private val secrets: JsonObject by lazy { Json.parseToJsonElement(File(local, "secrets.json").readText()).jsonObject }
    private val passwords: JsonObject by lazy { Json.parseToJsonElement(File(local, "e2e-passwords.json").readText()).jsonObject }

    private val config by lazy {
        AppConfig(
            environment = Environment.DEV,
            baseUrl = System.getenv("CONTROL_E2E_CONTROL_URL") ?: "http://127.0.0.1:18190",
            backendToken = "",
            supabaseUrl = System.getenv("CONTROL_E2E_GATEWAY_URL") ?: "http://localhost:56321",
            supabaseAnonKey = secrets["anonKey"]!!.jsonPrimitive.content,
            supabaseBucketExamResources = "exam-resources",
            featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
            appName = "OposiControl UI E2E",
            appVersion = "e2e",
        )
    }

    private fun flow(name: String, body: ComposeUiTest.() -> Unit) {
        if (local == null) {
            println("UI E2E SKIPPED $name: CONTROL_E2E_LOCAL not set")
            return
        }
        check(dbContainer.startsWith("oposi-control-e2e-")) { "solo el stack aislado oposi-control-e2e" }
        println("UI E2E RUN $name")
        runDesktopComposeUiTest(width = 1440, height = 1000) {
            val memory = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AdminDatabase.Schema.create(it) }
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    AdminTheme(darkTheme = false) {
                        KoinApplication(application = {
                            allowOverride(true)
                            modules(appModules(config, AppPlatformContext()) + module { single { AdminDatabase(memory) } })
                        }) { AppRoot(appConfig = config) }
                    }
                }
            }
            try {
                body()
            } finally {
                shot("$name-final")
            }
        }
    }

    private fun ComposeUiTest.shot(name: String) {
        runCatching {
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots, "$name.png"))
        }
    }

    private fun ComposeUiTest.waitText(text: String, timeoutMs: Long = 20_000) {
        waitUntil(timeoutMillis = timeoutMs) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.login(email: String, password: String = passwords[email]!!.jsonPrimitive.content) {
        waitUntil(timeoutMillis = 20_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2 }
        val fields = onAllNodes(hasSetTextAction())
        fields[0].performTextInput(email)
        fields[1].performTextInput(password)
        onNodeWithText("Entrar").performClick()
    }

    /** psql en el contenedor del stack aislado (datos sintéticos). */
    private fun sql(query: String): String {
        val p = ProcessBuilder("docker", "exec", "-i", dbContainer, "psql", "-U", "postgres", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1")
            .redirectErrorStream(true).start()
        p.outputStream.use { it.write(query.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText().trim()
        check(p.waitFor() == 0) { "psql: $out" }
        return out
    }

    // ---------- 1. acceso y roles por la pantalla real ----------

    @Test
    fun uiLoginAdminEntersThePanel() = flow("ui-login-admin") {
        login("admin@exam.local")
        waitText("Exámenes")
        shot("ui-login-admin-in")
        onNodeWithText("Cerrar sesión", substring = true).assertExists()
        assertEquals(0, onAllNodesWithText("Sin acceso al panel").fetchSemanticsNodes().size)
    }

    @Test
    fun uiLoginWrongPasswordStaysOnLogin() = flow("ui-login-wrong") {
        login("admin@exam.local", password = "E2e-wrong-password")
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Entrar").fetchSemanticsNodes().isNotEmpty() && onAllNodesWithText("incorrect", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, onAllNodesWithText("Exámenes").fetchSemanticsNodes().size, "no entra")
    }

    @Test
    fun uiLoginBlockedAdminSeesNoAccess() = flow("ui-login-blocked") {
        login("blocked@control.local")
        waitText("Sin acceso al panel")
        // Texto genérico del panel para un 403 (exacto y correcto): el servidor no deja entrar y no hay menú.
        onNodeWithText("No tienes permisos", substring = true).assertExists()
        assertEquals(0, onAllNodesWithText("Exámenes").fetchSemanticsNodes().size, "sin menú")
    }

    @Test
    fun uiLoginModeratorSeesOnlyForumModeration() = flow("ui-login-moderator") {
        login("mod@control.local")
        waitText("Moderación del foro")
        assertEquals(0, onAllNodesWithText("Exámenes").fetchSemanticsNodes().size, "sin menú de admin")
        assertEquals(0, onAllNodesWithText("Canales").fetchSemanticsNodes().size, "sin configuración del foro")
    }

    // ---------- datos de preparación: lo que haría la app de Bot o un dato sintético; el flujo probado es la interfaz ----------

    private val ktorEnv: Map<String, String> by lazy {
        File(local, "ktor.env").readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }
    private val tokens: JsonObject by lazy { Json.parseToJsonElement(File(local, "tokens.json").readText()).jsonObject }
    private val appUrl get() = System.getenv("CONTROL_E2E_APP_URL") ?: "http://127.0.0.1:18189"
    private val adminId = "3a000000-0000-4000-8000-00000000003a"
    private val q4 = "2e000000-0000-4000-8000-00000000002e"

    private fun post(url: String, body: String, headers: Map<String, String>): String {
        val req = HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/json")
            .apply { headers.forEach { (k, v) -> header(k, v) } }.POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body()
    }

    /** Pedido sandbox de Q4 por la app y aviso firmado con importe erróneo → `en_revision` (lado Bot). */
    private fun orderInReview(): String {
        val created = post(
            "$appUrl/api/v1/app/payments/orders", """{"productCode":"LOCAL_PACK_50","provider":"sandbox"}""",
            mapOf("Authorization" to "Bearer " + tokens["Q4"]!!.jsonPrimitive.content, "Idempotency-Key" to UUID.randomUUID().toString()),
        )
        val id = Json.parseToJsonElement(created).jsonObject["order"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val raw = """{"eventId":"evt-${UUID.randomUUID()}","type":"payment.succeeded","orderId":"$id","transactionId":"tx-${UUID.randomUUID()}",""" +
            """"amountCents":1,"currency":"EUR","productCode":"LOCAL_PACK_50"}"""
        val ts = (System.currentTimeMillis() / 1000).toString()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ktorEnv.getValue("PAYMENTS_SANDBOX_SECRET").toByteArray(), "HmacSHA256")) }
        val sig = mac.doFinal("$ts.$raw".toByteArray()).joinToString("") { "%02x".format(it) }
        post("$appUrl/api/v1/webhooks/payments/sandbox", raw, mapOf("X-Sandbox-Timestamp" to ts, "X-Sandbox-Signature" to sig))
        check(sql("select estado from public.compras_usuario where id = '$id'") == "en_revision") { "el pedido no quedó en revisión" }
        return id
    }

    private fun ComposeUiTest.clickLast(text: String) {
        val nodes = onAllNodesWithText(text)
        nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
    }

    // ---------- 3. publicación desde el espacio de trabajo del examen ----------

    @Test
    fun uiAdminPublishesAnExam() = flow("ui-publish") {
        val title = "E2E UI publicar " + UUID.randomUUID().toString().take(8)
        val id = sql("select e.id from public.examenes e where e.activo and e.publicado_en is null and e.total_preguntas > 0 and e.titulo like 'E2E J07%' limit 1")
        check(id.isNotBlank()) { "no hay borrador con preguntas en el stack" }
        sql("update public.examenes set titulo = '$title' where id = '$id'")
        login("admin@exam.local")
        waitText("Exámenes")
        onAllNodesWithText("Exámenes")[0].performClick()
        // 107 exámenes en 9 páginas: se busca como lo haría una persona.
        waitText("Buscar examen")
        onAllNodes(hasSetTextAction())[0].performTextInput(title)
        waitText(title)
        shot("ui-publish-list")
        // El primer nodo con el título es el propio buscador; la tarjeta del examen es el último.
        clickLast(title)
        // Abierto el examen existente (ya no es el formulario «Nuevo examen»).
        waitText("Guardar cambios")
        // «Publicación» es también la cabecera de un grupo del menú lateral: la pestaña es la que responde al clic.
        onAllNodes(hasText("Publicación") and hasClickAction())[0].performClick()
        waitText("Publicar ahora")
        onNodeWithText("Publicar ahora").performScrollTo().performClick()
        clickLast("Publicar")
        waitText("Examen publicado.")
        assertEquals("t", sql("select (publicado_en is not null)::text from public.examenes where id = '$id'").take(1), "publicado en PG17")
        assertEquals("1", sql("select count(*) from public.auditoria_log where accion = 'examen_publicado' and registro_id::text = '$id' and usuario_id = '$adminId'"), "auditado con el admin como actor")
    }

    // ---------- 8. pedidos: resolver uno en revisión ----------

    @Test
    fun uiAdminCreditsAnOrderInReview() = flow("ui-orders") {
        // Solo queda en revisión el pedido de esta prueba (los de pruebas anteriores se rechazan en la preparación).
        // La máquina de estados prohíbe en_revision -> fallida por SQL directo (correcto): los restos se rechazan por la función admin.
        sql("select public.admin_payment_order_resolve('$adminId'::uuid, c.id, 'rechazar', 'Limpieza previa del UI E2E', false) from public.compras_usuario c where c.usuario_id = '$q4' and c.estado = 'en_revision'")
        val id = orderInReview()
        val before = sql("select saldo_tickets from public.carteras_usuario where usuario_id = '$q4'").toInt()
        login("admin@exam.local")
        waitText("Catálogo")
        onAllNodesWithText("Catálogo")[0].performClick()
        waitText("Pedidos")
        onAllNodesWithText("Pedidos")[0].performClick()
        waitText("@q4_pagos")
        shot("ui-orders-list")
        onAllNodesWithText("@q4_pagos")[0].performClick()
        waitText("Elige una decisión")
        onNodeWithText("Elige una decisión").performClick()
        waitText("Acreditar")
        onAllNodesWithText("Acreditar")[0].performClick()
        // Dos campos: «Motivo de la decisión» (obligatorio) y «Nota interna». El motivo es el penúltimo.
        val fields = onAllNodes(hasSetTextAction())
        fields[fields.fetchSemanticsNodes().size - 2].performTextInput("Pago verificado en el proveedor (UI E2E)")
        onNodeWithText("Resolver pedido").performScrollTo().performClick()
        waitText("Acreditar pedido")
        clickLast("Acreditar")
        waitText("Pedido acreditado")
        assertEquals("pagada", sql("select estado from public.compras_usuario where id = '$id'"))
        assertEquals(before + 50, sql("select saldo_tickets from public.carteras_usuario where usuario_id = '$q4'").toInt(), "+50 OT una vez")
        assertEquals("1", sql("select count(*) from public.auditoria_log where accion = 'pedido_resuelto' and registro_id::text = '$id' and usuario_id = '$adminId'"))
    }


    // ---------- 12. auditoría ----------

    @Test
    fun uiAdminReadsTheAuditLog() = flow("ui-audit") {
        login("admin@exam.local")
        // Auditoría es una pestaña del hub Sistema (no tiene entrada propia en el menú lateral).
        waitText("Sistema")
        onAllNodes(hasText("Sistema") and hasClickAction())[0].performScrollTo().performClick()
        waitText("Auditoría")
        onAllNodes(hasText("Auditoría") and hasClickAction())[0].performClick()
        waitText("Admin local")
        shot("ui-audit-list")
    }

    // ---------- 11. moderador: oculta una publicación desde su cola ----------

    @Test
    fun uiModeratorRejectsAPendingPost() = flow("ui-moderator") {
        val channel = sql("select id from public.categorias_oposired where activa order by creado_en desc limit 1")
        check(channel.isNotBlank()) { "sin canal activo (lo crea el E2E de datos J11)" }
        sql("update public.configuracion_v1 set valor_integer = 1 where clave = 'foro_premoderacion_posts'")
        // Solo queda en la cola la publicación de esta prueba: las pendientes anteriores se rechazan por la función de moderación.
        sql("select public.admin_foro_moderate('$adminId'::uuid, 'publicacion', p.id, 'rechazar', null, 'Limpieza previa del UI E2E') from public.publicaciones_oposired p where p.estado = 'pendiente_moderacion'")
        val marker = "UI E2E " + UUID.randomUUID().toString().take(8)
        post(
            "$appUrl/api/v1/app/oposired/publicaciones", """{"categoriaId":"$channel","titulo":"$marker","cuerpo":"Duda de moderacion desde la interfaz $marker"}""",
            mapOf("Authorization" to "Bearer " + tokens["Q1"]!!.jsonPrimitive.content, "Idempotency-Key" to UUID.randomUUID().toString()),
        )
        val postId = sql("select id from public.publicaciones_oposired where titulo = '$marker'")
        check(postId.isNotBlank()) { "la publicación no se creó" }
        login("mod@control.local")
        waitText(marker)
        shot("ui-moderator-queue")
        // En «Pendientes» las acciones son Aprobar, Rechazar y Silenciar al autor; rechazar deja la publicación oculta.
        onNodeWithText("Rechazar").performScrollTo().performClick()
        waitText("Motivo")
        val fields = onAllNodes(hasSetTextAction())
        fields[fields.fetchSemanticsNodes().size - 1].performTextInput("Fuera de tema (UI E2E)")
        clickLast("Rechazar")
        waitUntil(timeoutMillis = 20_000) { sql("select estado from public.publicaciones_oposired where id = '$postId'") == "oculta" }
        assertEquals("5c000000-0000-4000-8000-00000000005c", sql("select usuario_id from public.auditoria_log where registro_id::text = '$postId' order by creado_en desc limit 1"), "auditado con el MODERADOR como actor")
    }
}
