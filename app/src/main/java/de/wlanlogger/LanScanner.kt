package de.wlanlogger

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Findet Geräte im eigenen Heimnetz und sammelt alles, was ohne Root erreichbar ist:
 * Ping-Sweep + TCP-Probe, Ports, Reverse-DNS (Router-Namen), NetBIOS (Windows/NAS, inkl. MAC),
 * mDNS/Bonjour (Apple, Chromecast, Drucker, Smart Home), SSDP/UPnP (TVs, Router, Media).
 *
 * Android 10+ verbietet Apps das Lesen der ARP-Tabelle; MAC-Adressen gibt es daher
 * nur, wenn NetBIOS sie liefert oder das Gerät ein älteres Android hat.
 */
class LanScanner(ctx: Context) {

    companion object {
        val busy = AtomicBoolean(false)

        private val QUICK_PORTS = intArrayOf(80, 443, 445, 62078)

        val PORTS: Map<Int, String> = linkedMapOf(
            21 to "ftp", 22 to "ssh", 23 to "telnet", 53 to "dns", 80 to "http", 139 to "netbios",
            443 to "https", 445 to "smb", 515 to "lpd", 554 to "rtsp", 631 to "ipp", 1883 to "mqtt",
            3389 to "rdp", 5000 to "upnp/nas", 7000 to "airplay", 8008 to "cast", 8009 to "cast",
            8080 to "http-alt", 8443 to "https-alt", 9100 to "printer", 49000 to "fritz-tr064",
            62078 to "apple-sync",
        )

        private val MDNS_TYPES = listOf(
            "_services._dns-sd._udp.local", "_googlecast._tcp.local", "_airplay._tcp.local",
            "_raop._tcp.local", "_companion-link._tcp.local", "_hap._tcp.local", "_homekit._tcp.local",
            "_ipp._tcp.local", "_printer._tcp.local", "_pdl-datastream._tcp.local", "_smb._tcp.local",
            "_workstation._tcp.local", "_device-info._tcp.local", "_spotify-connect._tcp.local",
            "_sonos._tcp.local", "_amzn-wplay._tcp.local", "_hue._tcp.local", "_http._tcp.local",
            "_esphomelib._tcp.local", "_matter._tcp.local", "_androidtvremote2._tcp.local",
        )

        private val TXT_KEYS = listOf(
            "md=", "fn=", "model=", "ty=", "am=", "rpMd=", "manufacturer=", "product=",
            "usb_MFG=", "usb_MDL=", "deviceid=", "mn=", "ve=", "friendly_name=",
        )

        /** Scan ausführen, speichern, Änderungen gegenüber dem letzten Scan als Ereignis loggen. */
        fun runAndStore(ctx: Context): List<JSONObject> {
            val rows = LanScanner(ctx).scan { LiveState.deviceProgress = it }
            val prev = if (LiveState.lastDevicesTs > 0) LiveState.lastDevices.associateBy { it.optString("ip") } else null
            Store.appendDevices(rows)
            LiveState.lastDevices = rows
            LiveState.lastDevicesTs = System.currentTimeMillis()
            Store.event("device_scan", JSONObject().put("devices", rows.size))
            if (prev != null) {
                val now = rows.associateBy { it.optString("ip") }
                (now.keys - prev.keys).forEach { ip ->
                    Store.event("device_new", JSONObject().put("ip", ip).put("name", displayName(now.getValue(ip))))
                }
                (prev.keys - now.keys).forEach { ip ->
                    Store.event("device_gone", JSONObject().put("ip", ip).put("name", displayName(prev.getValue(ip))))
                }
            }
            return rows
        }

        fun displayName(o: JSONObject): String {
            for (k in listOf("upnp_name", "hostname", "netbios_name", "mdns_names")) {
                if (!o.isNull(k)) {
                    val v = o.getString(k).substringBefore(" | ").trim()
                    if (v.isNotEmpty()) return v
                }
            }
            return ""
        }
    }

    private val app = ctx.applicationContext
    private val cm: ConnectivityManager = app.getSystemService(ConnectivityManager::class.java)
    private val wifi: WifiManager = app.getSystemService(WifiManager::class.java)

    private class Dev(val ip: String) {
        val via = LinkedHashSet<String>()
        val ports = TreeSet<Int>()
        var pingSent = 0
        var pingRecv = 0
        var pingAvg: Double? = null
        var pingMax: Double? = null
        var hostname: String? = null
        var netbios: String? = null
        var mac: String? = null
        val mdnsNames = LinkedHashSet<String>()
        val mdnsServices = LinkedHashSet<String>()
        val mdnsTxt = LinkedHashSet<String>()
        var ssdpServer: String? = null
        val locations = LinkedHashSet<String>()
        var upnpName: String? = null
        var upnpManufacturer: String? = null
        var upnpModel: String? = null
        var upnpType: String? = null
    }

    private val devs = ConcurrentHashMap<String, Dev>()

    private fun dev(ip: String): Dev = devs.getOrPut(ip) { Dev(ip) }

    private fun mark(ip: String, via: String) {
        val d = dev(ip)
        synchronized(d) { d.via.add(via) }
    }

    fun scan(progress: (String) -> Unit = {}): List<JSONObject> {
        if (!busy.compareAndSet(false, true)) throw IllegalStateException("Es läuft bereits ein Geräte-Scan")
        try {
            return doScan(progress)
        } finally {
            busy.set(false)
            progress("")
        }
    }

    @Suppress("DEPRECATION")
    private fun findWifi(): Network? {
        val active = cm.activeNetwork
        if (active != null && cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            return active
        }
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun doScan(progress: (String) -> Unit): List<JSONObject> {
        val net = findWifi() ?: throw IllegalStateException("Nicht mit einem WLAN verbunden")
        val lp = cm.getLinkProperties(net) ?: throw IllegalStateException("Keine IP-Konfiguration")
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
            ?: throw IllegalStateException("Keine IPv4-Adresse")
        val own = la.address.hostAddress ?: ""
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        // Große Netze (< /22) auf das eigene /24 begrenzen, sonst dauert der Sweep zu lange.
        val prefix = if (la.prefixLength < 22) 24 else la.prefixLength.coerceAtMost(30)
        val hosts = hostsOf(la.address as Inet4Address, prefix)
        val started = System.currentTimeMillis()

        val lock = wifi.createMulticastLock("WlanLogger::lan").apply { setReferenceCounted(false); acquire() }
        val pool = Executors.newFixedThreadPool(48)
        val side = Executors.newFixedThreadPool(2)
        try {
            progress("Multicast-Suche (mDNS/SSDP) …")
            val fSsdp = side.submit { safe { ssdp(net) } }
            val fMdns = side.submit { safe { mdns(net) } }

            val done = AtomicInteger()
            progress("Suche Geräte: 0/${hosts.size}")
            hosts.map { ip ->
                pool.submit {
                    safe { probeAlive(net, ip) }
                    val n = done.incrementAndGet()
                    if (n % 16 == 0) progress("Suche Geräte: $n/${hosts.size} · gefunden: ${devs.size}")
                }
            }.forEach { waitFor(it) }
            waitFor(fSsdp)
            waitFor(fMdns)

            readNeighbours()
            mark(own, "self")
            if (gateway != null) mark(gateway, "gateway")

            val list = devs.keys.filter { !it.contains(':') }
            val detailDone = AtomicInteger()
            progress("Details für ${list.size} Geräte …")
            list.map { ip ->
                pool.submit {
                    safe { details(net, ip) }
                    progress("Details: ${detailDone.incrementAndGet()}/${list.size}")
                }
            }.forEach { waitFor(it) }
            readNeighbours()
        } finally {
            pool.shutdownNow()
            side.shutdownNow()
            if (lock.isHeld) lock.release()
        }
        return build(started, own, gateway, prefix)
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { }
    }

    private fun waitFor(f: Future<*>) {
        try { f.get(90, TimeUnit.SECONDS) } catch (e: Exception) { }
    }

    private fun hostsOf(addr: Inet4Address, prefix: Int): List<String> {
        val b = addr.address
        val ip = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or
            ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
        val mask = -1 shl (32 - prefix)
        val base = ip and mask
        val count = 1 shl (32 - prefix)
        return (1 until count - 1).map { intToIp(base + it) }
    }

    private fun intToIp(v: Int) = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    private fun ipKey(ip: String): Long =
        ip.split('.').fold(0L) { acc, part -> acc * 256 + (part.toLongOrNull() ?: 0L) }

    // ---------------- Erreichbarkeit ----------------

    private enum class Tcp { OPEN, REFUSED, TIMEOUT }

    private fun tcp(net: Network, ip: String, port: Int, timeoutMs: Int): Tcp {
        val s: Socket = try { net.socketFactory.createSocket() } catch (e: Exception) { Socket() }
        return try {
            s.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs)
            Tcp.OPEN
        } catch (e: ConnectException) {
            // "Connection refused" = Host lebt, Port zu. "No route to host" = kein Gerät.
            val m = e.message ?: ""
            if (m.contains("ECONNREFUSED") || m.contains("refused", ignoreCase = true)) Tcp.REFUSED else Tcp.TIMEOUT
        } catch (e: Exception) {
            Tcp.TIMEOUT
        } finally {
            try { s.close() } catch (e: Exception) { }
        }
    }

    private fun probeAlive(net: Network, ip: String) {
        if (Net.ping(ip, 1) != null) {
            mark(ip, "ping")
            return
        }
        // Viele Geräte (Windows, iPhones) blocken Ping – TCP-Antwort reicht als Lebenszeichen.
        for (p in QUICK_PORTS) {
            when (tcp(net, ip, p, 400)) {
                Tcp.OPEN -> {
                    val d = dev(ip)
                    synchronized(d) { d.via.add("tcp"); d.ports.add(p) }
                    return
                }
                Tcp.REFUSED -> {
                    mark(ip, "tcp")
                    return
                }
                Tcp.TIMEOUT -> {}
            }
        }
    }

    private fun details(net: Network, ip: String) {
        val d = dev(ip)
        val ps = Net.pingStats(ip, 5, 1)
        val open = PORTS.keys.filter { tcp(net, ip, it, 500) == Tcp.OPEN }
        val host = try {
            InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }
        } catch (e: Exception) {
            null
        }
        val nb = try { netbios(net, ip) } catch (e: Exception) { null }
        val location = synchronized(d) { d.locations.firstOrNull() }
        val upnp = location?.let { try { fetchUpnp(net, it) } catch (e: Exception) { null } }

        synchronized(d) {
            d.pingSent = ps.sent
            d.pingRecv = ps.received
            if (ps.times.isNotEmpty()) {
                d.pingAvg = Util.round1(ps.times.average())
                d.pingMax = ps.times.maxOrNull()
            }
            d.ports.addAll(open)
            d.hostname = host
            if (nb != null) {
                d.netbios = nb.first
                if (d.mac == null) d.mac = nb.second
                d.via.add("netbios")
            }
            if (upnp != null) {
                d.upnpName = upnp["friendlyName"]
                d.upnpManufacturer = upnp["manufacturer"]
                d.upnpModel = listOfNotNull(upnp["modelName"], upnp["modelNumber"]).joinToString(" ").ifEmpty { null }
                d.upnpType = upnp["deviceType"]
            }
        }
    }

    // ---------------- NetBIOS (UDP 137) ----------------

    /** NBSTAT-Abfrage: liefert (Name, MAC) von Windows-PCs, NAS, Samba. */
    private fun netbios(net: Network, ip: String): Pair<String?, String?>? {
        val q = ByteArray(50)
        q[0] = 0x13; q[1] = 0x37
        q[5] = 1
        q[12] = 0x20
        val enc = "CK" + "A".repeat(30)
        for (i in enc.indices) q[13 + i] = enc[i].code.toByte()
        q[45] = 0
        q[47] = 0x21
        q[49] = 1
        return DatagramSocket().use { s ->
            net.bindSocket(s)
            s.soTimeout = 800
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (e: SocketTimeoutException) { return null }
            val len = p.length
            if (len < 57) return null
            val n = buf[56].toInt() and 255
            var name: String? = null
            var fallback: String? = null
            for (i in 0 until n) {
                val o = 57 + i * 18
                if (o + 18 > len) break
                val nm = String(buf, o, 15, Charsets.US_ASCII).trim()
                val suffix = buf[o + 15].toInt() and 255
                val group = (buf[o + 16].toInt() and 0x80) != 0
                if (fallback == null && !group) fallback = nm
                if (name == null && suffix == 0 && !group) name = nm
            }
            val mo = 57 + n * 18
            val mac = if (mo + 6 <= len) {
                (0 until 6).joinToString(":") { String.format("%02x", buf[mo + it].toInt() and 255) }
                    .takeIf { it != "00:00:00:00:00:00" }
            } else null
            val finalName = (name ?: fallback)?.takeIf { it.isNotEmpty() }
            if (finalName == null && mac == null) null else Pair(finalName, mac)
        }
    }

    // ---------------- SSDP / UPnP ----------------

    private fun ssdp(net: Network) {
        DatagramSocket().use { s ->
            net.bindSocket(s)
            s.soTimeout = 400
            val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n").toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) {
                s.send(DatagramPacket(msg, msg.size, group, 1900))
                Thread.sleep(150)
            }
            val end = System.currentTimeMillis() + 3500
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                val ip = p.address?.hostAddress ?: continue
                if (ip.contains(':')) continue
                val headers = String(p.data, 0, p.length, Charsets.UTF_8).lines().mapNotNull { l ->
                    val i = l.indexOf(':')
                    if (i > 0) l.substring(0, i).trim().uppercase() to l.substring(i + 1).trim() else null
                }.toMap()
                val d = dev(ip)
                synchronized(d) {
                    d.via.add("ssdp")
                    headers["SERVER"]?.let { d.ssdpServer = it }
                    headers["LOCATION"]?.let { if (d.locations.size < 5) d.locations.add(it) }
                }
            }
        }
    }

    private fun fetchUpnp(net: Network, location: String): Map<String, String> {
        val url = URL(location)
        if (url.protocol != "http") return emptyMap()
        val c = net.openConnection(url) as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 1500
        try {
            val sb = StringBuilder()
            c.inputStream.bufferedReader().use { r ->
                val cb = CharArray(8192)
                while (sb.length < 131072) {
                    val n = r.read(cb)
                    if (n < 0) break
                    sb.append(cb, 0, n)
                }
            }
            val body = sb.toString()
            val out = HashMap<String, String>()
            for (tag in listOf("friendlyName", "manufacturer", "modelName", "modelNumber", "deviceType")) {
                Regex("<$tag>([^<]*)</$tag>").find(body)?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { out[tag] = unxml(it) }
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    private fun unxml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    // ---------------- mDNS / Bonjour ----------------

    private fun mdns(net: Network) {
        DatagramSocket().use { s ->
            net.bindSocket(s)
            s.soTimeout = 400
            val group = InetAddress.getByName("224.0.0.251")
            val asked = HashSet(MDNS_TYPES)
            val discovered = LinkedHashSet<String>()
            fun ask(types: List<String>) {
                types.chunked(10).forEach { chunk ->
                    val q = mdnsQuery(chunk)
                    s.send(DatagramPacket(q, q.size, group, 5353))
                }
            }
            ask(MDNS_TYPES)
            receiveMdns(s, 2500, discovered)
            ask(MDNS_TYPES)
            receiveMdns(s, 1500, discovered)
            // Zweite Runde: Diensttypen, die Geräte selbst gemeldet haben
            val more = discovered.filter { asked.add(it) }
            if (more.isNotEmpty()) {
                ask(more)
                receiveMdns(s, 2500, discovered)
            }
        }
    }

    private fun mdnsQuery(names: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write((v shr 8) and 255)
            out.write(v and 255)
        }
        u16(0); u16(0); u16(names.size); u16(0); u16(0); u16(0)
        for (n in names) {
            for (label in n.trimEnd('.').split('.')) {
                val b = label.toByteArray(Charsets.UTF_8)
                out.write(b.size)
                out.write(b, 0, b.size)
            }
            out.write(0)
            u16(12) // PTR
            u16(1)  // IN
        }
        return out.toByteArray()
    }

    private fun receiveMdns(s: DatagramSocket, ms: Long, discovered: MutableSet<String>) {
        val end = System.currentTimeMillis() + ms
        val buf = ByteArray(9000)
        while (System.currentTimeMillis() < end) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
            val ip = p.address?.hostAddress ?: continue
            if (ip.contains(':')) continue
            try { parseMdns(ip, p.data.copyOf(p.length), discovered) } catch (e: Exception) { }
        }
    }

    private fun parseMdns(ip: String, b: ByteArray, discovered: MutableSet<String>) {
        fun u16(o: Int) = ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255)
        if (b.size < 12) return
        val qd = u16(4)
        val total = u16(6) + u16(8) + u16(10)
        var off = 12
        repeat(qd) { off = readName(b, off).second + 4 }
        val d = dev(ip)
        repeat(total) {
            val (name, after) = readName(b, off)
            if (after + 10 > b.size) return
            val type = u16(after)
            val rdlen = u16(after + 8)
            val rd = after + 10
            if (rd + rdlen > b.size) return
            when (type) {
                12 -> { // PTR
                    val target = readName(b, rd).first
                    synchronized(d) {
                        d.via.add("mdns")
                        if (name.equals("_services._dns-sd._udp.local", ignoreCase = true)) {
                            discovered.add(target)
                            d.mdnsServices.add(target.removeSuffix(".local"))
                        } else {
                            d.mdnsServices.add(name.removeSuffix(".local"))
                            target.substringBefore("._").takeIf { it.isNotEmpty() }?.let { d.mdnsNames.add(it) }
                        }
                    }
                }
                1 -> synchronized(d) { // A
                    d.via.add("mdns")
                    d.mdnsNames.add(name)
                }
                33 -> { // SRV
                    if (rdlen > 6) {
                        val target = readName(b, rd + 6).first
                        synchronized(d) { d.mdnsNames.add(target) }
                    }
                }
                16 -> { // TXT
                    var o = rd
                    while (o < rd + rdlen) {
                        val l = b[o].toInt() and 255
                        val avail = minOf(l, rd + rdlen - o - 1)
                        if (avail > 0) {
                            val str = String(b, o + 1, avail, Charsets.UTF_8)
                            if (str.length in 3..80 && TXT_KEYS.any { str.startsWith(it) }) {
                                synchronized(d) { if (d.mdnsTxt.size < 12) d.mdnsTxt.add(str) }
                            }
                        }
                        o += l + 1
                    }
                }
            }
            off = rd + rdlen
        }
    }

    /** DNS-Namen lesen, inkl. Kompressionszeigern. Rückgabe: (Name, Offset nach dem Namen). */
    private fun readName(b: ByteArray, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var off = start
        var end = -1
        var jumps = 0
        while (off < b.size) {
            val len = b[off].toInt() and 255
            if (len == 0) {
                off += 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (off + 1 >= b.size) break
                if (end < 0) end = off + 2
                off = ((len and 0x3F) shl 8) or (b[off + 1].toInt() and 255)
                jumps++
                if (jumps > 40) break
                continue
            }
            if (off + 1 + len > b.size) break
            labels.add(String(b, off + 1, len, Charsets.UTF_8))
            off += len + 1
        }
        return Pair(labels.joinToString("."), if (end >= 0) end else off)
    }

    // ---------------- ARP (nur ältere Android-Versionen) ----------------

    private fun readNeighbours() {
        val map = HashMap<String, String>()
        try {
            File("/proc/net/arp").readLines().drop(1).forEach { l ->
                val p = l.trim().split(Regex("\\s+"))
                if (p.size >= 4 && p[3] != "00:00:00:00:00:00" && p[2] != "0x0") map[p[0]] = p[3].lowercase()
            }
        } catch (e: Exception) { }
        if (map.isEmpty()) {
            try {
                val pr = ProcessBuilder("ip", "neigh", "show").redirectErrorStream(true).start()
                val out = pr.inputStream.bufferedReader().use { it.readText() }
                pr.waitFor(3, TimeUnit.SECONDS)
                Regex("^(\\d+\\.\\d+\\.\\d+\\.\\d+) .*lladdr ([0-9a-fA-F:]{17})", RegexOption.MULTILINE)
                    .findAll(out).forEach { map[it.groupValues[1]] = it.groupValues[2].lowercase() }
            } catch (e: Exception) { }
        }
        for ((ip, mac) in map) {
            val d = dev(ip)
            synchronized(d) {
                if (d.mac == null) d.mac = mac
                d.via.add("arp")
            }
        }
    }

    // ---------------- Ergebnis ----------------

    private fun guess(d: Dev): String? {
        val svc = d.mdnsServices.joinToString(" ").lowercase()
        val txt = d.mdnsTxt.joinToString(" ").lowercase()
        val up = listOfNotNull(d.ssdpServer, d.upnpManufacturer, d.upnpModel, d.upnpType, d.upnpName)
            .joinToString(" ").lowercase()
        val p = d.ports
        return when {
            "fritz" in up || 49000 in p -> "AVM FRITZ!Box / Repeater"
            "_googlecast" in svc || 8009 in p -> "Google Chromecast / Nest / Android TV"
            "_androidtvremote" in svc -> "Android TV"
            "_amzn-wplay" in svc || "amazon" in up -> "Amazon Fire TV / Echo"
            "_sonos" in svc || "sonos" in up -> "Sonos-Lautsprecher"
            "_airplay" in svc || "_raop" in svc -> if ("appletv" in txt) "Apple TV" else "AirPlay-Gerät (Apple TV, Mac, Lautsprecher, TV)"
            "_companion-link" in svc || 62078 in p -> "Apple-Gerät (iPhone/iPad/Mac)"
            "_ipp" in svc || "_printer" in svc || "_pdl-datastream" in svc || 9100 in p || 631 in p -> "Drucker"
            "_hap" in svc || "_homekit" in svc -> "HomeKit-Smart-Home-Gerät"
            "_hue" in svc || "philips" in up -> "Philips Hue Bridge"
            "_esphomelib" in svc -> "ESPHome-Gerät"
            "_matter" in svc -> "Matter-Smart-Home-Gerät"
            "mediarenderer" in up || "dial" in up -> "Smart-TV / Media-Player"
            "internetgatewaydevice" in up -> "Router"
            554 in p -> "IP-Kamera / Rekorder (RTSP)"
            1883 in p -> "Smart-Home-Zentrale (MQTT)"
            3389 in p || d.netbios != null || 445 in p || 139 in p -> "Windows-PC / NAS"
            22 in p -> "Linux / Raspberry Pi / NAS"
            else -> null
        }
    }

    private fun build(started: Long, own: String, gateway: String?, prefix: Int): List<JSONObject> {
        val now = System.currentTimeMillis()
        val duration = Util.round1((now - started) / 1000.0)
        return devs.values.filter { !it.ip.contains(':') }.sortedBy { ipKey(it.ip) }.map { d ->
            synchronized(d) {
                val role = when (d.ip) {
                    own -> "Dieses Handy"
                    gateway -> "Router (Gateway)"
                    else -> null
                }
                JSONObject()
                    .put("device_scan_id", started)
                    .put("ts", now)
                    .put("time", Util.iso(now))
                    .put("ip", d.ip)
                    .putN("role", role)
                    .putN("type_guess", guess(d))
                    .putN("hostname", d.hostname)
                    .putN("netbios_name", d.netbios)
                    .putN("mac", d.mac)
                    .put("ping_sent", d.pingSent)
                    .put("ping_recv", d.pingRecv)
                    .putN("ping_loss_pct", if (d.pingSent > 0) Util.round1(100.0 * (d.pingSent - d.pingRecv) / d.pingSent) else null)
                    .putN("ping_avg_ms", d.pingAvg)
                    .putN("ping_max_ms", d.pingMax)
                    .put("open_ports", d.ports.joinToString(" ") { "$it/${PORTS[it] ?: "?"}" })
                    .put("discovered_via", d.via.joinToString(" "))
                    .putN("mdns_names", d.mdnsNames.joinToString(" | ").ifEmpty { null })
                    .putN("mdns_services", d.mdnsServices.joinToString(" | ").ifEmpty { null })
                    .putN("mdns_txt", d.mdnsTxt.joinToString(" | ").ifEmpty { null })
                    .putN("ssdp_server", d.ssdpServer)
                    .putN("upnp_name", d.upnpName)
                    .putN("upnp_manufacturer", d.upnpManufacturer)
                    .putN("upnp_model", d.upnpModel)
                    .putN("upnp_device_type", d.upnpType)
                    .put("scan_prefix", prefix)
                    .put("scan_duration_s", duration)
            }
        }
    }
}
