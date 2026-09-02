package com.blockthis.app.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.blockthis.app.R
import com.blockthis.app.data.Store
import com.blockthis.app.data.UsageStore
import com.blockthis.app.model.BlockMode
import com.blockthis.app.model.BlockProfile
import com.blockthis.app.model.ScheduleRule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What the overlay should show for a blocked surface. */
class BlockDecision(
    val title: String,
    val subtitle: String,
    val untilText: String
)

object BlockEngine {

    /** Screens that host app uninstall flows. Blocked wholesale during strict blocks. */
    private val INSTALLER_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller"
    )

    /** App stores can uninstall apps too; blocked during strict blocks. */
    private val STORE_PACKAGES = setOf(
        "com.android.vending"
    )

    /** System settings apps (incl. OEM "security center" apps that manage uninstall). */
    private val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.iqoo.secure",
        "com.huawei.systemmanager"
    )

    /** Never blocked: phone/emergency, system UI, ourselves (by package below). */
    private val ALWAYS_SAFE = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.app.telephonyui",
        "com.samsung.android.emergency"
    )

    /** Substrings of settings screen titles/classes that must stay unreachable
     *  during a strict block, because they can disable or remove the blocker. */
    private val SENSITIVE_SETTINGS_NEEDLES = listOf(
        "device admin", "deviceadmin",
        "accessibility",
        "app info", "appinfo", "installedappdetails",
        "manage apps", "manageapplications", "all apps", "app management",
        "special app access", "specialaccess",
        "uninstall"
    )

    @Volatile
    private var cachedLaunchers: Set<String> = emptySet()

    @Volatile
    private var lastLauncherRefresh = 0L

    fun warm(context: Context) {
        refreshLaunchers(context)
    }

    private fun refreshLaunchers(context: Context) {
        lastLauncherRefresh = System.currentTimeMillis()
        cachedLaunchers = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun launcherPackages(context: Context): Set<String> {
        if (cachedLaunchers.isEmpty() ||
            System.currentTimeMillis() - lastLauncherRefresh > 6 * 60 * 60 * 1000L
        ) {
            refreshLaunchers(context)
        }
        return cachedLaunchers
    }

    /** Apps that may be added to block lists (everything except us + safety set). */
    fun isPackageSelectable(context: Context, pkg: String): Boolean =
        pkg != context.packageName && pkg !in ALWAYS_SAFE && pkg !in launcherPackages(context)

    // ------------------------------------------------------------------
    // Active/blocking state
    // ------------------------------------------------------------------

    fun isBlockingNow(
        context: Context,
        p: BlockProfile,
        now: Long = System.currentTimeMillis()
    ): Boolean = when (p.mode) {
        BlockMode.ALWAYS -> p.enabled
        BlockMode.SCHEDULE -> p.enabled && p.schedules.any { ruleEndMillis(it, now) != null }
        BlockMode.TIMER -> now < p.timerEndMillis
        BlockMode.LIMIT -> p.enabled && p.limitMinutes > 0 &&
            UsageStore.usedSeconds(context, p.id) >= p.limitMinutes * 60L
    }

    /**
     * A strict profile that is actively blocking locks itself: it cannot be
     * toggled, edited or deleted, and the app protects itself from removal.
     * ALWAYS profiles are deliberately never strict-locked so there is always a
     * way out of a mistake; a 24/7 strict schedule gives a permanent block.
     */
    fun isStrictLocked(
        context: Context,
        p: BlockProfile,
        now: Long = System.currentTimeMillis()
    ): Boolean = p.strict && p.mode != BlockMode.ALWAYS && isBlockingNow(context, p, now)

    fun anyStrictActive(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        Store.profiles(context).any { isStrictLocked(context, it, now) }

    // ------------------------------------------------------------------
    // "Blocked until" computation
    // ------------------------------------------------------------------

    /** Long.MAX_VALUE means "until you switch it off" / no scheduled end. */
    fun blockedUntilMillis(context: Context, p: BlockProfile, now: Long): Long = when (p.mode) {
        BlockMode.ALWAYS -> Long.MAX_VALUE
        BlockMode.TIMER -> p.timerEndMillis
        BlockMode.LIMIT -> nextMidnight(now)
        BlockMode.SCHEDULE -> scheduleEnd(p.schedules, now)
    }

    private fun nextMidnight(now: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.add(Calendar.DAY_OF_YEAR, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Walk chained/overlapping windows to find when blocking actually ends. */
    private fun scheduleEnd(rules: List<ScheduleRule>, now: Long): Long {
        var t = now
        var end = now
        repeat(20) {
            val ends = rules.mapNotNull { ruleEndMillis(it, t) }
            if (ends.isEmpty()) return end
            val maxEnd = ends.max()
            if (maxEnd <= end && it > 0) return end
            end = maxOf(end, maxEnd)
            t = end
        }
        return Long.MAX_VALUE
    }

    /** ISO day-of-week (1 = Monday .. 7 = Sunday) for a Calendar. */
    private fun isoDay(cal: Calendar): Int = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1

    /**
     * If the rule covers instant [now], returns the absolute end of that
     * coverage (epoch millis, end-exclusive); otherwise null.
     */
    fun ruleEndMillis(rule: ScheduleRule, now: Long): Long? {
        if (rule.days.isEmpty()) return null
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val today = isoDay(cal)
        val prev = if (today == 1) 7 else today - 1
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        fun endAt(dayOffset: Int, endMin: Int): Long {
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, dayOffset)
            c.set(Calendar.HOUR_OF_DAY, endMin / 60)
            c.set(Calendar.MINUTE, endMin % 60)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        val wraps = rule.endMinute <= rule.startMinute
        return if (!wraps) {
            if (today in rule.days && minute >= rule.startMinute && minute < rule.endMinute) {
                endAt(0, rule.endMinute)
            } else null
        } else {
            when {
                today in rule.days && minute >= rule.startMinute -> endAt(1, rule.endMinute)
                prev in rule.days && minute < rule.endMinute -> endAt(0, rule.endMinute)
                else -> null
            }
        }
    }

    fun untilText(context: Context, untilMillis: Long): String {
        if (untilMillis == Long.MAX_VALUE) {
            return context.getString(R.string.until_indefinite)
        }
        val now = System.currentTimeMillis()
        val text = if (untilMillis - now < 20 * 60 * 60 * 1000L) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(untilMillis))
        } else {
            SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(untilMillis))
        }
        return context.getString(R.string.until_time, text)
    }

    // ------------------------------------------------------------------
    // The decision the accessibility service asks for on every window change
    // ------------------------------------------------------------------

    fun evaluate(
        context: Context,
        pkg: String,
        className: String,
        titles: List<String>
    ): BlockDecision? {
        if (pkg.isEmpty()) return null
        if (pkg == context.packageName || pkg in ALWAYS_SAFE) return null
        if (pkg in launcherPackages(context)) return null

        val now = System.currentTimeMillis()
        val profiles = Store.profiles(context)

        val strictActive = profiles.filter { isStrictLocked(context, it, now) }
        if (strictActive.isNotEmpty() && isProtectedSurface(context, pkg, className, titles)) {
            val until = strictActive.maxOf { blockedUntilMillis(context, it, now) }
            return BlockDecision(
                title = context.getString(R.string.overlay_protected_title),
                subtitle = context.getString(R.string.overlay_protected_subtitle),
                untilText = untilText(context, until)
            )
        }

        for (p in profiles) {
            if (pkg in p.packages && isBlockingNow(context, p, now)) {
                return BlockDecision(
                    title = context.getString(R.string.overlay_blocked_title, appLabel(context, pkg)),
                    subtitle = context.getString(R.string.overlay_blocked_subtitle, p.name),
                    untilText = untilText(context, blockedUntilMillis(context, p, now))
                )
            }
        }
        return null
    }

    /**
     * While a strict block runs, screens that could disable or remove the
     * blocker are themselves blocked: any package installer/uninstaller, the
     * Play Store, and the sensitive parts of system Settings (device admin,
     * accessibility, app info / app lists). With "paranoid" on, the whole
     * Settings app is blocked during strict blocks.
     */
    private fun isProtectedSurface(
        context: Context,
        pkg: String,
        className: String,
        titles: List<String>
    ): Boolean {
        if (pkg in INSTALLER_PACKAGES) return true
        if (pkg in STORE_PACKAGES) return true
        if (pkg !in SETTINGS_PACKAGES) return false
        if (Store.paranoid(context)) return true
        val hay = StringBuilder(className.lowercase(Locale.ROOT))
        for (t in titles) hay.append(' ').append(t.lowercase(Locale.ROOT))
        val text = hay.toString()
        if (SENSITIVE_SETTINGS_NEEDLES.any { text.contains(it) }) return true
        val ownLabel = context.getString(R.string.app_name).lowercase(Locale.ROOT)
        if (text.contains(ownLabel)) return true
        // The bare "Apps" list is the road to uninstall; match it as a whole title.
        return titles.any { it.trim().equals("apps", ignoreCase = true) }
    }

    fun appLabel(context: Context, pkg: String): String = try {
        val ai = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(ai).toString()
    } catch (_: Throwable) {
        pkg
    }
}
