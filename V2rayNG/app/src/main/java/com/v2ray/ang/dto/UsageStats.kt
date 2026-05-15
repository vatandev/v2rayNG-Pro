package com.v2ray.ang.dto

data class UsageStats(
    val downBytes: Long = 0L,
    val upBytes: Long = 0L,
    val connectedMillis: Long = 0L,
    val lastUsedAt: Long = 0L
) {
    val totalBytes: Long get() = downBytes + upBytes
}
