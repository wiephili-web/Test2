package de.wlanlogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * Löst WLAN-Scans aus und speichert die Ergebnisse, sobald Android sie meldet.
 * Achtung: Android drosselt Apps auf 4 Scans pro 2 Minuten (abschaltbar in den
 * Entwickleroptionen: "WLAN-Scan-Drosselung").
 */
class ScanController(
    private val ctx: Context,
    private val collector: WifiCollector,
    private val io: Executor,
) {
    @Volatile var onResult: ((List<JSONObject>) -> Unit)? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            try {
                io.execute {
                    if (updated) {
                        val rows = collector.readScan()
                        Store.appendScan(rows)
                        LiveState.lastScan = rows
                        LiveState.lastScanTs = System.currentTimeMillis()
                        LiveState.lastScanStored = true
                        onResult?.invoke(rows)
                    } else {
                        Store.event("scan_not_updated")
                    }
                }
            } catch (e: Exception) {
                // Executor bereits beendet – Ergebnis verwerfen
            }
        }
    }

    fun register() {
        if (registered) return
        ContextCompat.registerReceiver(
            ctx, receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        try { ctx.unregisterReceiver(receiver) } catch (e: Exception) { }
        registered = false
    }

    fun trigger(): Boolean {
        val ok = collector.requestScan()
        if (!ok) Store.event("scan_rejected_throttled")
        return ok
    }
}
