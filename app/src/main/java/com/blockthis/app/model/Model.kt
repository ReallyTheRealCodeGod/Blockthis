package com.blockthis.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class BlockMode {
    /** Blocked the whole time the profile is switched on. */
    ALWAYS,

    /** Blocked during configured weekly time windows. */
    SCHEDULE,

    /** Blocked while a manually started focus session is running. */
    TIMER,

    /** Blocked for the rest of the day once the daily usage limit is used up. */
    LIMIT
}

/**
 * One weekly time window. Days use ISO numbering: 1 = Monday .. 7 = Sunday.
 * Minutes are minutes-of-day (0..1439). If endMinute <= startMinute the window
 * runs past midnight into the next day; startMinute == endMinute means a full
 * 24 hours starting at startMinute.
 */
class ScheduleRule(
    val days: MutableSet<Int> = mutableSetOf(1, 2, 3, 4, 5),
    var startMinute: Int = 9 * 60,
    var endMinute: Int = 17 * 60
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("days", JSONArray(days.toList().sorted()))
        put("start", startMinute)
        put("end", endMinute)
    }

    companion object {
        fun fromJson(o: JSONObject): ScheduleRule {
            val days = mutableSetOf<Int>()
            val arr = o.optJSONArray("days") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val d = arr.optInt(i, 0)
                if (d in 1..7) days.add(d)
            }
            if (days.isEmpty()) days.addAll(1..7)
            return ScheduleRule(
                days,
                o.optInt("start", 0).coerceIn(0, 1439),
                o.optInt("end", 0).coerceIn(0, 1439)
            )
        }
    }
}

class BlockProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var mode: BlockMode = BlockMode.SCHEDULE,
    val packages: MutableSet<String> = mutableSetOf(),
    val schedules: MutableList<ScheduleRule> = mutableListOf(),
    /** Daily allowance in minutes for LIMIT mode. */
    var limitMinutes: Int = 60,
    /** Default session length in minutes for TIMER mode. */
    var timerMinutes: Int = 30,
    /** End of the currently running TIMER session (epoch millis), 0 = none. */
    var timerEndMillis: Long = 0L,
    /** Session end on the monotonic elapsedRealtime clock, so changing the
     *  wall clock cannot end a session early. 0 = none. */
    var timerEndElapsed: Long = 0L,
    /** elapsedRealtime() when the session started; used to detect reboots
     *  (after a reboot the monotonic clock restarts and wall time governs). */
    var timerElapsedAtStart: Long = 0L,
    /** While a strict profile is actively blocking, the app protects itself:
     *  uninstall/settings screens are blocked and the profile is locked. */
    var strict: Boolean = false,
    var enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("mode", mode.name)
        put("packages", JSONArray(packages.toList().sorted()))
        val rules = JSONArray()
        schedules.forEach { rules.put(it.toJson()) }
        put("schedules", rules)
        put("limitMinutes", limitMinutes)
        put("timerMinutes", timerMinutes)
        put("timerEndMillis", timerEndMillis)
        put("timerEndElapsed", timerEndElapsed)
        put("timerElapsedAtStart", timerElapsedAtStart)
        put("strict", strict)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): BlockProfile {
            val packages = mutableSetOf<String>()
            val pkgArr = o.optJSONArray("packages") ?: JSONArray()
            for (i in 0 until pkgArr.length()) {
                val p = pkgArr.optString(i, "")
                if (p.isNotEmpty()) packages.add(p)
            }
            val schedules = mutableListOf<ScheduleRule>()
            val ruleArr = o.optJSONArray("schedules") ?: JSONArray()
            for (i in 0 until ruleArr.length()) {
                val ro = ruleArr.optJSONObject(i)
                if (ro != null) schedules.add(ScheduleRule.fromJson(ro))
            }
            val mode = try {
                BlockMode.valueOf(o.optString("mode", BlockMode.SCHEDULE.name))
            } catch (_: IllegalArgumentException) {
                BlockMode.SCHEDULE
            }
            return BlockProfile(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", ""),
                mode = mode,
                packages = packages,
                schedules = schedules,
                limitMinutes = o.optInt("limitMinutes", 60).coerceAtLeast(1),
                timerMinutes = o.optInt("timerMinutes", 30).coerceAtLeast(1),
                timerEndMillis = o.optLong("timerEndMillis", 0L),
                timerEndElapsed = o.optLong("timerEndElapsed", 0L),
                timerElapsedAtStart = o.optLong("timerElapsedAtStart", 0L),
                strict = o.optBoolean("strict", false),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }
}
