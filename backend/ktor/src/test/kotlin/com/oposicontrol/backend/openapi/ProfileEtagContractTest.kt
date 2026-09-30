package com.oposicontrol.backend.openapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MASTER5-OPENAPI-001: the profile version travels as a declared `ETag` response header on D1-11/12/13. */
class ProfileEtagContractTest {
    private val docs = OpenApiDocs.locate()
    private val ops = docs.operations().associateBy { it.contractId }

    @Suppress("UNCHECKED_CAST")
    private fun okHeaders(id: String): Map<String, Any?> {
        val op = ops.getValue(id)
        val ok = (op.op["responses"] as Map<String, Any?>)["200"] as Map<String, Any?>
        return ok["headers"] as? Map<String, Any?> ?: emptyMap()
    }

    @Test
    fun meOnboardingAndProfilePatchDeclareTheEtagHeader() {
        for (id in listOf("D1-11", "D1-12", "D1-13")) {
            val etag = okHeaders(id)["ETag"]
            assertTrue(etag != null, "$id must declare the ETag response header")
            @Suppress("UNCHECKED_CAST")
            assertEquals("#/components/headers/ProfileETag", (etag as Map<String, Any?>)["\$ref"], id)
        }
    }

    @Test
    fun otherProfileReadsDoNotClaimTheProfileEtag() {
        // D1-16 (legal documents) has its own content ETag, not the profile version.
        assertTrue("ETag" !in okHeaders("D1-17"))
    }
}
