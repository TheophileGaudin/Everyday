package com.everyday.everyday_phone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class PhoneNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        val snapshot = activeNotifications
            ?.mapNotNull { PhoneNotificationRepository.fromStatusBarNotification(this, it) }
            .orEmpty()
        PhoneNotificationRepository.replaceAll(this, snapshot)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        PhoneNotificationRepository.fromStatusBarNotification(this, sbn)?.let { item ->
            PhoneNotificationRepository.upsert(this, item)
        } ?: PhoneNotificationRepository.remove(this, sbn.key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        PhoneNotificationRepository.remove(this, sbn.key)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        PhoneNotificationRepository.replaceAll(this, emptyList())
    }
}
