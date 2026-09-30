package net.omori_sunny.create_waterparked.content.roller

import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.companion.math.JOMLConversion
import dev.ryanhcode.sable.companion.math.Pose3d
import net.createmod.catnip.levelWrappers.WrappedLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

// the pull a deck feels, read in the deck's own frame so a tilted run lets its loads slide
object RollerDeckGravity {

    // this mod works in blocks per second, so the world's own g is 32 of them, the same value slide space uses
    private val WORLD_GRAVITY: Vec3 = Vec3(0.0, -32.0, 0.0)

    // a sable plot turns the world's own pull into plot coordinates, while a contraption frame is owned by its movement behaviour and falls back as is
    fun localGravity(level: Level, pos: BlockPos): Vec3 {
        if (level is WrappedLevel) return WORLD_GRAVITY
        return localGravity(Sable.HELPER.getContaining(level, pos)?.logicalPose())
    }

    // the same pull for a caller that already holds the run's pose, so a tick that read it once reuses it
    fun localGravity(pose: Pose3d?): Vec3 {
        if (pose == null) return WORLD_GRAVITY
        val out = pose.transformNormalInverse(JOMLConversion.toJOML(WORLD_GRAVITY), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    // a deck is always placed level, so its travel axis is horizontal and only the flat part of the pull drives it
    fun accelerationAlong(localGravity: Vec3, facing: Direction): Float =
        (localGravity.x * facing.stepX + localGravity.z * facing.stepZ).toFloat()
}
