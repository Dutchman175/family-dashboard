package com.dutchman175.familydashboard

import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * On-screen switches for idle return. Left and right change the highlighted
 * row. Back closes the screen. The values are the same ones adb can set.
 */
internal class IdleSettingsPanel(root: View) {
    private val panel = root.findViewById<View>(R.id.idle_settings)
    private val returnRow = root.findViewById<TextView>(R.id.idle_return_row)
    private val minutesRow = root.findViewById<TextView>(R.id.idle_minutes_row)
    private val bootRow = root.findViewById<TextView>(R.id.idle_boot_row)
    private val status = root.findViewById<TextView>(R.id.idle_status)
    private val rows = arrayOf(returnRow, minutesRow, bootRow)
    private val settings = IdleSettings(root.context)
    private var row = 0
    private var open = false

    fun isOpen(): Boolean = open && panel != null

    fun show() {
        if (panel == null) {
            return
        }
        open = true
        row = 0
        panel.visibility = View.VISIBLE
        render()
    }

    fun hide() {
        open = false
        if (panel != null) {
            panel.visibility = View.GONE
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (!isOpen()) {
            return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) {
            return true
        }
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || RemoteKeys.isMenu(code)) {
            if (event.repeatCount == 0) {
                hide()
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            if (row > 0) {
                row -= 1
                render()
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (row < rows.size - 1) {
                row += 1
                render()
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val toggle = row == 0 || row == 2
            if (toggle && event.repeatCount > 0) {
                return true
            }
            change(code == KeyEvent.KEYCODE_DPAD_RIGHT)
            render()
            return true
        }
        return true
    }

    private fun change(forward: Boolean) {
        when (row) {
            0 -> settings.setIdleReturn(!settings.idleReturn)
            1 -> {
                val step = if (forward) 1 else -1
                settings.setMinutes(settings.minutes + step)
            }
            else -> settings.setOpenOnBoot(!settings.openOnBoot)
        }
    }

    private fun render() {
        val on = panel.context.getString(R.string.idle_on)
        val off = panel.context.getString(R.string.idle_off)
        returnRow.text = panel.context.getString(
            R.string.idle_return_row,
            if (settings.idleReturn) on else off
        )
        minutesRow.text = panel.context.getString(R.string.idle_minutes_row, settings.minutes)
        bootRow.text = panel.context.getString(
            R.string.idle_boot_row,
            if (settings.openOnBoot) on else off
        )
        val context = panel.context
        val usage = IdleWatch.usageGranted(context)
        val overlay = IdleWatch.overlayGranted(context)
        status.text = when {
            usage && overlay -> context.getString(R.string.idle_ready)
            !usage && !overlay -> context.getString(R.string.idle_need_both)
            !usage -> context.getString(R.string.idle_need_usage)
            else -> context.getString(R.string.idle_need_overlay)
        }
        val gold = ContextCompat.getColor(context, R.color.gold)
        val text = ContextCompat.getColor(context, R.color.text)
        for (i in rows.indices) {
            rows[i].setTextColor(if (i == row) gold else text)
        }
    }
}
