package com.poolaim.overlay.cv

import com.poolaim.overlay.physics.Vec2

/**
 * Result of one CV analysis frame.
 *
 * @param cueBallCenter   Detected white cue ball center in screen pixels, or null.
 * @param aimDirection    Normalized direction vector of the detected aim line, or null.
 * @param targetBallCenter Detected object-ball center in screen pixels, or null.
 * @param confidence      Overall detection quality in [0,1].
 *                        < 0.4 → overlay falls back to manual-marker mode.
 */
data class DetectionResult(
    val cueBallCenter: Vec2?,
    val aimDirection: Vec2?,
    val targetBallCenter: Vec2?,
    val confidence: Float
) {
    companion object {
        val EMPTY = DetectionResult(null, null, null, 0f)
    }
}
