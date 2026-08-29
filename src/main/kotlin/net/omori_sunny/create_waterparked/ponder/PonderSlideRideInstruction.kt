package net.omori_sunny.create_waterparked.ponder

import com.simibubi.create.content.trains.track.BezierConnection
import net.createmod.ponder.foundation.PonderScene
import net.createmod.ponder.foundation.instruction.TickingInstruction
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * Capture-and-ride inside a Ponder scene: an entity spawned by the storyboard
 * (createEntity / slot in the schematic) follows the slide curve at a constant
 * block speed, with its position and rotation driven from the bezier tangent.
 * Progress is percentage based (0..1 of the curve length).
 */
class PonderSlideRideInstruction(
    private val entity: Entity,
    private val bc: BezierConnection,
    private val startProgress: Float,
    private val speedBlocksPerSecond: Float
) : TickingInstruction(false, 1) {

    private var progress = startProgress.coerceIn(0f, 1f)
    private var done = false

    override fun firstTick(scene: PonderScene) {
        placeAt(scene, progress)
    }

    override fun tick(scene: PonderScene) {
        // run every render tick - the instruction stays alive until the end
        if (done) return
        val dt = 1.0 / 20.0
        val arcLen = bezierLength()
        if (arcLen < 1.0E-4) {
            done = true
            return
        }
        progress = progress + (speedBlocksPerSecond * dt.toFloat() / arcLen.toFloat()).coerceAtMost(0.02f)
        if (progress >= 1f) {
            progress = 1f
            done = true
        }
        placeAt(scene, progress)
    }

    override fun isComplete(): Boolean = done

    private fun placeAt(scene: PonderScene, p: Float) {
        val t = p.toDouble().coerceIn(0.0, 1.0)
        val pos = bc.getPosition(t)
        val tan = dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
            .unitTangentAt(bc, p).normalize()
        entity.moveTo(pos.x, pos.y, pos.z)
        if (tan.lengthSqr() > 1.0E-8) {
            val yaw = Math.toDegrees(Math.atan2(tan.x, tan.z)).toFloat()
            val pitch = -Math.toDegrees(Math.asin((tan.y / Math.max(tan.length(), 1.0E-6)).coerceIn(-1.0, 1.0))).toFloat()
            entity.setYRot(yaw)
            entity.setXRot(pitch)
        }
    }

    private fun bezierLength(): Double {
        var sum = 0.0
        val steps = 32
        var prev = bc.getPosition(0.0)
        for (i in 1..steps) {
            val cur = bc.getPosition(i.toDouble() / steps)
            sum += cur.distanceTo(prev)
            prev = cur
        }
        return sum
    }
}
