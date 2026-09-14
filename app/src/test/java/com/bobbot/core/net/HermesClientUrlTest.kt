package com.bobbot.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HermesClientUrlTest {
    @Test fun bareHostsGetTheDashboardPort() {
        assertEquals("http://192.168.2.17:9119", HermesClient.normalizeBaseUrl("192.168.2.17"))
        assertEquals("http://192.168.2.17:9119", HermesClient.normalizeBaseUrl("http://192.168.2.17"))
        assertEquals("http://box.local:9119", HermesClient.normalizeBaseUrl(" box.local/ "))
    }

    @Test fun explicitPortsAreKept() {
        assertEquals("http://192.168.2.17:8080", HermesClient.normalizeBaseUrl("192.168.2.17:8080"))
        assertEquals("http://192.168.2.17", HermesClient.normalizeBaseUrl("http://192.168.2.17:80"))
    }

    @Test fun httpsMeansATunnelOn443NotThePlainPort() {
        assertEquals("https://hermes.example.com", HermesClient.normalizeBaseUrl("https://hermes.example.com"))
        assertEquals("https://hermes.example.com", HermesClient.normalizeBaseUrl("https://hermes.example.com:443"))
        assertEquals("https://hermes.example.com:8443", HermesClient.normalizeBaseUrl("https://hermes.example.com:8443"))
    }

    @Test fun garbageIsRejected() {
        assertNull(HermesClient.normalizeBaseUrl(""))
        assertNull(HermesClient.normalizeBaseUrl("http://"))
    }
}
