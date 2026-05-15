package com.v2ray.ang.handler

import com.v2ray.ang.dto.UsageStats
import com.v2ray.ang.util.JsonUtil

object UsageStatsManager {

    private const val KEY_PREFIX = "stats_"
    private const val SUB_KEY_PREFIX = "sub_stats_"
    private const val KEY_LAST_RESET_AT = "_last_reset_at"

    private val tickListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun addTickListener(l: () -> Unit) { tickListeners.add(l) }
    fun removeTickListener(l: () -> Unit) { tickListeners.remove(l) }
    fun notifyTick() {
        mainHandler.post { tickListeners.forEach { runCatching { it() } } }
    }

    private val storage get() = MmkvManager.trafficStatsStorage

    @Synchronized
    fun get(guid: String): UsageStats {
        if (guid.isBlank()) return UsageStats()
        val raw = storage.decodeString(KEY_PREFIX + guid) ?: return UsageStats()
        return runCatching { JsonUtil.fromJson(raw, UsageStats::class.java) }.getOrNull() ?: UsageStats()
    }

    @Synchronized
    fun addDelta(guid: String, downBytes: Long, upBytes: Long, sessionMillis: Long) {
        if (guid.isBlank()) return
        val safeDown = if (downBytes > 0) downBytes else 0L
        val safeUp = if (upBytes > 0) upBytes else 0L
        val safeMs = if (sessionMillis in 1..600_000L) sessionMillis else 0L
        if (safeDown == 0L && safeUp == 0L && safeMs == 0L) return

        val cur = get(guid)
        val updated = cur.copy(
            downBytes = cur.downBytes + safeDown,
            upBytes = cur.upBytes + safeUp,
            connectedMillis = cur.connectedMillis + safeMs,
            lastUsedAt = System.currentTimeMillis()
        )
        storage.encode(KEY_PREFIX + guid, JsonUtil.toJson(updated))
    }

    @Synchronized
    fun resetOne(guid: String) {
        if (guid.isBlank()) return
        storage.removeValueForKey(KEY_PREFIX + guid)
    }

    @Synchronized
    fun resetAll() {
        storage.allKeys()?.forEach { key ->
            if (key.startsWith(KEY_PREFIX)) storage.removeValueForKey(key)
        }
        storage.encode(KEY_LAST_RESET_AT, System.currentTimeMillis())
    }

    fun lastResetAt(): Long = storage.decodeLong(KEY_LAST_RESET_AT, 0L)

    @Synchronized
    fun getSubSnapshot(subscriptionId: String): UsageStats {
        if (subscriptionId.isBlank()) return UsageStats()
        val raw = storage.decodeString(SUB_KEY_PREFIX + subscriptionId) ?: return UsageStats()
        return runCatching { JsonUtil.fromJson(raw, UsageStats::class.java) }.getOrNull() ?: UsageStats()
    }

    @Synchronized
    fun absorbBeforeSubRefresh(subscriptionId: String, guids: List<String>) {
        if (subscriptionId.isBlank() || guids.isEmpty()) return
        var snap = getSubSnapshot(subscriptionId)
        for (g in guids) {
            val s = get(g)
            if (s.downBytes == 0L && s.upBytes == 0L && s.connectedMillis == 0L && s.lastUsedAt == 0L) continue
            snap = snap.copy(
                downBytes = snap.downBytes + s.downBytes,
                upBytes = snap.upBytes + s.upBytes,
                connectedMillis = snap.connectedMillis + s.connectedMillis,
                lastUsedAt = maxOf(snap.lastUsedAt, s.lastUsedAt)
            )
            storage.removeValueForKey(KEY_PREFIX + g)
        }
        storage.encode(SUB_KEY_PREFIX + subscriptionId, JsonUtil.toJson(snap))
    }
}
