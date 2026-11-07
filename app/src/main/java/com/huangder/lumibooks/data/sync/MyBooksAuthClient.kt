package com.huangder.lumibooks.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Native mybooks-server login. Passwords are posted once and never persisted. */
class MyBooksAuthClient(serverUrl: String) {
    private val baseUrl = MyBooksEndpoint.normalize(serverUrl)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun testConnection() = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url("$baseUrl/api/admin/install").build()).execute().use { response ->
            check(response.isSuccessful) { "无法识别 mybooks 服务端" }
            val data = JSONObject(response.body?.string().orEmpty())
            check(data.opt("installed") is Boolean && data.optString("err") in setOf("installed", "not_intalled")) {
                "该地址不是支持的 mybooks 服务端"
            }
            check(data.getBoolean("installed")) { "服务器尚未初始化，请先在网页完成初始化" }
        }
    }

    suspend fun login(username: String, password: String): String = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/user/sign_in".toHttpUrl()
        val body = FormBody.Builder().add("username", username).add("password", password).build()
        val session = client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, text)
            val data = JSONObject(text)
            if (data.optString("err") != "ok") throw MyBooksHttpException(401,
                JSONObject().put("error", data.optString("msg").ifBlank { "登录失败" }).toString())
            PoxenSession.encode(baseUrl, username, Cookie.parseAll(url, response.headers))
        }
        PoxenApiClient(baseUrl, { session }, client).assertLoggedIn()
        session
    }

    suspend fun checkSession(token: String) = withContext(Dispatchers.IO) {
        PoxenApiClient(baseUrl, { token }, client).assertLoggedIn()
    }

    suspend fun logout(token: String) = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/user/sign_out".toHttpUrl()
        val header = PoxenSession.cookieHeader(token, url)
        if (header.isBlank()) throw MyBooksHttpException(401, """{"error":"登录已失效"}""")
        client.newCall(Request.Builder().url(url).header("Cookie", header).post(FormBody.Builder().build()).build())
            .execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw MyBooksHttpException(response.code, body)
                checkedPoxenResponse(body)
            }
        Unit
    }
}
