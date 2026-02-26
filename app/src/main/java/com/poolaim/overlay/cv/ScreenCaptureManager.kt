package com.poolaim.overlay.cv

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Log

/**
 * Manages the MediaProjection VirtualDisplay and ImageReader for screen capture.
 *
 * Captures at 1/3 of native resolution for performance (~360p on a 1080p device).
 * Call [latestBitmap] to get the most recent frame (non-blocking).
 */
class ScreenCaptureManager {

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val SCALE = 3           // capture at 1/SCALE resolution
        private const val MAX_IMAGES = 2      // ImageReader buffer size
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0

    /** Scale factor applied to detection results to convert back to screen coordinates. */
    var scaleToScreen = SCALE.toFloat()
        private set

    /**
     * Start capturing from [projection] at [screenWidth]×[screenHeight].
     * Must be called from any thread — ImageReader callbacks happen on a reader thread.
     */
    fun start(
        projection: MediaProjection,
        screenWidth: Int,
        screenHeight: Int,
        densityDpi: Int
    ) {
        stop() // tear down any existing session
        this.projection = projection
        captureWidth = screenWidth / SCALE
        captureHeight = screenHeight / SCALE
        scaleToScreen = SCALE.toFloat()

        imageReader = ImageReader.newInstance(
            captureWidth, captureHeight,
            PixelFormat.RGBA_8888,
            MAX_IMAGES
        )

        virtualDisplay = projection.createVirtualDisplay(
            "PoolAimCapture",
            captureWidth, captureHeight, densityDpi / SCALE,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
        Log.d(TAG, "Screen capture started at ${captureWidth}×${captureHeight}")
    }

    /**
     * Returns the latest captured bitmap, or null if no frame is available yet.
     * The caller is responsible for closing/recycling the returned bitmap.
     */
    fun latestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        return try {
            val image = reader.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val buffer = plane.buffer

            val bmp = Bitmap.createBitmap(
                captureWidth, captureHeight, Bitmap.Config.ARGB_8888
            )

            // Copy plane buffer respecting row stride
            if (rowStride == captureWidth * pixelStride) {
                bmp.copyPixelsFromBuffer(buffer)
            } else {
                // Slow path: copy row by row (uncommon but needed on some devices)
                val rowBuf = ByteArray(captureWidth * pixelStride)
                for (row in 0 until captureHeight) {
                    buffer.position(row * rowStride)
                    buffer.get(rowBuf, 0, rowBuf.size)
                    val rowBmp = Bitmap.createBitmap(captureWidth, 1, Bitmap.Config.ARGB_8888)
                    rowBmp.copyPixelsFromBuffer(
                        java.nio.ByteBuffer.wrap(rowBuf)
                    )
                    val pixels = IntArray(captureWidth)
                    rowBmp.getPixels(pixels, 0, captureWidth, 0, 0, captureWidth, 1)
                    bmp.setPixels(pixels, 0, captureWidth, 0, row, captureWidth, 1)
                    rowBmp.recycle()
                }
            }

            image.close()
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "Frame capture failed: ${e.message}")
            null
        }
    }

    /** Stop the virtual display and release the MediaProjection. */
    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
    }

    val isRunning get() = virtualDisplay != null
}
