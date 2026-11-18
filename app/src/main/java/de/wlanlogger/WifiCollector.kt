package de.wlanlogger

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/** Liest Rohwerte aus WifiManager / ConnectivityManager und misst Ping. */
@SuppressLint("MissingPermission")
class WifiCollector(ctx: Context) {
    private val app = ctx.applicationContext
    val wifi: WifiManager = app.getSystemService(WifiManager::class.java)
    private val cm: ConnectivityManager = app.getSystemService(ConnectivityManager::class.java)

    fun requestScan(): Boolean = try {
        @Suppress("DEPRECATION")
        wifi.startScan()
    } catch (e: SecurityException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun connectionInfo(): WifiInfo? = try {
        wifi.connectionInfo
    } catch (e: SecurityException) {
        null
    }

    private fun validBssid(b: String?): Boolean =
        b != null && b != "02:00:00:00:00:00" && b != "00:00:00:00:00:00"

    fun currentBssid(): String? = connectionInfo()?.bssid?.takeIf { validBssid(it) }

    /** Alle sichtbaren Access Points, stärkste zuerst. */
    @Suppress("DEPRECATION")
    fun readScan(): List<JSONObject> {
        val results = try {
            wifi.scanResults ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        val now = System.currentTimeMillis()
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1000
        val connected = currentBssid()
        return results.sortedByDescending { it.level }.map { r ->
            val ssid: String? = r.SSID
            val caps = r.capabilities ?: ""
            JSONObject()
                .put("scan_id", now)
                .put("ts", now)
                .put("time", Util.iso(now))
                .put("ssid", if (ssid.isNullOrEmpty()) "<hidden>" else ssid)
                .putN("bssid", r.BSSID)
                .put("rssi_dbm", r.level)
                .put("frequency_mhz", r.frequency)
                .put("channel", Util.channel(r.frequency))
                .put("band", Util.band(r.frequency))
                .put("channel_width", Util.width(r.channelWidth))
                .put("center_freq0_mhz", r.centerFreq0)
                .put("center_freq1_mhz", r.centerFreq1)
                .put("security", Util.security(caps))
                .put("capabilities", caps)
                .put("wifi_standard", if (Build.VERSION.SDK_INT >= 30) Util.standard(r.wifiStandard) else "unknown")
                .put("age_ms", ((nowUs - r.timestamp) / 1000).coerceAtLeast(0))
                .put("connected", connected != null && connected.equals(r.BSSID, ignoreCase = true))
        }
    }

    /** Eine Messung der aktuellen Verbindung (inkl. optionalem Ping). */
    @Suppress("DEPRECATION")
    fun sampleConnection(network: Network?, pingTarget: String, pingEnabled: Boolean): JSONObject {
        val now = System.currentTimeMillis()
        val o = JSONObject().put("ts", now).put("time", Util.iso(now))
        val info = connectionInfo()
        val connected = network != null || info?.supplicantState == SupplicantState.COMPLETED
        o.put("connected", connected)
        if (!connected) return o

        if (info != null) {
            val ssid = info.ssid?.removeSurrounding("\"")
            o.putN("ssid", ssid?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" })
            o.putN("bssid", info.bssid?.takeIf { validBssid(it) })
            o.put("rssi_dbm", info.rssi)
            o.put("signal_level", WifiManager.calculateSignalLevel(info.rssi, 5))
            o.put("link_speed_mbps", info.linkSpeed)
            if (Build.VERSION.SDK_INT >= 29) {
                o.put("tx_link_speed_mbps", info.txLinkSpeedMbps)
                o.put("rx_link_speed_mbps", info.rxLinkSpeedMbps)
            }
            if (Build.VERSION.SDK_INT >= 30) {
                o.put("max_tx_link_speed_mbps", info.maxSupportedTxLinkSpeedMbps)
                o.put("max_rx_link_speed_mbps", info.maxSupportedRxLinkSpeedMbps)
                o.put("wifi_standard", Util.standard(info.wifiStandard))
            }
            o.put("frequency_mhz", info.frequency)
            o.put("channel", Util.channel(info.frequency))
            o.put("band", Util.band(info.frequency))
        }

        var gateway: String? = null
        if (network != null) {
            val lp = try { cm.getLinkProperties(network) } catch (e: Exception) { null }
            val nc = try { cm.getNetworkCapabilities(network) } catch (e: Exception) { null }
            if (lp != null) {
                val ip = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
                gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
                o.putN("ip", ip)
                o.putN("gateway", gateway)
                o.put("dns", lp.dnsServers.joinToString(";") { it.hostAddress ?: "" })
            }
            if (nc != null) {
                o.put("validated", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                o.put("down_kbps", nc.linkDownstreamBandwidthKbps)
                o.put("up_kbps", nc.linkUpstreamBandwidthKbps)
            }
        }

        if (pingEnabled) {
            // null = kein Echo innerhalb des Timeouts (Paketverlust)
            if (gateway != null) o.putN("ping_gateway_ms", ping(gateway))
            if (pingTarget.isNotBlank()) o.putN("ping_internet_ms", ping(pingTarget.trim()))
        }
        return o
    }

    private val timeRegex = Regex("time[=<]([0-9]+(?:\\.[0-9]+)?)")

    /** Ein ICMP-Echo über das System-ping. Rückgabe: RTT in ms oder null. */
    fun ping(host: String, timeoutSec: Int = 2): Double? = try {
        val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", timeoutSec.toString(), host)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(timeoutSec + 2L, TimeUnit.SECONDS)) {
            p.destroy()
            null
        } else {
            timeRegex.find(out)?.groupValues?.get(1)?.toDoubleOrNull()?.let { Util.round1(it) }
        }
    } catch (e: Exception) {
        null
    }
}
