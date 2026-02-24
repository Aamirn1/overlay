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
 * Foreground service that coordinates the 100% passthrough overlay architecture.
 * Manages 8 tiny windows + 1 full-screen non-touchable layer.
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
    
    // Bubble windows
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
        
        // Window Hierarchy (Bottom to Top):
        // Aim Layer (Non-touchable)
        // Setup Handles (Only in setup mode)
        // Markers (Only in aim mode)
        // Control Bar (Persistent)
        
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
        // FLAG_NOT_TOUCHABLE ensures this never blocks game input
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlay, params); aimOverlay = overlay
    }

    private fun createMarkers() {
        val ballRadius = prefs.getFloat("ball_radius", 15f)
        val cuePos = Vec2(prefs.getFloat("cue_x", 400f), prefs.getFloat("cue_y", 400f))
        val targetPos = Vec2(prefs.getFloat("target_x", 800f), prefs.getFloat("target_y", 400f))
        
        val markerSize = (ballRadius * 5).toInt()
        
        val cueParams = createMarkerParams(cuePos, markerSize)
        cueMarker = MarkerOverlayView(this, windowManager, cueParams, "CUE", cuePos) { newPos ->
            aimOverlay?.cuePos = newPos; aimOverlay?.invalidate(); saveMarkerPos("cue", newPos)
        }.apply { visibility = View.GONE; this.ballRadius = ballRadius }
        windowManager.addView(cueMarker, cueParams)

        val targetParams = createMarkerParams(targetPos, markerSize)
        targetMarker = MarkerOverlayView(this, windowManager, targetParams, "OBJ", targetPos) { newPos ->
            aimOverlay?.targetPos = newPos; aimOverlay?.invalidate(); saveMarkerPos("target", newPos)
        }.apply { visibility = View.GONE; this.ballRadius = ballRadius }
        windowManager.addView(targetMarker, targetParams)
    }

    private fun createSetupHandles() {
        val ballRadius = prefs.getFloat("ball_radius", 15f)
        val bounds = loadTableBounds()
        val handleSize = (ballRadius * 4).toInt()
        
        val tlPos = Vec2(bounds.left, bounds.top)
        val trPos = Vec2(bounds.right, bounds.top)
        val brPos = Vec2(bounds.right, bounds.bottom)
        val blPos = Vec2(bounds.left, bounds.bottom)
        
        handleTL = createHandle("TL", tlPos, handleSize) { newPos -> updateBounds(tl = newPos) }
        handleTR = createHandle("TR", trPos, handleSize) { newPos -> updateBounds(tr = newPos) }
        handleBR = createHandle("BR", brPos, handleSize) { newPos -> updateBounds(br = newPos) }
        handleBL = createHandle("BL", blPos, handleSize) { newPos -> updateBounds(bl = newPos) }
    }

    private fun createHandle(type: String, pos: Vec2, size: Int, onMove: (Vec2) -> Unit): MarkerOverlayView {
        val params = createMarkerParams(pos, size)
        val view = MarkerOverlayView(this, windowManager, params, type, pos, onMove).apply { 
            visibility = View.GONE; ballRadius = prefs.getFloat("ball_radius", 15f)
        }
        windowManager.addView(view, params)
        return view
    }

    private fun createMarkerParams(pos: Vec2, size: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (pos.x - size / 2f).toInt()
            y = (pos.y - size / 2f).toInt()
        }
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
        
        val visibility = if (isSetupShowing) View.VISIBLE else View.GONE
        handleTL?.visibility = visibility; handleTR?.visibility = visibility
        handleBR?.visibility = visibility; handleBL?.visibility = visibility
        
        if (!isSetupShowing) saveTableBounds(aimOverlay?.tableBounds ?: loadTableBounds())
    }

    private fun updateBounds(tl: Vec2? = null, tr: Vec2? = null, br: Vec2? = null, bl: Vec2? = null) {
        val current = aimOverlay?.tableBounds ?: loadTableBounds()
        
        val newLeft = tl?.x ?: bl?.x ?: current.left
        val newTop = tl?.y ?: tr?.y ?: current.top
        val newRight = tr?.x ?: br?.x ?: current.right
        val newBottom = bl?.y ?: br?.y ?: current.bottom
        
        val newBounds = RectF(newLeft, newTop, newRight, newBottom)
        aimOverlay?.tableBounds = newBounds
        aimOverlay?.invalidate()
        
        // Sync other handles to maintain rectangle
        tl?.let { handleTR?.updateParamsPosition(Vec2(newRight, it.y)); handleBL?.updateParamsPosition(Vec2(it.x, newBottom)) }
        tr?.let { handleTL?.updateParamsPosition(Vec2(newLeft, it.y)); handleBR?.updateParamsPosition(Vec2(it.x, newBottom)) }
        br?.let { handleTR?.updateParamsPosition(Vec2(it.x, newTop)); handleBL?.updateParamsPosition(Vec2(newLeft, it.y)) }
        bl?.let { handleTL?.updateParamsPosition(Vec2(it.x, newTop)); handleBR?.updateParamsPosition(Vec2(newRight, it.y)) }
    }

    private fun saveTableBounds(b: RectF) {
        prefs.edit().putFloat("table_left", b.left).putFloat("table_top", b.top)
            .putFloat("table_right", b.right).putFloat("table_bottom", b.bottom).apply()
    }

    private fun saveMarkerPos(prefix: String, pos: Vec2) {
        prefs.edit().putFloat("${prefix}_x", pos.x).putFloat("${prefix}_y", pos.y).apply()
    }

    private fun loadTableBounds(): RectF {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        
        // Default to a 80% centered rectangle (works for both portrait and landscape)
        val defaultLeft = w * 0.1f
        val defaultTop = h * 0.15f
        val defaultRight = w * 0.9f
        val defaultBottom = h * 0.85f
        
        return RectF(
            prefs.getFloat("table_left", defaultLeft),
            prefs.getFloat("table_top", defaultTop),
            prefs.getFloat("table_right", defaultRight),
            prefs.getFloat("table_bottom", defaultBottom)
        )
    }

    private fun removeAllOverlays() {
        listOf(controlBar, aimOverlay, cueMarker, targetMarker, handleTL, handleTR, handleBR, handleBL).forEach { v ->
            v?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        }
        controlBar = null; aimOverlay = null; cueMarker = null; targetMarker = null
        handleTL = null; handleTR = null; handleBR = null; handleBL = null
    }
}
