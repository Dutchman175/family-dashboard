package com.dutchman175.familydashboard

import android.app.Application

class DashboardApp : Application() {
    lateinit var calendar: CalendarRepository
        private set

    lateinit var photos: PhotoAlbumRepository
        private set

    lateinit var messages: MessageRepository
        private set

    lateinit var layouts: LayoutRepository
        private set

    lateinit var updates: UpdateRepository
        private set

    override fun onCreate() {
        super.onCreate()
        calendar = CalendarRepository(this)
        calendar.start()
        photos = PhotoAlbumRepository(this)
        photos.start()
        layouts = LayoutRepository(this)
        messages = MessageRepository(this)
        messages.setControlHandler { seconds, resetLayout ->
            if (seconds != null) {
                photos.setPhotoSeconds(seconds)
            }
            if (resetLayout) {
                layouts.reset()
            }
        }
        messages.start()
        updates = UpdateRepository(this)
        updates.start()
    }
}
