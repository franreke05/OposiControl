package com.oposicontrol.backend

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

// MASTER-QUALITY-001: vive en el source set de test; ningun codigo de produccion lo usa.
/**
 * Almacen en memoria para tests (y solo para tests: no sobrevive a un reinicio
 * ni se comparte entre instancias). Misma semantica que las RPC.
 */
class InMemoryIdempotencyStore(
    private val clock: () -> Instant = Instant::now,
) : IdempotencyStore {
    private data class Row(
        val id: String,
        val actorId: String,
        val scope: String,
        val key: String,
        val fingerprint: String,
        var completed: Boolean,
        var response: StoredIdempotentResponse?,
        var lockedUntil: Instant?,
        val expiresAt: Instant,
        var token: String = UUID.randomUUID().toString(),
    )

    private val mutex = Mutex()
    private val rows = LinkedHashMap<Triple<String, String, String>, Row>()
    private var sequence = 0L

    override suspend fun begin(request: IdempotencyBeginRequest): IdempotencyBeginResult = mutex.withLock {
        val now = clock()
        val identity = Triple(request.actorId, request.scope, request.key.value)
        rows[identity]?.takeIf { !it.expiresAt.isAfter(now) }?.let { rows.remove(identity) }
        rows.entries.removeIf { !it.value.expiresAt.isAfter(now) }
        val existing = rows[identity]
        when {
            existing == null -> {
                val row = Row(
                    id = "mem-${++sequence}",
                    actorId = request.actorId,
                    scope = request.scope,
                    key = request.key.value,
                    fingerprint = request.fingerprint,
                    completed = false,
                    response = null,
                    lockedUntil = now.plus(request.lease),
                    expiresAt = now.plus(request.ttl),
                )
                rows[identity] = row
                IdempotencyBeginResult.Claimed(IdempotencyClaim(row.id, row.token), takeover = false)
            }
            existing.fingerprint != request.fingerprint -> IdempotencyBeginResult.Mismatch
            existing.completed -> IdempotencyBeginResult.Replay(checkNotNull(existing.response))
            existing.lockedUntil?.isAfter(now) == true -> IdempotencyBeginResult.InProgress
            else -> {
                existing.lockedUntil = now.plus(request.lease)
                existing.token = UUID.randomUUID().toString()
                IdempotencyBeginResult.Claimed(IdempotencyClaim(existing.id, existing.token), takeover = true)
            }
        }
    }

    override suspend fun complete(claim: IdempotencyClaim, response: StoredIdempotentResponse) {
        require(response.status in 200..299) { "solo se guardan respuestas 2xx" }
        mutex.withLock {
            rows.values.firstOrNull { it.id == claim.id && it.token == claim.token && !it.completed }?.let {
                it.completed = true
                it.response = response
                it.lockedUntil = null
            }
        }
    }

    override suspend fun release(claim: IdempotencyClaim) {
        mutex.withLock {
            rows.entries.removeIf { it.value.id == claim.id && it.value.token == claim.token && !it.value.completed }
        }
    }

    internal suspend fun size(): Int = mutex.withLock { rows.size }
}
