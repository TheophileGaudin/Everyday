package com.everyday.everyday_phone

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.everyday.shared.sync.PhoneNotificationItem
import com.everyday.shared.sync.PhoneNotificationsSnapshot

object PhoneNotificationRepository {
    private val lock = Any()
    private val itemsByKey = LinkedHashMap<String, PhoneNotificationItem>()
    private val observers = mutableSetOf<(PhoneNotificationsSnapshot) -> Unit>()

    fun addObserver(observer: (PhoneNotificationsSnapshot) -> Unit, context: Context) {
        val snapshot = synchronized(lock) {
            observers.add(observer)
            snapshotLocked(context)
        }
        observer(snapshot)
    }

    fun removeObserver(observer: (PhoneNotificationsSnapshot) -> Unit) {
        synchronized(lock) {
            observers.remove(observer)
        }
    }

    fun current(context: Context): PhoneNotificationsSnapshot =
        synchronized(lock) { snapshotLocked(context) }

    fun replaceAll(context: Context, items: List<PhoneNotificationItem>) {
        val snapshot = synchronized(lock) {
            itemsByKey.clear()
            items.forEach { item -> itemsByKey[item.key] = item }
            snapshotLocked(context)
        }
        notifyObservers(snapshot)
    }

    fun upsert(context: Context, item: PhoneNotificationItem) {
        val snapshot = synchronized(lock) {
            itemsByKey[item.key] = item
            snapshotLocked(context)
        }
        notifyObservers(snapshot)
    }

    fun remove(context: Context, key: String) {
        val snapshot = synchronized(lock) {
            itemsByKey.remove(key)
            snapshotLocked(context)
        }
        notifyObservers(snapshot)
    }

    fun clearForTests() {
        synchronized(lock) {
            itemsByKey.clear()
            observers.clear()
        }
    }

    fun isListenerEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun fromStatusBarNotification(
        context: Context,
        sbn: StatusBarNotification
    ): PhoneNotificationItem? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras
        val title = firstText(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
        )
        val text = firstText(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
        )
        val subText = firstText(
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            extras.getCharSequence(Notification.EXTRA_INFO_TEXT)
        )
        val item = PhoneNotificationItem(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(context, sbn.packageName),
            title = title,
            text = text,
            subText = subText,
            postTime = sbn.postTime,
            importance = 0,
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            isClearable = sbn.isClearable,
            category = notification.category
        )
        return item.takeIf { shouldShow(it, context.packageName) }
    }

    fun shouldShow(item: PhoneNotificationItem, ownPackageName: String): Boolean {
        if (item.key.isBlank()) return false
        if (item.packageName == ownPackageName) return false
        if (item.previewText.isBlank()) return false
        if (item.packageName in noisyPackages) return false
        if (item.isOngoing && !item.isClearable) return false
        if (item.category == Notification.CATEGORY_SERVICE && item.isOngoing) return false
        return true
    }

    private fun snapshotLocked(context: Context): PhoneNotificationsSnapshot =
        PhoneNotificationsSnapshot(
            items = currentItemsLocked(),
            listenerEnabled = isListenerEnabled(context),
            capturedAtMs = System.currentTimeMillis()
        )

    private fun currentItemsLocked(): List<PhoneNotificationItem> =
        itemsByKey.values.sortedWith(
            compareByDescending<PhoneNotificationItem> { it.postTime }
                .thenBy { it.appLabel.lowercase() }
                .thenBy { it.key }
        )

    private fun notifyObservers(snapshot: PhoneNotificationsSnapshot) {
        val callbacks = synchronized(lock) { observers.toList() }
        callbacks.forEach { it(snapshot) }
    }

    private fun firstText(vararg values: CharSequence?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.toString()?.trim().orEmpty()

    private fun appLabel(context: Context, packageName: String): String {
        val pm = context.packageManager
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.')
        }
    }

    private val noisyPackages = setOf(
        "android",
        "com.android.mtp",
        "com.android.systemui"
    )
}
