package com.meda.portico.setup

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.meda.portico.R
import com.meda.portico.core.ha.FailureClass
import com.meda.portico.core.ring.Dot
import com.meda.portico.core.ring.Fix
import com.meda.portico.core.ring.Severity
import com.meda.portico.core.ring.StatusKey
import com.meda.portico.core.ring.StatusLine
import com.meda.portico.core.ring.StatusItem
import com.meda.portico.core.ring.StatusRow
import com.meda.portico.core.ring.FactKey
import com.meda.portico.core.ring.SignIn
import com.meda.portico.core.ring.StatusFact

/**
 * Renders `:core`'s [StatusRow]s (StatusBoard) into the Status screen: one compact line per fact, a coloured dot on the
 * left and a short text; a problem is bold. Every text is a string resource. 0.6.69: no button opens an Android
 * settings page here. 0.6.70: facts are plain "label: value" rows; the phone/ring configuration rows are gone from Status
 * (the notification permission request moved to Settings > Android settings > Notifications). The only buttons are
 * in-app fixes: [restart] and (kept for [Fix.REQUEST_NOTIFICATIONS], no producer feeds it to the board now) [requestNotifications].
 */
class SetupChecks(
    private val activity: AppCompatActivity,
    private val requestNotifications: () -> Unit,
    private val restart: () -> Unit,
) {

    /** One group: checks (dot + text) and facts (label: value) in the order [com.meda.portico.core.ring.StatusBoard.items] gave. */
    fun render(container: LinearLayout, items: List<StatusItem>) {
        container.removeAllViews()
        for (item in items) {
            when (item) {
                is StatusItem.Check -> renderCheck(container, item.row)
                is StatusItem.Fact -> renderFact(container, item.fact)
            }
        }
    }

    /** A fact: the label in a fixed-share left column, the value in the right one, both starting where a check's text starts. */
    private fun renderFact(container: LinearLayout, fact: StatusFact) {
        val holder = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        holder.addView(View(activity), LinearLayout.LayoutParams(dp(12 + 10), 1)) // the width of a dot and its gap
        holder.addView(
            TextView(activity).apply {
                setText(factLabel(fact.key))
                alpha = 0.7f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.38f),
        )
        holder.addView(
            TextView(activity).apply { text = factValue(fact) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.62f),
        )
        container.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun factValue(f: StatusFact): String = when (f.key) {
        FactKey.SIGN_IN -> activity.getString(if (f.value == SignIn.TOKEN.name) R.string.fact_sign_in_token else R.string.fact_sign_in_renews)
        FactKey.ENROLMENT -> activity.getString(R.string.fact_enrolment_finishing)
        else -> f.value
    }

    private fun renderCheck(container: LinearLayout, row: StatusRow) {
        val line = row.line
        val holder = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        holder.addView(
            ImageView(activity).apply {
                setImageResource(R.drawable.status_dot)
                imageTintList = ColorStateList.valueOf(activity.getColor(dotColor(row.dot)))
                contentDescription = activity.getString(dotDescription(row.dot))
            },
            LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) },
        )
        holder.addView(
            TextView(activity).apply {
                text = bodyOf(line)
                if (line.severity == Severity.PROBLEM) setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        container.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val fix = line.fix ?: return
        container.addView(
            Button(activity).apply {
                setText(fixRes(fix))
                minHeight = dp(48)
                setOnClickListener { apply(fix) }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    /** The line's text; a per-path failure line gets its reason's own plain words as the second argument. */
    private fun bodyOf(line: StatusLine): String {
        val failure = line.failure
        val second = when {
            failure != null -> {
                val res = failureRes(line.key, failure, line.arg2 != null)
                if (line.arg2 != null) activity.getString(res, line.arg2) else activity.getString(res)
            }
            else -> line.arg2
        }
        return when {
            second != null -> activity.getString(textRes(line.key), line.arg ?: "", second)
            line.arg != null -> activity.getString(textRes(line.key), line.arg)
            else -> activity.getString(textRes(line.key))
        }
    }

    private fun apply(fix: Fix) {
        when (fix) {
            Fix.REQUEST_NOTIFICATIONS -> requestNotifications()
            Fix.RESTART_APP -> restart()
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    companion object {
        fun factLabel(k: FactKey): Int = when (k) {
            FactKey.ADDRESS -> R.string.fact_address
            FactKey.TAILNET_DIAL -> R.string.fact_tailnet_dial
            FactKey.SIGN_IN -> R.string.fact_sign_in
            FactKey.PHONE_NAME -> R.string.fact_phone_name
            FactKey.ENROLMENT -> R.string.fact_enrolment
            FactKey.VERSION -> R.string.fact_version
            FactKey.FIREBASE -> R.string.fact_firebase
        }

        fun fixRes(fix: Fix): Int = when (fix) {
            Fix.REQUEST_NOTIFICATIONS -> R.string.fix_request_notifications
            Fix.RESTART_APP -> R.string.fix_restart
        }

        fun dotColor(d: Dot): Int = when (d) {
            Dot.GREEN -> R.color.dot_green
            Dot.AMBER -> R.color.dot_amber
            Dot.RED -> R.color.dot_red
            Dot.GREY -> R.color.dot_grey
        }

        fun dotDescription(d: Dot): Int = when (d) {
            Dot.GREEN -> R.string.dot_ok
            Dot.AMBER -> R.string.dot_attention
            Dot.RED -> R.string.dot_problem
            Dot.GREY -> R.string.dot_off
        }

        fun textRes(key: StatusKey): Int = when (key) {
            StatusKey.NOTIFICATIONS_OK -> R.string.st_notifications_ok
            StatusKey.NOTIFICATIONS_NOT_ASKED -> R.string.st_notifications_not_asked
            StatusKey.NOTIFICATIONS_DENIED -> R.string.st_notifications_denied
            StatusKey.NOTIFICATIONS_DENIED_PERMANENT -> R.string.st_notifications_denied_permanent
            StatusKey.NOTIFICATIONS_OFF -> R.string.st_notifications_off
            StatusKey.RING_CHANNEL_LOWERED -> R.string.st_ring_channel_lowered
            StatusKey.RING_CHANNEL_BLOCKED -> R.string.st_ring_channel_blocked
            StatusKey.STATUS_CHANNEL_BLOCKED -> R.string.st_status_channel_blocked
            StatusKey.FULL_SCREEN_OK -> R.string.st_full_screen_ok
            StatusKey.FULL_SCREEN_DENIED -> R.string.st_full_screen_denied
            StatusKey.FULL_SCREEN_NOT_A_QUESTION -> R.string.st_full_screen_not_a_question
            StatusKey.DND_ON -> R.string.st_dnd_on
            StatusKey.RING_BYPASS_DND -> R.string.st_ring_bypass_dnd
            StatusKey.RING_NO_BYPASS_DND -> R.string.st_ring_no_bypass_dnd
            StatusKey.BACKGROUND_RESTRICTED -> R.string.st_background_restricted
            StatusKey.BATTERY_OPTIMISED -> R.string.st_battery_optimised
            StatusKey.CALL_STYLE_REFUSED -> R.string.st_call_style_refused
            StatusKey.LINK_CONNECTED -> R.string.st_link_connected
            StatusKey.LINK_CONNECTING -> R.string.st_link_connecting
            StatusKey.LINK_RETRYING -> R.string.st_link_retrying
            StatusKey.LINK_AUTH_REJECTED -> R.string.st_link_auth_rejected
            StatusKey.LINK_SIGNED_OUT -> R.string.st_link_signed_out
            StatusKey.LINK_NOT_ENROLLED -> R.string.st_link_not_enrolled
            StatusKey.LINK_STOPPED -> R.string.st_link_stopped
            StatusKey.LINK_URL_REFUSED -> R.string.st_link_url_refused
            StatusKey.URL_PLAIN_HTTP -> R.string.setup_url_plain_http
            StatusKey.URL_REFUSED -> R.string.st_url_refused
            StatusKey.PUSH_NO_FIREBASE -> R.string.st_push_no_firebase
            StatusKey.PUSH_FIREBASE_FAILED -> R.string.st_push_firebase_failed
            StatusKey.PUSH_RESTART_TO_APPLY -> R.string.st_push_restart
            StatusKey.PUSH_WAITING_FOR_FIREBASE -> R.string.st_push_waiting_for_firebase
            StatusKey.PUSH_REGISTERING -> R.string.st_push_registering
            StatusKey.PUSH_OFF_IN_HA -> R.string.st_push_off_in_ha
            StatusKey.PUSH_ON -> R.string.st_push_on
            StatusKey.PUSH_ON_LAST_OK -> R.string.st_push_on_last_ok
            StatusKey.PUSH_REFUSED -> R.string.st_push_refused
            StatusKey.PUSH_SERVER_WITHOUT_PUSH -> R.string.st_push_server_without_push
            StatusKey.PUSH_RETRY -> R.string.st_push_retry
            StatusKey.PUSH_OFF_LOCAL -> R.string.st_push_off_local
            StatusKey.PUSH_SIGNED_OUT -> R.string.st_push_signed_out
            StatusKey.PUSH_SERVER_STATE -> R.string.st_push_server_state
            StatusKey.RING_SWITCH_OFF -> R.string.st_ring_switch_off
            StatusKey.LINK_PATH_DIRECT -> R.string.st_link_path_direct
            StatusKey.LINK_PATH_TAILNET -> R.string.st_link_path_tailnet
            StatusKey.TAILNET_NOT_BUILT -> R.string.st_tailnet_not_built
            StatusKey.TAILNET_NOT_SET_UP -> R.string.st_tailnet_not_set_up
            StatusKey.TAILNET_READY -> R.string.st_tailnet_ready
            StatusKey.TAILNET_KEY_PENDING -> R.string.st_tailnet_key_pending
            StatusKey.TAILNET_JOINING -> R.string.st_tailnet_joining
            StatusKey.TAILNET_NEEDS_APPROVAL -> R.string.st_tailnet_needs_approval
            StatusKey.TAILNET_UP -> R.string.st_tailnet_up
            StatusKey.TAILNET_KEY_REJECTED -> R.string.st_tailnet_key_rejected
            StatusKey.TAILNET_NEEDS_LOGIN -> R.string.st_tailnet_needs_login
            StatusKey.TAILNET_TIMEOUT -> R.string.st_tailnet_timeout
            StatusKey.TAILNET_LIBRARY_FAILED -> R.string.st_tailnet_library_failed
            StatusKey.TAILNET_ERROR -> R.string.st_tailnet_error
            StatusKey.TAILNET_NO_TARGET -> R.string.st_tailnet_no_target
            StatusKey.TAILNET_KEY_EXPIRED -> R.string.st_tailnet_key_expired
            StatusKey.PATH_DIRECT_OPENED -> R.string.st_path_direct_opened
            StatusKey.PATH_DIRECT_FAILED -> R.string.st_path_direct_failed
            StatusKey.PATH_TAILNET_OPENED -> R.string.st_path_tailnet_opened
            StatusKey.PATH_TAILNET_FAILED -> R.string.st_path_tailnet_failed
            StatusKey.PATH_TAILNET_NOT_BUILT -> R.string.st_path_tailnet_not_built
            StatusKey.ROUTE_VIA -> R.string.st_route_via
            StatusKey.ROUTE_NONE -> R.string.st_route_none
            StatusKey.ROUTE_UNKNOWN -> R.string.st_route_unknown
            StatusKey.CONNECT_TIMELINE -> R.string.st_connect_timeline
            StatusKey.PAUSED_UNTIL -> R.string.st_paused_until
            StatusKey.PAUSED_UNTIL_RESUMED -> R.string.st_paused_until_resumed
            StatusKey.LINK_PAUSED -> R.string.st_link_paused
            StatusKey.PUSH_PAUSED -> R.string.st_push_paused
            StatusKey.TAILNET_PAUSED -> R.string.st_tailnet_paused
        }

        /**
         * A failure's plain words. The tailnet path has its own wording where the cause differs:
         * there a name that does not resolve means the QR had no `ha_dial`, and a refusal comes
         * from the target behind the forwarder. [hasArg]: the line carries the forwarder's
         * `127.0.0.1:<port>`, so its reason names it.
         */
        fun failureRes(key: StatusKey, f: FailureClass, hasArg: Boolean = false): Int {
            val tailnet = key == StatusKey.PATH_TAILNET_FAILED
            return when (f) {
                FailureClass.NAME_NOT_RESOLVED -> if (tailnet) R.string.why_tailnet_no_such_host else R.string.why_name_not_resolved
                FailureClass.REFUSED -> if (tailnet) R.string.why_tailnet_refused else R.string.why_refused
                FailureClass.TIMED_OUT -> R.string.why_timed_out
                FailureClass.UNREACHABLE -> R.string.why_unreachable
                FailureClass.TLS -> R.string.why_tls
                FailureClass.CERTIFICATE -> R.string.why_certificate
                FailureClass.HOSTNAME -> R.string.why_hostname
                FailureClass.HTTP_STATUS -> R.string.why_http_status
                FailureClass.AUTH_REJECTED -> R.string.why_auth_rejected
                FailureClass.CONTRACT_MISMATCH -> if (hasArg) R.string.why_contract_mismatch_at else R.string.why_contract_mismatch
                FailureClass.CLOSED_EARLY -> R.string.why_closed_early
                FailureClass.TAILNET_NOT_UP -> R.string.why_tailnet_not_up
                FailureClass.NO_ROUTE -> R.string.why_no_route
                FailureClass.DIAL_TIMEOUT -> R.string.why_dial_timeout
                FailureClass.FORWARDER_TOKEN -> R.string.why_forwarder_token
                FailureClass.FORWARDER_DOWN -> if (hasArg) R.string.why_forwarder_down_at else R.string.why_forwarder_down
                FailureClass.SUPERSEDED -> R.string.why_superseded
                FailureClass.OTHER -> R.string.why_other
            }
        }
    }
}
