package com.huangder.lumibooks.data.sync

import java.net.URI

/** A single active server. Credentials, queries and fragments are never part of its address. */
object MyBooksEndpoint {
    fun normalize(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("服务器地址格式不正确")
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "请输入完整的 http:// 或 https:// 服务器地址"
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)) { "服务器地址不能包含账号、查询参数或片段" }
        require(uri.path.split('/').none { it == "." || it == ".." }) { "服务器路径格式不正确" }
        return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port,
            uri.path.trimEnd('/'), null, null).toASCIIString()
    }
}
