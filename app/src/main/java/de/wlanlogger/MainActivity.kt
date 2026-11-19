package de.wlanlogger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERM = 1
        private const val REQ_SAVE = 2
        private const val MAX_SCAN_LINES = 40
        private const val LARGE_FILE = 10L * 1024 * 1024
    }

    private val ui = Handler(Looper.getMainLooper())
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var tvStatus: TextView
    private lateinit var tvConn: TextView
    private lateinit var tvScan: TextView
    private lateinit var tvEvents: TextView
    private lateinit var tvStore: TextView
    private lateinit var tvDevices: TextView
    private lateinit var etDev: EditText
    private lateinit var etConn: EditText
    private lateinit var etScan: EditText
    private lateinit var etTarget: EditText
    private lateinit var cbPing: CheckBox
    private lateinit var btnToggle: Button

    private var pendingAfterPerm: (() -> Unit)? = null
    private var pendingSaveFile: File? = null

    private var oneShot: ScanController? = null
    private var oneShotExec: ExecutorService? = null
    private val oneShotTimeout = Runnable { cleanupOneShot() }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvConn = findViewById(R.id.tvConn)
        tvScan = findViewById(R.id.tvScan)
        tvEvents = findViewById(R.id.tvEvents)
        tvStore = findViewById(R.id.tvStore)
        tvDevices = findViewById(R.id.tvDevices)
        etDev = findViewById(R.id.etDev)
        etConn = findViewById(R.id.etConn)
        etScan = findViewById(R.id.etScan)
        etTarget = findViewById(R.id.etTarget)
        cbPing = findViewById(R.id.cbPing)
        btnToggle = findViewById(R.id.btnToggle)

        etConn.setText(Prefs.connInterval(this).toString())
        etScan.setText(Prefs.scanInterval(this).toString())
        etTarget.setText(Prefs.pingTarget(this))
        cbPing.isChecked = Prefs.pingEnabled(this)
        etDev.setText(Prefs.deviceInterval(this).toString())

        btnToggle.setOnClickListener {
            if (LiveState.running) {
                MonitorService.stop(this)
            } else if (saveSettings()) {
                withPermissions { MonitorService.start(this) }
            }
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            withPermissions {
                if (LiveState.running) MonitorService.scanNow(this) else oneShotScan()
            }
        }
        findViewById<Button>(R.id.btnDevices).setOnClickListener {
            if (LanScanner.busy.get()) {
                toast("Geräte-Scan läuft bereits")
            } else {
                toast("Suche Geräte im Heimnetz … dauert ca. 20–60 s")
                val app = applicationContext
                Thread {
                    val r = runCatching { LanScanner.runAndStore(app) }
                    ui.post {
                        r.onSuccess { toast("${it.size} Geräte gefunden") }
                        r.onFailure { toast("Geräte-Scan fehlgeschlagen: ${it.message}") }
                    }
                }.start()
            }
        }
        findViewById<Button>(R.id.btnShareJson).setOnClickListener { exportJson(save = false) }
        findViewById<Button>(R.id.btnSaveJson).setOnClickListener { exportJson(save = true) }
        findViewById<Button>(R.id.btnShareCsv).setOnClickListener { exportCsv() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Alle Daten löschen?")
                .setMessage("Alle gespeicherten Messungen, Scans und Ereignisse werden unwiderruflich gelöscht.")
                .setPositiveButton("Löschen") { _, _ ->
                    bg.execute {
                        Store.clear()
                        LiveState.lastScan = emptyList()
                        LiveState.lastConnection = null
                        LiveState.lastDevices = emptyList()
                        LiveState.lastDevicesTs = 0L
                        ui.post { toast("Daten gelöscht") }
                    }
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        cleanupOneShot()
        bg.shutdown()
        super.onDestroy()
    }

    // ---------------- Einstellungen ----------------

    private fun saveSettings(): Boolean {
        val conn = etConn.text.toString().trim().toIntOrNull()
        val scan = etScan.text.toString().trim().toIntOrNull()
        if (conn == null || conn < 1) { toast("Messintervall muss ≥ 1 s sein"); return false }
        if (scan == null || scan < 5) { toast("Scan-Intervall muss ≥ 5 s sein"); return false }
        val dev = etDev.text.toString().trim().toIntOrNull()
        if (dev == null || dev < 0) { toast("Geräte-Scan-Intervall muss ≥ 0 sein"); return false }
        if (scan < 30) toast("Hinweis: Android lässt nur ca. 4 Scans pro 2 Minuten zu")
        Prefs.save(this, conn, scan, etTarget.text.toString().trim(), cbPing.isChecked, dev)
        return true
    }

    // ---------------- Berechtigungen ----------------

    private fun missingPermissions(): List<String> {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.NEARBY_WIFI_DEVICES
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun withPermissions(action: () -> Unit) {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            checkSystemState(action)
            return
        }
        pendingAfterPerm = action
        requestPermissions(missing.toTypedArray(), REQ_PERM)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        val action = pendingAfterPerm
        pendingAfterPerm = null
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setTitle("Genauer Standort nötig")
                .setMessage(
                    "Android gibt WLAN-Scanergebnisse sowie SSID/BSSID nur mit der Berechtigung " +
                        "„Genauer Standort“ heraus. Die App sendet keine Standortdaten irgendwohin."
                )
                .setPositiveButton("App-Einstellungen") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("Abbrechen", null)
                .show()
            return
        }
        action?.let { checkSystemState(it) }
    }

    private fun checkSystemState(action: () -> Unit) {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        if (!wifi.isWifiEnabled) toast("WLAN ist ausgeschaltet – es werden kaum Daten erfasst")

        val lm = getSystemService(LocationManager::class.java)
        val locationOn = if (Build.VERSION.SDK_INT >= 28) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        if (!locationOn) {
            AlertDialog.Builder(this)
                .setTitle("Standort ist ausgeschaltet")
                .setMessage("Ohne eingeschalteten Standort liefert Android keine Scanergebnisse und keine SSID.")
                .setPositiveButton("Einschalten") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Trotzdem") { _, _ -> action() }
                .show()
            return
        }
        action()
    }

    // ---------------- Einzel-Scan ohne laufendes Logging ----------------

    private fun oneShotScan() {
        cleanupOneShot()
        val ex = Executors.newSingleThreadExecutor()
        val collector = WifiCollector(this)
        val sc = ScanController(this, collector, ex)
        oneShotExec = ex
        oneShot = sc
        sc.onResult = { rows -> ui.post { toast("${rows.size} Netzwerke gefunden"); cleanupOneShot() } }
        sc.register()
        if (sc.trigger()) {
            toast("Scan läuft …")
            ui.postDelayed(oneShotTimeout, 20_000)
        } else {
            // Gedrosselt: zwischengespeicherte Ergebnisse nur anzeigen, nicht als neue Messung speichern.
            ex.execute {
                LiveState.lastScan = collector.readScan()
                LiveState.lastScanTs = System.currentTimeMillis()
                LiveState.lastScanStored = false
            }
            toast("Android hat den Scan gedrosselt – zeige zwischengespeicherte Ergebnisse")
            ui.postDelayed(oneShotTimeout, 1_000)
        }
    }

    private fun cleanupOneShot() {
        ui.removeCallbacks(oneShotTimeout)
        oneShot?.unregister()
        oneShot = null
        oneShotExec?.shutdown()
        oneShotExec = null
    }

    // ---------------- Export ----------------

    private fun exportJson(save: Boolean) {
        toast("Export wird erstellt …")
        bg.execute {
            val result = runCatching { Exporter.buildJson(this) }
            ui.post {
                result.onFailure { toast("Export fehlgeschlagen: ${it.message}") }
                result.onSuccess { file ->
                    if (file.length() > LARGE_FILE) {
                        toast("Datei ist ${Util.bytes(file.length())} groß. Für Claude reicht oft der Abschnitt „summary“.")
                    }
                    if (save) {
                        pendingSaveFile = file
                        @Suppress("DEPRECATION")
                        startActivityForResult(
                            Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("application/json")
                                .putExtra(Intent.EXTRA_TITLE, file.name),
                            REQ_SAVE,
                        )
                    } else {
                        share(listOf(file), "application/json")
                    }
                }
            }
        }
    }

    private fun exportCsv() {
        toast("Export wird erstellt …")
        bg.execute {
            val result = runCatching { Exporter.buildCsv(this) }
            ui.post {
                result.onFailure { toast("Export fehlgeschlagen: ${it.message}") }
                result.onSuccess { files -> share(files, "text/csv") }
            }
        }
    }

    @Deprecated("Platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE) return
        val file = pendingSaveFile
        pendingSaveFile = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || file == null) return
        bg.execute {
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: error("Ziel nicht beschreibbar")
            }
            ui.post { toast(if (ok.isSuccess) "Gespeichert" else "Speichern fehlgeschlagen: ${ok.exceptionOrNull()?.message}") }
        }
    }

    private fun share(files: List<File>, mime: String) {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = mime
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = ClipData.newRawUri("WLAN-Daten", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        send.clipData = clip
        startActivity(Intent.createChooser(send, "WLAN-Daten teilen"))
    }

    // ---------------- Anzeige ----------------

    private fun render() {
        val running = LiveState.running
        btnToggle.text = if (running) "Logging stoppen" else "Logging starten"
        tvStatus.text = if (running) "● Logging läuft seit ${Util.short(LiveState.startedAt)}" else "○ Logging gestoppt"
        listOf(etConn, etScan, etTarget, etDev, cbPing).forEach { it.isEnabled = !running }
        tvConn.text = formatConn(LiveState.lastConnection)
        tvScan.text = formatScan(LiveState.lastScan, LiveState.lastScanTs)
        tvDevices.text = formatDevices(LiveState.lastDevices, LiveState.lastDevicesTs, LiveState.deviceProgress)
        tvEvents.text = LiveState.events().joinToString("\n").ifEmpty { "–" }
        tvStore.text = "Gespeichert: ${Store.connRows.get()} Verbindungsmessungen · " +
            "${Store.scanRows.get()} Scan-Einträge · ${Store.deviceRows.get()} Geräte-Einträge · " +
            "${Store.eventRows.get()} Ereignisse · " +
            Util.bytes(Store.totalBytes())
    }

    private fun formatConn(o: JSONObject?): String {
        if (o == null) return "Noch keine Messung – Logging starten."
        val t = Util.short(o.optLong("ts"))
        if (!o.optBoolean("connected")) return "$t  Nicht mit einem WLAN verbunden"
        fun v(k: String, unit: String = ""): String = if (o.isNull(k)) "–" else o.get(k).toString() + unit
        fun ping(k: String): String = when {
            !o.has(k) -> "–"
            o.isNull(k) -> "Timeout"
            else -> o.get(k).toString() + " ms"
        }
        return buildString {
            appendLine("Zeit       $t")
            appendLine("SSID       ${v("ssid")}")
            appendLine("BSSID      ${v("bssid")}")
            appendLine("Signal     ${v("rssi_dbm", " dBm")}  (Stufe ${v("signal_level")}/4)")
            appendLine("Band/Kanal ${v("band")} / ${v("channel")}  (${v("frequency_mhz", " MHz")})")
            appendLine("Standard   ${v("wifi_standard")}")
            appendLine("Linkrate   ${v("link_speed_mbps", " Mbit/s")}  TX ${v("tx_link_speed_mbps")} / RX ${v("rx_link_speed_mbps")}")
            appendLine("IP / GW    ${v("ip")} / ${v("gateway")}")
            appendLine("Internet   ${if (o.optBoolean("validated")) "bestätigt" else "nicht bestätigt"}")
            append("Ping       Router ${ping("ping_gateway_ms")} · Internet ${ping("ping_internet_ms")}")
        }
    }

    private fun formatScan(rows: List<JSONObject>, ts: Long): String {
        if (ts == 0L) return "Noch kein Scan – „Jetzt scannen“ tippen."
        val head = "${Util.short(ts)} · ${rows.size} Netzwerke" +
            (if (LiveState.lastScanStored) "" else " (Cache, nicht gespeichert)") + "\n" +
            "  dBm  Band   Kan  Sicherheit  SSID\n"
        val body = rows.take(MAX_SCAN_LINES).joinToString("\n") { r ->
            String.format(
                Locale.ROOT, "%s%4d  %-6s %3d  %-10s  %s",
                if (r.optBoolean("connected")) "*" else " ",
                r.optInt("rssi_dbm"), r.optString("band"), r.optInt("channel"),
                r.optString("security"), r.optString("ssid"),
            )
        }
        val more = if (rows.size > MAX_SCAN_LINES) "\n… ${rows.size - MAX_SCAN_LINES} weitere" else ""
        return head + body + more + "\n(* = verbunden)"
    }

    private fun formatDevices(rows: List<JSONObject>, ts: Long, progress: String): String {
        val sb = StringBuilder()
        if (progress.isNotEmpty()) sb.append("⏳ ").append(progress).append("\n\n")
        if (ts == 0L) {
            if (progress.isEmpty()) sb.append("Noch kein Geräte-Scan – „Heimnetz-Geräte suchen“ tippen.")
            return sb.toString()
        }
        sb.append("${Util.short(ts)} · ${rows.size} Geräte\n")
        rows.forEach { r ->
            val label = listOfNotNull(
                if (r.isNull("role")) null else r.getString("role"),
                if (r.isNull("type_guess")) null else r.getString("type_guess"),
            ).joinToString(" · ").ifEmpty { "unbekannt" }
            val name = LanScanner.displayName(r)
            sb.append("\n").append(r.optString("ip")).append("  ").append(label).append("\n")
            if (name.isNotEmpty()) sb.append("   ").append(name).append("\n")
            val model = listOfNotNull(
                if (r.isNull("upnp_manufacturer")) null else r.getString("upnp_manufacturer"),
                if (r.isNull("upnp_model")) null else r.getString("upnp_model"),
            ).joinToString(" ")
            if (model.isNotEmpty()) sb.append("   ").append(model).append("\n")
            val ping = if (r.isNull("ping_avg_ms")) "kein Ping" else
                "Ping ${r.get("ping_avg_ms")} ms (max ${r.opt("ping_max_ms")}), Verlust ${r.opt("ping_loss_pct")} %"
            sb.append("   ").append(ping)
            if (!r.isNull("mac")) sb.append(" · MAC ").append(r.getString("mac"))
            sb.append("\n")
            val ports = r.optString("open_ports")
            if (ports.isNotEmpty()) sb.append("   Ports ").append(ports).append("\n")
        }
        return sb.toString()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
