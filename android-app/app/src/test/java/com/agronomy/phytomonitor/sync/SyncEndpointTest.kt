package com.agronomy.phytomonitor.sync

import org.junit.Assert.*
import org.junit.Test

class SyncEndpointTest {
    @Test fun serverTimestampsAcceptExplicitOffsetsAndMicroseconds() {
        assertEquals(SyncPayload.parseTime("2026-10-19T19:42:36.222Z"), SyncPayload.parseTime("2026-10-19T19:42:36.222000+00:00"))
        assertEquals(SyncPayload.parseTime("2026-10-19T19:42:36.222Z"), SyncPayload.parseTime("2026-10-19T22:42:36.222000+03:00"))
    }
    @Test fun normalizesOriginWithoutChangingAuthority() {
        assertEquals("https://example.com", SyncEndpoint.normalize("HTTPS://EXAMPLE.COM:443/"))
        assertEquals("http://127.0.0.1:8028", SyncEndpoint.normalize("http://127.0.0.1:8028"))
        assertEquals("http://192.168.1.2", SyncEndpoint.normalize("http://192.168.1.2"))
    }
    @Test fun rejectsCredentialPathsRedirectTargetsAndPublicCleartext() {
        listOf("http://example.com", "http://192.168.1.2.evil.test", "http://2130706433", "http://127.1",
            "https://user:pass@example.com", "https://example.com/api", "https://example.com?x=1",
            "file:///tmp/x", "https://example.com#x", "http://169.254.169.254", "http://192.168.01.2").forEach {
            assertTrue(it, runCatching { SyncEndpoint.normalize(it) }.isFailure)
        }
    }
}
