package de.wlanlogger

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** put() mit explizitem JSON-null, damit jedes Feld in jedem Datensatz existiert. */
fun JSONObject.putN(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

object Util {
    private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val SHORT = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(ISO)
    fun short(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(SHORT)
    fun stamp(ms: Long = System.currentTimeMillis()): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(STAMP)

    fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    /** Kanalnummer aus Mittenfrequenz (MHz). -1 = unbekannt. */
    fun channel(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5160..5885 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5
        else -> -1
    }

    fun band(freq: Int): String = when (freq) {
        in 2400..2500 -> "2.4GHz"
        in 4900..5900 -> "5GHz"
        in 5925..7125 -> "6GHz"
        in 57000..71000 -> "60GHz"
        else -> "unknown"
    }

    /** ScanResult.CHANNEL_WIDTH_* -> MHz als Text. */
    fun width(code: Int): String = when (code) {
        0 -> "20"
        1 -> "40"
        2 -> "80"
        3 -> "160"
        4 -> "80+80"
        5 -> "320"
        else -> "unknown"
    }

    /** ScanResult/WifiInfo.WIFI_STANDARD_* -> Klartext. */
    fun standard(code: Int): String = when (code) {
        1 -> "legacy (a/b/g)"
        4 -> "802.11n (WiFi 4)"
        5 -> "802.11ac (WiFi 5)"
        6 -> "802.11ax (WiFi 6/6E)"
        7 -> "802.11ad"
        8 -> "802.11be (WiFi 7)"
        else -> "unknown"
    }

    fun security(cap: String): String = when {
        "SAE" in cap && "PSK" in cap -> "WPA2/WPA3"
        "SAE" in cap -> "WPA3"
        "EAP" in cap -> "Enterprise"
        "PSK" in cap -> "WPA/WPA2"
        "WEP" in cap -> "WEP"
        "OWE" in cap -> "OWE"
        else -> "offen"
    }

    fun bytes(b: Long): String = when {
        b >= 1_048_576 -> String.format(Locale.GERMANY, "%.1f MB", b / 1_048_576.0)
        b >= 1024 -> String.format(Locale.GERMANY, "%.0f kB", b / 1024.0)
        else -> "$b B"
    }
}

/** ICMP über das System-ping (funktioniert ohne Root). */
object Net {
    private val timeRegex = Regex("time[=<]([0-9]+(?:\\.[0-9]+)?)")
    private val recvRegex = Regex("([0-9]+) (?:packets )?received")

    class PingResult(val sent: Int, val received: Int, val times: List<Double>)

    /** Ein Echo. Rückgabe: RTT in ms oder null (Timeout). */
    fun ping(host: String, timeoutSec: Int = 2): Double? =
        pingStats(host, 1, timeoutSec).times.firstOrNull()

    /** [count] Echos im Abstand von 0,2 s (Minimum ohne Root). */
    fun pingStats(host: String, count: Int, timeoutSec: Int = 1): PingResult = try {
        val args = mutableListOf("/system/bin/ping", "-c", count.toString(), "-W", timeoutSec.toString())
        if (count > 1) args += listOf("-i", "0.2")
        args += host
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(timeoutSec + count + 3L, java.util.concurrent.TimeUnit.SECONDS)) p.destroy()
        val times = timeRegex.findAll(out).mapNotNull { it.groupValues[1].toDoubleOrNull() }.map { Util.round1(it) }.toList()
        val recv = recvRegex.find(out)?.groupValues?.get(1)?.toIntOrNull() ?: times.size
        PingResult(count, recv, times)
    } catch (e: Exception) {
        PingResult(count, 0, emptyList())
    }
}
