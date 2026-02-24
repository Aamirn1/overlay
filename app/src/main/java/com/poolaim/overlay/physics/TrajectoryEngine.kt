package com.poolaim.overlay.physics

import android.graphics.RectF
import kotlin.math.sqrt

data class CollisionResult(
    val contactPoint: Vec2,
    val cueOutDir: Vec2,
    val objectOutDir: Vec2,
    val cueStops: Boolean
)

data class PathSegment(val start: Vec2, val end: Vec2)

data class TrajectoryResult(
    val cuePath: List<PathSegment>,
    val objectPath: List<PathSegment>,
    val pocketed: Boolean,
    val pocketIndex: Int
)

/**
 * Core physics engine for billiard trajectory computation.
 * Handles ray-circle intersection, elastic collisions, cushion reflections,
 * multi-bounce tracing, and pocket detection.
 */
class TrajectoryEngine {

    companion object {
        val POCKET_RATIOS = arrayOf(
            0f to 0f, 0.5f to 0f, 1f to 0f,
            0f to 1f, 0.5f to 1f, 1f to 1f
        )
    }

    fun getPocketPositions(tableBounds: RectF, ballRadius: Float): List<Vec2> {
        return POCKET_RATIOS.map { (rx, ry) ->
            Vec2(
                tableBounds.left + rx * tableBounds.width(),
                tableBounds.top + ry * tableBounds.height()
            )
        }
    }

    fun checkPocket(point: Vec2, pockets: List<Vec2>, pocketRadius: Float): Int {
        for (i in pockets.indices) {
            if (point.distanceTo(pockets[i]) <= pocketRadius) return i
        }
        return -1
    }

    /**
     * Ray-circle intersection: find where cue ball hits target ball.
     * Returns cue ball center at moment of contact, or null if miss.
     */
    fun findBallCollision(
        origin: Vec2, direction: Vec2, targetCenter: Vec2, ballRadius: Float
    ): Vec2? {
        val collisionDist = ballRadius * 2f
        val oc = origin - targetCenter
        val a = direction.dot(direction)
        val b = 2f * oc.dot(direction)
        val c = oc.dot(oc) - collisionDist * collisionDist
        val discriminant = b * b - 4f * a * c
        if (discriminant < 0) return null
        val sqrtD = sqrt(discriminant)
        val t1 = (-b - sqrtD) / (2f * a)
        val t2 = (-b + sqrtD) / (2f * a)
        val t = when {
            t1 > 0.1f -> t1
            t2 > 0.1f -> t2
            else -> return null
        }
        return origin + direction * t
    }

    /**
     * Elastic collision between equal-mass balls.
     * Parallel component along line of centers is exchanged;
     * perpendicular component is retained by cue ball.
     */
    fun computeElasticCollision(
        cueVel: Vec2, cueContactPos: Vec2, targetPos: Vec2
    ): Pair<Vec2, Vec2> {
        val lineOfCenters = (targetPos - cueContactPos).normalized()
        val parallelComponent = cueVel.projectOnto(lineOfCenters)
        val perpComponent = cueVel.perpendicularTo(lineOfCenters)
        val objectOutVel = parallelComponent
        val cueOutVel = perpComponent
        return Pair(cueOutVel.normalized(), objectOutVel.normalized())
    }

    /**
     * Find where a ray hits the nearest rail. Returns (hitPoint, normal, distance).
     */
    fun findRailIntersection(
        origin: Vec2, direction: Vec2, tableBounds: RectF, ballRadius: Float
    ): Triple<Vec2, Vec2, Float>? {
        val left = tableBounds.left + ballRadius
        val right = tableBounds.right - ballRadius
        val top = tableBounds.top + ballRadius
        val bottom = tableBounds.bottom - ballRadius
        var minT = Float.MAX_VALUE
        var hitPoint = Vec2.ZERO
        var hitNormal = Vec2.ZERO

        if (direction.x < -0.0001f) {
            val t = (left - origin.x) / direction.x
            if (t > 0.5f) {
                val y = origin.y + t * direction.y
                if (y in top..bottom && t < minT) {
                    minT = t; hitPoint = Vec2(left, y); hitNormal = Vec2(1f, 0f)
                }
            }
        }
        if (direction.x > 0.0001f) {
            val t = (right - origin.x) / direction.x
            if (t > 0.5f) {
                val y = origin.y + t * direction.y
                if (y in top..bottom && t < minT) {
                    minT = t; hitPoint = Vec2(right, y); hitNormal = Vec2(-1f, 0f)
                }
            }
        }
        if (direction.y < -0.0001f) {
            val t = (top - origin.y) / direction.y
            if (t > 0.5f) {
                val x = origin.x + t * direction.x
                if (x in left..right && t < minT) {
                    minT = t; hitPoint = Vec2(x, top); hitNormal = Vec2(0f, 1f)
                }
            }
        }
        if (direction.y > 0.0001f) {
            val t = (bottom - origin.y) / direction.y
            if (t > 0.5f) {
                val x = origin.x + t * direction.x
                if (x in left..right && t < minT) {
                    minT = t; hitPoint = Vec2(x, bottom); hitNormal = Vec2(0f, -1f)
                }
            }
        }
        return if (minT < Float.MAX_VALUE) Triple(hitPoint, hitNormal, minT) else null
    }

    /** Trace a ball's path with rail reflections and pocket detection. */
    fun traceBallPath(
        startPos: Vec2, direction: Vec2, tableBounds: RectF, ballRadius: Float,
        maxBounces: Int, maxLength: Float, pockets: List<Vec2>, pocketRadius: Float
    ): Pair<List<PathSegment>, Int> {
        val segments = mutableListOf<PathSegment>()
        var currentPos = startPos
        var currentDir = direction.normalized()
        var remainingLength = maxLength

        for (i in 0..maxBounces) {
            val railHit = findRailIntersection(currentPos, currentDir, tableBounds, ballRadius)
                ?: break
            val (hitPoint, hitNormal, dist) = railHit
            val pocketIdx = checkPathForPocket(currentPos, hitPoint, pockets, pocketRadius)
            if (pocketIdx >= 0) {
                segments.add(PathSegment(currentPos, pockets[pocketIdx]))
                return Pair(segments, pocketIdx)
            }
            if (dist > remainingLength) {
                segments.add(PathSegment(currentPos, currentPos + currentDir * remainingLength))
                break
            }
            segments.add(PathSegment(currentPos, hitPoint))
            remainingLength -= dist
            if (i < maxBounces) {
                currentDir = currentDir.reflect(hitNormal)
                currentPos = hitPoint
            }
        }
        return Pair(segments, -1)
    }

    private fun checkPathForPocket(
        from: Vec2, to: Vec2, pockets: List<Vec2>, pocketRadius: Float
    ): Int {
        val segDir = to - from
        val segLen = segDir.length()
        if (segLen < 0.001f) return -1
        val segNorm = segDir / segLen
        for (i in pockets.indices) {
            val toPocket = pockets[i] - from
            val proj = toPocket.dot(segNorm)
            if (proj < 0 || proj > segLen) continue
            val closestPoint = from + segNorm * proj
            if (closestPoint.distanceTo(pockets[i]) <= pocketRadius) return i
        }
        return -1
    }

    /** Full trajectory: cue ball → target ball with both post-collision paths. */
    fun computeFullTrajectory(
        cuePos: Vec2, targetPos: Vec2, aimDir: Vec2? = null,
        tableBounds: RectF, ballRadius: Float,
        maxBounces: Int = 3, maxPathLength: Float = 3000f
    ): TrajectoryResult {
        val pockets = getPocketPositions(tableBounds, ballRadius)
        val pocketRadius = ballRadius * 2.5f
        val direction = (aimDir ?: (targetPos - cuePos)).normalized()
        val collisionPoint = findBallCollision(cuePos, direction, targetPos, ballRadius)

        val cueSegments = mutableListOf<PathSegment>()
        val objectSegments = mutableListOf<PathSegment>()
        var pocketed = false
        var pocketIdx = -1

        if (collisionPoint != null) {
            cueSegments.add(PathSegment(cuePos, collisionPoint))
            val (cueOutDir, objOutDir) = computeElasticCollision(direction, collisionPoint, targetPos)
            val cueStops = cueOutDir.lengthSquared() < 0.001f
            val (objPath, objPocket) = traceBallPath(
                targetPos, objOutDir, tableBounds, ballRadius, maxBounces, maxPathLength, pockets, pocketRadius
            )
            objectSegments.addAll(objPath)
            if (objPocket >= 0) { pocketed = true; pocketIdx = objPocket }
            if (!cueStops) {
                val (cuePath, _) = traceBallPath(
                    collisionPoint, cueOutDir, tableBounds, ballRadius, maxBounces, maxPathLength * 0.5f, pockets, pocketRadius
                )
                cueSegments.addAll(cuePath)
            }
        } else {
            val (cuePath, cuePocket) = traceBallPath(
                cuePos, direction, tableBounds, ballRadius, maxBounces, maxPathLength, pockets, pocketRadius
            )
            cueSegments.addAll(cuePath)
            if (cuePocket >= 0) { pocketed = true; pocketIdx = cuePocket }
        }
        return TrajectoryResult(cueSegments, objectSegments, pocketed, pocketIdx)
    }
}
