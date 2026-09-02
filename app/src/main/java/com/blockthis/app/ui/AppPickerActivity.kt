package com.blockthis.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.blockthis.app.R
import com.blockthis.app.engine.BlockEngine
import java.util.Locale

class AppPickerActivity : Activity() {

    companion object {
        const val EXTRA_SELECTED = "selected"
    }

    private class AppEntry(val pkg: String, val label: String, val icon: Drawable?)

    private val selected = LinkedHashSet<String>()
    private var allApps: List<AppEntry> = emptyList()
    private var shownApps: List<AppEntry> = emptyList()
    private var filterText = ""

    private lateinit var adapter: AppAdapter
    private lateinit var doneButton: Button
    private lateinit var loadingView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        selected.addAll(intent.getStringArrayListExtra(EXTRA_SELECTED) ?: arrayListOf())

        adapter = AppAdapter()
        findViewById<ListView>(R.id.picker_list).adapter = adapter
        loadingView = findViewById(R.id.picker_loading)
        doneButton = findViewById(R.id.button_done)
        doneButton.setOnClickListener {
            setResult(
                RESULT_OK,
                Intent().putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected))
            )
            finish()
        }
        updateDoneButton()

        findViewById<EditText>(R.id.edit_search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                filterText = s?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
                applyFilter()
            }
        })

        Thread {
            val loaded = loadApps()
            runOnUiThread {
                allApps = loaded
                loadingView.visibility = View.GONE
                applyFilter()
            }
        }.start()
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        try {
            for (info in pm.queryIntentActivities(launcherIntent, 0)) {
                val activityInfo = info.activityInfo ?: continue
                val pkg = activityInfo.packageName ?: continue
                if (!seen.add(pkg)) continue
                if (!BlockEngine.isPackageSelectable(this, pkg)) continue
                val label = try {
                    info.loadLabel(pm)?.toString() ?: pkg
                } catch (_: Throwable) {
                    pkg
                }
                val icon = try {
                    info.loadIcon(pm)
                } catch (_: Throwable) {
                    null
                }
                out.add(AppEntry(pkg, label, icon))
            }
        } catch (_: Throwable) {
        }
        out.sortBy { it.label.lowercase(Locale.getDefault()) }
        return out
    }

    private fun applyFilter() {
        shownApps = if (filterText.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.lowercase(Locale.getDefault()).contains(filterText) ||
                    it.pkg.lowercase(Locale.getDefault()).contains(filterText)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun updateDoneButton() {
        doneButton.text = getString(R.string.done_count, selected.size)
    }

    private inner class AppAdapter : BaseAdapter() {

        override fun getCount(): Int = shownApps.size

        override fun getItem(position: Int): Any = shownApps[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val entry = shownApps[position]

            view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(entry.icon)
            view.findViewById<TextView>(R.id.app_label).text = entry.label
            view.findViewById<TextView>(R.id.app_package).text = entry.pkg

            val checkBox = view.findViewById<CheckBox>(R.id.app_check)
            checkBox.isChecked = entry.pkg in selected

            view.setOnClickListener {
                if (entry.pkg in selected) selected.remove(entry.pkg) else selected.add(entry.pkg)
                checkBox.isChecked = entry.pkg in selected
                updateDoneButton()
            }
            return view
        }
    }
}
