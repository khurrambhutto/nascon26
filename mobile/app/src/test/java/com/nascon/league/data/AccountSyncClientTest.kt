package com.nascon.league.data

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class AccountSyncClientTest {
    @Test fun organizerDeliveryKeepsItsIdentityAfterViewerLoginAndLogout() = runBlocking {
        val seen = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/offline-sync") { exchange ->
            seen.add(exchange.requestHeaders.getFirst("Authorization"))
            val bytes = """{"device_id":"phone","results":[{"action_id":"saved-goal","status":"applied"}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        var activeToken: String? = "organizer-jwt"
        try {
            val shared = buildHttpClient { activeToken }
            val savedClient = accountSyncClient("http://127.0.0.1:${server.address.port}/", "organizer-jwt", shared)
            val batch = SyncRequest("phone", listOf(OfflineAction("saved-goal", "score_event",
                "2026-10-18T00:00:00Z", 0, buildJsonObject {})))
            activeToken = null
            assertEquals("applied", savedClient.sync(batch).results.single().status)
            activeToken = "viewer-jwt"
            assertEquals("applied", savedClient.sync(batch).results.single().status)
            assertEquals(listOf("Bearer organizer-jwt", "Bearer organizer-jwt"), seen)
        } finally { server.stop(0) }
    }

    @Test fun expiredOriginalCredentialDoesNotFallBackToViewer() = runBlocking {
        var seen: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/offline-sync") { exchange ->
            seen = exchange.requestHeaders.getFirst("Authorization")
            val bytes = """{"error":{"code":"UNAUTHENTICATED"}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val savedClient = accountSyncClient("http://127.0.0.1:${server.address.port}/", "expired-organizer", buildHttpClient { "viewer-jwt" })
            try {
                apiCall { savedClient.sync(SyncRequest("phone", emptyList())) }
                fail("An expired credential must fail")
            } catch (e: ApiException) { assertEquals(401, e.status) }
            assertEquals("Bearer expired-organizer", seen)
        } finally { server.stop(0) }
    }

    @Test fun queuesCannotCrossServerInstallationsOrConfuseSimilarPrefixes() {
        assertTrue(queueBelongsToServer("http://lan:8080/|u-organizer", "http://lan:8080/"))
        assertFalse(queueBelongsToServer("http://lan:8080/other|u-organizer", "http://lan:8080/"))
        assertFalse(queueBelongsToServer("http://other:8080/|u-organizer", "http://lan:8080/"))
        assertFalse(queueBelongsToServer("http://lan:8080/|", "http://lan:8080/"))
    }
}
