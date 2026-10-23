package com.agronomy.phytomonitor.sync

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class SyncHttpClientTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun logoutDuringUploadStopsTheRemainingBody() {
        val file = temporary.newFile().apply { writeBytes(ByteArray(10 * 1024 * 1024) { 17 }) }
        val active = AtomicBoolean(true)
        val received = AtomicLong()
        val done = CountDownLatch(1)
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        source.createContext("/photos/") { exchange ->
            try {
                val buffer = ByteArray(8192)
                val first = exchange.requestBody.read(buffer)
                if (first > 0) received.addAndGet(first.toLong())
                active.set(false) // Logout after the first body block.
                runCatching {
                    while (true) {
                        val count = exchange.requestBody.read(buffer)
                        if (count < 0) break
                        received.addAndGet(count.toLong())
                    }
                }
                exchange.sendResponseHeaders(200, -1)
            } finally {
                exchange.close()
                done.countDown()
            }
        }
        source.start()
        try {
            val failure = runCatching {
                SyncHttpClient("http://127.0.0.1:${source.address.port}", "fixture", active::get)
                    .upload(java.util.UUID.randomUUID().toString(), file, "a".repeat(64))
            }.exceptionOrNull()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(failure is java.util.concurrent.CancellationException)
            assertTrue("received ${received.get()} of ${file.length()}", received.get() < file.length() / 2)
        } finally { source.stop(0) }
    }

    @Test fun pullPageFitsBoundedSeriesResponses() {
        val query = AtomicReference<String>()
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        source.createContext("/sync/inspections") { exchange ->
            query.set(exchange.requestURI.query)
            val bytes = "{\"inspections\":[],\"next_after\":7,\"has_more\":false}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        source.start()
        try {
            assertFalse(SyncHttpClient("http://127.0.0.1:${source.address.port}", "fixture").pull(7).getBoolean("has_more"))
            assertEquals("after=7&limit=10", query.get())
        } finally { source.stop(0) }
    }

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
