package com.meda.portico.home

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.meda.portico.R
import com.meda.portico.core.content.Presence
import com.meda.portico.core.content.RosterRow
import com.meda.portico.core.content.RosterStatus
import com.meda.portico.core.content.RosterView
import com.meda.portico.core.content.RowState

/**
 * The roster page of Home's pager (M7 3b-A): one row per person or screen the server lets this phone call, in the server's
 * order (Everyone first where it is offered). Each row is a presence dot, the name over one line of words ("Do not disturb",
 * "Not reachable", ...), and a Call button of at least 48 dp. The dot is never the only signal: the words say the same, and
 * the row reads as one sentence to TalkBack. Drawing only: which rows, and every state, is `:core`'s `RosterModel`.
 *
 * Built only when `:core` says house calls are on for this phone; with them off this class is never reached.
 */
class RosterViews(private val context: Context) {

    private fun dp(n: Int): Int = (n * context.resources.displayMetrics.density + 0.5f).toInt()

    fun rebuild(container: LinearLayout, roster: RosterView, onCall: (RosterRow) -> Unit) {
        container.removeAllViews()
        for (row in roster.rows) {
            container.addView(rowView(row, onCall), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
    }

    private fun rowView(row: RosterRow, onCall: (RosterRow) -> Unit): View {
        val name = nameText(row)
        val status = statusText(row)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(0, dp(8), 0, dp(8))
        }
        box.addView(TextView(context).apply {
            text = glyph(row.presence)
            textSize = 20f
            setTextColor(color(row.presence))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The name and the words are ONE sentence for TalkBack ("Alice's Pixel, Do not disturb"); the Call button is its own stop.
            ViewCompat.setScreenReaderFocusable(this, true)
            contentDescription = context.getString(R.string.roster_row_desc, name, status)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        words.addView(TextView(context).apply {
            text = name
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        words.addView(TextView(context).apply {
            text = status
            textSize = 14f
            alpha = 0.8f
        })
        box.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(Button(context).apply {
            text = context.getString(if (row.state == RowState.CALLING) R.string.roster_open_call else R.string.roster_call)
            isEnabled = row.state != RowState.DISABLED
            minHeight = dp(48)
            minWidth = dp(88)
            contentDescription = context.getString(R.string.roster_call_desc, name)
            setOnClickListener { onCall(row) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    /** Everyone says how many phones a press will ring ("Everyone (2 phones)"); every other row is its (cleaned) name. */
    private fun nameText(row: RosterRow): String {
        if (!row.isEveryone) return row.name
        val n = row.count
        return when {
            n == null || n <= 0 -> context.getString(R.string.roster_everyone)
            n == 1 -> context.getString(R.string.roster_everyone_one)
            else -> context.getString(R.string.roster_everyone_n, n)
        }
    }

    private fun statusText(row: RosterRow): String = context.getString(
        when (row.status) {
            RosterStatus.AVAILABLE -> R.string.roster_status_available
            RosterStatus.BY_NOTIFICATION -> R.string.roster_status_by_notification
            RosterStatus.NOT_REACHABLE -> R.string.roster_status_not_reachable
            RosterStatus.DO_NOT_DISTURB -> R.string.roster_status_dnd
            RosterStatus.ON_A_CALL -> R.string.roster_status_on_a_call
            RosterStatus.CALLS_OFF -> R.string.roster_status_calls_off
            RosterStatus.NO_RECEIVERS -> R.string.roster_status_no_receivers
            RosterStatus.GONE -> R.string.roster_status_gone
            RosterStatus.NOT_AVAILABLE -> R.string.roster_status_not_available
            RosterStatus.NOT_CONNECTED -> R.string.roster_status_not_connected
            RosterStatus.CALLING -> R.string.roster_status_calling
        },
    )

    private fun glyph(p: Presence): String = when (p) {
        Presence.ONLINE, Presence.PUSH_ONLY, Presence.DND, Presence.BUSY -> "●"
        Presence.OFFLINE, Presence.OFF -> "○"
    }

    private fun color(p: Presence): Int = when (p) {
        Presence.ONLINE -> COLOR_OK
        Presence.PUSH_ONLY -> COLOR_LIGHT
        Presence.DND -> COLOR_WARN
        Presence.BUSY -> COLOR_BAD
        Presence.OFFLINE, Presence.OFF -> COLOR_MUTED
    }

    private companion object {
        // The Home status bar's colours: they read on the default light and dark themes.
        val COLOR_OK: Int = Color.parseColor("#2E7D32")
        val COLOR_LIGHT: Int = Color.parseColor("#81C784")
        val COLOR_WARN: Int = Color.parseColor("#EF6C00")
        val COLOR_BAD: Int = Color.parseColor("#C62828")
        val COLOR_MUTED: Int = Color.parseColor("#757575")
    }
}
