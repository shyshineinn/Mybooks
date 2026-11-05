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

/** 三态分类对应的服务端 status 值。 */
internal fun ReadingStatus.toServerStatus(): String = when (this) {
    ReadingStatus.WISH -> "wish"
    ReadingStatus.READING -> "reading"
    ReadingStatus.DONE -> "done"
}

/**
 * “标记已读”与服务端 status/read_logs 的转换规则。
 *
 * 服务端语义：read_logs 是读完日期与读过次数的唯一来源；status 单独表示未读/在读/已读。
 * 标记已读时补一条读完记录，取消时清掉最后一条 finish，与网页端的状态联动一致。
 */
internal object MyBooksReadStatus {
    /**
     * 三态切换：首次在读记录开始日期；后续分类切换保留已有轮次与历史记录；
     * 已读优先完成当前轮次，没有未完成轮次时不重复写历史完成记录。
     */
    fun transition(
        currentLogs: List<MyBooksReadLog>,
        target: ReadingStatus,
        today: String
    ): MyBooksReadToggle {
        val logs = canonical(currentLogs)
        return when (target) {
            ReadingStatus.WISH ->
                MyBooksReadToggle(status = "wish", logs = logs)
            ReadingStatus.READING ->
                MyBooksReadToggle(status = "reading", logs = if (logs.isEmpty()) listOf(MyBooksReadLog(start = today)) else logs)
            ReadingStatus.DONE -> {
                val pending = logs.indexOfLast { it.start.isNotBlank() && it.finish.isBlank() }
                val completed = when {
                    pending >= 0 -> logs.toMutableList().also { it[pending] = it[pending].copy(finish = today) }
                    logs.any { it.finish.isNotBlank() } -> logs
                    else -> logs + MyBooksReadLog(start = today, finish = today)
                }
                MyBooksReadToggle(status = "done", logs = canonical(completed))
            }
        }
    }

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

        return transition(logs, ReadingStatus.DONE, today)
    }

    /** An explicit reread starts one new round; repeated taps keep the pending round. */
    fun startNextReading(currentLogs: List<MyBooksReadLog>, today: String): MyBooksReadToggle {
        val logs = canonical(currentLogs)
        val hasPending = logs.any { it.start.isNotBlank() && it.finish.isBlank() }
        val next = if (!hasPending && logs.any { it.finish.isNotBlank() }) {
            logs + MyBooksReadLog(start = today)
        } else logs
        return MyBooksReadToggle(status = "reading", logs = canonical(next))
    }

    /** Remove only the newest unfinished round, never a completed historical record. */
    fun abandonCurrentReading(currentLogs: List<MyBooksReadLog>): MyBooksReadToggle {
        val logs = canonical(currentLogs).toMutableList()
        val pending = logs.indexOfLast { it.start.isNotBlank() && it.finish.isBlank() }
        if (pending >= 0) logs.removeAt(pending)
        val status = when {
            logs.any { it.start.isNotBlank() && it.finish.isBlank() } -> "reading"
            logs.any { it.finish.isNotBlank() } -> "done"
            else -> "wish"
        }
        return MyBooksReadToggle(status, logs)
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
            // Same-day rereads can legitimately have identical start and finish dates.
            .sortedWith(compareBy<MyBooksReadLog> { it.start.ifBlank { it.finish } }.thenBy { it.finish.isBlank() }.thenBy { it.finish })

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
