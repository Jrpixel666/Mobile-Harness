package com.jarves.mh.runtime

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarves.mh.MainActivity
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings

/**
 * Keeps the app, and with it the llama-server child process, out of Android's background freezer
 * while an on-device model is loaded, so warm-up and replies continue in other apps.
 */
class LocalModelService : Service() {
    override fun onCreate() {
        super.onCreate()
        RuntimeExecutionService.ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LocalModelServer.active?.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_MODEL).orEmpty()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(title),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        return START_NOT_STICKY
    }

    private fun notification(model: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            4,
            Intent(this, LocalModelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(AppStrings.get(this, R.string.local_service_title))
            .setContentText(AppStrings.get(this, R.string.local_service_text, model))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, AppStrings.get(this, R.string.local_service_stop), stop)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_STOP = "com.jarves.mh.STOP_LOCAL_MODEL"
        private const val EXTRA_MODEL = "model"
        // Shares the running-work channel created by RuntimeExecutionService.
        private const val RUNNING_CHANNEL_ID = "runtime"
        private const val NOTIFICATION_ID = 43

        fun start(context: Context, model: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LocalModelService::class.java).putExtra(EXTRA_MODEL, model),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LocalModelService::class.java)) }
        }
    }
}
