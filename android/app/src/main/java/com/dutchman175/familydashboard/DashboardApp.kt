package com.dutchman175.familydashboard

import android.app.Application

class DashboardApp : Application() {
    lateinit var calendar: CalendarRepository
        private set

    lateinit var photos: PhotoAlbumRepository
        private set

    lateinit var messages: MessageRepository
        private set

    override fun onCreate() {
        super.onCreate()
        calendar = CalendarRepository(this)
        calendar.start()
        photos = PhotoAlbumRepository(this)
        photos.start()
        messages = MessageRepository(this)
        messages.start()
    }
}
