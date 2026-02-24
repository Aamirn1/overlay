package com.poolaim.overlay.view

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.poolaim.overlay.physics.Vec2

/**
 * Setup overlay for aligning virtual table boundaries with the game.
 * Yellow rectangle with 4 draggable corner handles.
 */
class SetupOverlayView(context: Context) : View(context) {

    var topLeft = Vec2(50f, 300f)
    var bottomRight = Vec2(1030f, 1800f)
    var onBoundsChanged: ((RectF) -> Unit)? = null

    private var draggingCorner = -1
    private val handleRadius = 28f
    private val touchSlop = 50f

    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFD740"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val railFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#08FFD740"); style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFD740"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1A1A2E"); textSize = 16f; textAlign = Paint.Align.CENTER
    }
    private val instrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFD740"); textSize = 18f; textAlign = Paint.Align.CENTER
    }
    private val pocketDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6600C853"); style = Paint.Style.FILL
    }

    private fun getCorners(): Array<Vec2> {
        val tr = Vec2(bottomRight.x, topLeft.y)
        val bl = Vec2(topLeft.x, bottomRight.y)
        return arrayOf(topLeft, tr, bottomRight, bl)
    }

    override fun onDraw(canvas: Canvas) {
        val rect = RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
        canvas.drawRect(rect, railFillPaint)
        canvas.drawRect(rect, railPaint)

        val cx = (topLeft.x + bottomRight.x) / 2f
        val cy = (topLeft.y + bottomRight.y) / 2f
        val dashPaint = Paint(railPaint).apply {
            strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
            color = Color.parseColor("#44FFD740")
        }
        canvas.drawLine(cx, topLeft.y, cx, bottomRight.y, dashPaint)
        canvas.drawLine(topLeft.x, cy, bottomRight.x, cy, dashPaint)

        val corners = getCorners()
        val labels = arrayOf("TL", "TR", "BR", "BL")
        for (i in corners.indices) {
            canvas.drawCircle(corners[i].x, corners[i].y, handleRadius, handlePaint)
            canvas.drawCircle(corners[i].x, corners[i].y, handleRadius, handleStrokePaint)
            canvas.drawText(labels[i], corners[i].x, corners[i].y + 7f, labelPaint)
        }

        val pocketPositions = arrayOf(
            topLeft, Vec2(cx, topLeft.y), Vec2(bottomRight.x, topLeft.y),
            Vec2(topLeft.x, bottomRight.y), Vec2(cx, bottomRight.y), bottomRight
        )
        for (p in pocketPositions) canvas.drawCircle(p.x, p.y, 12f, pocketDotPaint)

        canvas.drawText("Drag corners to align with table rails", cx, topLeft.y - 20f, instrPaint)
        canvas.drawText("Tap ⚙ again to lock", cx, bottomRight.y + 40f, instrPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y; val tv = Vec2(x, y)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val corners = getCorners()
                for (i in corners.indices) {
                    if (tv.distanceTo(corners[i]) <= touchSlop + handleRadius) {
                        draggingCorner = i; return true
                    }
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingCorner >= 0) {
                    when (draggingCorner) {
                        0 -> topLeft = Vec2(x, y)
                        1 -> { topLeft = Vec2(topLeft.x, y); bottomRight = Vec2(x, bottomRight.y) }
                        2 -> bottomRight = Vec2(x, y)
                        3 -> { topLeft = Vec2(x, topLeft.y); bottomRight = Vec2(bottomRight.x, y) }
                    }
                    notifyBoundsChanged(); invalidate(); return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingCorner >= 0) { draggingCorner = -1; return true }
            }
        }
        return false
    }

    private fun notifyBoundsChanged() {
        val rect = RectF(
            minOf(topLeft.x, bottomRight.x), minOf(topLeft.y, bottomRight.y),
            maxOf(topLeft.x, bottomRight.x), maxOf(topLeft.y, bottomRight.y)
        )
        onBoundsChanged?.invoke(rect)
    }

    fun getTableBounds(): RectF = RectF(
        minOf(topLeft.x, bottomRight.x), minOf(topLeft.y, bottomRight.y),
        maxOf(topLeft.x, bottomRight.x), maxOf(topLeft.y, bottomRight.y)
    )
}
