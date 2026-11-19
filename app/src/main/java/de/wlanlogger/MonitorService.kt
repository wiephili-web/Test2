package de.wlanlogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Vordergrunddienst: misst die Verbindung im festen Intervall, scannt die
 * Umgebung und protokolliert Ereignisse (Verbindungsverlust, Roaming, Internet weg).
 */
class MonitorService : Service() {

    companion object {
        private const val ACTION_START = "de.wlanlogger.START"
        private const val ACTION_SCAN_NOW = "de.wlanlogger.SCAN_NOW"
        private const val CHANNEL_ID = "monitor"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, MonitorService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }

        fun scanNow(ctx: Context) {
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_SCAN_NOW))
        }
    }

    private var collector: WifiCollector? = null
    private var scanner: ScanController? = null
    private var exec: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }

    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var lastValidated: Boolean? = null
    private var callbackRegistered = false
    private var lastBssid: String? = null
    private var samples = 0L

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            Store.event("wifi_connected")
        }

        override fun onLost(network: Network) {
            if (network == wifiNetwork) wifiNetwork = null
            lastValidated = null
            Store.event("wifi_lost")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val v = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val prev = lastValidated
            if (prev != null && prev != v) Store.event(if (v) "internet_restored" else "internet_lost")
            lastValidated = v
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SCAN_NOW -> {
                if (LiveState.running) scanner?.trigger() else stopSelf()
            }
            else -> if (!LiveState.running) startMonitoring()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification("Logging startet …"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            Store.event("start_failed", JSONObject().put("error", e.toString()))
            stopSelf()
            return
        }

        LiveState.running = true
        LiveState.startedAt = System.currentTimeMillis()

        val col = WifiCollector(this)
        collector = col

        // Ohne WakeLock schläft die CPU bei ausgeschaltetem Display und die Messreihe hat Lücken.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WlanLogger::monitor")
            .apply { setReferenceCounted(false); acquire() }

        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                callback,
            )
            callbackRegistered = true
        } catch (e: Exception) {
            Store.event("network_callback_failed", JSONObject().put("error", e.toString()))
        }

        val connI = Prefs.connInterval(this).coerceAtLeast(1).toLong()
        val scanI = Prefs.scanInterval(this).coerceAtLeast(5).toLong()
        val target = Prefs.pingTarget(this)
        val pingOn = Prefs.pingEnabled(this)
        val devI = Prefs.deviceInterval(this).coerceAtLeast(0).toLong()
        Store.event(
            "logging_started",
            JSONObject()
                .put("conn_interval_s", connI)
                .put("scan_interval_s", scanI)
                .put("ping_enabled", pingOn)
                .put("ping_target", target)
                .put("device_scan_interval_min", devI),
        )

        val ex = Executors.newScheduledThreadPool(3)
        exec = ex
        scanner = ScanController(this, col, ex).also { it.register() }
        ex.scheduleWithFixedDelay({ guarded("sample") { sample(col, target, pingOn) } }, 0, connI, TimeUnit.SECONDS)
        ex.scheduleWithFixedDelay({ guarded("scan") { scanner?.trigger() } }, 1, scanI, TimeUnit.SECONDS)
        if (devI > 0) {
            val app = applicationContext
            ex.scheduleWithFixedDelay(
                { guarded("devices") { if (!LanScanner.busy.get()) LanScanner.runAndStore(app) } },
                15, devI * 60, TimeUnit.SECONDS,
            )
        }
    }

    /** Eine Exception würde sonst alle künftigen Ausführungen des Tasks stillschweigend beenden. */
    private inline fun guarded(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Store.event("error", JSONObject().put("where", name).put("error", t.toString()))
        }
    }

    private fun sample(col: WifiCollector, target: String, pingOn: Boolean) {
        val o = col.sampleConnection(wifiNetwork, target, pingOn)
        Store.appendConnection(o)
        LiveState.lastConnection = o

        val bssid = if (o.isNull("bssid")) null else o.getString("bssid")
        val prev = lastBssid
        if (bssid != null && prev != null && !bssid.equals(prev, ignoreCase = true)) {
            Store.event(
                "roam",
                JSONObject().put("from", prev).put("to", bssid).put("rssi_dbm", o.optInt("rssi_dbm")),
            )
        }
        if (bssid != null) lastBssid = bssid

        samples++
        if (samples % 5 == 1L) {
            val text = if (o.optBoolean("connected")) {
                val ping = if (o.has("ping_internet_ms")) {
                    if (o.isNull("ping_internet_ms")) " · Ping Timeout" else " · Ping ${o.get("ping_internet_ms")} ms"
                } else ""
                "${o.optInt("rssi_dbm")} dBm · ${o.optInt("link_speed_mbps")} Mbit/s$ping · ${Store.connRows.get()} Messungen"
            } else {
                "Nicht verbunden · ${Store.connRows.get()} Messungen"
            }
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    override fun onDestroy() {
        if (LiveState.running) {
            scanner?.unregister()
            exec?.shutdownNow()
            if (callbackRegistered) {
                try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) { }
                callbackRegistered = false
            }
            wakeLock?.let { if (it.isHeld) it.release() }
            LiveState.running = false
            Store.event("logging_stopped")
        }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "WLAN-Logging", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle("WLAN Logger zeichnet auf")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
