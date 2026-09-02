package com.blockthis.app.data

import android.content.Context
import android.content.SharedPreferences
import com.blockthis.app.model.BlockProfile
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale

/**
 * Profile persistence: SharedPreferences + JSON, with an in-memory cache so the
 * accessibility service can consult profiles on every window event cheaply.
 * Everything runs in the single app process, so the cache is authoritative.
 */
object Store {
    private const val PREFS = "blockthis"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_PARANOID = "paranoid"

    @Volatile
    private var cache: MutableList<BlockProfile>? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun profiles(context: Context): MutableList<BlockProfile> {
        cache?.let { return it }
        val loaded = mutableListOf<BlockProfile>()
        try {
            val raw = prefs(context).getString(KEY_PROFILES, null)
            if (raw != null) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    loaded.add(BlockProfile.fromJson(o))
                }
            }
        } catch (_: Throwable) {
            // Corrupt store: start empty rather than crash the service.
        }
        cache = loaded
        return loaded
    }

    @Synchronized
    fun persist(context: Context) {
        val list = profiles(context)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    @Synchronized
    fun upsert(context: Context, profile: BlockProfile) {
        val list = profiles(context)
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        persist(context)
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val list = profiles(context)
        list.removeAll { it.id == id }
        persist(context)
    }

    /** Deep copy for editing so cancelled edits never leak into the live cache. */
    fun copyOf(context: Context, id: String): BlockProfile? =
        profiles(context).find { it.id == id }?.let { BlockProfile.fromJson(it.toJson()) }

    fun paranoid(context: Context): Boolean = prefs(context).getBoolean(KEY_PARANOID, false)

    fun setParanoid(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PARANOID, value).apply()
    }
}

/**
 * Per-day usage metering for LIMIT profiles. Seconds are accumulated by the
 * accessibility service's tick while a watched app is in the foreground.
 */
object UsageStore {
    private const val PREFS = "blockthis_usage"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dayStamp(): String {
        val c = Calendar.getInstance()
        return String.format(
            Locale.US, "%04d%02d%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun key(profileId: String) = "u." + profileId + "." + dayStamp()

    fun addSeconds(context: Context, profileId: String, seconds: Long) {
        val p = prefs(context)
        val k = key(profileId)
        p.edit().putLong(k, p.getLong(k, 0L) + seconds).apply()
    }

    fun usedSeconds(context: Context, profileId: String): Long =
        prefs(context).getLong(key(profileId), 0L)

    /** Drop counters from previous days. */
    fun prune(context: Context) {
        val p = prefs(context)
        val suffix = "." + dayStamp()
        val stale = p.all.keys.filter { !it.endsWith(suffix) }
        if (stale.isNotEmpty()) {
            val e = p.edit()
            stale.forEach { e.remove(it) }
            e.apply()
        }
    }
}
