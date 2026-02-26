package com.poolaim.overlay.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.poolaim.overlay.MainActivity
import com.poolaim.overlay.R
import com.poolaim.overlay.cv.PhysicsLoop
import com.poolaim.overlay.cv.ScreenCaptureManager
import com.poolaim.overlay.physics.Vec2
import com.poolaim.overlay.view.AimOverlayView
import com.poolaim.overlay.view.MarkerOverlayView

/**
 * Overlay service supporting two modes:
 *
 *  • **Manual mode** (existing): draggable CUE / OBJ markers, user positions them.
 *  • **CV mode** (new): MediaProjection screen capture → BitmapCv detection →
 *    PhysicsLoop recalculates & redraws at ~15 FPS automatically.
 *
 * CV mode is activated via [ACTION_START_CV] with the MediaProjection result embedded
 * in the intent extras. Manual markers are hidden while CV mode is active but are
 * re-shown when detection confidence drops.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        const val CHANNEL_ID = "pool_aim_overlay_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE    = "com.poolaim.overlay.TOGGLE"
        const val ACTION_STOP      = "com.poolaim.overlay.STOP"
        const val ACTION_START_CV  = "com.poolaim.overlay.START_CV"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val PREFS_NAME = "pool_aim_prefs"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences

    private var controlBar: View? = null
    private var aimOverlay: AimOverlayView? = null
    private var cueMarker: MarkerOverlayView? = null
    private var targetMarker: MarkerOverlayView? = null
    private var handleTL: MarkerOverlayView? = null
    private var handleTR: MarkerOverlayView? = null
    private var handleBR: MarkerOverlayView? = null
    private var handleBL: MarkerOverlayView? = null

    private var isAimMarkerShowing = false
    private var isSetupActive = false
    private var isBarExpanded = true  // Collapsible state

    // CV components
    private val captureManager = ScreenCaptureManager()
    private var physicsLoop: PhysicsLoop? = null
    private var isCvModeActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createAimOverlay()
        createSetupHandles()
        createMarkers()
        createControlBar()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE   -> toggleAim()
            ACTION_STOP     -> exitService()
            ACTION_START_CV -> handleStartCv(intent)
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshOverlayLayouts()
    }

    override fun onDestroy() {
        stopCvMode()
        removeAllOverlays()
        super.onDestroy()
    }

    // ── CV Mode ──────────────────────────────────────────────────────────── //

    private fun handleStartCv(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.w(TAG, "MediaProjection consent denied or missing")
            return
        }

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection =
            projectionManager.getMediaProjection(resultCode, resultData)

        val dm = resources.displayMetrics
        captureManager.start(projection, dm.widthPixels, dm.heightPixels, dm.densityDpi)

        val loop = PhysicsLoop(captureManager, aimOverlay!!)
        loop.tableBoundsScreen = aimOverlay?.tableBounds ?: loadTableBounds()
        physicsLoop = loop
        loop.start()

        isCvModeActive = true
        aimOverlay?.isCvSearching = true
        // Show aim overlay; hide draggable markers (CV drives them now)
        if (!isAimMarkerShowing) toggleAim()
        setMarkersVisible(false)
        Log.d(TAG, "CV mode started")
    }

    private fun stopCvMode() {
        physicsLoop?.stop()
        physicsLoop = null
        captureManager.stop()
        isCvModeActive = false
        aimOverlay?.isCvActive = false
        aimOverlay?.isCvSearching = false
    }

    // ── Overlay construction ─────────────────────────────────────────────── //

    private fun exitService() {
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.channel_description); setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openPI = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val togglePI = PendingIntent.getService(this, 1,
            Intent(this, OverlayService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPI = PendingIntent.getService(this, 2,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openPI).setOngoing(true)
            .addAction(android.R.drawable.ic_media_play, getString(R.string.action_toggle), togglePI)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), stopPI)
            .build()
    }

    private fun createControlBar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xEE1A1A2E.toInt())
            setPadding(16, 8, 16, 8); elevation = 60f
        }
        val dragHandle = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size)
            setPadding(12, 12, 12, 12); setColorFilter(0xFFCCCCCC.toInt())
        }

        // Action grouping for collapse/expand
        val actionGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnAim = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_compass)
            setBackgroundColor(0x00000000); setPadding(12, 12, 12, 12)
            alpha = if (isAimMarkerShowing) 1f else 0.4f
            setOnClickListener { toggleAim(); alpha = if (isAimMarkerShowing) 1f else 0.4f }
        }
        val btnSetup = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            setBackgroundColor(0x00000000); setPadding(12, 12, 12, 12)
            alpha = if (isSetupActive) 1f else 0.4f
            setOnClickListener { toggleSetup(); alpha = if (isSetupActive) 1f else 0.4f }
        }

        // ⚡ New CV Toggle Button
        val btnCv = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_view) // using eye/view icon for CV
            setBackgroundColor(0x00000000); setPadding(12, 12, 12, 12)
            setColorFilter(if (isCvModeActive) 0xFF00C853.toInt() else 0xFFFFFFFF.toInt())
            setOnClickListener {
                if (isCvModeActive) stopCvMode() else {
                    Toast.makeText(this@OverlayService, "Start CV from main app first", Toast.LENGTH_SHORT).show()
                }
                setColorFilter(if (isCvModeActive) 0xFF00C853.toInt() else 0xFFFFFFFF.toInt())
            }
        }

        val btnClose = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(0x00000000); setPadding(12, 12, 12, 12)
            setColorFilter(0xFFF44336.toInt())
            setOnClickListener { exitService() }
        }

        // Arrow Toggle (Collapse/Expand)
        val btnToggle = ImageButton(this).apply {
            setImageResource(if (isBarExpanded) android.R.drawable.ic_media_previous else android.R.drawable.ic_media_next)
            setBackgroundColor(0x00000000); setPadding(12, 12, 12, 12)
            setColorFilter(0xFFFFFFFF.toInt())
            setOnClickListener {
                isBarExpanded = !isBarExpanded
                actionGroup.visibility = if (isBarExpanded) View.VISIBLE else View.GONE
                setImageResource(if (isBarExpanded) android.R.drawable.ic_media_previous else android.R.drawable.ic_media_next)
            }
        }

        bar.addView(dragHandle)
        // Add actions to group
        actionGroup.addView(btnAim)
        actionGroup.addView(btnSetup)
        actionGroup.addView(btnCv)
        actionGroup.addView(btnClose)

        bar.addView(actionGroup)
        bar.addView(btnToggle)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 50; y = 200 }

        var ix = 0; var iy = 0; var itx = 0f; var ity = 0f
        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = params.x; iy = params.y; itx = event.rawX; ity = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = ix + (event.rawX - itx).toInt()
                    params.y = iy + (event.rawY - ity).toInt()
                    windowManager.updateViewLayout(bar, params); true
                }
                else -> false
            }
        }
        windowManager.addView(bar, params); controlBar = bar
    }

    private fun createAimOverlay() {
        val overlay = AimOverlayView(this).apply {
            tableBounds = loadTableBounds()
            ballRadius  = prefs.getFloat("ball_radius", 15f)
            maxBounces  = prefs.getInt("max_bounces", 3)
            lineThickness = prefs.getFloat("line_thickness", 3f)
            cuePos    = Vec2(prefs.getFloat("cue_x", 400f), prefs.getFloat("cue_y", 400f))
            targetPos = Vec2(prefs.getFloat("target_x", 800f), prefs.getFloat("target_y", 400f))
        }
        // CRITICAL: alpha < 0.8 is REQUIRED for touch passthrough on Android 12+
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { alpha = 0.7f }
        windowManager.addView(overlay, params); aimOverlay = overlay
    }

    private fun createMarkers() {
        val radius  = prefs.getFloat("ball_radius", 15f)
        val cueP    = Vec2(prefs.getFloat("cue_x", 400f), prefs.getFloat("cue_y", 400f))
        val targetP = Vec2(prefs.getFloat("target_x", 800f), prefs.getFloat("target_y", 400f))
        val size    = (radius * 5).toInt()
        cueMarker    = createMarkerBubble("CUE", cueP, size) {
            aimOverlay?.cuePos = it; aimOverlay?.invalidate(); savePos("cue", it)
        }
        targetMarker = createMarkerBubble("OBJ", targetP, size) {
            aimOverlay?.targetPos = it; aimOverlay?.invalidate(); savePos("target", it)
        }
    }

    private fun createSetupHandles() {
        val radius = prefs.getFloat("ball_radius", 15f)
        val b = loadTableBounds(); val size = (radius * 5).toInt()
        handleTL = createMarkerBubble("TL", Vec2(b.left, b.top), size)   { updateBounds(tl = it) }
        handleTR = createMarkerBubble("TR", Vec2(b.right, b.top), size)  { updateBounds(tr = it) }
        handleBR = createMarkerBubble("BR", Vec2(b.right, b.bottom), size){ updateBounds(br = it) }
        handleBL = createMarkerBubble("BL", Vec2(b.left, b.bottom), size) { updateBounds(bl = it) }
    }

    private fun createMarkerBubble(type: String, pos: Vec2, size: Int, onMove: (Vec2) -> Unit): MarkerOverlayView {
        val p = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (pos.x - size / 2f).toInt(); y = (pos.y - size / 2f).toInt()
            alpha = 0.85f
        }
        val view = MarkerOverlayView(this, windowManager, p, type, pos, onMove).apply {
            visibility = View.GONE; ballRadius = prefs.getFloat("ball_radius", 15f)
        }
        windowManager.addView(view, p)
        return view
    }

    // ── Visibility helpers ───────────────────────────────────────────────── //

    private fun toggleAim() {
        isAimMarkerShowing = !isAimMarkerShowing
        aimOverlay?.isAimVisible = isAimMarkerShowing; aimOverlay?.invalidate()
        // Only show manual markers when NOT in CV mode
        if (!isCvModeActive) setMarkersVisible(isAimMarkerShowing)
        if (isAimMarkerShowing && isSetupActive) toggleSetup()
        // Keep physics loop table bounds in sync
        physicsLoop?.tableBoundsScreen = aimOverlay?.tableBounds ?: loadTableBounds()
    }

    private fun setMarkersVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        cueMarker?.visibility = v; targetMarker?.visibility = v
    }

    private fun toggleSetup() {
        isSetupActive = !isSetupActive
        aimOverlay?.isSetupVisible = isSetupActive; aimOverlay?.invalidate()
        val v = if (isSetupActive) View.VISIBLE else View.GONE
        handleTL?.visibility = v; handleTR?.visibility = v
        handleBR?.visibility = v; handleBL?.visibility = v
        if (!isSetupActive) saveTableBounds(aimOverlay?.tableBounds ?: loadTableBounds())
    }

    private fun refreshOverlayLayouts() {
        val b = loadTableBounds()
        aimOverlay?.tableBounds = b; aimOverlay?.invalidate()
        handleTL?.updateParamsPosition(Vec2(b.left, b.top))
        handleTR?.updateParamsPosition(Vec2(b.right, b.top))
        handleBR?.updateParamsPosition(Vec2(b.right, b.bottom))
        handleBL?.updateParamsPosition(Vec2(b.left, b.bottom))
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        aimOverlay?.cuePos    = Vec2(aimOverlay?.cuePos?.x?.coerceIn(0f, w)    ?: 0f,
                                     aimOverlay?.cuePos?.y?.coerceIn(0f, h)    ?: 0f)
        aimOverlay?.targetPos = Vec2(aimOverlay?.targetPos?.x?.coerceIn(0f, w) ?: 0f,
                                     aimOverlay?.targetPos?.y?.coerceIn(0f, h) ?: 0f)
        cueMarker?.updateParamsPosition(aimOverlay!!.cuePos)
        targetMarker?.updateParamsPosition(aimOverlay!!.targetPos)
        aimOverlay?.invalidate()
        physicsLoop?.tableBoundsScreen = b
    }

    // ── Persistence ──────────────────────────────────────────────────────── //

    private fun updateBounds(tl: Vec2? = null, tr: Vec2? = null, br: Vec2? = null, bl: Vec2? = null) {
        val c = aimOverlay?.tableBounds ?: loadTableBounds()
        val nl = tl?.x ?: bl?.x ?: c.left
        val nt = tl?.y ?: tr?.y ?: c.top
        val nr = tr?.x ?: br?.x ?: c.right
        val nb = bl?.y ?: br?.y ?: c.bottom
        val nbnd = RectF(nl, nt, nr, nb)
        aimOverlay?.tableBounds = nbnd; aimOverlay?.invalidate()
        tl?.let { handleTR?.updateParamsPosition(Vec2(nr, it.y)); handleBL?.updateParamsPosition(Vec2(it.x, nb)) }
        tr?.let { handleTL?.updateParamsPosition(Vec2(nl, it.y)); handleBR?.updateParamsPosition(Vec2(it.x, nb)) }
        br?.let { handleTR?.updateParamsPosition(Vec2(it.x, nt)); handleBL?.updateParamsPosition(Vec2(nl, it.y)) }
        bl?.let { handleTL?.updateParamsPosition(Vec2(it.x, nt)); handleBR?.updateParamsPosition(Vec2(nr, it.y)) }
        physicsLoop?.tableBoundsScreen = nbnd
    }

    private fun saveTableBounds(b: RectF) {
        prefs.edit().putFloat("table_left", b.left).putFloat("table_top", b.top)
            .putFloat("table_right", b.right).putFloat("table_bottom", b.bottom).apply()
    }

    private fun savePos(pre: String, pos: Vec2) {
        prefs.edit().putFloat("${pre}_x", pos.x).putFloat("${pre}_y", pos.y).apply()
    }

    private fun loadTableBounds(): RectF {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        return RectF(
            prefs.getFloat("table_left",   w * 0.15f),
            prefs.getFloat("table_top",    h * 0.2f),
            prefs.getFloat("table_right",  w * 0.85f),
            prefs.getFloat("table_bottom", h * 0.8f)
        )
    }

    private fun removeAllOverlays() {
        listOf(controlBar, aimOverlay, cueMarker, targetMarker,
               handleTL, handleTR, handleBR, handleBL).forEach { v ->
            v?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        }
        controlBar = null; aimOverlay = null; cueMarker = null; targetMarker = null
        handleTL = null; handleTR = null; handleBR = null; handleBL = null
    }
}
