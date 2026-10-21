package com.oposicontrol.admin.data.e2e

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.ApiClientFactory
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.AuditApi
import com.oposicontrol.admin.data.remote.source.RealAuditDataSource
import com.oposicontrol.admin.data.repository.AuditRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorAdminOperationsRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorAdminV1ContentRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorControlV1RepositoryImpl
import com.oposicontrol.admin.data.repository.KtorCommercialCatalogRepositoryImpl
import com.oposicontrol.admin.data.repository.KtorSystemStatusRepositoryImpl
import com.oposicontrol.admin.data.storage.PlatformFileReader
import com.oposicontrol.admin.domain.model.AuditFilter
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamAccess
import com.oposicontrol.admin.domain.model.ExamEconomyDraft
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.ForumAction
import com.oposicontrol.admin.domain.model.ForumChannelDraft
import com.oposicontrol.admin.domain.model.ForumContentType
import com.oposicontrol.admin.domain.model.OfficialQuestionDraft
import com.oposicontrol.admin.domain.model.OrderDecision
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.Session
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.File
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

/**
 * Recorridos E2E del panel OposiControl: repositorios REALES de `data` → Ktor REAL (perfil `control`) → PG17 REAL.
 * Cada recorrido verifica la fila en la base y la de `auditoria_log`, no solo el HTTP 200.
 *
 * Solo corre contra el stack AISLADO `oposi-control-e2e` (copia de ops/local-exam con proyecto, puertos y volúmenes
 * propios). Sin `CONTROL_E2E_LOCAL` (carpeta `.local` del stack) cada prueba sale sin hacer nada y lo dice en stdout.
 * Usuarios sintéticos: ADMIN (admin activo), SUPER (superadmin), ADMIN_BLK (admin bloqueado), MOD (moderador), Q1/Q4.
 * El lado de usuario (pedido sandbox, publicación en el foro) va por el Ktor de la app (`bot`), como lo haría OposiBot.
 */
class ControlJourneysE2ETest {
    private val local: File? = System.getenv("CONTROL_E2E_LOCAL")?.let(::File)?.takeIf { it.isDirectory }
    private val controlUrl = System.getenv("CONTROL_E2E_CONTROL_URL") ?: "http://127.0.0.1:18190"
    private val appUrl = System.getenv("CONTROL_E2E_APP_URL") ?: "http://127.0.0.1:18189"
    private val dbContainer = System.getenv("CONTROL_E2E_DB_CONTAINER") ?: "oposi-control-e2e-db-1"

    private val tokens: JsonObject by lazy { Json.parseToJsonElement(File(local, "tokens.json").readText()).jsonObject }
    private val ktorEnv: Map<String, String> by lazy {
        File(local, "ktor.env").readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private val community = "c1000000-0000-4000-8000-000000000001"
    private val specialty = "e5000000-0000-4000-8000-000000000001"
    private val admin = "3a000000-0000-4000-8000-00000000003a"
    private val q1 = "2a000000-0000-4000-8000-00000000002a"
    private val q4 = "2e000000-0000-4000-8000-00000000002e"

    private fun journey(name: String, body: suspend () -> Unit) {
        if (local == null) {
            println("E2E SKIPPED $name: CONTROL_E2E_LOCAL not set")
            return
        }
        // Nunca contra la base de otro stack (el QA de Bot es oposi-exam-local-*).
        check(dbContainer.startsWith("oposi-control-e2e-")) { "solo el stack aislado oposi-control-e2e" }
        println("E2E RUN $name")
        runBlocking { body() }
    }

    // ---------- piezas reales del panel ----------
    private val config by lazy {
        AppConfig(
            environment = Environment.DEV, baseUrl = controlUrl, backendToken = "", supabaseUrl = "http://localhost:56321",
            supabaseAnonKey = "", supabaseBucketExamResources = "exam-resources",
            featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false), appName = "OposiControl E2E", appVersion = "e2e",
        )
    }
    private val client: HttpClient by lazy { ApiClientFactory(config, NoOpLogger).create() }
    private val adapter = NetworkResultAdapter(NoOpLogger)

    private fun sessionOf(user: String?): SessionLocalDataSource {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        return SessionLocalDataSource(AdminDatabase(driver)).also { store ->
            if (user != null) {
                store.save(Session(user, user, token(user), "e2e-refresh", Clock.System.now() + kotlin.time.Duration.parse("11h"), listOf("admin")))
            }
        }
    }

    private inner class Panel(user: String?) {
        val session = sessionOf(user)
        val control = KtorControlV1RepositoryImpl(client, config, session, adapter)
        val content = KtorAdminV1ContentRepositoryImpl(client, config, session, adapter, PlatformFileReader(AppPlatformContext()))
        val operations = KtorAdminOperationsRepositoryImpl(client, config, session, adapter)
        val commercial = KtorCommercialCatalogRepositoryImpl(client, config, session, adapter)
        val status = KtorSystemStatusRepositoryImpl(client, config, session, adapter)
        val audit = AuditRepositoryImpl(RealAuditDataSource(AuditApi(client, config, session), adapter))
    }

    private fun token(user: String) = tokens[user]?.jsonPrimitive?.content ?: error("token $user")

    private fun <T> AppResult<T>.ok(what: String): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> fail("$what: $error")
    }

    private fun AppResult<*>.failure(what: String): AppError = when (this) {
        is AppResult.Success -> fail("$what: se esperaba un fallo y fue $value")
        is AppResult.Failure -> error
    }

    /** SQL contra el stack aislado; devuelve filas `a|b` como `psql -At`. Solo datos sintéticos. Por JDBC al puerto del stack aislado (`ports.json`): `docker exec` abría una consola por consulta en Windows. */
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

    private fun auditCount(action: String, record: String) =
        sql("select count(*) from public.auditoria_log where accion = '$action' and registro_id::text = '$record'").toInt()

    private fun auditActor(action: String, record: String) =
        sql("select usuario_id from public.auditoria_log where accion = '$action' and registro_id::text = '$record' order by creado_en desc limit 1")

    private fun newExam(title: String) = Exam(
        id = "", specialtyId = specialty, specialtyName = "", communityId = community, title = title, description = "E2E OposiControl",
        typeExam = "rafaga", typeAccess = "gratuito", priceTickets = 0, status = ExamStatus.DRAFT, publishFrom = null, publishedAt = null,
        sortOrder = 1, questionCount = 0, isOfficial = true, createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )

    private fun question(examId: String, n: Int): ExamQuestion {
        val now = Clock.System.now()
        val qid = "e2e-q$n"
        return ExamQuestion(
            id = qid, examId = examId, title = "Pregunta E2E $n", statement = "Enunciado E2E ${examId.take(8)} numero $n del examen", sortOrder = n,
            options = listOf("A", "B", "C", "D").mapIndexed { i, l -> QuestionOption("$qid-$l", qid, l, "Opcion $l de $n ${examId.take(8)}", i == 0, i + 1) },
            resources = emptyList(), createdAt = now, updatedAt = now,
        )
    }

    /** Examen borrador con [questions] preguntas importadas por la ruta real de importación del panel. */
    private suspend fun examWithQuestions(p: Panel, title: String, questions: Int): String {
        val exam = p.content.upsertExam(newExam(title)).ok("crear examen")
        if (questions > 0) p.content.importQuestions(exam.id, (1..questions).map { question(exam.id, it) }).ok("importar preguntas")
        return exam.id
    }

    // ---------- 1. acceso y permisos ----------
    @Test
    fun j01AccessAndPermissions() = journey("J01 access") {
        val a = Panel("ADMIN").status.loadSession().ok("admin")
        assertTrue(a.isAdmin && !a.isSuperAdmin && a.userId == admin, a.toString())
        val s = Panel("SUPER").status.loadSession().ok("superadmin")
        assertTrue(s.isSuperAdmin && s.isAdmin, s.toString())
        assertIs<AppError.Authorization>(Panel("ADMIN_BLK").status.loadSession().failure("admin bloqueado"))
        assertIs<AppError.Authorization>(Panel("Q1").status.loadSession().failure("usuario normal"))
        // Decisión del propietario 2026-10-20: el moderador entra en el panel, pero solo para moderar el foro.
        val m = Panel("MOD").status.loadSession().ok("moderador")
        assertTrue("moderador" in m.roles && !m.isAdmin && !m.isSuperAdmin, m.toString())
        assertIs<AppError.Authorization>(Panel("MOD").control.orders("en_revision", 0).failure("moderador lee pedidos"))
        assertIs<AppError.Authorization>(Panel("MOD").content.upsertExam(newExam("E2E moderador")).failure("moderador crea examen"))
        assertIs<AppError.Authentication>(Panel(null).status.loadSession().failure("sin token"))
        // Un admin bloqueado tampoco escribe: ninguna fila nueva.
        val before = sql("select count(*) from public.examenes")
        assertIs<AppError.Authorization>(Panel("ADMIN_BLK").content.upsertExam(newExam("E2E bloqueado")).failure("bloqueado crea examen"))
        assertEquals(before, sql("select count(*) from public.examenes"))
    }

    // ---------- 2. gestión de exámenes ----------
    @Test
    fun j02ExamManagement() = journey("J02 exams") {
        val p = Panel("ADMIN")
        val exam = p.content.upsertExam(newExam("E2E J02 crear")).ok("crear")
        assertEquals("$community|$specialty|E2E J02 crear|true|true", sql("select comunidad_id || '|' || especialidad_id || '|' || titulo || '|' || activo || '|' || (publicado_en is null) from public.examenes where id = '${exam.id}'"))
        assertEquals(1, auditCount("examen_creado", exam.id))
        assertEquals(admin, auditActor("examen_creado", exam.id), "el actor sale del JWT")
        val edited = p.content.upsertExam(exam.copy(title = "E2E J02 editado", status = ExamStatus.DRAFT)).ok("editar")
        assertEquals("E2E J02 editado", edited.title)
        assertEquals("E2E J02 editado", sql("select titulo from public.examenes where id = '${exam.id}'"))
        // Antes no se auditaba la edición (corregido 2026-10-20): una fila con los NOMBRES de los campos tocados.
        assertEquals(1, auditCount("examen_actualizado", exam.id))
        assertTrue(sql("select despues::text from public.auditoria_log where accion = 'examen_actualizado' and registro_id::text = '${exam.id}'").contains("titulo"))
        // Error: comunidad inexistente.
        p.content.upsertExam(newExam("E2E J02 sin comunidad").copy(communityId = UUID.randomUUID().toString())).failure("comunidad desconocida")
        assertEquals("0", sql("select count(*) from public.examenes where titulo = 'E2E J02 sin comunidad'"))
    }

    // ---------- 3. publicación y estados ----------
    @Test
    fun j03PublishAndStates() = journey("J03 publish") {
        val p = Panel("ADMIN")
        val empty = examWithQuestions(p, "E2E J03 vacio", 0)
        p.content.publishExam(empty).failure("publicar sin preguntas")
        assertEquals("t", sql("select (publicado_en is null) from public.examenes where id = '$empty'").take(1), "sigue en borrador")
        val id = examWithQuestions(p, "E2E J03 con preguntas", 2)
        assertEquals(ExamStatus.PUBLISHED, p.content.publishExam(id).ok("publicar").status)
        assertEquals("t|t", sql("select activo || '|' || (publicado_en is not null) from public.examenes where id = '$id'").replace("true", "t"))
        assertEquals(1, auditCount("examen_publicado", id))
        p.content.unpublishExam(id).ok("despublicar")
        assertEquals("t", sql("select (publicado_en is null) from public.examenes where id = '$id'").take(1))
        assertEquals(1, auditCount("examen_despublicado", id))
        assertEquals(ExamStatus.ARCHIVED, p.content.archiveExam(id).ok("archivar").status)
        assertEquals("f", sql("select activo from public.examenes where id = '$id'").take(1))
        assertEquals(1, auditCount("examen_archivado", id))
        assertIs<AppError.Authorization>(Panel("Q1").content.publishExam(id).failure("usuario publica"))
    }

    // ---------- 4. anuladas y reserva ----------
    @Test
    fun j04AnnulledAndReserve() = journey("J04 official") {
        val p = Panel("ADMIN")
        val id = examWithQuestions(p, "E2E J04 oficial", 3)
        val qs = p.control.officialQuestions(id).ok("leer oficiales")
        val first = qs.questions.first()
        // Decisión del propietario 2026-10-20: anular exige el motivo del tribunal. Sin él, el servidor responde 422 y nada cambia.
        val sinMotivo = p.control.saveOfficialQuestion(id, first.questionId, OfficialQuestionDraft(true, "   ", false, "")).failure("anular sin motivo")
        assertTrue(sinMotivo is AppError.Validation && sinMotivo.message.contains("motivo del tribunal"), sinMotivo.toString())
        assertEquals("f", sql("select anulada from public.preguntas_examen where examen_id = '$id' and pregunta_id = '${first.questionId}'"), "nada cambia")
        p.control.saveOfficialQuestion(id, first.questionId, OfficialQuestionDraft(true, "Anulada por el tribunal E2E", false, "")).ok("anular")
        assertEquals("t", sql("select anulada from public.preguntas_examen where examen_id = '$id' and pregunta_id = '${first.questionId}'").take(1))
        assertTrue(sql("select count(*) from public.auditoria_log where registro_id::text in ('$id', '${first.questionId}') and creado_en > now() - interval '5 minutes'").toInt() >= 1)
        // Reserva: la tercera pregunta pasa a ser la reserva 1.
        val third = qs.questions[2]
        p.control.saveOfficialQuestion(id, third.questionId, OfficialQuestionDraft(false, "", true, "1")).ok("reserva")
        assertEquals("true|1", sql("select es_reserva || '|' || orden_reserva from public.preguntas_examen where examen_id = '$id' and pregunta_id = '${third.questionId}'"))
        // Error del servidor: orden de reserva 0 (el panel ya quita el motivo si no se anula). Nada cambia en la segunda pregunta.
        val second = qs.questions[1]
        p.control.saveOfficialQuestion(id, second.questionId, OfficialQuestionDraft(false, "", true, "0")).failure("reserva con orden 0")
        assertEquals("false|false", sql("select anulada || '|' || es_reserva from public.preguntas_examen where examen_id = '$id' and pregunta_id = '${second.questionId}'"))
        assertIs<AppError.Authorization>(Panel("Q1").control.saveOfficialQuestion(id, second.questionId, OfficialQuestionDraft(true, "x", false, "")).failure("usuario anula"))
    }

    // ---------- 5. penalizaciones y reglas ----------
    @Test
    fun j05GradingRules() = journey("J05 grading") {
        val p = Panel("ADMIN")
        val id = examWithQuestions(p, "E2E J05 reglas", 2)
        p.control.saveGradingRules(id, "NO_PUNTUA", false, 1, 3, "Bases de la convocatoria E2E").ok("guardar reglas")
        assertEquals("NO_PUNTUA|1|3", sql("select coalesce(regla_anuladas, '') || '|' || penalizacion_num || '|' || penalizacion_den from public.examenes where id = '$id'"))
        assertTrue(p.control.getGradingAudit(id).ok("auditoria reglas").isNotEmpty())
        assertTrue(sql("select count(*) from public.auditoria_log where registro_id::text = '$id' and accion like 'examen_reglas%'").toInt() >= 1)
        p.control.saveGradingRules(id, "NO_PUNTUA", false, 1, 0, "Denominador cero").failure("denominador 0")
        assertEquals("1|3", sql("select penalizacion_num || '|' || penalizacion_den from public.examenes where id = '$id'"), "sin cambio")
    }

    // ---------- 6. importación de contenido ----------
    @Test
    fun j06ContentImport() = journey("J06 import") {
        val p = Panel("ADMIN")
        val id = examWithQuestions(p, "E2E J06 import", 3)
        assertEquals("3", sql("select count(*) from public.preguntas_examen where examen_id = '$id'"))
        assertEquals("3", sql("select total_preguntas from public.examenes where id = '$id'"))
        val lote = sql("select id from public.lotes_importacion_preguntas where examen_id = '$id' order by creado_en desc limit 1")
        assertEquals(1, auditCount("lote_importado", lote))
        assertEquals(1, auditCount("lote_creado", lote), "crear el lote también queda auditado (2026-10-20)")
        assertIs<AppError.Authorization>(Panel("Q1").content.importQuestions(id, listOf(question(id, 9))).failure("usuario importa"))
    }

    // ---------- 7. precios y packs ----------
    @Test
    fun j07PricesAndPacks() = journey("J07 prices") {
        val p = Panel("ADMIN")
        val id = examWithQuestions(p, "E2E J07 precio", 1)
        p.control.saveExamEconomy(id, ExamEconomyDraft(true, "120", "30", community)).ok("precio")
        assertEquals("120|t|30", sql("select precio_tickets || '|' || comprable_con_tickets || '|' || dias_acceso_compra from public.examenes where id = '$id'").replace("true", "t"))
        assertEquals(1, auditCount("examen_economia_modificada", id))
        p.control.saveExamEconomy(id, ExamEconomyDraft(true, "-5", "30", community)).failure("precio negativo")
        // Packs: el canónico es productos_comerciales (tipo pack_tickets, lo que vende la app). La tabla de Market
        // (paquetes_opositickets) no está en el repo ni la lee la app: PRODUCT_DECISIONS_PENDING §15.1, fuera de este recorrido.
        val code = "E2E_PACK_" + UUID.randomUUID().toString().take(8).uppercase()
        val pack = p.commercial.saveProduct(CommercialProduct(id = "", code = code, productType = "pack_tickets", name = "Pack E2E", priceCents = 199, ticketAmount = 15)).ok("pack")
        assertEquals("pack_tickets|15|199", sql("select tipo_producto || '|' || cantidad_tickets || '|' || precio_centimos from public.productos_comerciales where codigo = '$code'"))
        assertEquals(1, auditCount("producto_comercial_guardado", pack.id))
        p.commercial.saveProduct(CommercialProduct(id = "", code = code, productType = "pack_tickets", name = "Pack E2E duplicado", priceCents = 199, ticketAmount = 15)).failure("codigo duplicado")
        assertIs<AppError.Authorization>(Panel("Q1").commercial.saveProduct(CommercialProduct(id = "", code = code + "X", productType = "pack_tickets", name = "x", priceCents = 1, ticketAmount = 1)).failure("usuario fija precios"))
    }

    // ---------- 8 y 9. pedidos y reembolsos ----------
    private suspend fun orderInReview(user: String): String {
        val order = client.post("$appUrl/api/v1/app/payments/orders") {
            header("Authorization", "Bearer ${token(user)}"); header("Idempotency-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json); setBody("""{"productCode":"LOCAL_PACK_50","provider":"sandbox"}""")
        }.bodyAsText().let { Json.parseToJsonElement(it).jsonObject["order"]!!.jsonObject }
        val id = order["id"]!!.jsonPrimitive.content
        val raw = """{"eventId":"evt-${UUID.randomUUID()}","type":"payment.succeeded","orderId":"$id","transactionId":"tx-${UUID.randomUUID()}","amountCents":1,"currency":"EUR","productCode":"LOCAL_PACK_50"}"""
        val hook = sandboxHook(raw)
        assertTrue(hook.contains("EN_REVISION"), "importe incorrecto -> en revision: $hook")
        assertEquals("en_revision", sql("select estado from public.compras_usuario where id = '$id'"))
        return id
    }

    /** Aviso firmado del proveedor sandbox (HMAC de `ts.cuerpo` con el secreto LOCAL del stack aislado). */
    private suspend fun sandboxHook(raw: String): String {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ktorEnv.getValue("PAYMENTS_SANDBOX_SECRET").toByteArray(), "HmacSHA256")) }
        val sig = mac.doFinal("$ts.$raw".toByteArray()).joinToString("") { "%02x".format(it) }
        return client.post("$appUrl/api/v1/webhooks/payments/sandbox") {
            header("X-Sandbox-Timestamp", ts); header("X-Sandbox-Signature", sig); contentType(ContentType.Application.Json); setBody(raw)
        }.bodyAsText()
    }

    private fun balance(user: String) = sql("select saldo_tickets from public.carteras_usuario where usuario_id = '$user'").toInt()

    @Test
    fun j08OrdersAdmin() = journey("J08 orders") {
        val p = Panel("ADMIN")
        val id = orderInReview("Q4")
        assertTrue(p.control.ordersInReviewCount().ok("contador") >= 1)
        assertTrue(p.control.orders("en_revision", 0).ok("lista").items.any { it.id == id })
        assertEquals("en_revision", p.control.order(id).ok("detalle").state)
        p.control.addOrderNote(id, "Revisado por E2E: el proveedor confirma el cobro").ok("nota")
        assertEquals(1, auditCount("pedido_nota_interna", id))
        val before = balance(q4)
        val r = p.control.resolveOrder(id, OrderDecision.CREDIT, "Cobro confirmado E2E").ok("acreditar")
        assertEquals("CREDITED", r.outcome)
        assertEquals("pagada", sql("select estado from public.compras_usuario where id = '$id'"))
        assertEquals(before + 50, balance(q4), "50 tickets una sola vez")
        assertEquals(1, auditCount("pedido_resuelto", id))
        assertEquals("REPLAY", p.control.resolveOrder(id, OrderDecision.CREDIT, "Repeticion E2E").ok("repetir").outcome)
        assertEquals(before + 50, balance(q4), "la repeticion no acredita")
        assertIs<AppError.Authorization>(Panel("Q4").control.resolveOrder(id, OrderDecision.CREDIT, "yo mismo").failure("usuario resuelve"))
    }

    @Test
    fun j09RefundEffects() = journey("J09 refund") {
        val p = Panel("ADMIN")
        val id = orderInReview("Q4")
        val before = balance(q4)
        val r = p.control.resolveOrder(id, OrderDecision.REFUND, "Reembolso E2E").let {
            if (it is AppResult.Failure) { println("E2E J09 refund on a never-credited order: ${it.error}"); null } else it.ok("reembolso")
        }
        println("E2E J09 outcome=${r?.outcome} state=${sql("select estado from public.compras_usuario where id = '$id'")} refund_case=" +
            sql("select count(*) from public.refund_case where compra_id = '$id'") + " balance ${before}->${balance(q4)}")
        assertEquals(before, balance(q4), "un pedido nunca acreditado no mueve tickets al reembolsar")
        // Reembolso real: pedido acreditado -> el proveedor avisa del reembolso (payment.refunded) -> vuelve a revision -> el admin decide.
        val id2 = orderInReview("Q4")
        p.control.resolveOrder(id2, OrderDecision.CREDIT, "Acreditar antes de reembolsar E2E").ok("acreditar")
        val afterCredit = balance(q4)
        val tx = sql("select identificador_transaccion from public.compras_usuario where id = '$id2'")
        val refundHook = sandboxHook("""{"eventId":"evt-${UUID.randomUUID()}","type":"payment.refunded","orderId":"$id2","transactionId":"$tx","amountCents":449,"currency":"EUR","productCode":"LOCAL_PACK_50"}""")
        val stateAfterHook = sql("select estado from public.compras_usuario where id = '$id2'")
        println("E2E J09 refund webhook -> $refundHook; state=$stateAfterHook balance=${balance(q4)} refund_case=" + sql("select count(*) from public.refund_case where compra_id = '$id2'"))
        if (stateAfterHook == "en_revision") {
            val r2 = p.control.resolveOrder(id2, OrderDecision.REFUND, "Reembolso confirmado E2E").ok("reembolsar")
            println("E2E J09 admin refund -> $r2; state=" + sql("select estado from public.compras_usuario where id = '$id2'") + " balance $afterCredit->${balance(q4)} refund_case=" +
                sql("select coalesce(string_agg(estado || ':' || politica || ':' || ot_revertidos || '/' || ot_gastados, ','), '') from public.refund_case where compra_id = '$id2'") +
                " ledger=" + sql("select coalesce(string_agg(tipo || ':' || cantidad, ',' order by seq), '') from public.transacciones_cartera where compra_id = '$id2'"))
            assertTrue(r2.outcome.startsWith("REFUNDED"), r2.toString())
            assertEquals("reembolsada", sql("select estado from public.compras_usuario where id = '$id2'"))
            assertEquals(afterCredit - 50, balance(q4), "los 50 OT sin gastar vuelven")
            assertTrue(sql("select count(*) from public.auditoria_log where accion = 'pedido_resuelto' and registro_id::text = '$id2'").toInt() >= 2, "acreditar + reembolsar auditados")
        } else {
            // Observado: el reembolso del proveedor de un pedido con todos sus OT sin gastar se resuelve solo (sin revision ni refund_case).
            assertEquals("reembolsada", stateAfterHook)
            assertEquals(afterCredit - 50, balance(q4), "se revierten los 50 OT sin gastar")
            assertEquals("0", sql("select count(*) from public.refund_case where compra_id = '$id2'"))
            assertEquals("reembolsada", p.control.order(id2).ok("detalle en el panel").state, "el panel enseña el reembolso")
            assertEquals("REPLAY", p.control.resolveOrder(id2, OrderDecision.REFUND, "Reembolsar otra vez E2E").ok("reembolsar otra vez").outcome, "idempotente")
            assertEquals(afterCredit - 50, balance(q4), "sin doble reversion")
        }
    }

    // ---------- 10. accesos concedidos y retirados ----------
    @Test
    fun j10AccessGrantAndRevoke() = journey("J10 access") {
        val p = Panel("ADMIN")
        val examId = examWithQuestions(p, "E2E J10 acceso", 1)
        p.content.publishExam(examId).ok("publicar para conceder")
        val granted = p.operations.upsertExamAccess(ExamAccess("", "", "", "manual", true, userId = q1, examId = examId)).ok("conceder")
        assertEquals("1", sql("select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '$q1' and examen_id = '$examId' and (valido_hasta is null or valido_hasta > now())"))
        assertEquals(1, auditCount("acceso_examen_concedido", granted.id))
        p.operations.revokeExamAccess(granted.id).ok("retirar")
        assertEquals("0", sql("select count(*) from public.contenido_desbloqueado_usuario where usuario_id = '$q1' and examen_id = '$examId' and (valido_hasta is null or valido_hasta > now())"))
        assertEquals(1, auditCount("acceso_examen_retirado", granted.id))
        assertIs<AppError.Authorization>(Panel("Q1").operations.upsertExamAccess(ExamAccess("", "", "", "manual", true, userId = q1, examId = examId)).failure("autoconcederse"))
    }

    // ---------- 11. moderación del foro ----------
    @Test
    fun j11ForumModeration() = journey("J11 forum") {
        val p = Panel("ADMIN")
        // Canal creado desde el panel (también es una función de Control) y premoderación activa para que el post entre en la cola.
        val slug = "e2e-" + UUID.randomUUID().toString().take(8)
        val channel = p.control.createForumChannel(ForumChannelDraft("Canal E2E", slug, "Canal de prueba E2E", true, "95", specialty, false)).ok("crear canal")
        assertEquals(1, auditCount("foro_canal_creado", channel.id))
        val premod = sql("select valor_integer from public.configuracion_v1 where clave = 'foro_premoderacion_posts'")
        // Límite anti-spam de publicaciones por hora (lado Bot): las ejecuciones repetidas del E2E lo agotan; se sube y se restaura.
        val postsHora = sql("select valor_integer from public.configuracion_v1 where clave = 'foro_posts_hora'")
        sql("update public.configuracion_v1 set valor_integer = 1000 where clave = 'foro_posts_hora'")
        sql("update auth.users set email_confirmed_at = now() - interval '3 days', created_at = now() - interval '3 days' where id = '$q1'; update public.configuracion_v1 set valor_integer = 1 where clave = 'foro_premoderacion_posts'")
        try {
        val body = "Duda E2E de moderacion ${UUID.randomUUID().toString().take(8)} sobre el tema 3"
        val created = client.post("$appUrl/api/v1/app/oposired/publicaciones") {
            header("Authorization", "Bearer ${token("Q1")}"); header("Idempotency-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody("""{"categoriaId":"${channel.id}","titulo":"Duda E2E","cuerpo":"$body"}""")
        }.bodyAsText()
        val postId = sql("select id from public.publicaciones_oposired where contenido = '$body'").ifEmpty { fail("publicacion no creada: $created") }
        // Moderador real (rol `moderador`, cuenta activa): ve la cola y modera; la auditoría lo registra a ÉL como actor.
        val mod = Panel("MOD")
        assertTrue(mod.control.forumQueue(ForumContentType.POST, "pendiente_moderacion", null).ok("cola del moderador").items.any { it.id == postId }, "el post premoderado entra en la cola")
        mod.control.moderateForum(ForumContentType.POST, postId, ForumAction.HIDE, "Fuera de tema E2E", null).ok("ocultar (moderador)")
        assertEquals("5c000000-0000-4000-8000-00000000005c", sql("select usuario_id from public.auditoria_log where registro_id::text = '$postId' order by creado_en desc limit 1"), "actor = moderador")
        mod.control.forumRestrictions().ok("moderador lee restricciones")
        assertIs<AppError.Authorization>(mod.control.createForumChannel(ForumChannelDraft("Canal mod", "mod-" + slug, "x", true, "96", specialty, false)).failure("moderador crea canal"))
        assertIs<AppError.Authorization>(mod.control.addForumBlock("e2e-mod-block").failure("moderador bloquea patrones"))
        assertIs<AppError.Authorization>(Panel("ADMIN_BLK").control.forumQueue(ForumContentType.POST, "pendiente_moderacion", null).failure("admin bloqueado modera"))
        p.control.moderateForum(ForumContentType.POST, postId, ForumAction.RESTORE, "", null).ok("restaurar (admin)")
        p.control.moderateForum(ForumContentType.POST, postId, ForumAction.HIDE, "Fuera de tema E2E", null).ok("ocultar")
        println("E2E J11 post state=" + sql("select coalesce(estado, '') from public.publicaciones_oposired where id = '$postId'"))
        assertTrue(sql("select count(*) from public.auditoria_log where registro_id::text = '$postId'").toInt() >= 1, "auditoria de la moderacion")
        p.control.moderateForum(ForumContentType.POST, postId, ForumAction.HIDE, "", null).failure("ocultar sin motivo")
        val pattern = "e2e-spam-" + UUID.randomUUID().toString().take(6)
        p.control.addForumBlock(pattern).ok("bloqueo")
        assertTrue(p.control.forumBlockList().ok("lista bloqueos").contains(pattern))
        p.control.removeForumBlock(pattern).ok("quitar bloqueo")
        assertTrue(!p.control.forumBlockList().ok("lista bloqueos").contains(pattern))
        assertIs<AppError.Authorization>(Panel("Q1").control.moderateForum(ForumContentType.POST, postId, ForumAction.RESTORE, "", null).failure("usuario modera"))
        } finally {
            sql("update public.configuracion_v1 set valor_integer = ${premod.ifEmpty { "0" }} where clave = 'foro_premoderacion_posts'")
            if (postsHora.isNotEmpty()) sql("update public.configuracion_v1 set valor_integer = $postsHora where clave = 'foro_posts_hora'")
        }
    }

    // ---------- 12. auditoría ----------
    @Test
    fun j12AuditLog() = journey("J12 audit") {
        val p = Panel("ADMIN")
        val exam = p.content.upsertExam(newExam("E2E J12 auditoria")).ok("crear")
        val page = p.audit.fetchMovements(AuditFilter(action = "examen_creado"), null).ok("movimientos")
        val mine = page.items.firstOrNull { it.recordId == exam.id } ?: fail("el movimiento de ${exam.id} no aparece en la primera pagina")
        assertEquals(admin, mine.actorId)
        val detail = p.audit.fetchDetail(mine.id).ok("detalle")
        println("E2E J12 detail=${detail.toString().take(300)}")
        // Append-only: ni el admin ni nadie cambia auditoria_log por la Data API.
        assertEquals("1", sql("select count(*) from public.auditoria_log where id = '${mine.id}'"))
        assertIs<AppError.Authorization>(Panel("Q1").audit.fetchMovements(AuditFilter(), null).failure("usuario lee auditoria"))
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
