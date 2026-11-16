package no.lionflow.app.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import no.lionflow.app.R

/**
 * Keeps the phone's GPS available while LionFlow draws the map on the driver display. Without a running
 * foreground service, Android stops giving location to an app the person isn't looking at, and the map freezes.
 */
class CarLocationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Driver display map", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("LionFlow").setContentText("Showing the map on the driver display")
            .setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(ID, n)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "driver_display"
        private const val ID = 42
        fun start(ctx: Context) { try { ContextCompat.startForegroundService(ctx, Intent(ctx, CarLocationService::class.java)) } catch (_: Exception) { } }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, CarLocationService::class.java)) } catch (_: Exception) { } }
    }
}
