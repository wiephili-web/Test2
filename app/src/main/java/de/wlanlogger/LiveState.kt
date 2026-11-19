package de.wlanlogger

import org.json.JSONObject

/** Prozessweiter Zustand, den die Oberfläche jede Sekunde abfragt. */
object LiveState {
    @Volatile var running = false
    @Volatile var startedAt = 0L
    @Volatile var lastConnection: JSONObject? = null
    @Volatile var lastScan: List<JSONObject> = emptyList()
    @Volatile var lastScanTs = 0L
    @Volatile var lastScanStored = true
    @Volatile var lastDevices: List<JSONObject> = emptyList()
    @Volatile var lastDevicesTs = 0L
    @Volatile var deviceProgress = ""

    private val events = ArrayList<String>()

    fun addEvent(line: String) = synchronized(events) {
        events.add(0, line)
        while (events.size > 30) events.removeAt(events.size - 1)
    }

    fun events(): List<String> = synchronized(events) { ArrayList(events) }
}
