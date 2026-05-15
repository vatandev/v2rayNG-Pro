package com.v2ray.ang.extension

import java.util.Locale

fun Long.toUsageBadge(): String {
    if (this <= 0L) return "—"
    if (this < 1024L) return "$this B"

    val kb = this / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)

    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)

    val gb = mb / 1024.0
    if (gb < 1024.0) return String.format(Locale.US, "%.2f GB", gb)

    val tb = gb / 1024.0
    return String.format(Locale.US, "%.2f TB", tb)
}
