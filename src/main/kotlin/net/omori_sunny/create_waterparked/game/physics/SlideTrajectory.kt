package net.omori_sunny.create_waterparked.game.physics

import net.minecraft.world.phys.Vec3
import kotlin.math.acos
import kotlin.math.sin

enum class SlideEndReason {
    EXITED,
    STOPPED,
    BLOCKED,
    CANCELLED
}

const val SLIDE_WALL_THICKNESS = 0.1

data class SlideSample(
    val time: Double,
    val center: Vec3,
    val tubeCenter: Vec3,
    val tangent: Vec3,
    val up: Vec3,
    val radius: Float,
    val speed: Double,
    val inTube: Boolean = true
) {
    val position: Vec3
        get() = center
}

// One computed ride; samples are 3D positions in slide-local space.
class SlideTrajectory(
    val samples: List<SlideSample>,
    val endReason: SlideEndReason,
    val endIsOpenEnd: Boolean,
    val exitVelocity: Vec3 = Vec3.ZERO,
    val landedOnSlide: Boolean = false
) {

    val duration: Double
        get() = samples.lastOrNull()?.time ?: 0.0

    val exitPosition: Vec3
        get() = samples.lastOrNull()?.position ?: Vec3.ZERO

    val exitTangent: Vec3
        get() = samples.lastOrNull()?.tangent ?: Vec3(0.0, 1.0, 0.0)

    val exitSpeed: Double
        get() = samples.lastOrNull()?.speed ?: 0.0

    data class AtTime(val sample: SlideSample, val index: Int)

    fun sampleAt(time: Double): AtTime {
        if (samples.isEmpty()) {
            return AtTime(
                SlideSample(0.0, Vec3.ZERO, Vec3.ZERO, Vec3(0.0, 1.0, 0.0), Vec3(0.0, 1.0, 0.0), 1f, 0.0),
                0
            )
        }
        if (time <= 0.0) return AtTime(samples[0], 0)
        if (time >= samples.last().time) return AtTime(samples.last(), samples.size - 1)

        var lo = 0
        var hi = samples.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (samples[mid].time <= time) lo = mid else hi = mid
        }
        val a = samples[lo]
        val b = samples[hi]
        val span = b.time - a.time
        val f = if (span <= 1.0E-9) 0.0 else (time - a.time) / span
        val center = a.center.lerp(b.center, f)
        val unit = slerpUnit(a.tangent, b.tangent, f)
        val upB = if (a.up.dot(b.up) < 0.0) b.up.scale(-1.0) else b.up
        val up = slerpUnit(a.up, upB, f)
        val tubeCenter = a.tubeCenter.lerp(b.tubeCenter, f)
        val radius = a.radius + (b.radius - a.radius) * f.toFloat()
        val speed = a.speed + (b.speed - a.speed) * f
        val inTube = a.inTube && b.inTube
        return AtTime(SlideSample(time, center, tubeCenter, unit, up, radius, speed, inTube), lo)
    }

    private fun slerpUnit(a: Vec3, b: Vec3, f: Double): Vec3 {
        if (a.lengthSqr() < 1.0E-12) return if (b.lengthSqr() < 1.0E-12) Vec3(0.0, 1.0, 0.0) else b.normalize()
        if (b.lengthSqr() < 1.0E-12) return a.normalize()
        val dot = (a.dot(b) / (a.length() * b.length())).coerceIn(-1.0, 1.0)
        val omega = acos(dot)
        if (omega < 1.0E-6) return a.normalize()
        val sinOmega = sin(omega)
        if (sinOmega < 1.0E-6) return a.normalize()
        val wa = sin((1.0 - f) * omega) / sinOmega
        val wb = sin(f * omega) / sinOmega
        return a.scale(wa).add(b.scale(wb)).normalize()
    }
}
