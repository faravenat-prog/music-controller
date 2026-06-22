package com.faravenat.musiccontroller

import android.service.notification.NotificationListenerService

class MediaService : NotificationListenerService() {

    companion object {
        var instance: MediaService? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }
}
