package com.oposicontrol.admin.core.common.config

import com.oposicontrol.admin.core.common.logging.Logger

/**
 * INV-NETWORK-COMPLETE: the only remote image URLs the clients may fetch outside the Ktor client are public objects of
 * the configured Supabase Storage host. https only, same authority as `SUPABASE_URL` (so `https://good@evil/...`,
 * a foreign host or another port are rejected), and a path under `/storage/v1/object/public/` (no signed, rest or
 * traversal paths). An unconfigured/non-https `supabaseUrl` allows nothing (deny by default). In non-dev builds the
 * resolver already refuses to start without an https `SUPABASE_URL` (loud config error, not a silent deny-all); the only
 * way to fetch over http is the explicit DEV flag `MEDIA_ALLOW_INSECURE_DEV` (see [AppConfig.allowInsecureDevMedia]),
 * and each http allowance is logged with its reason.
 */
object MediaUrlPolicy {
    private const val PUBLIC_OBJECT_PREFIX = "/storage/v1/object/public/"
    private const val SIGNED_OBJECT_PREFIX = "/storage/v1/object/sign/"

    /** Entry point for the platform actuals: no [AppConfig] (Koin not started) denies everything. */
    fun isAllowedRemoteMedia(url: String, config: AppConfig?, logger: Logger? = null): Boolean {
        if (config == null) return false
        val allowed = isAllowedRemoteMedia(url, config.supabaseUrl, config.allowInsecureDevMedia)
        if (!allowed && config.supabaseUrl.isBlank()) {
            logger?.warn("MediaUrlPolicy: remote media denied, SUPABASE_URL is not configured.")
        }
        if (allowed && url.trim().startsWith(HTTP, ignoreCase = true)) {
            logger?.warn("MediaUrlPolicy: http media allowed only because MEDIA_ALLOW_INSECURE_DEV=true (dev build).")
        }
        return allowed
    }

    fun isAllowedRemoteMedia(url: String, supabaseUrl: String, allowInsecureDevHttp: Boolean = false): Boolean =
        isStorageObject(url, supabaseUrl, allowInsecureDevHttp, listOf(PUBLIC_OBJECT_PREFIX))

    /**
     * Abrir un archivo de Storage FUERA de la app (navegador o reproductor del sistema), nunca descargarlo dentro: lo
     * usa el «Abrir» de los recursos de pregunta, cuyo bucket es privado y llega con URL FIRMADA de una hora. Mismas
     * reglas que [isAllowedRemoteMedia] (https, mismo host que `SUPABASE_URL`, sin traversal) y además la ruta firmada.
     * Cualquier otra URL guardada en la base no se abre.
     */
    fun isOpenableStorageObject(url: String, config: AppConfig?): Boolean =
        config != null && isOpenableStorageObject(url, config.supabaseUrl, config.allowInsecureDevMedia)

    fun isOpenableStorageObject(url: String, supabaseUrl: String, allowInsecureDevHttp: Boolean = false): Boolean =
        isStorageObject(url, supabaseUrl, allowInsecureDevHttp, listOf(PUBLIC_OBJECT_PREFIX, SIGNED_OBJECT_PREFIX))

    private fun isStorageObject(url: String, supabaseUrl: String, allowInsecureDevHttp: Boolean, prefixes: List<String>): Boolean {
        val allowedAuthority = authorityOf(supabaseUrl.trim(), allowInsecureDevHttp)?.second?.takeIf { it.isNotEmpty() } ?: return false
        val candidate = url.trim()
        if (candidate.any { it.isWhitespace() || it.code < 32 || it.code == 127 || it == '\\' }) return false
        val (scheme, authority) = authorityOf(candidate, allowInsecureDevHttp) ?: return false
        if (authority != allowedAuthority) return false
        val rest = candidate.substring(scheme.length + authority.length)
        val path = rest.substringBefore('?').substringBefore('#')
        val prefix = prefixes.firstOrNull { path.startsWith(it) } ?: return false
        if (path.length == prefix.length) return false
        val lowered = path.lowercase()
        return !lowered.contains("%2e") && !lowered.contains("%2f") && !lowered.contains("%5c") &&
            path.split('/').none { it == ".." || it == "." }
    }

    private const val HTTPS = "https://"
    private const val HTTP = "http://"

    /** (scheme prefix, lower-cased authority) of an `https://` URL (`http://` only with the dev flag); null on userinfo. */
    private fun authorityOf(url: String, allowHttp: Boolean): Pair<String, String>? {
        val scheme = when {
            url.startsWith(HTTPS, ignoreCase = true) -> HTTPS
            allowHttp && url.startsWith(HTTP, ignoreCase = true) -> HTTP
            else -> return null
        }
        val authority = url.substring(scheme.length).takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.contains('@')) return null
        return scheme to authority.lowercase()
    }
}
