package com.oposicontrol.backend

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SLICE 0 · paginacion canonica (`specs.pagination`). */
class CanonicalPaginationTest {

    private val scope = CursorScope(sort = listOf("creado_en:desc", "id:desc"), filters = mapOf("estado" to "publicado"))

    private fun request(cursor: String? = null, limit: String? = null, s: CursorScope = scope) =
        CursorPageRequest.of(cursor, limit, CursorPageRequest.APP_DEFAULT_LIMIT, CursorPageRequest.APP_MAX_LIMIT, s)

    // --- limites ---

    @Test
    fun missingLimitIsTheDefaultAndOverMaxIsClamped() {
        assertEquals(20, request().limit)
        assertEquals(21, request().fetchLimit)
        assertEquals(50, request(limit = "500").limit)
        assertEquals(7, request(limit = " 7 ").limit)
        assertEquals(100, CursorPageRequest.of(null, "999", CursorPageRequest.ADMIN_DEFAULT_LIMIT, CursorPageRequest.ADMIN_MAX_LIMIT, scope).limit)
    }

    @Test
    fun nonIntegerOrBelowOneLimitIs400ValidationOutOfRange() {
        listOf("0", "-3", "abc", "1.5").forEach { raw ->
            val error = assertFailsWith<BackendDomainException>(raw) { request(limit = raw) }
            assertEquals("VALIDATION", error.errorCode)
            assertEquals(400, error.status.value)
            assertEquals(listOf(ErrorIssue("limit", "OUT_OF_RANGE")), error.details?.issues)
        }
    }

    // --- cursor ---

    @Test
    fun aCursorRoundTripsItsKeysAndIsOpaqueBase64Url() {
        val keys = listOf<JsonElement>(JsonPrimitive("2026-09-28T10:00:00Z"), JsonPrimitive("b5a1c9d2-0000-4000-8000-000000000001"))
        val cursor = PageCursorCodec.encode(keys, scope.fingerprint())

        assertTrue(cursor.matches(Regex("^[A-Za-z0-9_-]+$")), cursor)
        val decoded = String(Base64.getUrlDecoder().decode(cursor))
        val payload = Json.parseToJsonElement(decoded).jsonObject
        assertEquals(setOf("v", "k", "f"), payload.keys)
        assertEquals(16, scope.fingerprint().length)
        assertEquals(keys, request(cursor = cursor).after)
    }

    @Test
    fun undecodableWrongVersionOrOtherFiltersCursorIs400InvalidCursor() {
        val good = PageCursorCodec.encode(listOf(JsonPrimitive(1)), scope.fingerprint())
        val wrongVersion = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"v":2,"k":[1],"f":"${scope.fingerprint()}"}""".toByteArray())
        val otherFilters = CursorScope(scope.sort, mapOf("estado" to "borrador"))
        val otherSort = CursorScope(listOf("id:asc"), scope.filters)
        val emptyKeys = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"v":1,"k":[],"f":"${scope.fingerprint()}"}""".toByteArray())

        listOf(
            { request(cursor = "%%%not-base64%%%") },
            { request(cursor = Base64.getUrlEncoder().encodeToString("no es json".toByteArray())) },
            { request(cursor = wrongVersion) },
            { request(cursor = emptyKeys) },
            { request(cursor = "A".repeat(5000)) },
            { request(cursor = good, s = otherFilters) },
            { request(cursor = good, s = otherSort) },
        ).forEachIndexed { index, attempt ->
            val error = assertFailsWith<BackendDomainException>("caso $index") { attempt() }
            assertEquals("INVALID_CURSOR", error.errorCode, "caso $index")
            assertEquals(400, error.status.value)
        }
    }

    @Test
    fun aNullFilterIsTheSameListAsAnAbsentOne() {
        assertEquals(
            CursorScope(listOf("id"), mapOf("a" to "1")).fingerprint(),
            CursorScope(listOf("id"), mapOf("a" to "1", "b" to null)).fingerprint(),
        )
    }

    // --- pagina ---

    @Test
    fun fetchingLimitPlusOneDecidesNextCursorAndKeysetWalksWithoutDuplicates() {
        val rows = (1..45).map { "row-%02d".format(it) }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = request(cursor = cursor)
            val after = page.after?.single()?.let { (it as JsonPrimitive).content }
            val fetched = rows.filter { after == null || it > after }.take(page.fetchLimit)
            val result = page.page(fetched) { listOf(JsonPrimitive(it)) }
            seen += result.items
            cursor = result.nextCursor
            pages++
        } while (cursor != null)

        assertEquals(rows, seen)
        assertEquals(3, pages)
    }

    @Test
    fun theLastPageHasNoNextCursor() {
        val page = request(limit = "5").page(listOf("a", "b")) { listOf(JsonPrimitive(it)) }
        assertEquals(listOf("a", "b"), page.items)
        assertNull(page.nextCursor)
        val exact = request(limit = "2").page(listOf("a", "b")) { listOf(JsonPrimitive(it)) }
        assertNull(exact.nextCursor, "exactamente `limit` filas no es senal de mas")
        val more = request(limit = "2").page(listOf("a", "b", "c")) { listOf(JsonPrimitive(it)) }
        assertEquals(listOf("a", "b"), more.items)
        assertNotNull(more.nextCursor)
    }

    // --- offset ---

    @Test
    fun offsetKeepsAdminLeniencyAndAcceptsALowerPerContractMax() {
        assertEquals(50, AdminPageRequest.of(null, null).limit)
        assertEquals(200, AdminPageRequest.of(10_000, 0).limit)
        assertEquals(0, AdminPageRequest.of(10, -5).offset)
        assertEquals(20, AdminPageRequest.of(10_000, 0, maxLimit = 20).limit)
        assertEquals(20, AdminPageRequest.of(null, 0, maxLimit = 20).limit)
        assertEquals(200, AdminPageRequest.of(10_000, 0, maxLimit = 5000).limit, "nunca por encima del tope duro")
    }

    @Test
    fun offsetPageAndCursorPageSerializeTheFrozenShape() {
        val json = Json { explicitNulls = false }
        val offset = json.parseToJsonElement(
            json.encodeToString(OffsetPage.serializer(String.serializer()), OffsetPage(listOf("a"), total = null, limit = 50, offset = 0)),
        ).jsonObject
        assertEquals(setOf("items", "limit", "offset"), offset.keys, "total null se omite: nunca un 0 falso")
        val cursor = json.parseToJsonElement(
            json.encodeToString(CursorPage.serializer(String.serializer()), CursorPage(listOf("a"), nextCursor = "abc")),
        ).jsonObject
        assertEquals(setOf("items", "nextCursor"), cursor.keys)
    }

    @Test
    fun adminUsersPageKeepsDataAsADeprecatedDuplicateOfItems() {
        val page = AdminUsersPage(data = emptyList(), total = 3)
        val body = Json { encodeDefaults = true }.let { it.parseToJsonElement(it.encodeToString(AdminUsersPage.serializer(), page)).jsonObject }
        assertTrue("items" in body.keys, body.toString())
        assertEquals(body["data"], body["items"])
    }
}

