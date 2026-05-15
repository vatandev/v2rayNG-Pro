package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.OutageCounter
import com.v2ray.ang.handler.RemoteConfigManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {

    private enum class RoutingMode(
        val prefValue: String,
        val buttonId: Int,
        val routingPresetIndex: Int,
        val labelResId: Int,
    ) {
        RULE("rule", R.id.btn_mode_rule, com.v2ray.ang.enums.RoutingType.WHITE_IRAN.ordinal, R.string.fork_routing_mode_rule),
        GLOBAL("global", R.id.btn_mode_global, com.v2ray.ang.enums.RoutingType.GLOBAL.ordinal, R.string.fork_routing_mode_global),
        DIRECT("direct", R.id.btn_mode_direct, com.v2ray.ang.enums.RoutingType.BLACK.ordinal, R.string.fork_routing_mode_direct);

        companion object {
            private const val PREF_KEY = "pref_fork_routing_mode"
            fun fromStoredValue(value: String?) = entries.firstOrNull { it.prefValue == value } ?: RULE
            fun fromButtonId(buttonId: Int) = entries.firstOrNull { it.buttonId == buttonId }
            fun stored(): RoutingMode = fromStoredValue(MmkvManager.decodeSettingsString(PREF_KEY))
            fun store(mode: RoutingMode) { MmkvManager.encodeSettings(PREF_KEY, mode.prefValue) }
        }
    }

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.title_server))

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        // setup navigation drawer
        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        // The drawer header title is Latin-only ("V2rayNG Pro"). The fa theme
        // overlays Vazirmatn on every TextView; Vazirmatn's Latin fallback is
        // heavier than the rest of the UI uses, so the title reads chunky next
        // to the Persian menu items. Force the original Latin typeface (the
        // light Montserrat we declared in the layout) directly on the view so
        // the theme override doesn't win.
        binding.navView.getHeaderView(0)?.findViewById<android.widget.TextView>(R.id.tv_nav_header_title)?.let { tv ->
            val tf = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.montserrat_thin)
            if (tf != null) tv.typeface = tf
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }

        setupGroupTab()
        setupViewModel()
        setupOutageTicker()
        setupRoutingModeToggle()
        setupQuickTestButton()
        setupUpdateBanner()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
        }
        mainViewModel.quickTestFinished.observe(this) { finished ->
            if (finished == true) handleQuickTestFinished()
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupQuickTestButton() {
        binding.btnQuickTestWrap.setOnClickListener {
            if (mainViewModel.isQuickTest) {
                com.v2ray.ang.util.MessageUtil.sendMsg2TestService(
                    applicationContext,
                    com.v2ray.ang.dto.TestServiceMessage(key = com.v2ray.ang.AppConfig.MSG_MEASURE_CONFIG_CANCEL)
                )
                mainViewModel.isQuickTest = false
                binding.btnQuickTest.text = getString(R.string.fork_quick_test_button)
                binding.progressQuickTest.visibility = android.view.View.GONE
                return@setOnClickListener
            }
            if (mainViewModel.serversCache.isEmpty()) {
                toast(R.string.fork_quick_test_no_servers)
                return@setOnClickListener
            }
            mainViewModel.isQuickTest = true
            binding.btnQuickTest.text = getString(R.string.fork_quick_test_cancel)
            binding.progressQuickTest.visibility = android.view.View.VISIBLE
            mainViewModel.testAllRealPing()
        }
    }

    private var pendingRoutingApply: kotlinx.coroutines.Job? = null
    private fun setupRoutingModeToggle() {
        val active = RoutingMode.stored()
        // Restore the visual selection without persisting again or restarting.
        binding.toggleRoutingMode.check(active.buttonId)

        binding.toggleRoutingMode.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = RoutingMode.fromButtonId(buttonId) ?: return@addOnButtonCheckedListener
            pendingRoutingApply?.cancel()
            pendingRoutingApply = lifecycleScope.launch {
                delay(350)
                applyRoutingMode(mode)
            }
        }
    }

    private fun applyRoutingMode(mode: RoutingMode) {
        RoutingMode.store(mode)
        val applied = runCatching {
            SettingsManager.resetRoutingRulesetsFromPresets(this, mode.routingPresetIndex)
        }
        if (applied.isFailure) {
            toast(getString(R.string.fork_routing_mode_apply_failed, getString(mode.labelResId)))
            return
        }
        toast(getString(R.string.fork_routing_mode_applied, getString(mode.labelResId)))
        if (mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
    }

    private fun handleQuickTestFinished() {
        val shouldStart = mainViewModel.isQuickTest
        mainViewModel.isQuickTest = false
        mainViewModel.quickTestFinished.value = null
        binding.btnQuickTest.text = getString(R.string.fork_quick_test_button)
        binding.progressQuickTest.visibility = android.view.View.GONE
        if (shouldStart && mainViewModel.isRunning.value != true) {
            if (SettingsManager.isVpnMode()) {
                val intent = android.net.VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                startV2Ray()
            }
        }
    }

    private val updateDismissedKey = "fork_update_banner_dismissed_for"

    private fun setupUpdateBanner() {
        // Wired once at onCreate: dismiss + click handlers stay alive for
        // the activity lifetime. The actual network check is decoupled into
        // refreshUpdateBanner() which we call again on every onResume so a
        // user who keeps the app open still sees the banner the moment a
        // new release is published.
        binding.btnUpdateBannerDismiss.setOnClickListener {
            val tag = binding.tvUpdateBanner.tag as? String ?: return@setOnClickListener
            MmkvManager.encodeSettings(updateDismissedKey, tag)
            binding.layoutUpdateBanner.visibility = android.view.View.GONE
        }
        binding.layoutUpdateBanner.setOnClickListener {
            val url = binding.layoutUpdateBanner.tag as? String ?: return@setOnClickListener
            Utils.openUri(this, url)
        }
        refreshUpdateBanner()
    }

    private fun refreshUpdateBanner() {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // Banner is a passive background check — don't fall back to
                    // the local SOCKS proxy if direct fails, that just floods
                    // the log with ECONNREFUSED when VPN is off.
                    UpdateCheckerManager.checkForUpdate(
                        includePreRelease = false,
                        allowProxyFallback = false
                    )
                }
            }.getOrNull() ?: return@launch
            if (result.hasUpdate != true) return@launch
            val latest = result.latestVersion ?: return@launch
            val dismissed = MmkvManager.decodeSettingsString(updateDismissedKey, "")
            if (dismissed == latest) return@launch
            // Re-bind even if already showing — version string might have
            // changed since the last check (two releases in a row).
            binding.tvUpdateBanner.tag = latest
            // One-line banner: title says "New update" (or fa equivalent), the
            // sub-text on the same row carries just the version number (LTR-locked
            // so 0.0.34 doesn't get rewritten to Persian digits, which would look
            // out of place next to the bold title).
            binding.tvUpdateBanner.text = getString(R.string.fork_update_banner_oneline)
            binding.tvUpdateBannerSub.text = latest
            binding.layoutUpdateBanner.tag = "${AppConfig.APP_URL}/releases/tag/$latest"
            binding.layoutUpdateBanner.visibility = android.view.View.VISIBLE
        }
    }

    private fun showSubscriptionUsagePopup() {
        val subId = mainViewModel.subscriptionId
        val remarks = if (subId.isNotEmpty()) MmkvManager.decodeSubscription(subId)?.remarks.orEmpty() else ""
        UsageSheets.showSubscriptionUsage(this, subId, remarks) {
            mainViewModel.reloadServerList()
        }
    }

    private var outageBlinkJob: kotlinx.coroutines.Job? = null

    private fun setupOutageTicker() {
        RemoteConfigManager.refreshAsync()

        if (!RemoteConfigManager.outageEnabled()) {
            binding.tvOutageTicker.visibility = android.view.View.GONE
            return
        }

        val day = OutageCounter.daysSinceOutageStart()
        val isFa = java.util.Locale.getDefault().language == "fa"
        val template = if (isFa) RemoteConfigManager.outageMessageFa() else RemoteConfigManager.outageMessageEn()
        val dayStr = if (isFa) com.v2ray.ang.util.JalaliCalendar.toPersianDigits(day.toString()) else day.toString()
        val fullText = template.replace("%d", dayStr)
        val dotIndex = fullText.indexOf('◌')

        binding.tvOutageTicker.text = buildOutageSpan(fullText, dotIndex, true)
        binding.tvOutageTicker.visibility = android.view.View.VISIBLE

        if (dotIndex >= 0) {
            outageBlinkJob?.cancel()
            outageBlinkJob = lifecycleScope.launch {
                val endAt = System.currentTimeMillis() + 10_000L
                var on = true
                while (System.currentTimeMillis() < endAt) {
                    binding.tvOutageTicker.text = buildOutageSpan(fullText, dotIndex, on)
                    on = !on
                    kotlinx.coroutines.delay(600)
                }
                binding.tvOutageTicker.text = buildOutageSpan(fullText, dotIndex, true)
            }
        }
    }

    private fun buildOutageSpan(fullText: String, dotIndex: Int, on: Boolean): CharSequence {
        if (dotIndex < 0) return fullText
        val drawable = androidx.core.content.ContextCompat.getDrawable(this, com.v2ray.ang.R.drawable.ic_outage_dot)
            ?: return fullText
        val size = (binding.tvOutageTicker.textSize * 1.05f).toInt()
        drawable.setBounds(0, 0, size, size)
        val tint = if (on) android.graphics.Color.parseColor("#FF3B30") else android.graphics.Color.parseColor("#33FF3B30")
        drawable.setTint(tint)
        val span = android.text.SpannableString(fullText)
        span.setSpan(
            android.text.style.ImageSpan(drawable, android.text.style.ImageSpan.ALIGN_CENTER),
            dotIndex, dotIndex + 1,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        binding.tabGroup.isVisible = groups.size > 1

        wireTabActions()
    }

    private var tabActionsListener: com.google.android.material.tabs.TabLayout.OnTabSelectedListener? = null

    private fun wireTabActions() {
        // Long-press anywhere on a tab opens the menu (fast for power users).
        for (i in 0 until binding.tabGroup.tabCount) {
            val tab = binding.tabGroup.getTabAt(i) ?: continue
            val subId = tab.tag as? String ?: continue
            tab.view.setOnLongClickListener {
                showSubscriptionTabMenu(subId)
                true
            }
        }
        // Tapping the already-selected tab a second time also opens it —
        // discoverable for users who don't know about long-press.
        tabActionsListener?.let { binding.tabGroup.removeOnTabSelectedListener(it) }
        val l = object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                val subId = tab.tag as? String ?: return
                showSubscriptionTabMenu(subId)
            }
        }
        binding.tabGroup.addOnTabSelectedListener(l)
        tabActionsListener = l
    }

    private fun showSubscriptionTabMenu(subId: String) {
        val remarks = MmkvManager.decodeSubscription(subId)?.remarks.orEmpty()
        UsageSheets.showSubscriptionMenu(
            activity = this,
            subId = subId,
            subRemarks = remarks,
            onUsage = {
                UsageSheets.showSubscriptionUsage(this, subId, remarks) { mainViewModel.reloadServerList() }
            },
            onRename = {
                UsageSheets.showRename(this, subId, remarks) { setupGroupTab() }
            }
        )
    }

    private fun handleFabAction() {
        applyRunningState(isLoading = true, isRunning = false)

        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // service not running: keep existing no-op (could show a message if desired)
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        CoreServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        // Upstream encodes the post-test status as "Success: …took 128ms\n[TR] 1.2.3.4"
        // on a single LiveData payload. The hairline below the FAB has room for
        // two lines (maxLines=2 in activity_main.xml), but we sometimes got the IP
        // pushed onto the same line because the renderer trims the newline. Split
        // and re-join with a real \n so the second line drops below the first.
        val text = content.orEmpty()
        val firstNl = text.indexOfAny(charArrayOf('\n', '\r'))
        binding.tvTestState.text = if (firstNl > 0) {
            val head = text.substring(0, firstNl).trim()
            val tail = text.substring(firstNl + 1).trim()
            com.v2ray.ang.util.JalaliCalendar.localizeDigits(this, "$head\n$tail")
        } else {
            com.v2ray.ang.util.JalaliCalendar.localizeDigits(this, text)
        }
        android.util.Log.d("FORK_UI", "testState set len=${text.length} twoLine=${firstNl > 0}")
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            return
        }

        if (isRunning) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUpdateBanner()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.fork_add_sheet -> {
            showAddConfigSheet()
            true
        }

        R.id.fork_more_sheet -> {
            showMoreActionsSheet()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /** "+" toolbar action → iOS-style sheet replacing the legacy submenu. */
    private fun showAddConfigSheet() {
        val sections = listOf(
            ActionSheets.Section(
                header = getString(R.string.fork_add_section_import),
                actions = listOf(
                    ActionSheets.Action("import_qrcode", getString(R.string.menu_item_import_config_qrcode),
                        R.drawable.ic_action_qrcode) { importQRcode() },
                    ActionSheets.Action("import_clipboard", getString(R.string.menu_item_import_config_clipboard),
                        R.drawable.ic_copy) { importClipboard() },
                    ActionSheets.Action("import_local", getString(R.string.menu_item_import_config_local),
                        R.drawable.ic_file_24dp) { importConfigLocal() },
                )
            ),
            ActionSheets.Section(
                header = getString(R.string.fork_add_section_manual),
                actions = listOf(
                    ActionSheets.Action("add_policy", getString(R.string.menu_item_import_config_policy_group),
                        R.drawable.ic_subscriptions_24dp) { importManually(EConfigType.POLICYGROUP.value) },
                    ActionSheets.Action("add_chain", getString(R.string.menu_item_import_config_proxy_chain),
                        R.drawable.ic_routing_24dp) { importManually(EConfigType.PROXYCHAIN.value) },
                    ActionSheets.Action("add_vmess", getString(R.string.menu_item_import_config_manually_vmess),
                        R.drawable.ic_add_24dp) { importManually(EConfigType.VMESS.value) },
                    ActionSheets.Action("add_vless", getString(R.string.menu_item_import_config_manually_vless),
                        R.drawable.ic_add_24dp) { importManually(EConfigType.VLESS.value) },
                    ActionSheets.Action("add_ss", getString(R.string.menu_item_import_config_manually_ss),
                        R.drawable.ic_add_24dp) { importManually(EConfigType.SHADOWSOCKS.value) },
                    ActionSheets.Action("add_socks", getString(R.string.menu_item_import_config_manually_socks),
                        R.drawable.ic_add_24dp) { importManually(EConfigType.SOCKS.value) },
                    ActionSheets.Action("add_http", getString(R.string.menu_item_import_config_manually_http),
                        R.drawable.ic_add_24dp) { importManually(EConfigType.HTTP.value) },
                    ActionSheets.Action("add_trojan", getString(R.string.menu_item_import_config_manually_trojan),
                        R.drawable.ic_add_24dp) { importManually(EConfigType.TROJAN.value) },
                    ActionSheets.Action("add_wireguard", getString(R.string.menu_item_import_config_manually_wireguard),
                        R.drawable.ic_add_24dp) { importManually(EConfigType.WIREGUARD.value) },
                    ActionSheets.Action("add_hysteria2", getString(R.string.menu_item_import_config_manually_hysteria2),
                        R.drawable.ic_add_24dp) { importManually(EConfigType.HYSTERIA2.value) },
                )
            ),
        )
        ActionSheets.show(this, "add_config", getString(R.string.fork_add_sheet_title), sections)
    }

    /** ⋮ toolbar action → iOS-style grouped sheet replacing the legacy overflow. */
    private fun showMoreActionsSheet() {
        val sections = listOf(
            ActionSheets.Section(
                header = getString(R.string.fork_more_section_service),
                actions = listOf(
                    ActionSheets.Action("service_restart", getString(R.string.title_service_restart),
                        R.drawable.ic_action_restart) { restartV2Ray() },
                )
            ),
            ActionSheets.Section(
                header = getString(R.string.fork_more_section_test),
                actions = listOf(
                    ActionSheets.Action("ping_all", getString(R.string.title_ping_all_server),
                        R.drawable.ic_action_tcping) {
                        toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
                        mainViewModel.testAllTcping()
                    },
                    ActionSheets.Action("real_ping_all", getString(R.string.title_real_ping_all_server),
                        R.drawable.ic_action_timer) {
                        toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
                        mainViewModel.testAllRealPing()
                    },
                    ActionSheets.Action("sort_results", getString(R.string.title_sort_by_test_results),
                        R.drawable.ic_select_all_24dp) { sortByTestResults() },
                    ActionSheets.Action("locate_selected", getString(R.string.title_locate_selected_config),
                        R.drawable.ic_action_done) { locateSelectedServer() },
                )
            ),
            ActionSheets.Section(
                header = getString(R.string.fork_more_section_subscription),
                actions = listOf(
                    ActionSheets.Action("sub_update", getString(R.string.title_sub_update),
                        R.drawable.ic_cloud_download_24dp) { importConfigViaSub() },
                    ActionSheets.Action("sub_usage", getString(R.string.fork_sub_usage_title),
                        R.drawable.ic_subscriptions_24dp) { showSubscriptionUsagePopup() },
                )
            ),
            ActionSheets.Section(
                header = getString(R.string.fork_more_section_other),
                actions = listOf(
                    ActionSheets.Action("export_all", getString(R.string.title_export_all),
                        R.drawable.ic_share_24dp) { exportAll() },
                )
            ),
            ActionSheets.Section(
                header = getString(R.string.fork_more_section_cleanup),
                actions = listOf(
                    ActionSheets.Action("del_dup", getString(R.string.title_del_duplicate_config),
                        R.drawable.ic_delete_24dp, destructive = true) { delDuplicateConfig() },
                    ActionSheets.Action("del_invalid", getString(R.string.title_del_invalid_config),
                        R.drawable.ic_delete_24dp, destructive = true) { delInvalidConfig() },
                    ActionSheets.Action("del_all", getString(R.string.title_del_all_config),
                        R.drawable.ic_delete_24dp, destructive = true) { delAllConfig() },
                )
            ),
        )
        ActionSheets.show(this, "more_actions", getString(R.string.fork_more_actions_title), sections)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else if (createConfigType == EConfigType.PROXYCHAIN.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerProxyChainActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            toast(R.string.toast_server_not_found_in_group)
            return
        }

        // Switch to target group if needed, then scroll to the server
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    /**
     * Scrolls to the selected server in the specified fragment.
     * @param groupIndex The index of the group/fragment to scroll in
     */
    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            toast(R.string.toast_fragment_not_available)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sub_setting -> requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.fork_website -> {
                android.util.Log.d("FORK_UI", "drawer tap=website")
                Utils.openUri(this, "https://vatan.dev")
            }
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }
}