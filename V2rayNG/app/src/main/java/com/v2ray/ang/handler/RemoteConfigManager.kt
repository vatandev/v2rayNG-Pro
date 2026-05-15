package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object RemoteConfigManager {

    private const val CONFIG_URL = "https://raw.githubusercontent.com/vatandev/v2rayNG-Pro/master/remote-config.json"
    private const val FETCH_TIMEOUT_MS = 5_000

    fun refreshAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { fetch() }.onSuccess { json ->
                MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_CONFIG_JSON, json)
            }
        }
    }

    fun outageStartDate(): String =
        cached()?.optJSONObject("outage")?.optString("start_date").orNullIfBlank() ?: "2026-02-28"

    fun outageEnabled(): Boolean =
        cached()?.optJSONObject("outage")?.optBoolean("enabled", true) ?: true

    fun outageMessageFa(): String =
        cached()?.optJSONObject("outage")?.optString("message_fa").orNullIfBlank()
            ?: "● روز %d قطعی اینترنت در ایران"

    fun outageMessageEn(): String =
        cached()?.optJSONObject("outage")?.optString("message_en").orNullIfBlank()
            ?: "● Day %d of internet disruption in Iran"

    private fun cached(): JSONObject? {
        val raw = MmkvManager.decodeSettingsString(AppConfig.PREF_REMOTE_CONFIG_JSON) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun fetch(): String {
        val conn = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = FETCH_TIMEOUT_MS
            readTimeout = FETCH_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun String?.orNullIfBlank(): String? = if (this.isNullOrBlank()) null else this
}
