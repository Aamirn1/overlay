package com.poolaim.overlay.cv

import android.graphics.Bitmap
import android.graphics.RectF
import com.poolaim.overlay.physics.Vec2
import kotlin.math.abs
import kotlin.math.max

/**
 * Pure-Kotlin Bitmap computer vision for 8 Ball Pool overlay detection.
 *
 * All detection runs on a downscaled capture (~360p) for performance.
 * No native libraries required.
 *
 * Detection strategy:
 *  1. White cue ball   → largest dense cluster of near-white pixels in the table area.
 *  2. Aim line         → longest connected run of near-white pixels that forms a line,
 *                        anchored near the cue ball center.
 *  3. Object ball      → first non-green, non-black, non-background dense cluster
 *                        that lies along the detected aim ray.
 */
object BitmapCv {

    // ── Colour thresholds ──────────────────────────────────────────────────── //
    private const val WHITE_THRESH = 210          // R,G,B all above this → white
    private const val WHITE_DIFF   = 25           // max channel difference for white
    private const val GREEN_G_MIN  = 100          // felt green: G dominates
    private const val GREEN_RATIO  = 1.3f         // G / max(R,B) > this

    // ── Sampling ──────────────────────────────────────────────────────────── //
    private const val BALL_SCAN_STEP = 4          // pixel stride for ball search
    private const val LINE_SCAN_STEP = 2          // pixel stride for line detection
    private const val CLUSTER_RADIUS = 8          // px — radius to accumulate votes

    // ─────────────────────────────────────────────────────────────────────── //

    /**
     * Full analysis pass on one bitmap.
     * [tableRect] must be in the coordinate space of [bmp] (already scaled-down).
     */
    fun analyze(bmp: Bitmap, tableRect: RectF): DetectionResult {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)

        val cue = findWhiteBall(pixels, bmp.width, bmp.height, tableRect)
            ?: return DetectionResult.EMPTY

        val line = findAimLine(pixels, bmp.width, bmp.height, tableRect, cue)
            ?: return DetectionResult(cue, null, null, 0.3f)

        val target = findObjectBall(pixels, bmp.width, bmp.height, tableRect, cue, line)

        // Confidence: have cue=0.3, +line=0.4, +target=0.3
        val confidence = 0.3f + 0.4f + if (target != null) 0.3f else 0f
        return DetectionResult(cue, line, target, confidence)
    }

    // ── Cue ball (white ball) detection ───────────────────────────────────── //

    private fun findWhiteBall(
        pixels: IntArray, w: Int, h: Int, table: RectF
    ): Vec2? {
        val l = table.left.toInt().coerceIn(0, w - 1)
        val t = table.top.toInt().coerceIn(0, h - 1)
        val r = table.right.toInt().coerceIn(0, w - 1)
        val b = table.bottom.toInt().coerceIn(0, h - 1)

        // Accumulation grid of cell size CLUSTER_RADIUS
        val cellW = (r - l) / CLUSTER_RADIUS + 1
        val cellH = (b - t) / CLUSTER_RADIUS + 1
        val votes = IntArray(cellW * cellH)

        var y = t
        while (y <= b) {
            var x = l
            while (x <= r) {
                val px = pixels[y * w + x]
                if (isNearWhite(px)) {
                    val cx = (x - l) / CLUSTER_RADIUS
                    val cy = (y - t) / CLUSTER_RADIUS
                    votes[cy * cellW + cx]++
                }
                x += BALL_SCAN_STEP
            }
            y += BALL_SCAN_STEP
        }

        // Find the cell with most white pixels
        var bestVote = 8              // minimum threshold to count
        var bestCx = -1; var bestCy = -1
        for (cy in 0 until cellH) {
            for (cx in 0 until cellW) {
                val v = votes[cy * cellW + cx]
                if (v > bestVote) {
                    bestVote = v; bestCx = cx; bestCy = cy
                }
            }
        }
        if (bestCx < 0) return null

        val px = l + bestCx * CLUSTER_RADIUS + CLUSTER_RADIUS / 2f
        val py = t + bestCy * CLUSTER_RADIUS + CLUSTER_RADIUS / 2f
        return Vec2(px, py)
    }

    // ── Aim line detection ────────────────────────────────────────────────── //

    /**
     * Sample rays in 36 directions from near the cue ball.
     * The direction with the longest continuous white-pixel run wins.
     */
    private fun findAimLine(
        pixels: IntArray, w: Int, h: Int, table: RectF, cueBall: Vec2
    ): Vec2? {
        val l = table.left.toInt().coerceIn(0, w - 1)
        val t = table.top.toInt().coerceIn(0, h - 1)
        val r = table.right.toInt().coerceIn(0, w - 1)
        val b = table.bottom.toInt().coerceIn(0, h - 1)

        val cx = cueBall.x; val cy = cueBall.y
        val maxRayLen = max(r - l, b - t).toFloat()

        var bestLen = 20f     // minimum line length (px) to count
        var bestDir = Vec2.ZERO

        // 36 directions × 5° each
        for (angle in 0 until 36) {
            val rad = Math.toRadians(angle * 5.0)
            val dx = Math.cos(rad).toFloat()
            val dy = Math.sin(rad).toFloat()

            var runLen = 0f
            var inRun = false
            var step = 20f          // start sampling 20 px ahead of the ball

            while (step < maxRayLen) {
                val sx = (cx + dx * step).toInt()
                val sy = (cy + dy * step).toInt()
                if (sx < l || sx > r || sy < t || sy > b) break

                val px = pixels[sy * w + sx]
                if (isNearWhite(px)) {
                    inRun = true
                    runLen += LINE_SCAN_STEP.toFloat()
                } else if (inRun) {
                    // Allow a 6px gap before deciding the run ended
                    val peekX = (cx + dx * (step + 6)).toInt()
                    val peekY = (cy + dy * (step + 6)).toInt()
                    if (peekX < l || peekX > r || peekY < t || peekY > b ||
                        !isNearWhite(pixels[peekY * w + peekX])) {
                        break  // run ended
                    }
                }
                step += LINE_SCAN_STEP
            }

            if (runLen > bestLen) {
                bestLen = runLen
                bestDir = Vec2(dx, dy)
            }
        }

        return if (bestDir == Vec2.ZERO) null else bestDir.normalized()
    }

    // ── Object-ball detection ─────────────────────────────────────────────── //

    /**
     * Walk along the aim ray from the cue ball.
     * The first dense cluster of non-white, non-green, non-black pixels is the object ball.
     */
    private fun findObjectBall(
        pixels: IntArray, w: Int, h: Int, table: RectF,
        cueBall: Vec2, aimDir: Vec2
    ): Vec2? {
        val l = table.left.toInt().coerceIn(0, w - 1)
        val t = table.top.toInt().coerceIn(0, h - 1)
        val r = table.right.toInt().coerceIn(0, w - 1)
        val b = table.bottom.toInt().coerceIn(0, h - 1)

        val cx = cueBall.x; val cy = cueBall.y
        val maxRayLen = max(r - l, b - t).toFloat()

        // Sample pixels along the ray and also ±10px perpendicular
        val perp = Vec2(-aimDir.y, aimDir.x)

        var step = 25f
        while (step < maxRayLen) {
            val rx = cx + aimDir.x * step
            val ry = cy + aimDir.y * step

            var ballPixels = 0
            for (offset in -10..10 step 3) {
                val sx = (rx + perp.x * offset).toInt()
                val sy = (ry + perp.y * offset).toInt()
                if (sx < l || sx > r || sy < t || sy > b) continue
                val px = pixels[sy * w + sx]
                if (!isNearWhite(px) && !isGreen(px) && !isNearBlack(px)) {
                    ballPixels++
                }
            }

            if (ballPixels >= 4) {   // Enough non-background pixels → ball found
                return Vec2(rx, ry)
            }
            step += LINE_SCAN_STEP
        }
        return null
    }

    // ── Colour classifiers ────────────────────────────────────────────────── //

    private fun isNearWhite(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r > WHITE_THRESH && g > WHITE_THRESH && b > WHITE_THRESH &&
               abs(r - g) < WHITE_DIFF && abs(g - b) < WHITE_DIFF && abs(r - b) < WHITE_DIFF
    }

    private fun isGreen(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return g > GREEN_G_MIN && g.toFloat() > max(r, b) * GREEN_RATIO
    }

    private fun isNearBlack(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r < 40 && g < 40 && b < 40
    }
}
