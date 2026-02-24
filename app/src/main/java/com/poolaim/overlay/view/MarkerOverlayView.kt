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
 * A small, draggable circular overlay for markers (Cue/Target) or setup handles (TL/TR/BR/BL).
 * Handles its own window movement.
 */
class MarkerOverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams,
    val type: String, // "CUE", "OBJ", "TL", "TR", "BR", "BL"
    initialPos: Vec2,
    private val onPositionChanged: (Vec2) -> Unit
) : View(context) {

    var ballRadius = 15f
    var position = initialPos
        private set

    private val isHandle = type != "CUE" && type != "OBJ"

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when(type) {
            "CUE" -> Color.parseColor("#EEFFFFFF")
            "OBJ" -> Color.parseColor("#EEFF5252")
            else -> Color.parseColor("#CCFFD740") // Setup handle color
        }
        style = Paint.Style.FILL
    }
    
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when(type) {
            "CUE" -> Color.parseColor("#CC00E5FF")
            "OBJ" -> Color.parseColor("#CCFFAB40")
            else -> Color.WHITE
        }
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isHandle) Color.BLACK else Color.parseColor("#FF1A1A2E")
        textSize = if (isHandle) 14f else 18f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onDraw(canvas: Canvas) {
        val r = if (isHandle) ballRadius * 1.2f else ballRadius * 1.5f
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, strokePaint)
        canvas.drawText(type, cx, cy + (if (isHandle) 5f else 6f), labelPaint)
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
