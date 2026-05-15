package com.v2ray.ang.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.v2ray.ang.R
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.extension.toUsageBadge
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UsageStatsManager
import com.v2ray.ang.util.JalaliCalendar

object UsageSheets {

    private const val LOG_TAG = "FORK_UI"

    private fun loc(ctx: Context, s: String) = JalaliCalendar.localizeDigits(ctx, s)

    private fun snappyAnimation(sheet: BottomSheetDialog) {
        sheet.window?.setWindowAnimations(R.style.BottomSheetSnappyAnimation)
    }

    fun showConfigUsage(
        context: Context,
        guid: String,
        profile: ProfileItem,
        onChanged: () -> Unit
    ) {
        val sheet = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_config_usage, null)
        sheet.setContentView(view)
        snappyAnimation(sheet)

        val title = view.findViewById<TextView>(R.id.tv_sheet_title)
        val subtitle = view.findViewById<TextView>(R.id.tv_sheet_subtitle)
        val tvUp = view.findViewById<TextView>(R.id.tv_stat_up)
        val tvDown = view.findViewById<TextView>(R.id.tv_stat_down)
        val tvTotal = view.findViewById<TextView>(R.id.tv_stat_total)
        val meta = view.findViewById<TextView>(R.id.tv_sheet_meta)
        val close = view.findViewById<ImageView>(R.id.btn_sheet_close)
        val reset = view.findViewById<TextView>(R.id.btn_reset_one)

        title.text = profile.remarks.ifBlank { context.getString(R.string.fork_config_usage_title) }
        subtitle.text = context.getString(R.string.fork_config_usage_title)

        val stats = UsageStatsManager.get(guid)
        val total = stats.upBytes + stats.downBytes
        tvUp.text = if (stats.upBytes > 0) loc(context, stats.upBytes.toUsageBadge()) else "—"
        tvDown.text = if (stats.downBytes > 0) loc(context, stats.downBytes.toUsageBadge()) else "—"
        tvTotal.text = if (total > 0) loc(context, total.toUsageBadge()) else "—"
        meta.text = if (stats.lastUsedAt > 0L) {
            context.getString(
                R.string.fork_sub_usage_meta_last,
                JalaliCalendar.formatCompact(context, stats.lastUsedAt)
            )
        } else {
            context.getString(R.string.fork_sub_usage_meta_never)
        }
        Log.d(LOG_TAG, "configUsage open guid=$guid up=${stats.upBytes} down=${stats.downBytes} last=${stats.lastUsedAt}")

        close.setOnClickListener {
            Log.d(LOG_TAG, "configUsage close guid=$guid")
            sheet.dismiss()
        }
        reset.setOnClickListener {
            Log.d(LOG_TAG, "configUsage reset guid=$guid")
            UsageStatsManager.resetOne(guid)
            onChanged()
            sheet.dismiss()
        }

        sheet.show()
    }

    /** Subscription tab re-tap / long-press → menu sheet. */
    fun showSubscriptionMenu(
        activity: Activity,
        subId: String,
        subRemarks: String,
        onUsage: () -> Unit,
        onRename: () -> Unit
    ) {
        val sheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_sub_menu, null)
        sheet.setContentView(view)
        snappyAnimation(sheet)

        view.findViewById<TextView>(R.id.tv_menu_title).text =
            subRemarks.ifBlank { subId.takeLast(8).ifBlank { activity.getString(R.string.fork_sub_usage_title) } }

        view.findViewById<LinearLayout>(R.id.btn_menu_usage).setOnClickListener {
            sheet.dismiss()
            onUsage()
        }
        view.findViewById<LinearLayout>(R.id.btn_menu_rename).setOnClickListener {
            sheet.dismiss()
            onRename()
        }

        val sw = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.sw_auto_update)
        val tvInterval = view.findViewById<TextView>(R.id.tv_interval_value)
        val rowAutoUpdate = view.findViewById<LinearLayout>(R.id.btn_menu_auto_update)
        val rowInterval = view.findViewById<LinearLayout>(R.id.btn_menu_interval)

        fun refresh() {
            val s = MmkvManager.decodeSubscription(subId) ?: return
            sw.isChecked = s.autoUpdate
            tvInterval.text = intervalLabel(activity, s.updateInterval)
            rowInterval.alpha = if (s.autoUpdate) 1f else 0.4f
            rowInterval.isEnabled = s.autoUpdate
        }
        refresh()

        rowAutoUpdate.setOnClickListener {
            val s = MmkvManager.decodeSubscription(subId) ?: return@setOnClickListener
            s.autoUpdate = !s.autoUpdate
            MmkvManager.encodeSubscription(subId, s)
            refresh()
        }
        rowInterval.setOnClickListener {
            val s = MmkvManager.decodeSubscription(subId) ?: return@setOnClickListener
            if (!s.autoUpdate) return@setOnClickListener
            showIntervalPicker(activity, subId) { refresh() }
        }

        sheet.show()
    }

    private fun intervalLabel(ctx: Context, minutes: Long): String = when (minutes) {
        60L -> ctx.getString(R.string.fork_interval_60)
        360L -> ctx.getString(R.string.fork_interval_360)
        1440L -> ctx.getString(R.string.fork_interval_1440)
        10080L -> ctx.getString(R.string.fork_interval_10080)
        else -> ctx.getString(R.string.fork_interval_custom, minutes.toInt())
    }

    private fun showIntervalPicker(activity: Activity, subId: String, onPicked: () -> Unit) {
        val sheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_interval_picker, null)
        sheet.setContentView(view)
        snappyAnimation(sheet)

        val current = MmkvManager.decodeSubscription(subId)?.updateInterval ?: 1440L
        val checks = mapOf(
            60L to view.findViewById<TextView>(R.id.check_60),
            360L to view.findViewById<TextView>(R.id.check_360),
            1440L to view.findViewById<TextView>(R.id.check_1440),
            10080L to view.findViewById<TextView>(R.id.check_10080)
        )
        checks.forEach { (k, v) -> v.text = if (k == current) "✓" else "" }

        val pick: (Long) -> Unit = pick@{ mins ->
            val s = MmkvManager.decodeSubscription(subId) ?: return@pick
            s.updateInterval = mins
            MmkvManager.encodeSubscription(subId, s)
            onPicked()
            sheet.dismiss()
        }
        view.findViewById<LinearLayout>(R.id.opt_60).setOnClickListener { pick(60L) }
        view.findViewById<LinearLayout>(R.id.opt_360).setOnClickListener { pick(360L) }
        view.findViewById<LinearLayout>(R.id.opt_1440).setOnClickListener { pick(1440L) }
        view.findViewById<LinearLayout>(R.id.opt_10080).setOnClickListener { pick(10080L) }

        sheet.show()
    }

    /** Subscription tab menu → Usage → sheet. */
    fun showSubscriptionUsage(
        activity: Activity,
        subId: String,
        subRemarks: String,
        onChanged: () -> Unit
    ) {
        val sheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_sub_usage, null)
        sheet.setContentView(view)
        snappyAnimation(sheet)

        val tvSubname = view.findViewById<TextView>(R.id.tv_sheet_subname)
        val tvCount = view.findViewById<TextView>(R.id.tv_sheet_count)
        val tvUp = view.findViewById<TextView>(R.id.tv_stat_up)
        val tvDown = view.findViewById<TextView>(R.id.tv_stat_down)
        val tvTotal = view.findViewById<TextView>(R.id.tv_stat_total)
        val meta = view.findViewById<TextView>(R.id.tv_sheet_meta)
        val list = view.findViewById<LinearLayout>(R.id.list_breakdown)
        val empty = view.findViewById<TextView>(R.id.tv_sheet_empty)
        val close = view.findViewById<ImageView>(R.id.btn_sheet_close)
        val reset = view.findViewById<TextView>(R.id.btn_reset_all)

        tvSubname.text = subRemarks.ifBlank { subId.takeLast(8) }

        val guids = if (subId.isEmpty()) MmkvManager.decodeAllServerList()
                    else MmkvManager.decodeServerList(subId)

        // Subscription-level carry-over snapshot (preserves usage across refreshes)
        val carry = if (subId.isNotEmpty()) UsageStatsManager.getSubSnapshot(subId)
                    else com.v2ray.ang.dto.UsageStats()

        var up = carry.upBytes
        var down = carry.downBytes
        var lastUsedAt = carry.lastUsedAt
        val rows = mutableListOf<Triple<String, Long, Long>>() // remark, total, lastUsed

        guids.forEach { g ->
            val s = UsageStatsManager.get(g)
            if (s.upBytes == 0L && s.downBytes == 0L) return@forEach
            up += s.upBytes
            down += s.downBytes
            if (s.lastUsedAt > lastUsedAt) lastUsedAt = s.lastUsedAt
            val remark = MmkvManager.decodeServerConfig(g)?.remarks.orEmpty().ifBlank { g.takeLast(6) }
            rows.add(Triple(remark, s.upBytes + s.downBytes, s.lastUsedAt))
        }
        rows.sortByDescending { it.second }

        val total = up + down
        tvUp.text = if (up > 0) loc(activity, up.toUsageBadge()) else "—"
        tvDown.text = if (down > 0) loc(activity, down.toUsageBadge()) else "—"
        tvTotal.text = if (total > 0) loc(activity, total.toUsageBadge()) else "—"
        tvCount.text = loc(activity, activity.getString(R.string.fork_sub_usage_count_n, rows.size))

        meta.text = if (lastUsedAt > 0L) {
            activity.getString(
                R.string.fork_sub_usage_meta_last,
                JalaliCalendar.formatCompact(activity, lastUsedAt)
            )
        } else {
            activity.getString(R.string.fork_sub_usage_meta_never)
        }
        Log.d(LOG_TAG, "subUsage open subId=$subId rows=${rows.size} total=$total last=$lastUsedAt")

        list.removeAllViews()
        if (rows.isEmpty() && carry.upBytes + carry.downBytes == 0L) {
            empty.visibility = View.VISIBLE
        } else {
            val inflater = LayoutInflater.from(activity)
            rows.forEach { (remark, t, _) ->
                val row = inflater.inflate(R.layout.item_sub_usage_row, list, false)
                row.findViewById<TextView>(R.id.tv_row_name).text = remark
                row.findViewById<TextView>(R.id.tv_row_value).text = loc(activity, t.toUsageBadge())
                list.addView(row)
            }
        }

        close.setOnClickListener { sheet.dismiss() }
        reset.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(R.string.fork_sub_usage_reset)
                .setMessage(R.string.fork_sub_usage_reset_confirm)
                .setPositiveButton(android.R.string.ok) { d, _ ->
                    UsageStatsManager.resetAll()
                    onChanged()
                    d.dismiss()
                    sheet.dismiss()
                }
                .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
                .show()
        }
        sheet.show()
    }

    /** Inline edit-text rename dialog (kept lightweight). */
    fun showRename(
        activity: Activity,
        subId: String,
        currentRemark: String,
        onRenamed: () -> Unit
    ) {
        val sheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_rename, null)
        sheet.setContentView(view)
        snappyAnimation(sheet)

        view.findViewById<TextView>(R.id.tv_rename_title).setText(R.string.fork_sub_rename_title)

        val edit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_rename)
        edit.setText(currentRemark)
        edit.setSelection(edit.text?.length ?: 0)
        sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        val commit = {
            val newName = edit.text?.toString()?.trim().orEmpty()
            val sub = MmkvManager.decodeSubscription(subId)
            if (sub != null && newName.isNotEmpty() && newName != currentRemark) {
                sub.remarks = newName
                MmkvManager.encodeSubscription(subId, sub)
                onRenamed()
            }
            sheet.dismiss()
        }

        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                commit(); true
            } else false
        }
        view.findViewById<TextView>(R.id.btn_rename_ok).setOnClickListener { commit() }
        view.findViewById<TextView>(R.id.btn_rename_cancel).setOnClickListener { sheet.dismiss() }

        sheet.show()
    }
}
