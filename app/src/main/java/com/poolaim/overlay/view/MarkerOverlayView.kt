package com.poolaim.overlay.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.poolaim.overlay.physics.Vec2

/**
 * A small, draggable circular overlay for markers (Cue/Target).
 * Handles its own window movement.
 */
class MarkerOverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams,
    val type: String, // "CUE" or "OBJ"
    initialPos: Vec2,
    private val onPositionChanged: (Vec2) -> Unit
) : View(context) {

    var ballRadius = 15f
    var position = initialPos
        private set

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (type == "CUE") Color.parseColor("#EEFFFFFF") else Color.parseColor("#EEFF5252")
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (type == "CUE") Color.parseColor("#CC00E5FF") else Color.parseColor("#CCFFAB40")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1A1A2E")
        textSize = 18f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onDraw(canvas: Canvas) {
        val r = ballRadius * 1.5f
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, strokePaint)
        canvas.drawText(type, cx, cy + 6f, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastTouchX
                val dy = event.rawY - lastTouchY
                params.x += dx.toInt()
                params.y += dy.toInt()
                windowManager.updateViewLayout(this, params)
                
                // Update internal position (center of marker)
                position = Vec2(params.x.toFloat() + width / 2f, params.y.toFloat() + height / 2f)
                onPositionChanged(position)
                
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                return true
            }
        }
        return false
    }

    fun updateParamsPosition(pos: Vec2) {
        params.x = (pos.x - width / 2f).toInt()
        params.y = (pos.y - height / 2f).toInt()
        position = pos
        windowManager.updateViewLayout(this, params)
    }
}
