package com.oposicontrol.admin.core.common.config

import com.oposicontrol.admin.core.common.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaUrlPolicyTest {
    private val supabase = "https://proj.supabase.co"
    private val good = "https://proj.supabase.co/storage/v1/object/public/news/a.png"

    private fun allowed(url: String, config: String = supabase) = MediaUrlPolicy.isAllowedRemoteMedia(url, config)

    @Test fun publicObjectOfTheConfiguredHostIsAllowed() {
        assertTrue(allowed(good))
        assertTrue(allowed("HTTPS://PROJ.supabase.co/storage/v1/object/public/news/a.png?t=1"))
        assertTrue(allowed(good, "https://proj.supabase.co/"))
    }

    @Test fun rejectsEveryNonPublicStorageShape() {
        assertFalse(allowed("http://proj.supabase.co/storage/v1/object/public/news/a.png"))
        assertFalse(allowed("https://evil.example/storage/v1/object/public/news/a.png"))
        assertFalse(allowed("https://proj.supabase.co/storage/v1/object/sign/news/a.png?token=x"))
        assertFalse(allowed("https://proj.supabase.co/rest/v1/news"))
        assertFalse(allowed("https://proj.supabase.co/storage/v1/object/public/"))
    }

    @Test fun rejectsHostConfusionAndTraversal() {
        assertFalse(allowed("https://proj.supabase.co@evil.example/storage/v1/object/public/a.png"))
        assertFalse(allowed("https://good@evil.example/storage/v1/object/public/a.png"))
        assertFalse(allowed("https://proj.supabase.co.evil.example/storage/v1/object/public/a.png"))
        assertFalse(allowed("https://proj.supabase.co:8443/storage/v1/object/public/a.png"))
        assertFalse(allowed("https://proj.supabase.co\\@evil.example/storage/v1/object/public/a.png"))
        assertFalse(allowed("https://proj.supabase.co/storage/v1/object/public/../../sign/a.png"))
        assertFalse(allowed("https://proj.supabase.co/storage/v1/object/public/%2e%2e/a.png"))
        assertFalse(allowed("file:///etc/passwd"))
        assertFalse(allowed("content://media/1"))
    }

    @Test fun deniesEverythingWithoutAnHttpsConfiguredHost() {
        assertFalse(allowed(good, ""))
        assertFalse(allowed(good, "http://proj.supabase.co"))
        assertFalse(allowed("http://localhost/storage/v1/object/public/a.png", "http://localhost"))
    }

    // --- dev flag + config entry point (INV-NETWORK-COMPLETE, round 4) ---

    private class Recorder : Logger {
        val warnings = mutableListOf<String>()
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) { warnings += message }
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    private fun config(supabaseUrl: String, env: Environment = Environment.DEV, allowInsecureDevMedia: Boolean = false) = AppConfig(
        environment = env, baseUrl = "https://api.example.com", backendToken = "", supabaseUrl = supabaseUrl, supabaseAnonKey = "k",
        supabaseBucketExamResources = "b", featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "n", appVersion = "1", allowInsecureDevMedia = allowInsecureDevMedia,
    )

    @Test fun httpIsDeniedWithoutTheExplicitDevFlagEvenInDev() {
        val local = "http://10.0.2.2:54321"
        val url = "$local/storage/v1/object/public/news/a.png"
        assertFalse(MediaUrlPolicy.isAllowedRemoteMedia(url, config(local)))
        assertFalse(MediaUrlPolicy.isAllowedRemoteMedia(url, local, allowInsecureDevHttp = false))
    }

    @Test fun explicitDevFlagAllowsHttpToTheConfiguredHostOnlyAndLogsTheReason() {
        val local = "http://10.0.2.2:54321"
        val log = Recorder()
        val cfg = config(local, allowInsecureDevMedia = true)
        assertTrue(MediaUrlPolicy.isAllowedRemoteMedia("$local/storage/v1/object/public/news/a.png", cfg, log))
        assertEquals(1, log.warnings.size)
        assertTrue(log.warnings.single().contains("MEDIA_ALLOW_INSECURE_DEV"))
        assertFalse(log.warnings.single().contains("10.0.2.2"), "the log must not echo the URL")
        assertFalse(MediaUrlPolicy.isAllowedRemoteMedia("http://evil.example/storage/v1/object/public/news/a.png", cfg, log))
        assertFalse(MediaUrlPolicy.isAllowedRemoteMedia("http://10.0.2.2:54321@evil.example/storage/v1/object/public/a.png", cfg, log))
        assertFalse(MediaUrlPolicy.isAllowedRemoteMedia("$local/storage/v1/object/sign/news/a.png", cfg, log))
    }

    @Test fun unconfiguredSupabaseDeniesAndLogsInsteadOfFailingSilently() {
        val log = Recorder()
        assertFalse(MediaUrlPolicy.isAllowedRemoteMedia(good, config(""), log))
        assertTrue(log.warnings.single().contains("SUPABASE_URL"))
        assertFalse(MediaUrlPolicy.isAllowedRemoteMedia(good, null, log))
    }

    @Test fun httpsConfigStillAllowsTheSameHostThroughTheConfigEntryPoint() {
        assertTrue(MediaUrlPolicy.isAllowedRemoteMedia(good, config(supabase, Environment.PROD)))
    }

    private fun openable(url: String) = MediaUrlPolicy.isOpenableStorageObject(url, supabase)

    /** «Abrir» un recurso de pregunta: la URL firmada del bucket privado se puede ABRIR fuera, nunca cargar dentro. */
    @Test fun signedObjectOfTheConfiguredHostCanBeOpenedButNotFetchedInApp() {
        val signed = "https://proj.supabase.co/storage/v1/object/sign/recursos-pregunta/preguntas/q/r.jpg?token=abc"
        assertTrue(openable(signed))
        assertTrue(openable(good))
        assertFalse(allowed(signed), "la politica de carga dentro de la app no cambia")
    }

    @Test fun openingRejectsEverythingOutsideOurStorage() {
        assertFalse(openable("https://evil.example/storage/v1/object/sign/a.jpg?token=x"))
        assertFalse(openable("https://proj.supabase.co@evil.example/storage/v1/object/sign/a.jpg"))
        assertFalse(openable("http://proj.supabase.co/storage/v1/object/sign/a.jpg"))
        assertFalse(openable("https://proj.supabase.co/storage/v1/object/sign/"))
        assertFalse(openable("https://proj.supabase.co/storage/v1/object/sign/../../rest/v1/x"))
        assertFalse(openable("https://proj.supabase.co/rest/v1/preguntas"))
        assertFalse(openable("file:///C:/Windows/notepad.exe"))
        assertFalse(MediaUrlPolicy.isOpenableStorageObject("https://proj.supabase.co/storage/v1/object/sign/a.jpg", null as AppConfig?))
    }
}
