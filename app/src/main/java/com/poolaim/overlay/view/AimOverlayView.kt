package com.poolaim.overlay.view

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.poolaim.overlay.physics.PathSegment
import com.poolaim.overlay.physics.TrajectoryEngine
import com.poolaim.overlay.physics.Vec2

/**
 * Full-screen transparent overlay that draws trajectory lines.
 * Handles dragging of cue and target ball markers.
 * Only consumes touches on markers — everything else passes through.
 */
class AimOverlayView(context: Context) : View(context) {

    var cuePos = Vec2(400f, 1200f)
    var targetPos = Vec2(400f, 800f)
    var tableBounds = RectF(50f, 300f, 1030f, 1800f)
    var ballRadius = 15f
    var maxBounces = 3
    var lineThickness = 3f
    var isAimVisible = false
    var onMarkersChanged: (() -> Unit)? = null

    private val engine = TrajectoryEngine()
    private var draggingCue = false
    private var draggingTarget = false
    private val touchSlop = 60f

    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00E5FF"); style = Paint.Style.STROKE
        strokeWidth = 3f; strokeCap = Paint.Cap.ROUND
    }
    private val cuePostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6600E5FF"); style = Paint.Style.STROKE
        strokeWidth = 2f; strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val objectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFAB40"); style = Paint.Style.STROKE
        strokeWidth = 3f; strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
    }
    private val bouncePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF5252"); style = Paint.Style.FILL
    }
    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4400C853"); style = Paint.Style.FILL
    }
    private val pocketStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6600C853"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val markerCueFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEFFFFFF"); style = Paint.Style.FILL
    }
    private val markerCueStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00E5FF"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val markerTargetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEFF5252"); style = Paint.Style.FILL
    }
    private val markerTargetStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFAB40"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val ghostBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#30FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val pocketedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00C853"); textSize = 28f
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80000000"); textSize = 18f
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        if (!isAimVisible) return
        cuePaint.strokeWidth = lineThickness
        cuePostPaint.strokeWidth = lineThickness * 0.7f
        objectPaint.strokeWidth = lineThickness

        val pockets = engine.getPocketPositions(tableBounds, ballRadius)
        val pocketRadius = ballRadius * 2.5f
        for (p in pockets) {
            canvas.drawCircle(p.x, p.y, pocketRadius, pocketPaint)
            canvas.drawCircle(p.x, p.y, pocketRadius, pocketStrokePaint)
        }

        val result = engine.computeFullTrajectory(
            cuePos, targetPos, tableBounds = tableBounds,
            ballRadius = ballRadius, maxBounces = maxBounces, maxPathLength = 4000f
        )

        if (result.cuePath.isNotEmpty()) {
            drawSeg(canvas, result.cuePath[0], cuePaint)
            for (i in 1 until result.cuePath.size) {
                drawSeg(canvas, result.cuePath[i], cuePostPaint)
                canvas.drawCircle(result.cuePath[i].start.x, result.cuePath[i].start.y, 4f, bouncePaint)
            }
        }
        for (seg in result.objectPath) drawSeg(canvas, seg, objectPaint)
        for (i in 1 until result.objectPath.size) {
            canvas.drawCircle(result.objectPath[i].start.x, result.objectPath[i].start.y, 4f, bouncePaint)
        }

        if (result.cuePath.isNotEmpty()) {
            val ce = result.cuePath[0].end
            if (ce.distanceTo(targetPos) < ballRadius * 3f)
                canvas.drawCircle(ce.x, ce.y, ballRadius, ghostBallPaint)
        }
        if (result.pocketed && result.pocketIndex in pockets.indices) {
            val p = pockets[result.pocketIndex]
            canvas.drawText("✓", p.x, p.y - pocketRadius - 8f, pocketedTextPaint)
        }

        drawMarker(canvas, cuePos, markerCueFill, markerCueStroke, "CUE")
        drawMarker(canvas, targetPos, markerTargetFill, markerTargetStroke, "OBJ")
    }

    private fun drawSeg(canvas: Canvas, s: PathSegment, p: Paint) {
        canvas.drawLine(s.start.x, s.start.y, s.end.x, s.end.y, p)
    }

    private fun drawMarker(canvas: Canvas, pos: Vec2, fill: Paint, stroke: Paint, label: String) {
        val r = ballRadius * 1.5f
        canvas.drawCircle(pos.x, pos.y, r, fill)
        canvas.drawCircle(pos.x, pos.y, r, stroke)
        canvas.drawText(label, pos.x, pos.y + 6f, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isAimVisible) return false
        val x = event.x; val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val tv = Vec2(x, y)
                when {
                    tv.distanceTo(cuePos) <= touchSlop -> { draggingCue = true; return true }
                    tv.distanceTo(targetPos) <= touchSlop -> { draggingTarget = true; return true }
                    else -> return false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingCue) { cuePos = Vec2(x, y); invalidate(); onMarkersChanged?.invoke(); return true }
                if (draggingTarget) { targetPos = Vec2(x, y); invalidate(); onMarkersChanged?.invoke(); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingCue || draggingTarget) { draggingCue = false; draggingTarget = false; return true }
            }
        }
        return false
    }
}
