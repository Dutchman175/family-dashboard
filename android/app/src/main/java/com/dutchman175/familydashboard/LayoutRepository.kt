package com.dutchman175.familydashboard

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the arranged boxes in the same private preferences as the calendar
 * link, the album, the message topic, and the photo timing. This only writes
 * its own key, so an upgrade keeps those settings.
 */
class LayoutRepository(context: Context) {
    fun interface LayoutListener {
        fun onLayout()
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val listeners = ArrayList<LayoutListener>()
    private val listenerLock = Any()

    @Volatile
    var current: DashboardGrid.Layout = load()
        private set

    fun reset() {
        prefs.edit().remove(KEY_LAYOUT).commit()
        current = DashboardGrid.defaultLayout()
        notifyListeners()
    }

    fun save(layout: DashboardGrid.Layout) {
        if (!DashboardGrid.fits(layout)) {
            return
        }
        prefs.edit().putString(KEY_LAYOUT, DashboardGrid.encode(layout)).commit()
        current = layout
        notifyListeners()
    }

    fun addListener(listener: LayoutListener) {
        synchronized(listenerLock) {
            listeners.add(listener)
        }
        listener.onLayout()
    }

    fun removeListener(listener: LayoutListener) {
        synchronized(listenerLock) {
            listeners.remove(listener)
        }
    }

    private fun load(): DashboardGrid.Layout {
        return DashboardGrid.parse(prefs.getString(KEY_LAYOUT, null)) ?: DashboardGrid.defaultLayout()
    }

    private fun notifyListeners() {
        val copy = synchronized(listenerLock) { ArrayList(listeners) }
        for (listener in copy) {
            listener.onLayout()
        }
    }

    private companion object {
        const val PREFS = "family_dashboard"
        const val KEY_LAYOUT = "dashboard_layout"
    }
}
