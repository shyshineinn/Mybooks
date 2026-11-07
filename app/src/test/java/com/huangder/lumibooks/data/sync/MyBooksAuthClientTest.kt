package com.huangder.lumibooks.data.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MyBooksAuthClientTest {
    @Test fun validatesAndNormalizesServerAddresses() {
        assertEquals("https://books.example.com", MyBooksEndpoint.normalize(" https://BOOKS.example.com/ "))
        for (invalid in listOf("books.example.com", "https://user:secret@books.example.com", "https://books.example.com/?token=secret",
            "https://books.example.com/#secret", "file:///books", "https://books.example.com/../books")) {
            try { MyBooksEndpoint.normalize(invalid); fail("Accepted invalid address") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun detectsOnlyInitializedNativeServers() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(MockResponse().setBody("""{"err":"installed","installed":true}"""))
            MyBooksAuthClient(server.url("/").toString()).testConnection()
            assertEquals("/api/admin/install", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"err":"not_intalled","installed":false}"""))
            try { MyBooksAuthClient(server.url("/").toString()).testConnection(); fail("Uninitialized service accepted") }
            catch (_: IllegalStateException) { }
            server.enqueue(MockResponse().setBody("""{"service":"my-books","auth_version":1,"initialized":true}"""))
            try { MyBooksAuthClient(server.url("/").toString()).testConnection(); fail("Retired service accepted") }
            catch (_: IllegalStateException) { }
        } finally { server.shutdown() }
    }
    @Test fun refusesLoginRedirectsWithoutForwardingPassword() = runBlocking {
        val source = MockWebServer()
        val destination = MockWebServer()
        try {
            source.start(); destination.start()
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", destination.url("/collect")))
            try { MyBooksAuthClient(source.url("/").toString()).login("reader", "private-password"); fail("Redirect accepted") }
            catch (error: MyBooksHttpException) { assertEquals(307, error.status) }
            assertEquals("/api/user/sign_in", source.takeRequest().path)
            assertNull(destination.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { source.shutdown(); destination.shutdown() }
    }
    @Test fun logicalLoginFailureDoesNotBecomeASession() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(MockResponse().setBody("""{"err":"params.invalid","msg":"Invalid credentials"}"""))
            try { MyBooksAuthClient(server.url("/").toString()).login("reader", "wrong"); fail("Failed login accepted") }
            catch (error: MyBooksHttpException) { assertEquals(401, error.status) }
        } finally { server.shutdown() }
    }
    @Test fun retiredBearerTokensAreNotSentToNativeServer() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            try { MyBooksAuthClient(server.url("/").toString()).checkSession("old-device-token"); fail("Retired token accepted") }
            catch (error: MyBooksHttpException) { assertEquals(401, error.status) }
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { server.shutdown() }
    }
}
