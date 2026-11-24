package com.meda.portico

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.meda.portico.call.AndroidAudioRoute
import com.meda.portico.call.AndroidPcmDevice
import com.meda.portico.call.LinkFrameChannel
import com.meda.portico.call.TailnetAwayMedia
import com.meda.portico.call.CallService
import com.meda.portico.call.LinkCommandPort
import com.meda.portico.call.WebRtcMedia
import com.meda.portico.core.auth.AccessTokens
import com.meda.portico.core.auth.CompleteResult
import com.meda.portico.core.auth.EnrollStatus
import com.meda.portico.core.auth.EnrollmentRedemption
import com.meda.portico.core.auth.HomeVerifier
import com.meda.portico.core.auth.LoginStore
import com.meda.portico.core.auth.PathedHttp
import com.meda.portico.core.auth.PendingEnrollment
import com.meda.portico.core.auth.PendingStore
import com.meda.portico.core.auth.RedeemClient
import com.meda.portico.core.auth.RefreshClient
import com.meda.portico.core.auth.VerifiedConnector
import com.meda.portico.core.auth.VerifiedHttpPost
import com.meda.portico.core.call.AnswerConfig
import com.meda.portico.core.call.AnswerCoordinator
import com.meda.portico.core.call.FrameMedia
import com.meda.portico.core.call.HouseCommands
import com.meda.portico.core.panel.FrontAction
import com.meda.portico.core.panel.FrontEvent
import com.meda.portico.core.panel.FrontReason
import com.meda.portico.core.panel.FrontReasons
import com.meda.portico.core.panel.PanelCallCoordinator
import com.meda.portico.core.panel.PanelCallGate
import com.meda.portico.core.panel.PanelCallSink
import com.meda.portico.core.panel.PanelCallWire
import com.meda.portico.core.panel.PanelFlag
import com.meda.portico.core.panel.RingbackPolicy
import com.meda.portico.core.panel.PanelFrontMachine
import com.meda.portico.core.panel.PanelRingLog
import com.meda.portico.core.panel.PanelSlug
import com.meda.portico.core.panel.PanelWake
import com.meda.portico.core.panel.RingPath
import com.meda.portico.core.panel.SlugStep
import com.meda.portico.core.panel.HeartbeatOutcome
import com.meda.portico.core.panel.PanelFacts
import com.meda.portico.core.panel.PanelHeartbeatCommands
import com.meda.portico.core.panel.PanelHeartbeatDriver
import com.meda.portico.core.panel.PanelStream
import com.meda.portico.core.panel.PanelVolumeKeeper
import com.meda.portico.panel.AndroidStreamLevels
import com.meda.portico.panel.PanelAndroidFacts
import com.meda.portico.panel.PrefsSavedLevels
import com.meda.portico.core.call.HouseDnd
import com.meda.portico.core.call.HouseDndReporter
import com.meda.portico.core.call.HouseEventSink
import com.meda.portico.core.call.HouseMedia
import com.meda.portico.core.call.HouseRingCoordinator
import com.meda.portico.core.content.HouseGate
import com.meda.portico.core.push.HouseStep
import com.meda.portico.call.LinkHouseSignal
import com.meda.portico.ring.HouseRingNotifier
import com.meda.portico.core.call.PlaceConfig
import com.meda.portico.core.call.PlaceCoordinator
import com.meda.portico.core.call.PlaceSession
import com.meda.portico.core.call.AnswerPathTalkMedia
import com.meda.portico.core.call.TalkConfig
import com.meda.portico.core.call.TalkCoordinator
import com.meda.portico.core.call.TalkMediaPort
import com.meda.portico.core.call.TalkSession
import com.meda.portico.core.content.HomeModel
import com.meda.portico.core.content.GateCall
import com.meda.portico.core.content.TalkGate
import com.meda.portico.core.call.AnswerSession
import com.meda.portico.core.call.AnswerSweep
import com.meda.portico.core.call.RefCountedAudioRoute
import com.meda.portico.core.call.Cancellable
import com.meda.portico.core.ha.CallEvent
import com.meda.portico.core.call.SerialMedia
import com.meda.portico.core.enroll.SettingsKeys
import com.meda.portico.core.ha.CallStateSink
import com.meda.portico.core.fcm.BootResult
import com.meda.portico.core.content.ContentRepository
import com.meda.portico.core.content.SizeMemory
import com.meda.portico.store.PrefsSizeStore
import com.meda.portico.core.live.LiveMemory
import com.meda.portico.store.PrefsLiveStore
import com.meda.portico.connlog.AndroidExitSource
import com.meda.portico.core.connlog.ExitReporter
import com.meda.portico.core.connlog.StartupMarker
import com.meda.portico.connlog.ConnNetWatcher
import com.meda.portico.connlog.PrefsConnLogStore
import com.meda.portico.core.connlog.ConnEnv
import com.meda.portico.core.connlog.ConnLog
import com.meda.portico.core.connlog.CrashHandler
import com.meda.portico.core.connlog.CrashRateLimit
import com.meda.portico.core.connlog.CrashRecord
import com.meda.portico.core.connlog.ConnLogCommands
import com.meda.portico.core.connlog.ConnLogQueue
import com.meda.portico.core.connlog.ConnLogReplies
import com.meda.portico.core.connlog.ConnLogReply
import com.meda.portico.core.connlog.ConnLogSender
import com.meda.portico.core.connlog.ConnTracer
import com.meda.portico.core.connlog.JournalReporter
import com.meda.portico.core.connlog.TriggerKind
import com.meda.portico.core.ha.Clock
import com.meda.portico.core.ha.DirectWorksOutside
import com.meda.portico.core.ha.PathFactsReader
import com.meda.portico.core.ha.PathHint
import com.meda.portico.core.ha.ConnectTimeline
import com.meda.portico.core.ha.ContentCommands
import com.meda.portico.core.ha.ContractMismatch
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.PorticoLink
import com.meda.portico.core.ha.TalkStateSink
import com.meda.portico.core.push.PushJournal
import com.meda.portico.core.push.SocketCallSink
import com.meda.portico.core.reg.RegistrationConfig
import com.meda.portico.core.reg.RegistrationMachine
import com.meda.portico.fcm.FirebaseBoot
import com.meda.portico.fcm.JournalingCallSink
import com.meda.portico.fcm.SocketOutcomeRoute
import com.meda.portico.fcm.FirebaseRegistrationSource
import com.meda.portico.fcm.MessagingServiceLogic
import com.meda.portico.fcm.OutcomeDispatcher
import com.meda.portico.fcm.PushReceiver
import com.meda.portico.core.ha.PushCommands
import com.meda.portico.core.reg.OfflineInputs
import com.meda.portico.core.reg.OfflineRegistration
import com.meda.portico.core.reg.RegStatus
import com.meda.portico.core.reg.RegistrationStore
import com.meda.portico.core.ring.LinkState
import com.meda.portico.core.ring.DoorSlots
import com.meda.portico.ring.AndroidOutcomeHandler
import com.meda.portico.ring.Channels
import com.meda.portico.ring.PushEnvProbe
import com.meda.portico.ring.PhonePermissions
import com.meda.portico.ring.RingNotifier
import com.meda.portico.ring.RingState
import com.meda.portico.signaling.LogcatLog
import com.meda.portico.signaling.SignalingClient
import com.meda.portico.store.PrefsActiveCallStore
import com.meda.portico.store.PrefsCallStore
import com.meda.portico.store.PrefsPushJournalStore
import com.meda.portico.store.PrefsContentStore
import com.meda.portico.store.PrefsDoorsCacheStore
import com.meda.portico.store.PrefsRegistrationStore
import com.meda.portico.store.PrefsStateFile
import com.meda.portico.store.Settings
import com.meda.portico.store.SettingsConnectionConfig
import com.meda.portico.core.ha.FallbackConnector
import com.meda.portico.core.ha.LinkDiagnostics
import com.meda.portico.core.ha.LinkFailures
import com.meda.portico.core.ha.LinkPath
import com.meda.portico.core.tailnet.PendingKey
import com.meda.portico.core.tailnet.PendingKeyStore
import com.meda.portico.core.tailnet.TailnetController
import com.meda.portico.core.tailnet.TailnetHostname
import com.meda.portico.core.tailnet.TailnetTarget
import com.meda.portico.core.pause.PauseKeeper
import com.meda.portico.core.pause.PauseRules
import com.meda.portico.core.pause.PauseState
import com.meda.portico.pause.PauseNotices
import com.meda.portico.store.PrefsPauseStore
import com.meda.portico.signaling.OkHttpConnector
import com.meda.portico.signaling.OkHttpPost
import com.meda.portico.tailnet.TailnetPost
import com.meda.portico.core.netpolicy.ConnectionMode
import com.meda.portico.core.netpolicy.NetClass
import com.meda.portico.core.netpolicy.NetworkChoices
import com.meda.portico.core.netpolicy.NetworkPolicy
import com.meda.portico.core.netpolicy.VideoMode
import com.meda.portico.core.netpolicy.VideoVerdict
import com.meda.portico.core.stay.StayConnected
import com.meda.portico.core.stay.StayConnectedPolicy
import com.meda.portico.core.stay.ConfigFacts
import com.meda.portico.core.stay.StayJournalLines
import com.meda.portico.core.stay.StayJournalSink
import com.meda.portico.core.stay.StayPorts
import com.meda.portico.stay.ConnectionService
import com.meda.portico.stay.StayNetWatcher
import java.util.concurrent.atomic.AtomicInteger
import com.meda.portico.tailnet.AndroidNetworkFacts
import com.meda.portico.tailnet.PrefsDirectOutsideStore
import com.meda.portico.tailnet.TailnetConnector
import com.meda.portico.tailnet.TailnetEngines
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The process's one set of long-lived objects (PR-E), built in `PorticoApp.onCreate` before any
 * Activity, service or receiver of the process runs:
 *
 * - ONE [PrefsCallStore] and ONE persisted [DoorsCache], shared by the push path
 *   ([messaging]) and the socket path ([SocketCallSink] inside [PorticoLink]);
 * - ONE [SignalingClient] + [PorticoLink] + [RegistrationMachine]: started at once, it connects
 *   when Settings hold `ha_url` and a token (else it idles in NOT_CONFIGURED until [onSettingsChanged]),
 *   and the machine registers on EVERY `auth_ok` (D's rule). The Firebase value reaches the
 *   machine from `PorticoMessagingService.onRegistered` via [SignalingClient.post];
 * - ONE [OutcomeDispatcher]: the ring switch, the socket wake for a pushed ring
 *   ([SignalingClient.wake]) and PR-F's [AndroidOutcomeHandler] ([ring]: CallStyle ring,
 *   status notifications, the AlarmManager backstop), which replaced PR-E's logging handler;
 * - the last registration status and `portico/push/status` answer, for Setup (PR-F).
 *
 * - DEV-557, the tailnet client: ONE [TailnetController] over the engine `gocore.aar` provides
 *   (or the not-built one), and the socket's [FallbackConnector]: `ha_url` directly first, the
 *   tailnet second ([TailnetConnector]). The tailnet is woken only when the direct path fails and
 *   put back to sleep when it works. The tailnet code has no foreground service of its own (a push
 *   wakes the process, the socket's reconnect does the rest); the optional "Stay connected" service below
 *   only keeps the process alive. Tailnet work runs on its own threads, never the UI's.
 *
 * - PAUSE (Setup's "Pause this phone", 2026-10-09): ONE [PauseKeeper] on a private prefs file,
 *   asked (fail open) by every choke point: the socket ([SignalingClient] stays PAUSED), the
 *   tailnet ([TailnetController.ensureUp], [FallbackConnector]), the push path (every push is
 *   dropped first thing in [MessagingServiceLogic]) and the socket's call sink. [pause] commits
 *   the state BEFORE tearing anything down; [resume] does what a normal start does. The deadline
 *   is re-checked on process start, on boot, by the alarm and by whichever check runs first after it.
 *
 * - CONTENT (Home, 2026-10-12): ONE [ContentRepository] ([content]) holds the profile HA built for this
 *   phone (cameras and buttons), kept across restarts, the newest picture per camera (memory only) and
 *   the buttons in flight. The link's thread writes it; the Home screen listens ([ContentRepository.listen])
 *   and asks through [runAction], [requestSnapshot] and [refreshContent], which do nothing while paused
 *   or not connected: nothing is queued for later.
 *
 * - STAY CONNECTED (2026-10-15, NS-1..NS-3): an optional foreground service ([ConnectionService]) that
 *   keeps the process, and so the socket, alive in the background; see [stay]. It is started from an
 *   allowed context only (process start, boot, a push, a screen opening, Settings) and stays started
 *   while the Settings switch is on; the network only decides whether it HOLDS the socket (Wi-Fi,
 *   Ethernet) or RELEASES it (mobile data). Pause stops it.
 *
 * The registration's `fcm_project_id` is the RUNNING Firebase project ([BootResult.Ready]), so
 * an FID is never sent under a project it does not belong to.
 */
class AppGraph(private val app: Context) {

    private val log: HaLog = LogcatLog("Portico")

    // ---- Pause --------------------------------------------------------------------------------

    /** The resume runs here when a check notices a passed deadline (never on the asking thread). */
    private val pauseExec = Executors.newSingleThreadExecutor { r -> Thread(r, "portico-pause").apply { isDaemon = true } }

    /** The stored pause. Every read fails open (an unreadable flag is "not paused"). */
    val pauseKeeper = PauseKeeper(
        PrefsPauseStore(app.getSharedPreferences(PrefsPauseStore.FILE, Context.MODE_PRIVATE)),
        System::currentTimeMillis,
        onExpired = { pauseExec.execute { resume("the pause ran out") } },
    )

    private val pauseNotices = PauseNotices(app, log)

    /** Is this phone paused right now? Cheap, any thread; notices a passed deadline (and resumes). */
    fun isPaused(): Boolean = pauseKeeper.active()

    /** Setup's live update while it is visible (main thread is the caller's business). */
    @Volatile
    var onPauseChanged: (() -> Unit)? = null

    // APP-L5: ONE Settings instance for the whole process (the Keystore + EncryptedSharedPreferences open is
    // slow). Every reader (the push path, the link, FirebaseBoot, the screens) goes through [settings].
    private val settingsLock = Any()

    /** Panel mode is on: the SERVER says this device is a panel (D-20261121-73ff; [PanelFlag], [applyServerPanelFlag]). Cheap, any thread. */
    fun panelMode(): Boolean = panelModeOn

    /** Set by [com.meda.portico.panel.PanelFrontHost]: the server stopped calling this device a panel, release what wall mode holds on a open screen. */
    @Volatile
    var onPanelModeOff: (() -> Unit)? = null

    @Volatile
    private var settingsCache: Settings? = null

    /** [SystemClock.elapsedRealtime] of the last failed open, or -1 when none has failed. Guarded by [settingsLock]. */
    private var settingsFailedAtMs = -1L

    /** Screens open Settings here, never on the main thread (see [withSettings]). */
    private val settingsExec = Executors.newSingleThreadExecutor { r -> Thread(r, "portico-settings").apply { isDaemon = true } }

    /**
     * The encrypted settings, or null if the Keystore is unusable. Thread-safe and a single instance: a caller
     * that finds it unopened waits for the one open in progress instead of starting its own. After a FAILED open
     * the next try waits [SETTINGS_RETRY_MS] (a broken Keystore must not cost a failed open per push) unless
     * [retryNow] (a screen the user just opened; never on the main thread). The first open is at process start
     * (the registration config and the Firebase boot need it); nothing after that opens it on the UI thread.
     */
    fun settings(retryNow: Boolean = false): Settings? {
        settingsCache?.let { return it }
        synchronized(settingsLock) {
            settingsCache?.let { return it }
            val now = SystemClock.elapsedRealtime()
            if (!retryNow && settingsFailedAtMs >= 0 && now - settingsFailedAtMs < SETTINGS_RETRY_MS) return null
            val opened = Settings.open(app)
            if (opened != null) {
                settingsCache = opened
                try { if (opened.dropLegacyPanelMode()) legacyPanelSwitchDropped = true } catch (_: Exception) { }
                // 0.6.64: the ring switch has no screen; a phone that still has it OFF (never opened 0.6.58-0.6.63) rings normally.
                try { if (!opened.ringOnThisPhone) opened.ringOnThisPhone = true } catch (_: Exception) { }
            } else {
                settingsFailedAtMs = now
            }
            return opened
        }
    }

    /** Runs [onReady] on a background thread with the shared Settings (null if unusable); the caller posts to the UI thread. */
    fun withSettings(retryNow: Boolean = true, onReady: (Settings?) -> Unit) {
        settingsExec.execute { onReady(settings(retryNow)) }
    }

    @Volatile
    var boot: BootResult = FirebaseBoot.ensure(app) { settings() }
        private set

    private val statePrefs = app.getSharedPreferences(PrefsStateFile.FILE, Context.MODE_PRIVATE)

    val callStore: PrefsCallStore = PrefsCallStore(app.getSharedPreferences(PrefsCallStore.FILE, Context.MODE_PRIVATE)) {
        log.log(HaLog.Level.WARN, "portico push: call store commit failed (kept in memory)")
    }

    val doors: DoorsCache = DoorsCache(PrefsDoorsCacheStore(statePrefs))

    /**
     * 0.5.12-alpha: what this phone decided about each push and ring, kept across process death and readable in Settings.
     * Write-only observation; the decision path never reads it.
     */
    val pushJournal: PushJournal = PushJournal(
        PrefsPushJournalStore(app.getSharedPreferences(PrefsPushJournalStore.FILE, Context.MODE_PRIVATE)),
        env = { PushEnvProbe.read(app, uiShown.get() > 0) },
    )

    /** The phone's content profile, pictures and presses; see the class doc. */
    val content: ContentRepository = ContentRepository(PrefsContentStore(statePrefs), journal = pushJournal)

    /**
     * DEV-807 (D-20261121-73ff): the server's panel flag, cached across restarts (a wall tablet that restarts offline must still be a panel).
     * With no cache yet (first boot after the update, or never learned) the persisted content result is read once; with neither, a phone.
     */
    @Volatile
    private var panelModeOn: Boolean = try {
        if (statePrefs.contains(KEY_PANEL_FLAG)) statePrefs.getBoolean(KEY_PANEL_FLAG, false) else PanelFlag.of(content.profile)
    } catch (_: Exception) { false }

    /** The old local switch was on when this version first opened the store: journalled once, from [onContent]'s first pass. */
    @Volatile
    private var legacyPanelSwitchDropped = false

    /** This phone's own camera size choices (blocks B6): tap a tile on Home to flip it; Home's menu forgets them all. Never sent to HA. */
    val sizes: SizeMemory = SizeMemory(PrefsSizeStore(app.getSharedPreferences(PrefsSizeStore.FILE, Context.MODE_PRIVATE)))

    /** DEV-713: this phone's live/still choices (the LIVE chip on a Home tile). Remembered only while they differ from the document. Never sent to HA. */
    /** 0.6.53: per camera, the last live resolution (Auto / big / small) and the off switch of the gear menu; on the phone, survives a restart. */
    val cameraChoices = com.meda.portico.core.content.CameraChoices(PrefsLiveStore(app.getSharedPreferences(PrefsLiveStore.FILE, Context.MODE_PRIVATE), PrefsLiveStore.CAMERA_KEY))

    val liveChoices: LiveMemory = LiveMemory(PrefsLiveStore(app.getSharedPreferences(PrefsLiveStore.FILE, Context.MODE_PRIVATE)))

    /**
     * DEV-713: Settings -> "Live video in this app" (default ON). Read from the Settings already open, never opening it (Home calls this
     * on the main thread); false while they are not open, which only happens when nothing is enrolled either.
     */
    /**
     * DEV-713 S2: the phone's own guards for live video (too hot; how many H.264 decoders): Home's `LiveHome` and the ring and in-call
     * screens' read them before every plan. Built on first use.
     */
    val liveGuards: com.meda.portico.core.live.LiveGuards by lazy { com.meda.portico.live.AndroidLiveGuards.create(app, pushJournal.liveLog) }

    /** DEV-713 S2: the ONE live code the ring screen and the in-call screen share, so answering keeps the picture and never doubles a stream. */
    val callerLive: com.meda.portico.live.CallerLiveHost by lazy { com.meda.portico.live.CallerLiveHost(app, { signaling }, pushJournal.liveLog, liveGuards) }

    /** The ONE `LiveHome` of the Home screen, shared by Home and the ring pre-warm so a stream the pre-warm started is the one Home then shows. */
    val homeLive: com.meda.portico.live.HomeLiveHost by lazy { com.meda.portico.live.HomeLiveHost(app, { signaling }, pushJournal.liveLog, liveGuards) }

    /** 0.6.73: Home's streams outlive Home going to the background for 30 s ([com.meda.portico.core.live.BackgroundGrace]). */
    val homeLiveGrace: com.meda.portico.live.HomeLiveGrace by lazy { com.meda.portico.live.HomeLiveGrace(this) }

    /** the owner, D-20261111-be58: on a ring, on Wi-Fi, the live streams of the page the ring opens start before the screen is up. See the class. */
    val ringPrewarm: com.meda.portico.live.RingPrewarmGlue by lazy { com.meda.portico.live.RingPrewarmGlue(this) }

    /**
     * A-10 / DEV-702: the roaming block. On a roaming or foreign mobile plan the picture (live video and stills) is held back until the
     * viewer taps; the ring, the signalling and call audio are never gated. Reads the network on demand with a short cache.
     */
    val roaming: com.meda.portico.roaming.AndroidRoaming by lazy {
        com.meda.portico.roaming.AndroidRoaming(
            app,
            app.getSharedPreferences("portico_roaming", Context.MODE_PRIVATE),
            atHomeLink = { linkPath == LinkPath.DIRECT && linkNow() == LinkState.CONNECTED },
            journal = { pushJournal.add(it) },
        )
    }

    /** True when the picture must not be fetched or streamed now (the roaming hold: the Roaming video row is Off and the trip is not allowed yet). */
    fun roamingBlocked(): Boolean = try {
        videoVerdict().roamingHold
    } catch (_: Exception) {
        false // fail open
    }

    /** 0.6.58: the class of the connection now (Wi-Fi, mobile, roaming); unknown is Mobile. */
    fun netClass(): NetClass = try {
        roaming.netClass()
    } catch (_: Exception) {
        NetClass.MOBILE
    }

    @Volatile
    private var choicesCache: NetworkChoices? = null

    /** The six per-network rows of Settings (cached; [onNetworkChoicesChanged] drops the cache). Null until the settings are open. */
    private fun networkChoices(): NetworkChoices? {
        choicesCache?.let { return it }
        val s = settingsCache ?: return null
        return s.networkChoices().also { choicesCache = it }
    }

    /**
     * 0.6.58: what the camera tiles may use right now: the video row of the current network class, as `:core`'s [NetworkPolicy.video]
     * reads it (roaming Off = the old roaming block until the viewer taps). Without settings or on any failure: stills, as the old
     * switch read when settings were unreadable.
     */
    fun videoVerdict(): VideoVerdict = try {
        val c = networkChoices()
        if (c == null) VideoVerdict(VideoMode.STILLS) else NetworkPolicy.video(netClass(), c, roaming.tripAllowed())
    } catch (_: Exception) {
        VideoVerdict(VideoMode.STILLS)
    }

    /** Settings changed a per-network row: forget the cache and look at the connection again (the service, the socket). */
    fun onNetworkChoicesChanged() {
        choicesCache = null
        roaming.invalidate()
        onStayConnectedChanged()
        journalConfig("setting")
    }

    /** A-10: what this app moved over mobile data (Settings shows it). Started with the graph. */
    val traffic: com.meda.portico.roaming.AndroidTraffic = com.meda.portico.roaming.AndroidTraffic(
        app,
        app.getSharedPreferences(com.meda.portico.roaming.AndroidTraffic.FILE, Context.MODE_PRIVATE),
    ).also { it.start() }

    /** A-10: the viewer tapped the roaming chip: pictures are allowed for the rest of this trip. */
    fun allowRoamingTrip() {
        try {
            roaming.allowTrip()
        } catch (_: Exception) {
            // fail open anyway
        }
    }

    /** Live video of any size is allowed on this network (the video row is Live video big or small). */
    fun liveVideoEnabled(): Boolean = try {
        videoVerdict().live
    } catch (_: Exception) {
        false
    }

    /** 0.6.74: the experimental direct ring screen (unlocked phone + "Display over other apps"); one instance, one overlay window at most. */
    private val directRing = com.meda.portico.ring.DirectRingLauncher(app, log, { pushJournal.add(it) })

    private val ringState = RingState(app.getSharedPreferences(RingState.FILE, Context.MODE_PRIVATE))

    /** PR-F: the ring UI. Also serves RingReceiver (decline, backstop) and RingActivity (stop). */
    val ring: AndroidOutcomeHandler = AndroidOutcomeHandler(
        poster = RingNotifier(app, log, { refusedBy -> ringState.setCallStyleRefusedBy(refusedBy) }, directRing, ringFront = { c, d, p -> panelRingFront(c, d, p) }),
        onFiltered = { d, c -> panelRingFront(c, d, RingPath.FILTERED) },
        quietRing = { answer.thirdRingIsQuiet() },
        state = ringState,
        slots = DoorSlots(ringState),
        perms = { PhonePermissions.permissionState(app) },
        ringOnThisPhone = { settings()?.ringOnThisPhone ?: SettingsKeys.RING_ON_THIS_PHONE_DEFAULT },
        doorName = { doors.nameFor(it) },
        clock = System::currentTimeMillis,
        log = log,
        journal = pushJournal,
    )

    /** PR-G, for Setup: the last ring's CallStyle refusal (class name), null if it was accepted. */
    fun callStyleRefusedBy(): String? = try {
        ringState.callStyleRefusedBy()
    } catch (e: Exception) {
        null
    }

    /** PR-F, for Setup: the registration machine's last status (set on the link's thread). */
    @Volatile
    var registrationStatus: RegStatus? = null
        private set

    /** PR-F, for Setup: the last `portico/push/status` answer. */
    @Volatile
    var pushStatus: PushCommands.Outcome? = null
        private set

    private val linkListener = object : PorticoLink.Listener {
        override fun onRegistrationStatus(status: RegStatus) {
            registrationStatus = status
            stay.refreshNote()
            // Plan §3.6: refresh push/status after each register result, so Setup shows HA's switch.
            if (status is RegStatus.Registered && !isPaused()) signaling.post { it.requestPushStatus() }
        }

        override fun onPushStatus(outcome: PushCommands.Outcome) {
            pushStatus = outcome
        }

        // R7-05: a server on another contract is a retrying state of this connection; Setup's path line says so.
        override fun onContractMismatch(mismatch: ContractMismatch) {
            linkDiagnostics.contractMismatch(System.currentTimeMillis(), mismatch.serverContract)
        }

        override fun onContent(outcome: ContentCommands.Outcome) {
            // DEV-807: a refusal of the NAMED slug is not "this phone is not enrolled": the slug is stale, the panel asks again without it
            if (outcome is ContentCommands.Outcome.Failed && panelRefusedByCall(outcome.kind, "get")) return
            content.onContent(outcome)
            if (outcome is ContentCommands.Outcome.Profile) applyServerPanelFlag(PanelFlag.of(outcome.profile), "content") // before the slug and the house gate read it
            houseStateChanged() // the roster says whether house calls are on for this phone
            panelContentArrived(outcome)
            panelContentChanged()
        }
        override fun onContentSubscribeRefused(kind: ContentCommands.ErrorKind) {
            panelRefused(kind, "subscribe")
        }
        override fun onContentRun(actionId: String, outcome: ContentCommands.Outcome) {
            content.onRun(actionId, outcome)
            (outcome as? ContentCommands.Outcome.Failed)?.let { panelRefusedByCall(it.kind, "run") }
        }
        override fun onSnapshot(cameraId: String, outcome: ContentCommands.Outcome) {
            content.onSnapshot(cameraId, outcome)
            (outcome as? ContentCommands.Outcome.Failed)?.let { panelRefusedByCall(it.kind, "snapshot") }
        }
        override fun onDisconnected() {
            content.onDisconnected()
            stay.refreshNote()
            answer.onLinkLost()
            place.onLinkLost()
            talk.onLinkLost()
            houseRing.onLinkLost()
            houseDnd.onDisconnected() // the server drops the report with the connection
            panelHeartbeat.onSocketClosed()
        }

        // The socket is authenticated: a call under way keeps going, a call a dead process left is released.
        override fun onAuthenticated(haVersion: String?) {
            stay.refreshNote()
            answer.onLinkReady()
            place.onLinkReady()
            talk.onLinkReady()
            panelHeartbeat.onSocketOpen()
        }
    }

    private val dispatcher: OutcomeDispatcher = OutcomeDispatcher(
        handler = ring,
        waker = {
            signaling.wake()
            // A push is an allowed context for starting the service, and on mobile data the socket it woke is kept a while.
            stay.onWoken()
            stay.evaluate(true, "push")
        },
        ringOnThisPhone = { settings()?.ringOnThisPhone ?: SettingsKeys.RING_ON_THIS_PHONE_DEFAULT },
        log = log,
        journal = pushJournal,
        // A door ringing outranks a call button's ring (house calls design 14.4): only a ring this phone will alert for.
        onDoorRing = {
            signaling.ringStarted() // the earliest decided-ring signal, push or socket: bring the link to proven-alive before Answer is pressed
            place.onDoorRinging()
            talk.onDoorRinging()
            houseRing.onDoorRinging() // a house ring is withdrawn; an answered house call is a place session and ended by the line above
        },
    )

    /** The machine's store; Setup reads [RegistrationStore.loadValue] for offline registration. */
    private val regStore = PrefsRegistrationStore(statePrefs) { boot.runningProjectId }

    /** Where a decided socket outcome goes: the ring path, unless paused (and the journal is told either way). */
    private val socketRoute = SocketOutcomeRoute(
        dispatch = { dispatcher.dispatch(it) },
        paused = { isPaused() },
        log = log,
        journal = pushJournal,
        knownDoors = { doors.knownDoorRefs().size },
    )

    private val socketSink = SocketCallSink(
        callStore, doors, System::currentTimeMillis,
        { m, o -> socketRoute.onOutcome(m, o) },
        { log.log(HaLog.Level.INFO, it) },
        resumedAt = pauseKeeper::resumedAt,
        doorTaken = { panelDoorTaken(it) },
    )

    private val link: PorticoLink = PorticoLink(
        machine = RegistrationMachine(
            registrationConfig(),
            regStore,
            Clock { SystemClock.elapsedRealtime() },
            log,
        ),
        doors = doors,
        // The socket's call events reach the ring decision first, then a live answer (remote hang-up, answered elsewhere).
        sink = JournalingCallSink(CallStateSink { event ->
            socketSink.onCallEvent(event)
            answer.onCallEvent(event)
            talk.onCallEvent(event) // inert: a session exists only after a press of a READY Talk button
        }, pushJournal),
        source = FirebaseRegistrationSource(
            onValue = { v -> signaling.post { it.onRegistrationValue(v) } },
            onFailed = { signaling.post { it.onRegistrationSourceFailed() } },
            log = log,
        ),
        listener = linkListener,
        log = log,
        // P3-C: the dialled call's own state, sent only to the phone that placed it. Inert: no session, no effect.
        talkSink = TalkStateSink { talk.onTalkState(it) },
        // M7 3b-A: the placing phone's call follows its `house_call` events. The server sends none while house calls are off.
        houseSink = HouseEventSink {
            place.onHouseCall(it)
            houseRing.onHouseCall(it)
        },
        journal = pushJournal,
        endpointOf = { panelEndpoint() },
        // DEV-807 follow-up: the server's `panel_call` request (`portico.call_everyone`). The coordinator refuses everything it should
        // before anything is shown or placed, so outside Panel mode this is an answer and nothing else.
        panelCallSink = PanelCallSink { r -> onAnswerThread { panelCall.onRequest(r) } },
    )

    /** DEV-764: the link's state for a press or a failed command line: socket state, path, and how far this connection's handshake has got. */
    private fun linkFields(): List<Pair<String, Any?>> =
        listOf<Pair<String, Any?>>("sock" to signaling.state.name.lowercase(), "path" to linkPath?.name?.lowercase()) + link.facts().fields()

    // ---- DEV-557: the tailnet client ---------------------------------------------------------

    private val pendingKeys = object : PendingKeyStore {
        override fun load(): PendingKey? = settings()?.pendingKeys?.load()
        override fun clear() {
            settings()?.pendingKeys?.clear()
        }
    }

    private val tailnetEngine = TailnetEngines.create(app, log)

    /** Whether this APK has the tailnet client (app/libs/gocore.aar was present at build time); About shows it. */
    val tailnetBuilt: Boolean get() = tailnetEngine.built

    /** DEV-694 P0: where the time of the last connect goes; written by the connector and the tailnet controller, read by Setup. */
    val connectTimeline = ConnectTimeline()

    /** The tailnet state machine. [TailnetController.state] is safe to read on the UI thread; nothing else is. */
    val tailnet = TailnetController(
        engine = tailnetEngine,
        keys = pendingKeys,
        hostname = { TailnetHostname.of(settings()?.phoneName) },
        target = { settings()?.let { TailnetTarget.resolve(it.haUrl, it.haDial) } },
        log = log,
        paused = ::isPaused,
        timeline = connectTimeline,
        holdMs = TAILNET_HOLD_MS,
        holdTimer = ::scheduleHold,
    )

    /** DEV-694: runs [task] on the tailnet executor after [delayMs]; the result cancels it. For the controller's warm hold. */
    private fun scheduleHold(delayMs: Long, task: () -> Unit): () -> Unit {
        val f = tailnetExec.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return { f.cancel(false) }
    }

    /** Tailnet bookkeeping (refresh, sleep, the enrollment's join); the first may wait for the library to load. */
    private val tailnetExec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "portico-tailnet").apply { isDaemon = true } }

    /** The direct path's deadline only, so a library load on [tailnetExec] can never delay it. */
    private val directTimer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "portico-direct-timer").apply { isDaemon = true } }

    /** The tailnet connect attempt, which may wait ~45 s for the node: never the timer's thread. */
    private val tailnetWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "portico-tailnet-connect").apply { isDaemon = true } }

    /** The Android default-network id ([ConnNetWatcher.networkId]) on which the last socket took the tailnet; -1: none, or direct won since. */
    @Volatile
    private var tailnetWonNet = -1

    /** The path the open socket took, null while it is not open. For Setup. */
    @Volatile
    var linkPath: LinkPath? = null
        private set

    /** DEV-764: the last non-null [linkPath]; the answer path reads it while the socket is closed (null) so a gap is not taken as "direct". */
    @Volatile
    private var lastKnownPath: LinkPath? = null

    /** The last attempt on each path and why it failed (Setup's per-path lines). */
    val linkDiagnostics = LinkDiagnostics()

    /** DEV-694 P1c: what Android says about the phone's networks, read once per connect. */
    private val networkFacts = AndroidNetworkFacts(app)

    /** Does the direct path work from outside the house? Learned on mobile data; a changed enrollment starts over. */
    private val directOutside = DirectWorksOutside(
        PrefsDirectOutsideStore(statePrefs),
        enrollment = { settings()?.let { s -> s.haUrl?.let { it + "|" + (s.haDial ?: "") } } },
    )

    /** The tailnet can help and may be started on a guess: built, a stored identity, no pending single-use key, and direct not known to work outside. */
    private fun tailnetNeeded(): Boolean =
        tailnetEngine.built && tailnetEngine.hasIdentity() && pendingKeys.load() == null && !directOutside.known()

    /**
     * DEV-798 (SEC-02): a cleartext (`ws://`, `http://`) Home Assistant address must prove it is the enrolled one before a token goes
     * to it directly (`POST /api/portico/whoami`, `:core` `HomeProof`). The probe is the plain direct post with a short timeout (never
     * the gated one) and runs on its own threads, not the single tailnet worker. A refusal is a failed direct attempt: the tailnet is next.
     */
    private val proofWorker = Executors.newCachedThreadPool { r -> Thread(r, "portico-proof").apply { isDaemon = true } }
    private val homeVerifier = HomeVerifier(
        http = OkHttpPost(
            OkHttpPost.defaultClient().newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS).writeTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
                .build(),
        ),
        refreshToken = { settings()?.refreshToken },
        // DEV-798: every decision goes to the connection log as one timeline stage (proof_ok / proof_failed / proof_skipped) and a fixed word
        onOutcome = { connectTimeline.mark(it.stage, it.word) },
    )

    private fun judgePath(here: Boolean) =
        PathHint.judge(PathFactsReader.of(networkFacts.snapshot(), isPaused(), tailnetNeeded(), tailnetWonHere = here))

    private val connector = FallbackConnector(
        direct = VerifiedConnector(OkHttpConnector(), homeVerifier, proofWorker),
        tailnet = if (tailnetEngine.built) {
            TailnetConnector(forwarder = { cancelled -> tailnet.awaitForwarder(TAILNET_WAIT_MS, cancelled) }, whyNot = { tailnet.state.toString() })
        } else {
            null
        },
        tailnetUsable = { tailnetEngine.built && (tailnetEngine.hasIdentity() || pendingKeys.load() != null) },
        timer = { delayMs, task ->
            val f = directTimer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            val cancel: () -> Unit = { f.cancel(false) }
            cancel
        },
        worker = tailnetWorker,
        onPath = {
            linkPath = it
            if (it != null) lastKnownPath = it
            lastTailnet = it == LinkPath.TAILNET
            when (it) { // DEV-764 aftermath: which network the tailnet had to carry, so a reconnect there starts it first
                LinkPath.TAILNET -> tailnetWonNet = connNet.networkId
                LinkPath.DIRECT -> tailnetWonNet = -1
                null -> Unit
            }
            if (lastTailnet) stay.evaluate(false, "tailnet path") // battery saver lets a tailnet connection go
        },
        onDirectOpen = { pathRechecker.onDirectOpen() },
        // The direct path won: stop the node if it was started for this connect (a stale call from an older connect does nothing).
        onDirectWon = { lease -> tailnetExec.execute { tailnet.abandonWarm(lease) } },
        newLease = { tailnet.newLease() },
        hint = {
            val here = tailnetWonNet >= 0 && tailnetWonNet == connNet.networkId
            judgePath(here).also {
                connLog.hint(it.mode.name, it.why, it.mobileOnly)
                pushJournal.diag("net", "path-choice", "mode" to it.mode.name.lowercase(), "tn_here" to here, "mobile" to it.mobileOnly, "cap" to it.directCapMs)
            }
        },
        // DEV-811: a link-race candidate asks the same question without writing the working socket's connection log and journal
        peekHint = { judgePath(tailnetWonNet >= 0 && tailnetWonNet == connNet.networkId) },
        onMobileDirect = { works -> if (works) directOutside.opened() else directOutside.unreachable() },
        onLandedAfterDeadline = { pathRechecker.onLandedAfterDeadline() },
        onAttempt = {
            linkDiagnostics.record(it)
            // DEV-694: the opened path's timeline goes to the Journal too (the mark was made just before this call)
            if (it.failure == null) pushJournal.connected(if (it.path == LinkPath.DIRECT) "direct" else "tailnet", ConnectTimeline.summary(connectTimeline.snapshot()))
        },
        // On the tailnet connect worker or OkHttp's thread (never the UI's): asks Go what its dial saw.
        refineTailnet = { f, startMs -> LinkFailures.tailnet(f, tailnet.forwarderDial(), startMs) },
        paused = ::isPaused,
        timeline = connectTimeline,
    )

    // ---- DEV-544: enrolment by one-time code, and the refresh-token login ------------------------------

    /**
     * The path for the redemption and for `/auth/token`: the SAME choice the socket makes (`ha_url` directly, the tailnet when
     * that cannot complete and the node could help), through [PathedHttp]. The usable test is the connector's own.
     */
    private val authHttp = PathedHttp(
        direct = VerifiedHttpPost(OkHttpPost(), homeVerifier), // DEV-798: `/auth/token` only to an address that proved itself
        tailnet = if (tailnetEngine.built) {
            TailnetPost(forwarder = { tailnet.awaitForwarder(TAILNET_WAIT_MS) }, whyNot = { tailnet.state.toString() })
        } else {
            null
        },
        tailnetUsable = { tailnetEngine.built && (tailnetEngine.hasIdentity() || pendingKeys.load() != null) },
        paused = ::isPaused,
    )

    private val loginStore = object : LoginStore {
        override fun refreshToken(): String? = settings()?.refreshToken
        override fun clientId(): String? = settings()?.clientId
        override fun signOut() {
            settings()?.loginStore?.signOut()
            forgetServerPanelFlag()
        }
    }

    /** The in-memory access token and its refresh (`:core` `AccessTokens`); used only while a refresh token is stored. */
    val tokens = AccessTokens(loginStore, RefreshClient(authHttp), haUrl = { settings()?.haUrl }, clockMs = SystemClock::elapsedRealtime)

    private val pendingStore = object : PendingStore {
        override fun load(): PendingEnrollment? = settings()?.pendingStore?.load()
        override fun complete(nonce: String, refreshToken: String, clientId: String): CompleteResult =
            settings()?.pendingStore?.complete(nonce, refreshToken, clientId) ?: CompleteResult.FAILED
        override fun clear(nonce: String) {
            settings()?.pendingStore?.clear(nonce)
        }
    }

    /** One redemption at a time, off every other thread (it blocks on the network and sleeps between retries). */
    private val redeemExec = Executors.newSingleThreadExecutor { r -> Thread(r, "portico-redeem").apply { isDaemon = true } }

    /** What the redemption is doing now, for Setup. Memory only: the pending record itself is persisted. */
    @Volatile
    var enrollStatus: EnrollStatus = EnrollStatus.Idle
        private set

    /** A new save starts from a clean slate: an earlier attempt's failure text must not outlive it. */
    fun resetEnrollStatus() {
        if (enrollStatus !is EnrollStatus.Redeeming && enrollStatus !is EnrollStatus.Retrying) enrollStatus = EnrollStatus.Idle
    }

    /** Setup's live update while it is visible; called from the redemption thread. */
    @Volatile
    var onEnrollChanged: (() -> Unit)? = null

    private val redemption = EnrollmentRedemption(
        store = pendingStore,
        client = RedeemClient(authHttp),
        tokens = tokens,
        installationId = { settings()?.installationId },
        wallClockMs = System::currentTimeMillis,
        sleep = Thread::sleep,
        onStatus = { st ->
            enrollStatus = st
            try {
                onEnrollChanged?.invoke()
            } catch (e: Exception) {
                // a screen's refresh must not break a redemption
            }
        },
    )

    /**
     * Redeems the pending one-time code, if there is one (a scan just saved it, the process just started, or Setup opened). Any
     * thread; returns at once. Safe to call again while one runs: they queue, and a run that finds nothing pending does nothing.
     */
    fun redeemPendingCode() {
        try {
            redeemExec.execute {
                try {
                    if (redemption.run() == EnrollStatus.Done) onRedeemed()
                } catch (e: Exception) {
                    log.log(HaLog.Level.ERROR, "enrol: ${e.javaClass.simpleName} while redeeming the one-time code")
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // shut down with the process
        }
    }

    /** The code was redeemed and the refresh token is saved (the first access token is already in [tokens]): connect with it. */
    private fun onRedeemed() {
        log.log(HaLog.Level.INFO, "enrol: the one-time code was redeemed; connecting with the refresh token")
        signaling.start()
        signaling.reconnect()
        stay.evaluate(true, "enrolled")
        changed()
    }

    val signaling: SignalingClient = SignalingClient(
        SettingsConnectionConfig(tokens, ::settings), link, connector = connector, log = log, clockMs = SystemClock::elapsedRealtime,
        onAuthRejected = { linkDiagnostics.authRejected(System.currentTimeMillis()) },
        paused = ::isPaused,
        onSignedOut = { log.log(HaLog.Level.WARN, "enrol: signed out of Home Assistant") },
        onTrace = ::connTrace,
        active = ::linkInUse,
        // DEV-811: on cellular or the tailnet a watchdog trip opens a second socket beside the working one instead of dropping it; Wi-Fi keeps the
        // strict rule. Asked only when the strict deadline has passed. Setup's "Reconnect while ringing" = Classic turns it off (default on).
        raceOnLink = {
            callAudio.linkRace() && (linkPath == LinkPath.TAILNET || networkFacts.snapshot()?.defaultNet?.cellular == true)
        },
        pathWord = { linkPath?.name?.lowercase() },
    )

    /** The watchdog's "pay more attention" signal: a ring is up, or the screen is on (the person may press something). */
    private fun ringStillUp(): Boolean = try { ringState.activeRings().isNotEmpty() } catch (_: Exception) { true }

    private fun linkInUse(): Boolean {
        val ringing = try { ringState.activeRings().isNotEmpty() } catch (_: Exception) { false }
        return ringing || app.getSystemService(PowerManager::class.java)?.isInteractive == true
    }

    // ---- DEV-705: the connection log ------------------------------------------------------------------
    // One report per connection sequence (a network change, an app start, a reconnect... to connected), queued here and sent
    // to Home Assistant over this app's own socket (`portico/connlog/report`). Design: the estate repo automation/portico_connlog_design.md.

    private val connLogPrefs = app.getSharedPreferences(PrefsConnLogStore.FILE, Context.MODE_PRIVATE)
    val connLogQueue = ConnLogQueue(PrefsConnLogStore(connLogPrefs))
    private val connLogExec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "portico-connlog").apply { isDaemon = true } }
    private val connCold = java.util.concurrent.atomic.AtomicBoolean(true)

    /** "Send connection logs to Home Assistant" (Settings). On by default; off records and sends nothing and empties the queue. */
    var connLogEnabled: Boolean
        get() = panelModeOn || connLogPrefs.getBoolean(PrefsConnLogStore.KEY_ENABLED, true) // Panel mode forces diagnostics on (design section 9)
        set(value) {
            connLogPrefs.edit().putBoolean(PrefsConnLogStore.KEY_ENABLED, value).apply()
            if (!value) {
                connLogQueue.clear()
                journalReporter.clear()
            }
        }

    private val connSender = ConnLogSender(
        connLogQueue,
        send = ::sendConnLog,
        schedule = { delayMs, task -> connLogExec.schedule(task, delayMs, TimeUnit.MILLISECONDS) },
        enabled = { connLogEnabled },
    )

    /** For Setup: how many reports wait, how many were dropped, how many HA accepted, when, and whether HA lacks the command. */
    class ConnLogStatus(val waiting: Int, val dropped: Int, val sent: Int, val lastSentAtMs: Long, val unsupported: Boolean)

    fun connLogStatus() = ConnLogStatus(connLogQueue.size(), connLogQueue.dropped(), connSender.sentCount, connSender.lastSentAtMs, connSender.isUnsupported)

    private val connLogAppVersion = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0" } catch (_: Exception) { "0" }

    /** A finished report (connection or Journal item) goes into the ONE queue and the ONE sender is kicked. Off: nothing is kept. */
    private fun enqueueConnLog(json: String) {
        if (connLogEnabled) {
            connLogQueue.add(json)
            connLogExec.schedule({ connSender.kick() }, 2, TimeUnit.SECONDS) // not in the middle of the connect it describes
        }
    }

    private val connLog = ConnLog(
        appVersion = connLogAppVersion,
        sdk = android.os.Build.VERSION.SDK_INT,
        env = ::connEnv,
        sink = ::enqueueConnLog,
    )

    /**
     * DEV-713 / DEV-705: the `live` and `audio` Journal lines go to HA through the same queue and sender (item `kind: journal`). The Journal
     * offers every line it stores (the one hook below); flushes run on [connLogExec], never on the thread that wrote the line; it is off
     * whenever "Send connection logs to Home Assistant" is.
     */
    private val journalReporter = JournalReporter(
        appVersion = connLogAppVersion,
        enabled = { connLogEnabled },
        sink = ::enqueueConnLog,
        schedule = { delayMs, task -> connLogExec.schedule(task, delayMs, TimeUnit.MILLISECONDS) },
        onFault = { pushJournal.journalReportFault(it) },
    )

    init {
        pushJournal.lineObserver = journalReporter::offer
        com.meda.portico.core.content.LayoutJournal.observer = journalReporter::offer // DEV-762 A5: `layout fail` lines, same path and switch
    }

    /**
     * DEV-762 A2 (case C4): an uncaught Java/Kotlin exception becomes a `crash` record (class names and our own frames, never a message)
     * in the connlog queue, ON DISK, after the Journal reporter's waiting lines, before the previous handler (the system's) runs. The
     * existing sender delivers it on the next connect. Follows the same switch. Installing twice keeps one handler.
     */
    fun installCrashHandler() {
        try {
            val limit = CrashRateLimit(
                read = { connLogPrefs.getString("crash_times", null) },
                write = { connLogPrefs.edit().putString("crash_times", it).commit() },
            )
            val handler = CrashHandler.wrapping(Thread.getDefaultUncaughtExceptionHandler()) { prev ->
                CrashHandler(
                    previous = prev,
                    enabled = { connLogEnabled },
                    flushJournal = journalReporter::flushNow,
                    buildRecord = { e, th ->
                        CrashRecord.build(
                            t = e,
                            threadName = th.name,
                            id = java.util.UUID.randomUUID().toString().replace("-", "").take(12),
                            appVersion = connLogAppVersion,
                            sdk = android.os.Build.VERSION.SDK_INT,
                            nowMs = System.currentTimeMillis(),
                            upMs = SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime(),
                            model = android.os.Build.MODEL,
                            release = android.os.Build.VERSION.RELEASE,
                        )
                    },
                    enqueueDurable = {
                        connLogQueue.add(it, durable = true)
                        connLogPrefs.edit().putLong(KEY_LAST_CRASH, System.currentTimeMillis()).commit() // A3: the platform's CRASH exit is this one
                    },
                    allow = { limit.tryAcquire(System.currentTimeMillis()) },
                    onFault = { pushJournal.journalReportFault(it) },
                )
            }
            Thread.setDefaultUncaughtExceptionHandler(handler)
        } catch (e: Throwable) {
            log.log(HaLog.Level.WARN, "crash report: handler not installed (${e.javaClass.simpleName})")
        }
    }

    /**
     * DEV-762 A3 (cases C5, C6). Called once from [PorticoApp.onCreate]: reads and replaces the start-up marker (set now, cleared at the
     * first drawn window or after [STARTUP_CLEAR_MS] alive), then, off the main thread, reports each new abnormal platform exit and a
     * `startup_died` for an unfinished marker. Everything inside `try`; a failure never blocks the start.
     */
    fun beginExitTracking(): android.app.Application.ActivityLifecycleCallbacks? {
        try {
            val marker = StartupMarker({ connLogPrefs.getString(KEY_STARTUP, null) }) { v ->
                connLogPrefs.edit().apply { if (v == null) remove(KEY_STARTUP) else putString(KEY_STARTUP, v) }.commit()
            }
            val prior = marker.pendingSince()
            marker.set(System.currentTimeMillis())
            val reporter = ExitReporter(
                source = AndroidExitSource(app),
                readCursor = { connLogPrefs.getLong(KEY_EXIT_CURSOR, -1L).takeIf { it >= 0 } },
                writeCursor = { connLogPrefs.edit().putLong(KEY_EXIT_CURSOR, it).apply() },
                enabled = { connLogEnabled },
                lastCrashMs = { connLogPrefs.getLong(KEY_LAST_CRASH, -1L).takeIf { it >= 0 } },
                enqueue = { connLogQueue.add(it, durable = true) },
                newId = { java.util.UUID.randomUUID().toString().replace("-", "").take(12) },
                now = System::currentTimeMillis,
                upMs = { SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() },
                appVersion = connLogAppVersion,
                sdk = android.os.Build.VERSION.SDK_INT,
                model = android.os.Build.MODEL,
                release = android.os.Build.VERSION.RELEASE,
                onFault = { pushJournal.journalReportFault(it) },
                goLog = com.meda.portico.connlog.AndroidGoLogSource(),
            )
            connLogExec.execute { reporter.run(prior) } // the sender is kicked by start() a few seconds later
            connLogExec.schedule({ marker.clear() }, STARTUP_CLEAR_MS, TimeUnit.MILLISECONDS) // alive this long: it did not die starting
            return object : android.app.Application.ActivityLifecycleCallbacks {
                private var done = false
                override fun onActivityResumed(activity: android.app.Activity) {
                    if (done) return
                    done = true
                    try {
                        val v = activity.window.decorView
                        v.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                            override fun onDraw() {
                                v.post { try { v.viewTreeObserver.removeOnDrawListener(this) } catch (_: Throwable) {} }
                                connLogExec.execute { marker.clear() }
                            }
                        })
                    } catch (_: Throwable) {
                        connLogExec.execute { marker.clear() }
                    }
                }
                override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
                override fun onActivityStarted(a: android.app.Activity) {}
                override fun onActivityPaused(a: android.app.Activity) {}
                override fun onActivityStopped(a: android.app.Activity) {}
                override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(a: android.app.Activity) {}
            }
        } catch (e: Throwable) {
            log.log(HaLog.Level.WARN, "exit report: not started (${e.javaClass.simpleName})")
            return null
        }
    }

    /** A crash record queued by the last process is sent once the socket is up; the sender is kicked a few seconds after start. */
    fun drainQueuedReports() {
        connLogExec.schedule({ if (connLogEnabled && !connLogQueue.isEmpty()) connSender.kick() }, 8, TimeUnit.SECONDS)
    }

    private val connNet = ConnNetWatcher(app, networkFacts, log) {
        connTracer.netChanged(it)
        lastTailnet = false // a new network: try the home LAN first again, even after battery saver let the tailnet go
        roaming.invalidate() // 0.6.58: the network class is read again
        stay.evaluate(false, "network changed")
    }

    private fun connEnv(): ConnEnv {
        val pm = app.getSystemService(PowerManager::class.java)
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        return ConnEnv(
            fg = uiShown.get() > 0,
            doze = pm?.isDeviceIdleMode == true,
            saver = pm?.isPowerSaveMode == true,
            metered = cm?.isActiveNetworkMetered == true,
            procMs = SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime(),
            cold = connCold.getAndSet(false),
            paused = isPaused(),
        )
    }

    /** Puts one batch on the socket. Always "accepted": a socket that is not ready answers [ConnLogReply.Lost] and the next CONNECTED kicks again. */
    private fun sendConnLog(body: kotlinx.serialization.json.JsonObject, onReply: (ConnLogReply) -> Unit): Boolean {
        signaling.post { link ->
            val sent = link.sendCall(ConnLogCommands.REPORT, body) { onReply(ConnLogReplies.of(it)) }
            if (!sent) onReply(ConnLogReply.Lost)
        }
        return true
    }

    /** The connection log's glue (core, tested): the socket client's steps and the network watcher's changes to sequences. */
    private val connTracer = ConnTracer(
        log = connLog,
        path = { linkPath?.name?.lowercase() },
        kick = { delayMs -> connLogExec.schedule({ connSender.kick() }, delayMs, TimeUnit.MILLISECONDS) },
        schedule = { delayMs, task -> connLogExec.schedule(task, delayMs, TimeUnit.MILLISECONDS) },
        journal = pushJournal,
    )

    private fun connTrace(event: String, cls: String?, n: Long?) = connTracer.trace(event, cls, n)

    init {
        connectTimeline.observer = object : ConnectTimeline.Observer {
            override fun begin() = connLog.event("connect_start")
            override fun mark(stage: ConnectTimeline.Stage, cls: String?) = connLog.event(stage.wire, cls)
            override fun note(text: String) = connLog.tsnet(text)
        }
    }

    // ---- Answering a door call ---------------------------------------------------------------------

    /** Everything the answer path does runs here, one task at a time; timers run here too. */
    private val answerExec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "portico-answer").apply { isDaemon = true } }

    /**
     * DEV-698: the audio journal around a call's WebRTC media (answer, Talk and the house call's WebRTC leg all open theirs through
     * [WebRtcMedia]): one `audio start` line about 2 s after the audio connects and one `audio end` line when it closes, in the
     * push and ring journal. Observation only.
     */
    private fun audioJournaled(path: String, callId: String, real: com.meda.portico.core.call.MediaPort): com.meda.portico.core.call.MediaPort =
        com.meda.portico.core.call.AudioJournalMedia(
            real, pushJournal, path, callId,
            onEnv = ::audioEnv,
            scheduler = { delayMs, task ->
                val f = answerExec.schedule({ try { task() } catch (e: Exception) { log.log(HaLog.Level.ERROR, "audio: ${e.javaClass.simpleName} in a timer") } }, delayMs, TimeUnit.MILLISECONDS)
                Cancellable { f.cancel(false) }
            },
        )

    private fun onAnswerThread(task: () -> Unit) {
        try {
            answerExec.execute {
                try {
                    task()
                } catch (e: Exception) {
                    log.log(HaLog.Level.ERROR, "answer: ${e.javaClass.simpleName} on the answer thread")
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // shut down with the process
        }
    }

    private val activeCallStore = PrefsActiveCallStore(app.getSharedPreferences(PrefsActiveCallStore.FILE, Context.MODE_PRIVATE))

    /** DEV-811: Setup's call-audio choices (Compatibility, the path on the home network) and the "wideband failed" mark; plain prefs, read at each call. */
    val callAudio = com.meda.portico.core.call.CallAudioConfig(
        com.meda.portico.store.PrefsKvStore(app.getSharedPreferences(com.meda.portico.store.PrefsKvStore.FILE, Context.MODE_PRIVATE)),
        System::currentTimeMillis,
        connLogAppVersion,
    )

    /** The frame path's 2-second first-frame check runs on the answer thread's timer. */
    private val frameScheduler = com.meda.portico.core.call.Scheduler { delayMs, task ->
        val f = answerExec.schedule({ try { task() } catch (e: Exception) { log.log(HaLog.Level.ERROR, "answer: ${e.javaClass.simpleName} in a timer") } }, delayMs, TimeUnit.MILLISECONDS)
        Cancellable { f.cancel(false) }
    }

    /** 0.6.95: one diagnostic Journal line of a call's audio (`frames-trk`, `frames-win`, `frames-lvl`). Observation only. */
    private fun audioDiag(event: String, fields: List<Pair<String, Any?>>) {
        try { pushJournal.diag("ui", event, fields) } catch (_: Exception) {}
    }

    /** 0.6.95: the Android audio environment (mode, volumes, route, playing streams) at the [at] (`start`, `end`) of a call's audio, both paths. */
    private fun audioEnv(at: String) {
        try {
            pushJournal.diag("ui", "audio-env", listOf<Pair<String, Any?>>("at" to at) + com.meda.portico.call.CallAudioEnvProbe.read(app))
        } catch (_: Exception) {
        }
    }

    /** DEV-811 P1: one call's frame audio at its close, as the two journal lines (`frames v=2`, `audio dev`) and one INFO line. */
    private fun frameAudioReport(r: com.meda.portico.core.call.FrameReport) {
        pushJournal.frameAudio(r, com.meda.portico.call.AudioRouteWord.now(app))
        val f = r.flow
        log.log(HaLog.Level.INFO, "audio: frames call=${com.meda.portico.core.push.Redact.prefix(r.callId)} codec=${r.profile.codec} secs=${f.secs} sent=${f.sent} dropped=${f.dropped} recv=${f.received} underruns=${f.underruns} maxgap=${f.maxGapMs}ms p95gap=${f.p95GapMs}ms why=${r.why} mark=${r.mark}")
    }
    /**
     * DEV-764 (app fixes): a command sent before the link has finished its handshake (doors, calls subscription) waits up to 4 s for it
     * instead of failing with "Not ready yet"; see [com.meda.portico.core.call.PatientCommandPort]. Inside the journal, so the line
     * shows the end result and the total time.
     */
    private val answerPressSeen = java.util.concurrent.atomic.AtomicBoolean(false)

    private val answerCommands = com.meda.portico.core.call.JournalingCommandPort(
        // the owner: at the gate, waiting. An Answer press starts the watchdog's fast rule (2 s checks for 15 s); a resend does not extend it.
        com.meda.portico.core.call.CommandPort { type, body, onReply ->
            if (type == com.meda.portico.core.call.AnswerCommands.ANSWER && !answerPressSeen.getAndSet(true)) {
                signaling.fastWatch()
                answerExec.schedule({ answerPressSeen.set(false) }, 15, TimeUnit.SECONDS)
            }
            patientCommands.send(type, body, onReply)
        },
        pushJournal, ::linkFields,
    )

    private val patientCommands: com.meda.portico.core.call.CommandPort =
        com.meda.portico.core.call.PatientCommandPort(
            LinkCommandPort({ signaling }, ::onAnswerThread),
            com.meda.portico.core.call.Scheduler { delayMs, task ->
                val f = answerExec.schedule({ try { task() } catch (e: Exception) { log.log(HaLog.Level.ERROR, "answer: ${e.javaClass.simpleName} in a retry") } }, delayMs, TimeUnit.MILLISECONDS)
                Cancellable { f.cancel(false) }
            },
            onNotSubscribed = { try { signaling.post { it.ensureCallsSubscribed() } } catch (_: Exception) { } },
            // DEV-764 aftermath: an answer/hangup/run whose socket the watchdog tore down is sent again after the reconnect (8 s, under the
            // session's 10 s step timer) while the ring is still up; a hangup always (it only ever ends something).
            lostWaitMs = 8_000,
            stillWanted = { type -> type.endsWith("hangup") || ringStillUp() },
            note = { type, what, ms -> pushJournal.diag("ui", "cmd-" + what, listOf("c" to type.substringAfterLast('/').replace('_', '-'), "ms" to ms)) },
        )

    /**
     * ONE audio route in front of the real one for every door session (15.13 step 6): `AndroidAudioRoute` keeps its state per
     * instance, so one per session would save `MODE_IN_COMMUNICATION` as the original mode and restore it under the other call.
     * With one session it enters and leaves exactly as before.
     */
    private val answerAudio = RefCountedAudioRoute(AndroidAudioRoute(app) { change -> answer.onAudioFocusChange(change) })

    /**
     * Answering. Nothing in this graph starts it: the only entry is `answer.press`, called by the
     * Answer button (and the ring notification's Answer action, which opens the ring screen that
     * presses). The WebRTC engine, the audio route and the foreground service exist only inside a press.
     */
    val answer: AnswerCoordinator = AnswerCoordinator(
        newSession = { callId, doorId, hooks, onChange ->
            AnswerSession(
                callId = callId,
                doorId = doorId,
                commands = answerCommands,
                media = audioJournaled("answer", callId, SerialMedia(WebRtcMedia(app, log, pushJournal), ::onAnswerThread)),
                audio = answerAudio,
                mic = { app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
                scheduler = { delayMs, task ->
                    val f = answerExec.schedule({ try { task() } catch (e: Exception) { log.log(HaLog.Level.ERROR, "answer: ${e.javaClass.simpleName} in a timer") } }, delayMs, TimeUnit.MILLISECONDS)
                    Cancellable { f.cancel(false) }
                },
                store = activeCallStore,
                wallClockMs = System::currentTimeMillis,
                hooks = hooks,
                config = AnswerConfig(),
                log = log,
                onChange = onChange,
                // Away call audio is always carried (0.6.64: the Setup switch is gone).
                away = TailnetAwayMedia({ linkPath }, { lastKnownPath }, LinkFrameChannel({ signaling }), { AndroidPcmDevice() }, ::onAnswerThread, log,
                    config = callAudio, scheduler = frameScheduler, onReport = ::frameAudioReport, onDiag = ::audioDiag, onEnv = ::audioEnv),
                journal = pushJournal,
            )
        },
        callStore = callStore,
        doors = doors,
        sweep = AnswerSweep(answerCommands, activeCallStore, log),
        post = ::onAnswerThread,
        stopRing = { door, call, why -> ring.stopRing(door, call, why) },
        serviceStart = {
            try {
                androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, CallService::class.java))
            } catch (e: Exception) {
                log.log(HaLog.Level.WARN, "answer: the call service could not start (${e.javaClass.simpleName})")
            }
        },
        serviceStop = {
            try {
                app.stopService(android.content.Intent(app, CallService::class.java))
            } catch (e: Exception) {
                log.log(HaLog.Level.WARN, "answer: the call service could not stop (${e.javaClass.simpleName})")
            }
        },
        paused = ::isPaused,
        wallClockMs = System::currentTimeMillis,
        // A door call outranks a call button, a tap included: an outgoing call ends before the answer begins.
        beforeBegin = {
            place.endForDoorAnswer()
            talk.endForDoorAnswer()
        },
        log = log,
        journal = pushJournal,
        linkFields = ::linkFields,
    )

    /**
     * Outgoing calls from a call button (house calls design 14). Nothing in this graph starts one: the only
     * entry is `place.press`, called by a press of a call button on Home. It shares the answer thread and the
     * foreground service. The audio leg is [houseMedia] (M7 3b-B: both ends of a house call);
     * `house_call` events reach it through the link's house sink (3b-A); on a server without call targets a press ends at
     * once with "This Home Assistant cannot place calls yet".
     */
    val place: PlaceCoordinator = PlaceCoordinator(
        newSession = { request, hooks, onChange ->
            PlaceSession(
                request = request,
                commands = answerCommands,
                media = houseMedia,
                audio = AndroidAudioRoute(app),
                mic = { app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
                scheduler = { delayMs, task ->
                    val f = answerExec.schedule({ try { task() } catch (e: Exception) { log.log(HaLog.Level.ERROR, "place: ${e.javaClass.simpleName} in a timer") } }, delayMs, TimeUnit.MILLISECONDS)
                    Cancellable { f.cancel(false) }
                },
                wallClockMs = System::currentTimeMillis,
                hooks = hooks,
                config = PlaceConfig(),
                log = log,
                onChange = onChange,
            )
        },
        post = ::onAnswerThread,
        serviceStart = {
            try {
                androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, CallService::class.java))
            } catch (e: Exception) {
                log.log(HaLog.Level.WARN, "place: the call service could not start (${e.javaClass.simpleName})")
            }
        },
        serviceStop = {
            try {
                app.stopService(android.content.Intent(app, CallService::class.java))
            } catch (e: Exception) {
                log.log(HaLog.Level.WARN, "place: the call service could not stop (${e.javaClass.simpleName})")
            }
        },
        paused = ::isPaused,
        otherCallLive = { answerLive() || talk.state().isLive },
        log = log,
    )

    // ---- DEV-807 follow-up: the panel's "Call phones" button (design: portico_panel_call_phones_design.md) ----------------------

    /** The call screen is visible (set by [OutgoingCallActivity] on the main thread); what the front launcher's watchdog reads. */
    @Volatile
    private var outgoingScreenUp = false

    /** The coordinator's "the screen is up" callback while a request waits for it (answer thread only). */
    private var panelCallOnUp: (() -> Unit)? = null

    fun outgoingScreenStarted() {
        outgoingScreenUp = true
        onAnswerThread { val f = panelCallOnUp; panelCallOnUp = null; f?.invoke() }
    }

    fun outgoingScreenStopped() { outgoingScreenUp = false }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Brings [com.meda.portico.call.OutgoingCallActivity] to the front for a panel call request. Never throws. */
    private fun panelCallFront(onUp: () -> Unit, onFailed: (String) -> Unit) {
        val intent = android.content.Intent(app, com.meda.portico.call.OutgoingCallActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (panelInFront()) {
            // Our own screen is already visible (the person opened the app): an ordinary start, no overlay needed.
            mainHandler.post {
                try {
                    app.startActivity(intent)
                    onAnswerThread { onUp() }
                } catch (e: Throwable) {
                    onAnswerThread { onFailed(e.javaClass.simpleName.lowercase().take(24)) }
                }
            }
            return
        }
        // Fully (or anything else) is in front: the ring window's own way, the overlay permission and a 1x1 overlay (Android 15), or nothing.
        // A screen that is off or locked is NOT a case this path wakes: the full-screen-intent path is a notification and the design leaves it out.
        if (directRing.decide() != com.meda.portico.core.ring.RingScreenPolicy.Decision.DIRECT_OVERLAY) {
            onAnswerThread { onFailed("screen off or no overlay permission") }
            return
        }
        panelCallOnUp = onUp
        directRing.launch(intent, windowUp = { outgoingScreenUp }, onFailed = { onAnswerThread { panelCallOnUp = null; onFailed("start blocked") } })
    }

    private fun panelCallReply(requestId: String, ok: Boolean, code: String?, text: String?) {
        try {
            signaling.post { link ->
                link.sendCall(PanelCallWire.RESULT, PanelCallWire.result(requestId, ok, code, text)) { r ->
                    // `unknown_request` = the server gave up first (its 15 s): nothing to undo, the call screen already says what happened.
                    if (r?.success != true) pushJournal.diag("wall", "call-reply", "req" to com.meda.portico.core.push.PushJournalFormat.DiagId(requestId), "result" to (r?.error?.code ?: "lost"))
                }
            }
        } catch (_: Exception) { }
    }

    /** One Journal line per step of a panel call request, with every setting the path depends on (D-20261121-ee09: logs report every relevant setting). */
    private fun panelCallNote(event: String, requestId: String, code: String?) {
        pushJournal.diag(
            "wall", "call-request",
            "ev" to event, "req" to com.meda.portico.core.push.PushJournalFormat.DiagId(requestId), "code" to code,
            "panelmode" to panelModeOn,
            "houseswitch" to houseReceiveOn(),
            "overlay" to PanelAndroidFacts.overlayOk(app),
            "mic" to PanelAndroidFacts.microphoneOk(app),
            "paused" to isPaused(),
            "dnd" to osDndOn(),
            "infront" to panelInFront(),
            "idlemin" to panelIdleMinutes(),
            "callpct" to PanelVolumeKeeper.DEFAULT_CALL_PCT,
            "volcall" to panelLevels.level(PanelStream.VOICE_CALL),
            "maxcall" to panelLevels.max(PanelStream.VOICE_CALL),
        )
    }

    val panelCall: PanelCallCoordinator = PanelCallCoordinator(
        gate = { PanelCallGate(panelModeOn, callLiveNow() || ringUpNow(), isPaused()) },
        everyone = { content.profile.roster.firstOrNull { it.kind == "group" } },
        front = ::panelCallFront,
        press = { place.press(it) },
        reply = ::panelCallReply,
        scheduler = { delayMs, task ->
            val f = answerExec.schedule({ try { task() } catch (e: Exception) { log.log(HaLog.Level.ERROR, "panel call: ${e.javaClass.simpleName} in a timer") } }, delayMs, TimeUnit.MILLISECONDS)
            Cancellable { f.cancel(false) }
        },
        note = ::panelCallNote,
    )

    /**
     * The ringback tone (Panel mode, while a placed call rings at the far end). The voice-call stream is raised to the configured call
     * level for the tone and put back after, by the same keeper the call's audio uses (holders are counted, so the call that follows
     * keeps the level without a gap). The Journal line says what could be read, because the speaker the tone reached cannot be.
     */
    private val ringback = com.meda.portico.call.Ringback(
        log = log,
        onStart = { started ->
            val raised = if (started) panelVolume.raise(PanelStream.VOICE_CALL, PanelVolumeKeeper.DEFAULT_CALL_PCT) else false
            pushJournal.diag(
                "wall", "ringback",
                "ev" to (if (started) "start" else "failed"), "stream" to "voice-call", "raised" to raised,
                "pct" to PanelVolumeKeeper.DEFAULT_CALL_PCT, "volcall" to panelLevels.level(PanelStream.VOICE_CALL),
                "maxcall" to panelLevels.max(PanelStream.VOICE_CALL), "volalarm" to panelLevels.level(PanelStream.ALARM),
                "dnd" to osDndOn(), "panelmode" to panelModeOn,
            )
        },
        onStop = { why ->
            panelVolume.restore(PanelStream.VOICE_CALL)
            pushJournal.diag("wall", "ringback", "ev" to "stop", "why" to why)
        },
    )

    // ---- House calls (M7 3b-B) ----------------------------------------------------------------------------------------
    //
    // Everything here is inert while the server has house calls off: the roster is what says so ([HouseGate]). Then there is no ring
    // (the coordinator's first rule), no presence report (the reporter sends nothing), no receiver registered, and a `ct: house` push
    // takes the door path and is ignored, exactly as before.

    private val houseFrames = LinkFrameChannel(
        { signaling },
        openCommand = { _, callId, codec -> HouseCommands.MEDIA_OPEN to HouseCommands.mediaOpen(callId, codec) },
        houseEnds = true,
    )

    /** The audio of a house call, both ends: WebRTC at home, frames when the socket is on the tailnet (counters in the journal). */
    private val houseMedia = HouseMedia(
        framePath = { listener, stats ->
            val choice = try { callAudio.choose() } catch (_: Exception) { com.meda.portico.core.call.AudioChoice.WIDEBAND }
            FrameMedia(
                houseFrames, AndroidPcmDevice(), listener, ::onAnswerThread, log, stats,
                choice = choice, scheduler = frameScheduler, onReport = ::frameAudioReport, onDiag = ::audioDiag, onEnv = ::audioEnv,
                onWidebandFailed = { callAudio.recordWidebandFailure() }, markWord = { callAudio.mark().word },
            )
        },
        rtc = SerialMedia(WebRtcMedia(app, log, pushJournal), ::onAnswerThread),
        audioFor = { callId, port -> audioJournaled("house", callId, port) },
        signal = LinkHouseSignal { signaling },
        preferFrames = { com.meda.portico.core.call.AwayPathPolicy.decide(linkPath, lastKnownPath, callAudio.pathChoice()).frames },
        post = ::onAnswerThread,
        log = log,
    )

    /** Whether the server has house calls on for this phone: its roster says so. Cheap; any thread. */
    fun houseEnabled(): Boolean = try {
        // Panel mode (design section 5 item 3 R9): the server's `house_calls` boolean decides, not the roster's contents.
        if (panelModeOn) content.profile.houseCalls == true else HouseGate.enabled(content.profile.roster)
    } catch (e: Exception) {
        false
    }

    private fun houseReceiveOn(): Boolean = settings()?.houseReceive ?: SettingsKeys.HOUSE_RECEIVE_DEFAULT

    /** Android's Do Not Disturb filter is on (anything but "allow all"). Read only through the switch, and only while house calls are on. */
    private fun osDndOn(): Boolean = try {
        val f = app.getSystemService(android.app.NotificationManager::class.java)?.currentInterruptionFilter
        f != null && f != android.app.NotificationManager.INTERRUPTION_FILTER_ALL && f != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    } catch (e: Exception) {
        false
    }

    private fun houseDndShared(): Boolean = settings()?.houseShareDnd ?: SettingsKeys.HOUSE_SHARE_DND_DEFAULT

    /** A door is ringing here, or a door call is live or held: a door outranks a house call (design 7, 15.13). */
    private fun doorBusy(): Boolean = answerLive() || try { ringState.activeRings().isNotEmpty() } catch (e: Exception) { false }

    /** The callee side of a house call before it is answered: the ring decision, Decline, and what ends a ring. */
    val houseRing: HouseRingCoordinator = HouseRingCoordinator(
        enabled = ::houseEnabled,
        receiveOn = ::houseReceiveOn,
        dndOn = { houseDndShared() && osDndOn() },
        paused = ::isPaused,
        doorBusy = ::doorBusy,
        callLive = { place.state().isLive || talk.state().isLive || answerLive() },
        // Panel mode: the phone half's id (`phone_endpoint`); null until the server says (events are held meanwhile). A phone: its own endpoint.
        myEndpoint = { if (panelModeOn) content.profile.phoneEndpoint else content.profile.endpoint },
        profile = { content.profile },
        commands = answerCommands,
        answerHouse = { callId, name -> place.answerHouse(callId, name) },
        ui = HouseRingNotifier(app, log, directRing),
        scheduler = { delayMs, task ->
            val f = answerExec.schedule({ try { task() } catch (e: Exception) { log.log(HaLog.Level.ERROR, "house: ${e.javaClass.simpleName} in a timer") } }, delayMs, TimeUnit.MILLISECONDS)
            Cancellable { f.cancel(false) }
        },
        clockMs = System::currentTimeMillis,
        post = ::onAnswerThread,
        journal = pushJournal,
        log = log,
        bufferWhileUnknown = { panelModeOn },
    )

    /** `portico/house/presence {dnd}`, sent only while house calls are on, on a ready connection, and only on a change. */
    private val houseDnd = HouseDndReporter { dnd, onDone ->
        try {
            signaling.post { link ->
                val sent = link.sendCall(HouseCommands.PRESENCE, HouseCommands.presence(dnd)) { r ->
                    val ok = r?.success == true
                    if (ok) pushJournal.house(HouseStep.DND, null, null, if (dnd) 1 else 0)
                    onDone(ok)
                }
                if (!sent) onDone(false)
            }
            true
        } catch (e: Exception) {
            false // the link's executor is shut down
        }
    }

    private var houseWasEnabled = false
    private var dndWatching = false

    /** The roster, a switch, the connection or Android's Do Not Disturb changed: say what the house should hear, if anything. */
    private fun houseStateChanged() {
        val on = houseEnabled()
        if (!on && !houseWasEnabled) return // off, and was off: nothing is touched (no settings read, no post, no command)
        if (on && !dndWatching) {
            dndWatching = true
            try {
                androidx.core.content.ContextCompat.registerReceiver(
                    app,
                    object : android.content.BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: android.content.Intent?) = houseStateChanged()
                    },
                    android.content.IntentFilter(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            } catch (e: Exception) {
                log.log(HaLog.Level.WARN, "house: Do Not Disturb changes cannot be watched (${e.javaClass.simpleName})")
            }
        }
        if (houseWasEnabled && !on) houseRing.onSwitchedOff()
        houseWasEnabled = on
        if (!houseReceiveOn()) houseRing.onSwitchedOff()
        houseDnd.evaluate(on, signaling.state == SignalingClient.State.CONNECTED, HouseDnd.reported(houseReceiveOn(), houseDndShared(), osDndOn()))
    }

    /** Setup's two house switches changed. */
    fun onHouseSettingsChanged() = houseStateChanged()

    // ---- DEV-807: Panel mode (slice 1) ---------------------------------------------------------------------------------
    // Design: the estate repo automation/portico_tablet_dashboard_mode_design.md sections 9 and 10. What a real tablet must prove (the
    // return to Fully, the overlay over Fully, the wake on a ring) is NOT here: it is slice 2.

    private val panelExec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "portico-panel").apply { isDaemon = true } }
    private val panelLevels = AndroidStreamLevels(app)
    private val panelVolume = PanelVolumeKeeper(panelLevels, PrefsSavedLevels(app.getSharedPreferences(PrefsSavedLevels.FILE, Context.MODE_PRIVATE)))

    // ---- slice 2: the screen slug, the door filter, the front machine ----------------------------------------------------

    /** The screen slug this panel names in content calls (`endpoint=<slug>`); null = none named (not adopted yet, or stale). Mirrored from prefs. */
    @Volatile
    private var panelSlug: String? = try { statePrefs.getString(KEY_PANEL_SLUG, null)?.takeIf { it.length in 1..64 } } catch (_: Exception) { null }

    /** The slug the link names, or null (always null outside Panel mode: a phone never names an endpoint). */
    fun panelEndpoint(): String? = if (panelModeOn) panelSlug else null

    private fun setPanelSlug(slug: String?) {
        panelSlug = slug
        try { statePrefs.edit().also { e -> if (slug == null) e.remove(KEY_PANEL_SLUG) else e.putString(KEY_PANEL_SLUG, slug) }.apply() } catch (_: Exception) { }
    }

    /** A named content call was refused. Returns true when it was the slug that is stale (the caller then does not treat it as "not enrolled"). */
    private fun panelRefusedByCall(kind: ContentCommands.ErrorKind, what: String): Boolean {
        if (!panelModeOn) return false
        if (what != "get" && kind == ContentCommands.ErrorKind.UNAUTHORIZED) {
            refreshContent() // a run or still refused as unauthorized: re-read, the content decides whether the slug is stale
            return false
        }
        return panelRefused(kind, what)
    }

    private fun panelRefused(kind: ContentCommands.ErrorKind, what: String): Boolean {
        if (!panelModeOn) return false
        return when (PanelSlug.afterRefusal(panelSlug, kind)) {
            SlugStep.Drop -> { panelSlugLost(what); true }
            else -> false
        }
    }

    /** The slug is stale: forget it (and its `wake`), ask without `endpoint`, re-read `screens`. Until a slug is back the panel rings for every door. */
    private fun panelSlugLost(what: String) {
        val had = panelSlug ?: return
        setPanelSlug(null)
        pushJournal.diag("wall", "endpoint-lost", "via" to what)
        log.log(HaLog.Level.WARN, "panel: the screen slug is stale (refused on $what); asking without it")
        signaling.post { l ->
            l.resubscribeContent() // the phone's own subscription: a slug that was refused left none
            l.requestContent()
        }
        panelHeartbeat.kick()
        if (had.isEmpty()) return
    }

    /** A content profile arrived: adopt the one screen `screens` names, or notice that the slug is gone from `screens`. */
    private fun panelContentArrived(outcome: ContentCommands.Outcome) {
        if (!panelModeOn || outcome !is ContentCommands.Outcome.Profile) return
        val p = outcome.profile
        val wake = p.wake
        PanelWake.lineFor(if (panelSlug != null) wake else null).let { line -> if (line != lastWakeLine) { lastWakeLine = line; line?.let { pushJournal.diag("wall", it) } } }
        when (val step = PanelSlug.afterProfile(panelSlug, p)) {
            is SlugStep.Adopt -> {
                setPanelSlug(step.slug)
                pushJournal.diag("wall", "endpoint-adopted")
                signaling.post { l ->
                    l.resubscribeContent() // now for the screen
                    l.requestContent() // the screen's own document: its pages and its `wake`
                }
            }
            SlugStep.Drop -> panelSlugLost("screens")
            SlugStep.Keep -> Unit
        }
    }

    /** True when a door ring takes this panel: always off Panel mode; in it, the screen's `wake` (absent: every door; empty: none). */
    private fun panelDoorTaken(doorRef: String): Boolean {
        if (!panelModeOn) return true
        return try {
            val wake = if (panelSlug != null) content.profile.wake else null
            PanelWake.accepts(wake, doorRef, doors.all.firstOrNull { it.doorRef == doorRef })
        } catch (_: Exception) {
            true // fail open: a ring is never lost to a bug in the filter
        }
    }

    private val panelRings = PanelRingLog()

    /** How a door ring ended up (call id, door ref): the `wall ring-front` line and the heartbeat's `last_ring_*`. Panel mode only. */
    fun panelRingFront(callId: String, doorRef: String, path: RingPath) {
        if (!panelModeOn) return
        val door = try { doors.doorIdFor(doorRef) } catch (_: Exception) { null }
        panelRings.record(callId, door, path)
        pushJournal.diag("wall", "ring-front", "call" to com.meda.portico.core.push.PushJournalFormat.DiagId(callId), "door" to door?.take(10), "path" to path.wire)
        panelHeartbeat.kick() // at once: "whenever a ring is shown, filtered or fails"
    }

    private val panelIdleKey = "panel_idle_min"

    /** The idle timeout in minutes (default 5). */
    fun panelIdleMinutes(): Int = try { statePrefs.getInt(panelIdleKey, PanelFrontMachine.DEFAULT_IDLE_MIN) } catch (_: Exception) { PanelFrontMachine.DEFAULT_IDLE_MIN }

    fun setPanelIdleMinutes(m: Int) {
        try { statePrefs.edit().putInt(panelIdleKey, m.coerceIn(1, 60)).apply() } catch (_: Exception) { }
        panelHeartbeat.kick()
        journalConfig("setting") // 0.6.97: the Journal names the new idle timeout
    }

    /** Main thread only (the host's tick and touch callbacks). */
    val panelFront = PanelFrontMachine(clockMs = SystemClock::elapsedRealtime, idleMs = { PanelFrontMachine.idleMs(panelIdleMinutes()) })

    @Volatile
    private var panelFrontCount = 0

    @Volatile
    private var panelOverdue = false

    private var lastWakeLine: String? = null

    /** [com.meda.portico.panel.PanelFrontHost]: how many of our screens are started (in front), set on the main thread. */
    fun panelSetFrontCount(n: Int) { panelFrontCount = n }

    fun panelInFront(): Boolean = panelFrontCount > 0

    private fun ringUpNow(): Boolean =
        (try { ringState.activeRings().isNotEmpty() } catch (_: Exception) { false }) || (try { houseRing.state().ring != null } catch (_: Exception) { false })

    private fun callLiveNow(): Boolean = place.state().isLive || talk.state().isLive || answerLive()

    /** Feeds the machine the facts and returns what to do. Main thread, about every 250 ms while any screen of ours is started. */
    fun panelFrontTick(): FrontAction {
        val before = panelFront.reason
        panelFront.update(panelModeOn && settingsCache != null, panelInFront(), ringUpNow(), callLiveNow())
        val od = panelFront.overdue()
        val changed = before != panelFront.reason || od != panelOverdue
        panelOverdue = od
        if (changed) panelHeartbeat.kick() // `front_reason` or `overdue` changed
        val action = panelFront.tick()
        journalFrontEvents()
        return action
    }

    private var idleOffLogged = false

    /**
     * 0.6.97: one `wall idle-*` line per machine event (arm, bar, cancel, close), each with the settings it depends on, plus `idle-off` once per
     * time in front when nothing will count (Panel mode off, or not enrolled): that is the line that says why a wall tablet never closes.
     */
    private fun journalFrontEvents() {
        try {
            val front = panelInFront()
            if (!front) idleOffLogged = false
            else if (!(panelModeOn && settingsCache != null) && !idleOffLogged) {
                idleOffLogged = true
                pushJournal.diag("wall", "idle-off", "panel" to panelModeOn, "enrolled" to (settingsCache != null), "idlemin" to panelIdleMinutes())
            }
            for (e in panelFront.drainEvents()) {
                val common = arrayOf<Pair<String, Any?>>("panel" to panelModeOn, "idlemin" to panelIdleMinutes(), "barsec" to (PanelFrontMachine.BAR_LAST_MS / 1000).toInt())
                when (e) {
                    is FrontEvent.Armed -> pushJournal.diag("wall", "idle-arm", "idlesec" to e.idleMs / 1000, *common)
                    is FrontEvent.BarStart -> pushJournal.diag("wall", "idle-bar", "kind" to e.kind, "totalsec" to e.totalMs / 1000, *common)
                    is FrontEvent.Cancelled -> pushJournal.diag("wall", "idle-cancel", "why" to e.why, "kind" to e.kind, "bar" to e.barShown, *common)
                    is FrontEvent.Closing -> pushJournal.diag("wall", "idle-close", "why" to e.why, "idlesec" to e.idleMs / 1000, "sincetouchsec" to e.sinceTouchMs / 1000, *common)
                }
            }
        } catch (_: Exception) { }
    }

    /** A touch on any of our screens (Panel mode): the idle or return count starts over. */
    fun panelTouched() { if (panelModeOn) panelFront.touch() }

    /** A ring window ended without an answer while in front (Panel mode): the 10 s bar starts now. */
    fun panelRingEnded() { if (panelModeOn) panelFront.noteEnded() }

    /** The Close control: go back to Fully at once. */
    fun panelClosePressed() { if (panelModeOn) panelFront.closeNow() }

    /** The return is being issued (journal `wall return reason=... result=...`). */
    fun panelReturnLine(reason: FrontReason, result: String) {
        pushJournal.diag("wall", "return", "reason" to reason.wire, "result" to result)
        if (result == "failed") pushJournal.diag("wall", "return-failed")
        panelHeartbeat.kick()
    }

    /** The facts of one heartbeat, read when it is sent. */
    private fun panelFacts(): PanelFacts {
        val p = content.profile
        val foreground = panelInFront()
        val ringUp = ringUpNow()
        val callLive = callLiveNow()
        val slug = panelSlug
        val last = panelRings.last
        return PanelFacts(
            appVersion = connLogAppVersion,
            panelMode = panelModeOn,
            endpointOk = PanelSlug.endpointOk(slug, p),
            foreground = foreground,
            frontReason = FrontReasons.of(foreground, ringUp, callLive),
            overdue = panelOverdue,
            overlayOk = PanelAndroidFacts.overlayOk(app),
            fsiOk = PhonePermissions.fullScreenAllowed(app) ?: true,
            microphoneOk = PanelAndroidFacts.microphoneOk(app),
            notificationsOk = PanelAndroidFacts.notificationsOk(app),
            ringAudible = PanelAndroidFacts.ringAudible(app),
            batteryExempt = PanelAndroidFacts.batteryExempt(app),
            stayConnected = networkChoices()?.connection(NetClass.WIFI) == ConnectionMode.STAY,
            ringLevelOk = panelVolume.levelOk(PanelStream.ALARM),
            wakeDoors = if (slug != null) p.wake?.size else null, // null = `wake_all: true` (no slug, or the server sent no `wake`)
            lastRingCall = last?.callId,
            lastRingPath = last?.path?.wire,
            lastRingDoor = last?.door,
            phoneEndpoint = p.phoneEndpoint,
            houseSwitch = houseReceiveOn(),
            houseGate = true, // D-20261122-aafb: no global House calls switch; still reported (true) for a server that reads it
            autoRevokeExempt = PanelAndroidFacts.autoRevokeExempt(app),
        )
    }

    private var lastPanelRefetchAt = Long.MIN_VALUE / 2

    private val panelHeartbeat = PanelHeartbeatDriver(
        enabled = { panelModeOn },
        facts = ::panelFacts,
        send = { body, onOutcome ->
            try {
                signaling.post { link ->
                    val sent = link.sendCall(PanelHeartbeatCommands.HEARTBEAT, body) { onOutcome(PanelHeartbeatCommands.outcome(it)) }
                    if (!sent) onOutcome(HeartbeatOutcome.Lost)
                }
                true
            } catch (_: Exception) {
                false // the link's executor is shut down
            }
        },
        schedule = { delayMs, task ->
            val f = panelExec.schedule({ try { task() } catch (e: Exception) { log.log(HaLog.Level.ERROR, "panel: ${e.javaClass.simpleName} in a timer") } }, delayMs, TimeUnit.MILLISECONDS)
            val cancel: () -> Unit = { f.cancel(false) }
            cancel
        },
        clockMs = SystemClock::elapsedRealtime,
        onOutcome = { o ->
            when (o) {
                is HeartbeatOutcome.Ok -> {
                    // The server's id differs from the cached one: the content is stale. At most one refetch a minute.
                    val now = SystemClock.elapsedRealtime()
                    val slugMissing = !PanelSlug.endpointOk(panelSlug, content.profile) // stale or never-adopted slug: re-read `screens` at each heartbeat
                    if ((slugMissing || PanelHeartbeatCommands.needsRefetch(o.reply, content.profile.phoneEndpoint)) && now - lastPanelRefetchAt > 60_000L) {
                        lastPanelRefetchAt = now
                        refreshContent()
                    }
                }
                is HeartbeatOutcome.Error -> {
                    pushJournal.diag("wall", if (o.code == "app_too_old") "too-old" else "hb-refused", "code" to o.code.lowercase().take(24))
                    if (PanelFlag.isNotAPanel(o.code)) applyServerPanelFlag(false, "heartbeat") // the server's flag is off for this login
                }
                HeartbeatOutcome.Lost -> Unit
            }
        },
    )

    /** The content changed (a fetch answered): the phone endpoint may have become known, and the readiness inputs may differ. */
    private fun panelContentChanged() {
        if (!panelModeOn) return
        if (content.profile.phoneEndpoint != null) {
            houseRing.onEndpointKnown()
        } else if (houseReceiveOn()) {
            pushJournal.diag("wall", "house-endpoint-lost") // the server does not name the panel's phone endpoint: no house call can ring it
        }
        panelHeartbeat.kick()
    }

    /** Something a heartbeat reports may have changed (a screen came forward, a permission dialog closed): send if an input differs. */
    fun panelInputsMayHaveChanged() {
        if (panelModeOn) panelHeartbeat.inputsMayHaveChanged()
    }

    /**
     * The server's panel flag, as a content result or a heartbeat refusal states it (D-20261121-73ff). Nothing else makes a panel and there is
     * no switch. A change takes effect at once, with no restart. ON: the app's own house-call switch goes on, the heartbeat starts, the stay
     * policy re-reads. OFF: the heartbeat stops, the slug is forgotten (the phone's own document again), the volumes are restored and the
     * screens let go of keep-screen-on. The flag is cached for the next start.
     */
    private fun applyServerPanelFlag(on: Boolean, via: String) {
        val was = panelModeOn
        try { statePrefs.edit().putBoolean(KEY_PANEL_FLAG, on).apply() } catch (_: Exception) { }
        if (legacyPanelSwitchDropped) {
            legacyPanelSwitchDropped = false
            pushJournal.diag("wall", "legacy-switch-dropped", "server" to on)
        }
        if (on == was) return
        panelModeOn = on
        pushJournal.diag("wall", if (on) "panel-on" else "panel-off", "via" to via)
        if (on) {
            withSettings { s -> try { s?.panelTurnedOnByServer() } catch (_: Exception) { } }
        }
        journalConfig("setting")
        houseStateChanged()
        if (on) {
            stay.evaluate(true, "panel mode")
            if (signaling.state == SignalingClient.State.CONNECTED) panelHeartbeat.onSocketOpen()
        } else {
            panelHeartbeat.onSocketClosed()
            setPanelSlug(null) // a phone names no endpoint: its own document again, and its own subscription
            signaling.post { l ->
                l.resubscribeContent()
                l.requestContent()
            }
            panelExec.execute { try { panelVolume.restoreAll() } catch (_: Exception) { } }
            try { onPanelModeOff?.invoke() } catch (_: Exception) { }
        }
    }

    /** The enrolment ended or a new one was saved: the flag the server gave the old one means nothing for the next (a phone until the server says). */
    private fun forgetServerPanelFlag() {
        try { statePrefs.edit().remove(KEY_PANEL_FLAG).apply() } catch (_: Exception) { }
        if (panelModeOn) {
            panelModeOn = false
            pushJournal.diag("wall", "panel-off", "via" to "enrolment")
            panelHeartbeat.onSocketClosed()
            setPanelSlug(null)
            panelExec.execute { try { panelVolume.restoreAll() } catch (_: Exception) { } }
            try { onPanelModeOff?.invoke() } catch (_: Exception) { }
        }
    }

    /** The ring window's sound starts: the alarm stream goes to the configured level (Panel mode only). */
    fun panelRingSoundStarted() {
        if (!panelModeOn) return
        val ok = panelVolume.raise(PanelStream.ALARM, PanelVolumeKeeper.DEFAULT_RING_PCT)
        if (!ok) pushJournal.diag("wall", "perm-lost", "field" to "ring_level")
        panelHeartbeat.kick()
    }

    /** The ring window's sound ends (or the window is gone): the person's own level comes back. Idempotent, and runs in any mode. */
    fun panelRingSoundStopped() = panelVolume.restore(PanelStream.ALARM)

    /** A call's audio starts: the call stream goes to the configured level and the call-start line is written (Panel mode only). */
    fun panelCallStarted() {
        if (!panelModeOn) return
        panelVolume.raise(PanelStream.VOICE_CALL, PanelVolumeKeeper.DEFAULT_CALL_PCT)
        pushJournal.diag(
            "wall", "call",
            "route" to "speaker",
            "aec" to try { android.media.audiofx.AcousticEchoCanceler.isAvailable() } catch (_: Throwable) { false },
            "ns" to try { android.media.audiofx.NoiseSuppressor.isAvailable() } catch (_: Throwable) { false },
            "vol" to panelLevels.level(PanelStream.VOICE_CALL),
            "mic" to if (PanelAndroidFacts.microphoneOk(app)) "granted" else "denied",
        )
        panelHeartbeat.kick()
    }

    fun panelCallEnded() {
        panelVolume.restore(PanelStream.VOICE_CALL)
        if (panelModeOn) panelHeartbeat.kick()
    }

    /** A door call is live or held (the answer path owns the microphone). */
    private fun answerLive(): Boolean = answer.hasActiveSession()

    private fun linkNow(): LinkState = try {
        LinkState.valueOf(signaling.state.name)
    } catch (e: IllegalArgumentException) {
        LinkState.STOPPED
    }

    /**
     * Away Talk (plan: the estate repo portico_front_door_talk_plan.md section 13): the Talk audio is [webrtc] at home, and frames on the
     * HA socket (`portico/media_open` for the dialled `out-` id) when the link is on the tailnet or WebRTC does not connect.
     */
    private fun talkMedia(webrtc: com.meda.portico.core.call.TalkMediaPort): com.meda.portico.core.call.TalkMediaPort =
        com.meda.portico.core.call.TalkMediaPort { callId, doorId, listener ->
            val real = com.meda.portico.core.call.AwayTalkMedia(
                webrtc,
                TailnetAwayMedia({ linkPath }, { lastKnownPath }, LinkFrameChannel({ signaling }), { AndroidPcmDevice() }, ::onAnswerThread, log,
                    config = callAudio, scheduler = frameScheduler, onReport = ::frameAudioReport, onDiag = ::audioDiag, onEnv = ::audioEnv),
                com.meda.portico.core.call.Scheduler { delayMs, task ->
                    val f = answerExec.schedule({ try { task() } catch (e: Exception) { log.log(HaLog.Level.ERROR, "talk: ${e.javaClass.simpleName} in a timer") } }, delayMs, TimeUnit.MILLISECONDS)
                    Cancellable { f.cancel(false) }
                },
                log = log,
            )
            real.open(callId, doorId, listener)
        }

    /**
     * Talk to the door (blocks B7b, DEV-658). INERT: the only entry is `talk.press`, called by a press of a Talk button on
     * Home, and the coordinator does nothing unless [TalkGate] says the button is READY, which is the server's word
     * (`available: true`); today every door answers `available: false, reason: talk_off`, so no session is ever built.
     * The audio is the answer path's WebRTC ([WebRtcMedia] and `portico/webrtc_offer`) against the dialled call id, built
     * only when the station has answered. The call events reach it from the socket sink above; the foreground service,
     * the microphone permission (Home asks, as the call screens do) and the thread are the answer path's.
     */
    val talk: TalkCoordinator = TalkCoordinator(
        newSession = { request, hooks, gate, onChange ->
            TalkSession(
                request = request,
                commands = answerCommands,
                media = talkMedia(TalkMediaPort { callId, doorId, listener ->
                    AnswerPathTalkMedia(audioJournaled("talk", callId, SerialMedia(WebRtcMedia(app, log, pushJournal), ::onAnswerThread)), answerCommands, AnswerConfig(), log).open(callId, doorId, listener)
                }),
                audio = AndroidAudioRoute(app),
                mic = { app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
                scheduler = { delayMs, task ->
                    val f = answerExec.schedule({ try { task() } catch (e: Exception) { log.log(HaLog.Level.ERROR, "talk: ${e.javaClass.simpleName} in a timer") } }, delayMs, TimeUnit.MILLISECONDS)
                    Cancellable { f.cancel(false) }
                },
                wallClockMs = System::currentTimeMillis,
                gate = gate,
                hooks = hooks,
                config = TalkConfig(),
                log = log,
                onChange = onChange,
                onEndPath = { pushJournal.talkEnd(it) },
            )
        },
        post = ::onAnswerThread,
        serviceStart = {
            try {
                androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, CallService::class.java))
            } catch (e: Exception) {
                log.log(HaLog.Level.WARN, "talk: the call service could not start (${e.javaClass.simpleName})")
            }
        },
        serviceStop = {
            try {
                app.stopService(android.content.Intent(app, CallService::class.java))
            } catch (e: Exception) {
                log.log(HaLog.Level.WARN, "talk: the call service could not stop (${e.javaClass.simpleName})")
            }
        },
        paused = ::isPaused,
        otherCallLive = { answerLive() || place.state().isLive },
        gate = { request ->
            val action = content.action(request.actionId)
            TalkGate.ready(action, HomeModel.usable(linkNow(), content.support), GateCall.of(action, content.profile.roster, doors.all))
        },
        later = { delayMs, task ->
            val f = answerExec.schedule({ try { task() } catch (e: Exception) { log.log(HaLog.Level.ERROR, "talk: ${e.javaClass.simpleName} in a timer") } }, delayMs, TimeUnit.MILLISECONDS)
            Cancellable { f.cancel(false) }
        },
        log = log,
        onTalkOver = ::refreshContent, // the document's Talk reason was read mid-call (`in_call`); read it again once the talk is over
        journal = pushJournal,
        gateWhy = { request ->
            val action = content.action(request.actionId)
            TalkGate.why(action, HomeModel.usable(linkNow(), content.support), GateCall.of(action, content.profile.roster, doors.all))
        },
    )

    /** For Setup: the tailnet dial target's IP when it is one (the route line), else null. */
    fun tailnetDialIp(): String? = settings()?.let { TailnetTarget.ipOf(TailnetTarget.resolve(it.haUrl, it.haDial)) }

    /** For Setup's 2 s refresh: re-read the tailnet state off the UI thread. */
    fun refreshTailnet() {
        tailnetExec.execute { tailnet.refresh() }
    }

    /** What `PorticoMessagingService` calls. */
    val messaging: MessagingServiceLogic = MessagingServiceLogic(
        receiver = PushReceiver(
            callStore, doors::knownDoorRefs, dispatcher, log, resumedAt = pauseKeeper::resumedAt, journal = pushJournal,
            // M7 3b-B: a house ring's push, only while house calls are on for this phone and it is set to receive them
            houseOn = { houseEnabled() && houseReceiveOn() },
            onHouse = { msg, nowMs -> houseRing.onPush(msg, nowMs) },
            doorTaken = { panelDoorTaken(it) },
        ),
        deliver = { v -> signaling.post { it.onRegistrationValue(v) } },
        installationId = { settings()?.installationId },
        log = log,
        paused = ::isPaused,
        journal = pushJournal,
    )

    /**
     * Network changes re-run the path choice when the socket sits on the tailnet and the phone is
     * now on Wi-Fi/Ethernet (docs/tailnet.md "Re-routing home"). Never during a call.
     */
    private val pathRechecker = com.meda.portico.core.ha.PathRechecker(
        timer = { delayMs, task ->
            val f = directTimer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            val cancel: () -> Unit = { f.cancel(false) }
            cancel
        },
        clockMs = SystemClock::elapsedRealtime,
        path = { linkPath },
        callLive = { answerLive() || place.state().isLive || talk.state().isLive },
        paused = ::isPaused,
        reconnect = { signaling.reconnect() },
        trace = { word -> connTrace(word, null, null) },
    )
    private val pathRecheckWatcher = com.meda.portico.signaling.PathRecheckWatcher(app, pathRechecker, log)

    // ---- Stay connected -----------------------------------------------------------------------

    private val uiShown = AtomicInteger(0)

    @Volatile
    private var stayRunning = false

    /** The last connection used the tailnet; kept after a release, cleared by a network change (see [StayConnectedPolicy.Inputs.onTailnet]). */
    @Volatile
    private var lastTailnet = false

    private fun powerSave(): Boolean = try {
        app.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
    } catch (_: Exception) {
        false
    }

    @Volatile
    private var stayRefused = false

    private val stayNet = StayNetWatcher(app, log) {
        roaming.invalidate() // 0.6.58: the class (Wi-Fi, mobile, roaming) is read again before the policy looks at it
        stay.evaluate(false, "network")
    }

    private val stayPorts = object : StayPorts {
        override fun startService(): Boolean = try {
            androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, ConnectionService::class.java))
            true
        } catch (e: Exception) {
            // Not an allowed context right now (Android 12+), or refused: "not now". The next allowed context retries.
            log.log(HaLog.Level.INFO, "stay: the service could not start now (${e.javaClass.simpleName})")
            false
        }

        override fun stopService() {
            try {
                app.stopService(android.content.Intent(app, ConnectionService::class.java))
            } catch (e: Exception) {
                log.log(HaLog.Level.WARN, "stay: the service could not stop (${e.javaClass.simpleName})")
            }
        }

        override fun connect() {
            signaling.start()
        }

        override fun release() {
            signaling.stop()
            tailnetExec.execute { tailnet.sleep() } // nothing uses the node now; a push or the app opening wakes it
        }

        override fun updateNote(text: StayConnectedPolicy.Text) {
            if (stayRunning) runCatching { ConnectionService.post(app, text, stayHomeName()) }
        }
    }

    /** "Stay connected": see [StayConnectedPolicy]. Evaluate with allowStart only from a context Android lets start a foreground service. */
    val stay: StayConnected = StayConnected(
        ports = stayPorts,
        inputs = { stayInputs() },
        timer = { delayMs, task ->
            val f = directTimer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            val cancel: () -> Unit = { f.cancel(false) }
            cancel
        },
        clockMs = SystemClock::elapsedRealtime,
        journal = StayJournalSink { event, fields -> pushJournal.diag("net", event, fields) }, // 0.6.92: `net stay-release|connect|defer|idle why=<word>`
        ringCount = { try { ring.activeRings().size } catch (_: Exception) { 0 } },
        log = log,
    )

    /**
     * 0.6.92 (the owner 2026-11-21): the settings that decide how the connection, the ring and the call behave, and what the stay policy sees
     * now, as two compact fixed-word Journal lines (`net cfg`, `net inputs`). At app start, on a settings change, at the start of each ring
     * and each call. Observation only: never throws, never blocks a ring.
     */
    fun journalConfig(why: String) {
        try {
            val c = networkChoices()
            val nm = androidx.core.app.NotificationManagerCompat.from(app)
            val facts = ConfigFacts(
                versionName = connLogAppVersion,
                versionCode = try { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0)).toInt() } catch (_: Exception) { 0 },
                mobile = c?.connection(NetClass.MOBILE),
                wifi = c?.connection(NetClass.WIFI),
                roaming = c?.connection(NetClass.ROAMING),
                linkRace = callAudio.linkRace(),
                path = try { callAudio.pathChoice() } catch (_: Exception) { com.meda.portico.core.call.PathChoice.AUTO },
                compat = try { callAudio.compatibility() } catch (_: Exception) { false },
                route = try { com.meda.portico.call.CallAudioControl.get(app).choice() } catch (_: Exception) { com.meda.portico.core.call.RouteChoice.AUTO },
                ringChannel = try { Channels.state(app, com.meda.portico.core.ring.Channel.RING) } catch (_: Exception) { com.meda.portico.core.ring.ChannelState.MISSING },
                ringBypassDnd = try { Channels.bypassesDnd(app, com.meda.portico.core.ring.Channel.RING) } catch (_: Exception) { null },
                notificationsOn = try { nm.areNotificationsEnabled() } catch (_: Exception) { null },
                answerWithoutUnlock = callAudio.answerWithoutUnlock(),
                unlockWhenAnswering = callAudio.unlockWhenAnswering(),
                panel = panelModeOn,
                enrolled = settingsCache != null,
                idleMin = panelIdleMinutes(),
                barSec = (PanelFrontMachine.BAR_LAST_MS / 1000).toInt(),
            )
            val (e1, f1) = StayJournalLines.cfg(why, facts)
            pushJournal.diag("net", e1, f1)
            val i = stay.currentInputs() ?: return
            val (e2, f2) = StayJournalLines.inputs(why, i, netClass().key, uiShown.get())
            pushJournal.diag("net", e2, f2)
        } catch (_: Exception) {
        }
    }

    private var callWasLive = false

    /** A call (placed, talk or answer) became live: one settings snapshot at its start. */
    private fun noteCallEdge() {
        val live = callLiveNow()
        if (live && !callWasLive) journalConfig("call")
        callWasLive = live
    }

    private fun stayInputs(): StayConnectedPolicy.Inputs {
        val s = settings()
        return StayConnectedPolicy.Inputs(
            // 0.6.58: the service is wanted while any network class stays connected; the current class decides what the socket does
            switchOn = networkChoices()?.anyStay ?: SettingsKeys.STAY_CONNECTED_DEFAULT,
            pushHere = networkChoices()?.connection(netClass()) == ConnectionMode.PUSH,
            enrolled = s?.enrolled == true,
            paused = isPaused(),
            net = stayNet.current(),
            callLive = answerLive() || place.state().isLive || talk.state().isLive,
            uiVisible = uiShown.get() > 0,
            inGrace = false, // StayConnected fills it in
            serviceRunning = stayRunning,
            serviceRefused = stayRefused,
            linkConnected = signaling.state == SignalingClient.State.CONNECTED,
            powerSave = powerSave(),
            onTailnet = lastTailnet,
        )
    }

    /** The home's name for the service's notification, null when the plugin set none. */
    fun stayHomeName(): String? = content.profile.homeName

    /** What the service's first notification says (the policy's text for right now). */
    fun stayNoteText(): StayConnectedPolicy.Text = try {
        StayConnectedPolicy.decide(stayInputs()).text
    } catch (e: Exception) {
        StayConnectedPolicy.Text.CONNECTING
    }

    /** [ConnectionService] reached startForeground. Re-evaluates at once: it may be unwanted already (paused, switched off). */
    fun stayServiceStarted() {
        stayRunning = true
        log.log(HaLog.Level.INFO, "stay: the connection service is running")
        stay.evaluate(false, "service started")
    }

    fun stayServiceStopped() {
        stayRunning = false
    }

    /** `startForeground` failed for a permission-class reason: not tried again until the switch is toggled, a screen opens, or boot/update. */
    fun stayServiceRefused() {
        stayRefused = true
        stayRunning = false
    }

    /** Settings' switch changed (the screen is visible, so starting is allowed). A new choice clears an earlier refusal. */
    fun onStayConnectedChanged() {
        stayRefused = false
        stay.evaluate(true, "the switch changed")
    }

    /** A screen of ours (Home, Settings) became visible: starting is allowed, and the socket must be there for it. */
    fun uiStarted() {
        uiShown.incrementAndGet()
        panelInputsMayHaveChanged() // a permission dialog or Android's settings page may just have closed
        stayRefused = false // an allowed context always retries once
        if (!isPaused() && signaling.state == SignalingClient.State.STOPPED) signaling.wake() // released on mobile data
        stay.evaluate(true, "a screen opened")
    }

    fun uiStopped() {
        if (uiShown.decrementAndGet() < 0) uiShown.set(0)
        stay.evaluate(false, "a screen closed")
        if (panelModeOn) panelHeartbeat.kick() // `foreground` and `front_reason` changed
    }

    /** `Application.onCreate`: start the socket (and with it the FID request). */
    fun start() {
        try {
            Channels.ensure(app)
        } catch (e: Exception) {
            log.log(HaLog.Level.ERROR, "portico ring: channel set-up failed: ${e.javaClass.simpleName}")
        }
        log.log(HaLog.Level.INFO, boot.statusLine())
        tailnetExec.execute { tailnet.refresh() } // DEV-557: expires a stale key; never loads Go by itself
        redeemPendingCode() // DEV-544: a scan whose redemption the last process did not finish (the nonce was kept)
        recheckPause("process start")
        panelExec.execute { try { panelVolume.restoreAll() } catch (_: Exception) { } } // DEV-807: a kill while raised left the person's level saved
        connLog.seedNet(connNet.current()) // DEV-705
        drainQueuedReports() // DEV-762 A2: a crash record the last process queued
        connLog.trigger(TriggerKind.APP_START) // before the watcher, so its first callback extends this sequence
        connNet.start()
        try {
            // Battery saver on: a tailnet connection is let go; off: it is held again (StayConnectedPolicy).
            app.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    stay.evaluate(false, "battery saver " + if (powerSave()) "on" else "off")
                }
            }, android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        } catch (e: Exception) {
            log.log(HaLog.Level.WARN, "stay: no battery saver receiver (${e.javaClass.simpleName})")
        }
        signaling.start() // PAUSED (connects nothing) while paused
        var ringWasUp = false
        ring.addChangeListener { // a ring that ended unanswered, declined or answered: the fast watch stays only for an Answer press's window
            val up = try { ring.activeRings().isNotEmpty() } catch (_: Exception) { true }
            if (ringWasUp && !up) signaling.ringEnded()
            if (up != ringWasUp) {
                // 0.6.92: a ring is "busy" for the stay policy (it holds the socket for the ring, and for the grace after it), capped at 60 s
                // the stay rule first, THEN the snapshot: `net inputs` read ringLive before it was set and so printed ring=no in every real ring
                // (a measurement artifact; the rule itself was in force, as `stay-ring`/`stay-eval` now show)
                stay.onRingChanged(up)
                if (up) journalConfig("ring")
            }
            ringWasUp = up
        }
        ringPrewarm.attach() // after the ring path is built: it only listens to the ring state
        // A held call is still a call (15.13): the path recheck and the stay-connected policy wait for the last one.
        answer.addListener { if (!it.isLive && !answerLive()) { pathRechecker.onCallEnded(); stay.evaluate(false, "call ended") }; noteCallEdge() }
        place.addListener { if (!it.isLive) { pathRechecker.onCallEnded(); stay.evaluate(false, "call ended") }; noteCallEdge() }
        place.addListener { panelCall.onPlaceState(it); ringback.update(RingbackPolicy.wanted(panelModeOn, it)) } // DEV-807 follow-up
        talk.addListener { if (!it.isLive) { pathRechecker.onCallEnded(); stay.evaluate(false, "call ended") }; noteCallEdge() }
        pathRecheckWatcher.start()
        // A process start is a wake of the socket (grace on mobile data); starting the service may be refused from the background and is then a logged "not now".
        stayNet.start()
        stay.onWoken()
        stay.evaluate(true, "process start")
        journalConfig("start")
    }

    // ---- Pause --------------------------------------------------------------------------------

    /**
     * Pause this phone for [lengthMs] ([PauseRules.ONE_HOUR], [PauseRules.FOUR_HOURS] or
     * [PauseRules.UNTIL_RESUMED]). The state is COMMITTED first, so every restart path already
     * refuses while the rest is torn down; a process killed mid-teardown comes back paused.
     * Only stops things: no network. False if the state could not be saved (nothing paused).
     */
    fun pause(lengthMs: Long): Boolean {
        val s = pauseKeeper.pause(lengthMs) ?: run {
            log.log(HaLog.Level.WARN, "pause: the state could not be saved; not paused")
            return false
        }
        log.log(HaLog.Level.INFO, "pause: PAUSED ${untilText(s)}; not connecting, every push dropped")
        pauseNotices.armAlarm(s)
        pauseNotices.post(s)
        ring.stopAllRings("paused") // a ring already up would go on for a doorbell this phone now ignores
        place.hangUp()
        talk.hangUp()
        answer.hangUpAll() // a call under way (and a held one, 15.13) is released while the socket is still up; if it is not in time it stays owed and is swept at resume
        signaling.pause() // closes the socket, cancels the retry and the ticker
        tailnetExec.execute { tailnet.pause() } // stops the node (up or joining); never on the UI thread
        stay.evaluate(false, "paused") // stops the connection service and its notification
        changed()
        return true
    }

    /**
     * THE ONE WAY OUT (Setup's Resume, the notification's Resume, the deadline): what a normal
     * start does. The socket connects (direct first, the tailnet when that fails), the machine
     * registers on `auth_ok`, so a registration value that rotated while paused reaches HA now.
     * Idempotent; any thread. Errors surface where they always do (Setup's lines).
     */
    fun resume(why: String) {
        val at = pauseKeeper.resume()
        pauseNotices.cancelAlarm()
        pauseNotices.cancelNotification()
        if (at == null) return // not paused (a stale alarm, a second tap): nothing to restart
        log.log(HaLog.Level.INFO, "pause: RESUMED ($why); reconnecting as on a normal start")
        tailnetExec.execute { tailnet.refresh() }
        signaling.start()
        stay.onWoken()
        stay.evaluate(true, "resumed") // the notification's Resume, Settings and the alarm; refused quietly if the system says not now
        changed()
    }

    /** The auto-resume alarm. Re-checks rather than trusting it (stale alarm, early after a clock change). */
    fun onPauseAlarm() {
        val s = pauseKeeper.state()
        if (!s.paused) {
            pauseNotices.cancelNotification()
            return
        }
        if (PauseRules.expired(s, System.currentTimeMillis())) resume("the pause ran out") else pauseNotices.armAlarm(s)
    }

    /** "until 14:30" / "until resumed", for the log. */
    private fun untilText(s: PauseState): String = if (s.untilMs == 0L) "until resumed" else "until ${PauseNotices.hhmm(s.untilMs)}"

    /**
     * Process start and boot: the deadline may have passed while the process was dead or the
     * phone off, and a reboot loses the alarm. Still paused: re-arm and re-post. Expired:
     * [PauseKeeper.active] has already queued the resume. Not paused: clear a leftover notification.
     */
    private fun recheckPause(why: String) {
        try {
            val s = pauseKeeper.state()
            if (!s.paused) {
                pauseNotices.cancelNotification()
                return
            }
            if (!pauseKeeper.active()) return // expired: the resume is queued
            log.log(HaLog.Level.INFO, "pause: still paused at $why, ${untilText(s)}")
            pauseNotices.armAlarm(s)
            pauseNotices.post(s)
        } catch (e: Exception) {
            log.log(HaLog.Level.WARN, "pause: re-check at $why failed: ${e.javaClass.simpleName}")
        }
    }

    private fun changed() {
        try {
            onPauseChanged?.invoke()
        } catch (e: Exception) {
            // a screen's refresh must not break a pause or a resume
        }
    }

    /**
     * After a Setup save: re-run the Firebase boot (a first config initialises now; a changed one
     * waits for a restart), give the machine the config, and reconnect with the new URL/token.
     * DEV-557: [joinTailnet] (the save carried a Tailscale key) starts redeeming it at once.
     */
    fun onSettingsChanged(joinTailnet: Boolean = false): BootResult {
        boot = FirebaseBoot.ensure(app) { settings() }
        tokens.invalidate() // SEC-03: a save may have replaced the login; never keep serving the old in-memory access token
        content.reset() // another HA or phone may be behind the new settings: nothing of the old content stays
        if (joinTailnet) tailnetExec.execute { tailnet.onEnrollmentSaved() }
        val cfg = registrationConfig()
        signaling.post { it.onConfigChanged(cfg) }
        signaling.start()
        signaling.reconnect()
        stay.evaluate(true, "settings saved")
        log.log(HaLog.Level.INFO, boot.statusLine())
        return boot
    }

    /**
     * Offline registration, for Setup: everything `:core`'s [OfflineRegistration.decide] reads.
     * The registration value is the machine's stored one for the RUNNING Firebase project
     * ([PrefsRegistrationStore.loadValue] returns null for any other project, or none yet).
     */
    fun offlineInputs(): OfflineInputs {
        val s = settings()
        val link = try {
            LinkState.valueOf(signaling.state.name)
        } catch (e: IllegalArgumentException) {
            LinkState.STOPPED
        }
        return OfflineInputs(
            configured = s?.enrolled == true,
            link = link,
            notConnectedForMs = signaling.notConnectedForMs(),
            regStatus = registrationStatus,
            value = regStore.loadValue(),
            enrollId = s?.enrollId,
            config = registrationConfig(),
        )
    }

    // ---- Home: the phone's own content ----------------------------------------------------------

    /**
     * Presses [actionId] (an id from [content]'s profile; the phone never names a service or an entity).
     * False, and the button's result is "not connected", when the socket is not up: nothing is queued.
     * Returns at once; the answer reaches [content] ([ContentRepository.lastRun]).
     */
    fun runAction(actionId: String): Boolean {
        // A Talk button is never run this way: a plain run would dial with no session to follow, hear or hang up the call.
        // Its only start is `talk.press` (blocks B7b), and that only for a button the server calls ready.
        if (content.action(actionId)?.isTalk == true) return false
        if (isPaused() || !content.markRunning(actionId)) return false
        signaling.post { l ->
            if (!l.runAction(actionId)) content.onRunNotSent(actionId)
        }
        return true
    }

    /**
     * One still from camera [cameraId] of the document (a page's or a caller's), if the socket is up; the answer reaches
     * [content]. False when nothing was sent: paused, or a request for this camera is already in flight (the guard is
     * shared by Home, the ring screen and the in-call screen), or the socket is down. [width] null asks for the server's
     * default (640); Home sends `StillWidth`'s answer for the tile's size and zoom, the call screens send none.
     */
    fun requestSnapshot(cameraId: String, width: Int? = null): Boolean {
        if (isPaused()) return false
        if (roamingBlocked()) return false // A-10: the picture is held back on a roaming plan (the tile says so and offers the tap)
        if (videoVerdict().picturesOff) return false // 0.6.58: this network's video row is Off: no stills either
        if (!content.beginSnapshot(cameraId)) return false
        signaling.post { if (!it.requestSnapshot(cameraId, width)) content.endSnapshot(cameraId) }
        return true
    }

    /** Asks HA for the profile again (Home's Refresh; an answer reaches [content]). */
    fun refreshContent() {
        if (isPaused()) return
        signaling.post { it.requestContent() }
    }

    /** PR-F, for Setup: ask HA for `portico/push/status` (answer lands in [pushStatus]). */
    fun refreshPushStatus() {
        if (isPaused()) return // no portico/push/status while paused (the socket is closed anyway)
        signaling.post { it.requestPushStatus() }
    }

    /** BOOT_COMPLETED / MY_PACKAGE_REPLACED: the process is up again; reconnect, which re-registers. */
    fun onBootOrUpdate() {
        boot = FirebaseBoot.ensure(app) { settings() }
        recheckPause("boot") // re-arms the auto-resume alarm a reboot cleared
        signaling.wake() // refused while paused
        stay.onWoken()
        stayRefused = false // an allowed context always retries once
        stay.evaluate(true, "boot or update") // BOOT_COMPLETED / MY_PACKAGE_REPLACED may start it; connectedDevice is not among Android 15's barred types
    }

    private companion object {
        const val KEY_PANEL_SLUG = "panel_slug"
        const val KEY_PANEL_FLAG = "panel_flag"

        const val KEY_STARTUP = "startup_inflight" // DEV-762 A3 (C6)
        const val KEY_EXIT_CURSOR = "exit_last_t"
        const val KEY_LAST_CRASH = "last_crash_ms"
        const val STARTUP_CLEAR_MS = 45_000L
        /** How long a tailnet connect waits for the node to come up (a first join may take a while). */
        const val TAILNET_WAIT_MS = 45_000L

        /** DEV-694 (the owner): how long a tailnet node that just carried a connect stays warm after the direct path wins. */
        const val TAILNET_HOLD_MS = 180_000L

        /** After a failed Settings open, the earliest the next background caller tries again (APP-L5). */
        const val SETTINGS_RETRY_MS = 30_000L
    }

    private fun registrationConfig(): RegistrationConfig {
        val version = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        } catch (_: Exception) {
            null
        } ?: "0"
        // An unopenable Settings (Keystore lost) has no HA token either, so nothing connects;
        // the blank id is then refused before the wire by PushCommands.register.
        return boot.registrationConfig(settings()?.installationId ?: "", version.take(64))
    }
}
