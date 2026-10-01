package com.oposicontrol.admin.core.common.config

import kotlin.test.Test
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
}
