package com.poolaim.overlay.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import com.poolaim.overlay.MainActivity
import com.poolaim.overlay.R
import com.poolaim.overlay.physics.Vec2
import com.poolaim.overlay.view.AimOverlayView
import com.poolaim.overlay.view.MarkerOverlayView

/**
 * Final refactored service for 100% passthrough and orientation awareness.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "pool_aim_overlay_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE = "com.poolaim.overlay.TOGGLE"
        const val ACTION_STOP = "com.poolaim.overlay.STOP"
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
    private var isSetupShowing = false

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
            ACTION_TOGGLE -> toggleAim()
            ACTION_STOP -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Refresh full-screen bounds and pockets on rotation
        val bounds = loadTableBounds()
        aimOverlay?.tableBounds = bounds
        aimOverlay?.invalidate()
        
        // Reposition handles and markers to keep them in view
        refreshMarkerPositions()
    }

    override fun onDestroy() { removeAllOverlays(); super.onDestroy() }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_description); setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openPI = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
            setPadding(8, 4, 8, 4); elevation = 30f
        }
        val btnAim = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_compass)
            setBackgroundColor(0x00000000); setPadding(16, 16, 16, 16)
            setColorFilter(0xFF00E5FF.toInt()); setOnClickListener { toggleAim() }
        }
        val btnSetup = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            setBackgroundColor(0x00000000); setPadding(16, 16, 16, 16)
            setColorFilter(0xFFFFD740.toInt()); setOnClickListener { toggleSetup() }
        }
        bar.addView(btnAim); bar.addView(btnSetup)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 50; y = 150 }

        var ix = 0; var iy = 0; var itx = 0f; var ity = 0f
        bar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; itx = event.rawX; ity = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = ix + (event.rawX - itx).toInt(); params.y = iy + (event.rawY - ity).toInt()
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
            ballRadius = prefs.getFloat("ball_radius", 15f)
            maxBounces = prefs.getInt("max_bounces", 3)
            lineThickness = prefs.getFloat("line_thickness", 3f)
            cuePos = Vec2(prefs.getFloat("cue_x", 400f), prefs.getFloat("cue_y", 400f))
            targetPos = Vec2(prefs.getFloat("target_x", 800f), prefs.getFloat("target_y", 400f))
        }
        // FLAG_NOT_TOUCHABLE + FLAG_NOT_TOUCH_MODAL + FLAG_NOT_FOCUSABLE = 100% Passthrough
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlay, params); aimOverlay = overlay
    }

    private fun createMarkers() {
        val radius = prefs.getFloat("ball_radius", 15f)
        val cueP = Vec2(prefs.getFloat("cue_x", 400f), prefs.getFloat("cue_y", 400f))
        val targetP = Vec2(prefs.getFloat("target_x", 800f), prefs.getFloat("target_y", 400f))
        val size = (radius * 5).toInt()
        
        cueMarker = createMarkerBubble("CUE", cueP, size) { aimOverlay?.cuePos = it; aimOverlay?.invalidate(); savePos("cue", it) }
        targetMarker = createMarkerBubble("OBJ", targetP, size) { aimOverlay?.targetPos = it; aimOverlay?.invalidate(); savePos("target", it) }
    }

    private fun createSetupHandles() {
        val radius = prefs.getFloat("ball_radius", 15f)
        val b = loadTableBounds()
        val size = (radius * 4).toInt()
        
        handleTL = createMarkerBubble("TL", Vec2(b.left, b.top), size) { updateBounds(tl = it) }
        handleTR = createMarkerBubble("TR", Vec2(b.right, b.top), size) { updateBounds(tr = it) }
        handleBR = createMarkerBubble("BR", Vec2(b.right, b.bottom), size) { updateBounds(br = it) }
        handleBL = createMarkerBubble("BL", Vec2(b.left, b.bottom), size) { updateBounds(bl = it) }
    }

    private fun createMarkerBubble(type: String, pos: Vec2, size: Int, onMove: (Vec2) -> Unit): MarkerOverlayView {
        val p = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (pos.x - size / 2f).toInt(); y = (pos.y - size / 2f).toInt() }
        val view = MarkerOverlayView(this, windowManager, p, type, pos, onMove).apply { 
            visibility = View.GONE; ballRadius = prefs.getFloat("ball_radius", 15f)
        }
        windowManager.addView(view, p)
        return view
    }

    private fun refreshMarkerPositions() {
        val b = loadTableBounds()
        handleTL?.updateParamsPosition(Vec2(b.left, b.top))
        handleTR?.updateParamsPosition(Vec2(b.right, b.top))
        handleBR?.updateParamsPosition(Vec2(b.right, b.bottom))
        handleBL?.updateParamsPosition(Vec2(b.left, b.bottom))
        
        // Ensure markers are within screen
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        
        aimOverlay?.cuePos = Vec2(aimOverlay?.cuePos?.x?.coerceIn(0f, w) ?: 0f, aimOverlay?.cuePos?.y?.coerceIn(0f, h) ?: 0f)
        aimOverlay?.targetPos = Vec2(aimOverlay?.targetPos?.x?.coerceIn(0f, w) ?: 0f, aimOverlay?.targetPos?.y?.coerceIn(0f, h) ?: 0f)
        
        cueMarker?.updateParamsPosition(aimOverlay!!.cuePos)
        targetMarker?.updateParamsPosition(aimOverlay!!.targetPos)
        
        aimOverlay?.invalidate()
    }

    private fun toggleAim() {
        isAimMarkerShowing = !isAimMarkerShowing
        aimOverlay?.isAimVisible = isAimMarkerShowing; aimOverlay?.invalidate()
        cueMarker?.visibility = if (isAimMarkerShowing) View.VISIBLE else View.GONE
        targetMarker?.visibility = if (isAimMarkerShowing) View.VISIBLE else View.GONE
        if (isAimMarkerShowing && isSetupShowing) toggleSetup()
    }

    private fun toggleSetup() {
        isSetupShowing = !isSetupShowing
        aimOverlay?.isSetupVisible = isSetupShowing; aimOverlay?.invalidate()
        val v = if (isSetupShowing) View.VISIBLE else View.GONE
        handleTL?.visibility = v; handleTR?.visibility = v; handleBR?.visibility = v; handleBL?.visibility = v
        if (!isSetupShowing) saveTableBounds(aimOverlay?.tableBounds ?: loadTableBounds())
    }

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
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        
        // Centered region that fits both portrait and landscape
        val dl = w * 0.15f; val dt = h * 0.2f; val dr = w * 0.85f; val db = h * 0.8f
        
        return RectF(
            prefs.getFloat("table_left", dl), prefs.getFloat("table_top", dt),
            prefs.getFloat("table_right", dr), prefs.getFloat("table_bottom", db)
        )
    }

    private fun removeAllOverlays() {
        listOf(controlBar, aimOverlay, cueMarker, targetMarker, handleTL, handleTR, handleBR, handleBL).forEach { v ->
            v?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        }
    }
}
