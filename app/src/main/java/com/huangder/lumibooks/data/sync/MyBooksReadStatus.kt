package com.huangder.lumibooks.data.sync

import com.huangder.lumibooks.domain.model.ReadingStatus
import org.json.JSONArray
import org.json.JSONObject

/** 服务器 `read_logs` 的一行；空白日期用空串表示。 */
internal data class MyBooksReadLog(
    val start: String = "",
    val finish: String = ""
)

internal data class MyBooksReadToggle(
    val status: String,
    val logs: List<MyBooksReadLog>
)

/**
 * “标记已读”与服务端 status/read_logs 的转换规则。
 *
 * 服务端语义：read_logs 是读完日期与读过次数的唯一来源；status 单独表示想读/在读/已读。
 * 标记已读时补一条读完记录，取消时清掉最后一条 finish，与网页端的状态联动一致。
 */
internal object MyBooksReadStatus {
    fun toggle(
        currentLogs: List<MyBooksReadLog>,
        markRead: Boolean,
        today: String
    ): MyBooksReadToggle {
        val logs = canonical(currentLogs).toMutableList()
        if (!markRead) {
            val lastFinished = logs.indexOfLast { it.finish.isNotBlank() }
            if (lastFinished >= 0) {
                logs[lastFinished] = logs[lastFinished].copy(finish = "")
            }
            val remaining = canonical(logs)
            return MyBooksReadToggle(
                status = if (remaining.isEmpty()) "wish" else "reading",
                logs = remaining
            )
        }

        if (logs.none { it.finish.isNotBlank() }) {
            val pending = logs.indexOfLast { it.start.isNotBlank() && it.finish.isBlank() }
            if (pending >= 0) {
                logs[pending] = logs[pending].copy(finish = today)
            } else {
                logs += MyBooksReadLog(start = today, finish = today)
            }
        }
        return MyBooksReadToggle(status = "done", logs = canonical(logs))
    }

    fun parseLogs(raw: Any?): List<MyBooksReadLog> {
        val array = when (raw) {
            is JSONArray -> raw
            is String -> runCatching { JSONArray(raw) }.getOrNull()
            else -> null
        } ?: return emptyList()
        val logs = mutableListOf<MyBooksReadLog>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            logs += MyBooksReadLog(
                start = item.optString("start").trim(),
                finish = item.optString("finish").trim()
            )
        }
        return canonical(logs)
    }

    fun logsToJson(logs: List<MyBooksReadLog>): JSONArray {
        val array = JSONArray()
        canonical(logs).forEach { log ->
            array.put(JSONObject().put("start", log.start).put("finish", log.finish))
        }
        return array
    }

    fun canonical(logs: List<MyBooksReadLog>): List<MyBooksReadLog> =
        logs.map { MyBooksReadLog(it.start.trim(), it.finish.trim()) }
            .filter { it.start.isNotEmpty() || it.finish.isNotEmpty() }
            .distinct()
            .sortedWith(compareBy({ it.start }, { it.finish }))

    /** 阅读进度首次达到 100% 的那一次写入才需要自动同步服务器。 */
    fun becameFinished(previousProgress: Float, newProgress: Float): Boolean =
        previousProgress < 1f && newProgress >= 1f

    /** 状态分类只看服务器 status：本地 override 优先，其次同步基线。 */
    fun categoryStatus(overridesJson: String, baseJson: String): ReadingStatus? {
        val overrides = runCatching { JSONObject(overridesJson) }.getOrDefault(JSONObject())
        val base = runCatching { JSONObject(baseJson) }.getOrDefault(JSONObject())
        val raw = overrides.optString("status").ifBlank { base.optString("status") }
        return ReadingStatus.fromServerStatus(raw)
    }
}
