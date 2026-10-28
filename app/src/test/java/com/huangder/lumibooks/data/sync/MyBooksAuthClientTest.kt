package com.huangder.lumibooks.data.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MyBooksAuthClientTest {
    @Test fun validatesAndNormalizesServerAddresses() {
        assertEquals("https://books.example.com", MyBooksEndpoint.normalize(" https://BOOKS.example.com/ "))
        assertEquals("http://127.0.0.1:3000", MyBooksEndpoint.normalize("http://127.0.0.1:3000/"))
        for (invalid in listOf("books.example.com", "https://user:secret@books.example.com", "https://books.example.com/?token=secret", "https://books.example.com/#secret", "file:///books", "https://books.example.com/../books")) {
            try { MyBooksEndpoint.normalize(invalid); fail("Accepted $invalid") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun validatesProtocolAndSendsPasswordOnlyInPostBody() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(MockResponse().setBody("""{"service":"my-books","auth_version":1,"initialized":true}"""))
            server.enqueue(MockResponse().setBody("""{"token":"device-session"}"""))
            val client = MyBooksAuthClient(server.url("/").toString())
            client.testConnection()
            assertEquals("device-session", client.login("admin", "test-password", "phone"))
            assertEquals("/api/auth/status", server.takeRequest().path)
            val login = server.takeRequest()
            assertEquals("POST", login.method)
            assertEquals("/api/auth/login", login.path)
            assertEquals("test-password", JSONObject(login.body.readUtf8()).getString("password"))
            assertNull(login.getHeader("Authorization"))
        } finally { server.shutdown() }
    }
    @Test fun refusesLoginRedirectsWithoutForwardingPassword() = runBlocking {
        val source = MockWebServer()
        val destination = MockWebServer()
        try {
            source.start(); destination.start()
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", destination.url("/collect")))
            try {
                MyBooksAuthClient(source.url("/").toString()).login("admin", "test-password", "phone")
                fail("Redirect must be rejected")
            } catch (error: MyBooksHttpException) { assertEquals(307, error.status) }
            assertNull(destination.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { source.shutdown(); destination.shutdown() }
    }
    @Test fun rejectsOtherServicesAndUninitializedServers() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(MockResponse().setBody("""{"service":"other","initialized":true}"""))
            server.enqueue(MockResponse().setBody("""{"service":"my-books","auth_version":1,"initialized":false}"""))
            repeat(2) {
                try { MyBooksAuthClient(server.url("/").toString()).testConnection(); fail("Must reject") }
                catch (_: IllegalStateException) { }
            }
        } finally { server.shutdown() }
    }
}
