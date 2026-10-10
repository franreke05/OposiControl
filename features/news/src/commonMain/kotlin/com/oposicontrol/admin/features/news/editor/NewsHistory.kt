package com.oposicontrol.admin.features.news.editor

/** Un paso del historial: el documento ANTES de la edicion y el grupo que la produjo. */
data class NewsHistoryEntry(val group: NewsEditGroup, val document: NewsDocument)

/**
 * Historial de deshacer/rehacer, inmutable y acotado (DEBT-074).
 *
 * Cada edicion registra el documento previo bajo su [NewsEditGroup]. Si el grupo coincide con el de la ultima
 * entrada, no se apila otra: el «antes» sigue siendo el de la primera edicion del grupo, asi que un arrastre o una
 * frase escrita se deshacen de un golpe. Cualquier edicion nueva vacia la pila de rehacer, como en todo editor.
 */
data class NewsHistory(
    val past: List<NewsHistoryEntry> = emptyList(),
    val future: List<NewsHistoryEntry> = emptyList(),
    val limit: Int = NEWS_HISTORY_LIMIT,
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Registra una edicion que transformo [before] en el documento actual. */
    fun record(before: NewsDocument, group: NewsEditGroup): NewsHistory {
        val last = past.lastOrNull()
        if (last != null && last.group == group) return if (future.isEmpty()) this else copy(future = emptyList())
        return copy(past = (past + NewsHistoryEntry(group, before)).takeLast(limit), future = emptyList())
    }

    /** Deshace: devuelve el historial avanzado y el documento a restaurar, o `null` si no hay nada que deshacer. */
    fun undo(current: NewsDocument): Pair<NewsHistory, NewsDocument>? {
        val entry = past.lastOrNull() ?: return null
        val next = copy(past = past.dropLast(1), future = future + NewsHistoryEntry(entry.group, current))
        return next to entry.document
    }

    fun redo(current: NewsDocument): Pair<NewsHistory, NewsDocument>? {
        val entry = future.lastOrNull() ?: return null
        val next = copy(past = past + NewsHistoryEntry(entry.group, current), future = future.dropLast(1))
        return next to entry.document
    }

    /** Cierra el grupo abierto: la siguiente edicion del mismo tipo empezara un paso nuevo. */
    fun closeGroup(): NewsHistory = if (past.isEmpty()) this else copy(past = past.dropLast(1) + past.last().copy(group = CLOSED))

    private companion object {
        val CLOSED = NewsEditGroup("__closed__")
    }
}

/** Cien pasos: mas de lo que nadie deshace de una sentada y menos de lo que pesa un documento en memoria. */
const val NEWS_HISTORY_LIMIT: Int = 100
