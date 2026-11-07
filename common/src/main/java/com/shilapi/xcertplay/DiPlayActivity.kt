// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.view.Window
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.ui.GaugeView
import com.shilapi.xcertplay.ui.Glyph
import com.shilapi.xcertplay.ui.GlyphView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.shilapi.xcertplay.compat.systemService
import com.shilapi.xcertplay.compat.systemServiceOrNull
import com.shilapi.xcertplay.compat.hasPermissionCompat

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"

    /** 0 = wireless page, 1 = wired page. The two are separate pages, not a control on one page. */
    private var connectionMode = 0
    private var headerStatusText: TextView? = null
    private var headerStatusDot: View? = null
    /** 0 idle, 1 connecting, 2 active: the tick returns early while this does not move. */
    private var lastGaugeState = -1
    private var gaugeView: GaugeView? = null
    private var gaugeTitle: TextView? = null
    private var gaugeSubtitle: TextView? = null
    /** Last line drawn under the gauge, so the once-a-second poll only touches the view when it moves. */
    private var lastStage: String? = null
    private var gaugePill: TextView? = null
    /** The action card's title and hint: they carry the last failure once a failed attempt has ended. */
    private var actionTitle: TextView? = null
    private var actionHint: TextView? = null
    /** Opens the failure-detail pages; shown only while a failed, interrupted or no-video attempt is on record. */
    private var failureDetailButton: View? = null
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null

    /** The last stack change was a trip to the car's own settings, so the page needs a full rebuild. */
    private var leftForSystemSettings = false
    private var pendingWireless = false

    /** A reconnect-only setting changed while a session was running; the header offers save/cancel. */
    private var pendingReconnect = false
    private var pendingSnapshot: Map<String, Map<String, *>>? = null
    private var pendingActions: LinearLayout? = null
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var usbPermissionOperation: UsbPermissionSetup.Operation? = null
    private var usbPermissionDialog: AlertDialog? = null
    internal var usbPermissionOperationFactory: (Context) -> UsbPermissionSetup.Operation = {
        UsbPermissionSetup.Operation(it.applicationContext)
    }
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var rootScroll: ScrollView? = null
    private var renderedPage: String? = null
    private var pendingScrollY: Int? = null
    private var carButtonCard: LinearLayout? = null
    private var adbSwitchChangePending = false
    private var pausedForAdbSwitchChange = false
    private var updatingAdbSwitches = false
    private var adbStatus: TextView? = null
    private val adbSwitches = mutableMapOf<Int, Pair<Switch, () -> Boolean>>()
    private var hotspotStartupResult: CarHotspotTethering.Result? = null
    @Volatile private var startupHotspotCancelled = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothAutoConnectPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        DiPlayPreferences.saveConnectOnPhoneBluetooth(this, granted)
        render()
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) {
            applyLocationReporting(true)
        } else {
            render()
            permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
        }
    }
    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) iconCrop.launch(Intent(this, ImageCropActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    private val iconCrop = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        refreshCarButton()
        carButtonSaved()
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        languagePreferenceAtCreate = AppLocale.preference(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        // The page follows the running session's transport, else the saved mode (ConnectionModePolicy), so
        // coming back from the host (which can recreate this activity) does not flip a wired session onto
        // the wireless page.
        connectionMode = if (convergedWireless()) 0 else 1
        // Back on the home page finishes this activity while the session runs on, so the launcher icon lands
        // here with nothing under it. Going straight to CarPlay from onCreate - and finishing - means the
        // connect screen never flashes on the way. Ported from upstream 10f19a6b; the switch is honoured
        // because `openCarPlayScreenIfConnected` is what decides.
        if (savedInstanceState == null && isLauncherIntent(intent) &&
            CarPlayBackgroundSession.hasSession() && openCarPlayScreenIfConnected()
        ) {
            finish()
            return
        }
        // Capture the ThreadGroup while the ROM still reports one; see WorkerThreads.
        com.shilapi.xcertplay.compat.WorkerThreads.capture()
        warmUpPreferences()
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page == "home") {
                    isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true
                    return
                }
                // Back out of a sub-page goes straight back to the picture when a session is running and the
                // owner has "open CarPlay when the app opens" on - the same switch that decides the launcher
                // behaviour, so there is only one setting for "take me back to CarPlay".
                if (CarPlayBackgroundSession.active && DiPlayPreferences.resumeCarPlayOnOpen(this@DiPlayActivity)) {
                    page = "home"
                    openProjection()
                } else {
                    page = "home"; render()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        handleWirelessRecovery()
        if (consumeBluetoothAutoConnectIntent(intent)) {
            if (initialLaunch) { initialLaunch = false; startCarHotspotOnLaunch() }
            handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
        }
        openCarPlayScreenIfConnected()
    }

    /**
     * A bare tap on the launcher icon.
     *
     * The launcher sends `ACTION_MAIN` + `CATEGORY_LAUNCHER` and nothing else; every internal entry point
     * (notification, Bluetooth auto-connect, the recovery links) carries extras instead — in particular a
     * `page` — so they must not be treated as "the driver wants CarPlay back". Ported from upstream 10f19a6b,
     * which also notes that the user-leave hint cannot tell these cases apart.
     */
    internal fun isLauncherIntent(intent: Intent?): Boolean =
        intent != null && intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.getStringExtra("page") == null

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }

    override fun onStart() {
        super.onStart()
        carPlayScreenBroughtForward = false
        syncNightModeWithScreen()
    }

    override fun onStop() {
        if (ambientListening) {
            ambientLight.stop()
            ambientListening = false
        }
        cancelUsbPermissionSetup()
        startupHotspotCancelled = true
        super.onStop()
    }

    /** Set while this activity hands the foreground to the running CarPlay screen. */
    private var carPlayScreenBroughtForward = false

    override fun onResume() {
        super.onResume()
        if (AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page. Coming back from
        // the CarPlay screen is not that case: a full render there rebuilt the page the instant a connect
        // started, which read as the whole screen being re-launched (owner report, 2026-10-29). The
        // settings page still rebuilds, because its switches can be changed from the CarPlay overlay too.
        if (!initialLaunch && !adbSwitchChangePending && !pausedForAdbSwitchChange &&
            (page == "home" || page == "settings" || page == "connection")) {
            if (leftForSystemSettings || page == "settings") {
                leftForSystemSettings = false
                render()
            } else {
                refreshStatus()
            }
        }
        pausedForAdbSwitchChange = false
        if (initialLaunch) {
            initialLaunch = false
            startCarHotspotOnLaunch()
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
        if (consumeBluetoothAutoConnectIntent(intent)) {
            if (initialLaunch) { initialLaunch = false; startCarHotspotOnLaunch() }
            handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
        }
        openCarPlayScreenIfConnected()
    }

    /**
     * True when [PhoneBluetoothReceiver] opened us for the selected iPhone. The receiver's extra is
     * consumed here, and the request only counts when nothing is connected yet.
     */
    /**
     * A running CarPlay session already owns the video surface, so tapping DiPlay goes straight back
     * to the CarPlay screen instead of the wired/wireless page. That is what the driver expects, and
     * it keeps the host activity's surface from being torn down and rebuilt for nothing. The
     * connection page stays underneath, so Back still reaches it.
     *
     * The owner can turn this off ([DiPlayPreferences.resumeCarPlayOnOpen]), in which case the home page
     * shows as it did before the behaviour was added. That only covers the *already connected* case: with no
     * session there is nothing to return to, and dialling out stays with the auto-connect switches.
     */
    private fun openCarPlayScreenIfConnected(): Boolean {
        val explicitPage = intent.getStringExtra("page")
        val active = CarPlayBackgroundSession.active
        val owns = CarPlayBackgroundSession.isOwner(this)
        val resume = DiPlayPreferences.resumeCarPlayOnOpen(this)
        if (explicitPage != null || !active || !resume) {
            android.util.Log.i(
                "DiPlay-Open",
                "staying on the connection page page=$explicitPage active=$active owner=$owns " +
                    "resumeCarPlay=$resume",
            )
            return false
        }
        return runCatching {
            startActivity(
                Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            carPlayScreenBroughtForward = true
            true
        }.onFailure {
            android.util.Log.w("DiPlay-Open", "bringing the CarPlay screen forward failed", it)
        }.getOrDefault(false)
    }

    private fun consumeBluetoothAutoConnectIntent(intent: Intent): Boolean {
        val requested = intent.getBooleanExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, false)
        intent.removeExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT)
        return requested && DiPlayPreferences.connectOnPhoneBluetooth(this) &&
            DiPlayPreferences.phoneAddress(this) != null && setupError == null &&
            !CarPlayBackgroundSession.hasSession()
    }
    override fun onPause() {
        pausedForAdbSwitchChange = adbSwitchChangePending
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        cancelUsbPermissionSetup()
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        render()
    }

    private val isCompactLayout: Boolean
        // Window size only: a multi-window task can fill the whole screen, and in multi-window the
        // configuration already reports the window's own size.
        get() = resources.configuration.screenWidthDp < 550 ||
            resources.configuration.screenHeightDp < 450

    // ------------------------------------------------------------------ page shell

    /**
     * Draws the whole page from scratch.
     *
     * The tree is three fixed rows and a body: header (64 dp), the connection tabs on the home page (66 dp),
     * the body (weight 1) and the bottom tab bar (62 dp). Everything below the header is one of four pages,
     * so the shell never changes shape and the driver's eye always finds the same anchors.
     *
     * Cost: one pass over ~80 small views per navigation. The one-second status tick does not come through
     * here - it updates text on views this method keeps references to (see [refreshStatus]).
     */
    private fun render() {
        // A restore still waiting for layout keeps its target: the old page was never laid out.
        val previousScrollY = (pendingScrollY ?: rootScroll?.scrollY)?.takeIf { renderedPage == page }
        status = null; connectButton = null; disconnectButton = null; lastRunning = null; carButtonCard = null
        adbSwitches.clear()
        adbStatus = null
        headerStatusText = null; headerStatusDot = null
        gaugeView = null; gaugeTitle = null; gaugeSubtitle = null; gaugePill = null
        actionTitle = null; actionHint = null; failureDetailButton = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        root.addView(buildHeaderBar(), LinearLayout.LayoutParams(-1, dp(64)))
        if (page == "home") root.addView(buildConnectionTabs(), LinearLayout.LayoutParams(-1, dp(66)))
        val body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        when (page) {
            "settings" -> settingsPage(body)
            "connection" -> body.addView(scrollPage { connectionSetup(it) }, ViewGroup.LayoutParams(-1, -1))
            "about" -> body.addView(scrollPage { about(it) }, ViewGroup.LayoutParams(-1, -1))
            else -> buildHome(body)
        }
        root.addView(buildBottomBar(), LinearLayout.LayoutParams(-1, dp(62)))
        setContentView(root)

        renderedPage = page
        lastRenderedNight = resolvedNight
        refreshStatus()
        pendingScrollY = previousScrollY
        previousScrollY?.let { y ->
            rootScroll?.doOnLayout { scroll ->
                if (rootScroll === scroll) {
                    scroll.scrollTo(0, y)
                    pendingScrollY = null
                }
            }
        }
    }

    private fun scrollPage(build: (LinearLayout) -> Unit): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(BG)
        }
        val content = column().apply { setPadding(dp(20), dp(8), dp(20), dp(24)) }
        scroll.addView(content)
        build(content)
        rootScroll = scroll
        return scroll
    }

    private fun buildHeaderBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        val mark = FrameLayout(this).apply { background = roundedR(ACCENT, 12) }
        mark.addView(
            GlyphView(this, Glyph.PHONE).apply { color = BUTTON_TEXT },
            FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER),
        )
        bar.addView(mark, LinearLayout.LayoutParams(dp(40), dp(40)))

        val titles = column().apply {
            setPadding(dp(12), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        titles.addView(label("SBplay", 17, TEXT, true))
        titles.addView(label("CarPlay · CS11", 10, MUTED))
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))

        // Shown only when a session is running and a restart-only setting was touched: the driver decides
        // whether to drop the session now or keep it and let the change apply next time.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (pendingReconnect) View.VISIBLE else View.GONE
        }
        actions.addView(
            SettingsWidgets.createButton(
                context = this,
                title = getString(R.string.discard_changes),
                style = SettingsWidgets.ButtonStyle.SECONDARY,
                theme = nightPalette,
                minHeightDp = 34,
                textSizeSp = 12f,
            ) { cancelPendingReconnect() },
            LinearLayout.LayoutParams(-2, dp(34)),
        )
        actions.addView(
            SettingsWidgets.createButton(
                context = this,
                title = getString(R.string.save_and_reconnect),
                style = SettingsWidgets.ButtonStyle.PRIMARY,
                theme = nightPalette,
                minHeightDp = 34,
                textSizeSp = 12f,
            ) { applyPendingReconnect() },
            LinearLayout.LayoutParams(-2, dp(34)).apply { leftMargin = dp(8) },
        )
        bar.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        pendingActions = actions
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(14), dp(6))
            background = roundedR(SURFACE, 16, DIVIDER)
        }
        val dot = View(this).apply { background = circle(MUTED) }
        pill.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(7) })
        val text = label(headerStatusTextValue(), 11, MUTED, true)
        pill.addView(text)
        bar.addView(pill)
        headerStatusText = text
        headerStatusDot = dot
        return bar
    }

    /**
     * Wireless / wired as two pages rather than a control on the page: each mode owns its own state line,
     * primary action and secondary card, and the gauge follows the tab.
     */
    private fun buildConnectionTabs(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(8))
        }
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedR(RAISED, 16)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val tabs = listOf(Glyph.WIFI to "无线连接", Glyph.USB to "有线连接")
        tabs.forEachIndexed { index, (glyph, title) ->
            val active = connectionMode == index
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = if (active) roundedR(ACCENT_SOFT, 13) else null
                isClickable = true
                setOnClickListener {
                    when (ConnectionModePolicy.onTabClick(
                        currentWireless = connectionMode == 0,
                        clickedWireless = index == 0,
                        hasSession = CarPlayBackgroundSession.hasSession(),
                    )) {
                        ConnectionModePolicy.TabClick.UNCHANGED -> Unit
                        // While a session runs the tab stays on its transport and the saved mode is left
                        // alone: the host re-reads it on every resume, so changing it now would switch the
                        // next automatic retry to the other transport.
                        ConnectionModePolicy.TabClick.BLOCKED_BY_SESSION ->
                            toast("当前是${if (connectionMode == 0) "无线" else "USB"}连接，请先断开再切换。")
                        ConnectionModePolicy.TabClick.SWITCH_AND_SAVE -> {
                            // Saved at once, so auto-connect, the Bluetooth receiver, the hotspot autostart
                            // and the report all follow the tab that is on screen.
                            AirPlayPersistence.saveWirelessEnabled(this@DiPlayActivity, index == 0)
                            connectionMode = index
                            render()
                        }
                    }
                }
            }
            cell.addView(
                GlyphView(this, glyph).apply { color = if (active) ACCENT else MUTED },
                LinearLayout.LayoutParams(dp(18), dp(18)),
            )
            cell.addView(label(title, 14, if (active) ACCENT else MUTED, active).apply {
                setPadding(dp(8), 0, 0, 0)
            })
            track.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        }
        outer.addView(track, LinearLayout.LayoutParams(-1, 0, 1f))
        return outer
    }

    private fun buildBottomBar(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
        }
        wrapper.addView(View(this).apply { setBackgroundColor(DIVIDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val items = listOf(
            Triple(Glyph.HOME, "首页", "home"),
            Triple(Glyph.SLIDERS, "设置", "settings"),
            Triple(Glyph.PULSE, "诊断", "diagnostics"),
        )
        val activeIndex = when {
            page == "settings" && settingsCategoryIndex == 4 -> 2
            page == "home" -> 0
            else -> 1
        }
        items.forEachIndexed { index, (glyph, title, target) ->
            val active = index == activeIndex
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { navigate(target) }
            }
            cell.addView(
                GlyphView(this, glyph).apply { color = if (active) ACCENT else MUTED },
                LinearLayout.LayoutParams(dp(20), dp(20)),
            )
            cell.addView(label(title, 10, if (active) ACCENT else MUTED, active).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            bar.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        }
        wrapper.addView(bar, LinearLayout.LayoutParams(-1, 0, 1f))
        return wrapper
    }

    private fun navigate(target: String) {
        when (target) {
            "home" -> { page = "home"; settingsDetail = null }
            "diagnostics" -> { page = "settings"; settingsDetail = null; settingsCategoryIndex = 4 }
            else -> { page = "settings"; settingsDetail = null; settingsCategoryIndex = 0 }
        }
        render()
    }

    // ------------------------------------------------------------------ home

    private fun buildHome(host: ViewGroup) {
        val wide = !isCompactLayout && resources.configuration.screenWidthDp >= 850
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(BG)
        }
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(12))
        }
        scroll.addView(pad, ViewGroup.LayoutParams(-1, -1))
        host.addView(scroll, ViewGroup.LayoutParams(-1, -1))
        rootScroll = scroll

        if (wide) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(buildGaugeCard(330), LinearLayout.LayoutParams(0, -1, 0.47f))
            row.addView(View(this), LinearLayout.LayoutParams(dp(14), 1))
            row.addView(buildRightColumn(), LinearLayout.LayoutParams(0, -1, 0.53f))
            pad.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            pad.addView(buildGaugeCard(210), LinearLayout.LayoutParams(-1, dp(260)))
            pad.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
            pad.addView(buildRightColumn(), LinearLayout.LayoutParams(-1, -2))
        }
        setupError?.let {
            pad.addView(label(it, 12, WARNING).apply { setPadding(0, dp(10), 0, 0) })
        }
    }

    /**
     * The instrument: five segments, one per link step, with the state in the middle and the three radios
     * along the bottom edge. Static drawing - [GaugeView] only invalidates when [gaugeFilled] changes.
     */
    private fun buildGaugeCard(gaugeSizeDp: Int): View {
        val card = FrameLayout(this).apply { background = roundedR(SURFACE, 18, BORDER) }
        val gauge = GaugeView(this).apply {
            accent = ACCENT
            ring = BORDER
            bezel = DIVIDER
            filled = gaugeFilled()
        }
        card.addView(
            gauge,
            FrameLayout.LayoutParams(dp(gaugeSizeDp), dp(gaugeSizeDp), Gravity.CENTER),
        )

        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        centre.addView(
            GlyphView(this, if (connectionMode == 0) Glyph.WIFI else Glyph.USB).apply { color = ACCENT },
            LinearLayout.LayoutParams(dp(42), dp(42)),
        )
        gaugeTitle = label(headline(), 25, TEXT, true).apply {
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        }
        centre.addView(gaugeTitle)
        gaugeSubtitle = label(subline(), 12, MUTED).apply {
            setPadding(0, dp(3), 0, 0)
            gravity = Gravity.CENTER
        }
        centre.addView(gaugeSubtitle)
        centre.addView(buildModePill(), LinearLayout.LayoutParams(-2, dp(30)).apply { topMargin = dp(12) })
        card.addView(centre, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        gaugeView = gauge
        return card
    }

    private fun buildModePill(): View {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(16), 0)
            background = roundedR(ACCENT_SOFT, 15)
        }
        pill.addView(
            View(this).apply { background = circle(ACCENT) },
            LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(7) },
        )
        gaugePill = label(pillText(), 11, ACCENT, true)
        pill.addView(gaugePill)
        return pill
    }

    private fun buildRightColumn(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // The action and radio cards hug their content with the same 14 dp inset; the summary card below
        // takes the rest of the height, so every gap between cards stays at 12 dp and the card bottoms line
        // up with the gauge card.
        column.addView(buildActionCard(), LinearLayout.LayoutParams(-1, -2))
        column.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        column.addView(buildInfoCard(), LinearLayout.LayoutParams(-1, -2))
        column.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        column.addView(buildDeviceCard(), LinearLayout.LayoutParams(-1, 0, 1f))
        return column
    }

    private fun buildActionCard(): View {
        val card = cardView(14).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val wireless = connectionMode == 0

        // Text on the left, actions on the right: the card is only as tall as one row of content.
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(if (wireless) "无线 CarPlay" else "USB CarPlay", 10, MUTED, true))
        val outcome = lastAttemptOutcome()
        text.addView(label(actionTitleText(wireless, outcome), 20, TEXT, true).apply {
            setPadding(0, dp(5), 0, 0)
            actionTitle = this
        })
        text.addView(label(actionHintText(wireless, outcome), 12, MUTED).apply {
            setPadding(0, dp(3), 0, 0)
            actionHint = this
        })
        card.addView(text, LinearLayout.LayoutParams(0, -2, 1f))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val primary = SettingsWidgets.createButton(
            context = this,
            title = if (wireless) "开始无线连接" else "开始 USB 连接",
            style = SettingsWidgets.ButtonStyle.PRIMARY,
            theme = nightPalette,
            minHeightDp = 52,
            textSizeSp = 15f,
        ) {
            if (CarPlayBackgroundSession.hasSession()) openProjection() else connect(wireless)
        }
        connectButton = primary
        // The slot beside the primary only carries the running session's stop action. Picking the phone used
        // to sit here too, but it duplicated what the connect flow already does: starting a connection with
        // no phone selected opens the picker by itself (owner report, 2026-10-29).
        val running = CarPlayBackgroundSession.hasSession()
        if (running) {
            val secondary = SettingsWidgets.createButton(
                context = this,
                title = "断开连接",
                style = SettingsWidgets.ButtonStyle.SECONDARY,
                theme = nightPalette,
                minHeightDp = 52,
                textSizeSp = 13f,
            ) {
                CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
            }.also { disconnectButton = it }
            buttons.addView(primary, LinearLayout.LayoutParams(dp(160), dp(52)))
            buttons.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
            buttons.addView(secondary, LinearLayout.LayoutParams(dp(136), dp(52)))
        } else {
            buttons.addView(primary, LinearLayout.LayoutParams(dp(170), dp(52)))
        }
        // In both layouts: a session can end while this page stays open, and the card is not rebuilt then.
        // The once-a-second refresh toggles its visibility (refreshActionCardText).
        val details = SettingsWidgets.createButton(
            context = this,
            title = getString(R.string.view_failure_detail),
            style = SettingsWidgets.ButtonStyle.SECONDARY,
            theme = nightPalette,
            minHeightDp = 52,
            textSizeSp = 13f,
        ) { showFailureDetail() }.apply {
            visibility = if (lastAttemptOutcome() != null) View.VISIBLE else View.GONE
        }
        failureDetailButton = details
        buttons.addView(details, LinearLayout.LayoutParams(dp(136), dp(52)).apply { leftMargin = dp(10) })
        card.addView(buttons, LinearLayout.LayoutParams(-2, -2))
        return card
    }

    private fun buildInfoCard(): View {
        val card = cardView(14)
        if (connectionMode == 0) {
            val hotspot = runCatching { com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) }
                .getOrNull() == true
            // The live AP name first (`getWifiApConfiguration` through the legacy bridge), then the SSID the
            // owner saved for the manual flow, then the default this build uses.
            val liveSsid = runCatching {
                com.shilapi.xcertplay.network.LegacySoftApControl.readStoredConfiguration(this)?.ssid
            }.getOrNull()?.takeIf { it.isNotBlank() }
            val ssid = liveSsid
                ?: AirPlayPersistence.loadManualHotspotSsid(this)?.takeIf { it.isNotBlank() }
                ?: "DiPlay-CS11"
            val clients = if (hotspot) hotspotClientCount() else 0
            val bt = bluetoothSummary()

            // Two blocks side by side: the wireless hand-off needs both radios, and each shows the same four
            // facts in the same order - state, local name, count, action - so the two columns scan alike.
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                infoBlock(
                    "车机热点",
                    if (hotspot) "已开启" else "未开启",
                    hotspot,
                    ssid,
                    when {
                        !hotspot -> "热点未开启，无连接"
                        clients == null -> "连接数暂不可读"
                        clients <= 0 -> "暂无设备连接"
                        else -> "已连接 $clients 台"
                    },
                    "打开车机热点设置",
                ) { openCarWifiSettings() },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            row.addView(
                View(this).apply { setBackgroundColor(DIVIDER) },
                LinearLayout.LayoutParams(dp(1), -1).apply { leftMargin = dp(12); rightMargin = dp(12) },
            )
            row.addView(
                infoBlock(
                    "蓝牙",
                    when {
                        !bt.available -> "不可用"
                        bt.on -> "已开启"
                        else -> "未开启"
                    },
                    bt.on,
                    if (bt.available) bt.name else "系统未提供蓝牙适配器",
                    when {
                        !bt.available -> ""
                        !bt.on -> "蓝牙未开启，无配对"
                        bt.paired <= 0 -> "已配对 0 台"
                        else -> "已配对 ${bt.paired} 台"
                    },
                    "打开蓝牙设置",
                ) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
        } else {
            card.addView(label("USB", 10, MUTED, true))
            card.addView(label("未插入", 17, TEXT, true).apply { setPadding(0, dp(3), 0, dp(2)) })
            card.addView(label("插入后会自动尝试连接。", 11, MUTED))
            card.addView(linkLabel("USB 权限说明") { wirelessHelp() }.apply { setPadding(0, dp(10), 0, 0) })
        }
        return card
    }

    /** What the Bluetooth radio is doing right now, best effort: the adapter can be absent on a device. */
    private data class BluetoothSummary(val available: Boolean, val on: Boolean, val name: String, val paired: Int)

    private fun bluetoothSummary(): BluetoothSummary {
        val adapter = runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter() }.getOrNull()
            ?: return BluetoothSummary(false, false, "未检测到适配器", 0)
        val name = runCatching { adapter.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "未命名"
        val paired = runCatching { adapter.bondedDevices?.size ?: 0 }.getOrDefault(0)
        return BluetoothSummary(true, runCatching { adapter.isEnabled }.getOrDefault(false), name, paired)
    }

    /**
     * How many clients the car's access point currently has.
     *
     * API 19 has no public client-count API (that arrived with `SoftApInfo` in API 30), so this reads the
     * ARP table and counts resolved entries. It is a best-effort number for the driver: `null` means the
     * table was unreadable, `0` means it was readable and empty.
     */
    private fun hotspotClientCount(): Int? = runCatching {
        val lines = java.io.File("/proc/net/arp").readLines()
        if (lines.size <= 1) return@runCatching 0
        var count = 0
        for (line in lines.drop(1)) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) continue
            if (parts[2] == "0x0") continue
            if (parts[3].equals("00:00:00:00:00:00", ignoreCase = true)) continue
            count++
        }
        count
    }.getOrNull()

    /** One status block: micro-label, big value with a dot, two secondary lines, and a link at the bottom. */
    private fun infoBlock(
        title: String,
        value: String,
        live: Boolean,
        sub: String,
        sub2: String,
        link: String,
        click: () -> Unit,
    ): View {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        block.addView(label(title, 10, MUTED, true))
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(2))
        }
        line.addView(label(value, 17, TEXT, true))
        line.addView(
            View(this).apply { background = circle(if (live) ACCENT else MUTED) },
            LinearLayout.LayoutParams(dp(7), dp(7)).apply { leftMargin = dp(8) },
        )
        block.addView(line)
        block.addView(label(sub, 11, MUTED))
        block.addView(label(sub2, 11, MUTED).apply { setPadding(0, dp(2), 0, 0) })
        block.addView(linkLabel(link) { click() }.apply { setPadding(0, dp(8), 0, 0) })
        return block
    }

    /**
     * What connected last time, and the name the iPhone shows for this receiver.
     *
     * The rows share the card's height, so making the card taller to close the gap between cards also
     * spreads the rows instead of leaving a blank strip at the bottom.
     */
    private fun buildDeviceCard(): View {
        val card = cardView(14)
        val selected = DiPlayPreferences.phoneName(this)
        val hasSelection = DiPlayPreferences.phoneAddress(this) != null
        val lastPhone = DiPlayPreferences.lastPhoneName(this)
            ?: selected.takeIf { hasSelection }
            ?: "—"
        val transport = when (DiPlayPreferences.lastWireless(this)) {
            true -> "无线 CarPlay"
            false -> "USB 数据线"
            null -> "—"
        }
        card.addView(deviceRow("上次连接手机", lastPhone), LinearLayout.LayoutParams(-1, 0, 1f))
        deviceDivider(card)
        card.addView(deviceRow("上次连接方式", transport), LinearLayout.LayoutParams(-1, 0, 1f))
        deviceDivider(card)
        card.addView(deviceRow("CarPlay 名称", DiPlayBootstrap.CARPLAY_NAME), LinearLayout.LayoutParams(-1, 0, 1f))
        deviceDivider(card)
        card.addView(deviceRow("版本", version()), LinearLayout.LayoutParams(-1, 0, 1f))
        return card
    }

    private fun deviceDivider(card: LinearLayout) {
        card.addView(
            View(this).apply { setBackgroundColor(DIVIDER) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(4); bottomMargin = dp(4) },
        )
    }

    private fun deviceRow(name: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label(name, 11, MUTED), LinearLayout.LayoutParams(dp(84), -2))
        row.addView(
            label(value, 12, TEXT, true).apply {
                gravity = Gravity.END
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        return row
    }

    private fun linkLabel(text: String, click: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { click() }
        }
        row.addView(label(text, 11, ACCENT, true))
        row.addView(label("›", 15, ACCENT, true).apply { setPadding(dp(4), 0, 0, dp(1)) })
        return row
    }

    private fun cardView(paddingDp: Int): LinearLayout =
        SettingsWidgets.createCard(this, nightPalette, paddingDp)

    // ------------------------------------------------------------------ home state

    private fun isWirelessMode(): Boolean = connectionMode == 0

    private fun sessionActive(): Boolean = CarPlayBackgroundSession.active

    private fun gaugeFilled(): Int = when {
        sessionActive() -> 5
        CarPlayBackgroundSession.hasSession() -> 2
        else -> 0
    }

    private fun headline(): String = when {
        // First-frame watchdog: the handshake is done but no picture has been rendered yet.
        sessionActive() && CarPlayProgress.awaitingFirstFrame -> "等待画面"
        sessionActive() -> "已连接"
        CarPlayBackgroundSession.hasSession() -> "连接中"
        isWirelessMode() -> "待连接"
        else -> "等待数据线"
    }

    private fun subline(): String {
        if (sessionActive() || CarPlayBackgroundSession.hasSession()) {
            return DiPlayPreferences.phoneName(this) ?: ""
        }
        // Failure visibility: a failed or interrupted attempt says so instead of the idle wording.
        lastAttemptOutcome()?.let { return ConnectionAttemptRecord.title(it) }
        return if (isWirelessMode()) "未发现 iPhone" else "未检测到 iPhone"
    }

    /**
     * The last attempt's failure or interruption, recorded by the CarPlay host (ConnectionAttemptRecord).
     * Hidden while a session exists: the live stage line is the truth then. SharedPreferences are cached in
     * memory after the first read, so the once-a-second tick costs no disk access.
     */
    private fun lastAttemptOutcome(): ConnectionAttemptOutcome? =
        if (CarPlayBackgroundSession.hasSession()) null
        else runCatching { ConnectionAttemptRecord.outcome(this) }.getOrNull()

    private fun actionTitleText(wireless: Boolean, outcome: ConnectionAttemptOutcome?): String = when {
        outcome != null -> ConnectionAttemptRecord.title(outcome)
        wireless -> "等待连接"
        else -> "插上线就开始"
    }

    private fun actionHintText(wireless: Boolean, outcome: ConnectionAttemptOutcome?): String = when {
        outcome != null -> ConnectionAttemptRecord.detail(outcome)
        wireless -> "保持车机热点与蓝牙开启。"
        else -> "插到支持数据传输的 USB 接口。"
    }

    /** Keeps the action card in step with the record when a failed attempt ends while this page is open. */
    private fun refreshActionCardText() {
        val title = actionTitle ?: return
        val hint = actionHint ?: return
        val wireless = isWirelessMode()
        val outcome = lastAttemptOutcome()
        val titleText = if (outcome == null && CrashDiagnostics.hasCrash(this)) "检测到上次应用异常"
            else actionTitleText(wireless, outcome)
        val hintText = if (outcome == null && CrashDiagnostics.hasCrash(this)) "点开失败详情可查看 Java 异常与堆栈。"
            else actionHintText(wireless, outcome)
        if (title.text?.toString() != titleText) title.text = titleText
        if (hint.text?.toString() != hintText) hint.text = hintText
        val detailsVisibility = if (outcome != null || CrashDiagnostics.hasCrash(this)) View.VISIBLE else View.GONE
        failureDetailButton?.let { if (it.visibility != detailsVisibility) it.visibility = detailsVisibility }
    }

    /**
     * The failure-detail pages (FailureDetail): summary first, then every retained line from the latest
     * attempt. Load and paginate on worker threads; render only one screen-sized page at a time.
     */
    private fun showFailureDetail() {
        val appContext = applicationContext
        val appVersion = runCatching { version() }.getOrDefault("?")
        com.shilapi.xcertplay.compat.WorkerThreads.start("diplay-failure-detail") {
            val lines = runCatching { FailureDetail.collect(appContext, appVersion) }
                .getOrElse { listOf("读取日志失败：${it.javaClass.simpleName}") }
            runOnUiThread { if (!isFinishing) presentFailureDetail(lines) }
        }
    }

    private fun presentFailureDetail(lines: List<String>) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        // Black on white text, large monospace: the page is meant to be photographed from the driver's seat.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.failure_detail_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = TYPE_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val pageLabel = TextView(this).apply {
            setTextColor(Color.rgb(0xC8, 0xC8, 0xC8))
            textSize = 18f
        }
        header.addView(pageLabel)
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        val body = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.MONOSPACE
            // Android 4.4 CS11 crashes while inflating its text-selection popup.
            setTextIsSelectable(false)
        }
        // Normally one page fits the screen exactly; the scroll only matters for a single line taller than a page.
        val scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun footerButton(title: String) = Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 18f
        }
        val previous = footerButton(getString(R.string.failure_detail_previous))
        val next = footerButton(getString(R.string.failure_detail_next))
        val copy = footerButton(getString(R.string.failure_detail_copy_page))
        val close = footerButton(getString(R.string.close))
        footer.addView(previous, LinearLayout.LayoutParams(0, dp(56), 1f))
        footer.addView(next, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(8) })
        footer.addView(copy, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(8) })
        footer.addView(close, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(8) })
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(root)

        // Never put the unpaginated log into one TextView: API 19 head units can run out of memory.
        var pages: List<List<String>> = listOf(listOf("…"))
        var index = 0
        var pagesReady = false
        fun showPage() {
            body.text = pages[index].joinToString("\n")
            scroll.scrollTo(0, 0)
            pageLabel.text = getString(R.string.failure_detail_page, index + 1, pages.size)
            previous.isEnabled = index > 0
            next.isEnabled = index < pages.size - 1
            copy.isEnabled = pagesReady && pages[index].isNotEmpty()
        }
        previous.setOnClickListener { if (index > 0) { index--; showPage() } }
        next.setOnClickListener { if (index < pages.size - 1) { index++; showPage() } }
        copy.setOnClickListener {
            if (!pagesReady) return@setOnClickListener
            // Copy one page at a time to avoid oversized clipboard IPC on Android 4.4.
            runCatching {
                systemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "SBplay failure details page ${index + 1}",
                        pages[index].joinToString("\n"),
                    ),
                )
            }.onSuccess {
                toast(getString(R.string.failure_detail_copied_page))
            }.onFailure {
                toast(getString(R.string.failure_detail_copy_failed))
            }
        }
        close.setOnClickListener { dialog.dismiss() }
        showPage()
        dialog.show()
        // Measure layout on the UI thread; paginate in the background so long logs do not freeze the car.
        scroll.post {
            val width = (body.width - body.paddingLeft - body.paddingRight).toFloat()
            val lineHeight = body.lineHeight
            val viewportHeight = scroll.height
            val paint = android.text.TextPaint(body.paint)
            com.shilapi.xcertplay.compat.WorkerThreads.start("diplay-failure-pages") {
                val computed = if (width > 0f && lineHeight > 0 && viewportHeight > 0) {
                    val rowsPerPage = (viewportHeight / lineHeight).coerceAtLeast(1)
                    // Leave a little slack for wrapping; only the selected page becomes TextView text.
                    val usable = width * 0.95f
                    FailureDetail.paginate(lines, rowsPerPage) { line ->
                        kotlin.math.ceil(paint.measureText(line) / usable).toInt().coerceAtLeast(1)
                    }
                } else {
                    // Layout measurement unavailable: still paginate, never render the entire file at once.
                    FailureDetail.paginate(lines, rowsPerPage = 16) { 1 }
                }
                runOnUiThread {
                    if (dialog.isShowing && !isFinishing) {
                        pages = computed
                        index = 0
                        pagesReady = true
                        showPage()
                    }
                }
            }
        }
    }

    private fun pillText(): String = if (isWirelessMode()) "无线 · 5 GHz" else "USB · 未插入"

    private fun headerStatusTextValue(): String = when {
        sessionActive() && CarPlayProgress.awaitingFirstFrame -> "等待画面"
        sessionActive() -> "已连接"
        CarPlayBackgroundSession.hasSession() -> "连接中"
        else -> "未连接"
    }

    /**
     * Which settings category the left index has selected, and the section indices each category shows.
     *
     * The indices are the order `settings()` adds its sections in. Filtering by index - rather than gating
     * the calls - cannot silently drop a section: every section is still built, and the filter only decides
     * what is visible. Reordering the sections in `settings()` means updating these sets (docs/_digest/60).
     */
    private var settingsCategoryIndex = 0

    /**
     * An open detail inside the settings page, e.g. the connection setup. It renders into the content column
     * next to the category index instead of replacing the whole screen, so the index stays usable.
     */
    private var settingsDetail: String? = null

    private fun settingsCategoryLabels(): List<String> = listOf(
        getString(R.string.connection),
        getString(R.string.display_and_performance),
        getString(R.string.audio_routing),
        getString(R.string.carplay_controls),
        getString(R.string.diagnostics),
        getString(R.string.settings_category_other),
        getString(R.string.about),
    )

    private fun settingsCategorySections(): List<Set<Int>> = listOf(
        // connection: connection setup, automatic connection.
        setOf(1, 3),
        // display: display and performance, and the language picker.
        setOf(4, 10),
        // audio: audio routing.
        setOf(6),
        // controls: CarPlay controls (the gesture), and the car button inside CarPlay.
        setOf(0, 5),
        // diagnostics: diagnostics only.
        setOf(2),
        // other: location, and the app permission entry.
        setOf(7, 8),
        // about: the about entry with its exit action.
        setOf(9),
    )

    private fun applySettingsCategoryFilter(sections: LinearLayout) {
        val categories = settingsCategorySections()
        val visible = categories.getOrNull(settingsCategoryIndex) ?: return
        // A section no category names stays visible everywhere: a new section can never be lost by forgetting
        // to list it here, which is the failure mode an index-based filter would otherwise have.
        val named = categories.flatten().toSet()
        // Only the section cards are counted. The page's own title and subtitle are children of the same
        // column, and counting them shifted every category by two: the first screenshot of this screen showed
        // the title hidden and the connection category holding the language section. section() tags its card
        // with this literal, deliberately not a new companion-object constant - adding one to this class by
        // script broke the build once already.
        val cards = (0 until sections.childCount).filter {
            sections.getChildAt(it).tag == "diplay-settings-section"
        }
        cards.forEachIndexed { index, childIndex ->
            val shown = index in visible || index !in named
            sections.getChildAt(childIndex).visibility = if (shown) View.VISIBLE else View.GONE
        }
    }

    /**
     * The settings page as the brand lays one out: the categories as an index on the left with a 3 dp bar on
     * the selected entry, and the sections in one column on the right.
     *
     * The sections themselves are built by `settings()`, which is handed the right column as its container -
     * so no call site inside it had to change, and the filter runs after it returns.
     */
    /**
     * The settings page: a pill index on the left, the sections in their own column on the right.
     *
     * It owns the whole body now (it used to sit inside the page's ScrollView), which is what lets the index
     * stay put while the sections scroll and lets both columns fill the height the shell leaves them.
     */
    private fun settingsPage(host: ViewGroup) {
        val sections = column()
        // A category's detail is built into the same content column rather than taking over the screen: the
        // index stays visible, which is the point of a two-column settings page (docs/_digest/60).
        val detail = settingsDetail
        val fillSections = {
            if (detail == null) {
                settings(sections)
            } else {
                sections.addView(
                    button(getString(R.string.back), false) { settingsDetail = null; render() },
                    LinearLayout.LayoutParams(dp(160), dp(44)).apply { bottomMargin = dp(18) },
                )
                if (detail == "connection") connectionSetup(sections)
            }
        }

        // The page title lives above both columns, so the index and the first card start on the same line.
        // It used to sit inside the scrolling column, which pushed the cards down and left the index hanging
        // at the top of the page (owner report, 2026-10-29).
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = column().apply { setPadding(dp(24), dp(10), dp(24), dp(12)) }
        header.addView(label(getString(R.string.your_drive_your_way), 26, TEXT, true))
        header.addView(label(getString(R.string.apply_reconnects_carplay_for_size_resolution_music_buffer), 12, MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        if (isCompactLayout) {
            // A narrow screen has no room for an index beside the content, so the sections run full width.
            root.addView(
                scrollPage { content -> content.addView(sections); fillSections() },
                LinearLayout.LayoutParams(-1, 0, 1f),
            )
            host.addView(root, ViewGroup.LayoutParams(-1, -1))
            if (detail == null) host.post { applySettingsCategoryFilter(sections) }
            return
        }
        val nav = column().apply { setPadding(dp(16), dp(8), dp(8), dp(10)) }
        settingsCategoryLabels().forEachIndexed { index, title ->
            val selected = index == settingsCategoryIndex
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                background = if (selected) roundedR(ACCENT_SOFT, 12) else null
            }
            item.addView(
                label(title, 14, if (selected) ACCENT else MUTED, selected),
                LinearLayout.LayoutParams(0, dp(40), 1f),
            )
            item.setOnClickListener {
                if (settingsCategoryIndex != index || settingsDetail != null) {
                    settingsCategoryIndex = index
                    // Picking a category closes any open detail, otherwise the click looks like it did nothing.
                    settingsDetail = null
                    render()
                }
            }
            nav.addView(item, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(3) })
        }
        val sectionScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(4), dp(8), dp(14), dp(8))
            addView(sections, LinearLayout.LayoutParams(-1, -2))
        }
        val page = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        page.addView(nav, LinearLayout.LayoutParams(dp(236), -1))
        page.addView(sectionScroll, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
        host.addView(root, ViewGroup.LayoutParams(-1, -1))
        fillSections()
        // After the sections are attached, so the filter sees every child whatever the category order is.
        if (detail == null) sectionScroll.post { applySettingsCategoryFilter(sections) }
    }

    private fun settings(content: LinearLayout) {
        section(content, getString(R.string.carplay_controls), R.drawable.ic_dp_controls) { card ->
            val gestureFingers = listOf(2, 3, 4)
            choice(card, getString(R.string.settings_gesture_fingers_label),
                gestureFingers.map { getString(R.string.settings_gesture_fingers_option, it) },
                gestureFingers.indexOf(AirPlayPersistence.loadSettingsGestureFingers(this)).coerceAtLeast(0),
                reconnects = false) {
                AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingers[it])
            }
            card.addView(label(getString(R.string.settings_gesture_fingers_hint), 14, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        section(content, getString(R.string.connection_setup), R.drawable.ic_dp_connection) { card ->
            // The setup steps used to live behind "打开连接设置" on a detail page. They are the first thing a
            // new driver needs, so they sit in the category itself now; one section, so the category filter's
            // indices are unchanged.
            connectionSetupControls(card)
        }
        section(content, getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
            // The quick look first: no file to save, one photo per page.
            card.addView(button(getString(R.string.view_recent_errors), false) { showFailureDetail() }, matchButton(10, 60))
            exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false) {
                chooseReportDestination()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            card.addView(button(getString(R.string.choose_save_location), false) { chooseReportDestination() }, matchButton(10, 60))
            val destination = getString(R.string.choose_where_to_save_your_report)
            card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
        section(content, getString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
            toggle(card, getString(R.string.connect_when_diplay_opens), getString(R.string.use_your_last_connection_type_and_selected_iphone), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            // Distinct from the switch above: that one decides whether to dial out, this one decides where
            // the app lands when a session is *already* running. Defaults on, which is the behaviour the
            // driver asked for (docs/_digest/84).
            toggle(
                card,
                getString(R.string.resume_carplay_on_open),
                getString(R.string.resume_carplay_on_open_description),
                DiPlayPreferences.resumeCarPlayOnOpen(this),
            ) { DiPlayPreferences.saveResumeCarPlayOnOpen(this, it) }
            toggle(card, getString(R.string.connect_when_iphone_bluetooth_connects),
                getString(R.string.connect_when_iphone_bluetooth_connects_description),
                DiPlayPreferences.connectOnPhoneBluetooth(this)) { enabled ->
                if (!enabled) {
                    DiPlayPreferences.saveConnectOnPhoneBluetooth(this, false)
                } else {
                    // CS11 (API 19) has no runtime Bluetooth permission, so enabling takes effect here.
                    DiPlayPreferences.saveConnectOnPhoneBluetooth(this, true)
                    render()
                }
            }
            adbToggle(card, R.string.open_after_the_car_starts,
                R.string.availability_depends_on_your_head_unit_s_startup_settings,
                read = { AirPlayPersistence.loadAutoStartOnBoot(this) },
                // API 19 grants the boot-launch permission at install time, so this preference
                // only needs ADB when the permission is actually missing.
                needsAdb = { CarHotspotSettings.enabled(this) &&
                    AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
                    !CarHotspotSetup.Permission.BOOT_LAUNCH.granted(this) },
                permissions = { listOf(CarHotspotSetup.Permission.BOOT_LAUNCH) }) {
                AirPlayPersistence.saveAutoStartOnBoot(this, it)
            }
            // Moved out of the deleted CarPlay panel: the on-screen debug log switch belongs with the other
            // start-up rows in the connection category.
            toggle(
                card,
                getString(R.string.debug_logs),
                getString(R.string.show_on_screen_debug_logs),
                AirPlayPersistence.loadDebugLogsEnabled(this),
            ) {
                AirPlayPersistence.saveDebugLogsEnabled(this, it)
                com.shilapi.xcertplay.diag.DiagLog.update(it)
            }
            val autoConfirmActive = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
            toggle(
                card,
                getString(R.string.usb_auto_confirm_title),
                getString(R.string.usb_auto_confirm_subtitle),
                autoConfirmActive,
            ) { enabled ->
                if (enabled) promptEnableUsbAutoConfirm()
                else {
                    if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.settings_screen_unavailable))
                    render()
                }
            }
            if (!autoConfirmActive) {
                card.addView(
                    // Secondary, not primary: this is one permission step inside a card, not the page's main
                    // action. It used to pass `true` here and `false` at the identical button in the
                    // dashboard section, so the same action was a white block on one screen and a dark one on
                    // the other (docs/_digest/84).
                    button(
                        getString(R.string.btn_auto_apply_permissions),
                        false,
                    ) {
                        autoApplyPermissions()
                    },
                    matchButton(8, 54),
                )
            } else {
                card.addView(
                    label(getString(R.string.usb_auto_confirm_active_hint), 14, SUCCESS).apply {
                        setPadding(0, dp(4), 0, dp(8))
                    },
                )
            }
        }
        section(content, getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
            // 跟随光感 is offered only where the hardware exists.
            //
            // The CS11 has no ambient light sensor at all: `SensorManager` lists accelerometer, gyroscope,
            // rotation vector and the AOSP composites but no `TYPE_LIGHT`, and the sysfs path named by
            // `ro.hardware.lightsensor` (/sys/class/i2c-dev/i2c-1/device/1-0044/) does not exist on this
            // firmware — it is a leftover from a reference design. Offering the option there would be a
            // control that can never do anything, which is exactly what the owner hit (2026-10-18).
            // All four modes stay listed: the car may well switch its own theme from a CAN illumination
            // signal (the owner reports the built-in and third-party navigation going dark in tunnels), it
            // is just not reaching Android as either a `TYPE_LIGHT` sensor or a `uiMode` night flag on this
            // firmware. Hiding the automatic modes would be wrong; the note below says what to do instead
            // until the real channel is found.
            val ambientSupported = ambientSensorAvailable
            val nightModes = CarPlayNightMode.entries.toList()
            val nightMode = AirPlayPersistence.loadCarPlayNightMode(this)
            // Follows the chosen mode, not the hardware: the threshold and delay are still the settings a
            // reader would configure for 跟随光感, and hiding them would make the option look broken.
            val ambientControls = column().apply {
                visibility = if (nightMode == CarPlayNightMode.AMBIENT) View.VISIBLE else View.GONE
            }
            choice(
                card,
                getString(R.string.carplay_night_mode),
                nightModes.map { getString(nightModeLabel(it)) },
                nightModes.indexOf(nightMode).coerceAtLeast(0),
                reconnects = false,
            ) { index ->
                AirPlayPersistence.saveCarPlayNightMode(this, nightModes[index])
                // Re-render at once: without this the page kept its old palette until some other action
                // happened to rebuild it, so picking 固定夜间 looked like it did nothing (owner report).
                render()
            }
            if (!ambientSupported) {
                card.addView(label(getString(R.string.carplay_night_auto_unavailable), 14, MUTED).apply {
                    setPadding(0, 0, 0, dp(18))
                })
            }
            card.addView(label(getString(R.string.carplay_night_hint), 14, MUTED))
            card.addView(label(getString(R.string.carplay_night_time_note), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientControls.addView(label(getString(R.string.carplay_night_ambient_hint), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientLightThresholdControl(ambientControls)
            nightDelaySettingControl(ambientControls, R.string.ambient_delay_title, R.string.ambient_delay_hint,
                0..60, 2, R.string.ambient_delay_summary, { AirPlayPersistence.loadAmbientDelaySeconds(this) },
                save = { AirPlayPersistence.saveAmbientDelaySeconds(this, it) })
            card.addView(ambientControls)
            card.addView(button(getString(R.string.picture_adjustments), false) {
                startActivity(Intent(this, CarPlayHostActivity::class.java)
                    .putExtra("picture_controls", true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, matchButton(0, 56).apply { bottomMargin = dp(24) })
            carPlaySizeControl(card)
            resolutionSettingControl(
                card, R.string.resolution, R.string.custom_resolution_hint,
                CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT, 100,
                R.string.custom_resolution_summary,
                { AirPlayPersistence.loadDisplayScalePercent(this) }, reconnects = true,
                save = { AirPlayPersistence.saveDisplayScalePercent(this, it) },
            )
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            toggle(card, getString(R.string.main_buffered_audio), getString(R.string.main_buffered_audio_description),
                AirPlayPersistence.loadMainBufferedAudio(this)) {
                AirPlayPersistence.saveMainBufferedAudio(this, it)
                reconnectForAppliedSetting()
            }
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            // The same switch the in-CarPlay three-finger settings carry: SurfaceView instead of TextureView.
            // It belongs here too, next to the other picture settings, because it is a rendering choice.
            toggle(
                card,
                getString(R.string.experimental_surface_view),
                getString(R.string.experimental_surface_view_description),
                AirPlayPersistence.loadSurfaceViewExperimental(this),
            ) { AirPlayPersistence.saveSurfaceViewExperimental(this, it) }
            toggle(card, getString(R.string.right_hand_drive), getString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            carPlayDockControl(card)
            addSystemBarControls(
                hideTopBar = AirPlayPersistence.loadHideTopBar(this),
                hideBottomBar = AirPlayPersistence.loadHideBottomBar(this),
                onHideTopBarChanged = { AirPlayPersistence.saveHideTopBar(this, it) },
                onHideBottomBarChanged = { AirPlayPersistence.saveHideBottomBar(this, it) },
            ) { label, checked, onChanged ->
                toggle(card, getString(label), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), checked, save = onChanged)
            }
            // Moved out of the deleted CarPlay panel: the dashboard push and the picture's corner shape are
            // display decisions, so they belong beside the other display rows.
            toggle(
                card,
                getString(R.string.cluster_push),
                getString(R.string.cluster_push_description),
                AirPlayPersistence.loadClusterBridgeEnabled(this),
            ) { AirPlayPersistence.saveClusterBridgeEnabled(this, it) }
            toggle(
                card,
                getString(R.string.screen_corner_mask),
                getString(R.string.screen_corner_mask_description),
                AirPlayPersistence.loadScreenCornerMask(this),
            ) { AirPlayPersistence.saveScreenCornerMask(this, it) }
        }
        section(content, getString(R.string.car_button_in_carplay), R.drawable.ic_dp_car) { card -> carButtonCard = card; carButtonControls(card) }
        section(content, getString(R.string.audio_routing), R.drawable.ic_dp_audio) { card ->
            toggle(card, getString(R.string.contrib_audio_home_toggle_audio_focus), getString(R.string.contrib_audio_home_toggle_audio_focus_desc), AirPlayPersistence.loadAudioFocusEnabled(this)) { AirPlayPersistence.saveAudioFocusEnabled(this, it) }
            if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                toggle(card, getString(R.string.advanced_audio_channel_mapping),
                    getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                }
            }
            mediaChannelControl(card)
            navigationChannelControl(card)
            callChannelControl(card)
            audioDecoderControl(card)
            // The head unit can keep the microphone for its own voice assistant, and CarPlay voice
            // input then fails silently; the hint below says so. The live AVAILABLE/BLOCKED badge is not
            // probed from this screen any more:
            //
            // on this ROM (CS11, Android 4.4 / Dalvik) constructing a Thread from inside this settings
            // lambda dies in `java.lang.Thread.create` with a NullPointerException and takes the whole
            // settings screen with it — measured on the car 2026-10-18 with all three forms
            // (`Thread(r, name)`, `Thread(r)` and `HandlerThread(name)`), so it is not the naming. The
            // microphone check still reaches the diagnostic report. Losing the live badge is cheaper than
            // losing the settings screen.
            card.addView(label(getString(R.string.microphone_status_hint), 13, MUTED))
        }
        section(content, getString(R.string.location), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.report_location_to_iphone),
                getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                AirPlayPersistence.loadLocationReportingEnabled(this), save = ::onLocationReportingChanged)
            card.addView(label(getString(R.string.location_reporting_reconnects), 14, MUTED))
        }
        // The permission and help sheet is gone from this section: its Bluetooth-settings and connection-help
        // buttons repeated what the home page and the wireless page already offer. Only the app's own
        // permission screen is left, and it belongs with the other miscellaneous entries below.
        section(content, getString(R.string.app_permissions), R.drawable.ic_dp_permissions) { card ->
            card.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(0, 60))
        }
        section(content, getString(R.string.about), R.drawable.ic_dp_about) { card ->
            card.addView(button(getString(R.string.about_diplay), false) { page = "about"; render() }, matchButton(0, 60))
            // An explicit way out, here rather than on the separate about page: this row is the one the
            // driver reaches through 设置 → 关于 (owner correction, 2026-10-18). Without it the only way to
            // stop DiPlay is the car's task killer, and a background CarPlay session keeps the hotspot and
            // the decoder alive after the driver has finished with it.
            card.addView(label(getString(R.string.exit_application_description), 15, MUTED).apply {
                setPadding(0, dp(16), 0, dp(10))
            })
            card.addView(button(getString(R.string.exit_application), primary = false) { exitDiPlay() }, matchButton(0, 60))
        }
        languageSettings(content)
    }

    private fun about(content: LinearLayout) {
        content.addView(label(getString(R.string.diplay), 40, TEXT, true))
        content.addView(label(getString(R.string.carplay_at_home_in_your_car), 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "${getString(R.string.about_public_preview_prefix)}${version()}") { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT))
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED))
        }    }

    /**
     * Ends DiPlay for real.
     *
     * `finish()` alone only closes the settings page: the wireless session, the hotspot and the codec
     * threads all live in this process, so the user would still be connected with no window to see. Removing
     * the task and then ending the process is what "exit the application" has to mean here, and it is the
     * same thing the in-CarPlay exit does (`CarPlayHostActivity.exitApplication`).
     */
    private fun exitDiPlay() {
        Log.i("DiPlay-Exit", "exit requested from the about page")
        // `finishAffinity` is API 16, so it is safe on the API 19 head unit; the API 21+ task removal that
        // CarPlayHostActivity uses is not needed here because the process is about to end anyway.
        finishAffinity()
        // Give the task a moment to leave the screen before the process goes, so the launcher is not left
        // drawing a window that has already died.
        handler.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, EXIT_KILL_DELAY_MILLIS)
    }

    // An opted-in connection prepares the hotspot in the controller instead of stopping at this reminder.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false &&
            !(CarHotspotSettings.enabled(this) && CarHotspotTethering.permitted(this))

    // CS11/4.4: the firmware keeps a usable hotspot configuration which the controller adopts for the
    // connection itself, so MANUAL setup never has to block the user on credentials typed by hand.
    private fun carHotspotConfiguration(): com.shilapi.xcertplay.network.LegacySoftApControl.UsableApConfiguration? =
        if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL) {
            com.shilapi.xcertplay.network.LegacySoftApControl.readUsableStored(this)
        } else {
            null
        }

    private fun carHotspotSelfConfigured(): Boolean = carHotspotConfiguration() != null


    private fun nightModeLabel(mode: CarPlayNightMode): Int = when (mode) {
        CarPlayNightMode.SYSTEM -> R.string.carplay_night_system
        CarPlayNightMode.AMBIENT -> R.string.carplay_night_ambient
        CarPlayNightMode.DAY -> R.string.carplay_night_day
        CarPlayNightMode.NIGHT -> R.string.carplay_night_night
    }


    /**
     * Opens every preference file this screen can reach while `onCreate` is still running.
     *
     * Measured on the car 2026-10-18 (CS11, Android 4.4.3 / API 19): the first `getSharedPreferences` call
     * for a file that is not loaded yet makes the framework's `SharedPreferencesImpl` start its own loader
     * thread, and on this ROM that thread construction fails —
     * `Caused by: java.lang.NullPointerException at java.lang.Thread.create(Thread.java:403)`
     * `at android.app.SharedPreferencesImpl.startLoadFromDisk(SharedPreferencesImpl.java:88)`. It is thrown
     * out of `onResume` (`startCarHotspotOnLaunch` -> `startupHotspotEligible`) and takes DiPlay with it.
     *
     * Creating the same threads during `onCreate` succeeds on this ROM (a probe of all ten construction
     * forms reported OK there), so warming the files up front keeps the later, fatal path on the cache.
     * This is a workaround for a firmware defect, not a design choice: see `docs/_digest/89`.
     */
    private fun warmUpPreferences() {
        for (name in PREFERENCE_FILES) {
            runCatching { getSharedPreferences(name, MODE_PRIVATE) }
                .onFailure { Log.w("DiPlay-Prefs", "warm up $name failed", it) }
        }
    }



    // The "自动开启热点" section used to be injected here from a background ADB check and, because it was
    // untagged, it showed up under every settings category. It is gone: the car's own hotspot switch is the
    // one place to turn the AP on, and the home page still offers a direct link to it.


    private fun adbToggle(parent: LinearLayout, title: Int, description: Int, read: () -> Boolean,
        needsAdb: () -> Boolean = { true },
        permissions: () -> List<CarHotspotSetup.Permission> = { emptyList() }, save: (Boolean) -> Unit) {
        val control = toggle(parent, getString(title), getString(description), read(),
            enabled = !adbSwitchChangePending) { enabled ->
            if (updatingAdbSwitches || adbSwitchChangePending) return@toggle
            if (enabled && needsAdb()) requestAdbSwitchChange(permissions()) { save(true) }
            else save(enabled)
        }
        adbSwitches[title] = control to read
    }

    private fun requestAdbSwitchChange(permissions: List<CarHotspotSetup.Permission>, save: () -> Unit) {
        if (adbSwitchChangePending) {
            updateAdbSwitches()
            return
        }
        val app = applicationContext
        adbSwitchChangePending = true
        updateAdbSwitches()
        adbStatus?.setText(R.string.adb_checking_may_ask)
        if (page == "settings") render()
        com.shilapi.xcertplay.compat.WorkerThreads.start("diplay-adb-grant") {
            var access = LocalAdb.Access.UNREACHABLE
            val ready = runCatching {
                access = CarHotspotSetup.grant(app, permissions)
                access == LocalAdb.Access.READY && permissions.all { it.granted(app) }
            }.getOrDefault(false)
            runOnUiThread {
                adbSwitchChangePending = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = if (ready) {
                    if (permissions.isEmpty()) R.string.adb_access_ready else R.string.hotspot_permission_granted
                } else when (access) {
                    LocalAdb.Access.NOT_APPROVED -> R.string.adb_not_approved
                    LocalAdb.Access.UNREACHABLE -> R.string.adb_off
                    LocalAdb.Access.UNSUPPORTED -> R.string.adb_pairing_only
                    else -> R.string.hotspot_permission_failed
                }
                adbStatus?.setText(if (ready) R.string.adb_access_ready else message)
                if (ready) save()
                updateAdbSwitches()
                toast(getString(message))
            }
        }
    }

    private fun updateAdbSwitches() {
        updatingAdbSwitches = true
        for ((control, read) in adbSwitches.values) {
            control.isChecked = read()
            control.isEnabled = !adbSwitchChangePending
        }
        updatingAdbSwitches = false
    }

    private fun startCarHotspotOnLaunch() {
        if (!startupHotspotEligible()) return
        val app = applicationContext
        com.shilapi.xcertplay.compat.WorkerThreads.start("diplay-hotspot-startup") {
            val result = CarHotspotTethering.enable(app,
                isCancelled = { startupHotspotCancelled || !startupHotspotEligible() },
                log = { Log.i("DiPlay-Hotspot", it) },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed || result == CarHotspotTethering.Result.CANCELLED) return@runOnUiThread
                hotspotStartupResult = result
                if (page == "home") render()
            }
        }
    }

    private fun startupHotspotEligible(): Boolean =
        CarHotspotSetup.shouldStartOnLaunch(applicationContext, CarPlayBackgroundSession.hasSession(),
            allowCredentiallessStart = com.shilapi.xcertplay.network.LegacySoftApControl.supported())

    private fun hotspotResultText(result: CarHotspotTethering.Result): String = getString(when (result) {
        CarHotspotTethering.Result.READY -> R.string.hotspot_control_on
        CarHotspotTethering.Result.PERMISSION_REQUIRED -> R.string.hotspot_control_missing
        CarHotspotTethering.Result.UNSUPPORTED -> R.string.hotspot_control_unsupported
        CarHotspotTethering.Result.TIMED_OUT -> R.string.hotspot_control_timeout
        else -> R.string.hotspot_control_failed
    })

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ -> connect(true) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    // Some firmware shows the tether screen as a dialog and closes it unless the car home screen is on
    // top, so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        if (packageManager.resolveActivity(hotspot, 0) == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        openSystem(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    /**
     * The three setup steps as one card, shown directly under the 连接 category.
     *
     * Same content the old "打开连接设置" detail page carried: pick the wireless link, pair the iPhone, connect
     * - plus the cable alternative. Steps are separated by a hairline and a 13 sp label instead of taking a
     * whole section each, which keeps the category filter's section indices unchanged.
     */
    /**
     * The 连接 category's card: which wireless link the car uses, and how to turn its hotspot on.
     *
     * Pairing the iPhone and starting the connection used to be two more steps here (with their own
     * buttons). They are gone: connecting is the home page's job, and the remaining choice is the link.
     */
    private fun connectionSetupControls(card: LinearLayout) {
        card.addView(label(getString(R.string.s_1_choose_your_connection), 13, TEXT, true))
        wirelessLinkControls(card)
    }

    private fun stepDivider(card: LinearLayout) {
        card.addView(
            View(this).apply { setBackgroundColor(DIVIDER) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) },
        )
    }

    private fun connectionSetup(content: LinearLayout) {
        content.addView(label(getString(R.string.connection_setup), 34, TEXT, true))
        content.addView(label(getString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.s_1_choose_your_connection)) { card -> wirelessLinkControls(card) }
        section(content, getString(R.string.s_2_pair_your_iphone)) { card ->
            card.addView(label(getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car), 16, MUTED))
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
            card.addView(button(getString(R.string.review_app_permissions), false) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchButton(12, 60))
        }
        section(content, getString(R.string.s_3_connect)) { card ->
            card.addView(label(getString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the), 16, MUTED))
            card.addView(button(getString(R.string.connect_phone), true) { connect(true) }, matchButton(12, 60))
        }
        section(content, getString(R.string.prefer_a_cable)) { card ->
            card.addView(label(getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo), 16, MUTED))
            card.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(12, 60))
        }
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = AirPlayPersistence.loadWirelessHotspotMode(this)
        // API 19 has no "attach to an existing Wi-Fi" backend, and Wi-Fi Direct has a legacy path.
        val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P)
        val titles = listOf(getString(R.string.built_in_car_hotspot), getString(R.string.wifi_direct))
        val descriptions = listOf(
            getString(R.string.hotspot_mode_manual_desc),
            getString(R.string.hotspot_mode_p2p_desc)
        )
        val wide = !isCompactLayout && resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        modes.forEachIndexed { index, candidate ->
            val option = column()
            choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2))
            option.addView(button("${if (mode == candidate) "✓  " else ""}${titles[index]}", mode == candidate) {
                // Manual mode needs nothing typed any more: the SSID and passphrase are read out of the
                // firmware's AP configuration. The only other mode with input is existing-Wi-Fi, whose
                // credentials belong to a router DiPlay has no way to discover.
                if (candidate == WirelessHotspotMode.EXISTING_WIFI) {
                    askHotspotCredentials(existingWifi = true) { ssid, password ->
                        AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                        applyWirelessLink(candidate)
                    }
                } else {
                    applyWirelessLink(candidate)
                }
            }, matchButton(12, 60))
            option.addView(label(descriptions[index], 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
        }
        if (mode == WirelessHotspotMode.MANUAL) {
            val storedInCar = carHotspotConfiguration()
            parent.addView(label(getString(R.string.hotspot_setup), 22, TEXT, true))
            // The SSID and passphrase are read out of the firmware's own AP configuration and pushed to the
            // iPhone over Bluetooth, so step 2 of the old instructions ("copy the name and password in below")
            // no longer exists and the edit button that asked for them is gone (docs/_digest/84).
            parent.addView(
                label(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED)
                    .apply { setPadding(0, dp(8), 0, dp(12)) },
            )
            parent.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0, 60))
            parent.addView(
                label(
                    when {
                        storedInCar != null -> getString(R.string.hotspot_details_read_from_car, storedInCar.ssid)
                        carHotspotOff() -> getString(R.string.hotspot_details_off)
                        else -> getString(R.string.turn_on_the_car_hotspot_in_the_car_settings_then_connect_again)
                    },
                    15,
                    if (storedInCar != null) MUTED else WARNING,
                ).apply { setPadding(0, dp(12), 0, 0) },
            )
        } else if (mode == WirelessHotspotMode.EXISTING_WIFI) {
            parent.addView(label(getString(R.string.existing_wifi_instructions), 16, MUTED))
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
            parent.addView(button(getString(R.string.existing_wifi_details), false) {
                askHotspotCredentials(existingWifi = true) { ssid, password ->
                    AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                    toast(getString(R.string.saved_for_your_next_connection))
                }
            }, matchButton(12, 60))
        } else {
            parent.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            wifiDirectChannelControl(parent)
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
    }

    private fun wifiDirectChannelLabel(channel: Int): String = if (channel == WifiP2pChannels.AUTO) {
        getString(R.string.auto)
    } else {
        getString(R.string.wifi_direct_channel_choice, channel,
            getString(if (channel < 36) R.string.s_2_4_ghz else R.string.s_5_ghz))
    }

    private fun wifiDirectChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.wifi_direct_channel_summary, wifiDirectChannelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadWifiP2pPreferredChannel(this)), false) {}
        control.setOnClickListener {
            val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.channels
            val current = AirPlayPersistence.loadWifiP2pPreferredChannel(this)
            var selection = current
            AlertDialog.Builder(this).setTitle(R.string.wifi_direct_channel_title)
                .setSingleChoiceItems(choices.map(::wifiDirectChannelLabel).toTypedArray(),
                    choices.indexOf(current)) { _, which -> selection = choices[which] }
                .setPositiveButton(R.string.save) { _, _ ->
                    if (selection != current) {
                        AirPlayPersistence.saveWifiP2pPreferredChannel(this, selection)
                        control.text = summary(selection)
                        toast(getString(R.string.saved_for_your_next_connection))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        parent.addView(control, matchButton(12, 60))
        parent.addView(label(getString(R.string.wifi_direct_channel_description), 15, MUTED).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
    }

    private fun mediaChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            channelSummary(R.string.contrib_audio_home_media_channel_label, it, 3, "STREAM_MUSIC")
        }
        val control = button(summary(AirPlayPersistence.loadMediaAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadMediaAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_media_channel_label),
                current = current,
                preselected = if (current == 0) 3 else current,
                navigation = false,
                onApply = { value -> applyMediaChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(12, 60))
    }

    private fun navigationChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            channelSummary(R.string.contrib_audio_home_nav_channel_label, it, 12, "STREAM_NAVI_TTS")
        }
        val control = button(summary(AirPlayPersistence.loadNavigationAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadNavigationAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_nav_channel_label),
                current = current,
                preselected = if (current == 0) 12 else current,
                navigation = true,
                onApply = { value -> applyNavigationChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(10, 60))
    }

    /**
     * The stream CarPlay calls play on.
     *
     * 0 means "automatic", which on the CS11 resolves to `STREAM_VOICE_CALL`: that ROM gives stream 0 its
     * own call volume and arbitration, so a call is audible over music and the wheel's volume key shows the
     * call slider (docs/_digest/68 §5.4). Other values override it for experimentation.
     */
    private fun callChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            channelSummary(R.string.contrib_audio_home_call_channel_label, it, 0, "STREAM_VOICE_CALL")
        }
        val control = button(summary(AirPlayPersistence.loadCallAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadCallAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_call_channel_label),
                current = current,
                // Stream 0 is STREAM_VOICE_CALL itself, so "automatic" and the call stream are the same
                // number here; the dialog simply opens on it.
                preselected = current,
                navigation = false,
                onApply = { value -> applyCallChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(10, 60))
        parent.addView(label(getString(R.string.contrib_audio_home_call_channel_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    /**
     * The stream picker.
     *
     * [current] is the stored value (0 = automatic); [preselected] is what the list opens on, so the
     * automatic row opens on the stream it will really use instead of on "0". Saving without moving the
     * selection is a no-op, so opening and closing the dialog never rewrites the stored value.
     */
    private fun showChannelDialog(
        title: String,
        current: Int,
        preselected: Int,
        navigation: Boolean,
        onApply: (Int) -> Unit,
    ) {
        val preview = AudioChannelPreview { channel ->
            toast(getString(R.string.contrib_audio_home_channel_preview_unavailable, channel))
        }
        val channels = AirPlayPersistence.AUDIO_CHANNELS
        val labels = channels.map(Int::toString).toTypedArray()
        var selection = preselected.coerceIn(channels.first, channels.last)
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_and_reconnect) else getString(R.string.save)) { _, _ ->
                if (selection != current) onApply(selection)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { preview.close() }
            .show()
    }

    private fun applyMediaChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveMediaAudioChannel(this, value)
        control.text = summary(value)
        markNeedsReconnect()
    }

    private fun applyNavigationChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveNavigationAudioChannel(this, value)
        control.text = summary(value)
        markNeedsReconnect()
    }

    private fun applyCallChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveCallAudioChannel(this, value)
        control.text = summary(value)
        markNeedsReconnect()
    }

    private fun channelLabel(value: Int): String = value.toString()

    /**
     * The summary line for an audio channel control, with the automatic value spelled out.
     *
     * Stored 0 means "let the mapper decide". Printing that as "0" made all three rows look unconfigured
     * even though this head unit really uses 3 / 12 / 0. Showing the resolved stream next to its name
     * answers "what will it actually use" without changing what is stored or how the sink routes.
     */
    private fun channelSummary(labelRes: Int, stored: Int, defaultStream: Int, defaultName: String): String {
        val value = if (stored == 0) "$defaultStream（$defaultName）" else stored.toString()
        return getString(R.string.contrib_audio_home_choice_summary, getString(labelRes), value)
    }

    /**
     * 音频解码器选择（docs/_digest/79 A1 的设置项）。
     *
     * 平台码始终可选；Freescale 那项**先探测再显示**——厂商库不在这台车机上、或入口符号缺失时，
     * 选项显示为不可用并附上原因，而不是让用户选中一个注定失败的开关。
     * 判定依据是 `FreescaleAacBridge`：shim 加载 → `/system/lib` 的 `dlopen` → `aacd_query_dec_mem` 返回 0。
     */
    private fun audioDecoderControl(card: android.widget.LinearLayout) {
        val ready = com.shilapi.xcertplay.media.FreescaleAacBridge.isAvailable()
        val freescaleLabel = if (ready) {
            getString(R.string.audio_decoder_freescale)
        } else {
            getString(
                R.string.audio_decoder_freescale,
            ) + " · " + getString(
                R.string.audio_decoder_unavailable,
                com.shilapi.xcertplay.media.FreescaleAacBridge.reason(),
            )
        }
        // 顺序与 AudioDecoderChoice 的 ordinal 对齐（MEDIACODEC=0, FREESCALE=1）。
        val options = listOf(getString(R.string.audio_decoder_platform), freescaleLabel)
        val current = AirPlayPersistence.loadAudioDecoder(this).ordinal.coerceIn(0, options.lastIndex)
        choice(
            card,
            getString(R.string.audio_decoder),
            options,
            current,
            // 解码器在下次建流时才生效，所以要重连才有意义。
            reconnects = true,
            // 厂商解码器不具备条件时不让选，避免用户以为切换失败是 bug。
            enabled = ready || current == 0,
        ) { index ->
            AirPlayPersistence.saveAudioDecoder(
                this,
                com.shilapi.xcertplay.media.AudioDecoderChoice.entries[
                    index.coerceIn(0, com.shilapi.xcertplay.media.AudioDecoderChoice.entries.lastIndex)
                ],
            )
        }
        card.addView(label(getString(R.string.audio_decoder_description), 13, MUTED))
    }

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    /**
     * The same check against the owner's saved form, which is the fallback only.
     *
     * The primary source is the firmware's own AP configuration ([carHotspotConfiguration]); this exists so a
     * head unit whose AP configuration cannot be read still has the older typed-in path, and so a genuinely
     * broken saved pair is reported instead of failing later inside the controller.
     */
    private fun manualHotspotError(): String? = hotspotError(storedSsid(), storedPassword())

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(existingWifi: Boolean = false, done: (String, String) -> Unit) {

        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(if (existingWifi) R.string.existing_wifi_instructions else R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        // CS11/4.4: offer what the car itself stores, so the fields never open blank on that firmware.
        val storedInCar = if (existingWifi) null else com.shilapi.xcertplay.network.LegacySoftApControl.readUsableStored(this)
        val ssid = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_ssid else R.string.hotspot_name)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiSsid(this@DiPlayActivity)
                else storedSsid().ifEmpty { storedInCar?.ssid.orEmpty() })
            setSingleLine()
        }
        val password = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_password else R.string.hotspot_password)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiPassphrase(this@DiPlayActivity)
                else storedPassword().ifEmpty { storedInCar?.password.orEmpty() })
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fields.addView(ssid); fields.addView(password)
        fields.addView(CheckBox(this).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(getString(if (existingWifi) R.string.existing_wifi_details else R.string.car_hotspot_details))
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = if (existingWifi) ssid.text.toString() else ssid.text.toString().trim()

                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else { hideKeyboard(); dialog.dismiss(); done(name, secret) }
            }
        }
        dialog.show()
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.

    private fun promptEnableUsbAutoConfirm() {
        if (UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)) {
            toast(getString(R.string.usb_auto_confirm_status_on))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.usb_auto_confirm_title))
            .setMessage(getString(R.string.usb_auto_confirm_dialog_msg))
            .setPositiveButton(getString(R.string.btn_auto_apply_permissions)) { _, _ ->
                autoApplyPermissions()
            }
            .setNeutralButton(getString(R.string.btn_open_accessibility_setting)) { _, _ ->
                if (!UsbAutoConfirmService.openSettings(this)) {
                    toast(getString(R.string.settings_screen_unavailable))
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                render()
            }
            .setOnCancelListener {
                render()
            }
            .show()
    }

    private fun cancelUsbPermissionSetup() {
        val operation = usbPermissionOperation
        usbPermissionOperation = null
        operation?.cancel()
        val dialog = usbPermissionDialog
        usbPermissionDialog = null
        dialog?.dismiss()
    }

    private fun autoApplyPermissions() {
        if (usbPermissionOperation != null || isFinishing || isDestroyed) return
        val operation = usbPermissionOperationFactory(applicationContext)
        usbPermissionOperation = operation
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.auto_grant_title)
            .setMessage(R.string.auto_grant_msg)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelUsbPermissionSetup() }
            .create()
        usbPermissionDialog = progress
        progress.setOnCancelListener { cancelUsbPermissionSetup() }
        progress.setOnDismissListener {
            if (usbPermissionDialog === progress) cancelUsbPermissionSetup()
        }
        progress.show()
        com.shilapi.xcertplay.compat.WorkerThreads.start("diplay-auto-permission") {
            val result = operation.run()
            handler.post {
                if (usbPermissionOperation !== operation || operation.isCancelled || isFinishing || isDestroyed) {
                    return@post
                }
                usbPermissionOperation = null
                usbPermissionDialog = null
                progress.dismiss()
                render()
                if (result.complete) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.auto_grant_success_title)
                        .setMessage(R.string.auto_grant_success_msg)
                        .setPositiveButton(R.string.close, null)
                        .show()
                } else {
                    val reason = when (result.access) {
                        LocalAdb.Access.NOT_APPROVED -> getString(R.string.auto_grant_confirm_msg)
                        LocalAdb.Access.UNSUPPORTED -> getString(R.string.adb_pairing_only)
                        else -> getString(R.string.auto_grant_incomplete)
                    }
                    showManualPermissionDialog(reason)
                }
            }
        }
    }

    private fun showManualPermissionDialog(reason: String) {
        val body = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
        body.addView(label(reason, 15, MUTED).apply { setPadding(0, 0, 0, dp(12)) })

        val autoConfirmOn = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
        body.addView(button(if (autoConfirmOn) getString(R.string.usb_auto_confirm_status_on) else getString(R.string.btn_open_accessibility_setting), false) {
            if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.settings_screen_unavailable))
        }, matchButton(0, 56))

        UsbPermissionSetup.snapshot(this).forEach { (permission, granted) ->
            val title = getString(when (permission) {
                UsbPermissionSetup.Permission.ACCESSIBILITY -> R.string.usb_auto_confirm_title
                UsbPermissionSetup.Permission.OVERLAY -> R.string.btn_open_overlay_setting
            })
            body.addView(label("$title: ${getString(if (granted) R.string.permission_enabled_ready else R.string.permission_not_enabled)}", 14, if (granted) MUTED else WARNING))
        }
        val adbCmd = UsbPermissionSetup.manualCommand(packageName)
        body.addView(label(getString(R.string.manual_grant_cmd_hint), 14, MUTED).apply { setPadding(0, dp(12), 0, dp(6)) })
        body.addView(label(adbCmd, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(false)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0x22FFFFFF)
        })
        body.addView(button(getString(R.string.copy_command), false) {
            systemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText("SBplay ADB Command", adbCmd)
            )
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(8, 50))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permissions_and_connection_help))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close)) { _, _ -> render() }
            .show()
    }

    private fun hasPreciseLocation() =
        hasPermissionCompat(Manifest.permission.ACCESS_FINE_LOCATION)

    /**
     * Records that a restart-only setting changed and puts the save/cancel pair in the header.
     *
     * The first change captures both preference files so 取消保存 can really put everything back, not just
     * hide the buttons; 保存并重新连接 restarts the session with the new values.
     */
    private fun markNeedsReconnect() {
        if (!CarPlayBackgroundSession.hasSession()) return
        if (!pendingReconnect) {
            pendingSnapshot = snapshotPreferences()
            pendingReconnect = true
        }
        pendingActions?.visibility = View.VISIBLE
    }

    private fun cancelPendingReconnect() {
        pendingSnapshot?.let(::restorePreferences)
        pendingSnapshot = null
        pendingReconnect = false
        pendingActions?.visibility = View.GONE
        toast(getString(R.string.discard_changes))
        render()
    }

    private fun applyPendingReconnect() {
        val transport = AirPlayPersistence.loadWirelessEnabled(this)
        pendingSnapshot = null
        pendingReconnect = false
        pendingActions?.visibility = View.GONE
        connect(transport)
    }

    private fun snapshotPreferences(): Map<String, Map<String, *>> = mapOf(
        "xcertplay_airplay" to getSharedPreferences("xcertplay_airplay", MODE_PRIVATE).all,
        "diplay" to getSharedPreferences("diplay", MODE_PRIVATE).all,
    )

    private fun restorePreferences(snapshot: Map<String, Map<String, *>>) {
        for ((name, values) in snapshot) {
            val editor = getSharedPreferences(name, MODE_PRIVATE).edit().clear()
            for ((key, value) in values) when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
            editor.apply()
        }
    }
    private fun reconnectForAppliedSetting() {
        markNeedsReconnect()
    }

    private fun onLocationReportingChanged(enabled: Boolean) {
        if (enabled && !hasPreciseLocation()) {
            // Keep the switch off until precise location is actually granted.
            render()
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        applyLocationReporting(enabled)
    }

    private fun applyLocationReporting(enabled: Boolean) {
        if (AirPlayPersistence.loadLocationReportingEnabled(this) == enabled) return
        AirPlayPersistence.saveLocationReportingEnabled(this, enabled)
        render()
        // Location support is advertised during iAP2 identification, so both enabling and
        // disabling it require a new session. The host also refreshes its location service type.
        if (CarPlayBackgroundSession.hasSession()) markNeedsReconnect()
        else toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        startupHotspotCancelled = true
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resolutionSettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.resolution_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.resolution_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showResolutionSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showResolutionSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    private fun nightDelaySettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.custom_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun ambientLightThresholdControl(parent: LinearLayout) {
        val title = getString(R.string.ambient_light_threshold_title)
        fun summary(): String = getString(
            R.string.contrib_audio_home_choice_summary,
            title,
            getString(R.string.ambient_light_threshold_summary, AirPlayPersistence.loadAmbientLightThreshold(this).lux),
        )

        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            fields.addView(label(getString(R.string.ambient_light_threshold_value), 16, MUTED))
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(AirPlayPersistence.loadAmbientLightThreshold(this@DiPlayActivity).lux.toString())
            }
            fields.addView(input)
            fields.addView(label(getString(R.string.ambient_light_threshold_hint), 14, MUTED))
            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setView(fields)
                .setPositiveButton(getString(R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener saveThreshold@{
                    val lux = input.text.toString().trim().toIntOrNull()
                    if (lux == null || !AmbientLightThreshold.isValid(lux)) {
                        input.error = getString(R.string.ambient_light_threshold_error)
                        return@saveThreshold
                    }
                    AirPlayPersistence.saveAmbientLightThreshold(this, AmbientLightThreshold(lux))
                    control.text = summary()
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(AmbientLightThreshold.DEFAULT_LUX.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showNightModeSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    /** Where CarPlay puts its dock; between the two fixed edges it moves at once, otherwise on reconnect. */
    private fun carPlayDockControl(card: LinearLayout) {
        val docks = CarPlayDock.entries
        choice(card, getString(R.string.carplay_dock), listOf(
            getString(R.string.carplay_dock_automatic),
            getString(R.string.carplay_dock_driver_side),
            getString(R.string.carplay_dock_bottom),
        ), docks.indexOf(CarPlayDock.load(this)), reconnects = false) {
            val from = CarPlayDock.load(this)
            val to = docks[it]
            CarPlayDock.save(this, to)
            val session = CarPlayBackgroundSession.snapshot()
            val areas = session?.display?.viewAreas
            val target = to.edge?.let { areas?.withDock(it) }
            if (CarPlayDock.movesLive(from, to) && areas != null && target != null) {
                if (session.controller.showViewArea(target)) areas.use(target)
            } else {
                reconnectForAppliedSetting()
            }
        }
        card.addView(label(getString(R.string.carplay_dock_hint), 14, MUTED).apply { setPadding(0, 0, 0, dp(18)) })
    }

    private fun carButtonControls(parent: LinearLayout) {
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val preview = row().apply { gravity = Gravity.CENTER_VERTICAL }
        preview.addView(ImageView(this).apply {
            setImageBitmap(custom ?: BitmapFactory.decodeResource(resources, R.raw.ic_car_home))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(SURFACE, BORDER)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(16) })
        val text = column()
        text.addView(label(getString(R.string.car_button_icon), 18, TEXT, true))
        text.addView(label(getString(if (custom != null) R.string.car_button_icon_custom else R.string.default_icon), 14, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        preview.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(preview)
        parent.addView(button(getString(R.string.choose_image), false) {
            runCatching { iconPicker.launch("image/*") }.onFailure { toast(getString(R.string.this_head_unit_has_no_image_picker)) }
        }, matchButton(16, 60))
        if (custom != null) parent.addView(button(getString(R.string.default_icon), false) {
            AirPlayPersistence.clearCustomAirPlayIcon(this)
            refreshCarButton()
            carButtonSaved()
        }, matchButton(10, 60))
        val name = AirPlayPersistence.loadOemLabel(this)
        parent.addView(button("${getString(R.string.car_button_name)} · $name", false) {
            textInput(getString(R.string.car_button_name), name, secret = false) {
                AirPlayPersistence.saveOemLabel(this, it)
                refreshCarButton()
                carButtonSaved()
            }
        }, matchButton(10, 60))
        parent.addView(label(getString(R.string.car_button_description), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun carButtonSaved() {
        if (CarPlayBackgroundSession.hasSession()) toast(getString(R.string.car_button_saved_next_connection))
    }

    // Rebuilds only this card: render() would scroll the page back to the top.
    private fun refreshCarButton() {
        val card = carButtonCard ?: return
        card.removeViews(1, card.childCount - 1)
        carButtonControls(card)
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun connect(wireless: Boolean) {
        startupHotspotCancelled = true
        // Whatever page the tap came from, the session's transport decides which page is shown afterwards.
        connectionMode = if (wireless) 0 else 1
        // Saved up front, not only once the checks below pass: otherwise the once-a-second convergence would
        // pull the tab back to the old mode while the phone picker or the hotspot dialog is open. Never while
        // a session runs on the other transport (see ConnectionModePolicy); the open path below saves after
        // stopping it.
        if (ConnectionModePolicy.maySaveOnConnect(wireless, CarPlayBackgroundSession.hasSession(), CarPlayProgress.sessionWireless)) {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
        }
        if (setupError != null) { toast(setupError!!); return }
        // CS11/4.4: the hotspot's SSID and passphrase are read out of the firmware's own AP configuration
        // (docs/_digest/84), so there is nothing for the owner to type. What can still block a connection is
        // the car having no usable hotspot configuration at all, and that is what this checks - the saved
        // form is only a fallback for a unit whose AP configuration cannot be read.
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            !carHotspotSelfConfigured() &&
            manualHotspotError() != null) {
            // Fallback path only: the firmware had no readable AP configuration, so the owner's saved form
            // is all there is and it has to be valid. The dialog is the same one the mode used to show.
            askHotspotCredentials { ssid, password ->
                saveHotspotCredentials(ssid, password)
                connect(true)
            }
            return
        }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection(startBehind = true)
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    /**
     * Opens the CarPlay screen.
     *
     * [startBehind] is used when a new connection is started from the home page: the host begins the
     * bring-up and immediately hands the screen back here, so the driver keeps the gauge and its stage line
     * instead of the host's own "preparing CarPlay" checklist. Tapping 打开 CarPlay passes false.
     */
    private fun openProjection(startBehind: Boolean = false) {
        val intent = Intent(this, CarPlayHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (startBehind) {
            intent.putExtra(EXTRA_START_BEHIND, true)
            // The host takes the screen in its own task for a few hundred milliseconds before it can hand
            // it back; it paints this frame first so the switch is invisible instead of a black flash.
            CarPlayLaunchSnapshot.bitmap = captureHomeFrame()
            // The host lives in a task of its own, and on 4.4 a task switch plays the system zoom animation
            // (the whole page shrinking in and out), which the owner read as the screen being re-launched.
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
        if (startBehind) {
            // The host lives in its own task, so the switch itself blanks this window; come straight back
            // instead of waiting for the host to finish its bring-up handshake. The short delay gives it
            // the one traversal it needs to measure the video surface and start the session.
            overridePendingTransition(0, 0)
            window.decorView.postDelayed({
                startActivity(
                    Intent(this, DiPlayActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
                overridePendingTransition(0, 0)
            }, RETURN_FROM_HOST_DELAY_MILLIS)
        }
    }

    /** One still frame of this screen, matching the live colours exactly so the hand-off does not shift. */
    private fun captureHomeFrame(): android.graphics.Bitmap? = runCatching {
        val view = window.decorView
        if (view.width <= 0 || view.height <= 0) return@runCatching null
        val bitmap = android.graphics.Bitmap.createBitmap(
            view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888,
        )
        view.draw(android.graphics.Canvas(bitmap))
        bitmap
    }.getOrNull()
    private fun choosePhone() {
        val adapter = systemServiceOrNull(BluetoothManager::class.java)?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(this).setTitle(getString(R.string.turn_on_bluetooth))
                .setMessage(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    // CS11: API19 无法关闭 Channel（句柄随进程释放）
    private fun closeP2pChannel(channel: android.net.wifi.p2p.WifiP2pManager.Channel) = Unit

    private fun resetWirelessGroup() {
        val manager = systemServiceOrNull(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { closeP2pChannel(channel); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { closeP2pChannel(channel); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        closeP2pChannel(channel); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { closeP2pChannel(channel); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            closeP2pChannel(channel); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    /** The transport the home page shows: the running session's, else the saved mode. */
    private fun convergedWireless(): Boolean = ConnectionModePolicy.displayedWireless(
        AirPlayPersistence.loadWirelessEnabled(this),
        if (CarPlayBackgroundSession.hasSession()) CarPlayProgress.sessionWireless else null,
    )

    private fun refreshStatus() {
        // Mode convergence: a session started elsewhere (USB plug-in, Bluetooth receiver) or switched to USB
        // inside the host moves the tab here. render() calls back into refreshStatus, which then finds the
        // tab already converged and carries on.
        // Only the home page is rebuilt for it; any other page (settings with edits in progress) just takes the
        // new value and shows it on its next render.
        val convergedMode = if (convergedWireless()) 0 else 1
        if (convergedMode != connectionMode) {
            connectionMode = convergedMode
            if (page == "home") {
                render()
                return
            }
        }
        val running = CarPlayBackgroundSession.hasSession()
        val active = CarPlayBackgroundSession.active
        status?.text = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        if (lastRunning != running) {
            connectButton?.text = when {
                running -> getString(R.string.open_carplay)
                isWirelessMode() -> "开始无线连接"
                else -> "开始 USB 连接"
            }
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
            // A session just started: remember what connected, so the home page's summary card can say so.
            if (running) {
                DiPlayPreferences.saveLastConnection(
                    this,
                    DiPlayPreferences.phoneName(this),
                    CarPlayProgress.sessionWireless ?: AirPlayPersistence.loadWirelessEnabled(this),
                )
            }
        }
        // 打开 CarPlay belongs to a session that is actually up. While the link is still coming up it is
        // hidden, because tapping it only landed on the host's own "preparing CarPlay" screen - the very
        // screen the owner asked not to see during a connect.
        connectButton?.visibility = if (running && !active) View.GONE else View.VISIBLE
        connectButton?.isEnabled = setupError == null
        refreshActionCardText()
        refreshHeaderAndGauge()
    }

    /**
     * The once-a-second status tick lands here instead of going through [render]. It does the cheap string
     * compare first and returns early while the state has not moved, so a steady connection costs nothing.
     */
    private fun refreshHeaderAndGauge() {
        val state = when {
            sessionActive() -> 2
            CarPlayBackgroundSession.hasSession() -> 1
            else -> 0
        }
        val text = when {
            state == 2 && CarPlayProgress.awaitingFirstFrame -> "等待画面"
            state == 2 -> "已连接"
            state == 1 -> "连接中"
            else -> "未连接"
        }
        if (headerStatusText?.text?.toString() != text) {
            headerStatusText?.text = text
            headerStatusDot?.background = circle(if (state == 0) MUTED else ACCENT)
        }
        val gauge = gaugeView ?: return
        // While the link is coming up the gauge spins and the small line reports the host's current step,
        // so the home page is a live progress view instead of opening the host's own checklist.
        val connecting = state == 1
        gauge.setSpinning(connecting)
        val stage = when {
            connecting -> CarPlayProgress.stage ?: "正在连接…"
            state == 2 && CarPlayProgress.awaitingFirstFrame -> ConnectionAttemptRecord.WAITING_FOR_FIRST_FRAME
            else -> null
        }
        // Compare the line actually drawn, not only the live stage: a failure recorded after the session
        // ended changes the idle line without moving the state.
        val line = stage ?: subline()
        if (state == lastGaugeState && line == lastStage) return
        lastGaugeState = state
        lastStage = line
        gauge.filled = gaugeFilled()
        gaugeTitle?.text = headline()
        gaugeSubtitle?.text = line
        gaugePill?.text = pillText()
    }

    private fun reportFileName() = "SBplay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        if (exportInProgress) return
        runCatching { export.launch(reportFileName()) }.onFailure { exportDiagnostics() }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        com.shilapi.xcertplay.compat.WorkerThreads.start("diplay-export") {
            val result = runCatching {
                val report = buildString {
                    appendLine("SBplay ${version()} · diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API 19")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    // The saved mode and the running session's transport are reported apart: they can only
                    // differ while a session is being replaced, and the report must not blur the two.
                    appendLine("Connection (selected mode): ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Session transport: ${ConnectionModePolicy.sessionTransportLabel(
                        CarPlayBackgroundSession.hasSession(), CarPlayProgress.sessionWireless)}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
                    appendLine("Saved video preference (may differ from active session): H.264; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last connection attempt (failure / interruption) ---")
                    appendLine(ConnectionAttemptRecord.report(appContext))
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    appendLine("--- Last received boot and app-launch result ---")
                    appendLine(StartupDiagnosticSnapshot.report(appContext))
                    appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
                        "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
                    appendLine()
                    appendLine("--- SBplay saved Java crash / startup checkpoint (API 19) ---")
                    appendLine(CrashDiagnostics.displayLines(
                        appContext, ConnectionAttemptRecord.load(appContext),
                        ConnectionAttemptRecord.outcome(appContext),
                    ).joinToString("\n"))
                    appendLine()
                    appendLine("--- Recent own-app process exits (Android 11+) ---")
                    appendLine(ProcessExitDiagnostics.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                val savedReport = if (uri != null) {
                    DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                    DiagnosticExportStore.SavedReport(uri)
                } else DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report)
                savedReport to report
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val (savedReport, report) = result.getOrThrow()
                    AlertDialog.Builder(this).setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(when {
                            savedReport.savedInApp -> getString(R.string.diagnostic_report_saved_in_app)
                            savedReport.savedPath != null -> getString(R.string.diagnostic_report_saved_to_path, savedReport.savedPath)
                            uri == null -> "Downloads/SBplay/$fileName"
                            else -> getString(R.string.your_report_was_saved_to_the_selected_location)
                        })
                        .setPositiveButton(getString(R.string.view_diagnostic_report)) { _, _ -> showDiagnosticReport(report) }
                        .setNegativeButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedReport.uri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedReport.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { showDiagnosticReport(report) }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }
    }

    private fun showDiagnosticReport(report: String) {
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.diagnostic_report_view_hint), 14, MUTED))
        body.addView(label(report, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            // Keep Android 4.4's broken text-selection ActionPopupWindow disabled.
            setTextIsSelectable(false)
        })
        AlertDialog.Builder(this).setTitle(getString(R.string.view_diagnostic_report))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close), null).show()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) {
        // Only a trip to the car's own settings needs the full rebuild on the way back (the hotspot
        // reminder may have changed); everything else is covered by refreshStatus.
        leftForSystemSettings = true
        runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) }
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun playTestTone(streamType: Int) {
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        var candidate: AudioTrack? = null
        val track = try {
            val pcm = assets.open("navigation_test.pcm").use { it.readBytes() }
            AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
                candidate = it
                check(it.state == AudioTrack.STATE_INITIALIZED)
                check(it.write(pcm, 0, pcm.size) == pcm.size)
                it.play()
            }
        } catch (error: Exception) {
            val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
            candidate?.let { runCatching { it.release() } }
            Log.w("DiPlay", "playTestTone streamType=$streamType unavailable", error)
            toast(getString(R.string.audio_stream_unavailable, streamType, state))
            return
        }
        Log.i("DiPlay", "playTestTone streamType=$streamType state=${track.state} playState=${track.playState}")
        testToneTrack = track
        val stop = Runnable {
            track.stop()
            track.release()
            if (testToneTrack === track) testToneTrack = null
            toneStop = null
        }
        toneStop = stop
        handler.postDelayed(stop, 4500)
    }

    private val channelButtons = mutableListOf<Button>()

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        // Selected is the inverted primary fill; unselected is the same secondary control every other button
        // uses. It used to fill with SURFACE, the card's own colour, so an unselected channel had no visible
        // box at all while a selected one was a white block — the "merges" versus "jarring" pair again.
        target.setTextColor(if (selected) BG else TEXT)
        target.background = ripple(if (selected) rounded(ACCENT, ACCENT) else secondaryControl())
    }

    private fun channelSelector(): ViewGroup {
        channelButtons.clear()
        val grid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 3
            setPadding(0, dp(8), 0, dp(8))
        }
        for (i in 0..20) {
            val btn = Button(this).apply {
                text = i.toString()
                isAllCaps = false
                textSize = 16f
                minHeight = dp(48)
                // Set here as well as in paintChannel: the grid is built before any selection is painted, so
                // without this an unselected channel would render as bare text with no box.
                setTextColor(TEXT)
                background = ripple(secondaryControl())
                setOnClickListener {
                    val previous = navigationStreamType
                    navigationStreamType = i
                    if (previous != i) {
                        paintChannel(previous, false)
                        paintChannel(i, true)
                    }
                    playTestTone(i)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                // CS11: 4.4 只能用 API14 的 spec(int,int)，退化为等宽而不是加权等宽。
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(btn, params)
            channelButtons.add(btn)
            paintChannel(i, i == navigationStreamType)
        }
        return grid
    }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun languageSettings(content: LinearLayout) {
        section(content, getString(R.string.language_section_title), R.drawable.ic_dp_language) { card ->
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            val languageButton = button("${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        val card = card()
        // Tagged so the settings category filter counts sections and not the page's own title or subtitle.
        card.tag = "diplay-settings-section"
        // A section is a quiet label above its card rather than a headline with an icon. The icon parameter
        // stays because every call site passes one; the card's own outline carries the grouping now.
        val heading = column().apply { setPadding(dp(2), 0, 0, dp(8)) }
        heading.addView(label(title, 11, MUTED, true))
        card.addView(heading)
        build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, enabled: Boolean = true, save: (Boolean) -> Unit): Switch {
        val result = SettingsWidgets.createSwitchRow(
            context = this,
            label = title,
            description = description,
            checked = value,
            theme = nightPalette,
            contentDescription = title,
            enabled = enabled,
            onChanged = save,
        )
        parent.addView(result.rowView)
        return result.switch
    }
    // [announcesReconnect] labels a choice whose [save] reconnects by itself.
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true,
        announcesReconnect: Boolean = reconnects, enabled: Boolean = true, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}.apply { isEnabled = enabled }
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (announcesReconnect && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    /**
     * A card, from the shared factory.
     *
     * This used to build its own `GradientDrawable`, which is exactly how the page kept a palette of its own
     * while the overlay was fixed separately: two implementations of "a card", so a change to one never
     * reached the other (docs/_digest/84).
     */
    private fun card() = SettingsWidgets.createCard(this, nightPalette)
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) TYPE_BOLD else TYPE_REGULAR
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 18f; setTextColor(if (primary) BUTTON_TEXT else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // Two tiers, and nothing in between.
        //
        // The secondary tier used to be transparent with a white hairline under it, which on a card read as
        // "no button at all"; the two places that did fill it used `SURFACE`, the card's own colour, so they
        // read as "no button at all" a different way. The owner's words were that a button's background either
        // "merges with the container" or is "jarringly white" (docs/_digest/84). A control needs to be a
        // filled, outlined box: [RAISED] lifts it 1.22:1 off the card and [CONTROL_BORDER] gives it an edge.
        background = if (primary) {
            ripple(rounded(ACCENT, ACCENT))
        } else {
            ripple(secondaryControl())
        }
        setPadding(dp(16), 0, dp(16), 0); minHeight = if (primary) dp(56) else dp(52)
        setOnClickListener { click() }
    }
    /** A single bottom hairline: the separator this system uses instead of outlining every row. */
    private class RowDivider(private val color: Int, private val thickness: Int) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint().apply { this.color = color }
        override fun draw(canvas: android.graphics.Canvas) {
            canvas.drawRect(
                0f,
                (bounds.height() - thickness).toFloat(),
                bounds.width().toFloat(),
                bounds.height().toFloat(),
                paint,
            )
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }
    // 4 dp: squared enough to read as technical, which is the register the brand's graphics live in.
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(4).toFloat(); setStroke(dp(1), stroke) }

    /** A filled rounded rectangle at any radius: cards, tab tracks, pills, the brand mark. */
    private fun roundedR(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    /** A status dot. */
    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /**
     * The background of any control that is neither the page nor the primary action: buttons, unselected
     * chips. One definition, so the two tiers cannot drift apart again.
     */
    private fun secondaryControl() = rounded(RAISED, CONTROL_BORDER)
    // CS11: 4.4 上使用静态圆角底（没有 RippleDrawable）
    private fun ripple(content: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable = content
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    // Rounded, not truncated: below 160 dpi dp(1) became 0 and every border vanished.
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    /**
     * The cabin's theme, resolved once per render from the owner's own day/night setting.
     *
     * `CarPlayNightMode` is the single source of truth: it already drives CarPlay's night mode itself, so
     * hanging this page off the same setting keeps the two in step. `DAY`/`NIGHT` are explicit, `SYSTEM`
     * follows Android's own `uiMode`, and `AMBIENT` falls back to `SYSTEM` here because the light sensor is
     * owned by the host activity's controller — this page is a flat surface with no video on it, so being a
     * few seconds behind a cloud is not worth a second sensor listener.
     */
    private val nightPalette: SettingsTheme get() = SettingsTheme.page(resolvedNight)

    /**
     * Whether the page should be dark, with every one of the four modes actually honoured.
     *
     * Before this, `SYSTEM` and `AMBIENT` both read `Configuration.uiMode`, so on the head unit — whose
     * `uiMode` never reports night — picking either one left the page light no matter how dark the cabin
     * was (owner report, 2026-10-18). `AMBIENT` now follows the light sensor against the saved threshold,
     * and `SYSTEM` falls back to `uiMode` only when there is no sensor or no reading yet.
     */
    private val resolvedNight: Boolean
        get() = when (AirPlayPersistence.loadCarPlayNightMode(this)) {
            CarPlayNightMode.DAY -> false
            CarPlayNightMode.NIGHT -> true
            CarPlayNightMode.SYSTEM -> systemNight
            CarPlayNightMode.AMBIENT ->
                if (ambientSensorAvailable) ambientNight else systemNight
        }

    private val systemNight: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val ambientSensorAvailable: Boolean
        get() = runCatching { ambientLight.available }.getOrDefault(false)

    /** Last sensor reading decided the page is dark. Flipped by [onAmbientLux]. */
    private var ambientNight = false
    private var ambientListening = false
    private var lastRenderedNight: Boolean? = null

    private val ambientLight by lazy { AndroidAmbientLight(this) }

    private fun onAmbientLux(lux: Float) {
        if (!lux.isFinite() || lux < 0f) return
        val threshold = AirPlayPersistence.loadAmbientLightThreshold(this).lux
        val night = lux < threshold
        if (night == ambientNight) return
        ambientNight = night
        if (page == "settings" || page == "about") render()
    }

    /**
     * Starts or stops the light sensor for the page palette, and re-renders when the resolved mode moved
     * while the screen was not visible (the settings page can sit behind CarPlay for a long time).
     */
    private fun syncNightModeWithScreen() {
        val mode = AirPlayPersistence.loadCarPlayNightMode(this)
        val wantListening = mode == CarPlayNightMode.AMBIENT && ambientSensorAvailable
        if (wantListening && !ambientListening) {
            ambientListening = ambientLight.start(::onAmbientLux)
        } else if (!wantListening && ambientListening) {
            ambientLight.stop()
            ambientListening = false
        }
        val rendered = lastRenderedNight
        if (rendered != null && rendered != resolvedNight) render()
    }

    /**
     * The page palette. These read the resolved theme on each access rather than being constants, because
     * the same binary draws a light page in daylight and a dark one at night; a `val` snapshot taken at
     * class-init would pin whichever mode the process happened to start in.
     *
     * Three borders, not one, because nothing here can cast a shadow — see [SettingsTheme]. [BORDER]
     * outlines cards and controls, which is the boundary the driver actually reads.
     */
    private val BG get() = nightPalette.background
    private val SURFACE get() = nightPalette.surface
    private val RAISED get() = nightPalette.surfaceElevated
    private val DIVIDER get() = nightPalette.divider
    private val BORDER get() = nightPalette.cardBorder
    private val CONTROL_BORDER get() = nightPalette.controlBorder
    private val ACCENT get() = nightPalette.accent
    private val ACCENT_SOFT get() = nightPalette.accentSoft
    private val BUTTON_TEXT get() = nightPalette.buttonText
    private val TEXT get() = nightPalette.textPrimary
    private val MUTED get() = nightPalette.textSecondary
    private val SUCCESS get() = nightPalette.success
    private val WARNING get() = nightPalette.warning

    companion object {
        private const val VEHICLE_VALIDATION_RETRY_MILLIS = 500L
        private const val ADB_KEY_SAVE_WAIT_MILLIS = 500L

        /**
         * How long the home page lets the CarPlay host sit in front before reordering itself back.
         *
         * The host has to be created and laid out once to measure the video surface, which is what starts
         * the session; one traversal is enough. Anything longer is the flash of the host's own screen that
         * the owner reported, so this is deliberately close to a couple of frames.
         */
        private const val RETURN_FROM_HOST_DELAY_MILLIS = 120L

        /**
         * Two typefaces for the whole page, resolved once.
         *
         * `sans-serif-medium` does not exist on Android 4.4 — `Typeface.create("sans-serif-medium", NORMAL)`
         * silently falls back to the regular family, which is why "bold" titles on the head unit were never
         * actually bold. `DEFAULT_BOLD` is the weight that exists on every API level, and caching the two
         * objects keeps a page build from allocating a Typeface per label.
         */
        private val TYPE_REGULAR: Typeface by lazy { Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
        private val TYPE_BOLD: Typeface by lazy { Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }

        /** Lets the task leave the screen before the process ends; see `exitDiPlay`. */
        private const val EXIT_KILL_DELAY_MILLIS = 250L

        /** Every preference file the app opens after `onCreate`; see `warmUpPreferences`. */
        private val PREFERENCE_FILES = listOf(
            "diplay",
            "diplay_car_hotspot",
            "diplay_phone_wake",
            "carplay_picture",
            "carplay_wifi_p2p",
            "carplay_wifi_p2p_success",
        )
    }
}





























