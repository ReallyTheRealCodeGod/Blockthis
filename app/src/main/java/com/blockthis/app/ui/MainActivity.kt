package com.blockthis.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.blockthis.app.R
import com.blockthis.app.admin.BlockerDeviceAdminReceiver
import com.blockthis.app.data.Store
import com.blockthis.app.data.UsageStore
import com.blockthis.app.engine.BlockEngine
import com.blockthis.app.model.BlockMode
import com.blockthis.app.model.BlockProfile
import com.blockthis.app.service.BlockerAccessibilityService

class MainActivity : Activity() {

    private lateinit var adapter: ProfileAdapter
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var accessibilityState: TextView
    private lateinit var adminState: TextView
    private lateinit var paranoidSwitch: Switch

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.main_list)
        emptyView = findViewById(R.id.main_empty)
        accessibilityState = findViewById(R.id.text_accessibility_state)
        adminState = findViewById(R.id.text_admin_state)
        paranoidSwitch = findViewById(R.id.switch_paranoid)

        adapter = ProfileAdapter()
        listView.adapter = adapter

        findViewById<View>(R.id.row_accessibility).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Throwable) {
                Toast.makeText(this, R.string.toast_no_settings, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.row_admin).setOnClickListener { onAdminRowClicked() }
        findViewById<Button>(R.id.button_new_profile).setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.removeCallbacks(refresher)
        handler.postDelayed(refresher, 30_000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    // ------------------------------------------------------------------

    private fun adminComponent() = ComponentName(this, BlockerDeviceAdminReceiver::class.java)

    private fun isAdminActive(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(adminComponent())
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (BlockerAccessibilityService.running) return true
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = packageName
        return enabled.split(':').any { it.startsWith("$me/") }
    }

    private fun onAdminRowClicked() {
        if (isAdminActive()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.admin_active)
                .setMessage(R.string.admin_active_dialog)
                .setPositiveButton(R.string.ok, null)
                .show()
        } else {
            try {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.admin_add_explanation)
                    )
                startActivity(intent)
            } catch (_: Throwable) {
                Toast.makeText(this, R.string.toast_no_settings, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refresh() {
        val serviceOn = isAccessibilityEnabled()
        accessibilityState.setText(
            if (serviceOn) R.string.status_enabled else R.string.status_tap_to_enable
        )
        adminState.setText(
            if (isAdminActive()) R.string.admin_active else R.string.status_tap_to_enable
        )

        val strictActive = BlockEngine.anyStrictActive(this)
        paranoidSwitch.setOnCheckedChangeListener(null)
        paranoidSwitch.isChecked = Store.paranoid(this)
        paranoidSwitch.isEnabled = !strictActive
        paranoidSwitch.setOnCheckedChangeListener { _, checked ->
            Store.setParanoid(this, checked)
        }

        adapter.notifyDataSetChanged()
        val empty = Store.profiles(this).isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun toastLocked(profile: BlockProfile) {
        val until = BlockEngine.blockedUntilMillis(this, profile, System.currentTimeMillis())
        Toast.makeText(
            this,
            getString(R.string.toast_locked, BlockEngine.untilText(this, until)),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun onToggleProfile(profile: BlockProfile, checked: Boolean) {
        val real = Store.profiles(this).find { it.id == profile.id } ?: return
        if (BlockEngine.isStrictLocked(this, real)) {
            toastLocked(real)
            refresh()
            return
        }
        real.enabled = checked
        Store.persist(this)
        if (checked && !isAccessibilityEnabled()) {
            Toast.makeText(this, R.string.toast_enable_service_first, Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun onOpenProfile(profile: BlockProfile) {
        val real = Store.profiles(this).find { it.id == profile.id } ?: return
        if (BlockEngine.isStrictLocked(this, real)) {
            toastLocked(real)
            return
        }
        startActivity(
            Intent(this, EditProfileActivity::class.java)
                .putExtra(EditProfileActivity.EXTRA_PROFILE_ID, real.id)
        )
    }

    private fun onTimerAction(profile: BlockProfile) {
        val real = Store.profiles(this).find { it.id == profile.id } ?: return
        val now = System.currentTimeMillis()
        if (BlockEngine.isBlockingNow(this, real, now)) {
            if (BlockEngine.isStrictLocked(this, real, now)) {
                toastLocked(real)
            } else {
                real.timerEndMillis = 0L
                real.timerEndElapsed = 0L
                real.timerElapsedAtStart = 0L
                Store.persist(this)
                refresh()
            }
            return
        }
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(real.timerMinutes.toString())
        AlertDialog.Builder(this)
            .setTitle(R.string.timer_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_start) { _, _ ->
                val minutes = input.text.toString().toIntOrNull() ?: 0
                if (minutes <= 0) return@setPositiveButton
                if (real.strict) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.confirm_strict_timer_title)
                        .setMessage(getString(R.string.confirm_strict_timer_message, minutes))
                        .setPositiveButton(R.string.dialog_start) { _, _ ->
                            startTimerSession(real, minutes)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    startTimerSession(real, minutes)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startTimerSession(profile: BlockProfile, minutes: Int) {
        val elapsed = SystemClock.elapsedRealtime()
        profile.timerMinutes = minutes
        profile.timerEndMillis = System.currentTimeMillis() + minutes * 60_000L
        profile.timerElapsedAtStart = elapsed
        profile.timerEndElapsed = elapsed + minutes * 60_000L
        Store.persist(this)
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, R.string.toast_enable_service_first, Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    // ------------------------------------------------------------------

    private inner class ProfileAdapter : BaseAdapter() {

        override fun getCount(): Int = Store.profiles(this@MainActivity).size

        override fun getItem(position: Int): Any = Store.profiles(this@MainActivity)[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.item_profile, parent, false)
            val profile = Store.profiles(this@MainActivity)[position]
            val now = System.currentTimeMillis()
            val blocking = BlockEngine.isBlockingNow(this@MainActivity, profile, now)
            val locked = BlockEngine.isStrictLocked(this@MainActivity, profile, now)

            val title = view.findViewById<TextView>(R.id.profile_title)
            val summary = view.findViewById<TextView>(R.id.profile_summary)
            val status = view.findViewById<TextView>(R.id.profile_status)
            val toggle = view.findViewById<Switch>(R.id.profile_switch)
            val timerButton = view.findViewById<Button>(R.id.profile_timer_button)

            title.text = profile.name
            summary.text = summaryText(profile)

            val statusText = statusText(profile, blocking, now)
            status.text = statusText
            status.visibility = if (statusText.isEmpty()) View.GONE else View.VISIBLE

            toggle.setOnCheckedChangeListener(null)
            if (profile.mode == BlockMode.TIMER) {
                toggle.visibility = View.GONE
                timerButton.visibility = View.VISIBLE
                if (blocking && locked) {
                    timerButton.isEnabled = false
                    timerButton.setText(R.string.timer_locked)
                } else {
                    timerButton.isEnabled = true
                    timerButton.setText(if (blocking) R.string.timer_stop else R.string.timer_start)
                }
                timerButton.setOnClickListener { onTimerAction(profile) }
            } else {
                timerButton.visibility = View.GONE
                toggle.visibility = View.VISIBLE
                toggle.isChecked = profile.enabled
                toggle.isEnabled = !locked
                toggle.setOnCheckedChangeListener { _, checked ->
                    onToggleProfile(profile, checked)
                }
            }

            view.setOnClickListener { onOpenProfile(profile) }
            return view
        }

        private fun summaryText(profile: BlockProfile): String {
            val mode = when (profile.mode) {
                BlockMode.ALWAYS -> getString(R.string.mode_always)
                BlockMode.SCHEDULE -> getString(R.string.mode_schedule)
                BlockMode.TIMER -> getString(R.string.mode_timer)
                BlockMode.LIMIT -> getString(R.string.mode_limit, profile.limitMinutes)
            }
            val parts = mutableListOf(
                mode,
                getString(R.string.summary_apps, profile.packages.size)
            )
            if (profile.strict) parts.add(getString(R.string.strict_marker))
            return parts.joinToString(" • ")
        }

        private fun statusText(profile: BlockProfile, blocking: Boolean, now: Long): String {
            if (blocking) {
                val until = BlockEngine.blockedUntilMillis(this@MainActivity, profile, now)
                return getString(
                    R.string.blocking_now,
                    BlockEngine.untilText(this@MainActivity, until)
                )
            }
            if (profile.mode == BlockMode.LIMIT && profile.enabled) {
                val used = (UsageStore.usedSeconds(this@MainActivity, profile.id) / 60L).toInt()
                return getString(R.string.limit_status, used, profile.limitMinutes)
            }
            if (profile.mode == BlockMode.SCHEDULE && profile.enabled) {
                val next = BlockEngine.nextScheduleStartMillis(profile, now)
                if (next != null) {
                    return getString(R.string.starts_at, BlockEngine.formatWallTime(next))
                }
            }
            return ""
        }
    }
}
