package com.oposicontrol.admin.core.common.config

/**
 * INV-NETWORK-COMPLETE: the only remote image URLs the clients may fetch outside the Ktor client are public objects of
 * the configured Supabase Storage host. https only, same authority as `SUPABASE_URL` (so `https://good@evil/...`,
 * a foreign host or another port are rejected), and a path under `/storage/v1/object/public/` (no signed, rest or
 * traversal paths). An unconfigured/non-https `supabaseUrl` allows nothing (deny by default).
 */
object MediaUrlPolicy {
    private const val PUBLIC_OBJECT_PREFIX = "/storage/v1/object/public/"

    fun isAllowedRemoteMedia(url: String, supabaseUrl: String): Boolean {
        val allowedAuthority = httpsAuthority(supabaseUrl.trim())?.takeIf { it.isNotEmpty() } ?: return false
        val candidate = url.trim()
        if (candidate.any { it.isWhitespace() || it.code < 32 || it.code == 127 || it == '\\' }) return false
        val authority = httpsAuthority(candidate) ?: return false
        if (authority != allowedAuthority) return false
        val rest = candidate.substring(HTTPS.length + authority.length)
        val path = rest.substringBefore('?').substringBefore('#')
        if (!path.startsWith(PUBLIC_OBJECT_PREFIX) || path.length == PUBLIC_OBJECT_PREFIX.length) return false
        val lowered = path.lowercase()
        return !lowered.contains("%2e") && !lowered.contains("%2f") && !lowered.contains("%5c") &&
            path.split('/').none { it == ".." || it == "." }
    }

    private const val HTTPS = "https://"

    /** Lower-cased authority of an `https://` URL, or null when it is not https or carries userinfo. */
    private fun httpsAuthority(url: String): String? {
        if (!url.startsWith(HTTPS, ignoreCase = true)) return null
        val afterScheme = url.substring(HTTPS.length)
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.contains('@')) return null
        return authority.lowercase()
    }
}
