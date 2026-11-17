package com.glebskorobogatov.subscriptiontracker.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.*
import androidx.core.app.NotificationCompat
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.app.MainActivity
import com.glebskorobogatov.subscriptiontracker.data.reminders.*
import com.glebskorobogatov.subscriptiontracker.domain.model.SubscriptionId
import com.glebskorobogatov.subscriptiontracker.domain.reminders.*
import java.time.*
import java.util.UUID

/** OS state is authoritative; a stored permission flag would become stale. */
data class NotificationAccess(val runtimeGranted: Boolean, val appEnabled: Boolean,
    val renewalsEnabled: Boolean) {
    val usable get() = runtimeGranted && appEnabled && renewalsEnabled
    fun allows(message: ReminderMessage) = runtimeGranted && appEnabled && message.reasons.all {
        it.eventType == ReminderEventType.RENEWAL && renewalsEnabled
    }
}
class AndroidReminderWords(context: Context) : ReminderWords {
    private val context = context.applicationContext
    override fun text(key: String, vararg arguments: Any) = context.getString(when (key) {
        "payment_title" -> R.string.notification_payment_title
        "today" -> R.string.notification_today
        "tomorrow" -> R.string.notification_tomorrow
        "in_days" -> R.string.notification_in_days
        "payment_body" -> R.string.notification_payment_body
        else -> error("Unknown notification resource")
    }, *arguments)
    override fun days(value: Long): String = context.getString(when (russianDayForm(value)) {
        RussianDayForm.ONE -> R.string.notification_days_one
        RussianDayForm.FEW -> R.string.notification_days_few
        RussianDayForm.MANY -> R.string.notification_days_many
    }, value)
}

/** Local synchronous adapter. PUBLISHED means API acceptance, never user visibility/read receipt. */
class AndroidReminderPublisher(context: Context) : ReminderPublisher {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    companion object {
        const val PAYMENTS = "subscription_payments_v1"
        private val gate = Any()
        private const val META = "tracker.reminder."
        const val DISMISS_GROUP = "com.glebskorobogatov.subscriptiontracker.DISMISS_REMINDER_GROUP"
        private data class PendingPost(val notification: Notification, val expires: Long)
        // Bounded process-local IPC bookkeeping, not a reminder/data/delivery store.
        private val pendingPosts = linkedMapOf<String, PendingPost>()
        private val cancelled = linkedSetOf<String>()
        fun group(generation: UUID) = "subscription-reminders:$generation"
        fun summaryTag(generation: UUID) = "reminder-summary:v1:$generation"
    }
    fun createChannels() {
        manager.createNotificationChannels(listOf(
            NotificationChannel(PAYMENTS, context.getString(R.string.notification_channel_payments), NotificationManager.IMPORTANCE_DEFAULT)))
    }
    fun access(): NotificationAccess {
        createChannels() // Recreating an existing channel does not replace user importance/sound choices.
        return NotificationAccess(Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            manager.areNotificationsEnabled(), manager.getNotificationChannel(PAYMENTS).importance != NotificationManager.IMPORTANCE_NONE)
    }
    override val available get() = access().usable
    override fun publish(identity: NotificationIdentity, message: ReminderMessage) = PublicationResult.UNAVAILABLE

    @SuppressLint("MissingPermission") // Checked immediately before notify; SecurityException is still handled.
    override fun publish(identity: NotificationIdentity, message: ReminderMessage, presentation: ReminderPresentation): PublicationResult = synchronized(gate) {
        try {
            val route = route(identity.tag) ?: return@synchronized PublicationResult.PERMANENT_SKIP
            require(route.subscription == message.subscriptionId.value && identity.id == 0)
            if (!access().allows(message)) return@synchronized PublicationResult.UNAVAILABLE
            val channel = PAYMENTS
            val copy = ReminderText.render(presentation, message, AndroidReminderWords(context))
            val occurrences = message.reasons.flatMap { it.occurrences }.distinct()
            require(occurrences.size in 1..ReminderRules.MAX_MESSAGE_OCCURRENCES)
            val encoded = occurrences.map { "${it.eventType}|${it.targetDate}|${it.offsetDays}|${it.plannedLocalDateTime}" }
            // 15 bounded calendar occurrences; reject malformed/oversized payload explicitly.
            // Keep metadata well below Binder's limit, even for extended ISO years.
            require(encoded.sumOf { it.toByteArray(Charsets.UTF_8).size } <= 4096)
            val metadata = Bundle().apply {
                putString(META + "route", route.identity)
                putString(META + "day", message.asOfLocalDate.toString())
                putString(META + "presentation", signature(presentation))
                putStringArray(META + "events", encoded.toTypedArray())
            }
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(copy.title).setContentText(copy.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(copy.body))
                .setContentIntent(contentIntent(route)).setDeleteIntent(deleteIntent(route))
                .setAutoCancel(true).setOnlyAlertOnce(true).setGroup(group(route.generation))
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.notification_private)).build())
                .addExtras(metadata).build()
            post(identity.tag, notification)
            // A summary failure must not turn an accepted child into an unsuccessful delivery.
            runCatching { updateSummary(route.generation, added = identity.tag to notification) }
            PublicationResult.PUBLISHED
        } catch (error: RuntimeException) { publicationFailure(error) }
    }
    fun contentIntent(route: NotificationRoute, activityIntent: Intent = Intent(context, MainActivity::class.java)): PendingIntent = PendingIntent.getActivity(context, 0,
        activityIntent.setAction(NotificationRoute.OPEN).setData(route.identity.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun deleteIntent(route: NotificationRoute) = PendingIntent.getBroadcast(context, 0,
        // A user dismissal is a short interactive callback, not deferrable background work.
        Intent(context, ReminderDismissReceiver::class.java).setData(route.identity.toUri())
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override fun reconcilePublished(snapshot: PublishedReminderSnapshot) = synchronized(gate) {
        val rules = ReminderRules()
        val changed = mutableSetOf<UUID>()
        val removed = mutableSetOf<String>()
        currentNotifications().forEach { (tag, notification) ->
            val previous = route(tag) ?: return@forEach
            val valid = runCatching {
                require(previous.generation == snapshot.generation)
                val extras = notification.extras
                require(extras.getString(META + "route") == previous.identity)
                require(extras.getString(META + "day") == snapshot.now.atZone(snapshot.zone).toLocalDate().toString())
                require(extras.getString(META + "presentation") == signature(snapshot.presentations.getValue(previous.subscription)))
                val payload = requireNotNull(extras.getStringArray(META + "events"))
                require(payload.size in 1..ReminderRules.MAX_MESSAGE_OCCURRENCES)
                require(payload.sumOf { it.toByteArray(Charsets.UTF_8).size } <= 4096)
                payload.all { encoded ->
                    val parts = encoded.split('|'); require(parts.size == 4)
                    rules.matchesCurrentSchedule(PlannedReminder(SubscriptionId(previous.subscription), ReminderEventType.valueOf(parts[0]),
                        LocalDate.parse(parts[1]), parts[2].toInt(), LocalDateTime.parse(parts[3])),
                        snapshot.subscriptions, snapshot.time, snapshot.now, snapshot.zone)
                }
            }.getOrDefault(false)
            if (!valid) { cancel(tag); changed += previous.generation; removed += tag }
        }
        (changed + snapshot.generation).forEach { updateSummary(it, removed = removed) }
        // Clean an orphan summary even when no child survived a process restart/user dismissal.
        currentNotifications().keys.filter { it.startsWith("reminder-summary:v1:") }.forEach {
            runCatching { UUID.fromString(it.removePrefix("reminder-summary:v1:")) }.getOrNull()?.let { generation -> updateSummary(generation, removed = removed) }
        }
    }
    fun dismiss(route: NotificationRoute) = synchronized(gate) {
        val tag = ReminderScheduling.identity(route.generation, route.subscription)
        cancel(tag)
        updateSummary(route.generation, removed = setOf(tag))
    }
    fun dismissGroup(generation: UUID) = synchronized(gate) {
        currentNotifications().keys.filter { route(it)?.generation == generation }.forEach(::cancel)
        cancel(summaryTag(generation))
    }
    private fun groupDeleteIntent(generation: UUID) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderDismissReceiver::class.java).setAction(DISMISS_GROUP)
            .setData(NotificationRoute(generation, UUID(0, 0)).identity.toUri()).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** notify() returns before NMS finishes enqueueing. Do not mistake that lag for dismissal.
     * Entries expire in 5s and disappear as soon as the OS echoes the per-post nonce. No timer. */
    private fun currentNotifications(): MutableMap<String, Notification> {
        val actual = manager.activeNotifications.filter { it.tag?.startsWith("reminder:v1:") == true ||
            it.tag?.startsWith("reminder-summary:v1:") == true }.associate { it.tag to it.notification }.toMutableMap()
        val elapsed = SystemClock.elapsedRealtime()
        val iterator = pendingPosts.iterator()
        while (iterator.hasNext()) {
            val (tag, pending) = iterator.next()
            if (pending.expires <= elapsed || actual[tag]?.extras?.getString(META + "post") == pending.notification.extras.getString(META + "post")) iterator.remove()
            else actual[tag] = pending.notification
        }
        cancelled.forEach(actual::remove)
        return actual
    }
    @SuppressLint("MissingPermission") // Publication checks OS permission and still catches SecurityException.
    private fun post(tag: String, notification: Notification) {
        notification.extras.putString(META + "post", UUID.randomUUID().toString())
        manager.notify(tag, 0, notification)
        cancelled.remove(tag)
        pendingPosts[tag] = PendingPost(notification, SystemClock.elapsedRealtime() + 5_000)
        while (pendingPosts.size > 128) pendingPosts.remove(pendingPosts.keys.first())
    }
    private fun cancel(tag: String) {
        manager.cancel(tag, 0)
        pendingPosts.remove(tag)
        cancelled += tag
        while (cancelled.size > 256) cancelled.remove(cancelled.first())
    }
    @SuppressLint("MissingPermission")
    private fun updateSummary(generation: UUID, added: Pair<String, Notification>? = null, removed: Set<String> = emptySet()) {
        val current = currentNotifications()
        val children = current.filter { route(it.key)?.generation == generation && it.key !in removed }.toMutableMap()
        added?.let { children[it.first] = it.second }
        val tag = summaryTag(generation)
        val previous = current[tag]
        if (children.isEmpty()) { cancel(tag); return }
        // Cancelling a group summary can cascade to the surviving child. Keep a one-child
        // summary after a group existed, and remove it only when the group is empty.
        if (children.size == 1 && previous == null) return
        val channel = PAYMENTS
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(channel).importance == NotificationManager.IMPORTANCE_NONE) return
        val members = digest(children.toSortedMap().map { (key, child) -> "$key|${child.extras.getCharSequence(Notification.EXTRA_TITLE)}" }.joinToString("\n"))
        if (previous?.channelId == channel && previous.extras.getString(META + "members") == members) return
        post(tag, NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.notification_group_title))
            .setContentText(context.getString(R.string.notification_group_count, children.size))
            .setStyle(NotificationCompat.InboxStyle().also { style ->
                children.toSortedMap().values.take(5).forEach { child ->
                    style.addLine(child.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
                }
            })
            .addExtras(Bundle().apply { putString(META + "members", members) })
            .setPublicVersion(NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.notification_private)).build())
            .setDeleteIntent(groupDeleteIntent(generation))
            .setGroup(group(generation)).setGroupSummary(true).setOnlyAlertOnce(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
    private fun signature(p: ReminderPresentation) = digest("${p.name}|${p.price.amountMinor}|${p.price.currency.value}")
    private fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun route(tag: String?): NotificationRoute? {
        if (tag == null || !tag.startsWith("reminder:v1:")) return null
        val parts = tag.split(':')
        return if (parts.size == 4) NotificationRoute.parse("tracker-reminder://subscription/v1/${parts[2]}/${parts[3]}") else null
    }
}
class ReminderDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationRoute.parse(intent.dataString)?.let { route -> runCatching {
            val publisher = AndroidReminderPublisher(context)
            if (intent.action == AndroidReminderPublisher.DISMISS_GROUP && route.subscription == UUID(0, 0)) publisher.dismissGroup(route.generation)
            else publisher.dismiss(route)
        } }
    }
}

/** No permission-denial path acknowledges delivery. Binder failures retain F-02's bounded retry. */
internal fun publicationFailure(error: RuntimeException): PublicationResult = when (error) {
    is SecurityException -> PublicationResult.UNAVAILABLE
    is IllegalArgumentException -> PublicationResult.PERMANENT_SKIP
    else -> PublicationResult.RETRYABLE_FAILURE
}
