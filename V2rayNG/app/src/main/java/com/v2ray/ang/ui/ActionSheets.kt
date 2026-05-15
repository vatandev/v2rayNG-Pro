package com.v2ray.ang.ui

import android.app.Activity
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.v2ray.ang.R

object ActionSheets {

    private const val LOG_TAG = "FORK_UI"

    data class Action(
        val id: String,
        val label: CharSequence,
        @param:DrawableRes val icon: Int? = null,
        val destructive: Boolean = false,
        val selected: Boolean = false,
        val onClick: () -> Unit,
    )

    data class Section(
        val header: CharSequence? = null,
        val actions: List<Action>,
    )

    /** Open an action-list sheet. Title can be empty for a header-less variant. */
    fun show(
        activity: Activity,
        sheetTag: String,
        title: CharSequence?,
        sections: List<Section>,
    ): BottomSheetDialog {
        val sheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_action_list, null)
        sheet.setContentView(view)
        sheet.window?.setWindowAnimations(R.style.BottomSheetSnappyAnimation)

        val tvTitle = view.findViewById<TextView>(R.id.tv_sheet_title)
        val btnClose = view.findViewById<ImageView>(R.id.btn_sheet_close)
        val container = view.findViewById<LinearLayout>(R.id.container_sections)

        if (title.isNullOrEmpty()) {
            tvTitle.visibility = View.GONE
        } else {
            tvTitle.text = title
        }

        val inflater = LayoutInflater.from(activity)
        sections.forEach { section ->
            if (!section.header.isNullOrEmpty()) {
                val header = inflater.inflate(R.layout.item_action_section_header, container, false) as TextView
                header.text = section.header
                container.addView(header)
            }
            section.actions.forEach { action ->
                val row = inflater.inflate(R.layout.item_action_row, container, false)
                val icon = row.findViewById<ImageView>(R.id.iv_action_icon)
                val label = row.findViewById<TextView>(R.id.tv_action_label)
                val check = row.findViewById<ImageView>(R.id.iv_action_check)

                val iconRes = action.icon
                if (iconRes == null) {
                    icon.visibility = View.GONE
                } else {
                    icon.visibility = View.VISIBLE
                    icon.setImageResource(iconRes)
                }
                label.text = action.label
                check.visibility = if (action.selected) View.VISIBLE else View.GONE

                if (action.destructive) {
                    val danger = ContextCompat.getColor(activity, R.color.colorDangerText)
                    label.setTextColor(danger)
                    if (iconRes != null) icon.setColorFilter(danger)
                    label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)
                }
                row.setOnClickListener {
                    Log.d(LOG_TAG, "actionSheet tap sheet=$sheetTag id=${action.id}")
                    sheet.dismiss()
                    action.onClick()
                }
                container.addView(row)
            }
        }

        btnClose.setOnClickListener {
            Log.d(LOG_TAG, "actionSheet close sheet=$sheetTag")
            sheet.dismiss()
        }
        sheet.setOnDismissListener {
            Log.d(LOG_TAG, "actionSheet dismissed sheet=$sheetTag")
        }
        Log.d(LOG_TAG, "actionSheet open sheet=$sheetTag sections=${sections.size}")
        sheet.show()
        return sheet
    }
}
