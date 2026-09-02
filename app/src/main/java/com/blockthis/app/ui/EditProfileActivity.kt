package com.blockthis.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.blockthis.app.R
import com.blockthis.app.data.Store
import com.blockthis.app.engine.BlockEngine
import com.blockthis.app.model.BlockMode
import com.blockthis.app.model.BlockProfile
import com.blockthis.app.model.ScheduleRule
import java.util.Locale

class EditProfileActivity : Activity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val REQUEST_PICK_APPS = 1
    }

    private lateinit var profile: BlockProfile
    private var isNew = true

    private lateinit var nameEdit: EditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var scheduleSection: View
    private lateinit var scheduleContainer: LinearLayout
    private lateinit var limitSection: View
    private lateinit var limitEdit: EditText
    private lateinit var timerSection: View
    private lateinit var timerEdit: EditText
    private lateinit var strictSwitch: Switch
    private lateinit var strictHint: TextView
    private lateinit var appsButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile)

        val id = intent.getStringExtra(EXTRA_PROFILE_ID)
        val existing = if (id != null) Store.copyOf(this, id) else null
        isNew = existing == null
        profile = existing ?: BlockProfile()

        // If the profile strict-locked itself between the list tap and now, bail.
        if (!isNew) {
            val original = Store.profiles(this).find { it.id == profile.id }
            if (original != null && BlockEngine.isStrictLocked(this, original)) {
                Toast.makeText(this, R.string.toast_locked_simple, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }

        findViewById<TextView>(R.id.edit_title).setText(
            if (isNew) R.string.title_new_profile else R.string.title_edit_profile
        )

        nameEdit = findViewById(R.id.edit_name)
        modeGroup = findViewById(R.id.mode_group)
        scheduleSection = findViewById(R.id.section_schedule)
        scheduleContainer = findViewById(R.id.schedule_container)
        limitSection = findViewById(R.id.section_limit)
        limitEdit = findViewById(R.id.edit_limit)
        timerSection = findViewById(R.id.section_timer)
        timerEdit = findViewById(R.id.edit_timer)
        strictSwitch = findViewById(R.id.switch_strict)
        strictHint = findViewById(R.id.text_strict_hint)
        appsButton = findViewById(R.id.button_select_apps)

        nameEdit.setText(profile.name)
        limitEdit.setText(profile.limitMinutes.toString())
        timerEdit.setText(profile.timerMinutes.toString())

        modeGroup.check(
            when (profile.mode) {
                BlockMode.ALWAYS -> R.id.radio_always
                BlockMode.SCHEDULE -> R.id.radio_schedule
                BlockMode.TIMER -> R.id.radio_timer
                BlockMode.LIMIT -> R.id.radio_limit
            }
        )
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            profile.mode = when (checkedId) {
                R.id.radio_always -> BlockMode.ALWAYS
                R.id.radio_timer -> BlockMode.TIMER
                R.id.radio_limit -> BlockMode.LIMIT
                else -> BlockMode.SCHEDULE
            }
            applyModeVisibility()
        }

        strictSwitch.isChecked = profile.strict
        strictSwitch.setOnCheckedChangeListener { _, checked -> profile.strict = checked }

        appsButton.setOnClickListener {
            val intent = Intent(this, AppPickerActivity::class.java)
                .putStringArrayListExtra(
                    AppPickerActivity.EXTRA_SELECTED, ArrayList(profile.packages)
                )
            startActivityForResult(intent, REQUEST_PICK_APPS)
        }

        findViewById<Button>(R.id.button_add_window).setOnClickListener {
            showRuleDialog(null)
        }
        findViewById<Button>(R.id.button_save).setOnClickListener { save() }

        val deleteButton = findViewById<Button>(R.id.button_delete)
        deleteButton.visibility = if (isNew) View.GONE else View.VISIBLE
        deleteButton.setOnClickListener { confirmDelete() }

        applyModeVisibility()
        rebuildScheduleList()
        updateAppsButton()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_APPS && resultCode == RESULT_OK && data != null) {
            val picked = data.getStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED)
            if (picked != null) {
                profile.packages.clear()
                profile.packages.addAll(picked)
                updateAppsButton()
            }
        }
    }

    private fun applyModeVisibility() {
        scheduleSection.visibility =
            if (profile.mode == BlockMode.SCHEDULE) View.VISIBLE else View.GONE
        limitSection.visibility =
            if (profile.mode == BlockMode.LIMIT) View.VISIBLE else View.GONE
        timerSection.visibility =
            if (profile.mode == BlockMode.TIMER) View.VISIBLE else View.GONE

        if (profile.mode == BlockMode.ALWAYS) {
            profile.strict = false
            strictSwitch.isChecked = false
            strictSwitch.isEnabled = false
            strictHint.setText(R.string.strict_always_hint)
        } else {
            strictSwitch.isEnabled = true
            strictHint.setText(R.string.strict_subtitle)
        }
    }

    private fun updateAppsButton() {
        appsButton.text = getString(R.string.button_select_apps, profile.packages.size)
    }

    private fun rebuildScheduleList() {
        scheduleContainer.removeAllViews()
        for (rule in profile.schedules) {
            val row = layoutInflater.inflate(R.layout.item_schedule, scheduleContainer, false)
            row.findViewById<TextView>(R.id.schedule_text).text = describeRule(rule)
            row.findViewById<View>(R.id.schedule_delete).setOnClickListener {
                profile.schedules.remove(rule)
                rebuildScheduleList()
            }
            row.setOnClickListener { showRuleDialog(rule) }
            scheduleContainer.addView(row)
        }
    }

    private fun describeRule(rule: ScheduleRule): String {
        val names = resources.getStringArray(R.array.days_short)
        val days = if (rule.days.size == 7) {
            getString(R.string.every_day)
        } else {
            rule.days.sorted().joinToString(", ") { names[it - 1] }
        }
        val time = String.format(
            Locale.getDefault(), "%02d:%02d – %02d:%02d",
            rule.startMinute / 60, rule.startMinute % 60,
            rule.endMinute / 60, rule.endMinute % 60
        )
        val suffix = if (rule.endMinute <= rule.startMinute) {
            " " + getString(R.string.next_day_marker)
        } else ""
        return "$days • $time$suffix"
    }

    private fun showRuleDialog(existing: ScheduleRule?) {
        val view = layoutInflater.inflate(R.layout.dialog_schedule, null)
        val checkboxIds = intArrayOf(
            R.id.cb_d1, R.id.cb_d2, R.id.cb_d3, R.id.cb_d4, R.id.cb_d5, R.id.cb_d6, R.id.cb_d7
        )
        val initialDays = existing?.days ?: setOf(1, 2, 3, 4, 5)
        for (day in 1..7) {
            view.findViewById<CheckBox>(checkboxIds[day - 1]).isChecked = day in initialDays
        }

        var start = existing?.startMinute ?: 9 * 60
        var end = existing?.endMinute ?: 17 * 60
        val startButton = view.findViewById<Button>(R.id.btn_start_time)
        val endButton = view.findViewById<Button>(R.id.btn_end_time)

        fun fmt(minute: Int) =
            String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)

        startButton.text = fmt(start)
        endButton.text = fmt(end)

        val is24h = DateFormat.is24HourFormat(this)
        startButton.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                start = h * 60 + m
                startButton.text = fmt(start)
            }, start / 60, start % 60, is24h).show()
        }
        endButton.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                end = h * 60 + m
                endButton.text = fmt(end)
            }, end / 60, end % 60, is24h).show()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_window_title)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                val days = mutableSetOf<Int>()
                for (day in 1..7) {
                    if (view.findViewById<CheckBox>(checkboxIds[day - 1]).isChecked) days.add(day)
                }
                if (days.isEmpty()) {
                    Toast.makeText(this, R.string.toast_pick_day, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (existing != null) {
                    existing.days.clear()
                    existing.days.addAll(days)
                    existing.startMinute = start
                    existing.endMinute = end
                } else {
                    profile.schedules.add(ScheduleRule(days, start, end))
                }
                rebuildScheduleList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun save() {
        // Re-check the lock: the block may have started while this screen was open.
        if (!isNew) {
            val original = Store.profiles(this).find { it.id == profile.id }
            if (original != null && BlockEngine.isStrictLocked(this, original)) {
                Toast.makeText(this, R.string.toast_locked_simple, Toast.LENGTH_LONG).show()
                return
            }
        }

        val name = nameEdit.text.toString().trim()
        profile.name = if (name.isEmpty()) getString(R.string.default_profile_name) else name

        if (profile.packages.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_apps, Toast.LENGTH_LONG).show()
            return
        }
        when (profile.mode) {
            BlockMode.SCHEDULE -> {
                if (profile.schedules.isEmpty()) {
                    Toast.makeText(this, R.string.toast_no_windows, Toast.LENGTH_LONG).show()
                    return
                }
            }
            BlockMode.LIMIT -> {
                val minutes = limitEdit.text.toString().toIntOrNull() ?: 0
                if (minutes <= 0) {
                    Toast.makeText(this, R.string.toast_bad_limit, Toast.LENGTH_LONG).show()
                    return
                }
                profile.limitMinutes = minutes
            }
            BlockMode.TIMER -> {
                val minutes = timerEdit.text.toString().toIntOrNull() ?: 0
                if (minutes <= 0) {
                    Toast.makeText(this, R.string.toast_bad_timer, Toast.LENGTH_LONG).show()
                    return
                }
                profile.timerMinutes = minutes
            }
            BlockMode.ALWAYS -> Unit
        }
        if (profile.mode == BlockMode.ALWAYS) profile.strict = false
        if (profile.mode != BlockMode.TIMER) profile.timerEndMillis = 0L

        Store.upsert(this, profile)
        finish()
    }

    private fun confirmDelete() {
        val original = Store.profiles(this).find { it.id == profile.id }
        if (original != null && BlockEngine.isStrictLocked(this, original)) {
            Toast.makeText(this, R.string.toast_locked_simple, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                Store.delete(this, profile.id)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
