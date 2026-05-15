package com.v2ray.ang.handler

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object OutageCounter {

    private const val FALLBACK_START = "2026-02-28"

    fun daysSinceOutageStart(now: Long = System.currentTimeMillis()): Int {
        val tz = TimeZone.getTimeZone("Asia/Tehran")
        val startMs = parseStart(RemoteConfigManager.outageStartDate(), tz)
            ?: parseStart(FALLBACK_START, tz)!!
        val start = Calendar.getInstance(tz).apply { timeInMillis = startMs }
        val today = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffMs = today.timeInMillis - start.timeInMillis
        if (diffMs < 0) return 0
        // +1 so the start day itself reads as "Day 1", not "Day 0".
        return (TimeUnit.MILLISECONDS.toDays(diffMs).toInt() + 1).coerceAtLeast(1)
    }

    private fun parseStart(date: String, tz: TimeZone): Long? = runCatching {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        fmt.parse(date)?.time
    }.getOrNull()
}
