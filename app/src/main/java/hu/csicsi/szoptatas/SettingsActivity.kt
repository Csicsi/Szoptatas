package hu.csicsi.szoptatas

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract.Calendars
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** Only screen: grant calendar access and pick the calendar the feeds go into. */
class SettingsActivity : Activity() {

    private lateinit var info: TextView
    private lateinit var list: ListView
    private var calendarIds: List<Long> = emptyList()

    private val permissions = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        info = TextView(this).apply {
            textSize = 18f
            setPadding(pad, pad, pad, pad)
            setText(R.string.pick_calendar)
        }
        list = ListView(this).apply { choiceMode = ListView.CHOICE_MODE_SINGLE }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info)
            addView(list)
        })

        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            loadCalendars()
        } else {
            requestPermissions(permissions, 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            loadCalendars()
        } else {
            info.setText(R.string.need_permission)
        }
    }

    private fun loadCalendars() {
        val ids = mutableListOf<Long>()
        val names = mutableListOf<String>()
        contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME),
            // no VISIBLE filter: calendars created on the web often start hidden/unsynced here
            "${Calendars.CALENDAR_ACCESS_LEVEL}>=?",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                ids += c.getLong(0)
                names += "${c.getString(1)}\n${c.getString(2)}"
            }
        }
        calendarIds = ids

        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, names)
        val selected = ids.indexOf(Feeding.calendarId(this))
        if (selected >= 0) list.setItemChecked(selected, true)

        list.setOnItemClickListener { _, _, pos, _ ->
            val id = calendarIds[pos]
            enableSync(id)
            Feeding.setCalendarId(this, id)
            FeedWidget.refreshAll(this)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** Make sure the chosen calendar is synced, so its events reach Google Calendar. */
    private fun enableSync(id: Long) {
        val values = ContentValues().apply {
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
        }
        try {
            contentResolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, id), values, null, null)
        } catch (e: Exception) {
            // not allowed on some providers; the user can still enable sync in the calendar app
        }
    }
}
