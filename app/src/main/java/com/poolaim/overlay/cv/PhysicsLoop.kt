package com.poolaim.overlay.cv

import android.graphics.RectF
import android.util.Log
import com.poolaim.overlay.view.AimOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coroutine-driven per-frame loop:
 *   capture → detect → compute physics (inside AimOverlayView) → redraw
 *
 * Runs at ~15 FPS on Dispatchers.Default to offload work from the main thread.
 * When detection confidence is low (< CONFIDENCE_THRESHOLD) it emits an EMPTY
 * result so the overlay smoothly falls back to showing manual markers.
 */
class PhysicsLoop(
    private val captureManager: ScreenCaptureManager,
    private val overlayView: AimOverlayView
) {
    companion object {
        private const val TAG = "PhysicsLoop"
        private const val FRAME_DELAY_MS = 66L          // ~15 FPS
        private const val CONFIDENCE_THRESHOLD = 0.4f
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    /** Current table bounds in SCREEN coordinates (not capture coordinates). */
    var tableBoundsScreen: RectF = RectF()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            Log.d(TAG, "Physics loop started")
            while (isActive) {
                tick()
                delay(FRAME_DELAY_MS)
            }
        }
    }

    private suspend fun tick() {
        // 1️⃣ Capture frame (background thread is fine for ImageReader)
        val bmp = captureManager.latestBitmap() ?: return

        // 2️⃣ Scale table bounds to capture resolution
        val scale = 1f / captureManager.scaleToScreen
        val scaledTable = RectF(
            tableBoundsScreen.left * scale,
            tableBoundsScreen.top * scale,
            tableBoundsScreen.right * scale,
            tableBoundsScreen.bottom * scale
        )

        // 3️⃣ Run CV analysis (CPU-bound, stays on Default dispatcher)
        val result = BitmapCv.analyze(bmp, scaledTable)
        bmp.recycle()

        // 4️⃣ Scale detection results back to screen coordinates
        val screenResult = if (result.confidence >= CONFIDENCE_THRESHOLD) {
            val s = captureManager.scaleToScreen
            DetectionResult(
                cueBallCenter   = result.cueBallCenter?.let  { it * s },
                aimDirection    = result.aimDirection,        // direction is scale-invariant
                targetBallCenter = result.targetBallCenter?.let { it * s },
                confidence      = result.confidence
            )
        } else {
            DetectionResult.EMPTY
        }

        // 5️⃣ Push to overlay on main thread
        withContext(Dispatchers.Main) {
            overlayView.updateFromDetection(screenResult)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope.cancel()
        Log.d(TAG, "Physics loop stopped")
    }
}

// ── Vec2 scale helper (avoids importing the whole math package here) ───────── //
private operator fun com.poolaim.overlay.physics.Vec2.times(s: Float) =
    com.poolaim.overlay.physics.Vec2(x * s, y * s)
