package com.huangder.lumibooks.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Login does not follow redirects, so a password cannot be forwarded to a different server. */
class MyBooksAuthClient(serverUrl: String) {
    private val baseUrl = MyBooksEndpoint.normalize(serverUrl)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun testConnection() {
        val status = request("status")
        check(status.optString("service") == "my-books" && status.optInt("auth_version") == 1) {
            "该地址不是支持账号登录的 My books 服务器，请检查地址或升级服务端"
        }
        check(status.optBoolean("initialized")) { "服务器尚未初始化，请在服务器运行 npm run setup" }
    }
    suspend fun login(username: String, password: String, deviceName: String): String {
        val result = request("login", JSONObject().put("username", username).put("password", password)
            .put("device_name", deviceName))
        return result.optString("token").takeIf { it.isNotBlank() }
            ?: error("服务器未返回设备登录凭据")
    }
    suspend fun checkSession(token: String) {
        check(request("me", token = token).optBoolean("authenticated")) { "登录已失效，请重新登录" }
    }
    suspend fun logout(token: String) { request("logout", JSONObject(), token) }

    private suspend fun request(path: String, body: JSONObject? = null, token: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url("$baseUrl/api/auth/$path").header("Accept", "application/json")
            if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
            if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw MyBooksHttpException(response.code, text)
                if (response.code == 204) JSONObject() else JSONObject(text)
            }
        }
}
