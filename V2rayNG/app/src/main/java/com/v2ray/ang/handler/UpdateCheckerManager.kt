package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateCheckerManager {

    // In-memory short-window cache. The banner fires checkForUpdate() on
    // every onResume() and a user tabbing in and out of the app shouldn't
    // hammer api.github.com (which rate-limits anonymous requests at 60/h
    // and returns 403 once the limit is hit). 10 minutes is short enough
    // that a freshly published release still shows up promptly, long
    // enough to keep the rate-limit budget for the human-triggered
    // "Check for update" screen.
    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    @Volatile private var cachedResult: CheckUpdateResult? = null
    @Volatile private var cachedAt: Long = 0L

    suspend fun checkForUpdate(
        includePreRelease: Boolean = false,
        allowProxyFallback: Boolean = true
    ): CheckUpdateResult = withContext(Dispatchers.IO) {
        // Cache short-circuit: a banner-side call within the TTL just reuses
        // whatever we already learned, no network. The explicit "Check for
        // update" screen passes allowProxyFallback=true and is willing to
        // wait, so we still re-validate for it.
        val now = System.currentTimeMillis()
        cachedResult?.let {
            if (!allowProxyFallback && now - cachedAt < CACHE_TTL_MS) {
                return@withContext it
            }
        }

        val url = if (includePreRelease) {
            AppConfig.APP_API_URL
        } else {
            AppConfig.APP_API_URL.concatUrl("latest")
        }

        var response = HttpUtil.getUrlContent(url, 5000)
        if (response.isNullOrEmpty() && allowProxyFallback) {
            // Only used by the explicit "Check for update" screen — banners
            // that fire automatically on app launch pass false so they don't
            // spam ECONNREFUSED to the logs when VPN is off.
            val proxyUsername = SettingsManager.getSocksUsername()
            val proxyPassword = SettingsManager.getSocksPassword()
            val httpPort = SettingsManager.getHttpPort()
            response = HttpUtil.getUrlContent(url, 5000, httpPort, proxyUsername, proxyPassword)
        }
        if (response.isNullOrEmpty()) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        val latestRelease = if (includePreRelease) {
            JsonUtil.fromJson(response, Array<GitHubRelease>::class.java)
                ?.firstOrNull()
                ?: throw IllegalStateException("No pre-release found")
        } else {
            JsonUtil.fromJson(response, GitHubRelease::class.java)
        }
        if (latestRelease == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        val latestVersion = latestRelease.tagName.removePrefix("v")
        LogUtil.i(
            AppConfig.TAG,
            "Found new version: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        val result = if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            val downloadUrl = getDownloadUrl(latestRelease, Build.SUPPORTED_ABIS[0])
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = downloadUrl,
                isPreRelease = latestRelease.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
        cachedResult = result
        cachedAt = System.currentTimeMillis()
        return@withContext result
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val v1 = version1.split(".")
        val v2 = version2.split(".")

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = if (i < v1.size) v1[i].toInt() else 0
            val num2 = if (i < v2.size) v2[i].toInt() else 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    private fun getDownloadUrl(release: GitHubRelease, abi: String): String {
        val fDroid = "fdroid"

        val assetsByAbi = release.assets.filter {
            (it.name.contains(abi, true))
        }

        val asset = if (BuildConfig.APPLICATION_ID.contains(fDroid, ignoreCase = true)) {
            assetsByAbi.firstOrNull { it.name.contains(fDroid) }
        } else {
            assetsByAbi.firstOrNull { !it.name.contains(fDroid) }
        }

        return asset?.browserDownloadUrl
            ?: throw IllegalStateException("No compatible APK found")
    }
}
