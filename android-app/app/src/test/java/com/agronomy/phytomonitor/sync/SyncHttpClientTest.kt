package com.agronomy.phytomonitor.sync

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class SyncHttpClientTest {
    @Test fun endedSessionDoesNotOpenAnotherHttpRequest() {
        val received = AtomicInteger()
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        source.createContext("/") { exchange -> received.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        source.start()
        try {
            val failure = runCatching {
                SyncHttpClient("http://127.0.0.1:${source.address.port}", "fixture", { false }).profile()
            }.exceptionOrNull()
            assertTrue(failure is java.util.concurrent.CancellationException)
            assertEquals(0, received.get())
        } finally { source.stop(0) }
    }

    @Test fun redirectDoesNotReceiveBearerToken() {
        val received = AtomicInteger()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/stolen") { exchange -> received.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        source.createContext("/me") { exchange ->
            assertEquals("Bearer secret-test-only", exchange.requestHeaders.getFirst("Authorization"))
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/stolen")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        target.start()
        source.start()
        try {
            val failure = runCatching { SyncHttpClient("http://127.0.0.1:${source.address.port}", "secret-test-only").profile() }.exceptionOrNull()
            assertTrue(failure is SyncHttpException)
            assertEquals(302, (failure as SyncHttpException).status)
            assertEquals(0, received.get())
        } finally { source.stop(0); target.stop(0) }
    }

    @Test fun realTransportEncodesRussianCredentialsAndPush() {
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val paths = mutableListOf<String>()
        source.createContext("/") { exchange ->
            paths += exchange.requestURI.path
            val body = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val response = if (exchange.requestURI.path == "/auth/token") {
                assertEquals("борис", body.getString("username"))
                assertEquals("пароль", body.getString("password"))
                "{\"access_token\":\"test-token\"}"
            } else {
                assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
                assertEquals("id", body.getJSONArray("inspections").getJSONObject(0).getString("id"))
                "{\"results\":[{\"status\":\"accepted\"}]}"
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        source.start()
        try {
            val origin = "http://127.0.0.1:${source.address.port}"
            val token = SyncHttpClient(origin).login("борис", "пароль").getString("access_token")
            assertEquals("accepted", SyncHttpClient(origin, token).push(JSONObject().put("id", "id")).getString("status"))
            assertEquals(listOf("/auth/token", "/sync/inspections"), paths)
        } finally { source.stop(0) }
    }
}
