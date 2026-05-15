package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.dto.ForkReleaseCatalog
import com.v2ray.ang.dto.ForkReleaseEntry
import com.v2ray.ang.util.JsonUtil

object ForkReleaseNotesManager {

    private const val ASSET_PATH = "fork_release_notes.json"

    @Volatile private var cache: List<ForkReleaseEntry>? = null

    fun all(context: Context): List<ForkReleaseEntry> {
        cache?.let { return it }
        val raw = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull().orEmpty()
        if (raw.isEmpty()) {
            cache = emptyList()
            return emptyList()
        }
        val parsed = runCatching { JsonUtil.fromJson(raw, ForkReleaseCatalog::class.java) }.getOrNull()
        val list = parsed?.releases.orEmpty()
        cache = list
        return list
    }
}
