package com.huangder.lumibooks.data.sync

import com.huangder.lumibooks.data.local.entity.MyBooksSyncStateEntity
import org.json.JSONObject

/** Only acknowledge the edit actually sent, never an edit made while the request was in flight. */
internal fun acknowledgedMyBooksKeys(
    sent: MyBooksSyncStateEntity, latest: MyBooksSyncStateEntity, candidates: Set<String>
): Set<String> {
    val before = JSONObject(sent.overridesJson)
    val after = JSONObject(latest.overridesJson)
    val beforeTimes = JSONObject(sent.editedAtJson)
    val afterTimes = JSONObject(latest.editedAtJson)
    return candidates.filterTo(linkedSetOf()) { key ->
        before.has(key) == after.has(key) && before.opt(key)?.toString() == after.opt(key)?.toString() &&
            beforeTimes.optLong(key) == afterTimes.optLong(key)
    }
}
