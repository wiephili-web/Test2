package de.wlanlogger

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.Writer
import java.util.TimeZone

/**
 * Baut Exportdateien aus den JSONL-Logs. Die JSON-Datei enthält vorne eine
 * vorberechnete Zusammenfassung, damit auch lange Messreihen auswertbar bleiben.
 */
object Exporter {

    val CONN_COLS = listOf(
        "ts", "time", "connected", "ssid", "bssid", "rssi_dbm", "signal_level",
        "link_speed_mbps", "tx_link_speed_mbps", "rx_link_speed_mbps",
        "max_tx_link_speed_mbps", "max_rx_link_speed_mbps", "frequency_mhz", "channel", "band",
        "wifi_standard", "ip", "gateway", "dns", "validated", "down_kbps", "up_kbps",
        "ping_gateway_ms", "ping_internet_ms",
    )
    val SCAN_COLS = listOf(
        "scan_id", "ts", "time", "ssid", "bssid", "rssi_dbm", "frequency_mhz", "channel", "band",
        "channel_width", "center_freq0_mhz", "center_freq1_mhz", "security", "capabilities",
        "wifi_standard", "age_ms", "connected",
    )

    private class Snapshot(dir: File) {
        val conn = File(dir, "connection.jsonl")
        val scan = File(dir, "scans.jsonl")
        val events = File(dir, "events.jsonl")
    }

    private fun exportDir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    private fun snapshot(ctx: Context): Snapshot {
        val d = File(exportDir(ctx), "snap").apply { deleteRecursively(); mkdirs() }
        Store.snapshotTo(d)
        return Snapshot(d)
    }

    private fun cleanOld(ctx: Context) {
        exportDir(ctx).listFiles()?.filter { it.name.startsWith("wlan_") }?.forEach { it.delete() }
    }

    private inline fun forEachJson(f: File, block: (JSONObject) -> Unit) {
        if (!f.exists()) return
        f.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank()) {
                    val o = try { JSONObject(line) } catch (e: JSONException) { null }
                    if (o != null) block(o)
                }
            }
        }
    }

    // ---------------- JSON ----------------

    fun buildJson(ctx: Context): File {
        cleanOld(ctx)
        val snap = snapshot(ctx)
        val out = File(exportDir(ctx), "wlan_export_${Util.stamp()}.json")
        out.bufferedWriter().use { w ->
            w.write("{\n\"meta\": ")
            w.write(meta(ctx).toString(2))
            w.write(",\n\"summary\": ")
            w.write(summarize(snap).toString(2))
            writeArray(w, "connection", snap.conn)
            writeArray(w, "scans", snap.scan)
            writeArray(w, "events", snap.events)
            w.write("\n}\n")
        }
        return out
    }

    private fun writeArray(w: Writer, name: String, f: File) {
        w.write(",\n\"$name\": [")
        var first = true
        forEachJson(f) { o ->
            w.write(if (first) "\n" else ",\n")
            w.write(o.toString())
            first = false
        }
        w.write("\n]")
    }

    private fun meta(ctx: Context): JSONObject {
        val version = try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        val now = System.currentTimeMillis()
        return JSONObject()
            .put("app", "WLAN Logger")
            .putN("app_version", version)
            .put("exported_at", Util.iso(now))
            .put("timezone", TimeZone.getDefault().id)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android_version", Build.VERSION.RELEASE)
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put(
                "settings", JSONObject()
                    .put("conn_interval_s", Prefs.connInterval(ctx))
                    .put("scan_interval_s", Prefs.scanInterval(ctx))
                    .put("ping_enabled", Prefs.pingEnabled(ctx))
                    .put("ping_target", Prefs.pingTarget(ctx))
            )
            .put(
                "field_notes", JSONObject()
                    .put("ts", "Unix-Zeit in ms; time = ISO-8601 lokal")
                    .put("rssi_dbm", "Signalstärke in dBm; näher an 0 = besser (-50 sehr gut, -70 mäßig, -80 schlecht)")
                    .put("signal_level", "0-4 (Android-Balken)")
                    .put("link_speed_mbps", "ausgehandelte PHY-Rate, nicht der echte Durchsatz")
                    .put("ping_*_ms", "RTT eines ICMP-Echos; null = Timeout (2 s) = Paketverlust; fehlt = nicht gemessen")
                    .put("validated", "Android hat Internetzugang bestätigt")
                    .put("down_kbps/up_kbps", "Bandbreitenschätzung von Android, grob")
                    .put("scans.age_ms", "Alter des Scan-Eintrags; große Werte = Ergebnis aus Cache")
                    .put("scans.scan_id", "gleiche ID = gleicher Scan-Durchlauf")
                    .put("events.type", "wifi_connected, wifi_lost, internet_lost, internet_restored, roam, scan_rejected_throttled, logging_started/stopped, error")
            )
    }

    // ---------------- Zusammenfassung ----------------

    private class Stats {
        private val v = ArrayList<Double>()
        fun add(x: Double) { v.add(x) }
        val count get() = v.size
        fun toJson(): JSONObject {
            if (v.isEmpty()) return JSONObject().put("count", 0)
            val s = v.sorted()
            fun pct(p: Double) = s[((s.size - 1) * p).toInt()]
            return JSONObject()
                .put("count", s.size)
                .put("min", Util.round1(s.first()))
                .put("avg", Util.round1(s.average()))
                .put("median", Util.round1(pct(0.5)))
                .put("p95", Util.round1(pct(0.95)))
                .put("max", Util.round1(s.last()))
        }
        fun avg(): Double = if (v.isEmpty()) -200.0 else v.average()
    }

    private class PingStats {
        var sent = 0
        val ok = Stats()
        fun toJson(): JSONObject = JSONObject()
            .put("sent", sent)
            .put("lost", sent - ok.count)
            .put("loss_pct", if (sent == 0) 0.0 else Util.round1(100.0 * (sent - ok.count) / sent))
            .put("rtt_ms", ok.toJson())
    }

    private class Ap(val bssid: String, val ssid: String, val band: String, val channel: Int,
                     val width: String, val security: String, val standard: String) {
        val rssi = Stats()
        val scanIds = HashSet<Long>()
    }

    private fun summarize(snap: Snapshot): JSONObject {
        // Verbindung
        var samples = 0
        var connected = 0
        var firstTs = Long.MAX_VALUE
        var lastTs = 0L
        val rssi = Stats()
        val link = Stats()
        val pingGw = PingStats()
        val pingNet = PingStats()
        val perBssid = HashMap<String, Int>()
        val bands = HashMap<String, Int>()
        forEachJson(snap.conn) { o ->
            samples++
            val ts = o.optLong("ts")
            if (ts < firstTs) firstTs = ts
            if (ts > lastTs) lastTs = ts
            if (o.optBoolean("connected")) {
                connected++
                if (!o.isNull("rssi_dbm")) rssi.add(o.getDouble("rssi_dbm"))
                if (!o.isNull("link_speed_mbps")) link.add(o.getDouble("link_speed_mbps"))
                if (o.has("ping_gateway_ms")) {
                    pingGw.sent++
                    if (!o.isNull("ping_gateway_ms")) pingGw.ok.add(o.getDouble("ping_gateway_ms"))
                }
                if (o.has("ping_internet_ms")) {
                    pingNet.sent++
                    if (!o.isNull("ping_internet_ms")) pingNet.ok.add(o.getDouble("ping_internet_ms"))
                }
                if (!o.isNull("bssid")) perBssid.merge(o.getString("bssid"), 1) { a, b -> a + b }
                if (!o.isNull("band")) bands.merge(o.getString("band"), 1) { a, b -> a + b }
            }
        }
        val conn = JSONObject()
            .put("samples", samples)
            .put("connected_samples", connected)
            .put("connected_pct", if (samples == 0) 0.0 else Util.round1(100.0 * connected / samples))
            .putN("from", if (samples > 0) Util.iso(firstTs) else null)
            .putN("to", if (samples > 0) Util.iso(lastTs) else null)
            .put("duration_min", if (samples > 0) Util.round1((lastTs - firstTs) / 60000.0) else 0.0)
            .put("rssi_dbm", rssi.toJson())
            .put("link_speed_mbps", link.toJson())
            .put("ping_gateway", pingGw.toJson())
            .put("ping_internet", pingNet.toJson())
            .put("samples_per_bssid", JSONObject(perBssid as Map<*, *>))
            .put("samples_per_band", JSONObject(bands as Map<*, *>))

        // Umgebung
        val aps = HashMap<String, Ap>()
        val allScans = HashSet<Long>()
        forEachJson(snap.scan) { o ->
            val id = o.optLong("scan_id")
            allScans.add(id)
            val b = o.optString("bssid", "?")
            val ap = aps.getOrPut(b) {
                Ap(b, o.optString("ssid"), o.optString("band"), o.optInt("channel", -1),
                    o.optString("channel_width"), o.optString("security"), o.optString("wifi_standard"))
            }
            ap.rssi.add(o.optDouble("rssi_dbm", -100.0))
            ap.scanIds.add(id)
        }
        val sorted = aps.values.sortedByDescending { it.rssi.avg() }
        val networks = JSONArray()
        sorted.take(150).forEach { ap ->
            networks.put(
                JSONObject()
                    .put("ssid", ap.ssid).put("bssid", ap.bssid)
                    .put("band", ap.band).put("channel", ap.channel).put("channel_width", ap.width)
                    .put("security", ap.security).put("wifi_standard", ap.standard)
                    .put("seen_in_scans", ap.scanIds.size)
                    .put("seen_pct", if (allScans.isEmpty()) 0.0 else Util.round1(100.0 * ap.scanIds.size / allScans.size))
                    .put("rssi_dbm", ap.rssi.toJson())
            )
        }
        // Belegung pro Kanal: Anzahl APs und stärkstes Durchschnittssignal
        val chan = sorted.groupBy { "${it.band}|${it.channel}" }
            .map { (k, list) ->
                val (band, ch) = k.split("|")
                JSONObject().put("band", band).put("channel", ch.toIntOrNull() ?: -1)
                    .put("ap_count", list.size)
                    .put("strongest_avg_rssi_dbm", Util.round1(list.maxOf { it.rssi.avg() }))
                    .put("ssids", JSONArray(list.map { it.ssid }.distinct().take(20)))
            }
            .sortedWith(compareBy<JSONObject>({ it.getString("band") }, { it.getInt("channel") }))
        val scans = JSONObject()
            .put("scan_rounds", allScans.size)
            .put("unique_bssids", aps.size)
            .put("unique_ssids", aps.values.map { it.ssid }.distinct().size)
            .put("networks_by_avg_rssi", networks)
            .put("channel_usage", JSONArray(chan))

        // Ereignisse
        val counts = HashMap<String, Int>()
        forEachJson(snap.events) { o -> counts.merge(o.optString("type"), 1) { a, b -> a + b } }

        return JSONObject()
            .put("connection", conn)
            .put("environment", scans)
            .put("event_counts", JSONObject(counts as Map<*, *>))
    }

    // ---------------- CSV ----------------

    fun buildCsv(ctx: Context): List<File> {
        cleanOld(ctx)
        val snap = snapshot(ctx)
        val st = Util.stamp()
        val dir = exportDir(ctx)
        val conn = File(dir, "wlan_connection_$st.csv")
        val scan = File(dir, "wlan_scans_$st.csv")
        val ev = File(dir, "wlan_events_$st.csv")
        writeCsv(snap.conn, conn, CONN_COLS)
        writeCsv(snap.scan, scan, SCAN_COLS)
        ev.bufferedWriter().use { w ->
            w.write("ts,time,type,details\n")
            forEachJson(snap.events) { o ->
                val details = JSONObject()
                o.keys().forEach { k -> if (k != "ts" && k != "time" && k != "type") details.put(k, o.get(k)) }
                w.write(listOf(o.optString("ts"), o.optString("time"), o.optString("type"),
                    if (details.length() == 0) "" else details.toString()).joinToString(",") { csv(it) })
                w.write("\n")
            }
        }
        return listOf(conn, scan, ev)
    }

    private fun writeCsv(src: File, dst: File, cols: List<String>) {
        dst.bufferedWriter().use { w ->
            w.write(cols.joinToString(","))
            w.write("\n")
            forEachJson(src) { o ->
                w.write(cols.joinToString(",") { c -> csv(if (o.isNull(c)) "" else o.get(c).toString()) })
                w.write("\n")
            }
        }
    }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
