package com.cablebee.assistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 前台 Service，在执行 fastboot 命令期间持有前台优先级，
 * 防止 Android 12+ PhantomProcess 机制冻结 App 进程导致 USB 中断。
 */
class FastbootForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID  = "cablebee_fastboot"
        private const val NOTIF_ID    = 9010
        const val ACTION_START = "start"
        const val ACTION_STOP  = "stop"

        fun start(context: Context) {
            val intent = Intent(context, FastbootForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FastbootForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification())
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Fastboot 执行",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "执行 fastboot 命令期间显示" }
            )
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("CableBee")
            .setContentText("正在执行 fastboot 命令…")
            .setOngoing(true)
            .build()
}
