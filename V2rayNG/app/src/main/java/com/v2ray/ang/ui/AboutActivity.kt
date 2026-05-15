package com.v2ray.ang.ui

import android.os.Bundle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.databinding.ActivityAboutBinding
import com.v2ray.ang.util.Utils

class AboutActivity : BaseActivity() {
    private val binding by lazy { ActivityAboutBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_about))

        binding.layoutWebsite.setOnClickListener {
            Utils.openUri(this, "https://vatan.dev/")
        }
        binding.layoutSoureCcode.setOnClickListener {
            Utils.openUri(this, AppConfig.APP_URL)
        }
        binding.layoutFeedback.setOnClickListener {
            Utils.openUri(this, "https://vatan.dev/v2rayng/feedback")
        }
        binding.layoutOssLicenses.setOnClickListener {
            val webView = android.webkit.WebView(this)
            webView.loadUrl("file:///android_asset/open_source_licenses.html")
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.title_oss_license)
                .setView(webView)
                .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                .show()
        }
        binding.tvVersion.text = BuildConfig.VERSION_NAME
        binding.tvUpstream.text = getString(R.string.fork_about_upstream_line, AppConfig.UPSTREAM_VERSION)

        val rawLib = CoreNativeManager.getLibVersion()
        val xrayPart = Regex("Xray-core\\s+v?([0-9.]+)").find(rawLib)?.groupValues?.getOrNull(1)
        binding.tvCoreVersion.text = if (xrayPart != null) "xray-core $xrayPart" else rawLib

        binding.tvAppId.text = BuildConfig.APPLICATION_ID
    }
}
