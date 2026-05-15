package com.v2ray.ang.util

import android.content.Context
import android.util.Log
import com.v2ray.ang.AppConfig
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object JalaliCalendar {

    private const val LOG_TAG = "FORK_DATE"

    data class JDate(val year: Int, val month: Int, val day: Int)

    /** Convert (gy, gm, gd) Gregorian → (jy, jm, jd) Jalali. */
    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): JDate {
        val gDaysInMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        val jDaysInMonth = intArrayOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 29)

        val gy2 = gy - 1600
        val gm2 = gm - 1
        val gd2 = gd - 1

        var gDayNo = 365 * gy2 + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400
        for (i in 0 until gm2) gDayNo += gDaysInMonth[i]
        if (gm2 > 1 && ((gy % 4 == 0 && gy % 100 != 0) || gy % 400 == 0)) gDayNo++
        gDayNo += gd2

        var jDayNo = gDayNo - 79

        val jNp = jDayNo / 12053
        jDayNo %= 12053

        var jy = 979 + 33 * jNp + 4 * (jDayNo / 1461)
        jDayNo %= 1461

        if (jDayNo >= 366) {
            jy += (jDayNo - 1) / 365
            jDayNo = (jDayNo - 1) % 365
        }

        var jm = 0
        for (i in 0..10) {
            if (jDayNo < jDaysInMonth[i]) { jm = i + 1; break }
            jDayNo -= jDaysInMonth[i]
        }
        if (jm == 0) jm = 12
        val jd = jDayNo + 1

        return JDate(jy, jm, jd)
    }

    /** Replace ASCII digits with their Persian (ARABIC-INDIC) counterparts. */
    fun toPersianDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(if (c in '0'..'9') ('۰' + (c - '0')) else c)
        }
        return sb.toString()
    }

    /**
     * Format a timestamp as `YYYY/MM/DD · HH:mm` in either Jalali (locale=fa)
     * or Gregorian (other locales). Digits localized to Persian when fa.
     */
    fun formatCompact(context: Context?, ts: Long): String {
        if (ts <= 0L) return ""
        val isFa = isFaLocale(context)
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = ts }
        val gy = cal.get(Calendar.YEAR)
        val gm = cal.get(Calendar.MONTH) + 1
        val gd = cal.get(Calendar.DAY_OF_MONTH)
        val hh = cal.get(Calendar.HOUR_OF_DAY)
        val mm = cal.get(Calendar.MINUTE)

        val out = if (isFa) {
            val j = gregorianToJalali(gy, gm, gd)
            String.format(Locale.US, "%04d/%02d/%02d · %02d:%02d", j.year, j.month, j.day, hh, mm)
        } else {
            String.format(Locale.US, "%04d/%02d/%02d · %02d:%02d", gy, gm, gd, hh, mm)
        }
        val res = if (isFa) toPersianDigits(out) else out
        Log.d(LOG_TAG, "formatCompact ts=$ts isFa=$isFa -> $res")
        return res
    }

    private fun isFaLocale(context: Context?): Boolean {
        val loc = context?.resources?.configuration?.let { cfg ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                cfg.locales[0] else @Suppress("DEPRECATION") cfg.locale
        } ?: Locale.getDefault()
        return loc.language == "fa"
    }

    /** Persian-digit aware version of a numeric string when locale is fa. */
    fun localizeDigits(context: Context?, s: String): String =
        if (isFaLocale(context)) toPersianDigits(s) else s
}
