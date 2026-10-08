package io.github.netlab.ui.internal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.netlab.capture.CaptureRecord
import io.github.netlab.capture.CaptureStore
import io.github.netlab.ui.DomainSwitchPanelActivity
import io.github.netlab.ui.R
import io.github.netlab.ui.capture.apiTitle

/**
 * 通知栏常驻入口。
 *
 * 常驻通知本身就是最顺手的入口，既然一直在，就顺便把**最近 5 条请求**显示出来 ——
 * 看一眼通知就知道刚才发出去的是什么、有没有失败，不用点进面板。
 * 这也正是 Monitor 当初的做法。
 */
internal object DomainSwitchUiNotification {

    private const val CHANNEL_ID = "domain_switch"
    private const val NOTIFICATION_ID = 20260929
    private const val MAX_LINES = 5

    /** 抓包高峰期每条请求都会触发两次通知刷新，做个去抖免得刷得太频繁。 */
    private const val DEBOUNCE_MILLIS = 300L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var applicationContext: Context? = null

    private val refreshTask = Runnable { refresh() }

    private val storeListener = CaptureStore.Listener { scheduleRefresh() }

    fun start(context: Context) {
        val application = context.applicationContext
        applicationContext = application
        // 两个 ContentProvider 的创建顺序不确定：本模块的 provider 可能先于 core 的 provider
        // 执行，这时 CaptureStore 还没建好，监听就会挂空、通知永远停在占位文案。
        // initialize 是幂等的，这里顺手补一次，保证无论谁先谁后都能拿到 store。
        CaptureStore.initialize(application)
        createChannel(application)
        CaptureStore.peek()?.addListener(storeListener)
        refresh()
    }

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(refreshTask)
        mainHandler.postDelayed(refreshTask, DEBOUNCE_MILLIS)
    }

    private fun refresh() {
        val context = applicationContext ?: return
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            // areNotificationsEnabled 要 API 24；低版本直接往下走，
            // 系统自己会在未授权时丢弃这次通知，不会出错
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !manager.areNotificationsEnabled()) {
                return
            }

            val records = CaptureStore.peek()?.records().orEmpty().take(MAX_LINES)
            val title = context.getString(R.string.domain_switch_notification_title)
            val placeholder = context.getString(R.string.domain_switch_notification_text)
            val lines = records.map { it.notificationLine }

            val style = Notification.InboxStyle().setBigContentTitle(title)
            lines.drop(n = 1).forEach { line -> style.addLine(line) }

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            val notification = builder
                .setSmallIcon(R.drawable.domain_switch_icon)
                .setContentTitle(title)
                .setContentText(lines.firstOrNull() ?: placeholder)
                .setContentIntent(buildContentIntent(context))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setStyle(style)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.domain_switch_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.domain_switch_channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildContentIntent(context: Context): PendingIntent {
        val intent = Intent(context, DomainSwitchPanelActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * 通知里的一行。
 *
 * 优先用接口说明（编译期从源码注释提取），没有就退回路径 —— 和面板里是同一套优先级，
 * 免得通知和面板显示的"名字"对不上。
 */
private val CaptureRecord.notificationLine: String
    get() {
        val label = apiTitle.ifEmpty { pathWithQuery.ifEmpty { "/" } }
        val status = when (state) {
            CaptureRecord.STATE_REQUESTING -> "…"
            CaptureRecord.STATE_FAILED -> "失败"
            else -> responseCode.toString()
        }
        return "$status · $label"
    }
