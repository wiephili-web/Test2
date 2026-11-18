package de.wlanlogger

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Ablage als JSON Lines (eine Messung pro Zeile). Anhängen ist billig und
 * ein Absturz zerstört höchstens die letzte Zeile, nicht die ganze Datei.
 */
object Store {
    lateinit var dir: File
        private set

    val connFile: File get() = File(dir, "connection.jsonl")
    val scanFile: File get() = File(dir, "scans.jsonl")
    val eventFile: File get() = File(dir, "events.jsonl")

    val connRows = AtomicLong()
    val scanRows = AtomicLong()
    val eventRows = AtomicLong()

    private val lock = Any()

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        Thread {
            synchronized(lock) {
                connRows.set(countLines(connFile))
                scanRows.set(countLines(scanFile))
                eventRows.set(countLines(eventFile))
            }
        }.start()
    }

    fun appendConnection(o: JSONObject) = append(connFile, listOf(o), connRows)

    fun appendScan(rows: List<JSONObject>) = append(scanFile, rows, scanRows)

    fun event(type: String, details: JSONObject? = null) {
        val now = System.currentTimeMillis()
        val o = JSONObject().put("ts", now).put("time", Util.iso(now)).put("type", type)
        if (details != null) for (k in details.keys()) o.put(k, details.get(k))
        append(eventFile, listOf(o), eventRows)
        LiveState.addEvent(Util.short(now) + "  " + type + (details?.let { "  $it" } ?: ""))
    }

    private fun append(f: File, rows: List<JSONObject>, counter: AtomicLong) {
        if (rows.isEmpty()) return
        val text = buildString { rows.forEach { append(it.toString()).append('\n') } }
        synchronized(lock) {
            f.appendText(text)
            counter.addAndGet(rows.size.toLong())
        }
    }

    /** Kopiert die Logdateien konsistent (unter Sperre) in [target]. */
    fun snapshotTo(target: File) {
        synchronized(lock) {
            listOf(connFile, scanFile, eventFile).forEach { f ->
                val dst = File(target, f.name)
                if (f.exists()) f.copyTo(dst, overwrite = true) else dst.delete()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            listOf(connFile, scanFile, eventFile).forEach { it.delete() }
            connRows.set(0); scanRows.set(0); eventRows.set(0)
        }
    }

    fun totalBytes(): Long = listOf(connFile, scanFile, eventFile).sumOf { if (it.exists()) it.length() else 0L }

    private fun countLines(f: File): Long {
        if (!f.exists()) return 0
        var n = 0L
        val buf = ByteArray(64 * 1024)
        f.inputStream().buffered().use { s ->
            while (true) {
                val r = s.read(buf)
                if (r < 0) break
                for (i in 0 until r) if (buf[i] == '\n'.code.toByte()) n++
            }
        }
        return n
    }
}
