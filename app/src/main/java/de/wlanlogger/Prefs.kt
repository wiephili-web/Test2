package de.wlanlogger

import android.content.Context

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun connInterval(ctx: Context): Int = p(ctx).getInt("conn_interval", 3)
    fun scanInterval(ctx: Context): Int = p(ctx).getInt("scan_interval", 30)
    fun pingTarget(ctx: Context): String = p(ctx).getString("ping_target", null) ?: "1.1.1.1"
    fun pingEnabled(ctx: Context): Boolean = p(ctx).getBoolean("ping_enabled", true)

    fun save(ctx: Context, conn: Int, scan: Int, target: String, ping: Boolean) {
        p(ctx).edit()
            .putInt("conn_interval", conn)
            .putInt("scan_interval", scan)
            .putString("ping_target", target)
            .putBoolean("ping_enabled", ping)
            .apply()
    }
}
