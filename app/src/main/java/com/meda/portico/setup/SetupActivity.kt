package com.meda.portico.setup

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.os.Process
import android.view.Gravity
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.meda.portico.PorticoApp
import com.meda.portico.R
import com.meda.portico.core.auth.EnrollStatus
import com.meda.portico.core.enroll.SettingsKeys
import com.meda.portico.core.enroll.SettingsSnapshot
import com.meda.portico.core.fcm.BootResult
import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.reg.OfflineOffer
import com.meda.portico.core.reg.OfflineRegistration
import com.meda.portico.core.enroll.SetupPlan
import com.meda.portico.core.enroll.SetupResult
import com.meda.portico.core.netpolicy.ConnectionMode
import com.meda.portico.core.netpolicy.NetClass
import com.meda.portico.core.netpolicy.NetworkChoices
import com.meda.portico.core.netpolicy.VideoMode
import com.meda.portico.core.ring.Channel
import com.meda.portico.core.ring.LinkState
import com.meda.portico.core.ring.SetupStatus
import com.meda.portico.core.ring.StatusBoard
import com.meda.portico.core.ring.NotifPermission
import com.meda.portico.core.ring.StatusFacts
import com.meda.portico.core.ring.StatusGroup
import com.meda.portico.core.ring.StatusItem
import com.meda.portico.core.traffic.TrafficCategory
import com.meda.portico.core.traffic.TrafficPeriod
import com.meda.portico.core.traffic.TrafficTable
import com.meda.portico.core.traffic.TrafficTableBlock
import com.meda.portico.core.panel.EntryAccess
import com.meda.portico.core.panel.PanelSettingsGuard
import com.meda.portico.core.panel.SettingsEntry
import com.meda.portico.core.pause.PauseRules
import com.meda.portico.pause.PauseNotices
import com.meda.portico.core.scan.AfterAsk
import com.meda.portico.core.scan.CameraPermissionPlan
import com.meda.portico.core.scan.ScanHandoff
import com.meda.portico.core.scan.ScanIntake
import com.meda.portico.core.scan.ScanNotice
import com.meda.portico.core.scan.ScanOutcome
import com.meda.portico.core.scan.ScanStart
import com.meda.portico.core.scan.ScanVerdict
import com.meda.portico.ring.Channels
import com.meda.portico.ring.PhonePermissions
import com.meda.portico.store.PrefsStateFile
import com.meda.portico.store.Settings
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P2's Setup (plan §3.1, §3.4, §3.6, §7 Q4). The whole screen scrolls.
 *
 * - **Status (0.6.70):** two groups. "Home Assistant" is one section: the connection checks, then plain label: value
 *   facts (address, sign-in, phone name, app version), merged from the old "This phone" block; "Push ring notification"
 *   (Firebase, registration, last push). The phone/ring configuration rows are not status and are gone. Refreshed on
 *   resume and every 2 s while visible; `portico/push/status` is asked on resume. All logic is `:core`'s [StatusBoard].
 * - **POST_NOTIFICATIONS** (Android 13+) is requested from here: by the Notifications button of Settings > Android
 *   settings, and once on the first open of this screen. "Not asked yet" is its own state, never read as "refused".
 * - **PR-C, below:** two paste fields (the enrollment text from Home Assistant, and an optional
 *   google-services.json), Save, the validation result, and what is configured: the HA host and
 *   "token present / absent", never the token itself. After a successful save both fields are
 *   cleared; they have `saveEnabled="false"` so their text is not kept in saved instance state.
 *
 * - **PR-J:** a scanned or pasted `ha_url` must pass `:core`'s `UrlPolicy` (https always, plain
 *   http only on a private network); a refused one stops the save with the policy's reason. A
 *   plain-http one saves, and "Unencrypted connection (plain http) on your own network" stays
 *   under the configured host and in the status lines while it is stored.
 *
 * - **PR-C2:** "Scan the QR code" opens [ScanActivity]. A scanned code goes through exactly the
 *   paste field's path ([ScanIntake] -> `SetupPlan.evaluate`, with the google-services field as
 *   it stands): the same messages and the same save. The scanned text is never put in the field,
 *   never stored, never logged. CAMERA is asked here, at the moment of use, after a line that says
 *   why; a refusal, "don't ask again" or no camera leaves a message and the paste field.
 *
 * - **Offline registration (2026-10-07):** under the push status, a block shown only when `:core`'s
 *   [OfflineRegistration] says this phone cannot reach Home Assistant (configured, not CONNECTED
 *   for 15 s, a Firebase value in hand). With a stored `enroll_id` it offers "Copy registration
 *   string" (plain clipboard, NOT marked sensitive: the user pastes it into HA); without one it
 *   asks for a fresh QR scan. The string is never shown, logged or put in a status line.
 *
 * - **Pause (2026-10-09), first on the screen:** "Pause this phone" with Pause for 1 hour / 4
 *   hours / until I resume; while paused an obvious "Paused until HH:MM / until you resume" line
 *   and Resume instead. The work is `AppGraph.pause` / `resume`; this only asks and redraws.
 *
 * - **DEV-544, one-time-code enrolment:** a scanned or pasted format-2 QR is saved like any other (address, name, Firebase,
 *   tailnet key) but its code waits in the encrypted store; `AppGraph.redeemPendingCode` redeems it in the background and
 *   [renderEnroll] shows how that is going ("Signing this phone in...", the contract's failure wording). The phone's old
 *   login is untouched until the redemption has succeeded. Opening this screen asks for a pending redemption to continue.
 *
 * Logs nothing.
 */
class SetupActivity : AppCompatActivity() {

    /** Null until the background open (APP-L5) has finished, and for good if the Keystore is unusable. */
    private var settings: Settings? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Everything a refresh reads from Settings (decrypts keys), gathered off the main thread. */
    private class SettingsReading(
        val installationId: String?,
        val snapshot: SettingsSnapshot?,
        val dialIp: String?,
        val offer: OfflineOffer,
    )

    /** DEV-807: the protected entries were unlocked by a long press and a confirmation; until this screen is left. */
    private var panelUnlocked = false

    /** DEV-807: the Settings-backed controls finished loading (until then they stay disabled and Panel mode does not touch them). */
    private var controlsLoaded = false

    private val refreshExec = Executors.newSingleThreadExecutor { r -> Thread(r, "portico-setup-refresh").apply { isDaemon = true } }
    private val refreshing = AtomicBoolean(false)
    private lateinit var checks: SetupChecks

    /** "0.6.70-alpha (code 6700)": the App version fact of the Home Assistant section. */
    private var versionText = ""

    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        refreshChecks()
    }

    // PR-C2: the camera prompt, and the scanner's result (the code comes through ScanHandoff).
    private val cameraRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val rationale = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        when (CameraPermissionPlan.afterAsk(granted, rationale)) {
            AfterAsk.OPEN_SCANNER -> openScanner()
            AfterAsk.DENIED -> showResult(getString(R.string.scan_denied))
            AfterAsk.DENIED_PERMANENTLY -> {
                showResult(getString(R.string.scan_denied_permanently))
                AlertDialog.Builder(this)
                    .setMessage(R.string.scan_denied_permanently)
                    .setPositiveButton(R.string.fix_open_app_details) { _, _ -> openAppSettings() }
                    .setNegativeButton(R.string.scan_camera_not_now, null)
                    .show()
            }
        }
    }

    /**
     * DEV-811 P2: "Call audio": Wideband | Compatibility, and under Advanced the path on the home network (Automatic | Frames | WebRTC).
     * The choices live in plain prefs ([com.meda.portico.core.call.CallAudioConfig]), so they work before the Keystore has opened. A
     * forced path resets to Automatic after 24 hours; the "wideband failed" mark shows as a line under the quality strip.
     */
    private fun wireCallAudio() {
        val config = PorticoApp.graph(this).callAudio
        val quality = mapOf(false to R.string.setup_callaudio_quality_wideband, true to R.string.setup_callaudio_quality_compat)
        val path = mapOf(
            com.meda.portico.core.call.PathChoice.AUTO to R.string.setup_callaudio_path_auto,
            com.meda.portico.core.call.PathChoice.FRAMES to R.string.setup_callaudio_path_frames,
            com.meda.portico.core.call.PathChoice.WEBRTC to R.string.setup_callaudio_path_webrtc,
        )
        val note = findViewById<TextView>(R.id.setup_callaudio_note)
        fun renderNote() {
            when (config.mark()) {
                com.meda.portico.core.call.MarkState.PERMANENT -> { note.setText(R.string.setup_callaudio_note_permanent); note.visibility = View.VISIBLE }
                com.meda.portico.core.call.MarkState.TEMP -> { note.setText(R.string.setup_callaudio_note_temp); note.visibility = View.VISIBLE }
                com.meda.portico.core.call.MarkState.NONE -> note.visibility = View.GONE
            }
        }
        // The first reads are prefs, off the Keystore; a failure leaves the strips at their defaults rather than crash the screen.
        val compat = try { config.compatibility() } catch (_: Exception) { false }
        val chosenPath = try { config.pathChoice() } catch (_: Exception) { com.meda.portico.core.call.PathChoice.AUTO }
        bindChoices(findViewById(R.id.setup_callaudio_quality), listOf(false, true), quality, compat) {
            refreshExec.execute { config.setCompatibility(it); journalSetup(); handler.post { renderNote() } }
        }
        bindChoices(findViewById(R.id.setup_callaudio_path), com.meda.portico.core.call.PathChoice.entries, path, chosenPath) {
            refreshExec.execute { config.setPathChoice(it); journalSetup() }
        }
        // DEV-811: the link race switch (default on), beside the call-audio choices
        val race = try { config.linkRace() } catch (_: Exception) { true }
        bindChoices(
            findViewById(R.id.setup_linkrace), listOf(true, false),
            mapOf(true to R.string.setup_linkrace_on, false to R.string.setup_linkrace_off), race,
        ) { refreshExec.execute { config.setLinkRace(it); journalSetup() } }
        // the owner 2026-11-21: Answer without unlocking (default on)
        val ansNoLock = try { config.answerWithoutUnlock() } catch (_: Exception) { true }
        bindChoices(
            findViewById(R.id.setup_answer_nolock), listOf(true, false),
            mapOf(true to R.string.setup_answer_nolock_on, false to R.string.setup_answer_nolock_off), ansNoLock,
        ) { refreshExec.execute { config.setAnswerWithoutUnlock(it); journalSetup() } }
        // the owner 2026-11-21: Unlock when answering (default on): the in-call screen asks for the unlock once (face / fingerprint)
        val unlockAns = try { config.unlockWhenAnswering() } catch (_: Exception) { true }
        bindChoices(
            findViewById(R.id.setup_unlock_answer), listOf(true, false),
            mapOf(true to R.string.setup_unlock_answer_on, false to R.string.setup_unlock_answer_off), unlockAns,
        ) { refreshExec.execute { config.setUnlockWhenAnswering(it); journalSetup() } }
        renderNote()
    }

    /** 0.6.92: a call-audio or reconnect choice changed: one settings snapshot in the Journal. */
    private fun journalSetup() {
        try { com.meda.portico.PorticoApp.graph(this).journalConfig("setup") } catch (_: Exception) { }
    }

    /** 0.6.64: "Ring control (Android)": opens Android's page of the door-call ring channel (ringtone, vibration, Do Not Disturb override). */
    private fun wireRingControl() {
        findViewById<Button>(R.id.setup_ring_control).setOnClickListener {
            Channels.ensure(this) // the channel must exist for its page to open
            try {
                startActivity(Channels.settingsIntent(this, Channel.RING))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.fix_no_settings_screen, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 0.6.69: "Android settings": one button per Android system page, each opening the intent the Status button used to. */
    private fun wireAndroidSettings() {
        val pages = listOf(
            R.id.setup_android_app to { AndroidPages.appDetails(this) },
            R.id.setup_android_calls to { AndroidPages.fullScreen(this) },
            R.id.setup_android_status_channel to { AndroidPages.statusChannel(this) },
            R.id.setup_android_overlay to { AndroidPages.overOtherApps(this) },
        )
        for ((id, intent) in pages) findViewById<Button>(id).setOnClickListener { AndroidPages.open(this, intent()) }
        // 0.6.70: the notification permission request left the Status screen with the rest of the ring configuration. It lives here:
        // while the system prompt can still be shown (never asked, or refused once) this button shows it, else Android's page opens.
        findViewById<Button>(R.id.setup_android_notifications).setOnClickListener {
            if (PhonePermissions.phoneState(this, askedBefore()).notifPermission.promptsFirst) {
                requestNotifications()
            } else {
                AndroidPages.open(this, AndroidPages.notifications(this))
            }
        }
    }

    /**
     * 0.6.70: nothing else asks for POST_NOTIFICATIONS (the Status "Allow notifications" button is gone), so the first time this
     * screen opens on a phone that was never asked, the prompt is shown once. [KEY_NOTIF_ASKED] makes it once.
     */
    private fun askNotificationsOnFirstOpen() {
        if (PhonePermissions.phoneState(this, askedBefore()).notifPermission == NotifPermission.NOT_ASKED) requestNotifications()
    }

    /** One toggle button per [values] in [group], labelled by [labels], [current] selected; [onPick] runs on a tap (never while the buttons are being set up). */
    private fun <T : Any> bindChoices(group: ChoiceStrip, values: List<T>, labels: Map<T, Int>, current: T, onPick: (T) -> Unit) =
        group.bind(values, labels, current, onPick)

    /** 0.6.58: the connection and video rows of the three network classes, from Settings (read off the main thread by the caller). */
    private fun wireNetworkRows(s: Settings, choices: NetworkChoices) {
        val conn = mapOf(ConnectionMode.STAY to R.string.conn_stay, ConnectionMode.PUSH to R.string.conn_push)
        val video = mapOf(
            VideoMode.LIVE_BIG to R.string.video_live_big, VideoMode.LIVE_SMALL to R.string.video_live_small,
            VideoMode.STILLS to R.string.video_stills, VideoMode.OFF to R.string.video_off,
        )
        val rows = listOf(
            Triple(NetClass.WIFI, R.id.setup_conn_wifi, R.id.setup_video_wifi),
            Triple(NetClass.MOBILE, R.id.setup_conn_mobile, R.id.setup_video_mobile),
            Triple(NetClass.ROAMING, R.id.setup_conn_roaming, R.id.setup_video_roaming),
        )
        for ((cls, connId, videoId) in rows) {
            bindChoices(findViewById(connId), ConnectionMode.entries, conn, choices.connection(cls)) {
                refreshExec.execute { s.setConnection(cls, it); handler.post { PorticoApp.graph(this).onNetworkChoicesChanged() } }
            }
            bindChoices(findViewById(videoId), VideoMode.entries, video, choices.video(cls)) {
                refreshExec.execute { s.setVideo(cls, it); handler.post { PorticoApp.graph(this).onNetworkChoicesChanged() } }
            }
        }
    }

    private val scanResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val failure = when (r.resultCode) {
            ScanActivity.RESULT_NO_CAMERA -> ScanOutcome.NoCamera
            ScanActivity.RESULT_CAMERA_FAILED -> ScanOutcome.CameraFailed
            else -> null
        }
        onScanned(ScanIntake.fromActivityResult(r.resultCode == RESULT_OK, failure))
    }

    private val tick = object : Runnable {
        override fun run() {
            refreshChecks()
            renderTraffic()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SEC-06: the Setup fields hold a pasted enrollment (a token, a one-time code, an auth key): no screenshot, recents thumbnail or screen capture.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_setup)
        wireScreens()
        checks = SetupChecks(this, ::requestNotifications, ::closeProcess)
        wirePause()
        wirePanel()
        wireJournal()
        wireTraffic()
        wireConnLog()
        wireRingControl()
        wireCallAudio()
        wireAndroidSettings()
        if (savedInstanceState == null) askNotificationsOnFirstOpen()

        val status = findViewById<TextView>(R.id.setup_status)
        val enrollment = findViewById<EditText>(R.id.setup_enrollment)
        val result = findViewById<TextView>(R.id.setup_result)
        val save = findViewById<Button>(R.id.setup_save)
        val scan = findViewById<Button>(R.id.setup_scan)

        // APP-L5: the Keystore open and the first decrypts are off the main thread. Until they are done
        // the Settings-backed controls are disabled and the status says so; Pause is already live.
        val houseReceive = findViewById<SwitchCompat>(R.id.setup_house_receive_switch)
        val houseDnd = findViewById<SwitchCompat>(R.id.setup_house_dnd_switch)
        val netGroups = listOf(
            R.id.setup_conn_wifi, R.id.setup_video_wifi, R.id.setup_conn_mobile, R.id.setup_video_mobile, R.id.setup_conn_roaming, R.id.setup_video_roaming,
        ).map { findViewById<ChoiceStrip>(it) }
        val controls = listOf<View>(houseReceive, houseDnd, save, scan) + netGroups
        controls.forEach { it.isEnabled = false }
        showNote(status, getString(R.string.setup_loading))
        PorticoApp.graph(this).withSettings { s ->
            if (s != null) s.installationId // mint it on first launch; it never changes after
            val first = try {
                s?.let { arrayOf(it.houseReceive, it.houseShareDnd) to it.snapshot() }
            } catch (e: Exception) {
                null
            }
            val choices = try { s?.networkChoices() } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                settings = s
                if (s == null || first == null || choices == null) {
                    showNote(status, getString(R.string.setup_storage_unavailable)) // the controls stay disabled
                    return@runOnUiThread
                }
                controls.forEach { it.isEnabled = true }
                controlsLoaded = true
                wireNetworkRows(s, choices)
                // M7 3b-B: the two house-call switches (their group is shown only while the server has house calls on for this phone).
                houseReceive.isChecked = first.first[0]
                houseReceive.setOnCheckedChangeListener { _, checked ->
                    s.houseReceive = checked
                    PorticoApp.graph(this).onHouseSettingsChanged()
                }
                houseDnd.isChecked = first.first[1]
                houseDnd.setOnCheckedChangeListener { _, checked ->
                    s.houseShareDnd = checked
                    PorticoApp.graph(this).onHouseSettingsChanged()
                }
                status.visibility = View.GONE // the facts it used to list are rows of the Home Assistant section now

                save.setOnClickListener {
                    when (val r = SetupPlan.evaluate(enrollment.text.toString(), "")) { // 0.6.58: Firebase comes in the enrollment's fcm block, no google-services.json on the phone
                        is SetupResult.Refused -> result.text = r.problems.joinToString("\n\n")
                        is SetupResult.Ok -> applySave(s, r, R.string.setup_saved)
                    }
                }
                scan.setOnClickListener { onScanTapped() }
                findViewById<Button>(R.id.setup_offline_copy).setOnClickListener { copyRegistrationString() }
                renderPanel()
                refreshChecks()
            }
        }
    }

    override fun onDestroy() {
        refreshExec.shutdown()
        super.onDestroy()
    }

    // ---- push and ring journal (0.5.12-alpha) --------------------------------------------------

    /** A-10: the mobile-data meter, read from the app's own ledger (no enrollment, no Keystore). Resettable. */
    private fun wireTraffic() {
        findViewById<Button>(R.id.setup_traffic_reset).setOnClickListener {
            PorticoApp.graph(this).traffic.meter.reset(System.currentTimeMillis())
            renderTraffic()
            showResult(getString(R.string.setup_traffic_reset_done))
        }
        renderTraffic()
    }

    /** What the table shows now; the 2 s tick rebuilds the table only when this changes. */
    private var trafficShown: Pair<List<TrafficTableBlock>, String>? = null

    /** 0.6.70: "Data used by this app" as a table: per period a header row (period, Mobile, Wi-Fi) and a row each for video, calls, other, total. */
    private fun renderTraffic() {
        val t = PorticoApp.graph(this).traffic
        t.sample() // fold in what has moved since the last tick, so the numbers are current
        val now = System.currentTimeMillis()
        val blocks = TrafficTable.blocks(t.meter, now)
        val since = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(t.meter.sinceMs(now)))
        if (trafficShown == blocks to since) return
        trafficShown = blocks to since
        val table = findViewById<LinearLayout>(R.id.setup_traffic_rows)
        table.removeAllViews()
        val dp = resources.displayMetrics.density
        // 0.6.71: every cell is 0dp wide and weighted (label 30 %, Mobile 35 %, Wi-Fi 35 %), single line, shrinking from 14sp to 11sp,
        // so no value can push a column off the screen at any width or font scale.
        fun cell(text: String, weight: Float, bold: Boolean, end: Boolean, maxSp: Int = 14, minSp: Int = 11) = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            this.text = text
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = if (end) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
            setSingleLine(true)
            ellipsize = null
            val p = (4 * dp).toInt()
            setPadding(p, p / 2, p, p / 2)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        fun row(vararg cells: TextView) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            cells.forEach { addView(it) }
        }
        for ((i, block) in blocks.withIndex()) {
            val label = when (block.period) {
                TrafficPeriod.TODAY -> getString(R.string.setup_traffic_today)
                TrafficPeriod.MONTH -> getString(R.string.setup_traffic_month)
                TrafficPeriod.SINCE_RESET -> getString(R.string.setup_traffic_since, since)
            }
            if (i > 0) table.addView(View(this).apply { // thin divider between blocks, in the text colour at low alpha
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, (1 * dp).toInt())).also {
                    it.topMargin = (10 * dp).toInt()
                }
                setBackgroundColor(ColorUtils.setAlphaComponent(TextView(this@SetupActivity).currentTextColor, 0x33))
            })
            // The period is a heading row of its own, full width, so a long "Since 9 Nov 2026" cannot squeeze the value columns.
            table.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                text = label
                setTypeface(typeface, Typeface.BOLD)
                setSingleLine(true)
                val p = (4 * dp).toInt()
                setPadding(p, (10 * dp).toInt(), p, 0)
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 12, 16, 1, TypedValue.COMPLEX_UNIT_SP)
            })
            table.addView(row(
                cell("", 0.30f, bold = true, end = false),
                cell(getString(R.string.setup_traffic_mobile), 0.35f, bold = true, end = true),
                cell(getString(R.string.setup_traffic_wifi), 0.35f, bold = true, end = true),
            ))
            for (r in block.rows) {
                val total = r.category == TrafficCategory.TOTAL
                val name = when (r.category) {
                    TrafficCategory.VIDEO -> R.string.setup_traffic_video
                    TrafficCategory.CALLS -> R.string.setup_traffic_calls
                    TrafficCategory.OTHER -> R.string.setup_traffic_other
                    TrafficCategory.TOTAL -> R.string.setup_traffic_total
                }
                table.addView(row(
                    cell(getString(name), 0.30f, bold = total, end = false),
                    cell(r.mobile, 0.35f, bold = total, end = true),
                    cell(r.wifi, 0.35f, bold = total, end = true),
                ))
            }
        }
    }

    /** 0.6.72: the journal is not listed; one button hands its text (and the last connect timeline) to the share sheet. */
    private fun wireJournal() {
        findViewById<Button>(R.id.setup_share_diagnostics).setOnClickListener { shareDiagnostics() }
    }

    private fun shareDiagnostics() {
        val graph = PorticoApp.graph(this)
        val lines = try { graph.pushJournal.lines() } catch (e: Exception) { emptyList() }
        val timeline = try { com.meda.portico.core.ha.ConnectTimeline.summary(graph.connectTimeline.snapshot()) } catch (e: Exception) { null }
        val text = (lines + listOfNotNull(timeline?.let { "connect: $it" })).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            startActivity(Intent.createChooser(send, null))
        } catch (e: ActivityNotFoundException) {
            // nothing to share with: nothing to say
        }
    }

    // ---- DEV-705: the connection log ------------------------------------------------------------

    /** Wired before Settings are opened: it needs no enrollment and no Keystore. */
    private fun wireConnLog() {
        val graph = PorticoApp.graph(this)
        val box = findViewById<SwitchCompat>(R.id.setup_connlog_switch)
        box.isChecked = graph.connLogEnabled
        box.setOnCheckedChangeListener { _, checked ->
            graph.connLogEnabled = checked
        }
    }

    // ---- pause ---------------------------------------------------------------------------------

    /** Wired before Settings are opened: pausing needs no enrollment and no Keystore. */
    private fun wirePause() {
        val graph = PorticoApp.graph(this)
        fun choose(lengthMs: Long) {
            if (!graph.pause(lengthMs)) showResult(getString(R.string.pause_failed))
            refreshChecks()
        }
        findViewById<Button>(R.id.setup_pause_1h).setOnClickListener { choose(PauseRules.ONE_HOUR) }
        findViewById<Button>(R.id.setup_pause_4h).setOnClickListener { choose(PauseRules.FOUR_HOURS) }
        findViewById<Button>(R.id.setup_pause_forever).setOnClickListener { choose(PauseRules.UNTIL_RESUMED) }
        findViewById<Button>(R.id.setup_pause_resume).setOnClickListener {
            graph.resume("Setup's Resume")
            refreshChecks()
        }
        renderPause()
    }

    // ---- DEV-807: Panel mode (the server's flag decides, D-20261121-73ff; no switch here) ----------------------------------

    private fun wirePanel() {
        val graph = PorticoApp.graph(this)
        // DEV-807 slice 2: the idle timeout. Only while Panel mode is on and the lock bar has been opened (long press + confirmation, as slice 1's
        // other protected entries); a tap steps through the choices.
        findViewById<View>(R.id.setup_panel_idle).setOnClickListener {
            if (!panelUnlocked) return@setOnClickListener
            val choices = com.meda.portico.core.panel.PanelFrontMachine.IDLE_CHOICES_MIN
            val now = graph.panelIdleMinutes()
            graph.setPanelIdleMinutes(choices.firstOrNull { it > now } ?: choices.first())
            renderPanel()
        }
        findViewById<View>(R.id.setup_panel_lock).setOnLongClickListener {
            confirm(R.string.panel_confirm_unlock_title, R.string.panel_confirm_unlock) {
                panelUnlocked = true
                renderPanel()
            }
            true
        }
        renderPanel()
    }

    private fun confirm(title: Int, message: Int, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.panel_confirm_yes) { _, _ -> onYes() }
            .setNegativeButton(R.string.panel_confirm_no, null)
            .show()
    }

    /** The Panel mode row, the lock bar, and which Settings-backed controls may be touched (core [PanelSettingsGuard]). */
    private fun renderPanel() {
        val panel = PorticoApp.graph(this).panelMode()
        findViewById<View>(R.id.setup_panel_heading).visibility = if (panel) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.setup_panel_row).apply { visibility = if (panel) View.VISIBLE else View.GONE; setText(R.string.panel_row_on) }
        val locked = PanelSettingsGuard.access(panel, SettingsEntry.ENROLMENT) == EntryAccess.LONG_PRESS_CONFIRM && !panelUnlocked
        findViewById<View>(R.id.setup_panel_lock).visibility = if (panel && !panelUnlocked) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.setup_panel_idle).apply {
            visibility = if (panel) View.VISIBLE else View.GONE
            text = getString(R.string.panel_idle_row, PorticoApp.graph(this@SetupActivity).panelIdleMinutes())
            isEnabled = panelUnlocked
        }
        if (!controlsLoaded) return // the loading code enables them, and calls this afterwards
        val guarded = listOf(
            R.id.setup_conn_wifi, R.id.setup_video_wifi, R.id.setup_conn_mobile, R.id.setup_video_mobile, R.id.setup_conn_roaming, R.id.setup_video_roaming,
            R.id.setup_scan, R.id.setup_enrollment, R.id.setup_save,
        )
        for (id in guarded) findViewById<View>(id).isEnabled = !locked
    }

    /** The paused line and Resume while paused; the three choices otherwise. */
    private fun renderPause() {
        val graph = PorticoApp.graph(this)
        if (PanelSettingsGuard.access(graph.panelMode(), SettingsEntry.PAUSE) == EntryAccess.HIDDEN) {
            // DEV-807: Panel mode does not offer Pause at all.
            for (id in listOf(R.id.setup_pause_heading, R.id.setup_pause_status, R.id.setup_pause_resume, R.id.setup_pause_choices, R.id.setup_pause_hint)) findViewById<View>(id).visibility = View.GONE
            return
        }
        findViewById<View>(R.id.setup_pause_heading).visibility = View.VISIBLE
        findViewById<View>(R.id.setup_pause_hint).visibility = View.VISIBLE
        val paused = graph.isPaused() // also notices a passed deadline (and resumes)
        val line = SetupStatus.pauseLines(graph.pauseKeeper.state(), System.currentTimeMillis(), PauseNotices::hhmm).firstOrNull()
        val status = findViewById<TextView>(R.id.setup_pause_status)
        if (paused && line != null) {
            status.text = if (line.arg != null) getString(SetupChecks.textRes(line.key), line.arg) else getString(SetupChecks.textRes(line.key))
            status.visibility = View.VISIBLE
        } else {
            status.visibility = View.GONE
        }
        findViewById<Button>(R.id.setup_pause_resume).visibility = if (paused) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.setup_pause_choices).visibility = if (paused) View.GONE else View.VISIBLE
    }

    // ---- offline registration ----------------------------------------------------------------

    /** The string currently offered; only ever handed to the clipboard. */
    private var offlineString: String? = null

    private fun renderOffline(offer: OfflineOffer) {
        val block = findViewById<LinearLayout>(R.id.setup_offline)
        val text = findViewById<TextView>(R.id.setup_offline_text)
        val copy = findViewById<Button>(R.id.setup_offline_copy)
        val copied = findViewById<TextView>(R.id.setup_offline_copied)
        val cannotReach = getString(R.string.offline_reg_cannot_reach)
        when (offer) {
            OfflineOffer.Hidden -> {
                offlineString = null
                block.visibility = View.GONE
                copied.visibility = View.GONE
            }
            OfflineOffer.ScanAgain -> {
                offlineString = null
                text.text = cannotReach + "\n\n" + getString(R.string.offline_reg_scan_again)
                copy.visibility = View.GONE
                copied.visibility = View.GONE
                block.visibility = View.VISIBLE
            }
            is OfflineOffer.Copy -> {
                if (offer.text != offlineString) copied.visibility = View.GONE // a new value: the old copy is stale
                offlineString = offer.text
                text.text = cannotReach + "\n\n" + getString(R.string.offline_reg_copy_instructions)
                copy.visibility = View.VISIBLE
                block.visibility = View.VISIBLE
            }
        }
    }

    private fun copyRegistrationString() {
        val value = offlineString ?: return
        val copied = findViewById<TextView>(R.id.setup_offline_copied)
        val ok = try {
            val cm = getSystemService(ClipboardManager::class.java)
            // Deliberately NOT ClipDescription.EXTRA_IS_SENSITIVE: the user must see and paste it into HA.
            cm?.setPrimaryClip(ClipData.newPlainText(getString(R.string.offline_reg_clip_label), value))
            cm != null
        } catch (e: Exception) {
            false // no detail kept: never let the value reach a log or a message
        }
        copied.setText(if (ok) R.string.offline_reg_copied else R.string.offline_reg_copy_failed)
        copied.visibility = View.VISIBLE
    }

    /** The one save path, for Save and for a scanned code (PR-C2). */
    private fun applySave(s: Settings, r: SetupResult.Ok, savedText: Int) {
        val result = findViewById<TextView>(R.id.setup_result)
        if (s.apply(r)) {
            findViewById<EditText>(R.id.setup_enrollment).text.clear()
            result.setText(savedText)
            // PR-E: boot Firebase / reconnect with the new values; say if a restart is needed.
            // DEV-557: a saved Tailscale key starts the join now (no key: nothing changes).
            val graph = PorticoApp.graph(this)
            graph.resetEnrollStatus()
            val boot = graph.onSettingsChanged(joinTailnet = r.joinsTailnet)
            if (r.redeemsCode) {
                // DEV-544: the code is saved (encrypted); redeem it now. The words say what is happening, not "saved".
                result.setText(R.string.setup_code_saved)
                graph.redeemPendingCode()
            }
            if (boot !is BootResult.Ready || boot.restartToApply) result.append("\n\n" + boot.statusLine())
            refreshChecks()
        } else {
            result.setText(R.string.setup_save_failed)
        }
    }

    // ---- PR-C2: scan the enrollment QR ---------------------------------------------------------

    private fun onScanTapped() {
        val hasCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val rationale = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        val asked = statePrefs().getBoolean(KEY_CAMERA_ASKED, false)
        when (CameraPermissionPlan.onScanTapped(hasCamera, granted, asked, rationale)) {
            ScanStart.NO_CAMERA -> showResult(getString(R.string.scan_no_camera))
            ScanStart.OPEN_SCANNER -> openScanner()
            ScanStart.ASK -> askCamera()
            ScanStart.EXPLAIN_THEN_ASK -> AlertDialog.Builder(this)
                .setTitle(R.string.scan_camera_why_title)
                .setMessage(R.string.scan_camera_why)
                .setPositiveButton(R.string.scan_camera_continue) { _, _ -> askCamera() }
                .setNegativeButton(R.string.scan_camera_not_now, null)
                .show()
        }
    }

    private fun askCamera() {
        statePrefs().edit().putBoolean(KEY_CAMERA_ASKED, true).commit()
        cameraRequest.launch(Manifest.permission.CAMERA)
    }

    private fun openScanner() {
        ScanHandoff.clear()
        scanResult.launch(Intent(this, ScanActivity::class.java))
    }

    /** The scanned text lives only inside this call; nothing here logs or keeps it. */
    private fun onScanned(outcome: ScanOutcome) {
        val s = settings ?: return
        when (val v = ScanIntake.evaluate(outcome, "")) {
            is ScanVerdict.Save -> {
                applySave(s, v.plan, R.string.setup_scan_saved)
                scrollToResult()
            }
            is ScanVerdict.Refused -> showResult(v.problems.joinToString("\n\n"))
            is ScanVerdict.Notice -> when (v.notice) {
                ScanNotice.CANCELLED -> Unit
                ScanNotice.NO_CAMERA -> showResult(getString(R.string.scan_no_camera))
                ScanNotice.CAMERA_FAILED -> showResult(getString(R.string.scan_camera_failed))
                ScanNotice.LOST -> showResult(getString(R.string.scan_lost))
                ScanNotice.EMPTY_CODE -> showResult(getString(R.string.scan_empty_code))
            }
        }
    }

    private fun showResult(text: String) {
        findViewById<TextView>(R.id.setup_result).text = text
        scrollToResult()
    }

    private fun scrollToResult() {
        val scroll = findViewById<ScrollView>(R.id.setup_scroll)
        val result = findViewById<TextView>(R.id.setup_result)
        scroll.post { scroll.smoothScrollTo(0, result.top) }
    }

    private fun openAppSettings() {
        try {
            startActivity(AndroidPages.appDetails(this))
        } catch (e: android.content.ActivityNotFoundException) {
            showResult(getString(R.string.fix_no_settings_screen))
        }
    }

    // Visibility is counted onStart/onStop, as Home does: onPause fires for a dialog or the permission prompt while we are still visible.
    override fun onStart() {
        super.onStart()
        com.meda.portico.panel.PanelScreen.keepOn(this) // DEV-807: Panel mode holds the screen on while this screen is in front
        PorticoApp.graph(this).uiStarted()
    }

    override fun onStop() {
        PorticoApp.graph(this).uiStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val graph = PorticoApp.graph(this)
        graph.refreshPushStatus() // refused while paused
        graph.onPauseChanged = { handler.post { refreshChecks() } } // the deadline or the notification resumed it
        graph.onEnrollChanged = { handler.post { refreshChecks() } } // DEV-544: the redemption moved on
        graph.redeemPendingCode() // DEV-544: continue a redemption the last run left pending (a no-op when there is none)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        PorticoApp.graph(this).onPauseChanged = null
        PorticoApp.graph(this).onEnrollChanged = null
        super.onPause()
    }

    /**
     * APP-L5: what needs Settings (decrypting keys, the registration string) is read on [refreshExec];
     * one refresh at a time, a tick that finds one running is skipped (the next one is 2 s away).
     */
    /** The house-call switches exist on screen only while the server says house calls are on for this phone; otherwise Setup is what it was. */
    private fun renderHouse() {
        findViewById<View>(R.id.setup_house_group).visibility = if (PorticoApp.graph(this).houseEnabled()) View.VISIBLE else View.GONE
    }

    private fun refreshChecks() {
        renderHouse()
        renderPause()
        if (!refreshing.compareAndSet(false, true)) return
        val s = settings
        val graph = PorticoApp.graph(this)
        try {
            refreshExec.execute {
                val reading = try {
                    SettingsReading(
                        installationId = s?.installationId,
                        snapshot = s?.snapshot(),
                        dialIp = graph.tailnetDialIp(),
                        offer = if (s == null) OfflineOffer.Hidden else OfflineRegistration.decide(graph.offlineInputs()),
                    )
                } catch (e: Exception) {
                    null // a failed read shows nothing new; the next tick tries again
                }
                handler.post {
                    refreshing.set(false)
                    if (reading != null && !isFinishing && !isDestroyed) renderChecks(reading)
                }
            }
        } catch (e: RejectedExecutionException) {
            refreshing.set(false) // the screen is gone
        }
    }

    private fun renderChecks(reading: SettingsReading) {
        val graph = PorticoApp.graph(this)
        val paused = graph.isPaused()
        val link = try {
            LinkState.valueOf(graph.signaling.state.name)
        } catch (e: IllegalArgumentException) {
            LinkState.STOPPED
        }
        val own = SetupStatus.ownRow(graph.pushStatus, reading.installationId)
        graph.refreshTailnet() // DEV-557: off this thread; the line below shows the last refresh
        val pushLines = SetupStatus.pushLines(graph.boot, link, graph.registrationStatus, own) +
            SetupStatus.urlLines(reading.snapshot?.haUrlCheck) + // PR-J
            SetupStatus.tailnetLines(graph.tailnet.state, graph.linkPath, paused) + // DEV-557
            SetupStatus.pathLines( // why each path did (not) open, and the route to the dial IP
                graph.linkDiagnostics.last(LinkPath.DIRECT),
                graph.linkDiagnostics.last(LinkPath.TAILNET),
                graph.tailnet.state,
                graph.tailnet.route,
                reading.dialIp,
                System.currentTimeMillis(),
                paused,
                graph.connectTimeline.snapshot(), // DEV-694 P0
            )
        renderEnroll(graph.enrollStatus)
        // 0.6.70: one board for all lines and facts, so a fact reported twice shows once; two groups. The phone and ring
        // configuration rows ("Ringing on this phone") are not status and are gone; the checks that gate ringing still run in the notifier.
        val board = StatusBoard.items(pushLines, StatusFacts.of(reading.snapshot, versionText))
        checks.render(findViewById<LinearLayout>(R.id.setup_ha_checks), board.getValue(StatusGroup.HOME_ASSISTANT))
        checks.render(findViewById<LinearLayout>(R.id.setup_push_checks), board.getValue(StatusGroup.PUSH))
        renderOffline(reading.offer)
    }

    /** APP-04: which half of the old screen this instance shows (entered from the Home menu / status line; no cross-links). */
    private fun wireScreens() {
        val screen = SettingsStatusSplit.Screen.of(intent?.getStringExtra(SettingsStatusSplit.EXTRA_SCREEN))
        val status = screen == SettingsStatusSplit.Screen.STATUS
        findViewById<View>(R.id.setup_settings_screen).visibility = if (status) View.GONE else View.VISIBLE
        findViewById<View>(R.id.setup_status_screen).visibility = if (status) View.VISIBLE else View.GONE
        setTitle(if (status) R.string.status_title else R.string.setup_title)
        val info = packageManager.getPackageInfo(packageName, 0)
        versionText = getString(R.string.fact_version_value, info.versionName ?: "?", androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info).toString())
    }

    /** DEV-544: how the redemption of a scanned one-time code is going. Hidden when there is nothing to say. */
    private fun renderEnroll(st: EnrollStatus) {
        val view = findViewById<TextView>(R.id.setup_enroll_status)
        val text = when (st) {
            EnrollStatus.Idle -> null
            EnrollStatus.Redeeming -> getString(R.string.enroll_redeeming)
            is EnrollStatus.Retrying -> getString(R.string.enroll_retrying, st.why.message)
            EnrollStatus.Done -> getString(R.string.enroll_done)
            is EnrollStatus.Failed -> st.message
        }
        view.text = text.orEmpty()
        view.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        statePrefs().edit().putBoolean(KEY_NOTIF_ASKED, true).commit()
        notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun askedBefore(): Boolean = statePrefs().getBoolean(KEY_NOTIF_ASKED, false)

    private fun statePrefs() = getSharedPreferences(PrefsStateFile.FILE, Context.MODE_PRIVATE)

    /** Plan §3.1: a changed Firebase config applies at the next process start. */
    private fun closeProcess() {
        finishAffinity()
        Process.killProcess(Process.myPid())
    }

    /** A note above the Home Assistant section, shown only while Settings load or cannot be opened. */
    private fun showNote(view: TextView, text: String) {
        view.text = text
        view.visibility = View.VISIBLE
    }

    companion object {
        /** Portico has shown the POST_NOTIFICATIONS dialog once ("not asked" is not "refused"). */
        const val KEY_NOTIF_ASKED = "notif_permission_asked_v1"

        /** PR-C2: Portico has shown the CAMERA prompt once (tells "never asked" from "don't ask again"). */
        const val KEY_CAMERA_ASKED = "camera_permission_asked_v1"
    }
}
