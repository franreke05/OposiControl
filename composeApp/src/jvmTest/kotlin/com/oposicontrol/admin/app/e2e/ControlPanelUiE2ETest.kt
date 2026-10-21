package com.oposicontrol.admin.app.e2e

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
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
import com.oposicontrol.admin.features.imports.presentation.LocalPdfPickerOverride
import com.oposicontrol.admin.features.imports.presentation.PickedPdf
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
import kotlin.test.assertTrue

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

    /** El PDF que «elige» la persona en Importación: el diálogo nativo no se puede pulsar desde una prueba. */
    private var nextPdf: PickedPdf? = null

    private fun flow(name: String, body: ComposeUiTest.() -> Unit) {
        if (local == null) {
            println("UI E2E SKIPPED $name: CONTROL_E2E_LOCAL not set")
            return
        }
        check(dbContainer.startsWith("oposi-control-e2e-")) { "solo el stack aislado oposi-control-e2e" }
        println("UI E2E RUN $name")
        runDesktopComposeUiTest(width = 1440, height = 1000, testTimeout = kotlin.time.Duration.parse("4m")) {
            val memory = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AdminDatabase.Schema.create(it) }
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 1f, fontScale = 1f),
                    LocalPdfPickerOverride provides { nextPdf },
                ) {
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

    /** SQL contra el stack aislado (datos sintéticos). Por JDBC al puerto del stack aislado (`ports.json`): `docker exec` abría una consola por consulta en Windows. */
    private fun sql(query: String): String = E2eDb.connection(local!!).createStatement().use { st ->
        var hasRows = st.execute(query)
        var last = ""
        while (true) {
            if (hasRows) {
                st.resultSet.use { rs ->
                    val n = rs.metaData.columnCount
                    val rows = mutableListOf<String>()
                    while (rs.next()) rows += (1..n).joinToString("|") { rs.getString(it) ?: "" }
                    last = rows.joinToString("\n")
                }
            } else if (st.updateCount == -1) {
                break
            }
            hasRows = st.moreResults
        }
        last
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

    /** Da a un examen existente un título único (dato de preparación) y lo abre en el espacio de trabajo como haría una persona. */
    private fun ComposeUiTest.openExam(likePattern: String, extraWhere: String = "", tag: String, prepare: (String) -> Unit = {}): Pair<String, String> {
        val title = "E2E UI $tag " + UUID.randomUUID().toString().take(8)
        // También vale el examen que esta misma prueba renombró en una pasada anterior: así se puede repetir sin regenerar datos.
        val id = sql("select id from public.examenes where (titulo like '$likePattern' or titulo like 'E2E UI $tag %') $extraWhere order by creado_en desc limit 1")
        check(id.isNotBlank()) { "no hay examen $likePattern en el stack" }
        sql("update public.examenes set titulo = '$title' where id = '$id'")
        prepare(id)
        login("admin@exam.local")
        waitText("Exámenes")
        onAllNodesWithText("Exámenes")[0].performClick()
        waitText("Buscar examen")
        onAllNodes(hasSetTextAction())[0].performTextInput(title)
        waitText(title)
        clickLast(title)
        waitText("Guardar cambios")
        return id to title
    }

    /** GlassToggleRow: solo el Switch es pulsable; se busca el interruptor que tiene ese título al lado. */
    private fun ComposeUiTest.toggle(title: String) {
        onAllNodes(isToggleable() and hasAnySibling(hasText(title)))[0].performScrollTo().performClick()
        waitForIdle()
    }

    /** Campo editable por su texto de ayuda (placeholder), como lo localizaría una persona. */
    private fun ComposeUiTest.field(placeholder: String) = onAllNodes(hasSetTextAction() and hasText(placeholder, substring = true))[0]

    private fun ComposeUiTest.tab(name: String) {
        onAllNodes(hasText(name) and hasClickAction())[0].performClick()
        waitForIdle()
    }

    // ---------- 2. edición de un examen ----------

    @Test
    fun uiAdminEditsAnExam() = flow("ui-edit-exam") {
        val (id, title) = openExam("E2E J02%", tag = "editar")
        val edited = "$title editado"
        val auditBefore = sql("select count(*) from public.auditoria_log where accion = 'examen_actualizado' and registro_id::text = '$id'").toInt()
        // Información básica: el segundo campo editable es el Título (el primero es el buscador).
        onAllNodes(hasSetTextAction())[1].performTextClearance()
        onAllNodes(hasSetTextAction())[1].performTextInput(edited)
        onNodeWithText("Guardar cambios").performClick()
        waitUntil(timeoutMillis = 20_000) { sql("select titulo from public.examenes where id = '$id'") == edited }
        // Una sola fila por guardado (el panel envía el formulario entero: «campos» son los enviados, no solo el cambiado).
        assertEquals(auditBefore + 1, sql("select count(*) from public.auditoria_log where accion = 'examen_actualizado' and registro_id::text = '$id' and usuario_id = '$adminId'").toInt())
        shot("ui-edit-exam-saved")
    }

    // ---------- 7. precio de un examen ----------

    @Test
    fun uiAdminSetsAnExamPrice() = flow("ui-price") {
        // Se parte de un examen gratuito y sin precio; se prepara ANTES de abrirlo para que la pantalla lo cargue así.
        val (id, _) = openExam("E2E J07%", tag = "precio") {
            sql("update public.examenes set precio_tickets = null, comprable_con_tickets = false, tipo_acceso = 'gratuito' where id = '$it'")
        }
        tab("Acceso y monetización")
        onAllNodes(hasText("Premium") and hasClickAction())[0].performClick()
        waitText("Poner un precio")
        toggle("Poner un precio")
        waitForIdle()
        shot("ui-price-form")
        val fields = onAllNodes(hasSetTextAction())
        fields[1].performTextClearance()
        fields[1].performTextInput("90")
        onNodeWithText("Guardar cambios").performClick()
        waitUntil(timeoutMillis = 20_000) { sql("select tipo_acceso || '|' || coalesce(precio_tickets::text, '') from public.examenes where id = '$id'") == "premium|90" }
        shot("ui-price-saved")
    }

    // ---------- 5. reglas de puntuación ----------

    @Test
    fun uiAdminChangesTheGradingRules() = flow("ui-grading") {
        // Un borrador de J04 o uno que ya usó esta prueba (cada pasada lo renombra); las reglas se dejan en el punto de partida.
        val (id, _) = openExam(
            "E2E%",
            extraWhere = "and activo and publicado_en is null and (titulo like 'E2E J04%' or titulo like 'E2E UI reglas%')",
            tag = "reglas",
        ) { sql("update public.examenes set penalizacion_racional = true, penalizacion_num = 0, penalizacion_den = 1 where id = '$it'") }
        tab("Configuración")
        waitText("Penalización racional")
        toggle("Penalización racional")
        waitForIdle()
        // Con la racional apagada, la fracción fija es editable: numerador 0 -> 1, denominador 1 -> 3.
        // Numerador y denominador comparten fila (sus etiquetas son hermanas de ambos campos): [0] numerador, [1] denominador.
        val fraction = { onAllNodes(hasSetTextAction() and hasAnySibling(hasText("Denominador"))) }
        fraction()[0].performScrollTo().performTextClearance()
        fraction()[0].performTextInput("1")
        fraction()[1].performTextClearance()
        fraction()[1].performTextInput("3")
        shot("ui-grading-form")
        onNodeWithText("Guardar reglas").performScrollTo().performClick()
        waitUntil(timeoutMillis = 20_000) {
            sql("select penalizacion_racional || '|' || penalizacion_num || '|' || penalizacion_den from public.examenes where id = '$id'") == "false|1|3"
        }
        assertTrue(sql("select count(*) from public.auditoria_log where accion like 'examen_reglas%' and registro_id::text = '$id'").toInt() >= 2)
    }

    // ---------- 4. anulada con motivo ----------

    @Test
    fun uiAdminAnnulsAQuestionWithItsReason() = flow("ui-annul") {
        // Regla «no puntúa»: una anulada no necesita reserva (J05 la deja así).
        val (id, _) = openExam("E2E J05%", extraWhere = "and activo and publicado_en is null and regla_anuladas = 'NO_PUNTUA'", tag = "anular")
        val question = sql("select p.id || '|' || p.titulo from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id where pe.examen_id = '$id' and not pe.anulada and not coalesce(pe.es_reserva, false) order by pe.posicion limit 1")
        val (questionId, questionTitle) = question.split('|', limit = 2)
        tab("Configuración")
        waitText("Anuladas y reservas")
        onAllNodesWithText("Anuladas y reservas")[0].performScrollTo()
        onAllNodesWithText(questionTitle.take(40), substring = true).let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo().performClick()
        waitText("Anulada por el tribunal")
        toggle("Anulada por el tribunal")
        waitText("Motivo de la anulación")
        val fields = onAllNodes(hasSetTextAction())
        fields[fields.fetchSemanticsNodes().size - 1].performTextInput("Error en el enunciado según el tribunal (UI E2E)")
        onNodeWithText("Guardar marcas").performScrollTo().performClick()
        waitUntil(timeoutMillis = 20_000) { sql("select anulada::text from public.preguntas_examen where examen_id = '$id' and pregunta_id = '$questionId'") == "true" }
        assertEquals("Error en el enunciado según el tribunal (UI E2E)", sql("select motivo_anulacion from public.preguntas_examen where examen_id = '$id' and pregunta_id = '$questionId'"))
        shot("ui-annul-saved")
    }

    /** Con 1 anulada y 1 reserva, anular otra dejaría el examen sin reservas: el servidor lo rechaza y no cambia nada. */
    @Test
    fun uiAnnullingWithoutEnoughReservesIsRejectedAndChangesNothing() = flow("ui-annul-rejected") {
        val (id, _) = openExam(
            "E2E%",
            extraWhere = "and activo and publicado_en is null and regla_anuladas is distinct from 'NO_PUNTUA'" +
                " and (select count(*) from public.preguntas_examen pe where pe.examen_id = examenes.id and coalesce(pe.es_reserva, false)) = 1" +
                " and exists (select 1 from public.preguntas_examen pe where pe.examen_id = examenes.id and pe.anulada)" +
                " and exists (select 1 from public.preguntas_examen pe where pe.examen_id = examenes.id and not pe.anulada and not coalesce(pe.es_reserva, false))",
            tag = "sin-reservas",
        )
        val question = sql("select p.id || '|' || p.titulo from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id where pe.examen_id = '$id' and not pe.anulada and not coalesce(pe.es_reserva, false) order by pe.posicion limit 1")
        val (questionId, questionTitle) = question.split('|', limit = 2)
        tab("Configuración")
        waitText("Anuladas y reservas")
        onAllNodesWithText(questionTitle.take(40), substring = true).let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo().performClick()
        waitText("Anulada por el tribunal")
        toggle("Anulada por el tribunal")
        waitText("Motivo de la anulación")
        val fields = onAllNodes(hasSetTextAction())
        fields[fields.fetchSemanticsNodes().size - 1].performTextInput("Motivo de prueba (UI E2E)")
        onNodeWithText("Guardar marcas").performScrollTo().performClick()
        waitText("sin reservas suficientes")
        assertEquals("false", sql("select anulada::text from public.preguntas_examen where examen_id = '$id' and pregunta_id = '$questionId'"))
    }

    // ---------- 10. conceder un acceso ----------

    @Test
    fun uiAdminGrantsAnExamAccess() = flow("ui-access") {
        val title = "E2E UI acceso " + UUID.randomUUID().toString().take(8)
        val examId = sql("select id from public.examenes where (titulo like 'E2E J10%' or titulo like 'E2E UI acceso%') and activo and publicado_en is not null order by creado_en desc limit 1")
        check(examId.isNotBlank()) { "no hay examen publicado de J10 en el stack" }
        sql("update public.examenes set titulo = '$title' where id = '$examId'")
        val q1 = "2a000000-0000-4000-8000-00000000002a"
        val before = sql("select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '$q1' and examen_id = '$examId'").toInt()
        login("admin@exam.local")
        waitText("Usuarios")
        onAllNodes(hasText("Usuarios") and hasClickAction())[0].performClick()
        waitText("Accesos")
        onAllNodes(hasText("Accesos") and hasClickAction())[0].performClick()
        waitText("Conceder acceso")
        onNodeWithText("Conceder acceso").performClick()
        waitText("Nombre o nombre de usuario")
        field("Nombre o nombre de usuario").performTextInput("q1_saldo_alto")
        waitForIdle()
        shot("ui-access-typed")
        onNodeWithText("Buscar").performClick()
        // El resultado se reconoce por su @usuario: la lista de la izquierda también muestra «Q1 saldo alto».
        waitText("@q1_saldo_alto")
        shot("ui-access-searched")
        onNodeWithText("@q1_saldo_alto").performClick()
        waitText("Cambiar")
        field("Título o especialidad").performTextInput(title)
        waitText(title)
        // El catálogo de la derecha va después de la lista de accesos (que puede tener concesiones antiguas de este examen).
        onAllNodes(hasText(title) and hasClickAction()).let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo().performClick()
        shot("ui-access-form")
        clickLast("Conceder acceso")
        waitUntil(timeoutMillis = 20_000) { sql("select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '$q1' and examen_id = '$examId'").toInt() == before + 1 }
        val grant = sql("select id from public.contenido_desbloqueado_usuario where usuario_id = '$q1' and examen_id = '$examId' order by creado_en desc limit 1")
        assertEquals("1", sql("select count(*) from public.auditoria_log where accion = 'acceso_examen_concedido' and registro_id::text = '$grant' and usuario_id = '$adminId'"))
        shot("ui-access-granted")
    }

    // ---------- 6. importación de un PDF ----------

    @Test
    fun uiAdminImportsAPdfIntoAnExam() = flow("ui-import") {
        val title = "E2E UI importar " + UUID.randomUUID().toString().take(8)
        val examId = sql(
            "select id from public.examenes where titulo like 'E2E%' and tipo_examen = 'rafaga' and activo and publicado_en is null" +
                " and not exists (select 1 from public.preguntas_examen pe where pe.examen_id = examenes.id)" +
                // Sin lotes previos: una pasada que falló después de subir deja un examen vacío pero con lote.
                " and not exists (select 1 from public.lotes_importacion_preguntas l where l.examen_id = examenes.id) order by creado_en desc limit 1",
        )
        check(examId.isNotBlank()) { "no hay borrador Ráfaga vacío en el stack" }
        sql("update public.examenes set titulo = '$title' where id = '$examId'")
        val pdf = File("../backend/ktor/src/test/resources/pdf/rafaga_valid.pdf").absoluteFile
        check(pdf.isFile) { "falta el PDF de ejemplo $pdf" }
        nextPdf = PickedPdf(path = pdf.path, name = pdf.name, sizeBytes = pdf.length())
        login("admin@exam.local")
        waitText("Importación")
        onAllNodes(hasText("Importación") and hasClickAction())[0].performClick()
        waitText("Examen destino")
        // Ráfaga es el tipo por defecto (paso «Tipo: Ráfaga»): no hace falta elegirlo.
        waitText("Ráfaga")
        onAllNodes(hasText("Elige un examen") and hasClickAction())[0].performClick()
        waitText(title)
        // La opción se pinta como «código · título».
        onAllNodesWithText(title, substring = true).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        onNodeWithText("Seleccionar PDF").performClick()
        waitText(pdf.name)
        shot("ui-import-picked")
        onNodeWithText("Subir y analizar").performClick()
        waitUntil(timeoutMillis = 30_000) { sql("select count(*) from public.lotes_importacion_preguntas where examen_id = '$examId'").toInt() == 1 }
        val lote = sql("select id from public.lotes_importacion_preguntas where examen_id = '$examId' order by creado_en desc limit 1")
        // Igual que `lote_importado`: la auditoría se escribe justo después de crear el lote, así que se espera a ella.
        waitUntil(timeoutMillis = 20_000) {
            sql("select count(*) from public.auditoria_log where accion = 'lote_creado' and registro_id::text = '$lote' and usuario_id = '$adminId'") == "1"
        }
        waitText("Publicar lote")
        shot("ui-import-review")
        // El PDF de ejemplo ya está en el banco tras la primera pasada: sus filas salen DUPLICADAS y el panel no deja
        // publicar hasta resolverlas. Se vinculan a la pregunta existente, como haría una persona.
        repeat(onAllNodesWithText("Ya existe en el banco", substring = true).fetchSemanticsNodes().size) {
            onAllNodesWithText("Ya existe en el banco", substring = true)[0].performScrollTo().performClick()
            waitText("Vincular a existente")
            onAllNodes(hasText("Vincular a existente", substring = true) and hasClickAction())[0].performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasText("Vincular a existente", substring = true) and hasClickAction()).fetchSemanticsNodes().isEmpty() }
            if (onAllNodesWithText("Cerrar").fetchSemanticsNodes().isNotEmpty()) onAllNodesWithText("Cerrar")[0].performClick()
            waitForIdle()
        }
        shot("ui-import-ready")
        onNodeWithText("Publicar lote").performScrollTo().performClick()
        waitUntil(timeoutMillis = 30_000) { sql("select count(*) from public.preguntas_examen where examen_id = '$examId'").toInt() > 0 }
        // La auditoría se escribe justo DESPUÉS de la RPC de importación: se espera a ella, no se lee en el mismo instante.
        waitUntil(timeoutMillis = 20_000) {
            sql("select count(*) from public.auditoria_log where accion = 'lote_importado' and registro_id::text = '$lote' and usuario_id = '$adminId'") == "1"
        }
        shot("ui-import-published")
    }

    // ---------- 3. publicación desde el espacio de trabajo del examen ----------

    @Test
    fun uiAdminPublishesAnExam() = flow("ui-publish") {
        val title = "E2E UI publicar " + UUID.randomUUID().toString().take(8)
        val id = sql("select e.id from public.examenes e where e.activo and e.publicado_en is null and e.total_preguntas > 0 and (e.titulo like 'E2E J06%' or e.titulo like 'E2E J07%' or e.titulo like 'E2E UI publicar %') order by e.creado_en desc limit 1")
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
    fun uiModeratorRejectsAPendingPost() {
        if (local == null) return flow("ui-moderator") {}
        // La publicación se prepara ANTES de montar la interfaz: su reintento (límite por usuario de la app) puede esperar
        // hasta un minuto, y dentro de la prueba de Compose esa espera bloquearía su reloj.
        val channel = sql("select id from public.categorias_oposired where activa order by creado_en desc limit 1")
        check(channel.isNotBlank()) { "sin canal activo (lo crea el E2E de datos J11)" }
        // Límite anti-spam de publicaciones por hora (lado Bot): las pasadas repetidas lo agotan para Q1. Se sube y, como la
        // premoderación, se restaura al acabar (mismo patrón que el E2E de datos J11).
        val premod = sql("select valor_integer from public.configuracion_v1 where clave = 'foro_premoderacion_posts'")
        val postsHora = sql("select valor_integer from public.configuracion_v1 where clave = 'foro_posts_hora'")
        sql("update public.configuracion_v1 set valor_integer = 1000 where clave = 'foro_posts_hora'")
        sql("update public.configuracion_v1 set valor_integer = 1 where clave = 'foro_premoderacion_posts'")
        try {
            // Solo queda en la cola la publicación de esta prueba: las pendientes anteriores se rechazan por la función de moderación.
            sql("select public.admin_foro_moderate('$adminId'::uuid, 'publicacion', p.id, 'rechazar', null, 'Limpieza previa del UI E2E') from public.publicaciones_oposired p where p.estado = 'pendiente_moderacion'")
            val marker = "UI E2E " + UUID.randomUUID().toString().take(8)
            // El límite por usuario de la app (lado Bot) puede saltar tras muchas pasadas seguidas: se reintenta como lo haría
            // una persona, con pausas y hasta un minuto; si sigue limitado, el fallo dice por qué.
            var created = ""
            for (attempt in 1..7) {
                created = post(
                    "$appUrl/api/v1/app/oposired/publicaciones", """{"categoriaId":"$channel","titulo":"$marker","cuerpo":"Duda de moderacion desde la interfaz $marker"}""",
                    mapOf("Authorization" to "Bearer " + tokens["Q1"]!!.jsonPrimitive.content, "Idempotency-Key" to UUID.randomUUID().toString()),
                )
                if (!created.contains("rate_limited")) break
                Thread.sleep(10_000)
            }
            val postId = sql("select id from public.publicaciones_oposired where titulo = '$marker'")
            check(postId.isNotBlank()) { "la publicación no se creó: $created" }
            flow("ui-moderator") {
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
        } finally {
            sql("update public.configuracion_v1 set valor_integer = ${premod.ifEmpty { "0" }} where clave = 'foro_premoderacion_posts'")
            if (postsHora.isNotEmpty()) sql("update public.configuracion_v1 set valor_integer = $postsHora where clave = 'foro_posts_hora'")
        }
    }
}

/** Una conexión por proceso de pruebas al Postgres del stack aislado (`127.0.0.1`, datos sintéticos, credenciales locales). */
private object E2eDb {
    private var conn: java.sql.Connection? = null

    @Synchronized
    fun connection(local: File): java.sql.Connection {
        conn?.takeIf { !it.isClosed }?.let { return it }
        val port = Json.parseToJsonElement(File(local, "ports.json").readText()).jsonObject["db"]!!.jsonPrimitive.content
        return java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:$port/postgres", "postgres", "postgres")
            .apply { autoCommit = true }
            .also { conn = it }
    }
}
