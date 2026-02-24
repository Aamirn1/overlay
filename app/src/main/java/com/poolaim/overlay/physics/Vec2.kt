package com.poolaim.overlay.physics

import kotlin.math.sqrt

/**
 * 2D Vector class for physics calculations.
 * Immutable - all operations return new instances.
 */
data class Vec2(val x: Float, val y: Float) {

    companion object {
        val ZERO = Vec2(0f, 0f)
    }

    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(scalar: Float) = Vec2(x * scalar, y * scalar)
    operator fun div(scalar: Float) = Vec2(x / scalar, y / scalar)

    fun dot(other: Vec2): Float = x * other.x + y * other.y

    fun lengthSquared(): Float = x * x + y * y

    fun length(): Float = sqrt(lengthSquared())

    fun normalized(): Vec2 {
        val len = length()
        return if (len > 0.0001f) Vec2(x / len, y / len) else ZERO
    }

    fun distanceTo(other: Vec2): Float = (this - other).length()

    /** Reflect this vector across a surface normal: v_out = v - 2(v·n)n */
    fun reflect(normal: Vec2): Vec2 {
        val d = 2f * this.dot(normal)
        return Vec2(x - d * normal.x, y - d * normal.y)
    }

    /** Project this vector onto another vector. */
    fun projectOnto(onto: Vec2): Vec2 {
        val denom = onto.dot(onto)
        if (denom < 0.0001f) return ZERO
        val scalar = this.dot(onto) / denom
        return onto * scalar
    }

    /** Perpendicular component: this - projection onto [onto]. */
    fun perpendicularTo(onto: Vec2): Vec2 {
        return this - projectOnto(onto)
    }

    fun rotate90(): Vec2 = Vec2(-y, x)
}
