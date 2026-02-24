package com.poolaim.overlay.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.IBinder
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
import com.poolaim.overlay.view.SetupOverlayView

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
    private var setupOverlay: SetupOverlayView? = null
    private var cueMarker: MarkerOverlayView? = null
    private var targetMarker: MarkerOverlayView? = null
    
    private var isAimMarkerShowing = false
    private var isSetupShowing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        
        // Z-order is determined by the order of adding to WindowManager.
        // We want: Aim (Bottom) -> Setup -> Markers -> ControlBar (Top)
        createAimOverlay()
        createSetupOverlay()
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
            setPadding(8, 4, 8, 4); elevation = 20f
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
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 100 }

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
            isAimVisible = false; tableBounds = loadTableBounds()
            ballRadius = prefs.getFloat("ball_radius", 15f)
            maxBounces = prefs.getInt("max_bounces", 3)
            lineThickness = prefs.getFloat("line_thickness", 3f)
            cuePos = Vec2(prefs.getFloat("cue_x", 400f), prefs.getFloat("cue_y", 1200f))
            targetPos = Vec2(prefs.getFloat("target_x", 400f), prefs.getFloat("target_y", 800f))
        }
        // IMPORTANT: FLAG_NOT_TOUCHABLE ensures this full-screen view never blocks input
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
        val cuePos = Vec2(prefs.getFloat("cue_x", 400f), prefs.getFloat("cue_y", 1200f))
        val targetPos = Vec2(prefs.getFloat("target_x", 400f), prefs.getFloat("target_y", 800f))
        
        val markerSize = (ballRadius * 4).toInt()
        
        val cueParams = createMarkerParams(cuePos, markerSize)
        cueMarker = MarkerOverlayView(this, windowManager, cueParams, "CUE", cuePos) { newPos ->
            aimOverlay?.cuePos = newPos
            aimOverlay?.invalidate()
            saveMarkerPos("cue", newPos)
        }.apply { visibility = View.GONE; this.ballRadius = ballRadius }
        windowManager.addView(cueMarker, cueParams)

        val targetParams = createMarkerParams(targetPos, markerSize)
        targetMarker = MarkerOverlayView(this, windowManager, targetParams, "OBJ", targetPos) { newPos ->
            aimOverlay?.targetPos = newPos
            aimOverlay?.invalidate()
            saveMarkerPos("target", newPos)
        }.apply { visibility = View.GONE; this.ballRadius = ballRadius }
        windowManager.addView(targetMarker, targetParams)
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

    private fun createSetupOverlay() {
        val bounds = loadTableBounds()
        val overlay = SetupOverlayView(this).apply {
            topLeft = Vec2(bounds.left, bounds.top); bottomRight = Vec2(bounds.right, bounds.bottom)
            onBoundsChanged = { nb -> aimOverlay?.tableBounds = nb; aimOverlay?.invalidate(); saveTableBounds(nb) }
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlay, params); setupOverlay = overlay
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
        setupOverlay?.visibility = if (isSetupShowing) View.VISIBLE else View.GONE
        if (!isSetupShowing) {
            val bounds = setupOverlay?.getTableBounds() ?: return
            saveTableBounds(bounds); aimOverlay?.tableBounds = bounds; aimOverlay?.invalidate()
        }
    }

    private fun saveTableBounds(b: RectF) {
        prefs.edit().putFloat("table_left", b.left).putFloat("table_top", b.top)
            .putFloat("table_right", b.right).putFloat("table_bottom", b.bottom).apply()
    }

    private fun saveMarkerPos(prefix: String, pos: Vec2) {
        prefs.edit().putFloat("${prefix}_x", pos.x).putFloat("${prefix}_y", pos.y).apply()
    }

    private fun loadTableBounds(): RectF = RectF(
        prefs.getFloat("table_left", 50f), prefs.getFloat("table_top", 300f),
        prefs.getFloat("table_right", 1030f), prefs.getFloat("table_bottom", 1800f)
    )

    private fun removeAllOverlays() {
        listOf(controlBar, aimOverlay, setupOverlay, cueMarker, targetMarker).forEach { v ->
            v?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        }
        controlBar = null; aimOverlay = null; setupOverlay = null; cueMarker = null; targetMarker = null
    }
}
