package net.omori_sunny.create_waterparked.game.physics

import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.companion.math.JOMLConversion
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

// block and coordinate access for main or sub level slide space
interface SlideSpaceAccess {
    val level: ServerLevel
    val space: SlideSpace
    fun getBlockEntity(pos: BlockPos): BlockEntity?
    fun getBlockState(pos: BlockPos): BlockState
    fun toWorld(local: Vec3): Vec3
    fun toWorldNormal(local: Vec3): Vec3
    fun worldToLocal(world: Vec3): Vec3
    fun worldNormalToLocal(world: Vec3): Vec3
    fun worldVelocityAt(localPos: Vec3): Vec3
    fun localGravity(): Vec3
}

class MainSlideSpaceAccess(override val level: ServerLevel) : SlideSpaceAccess {
    override val space: SlideSpace = SlideSpace.Main
    private val gravity = Vec3(0.0, -32.0, 0.0)
    override fun getBlockEntity(pos: BlockPos): BlockEntity? = level.getBlockEntity(pos)
    override fun getBlockState(pos: BlockPos): BlockState = level.getBlockState(pos)
    override fun toWorld(local: Vec3): Vec3 = local
    override fun toWorldNormal(local: Vec3): Vec3 = local.normalize()
    override fun worldToLocal(world: Vec3): Vec3 = world
    override fun worldNormalToLocal(world: Vec3): Vec3 = world
    override fun worldVelocityAt(localPos: Vec3): Vec3 = Vec3.ZERO
    override fun localGravity(): Vec3 = gravity
}

class SubSlideSpaceAccess(
    override val level: ServerLevel,
    val sub: ServerSubLevel
) : SlideSpaceAccess {
    override val space: SlideSpace = SlideSpace.SubLevel(sub.uniqueId)
    private val gravity: Vec3 by lazy { worldNormalToLocal(Vec3(0.0, -32.0, 0.0)) }

    // sub level blocks sit at plot global positions, treated as local
    override fun getBlockEntity(pos: BlockPos): BlockEntity? = level.getBlockEntity(pos)
    override fun getBlockState(pos: BlockPos): BlockState = level.getBlockState(pos)

    override fun toWorld(local: Vec3): Vec3 {
        val out = sub.logicalPose().transformPosition(JOMLConversion.toJOML(local), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    override fun toWorldNormal(local: Vec3): Vec3 {
        val out = sub.logicalPose().transformNormal(JOMLConversion.toJOML(local), Vector3d())
        return JOMLConversion.toMojang(out).normalize()
    }

    override fun worldToLocal(world: Vec3): Vec3 {
        val out = sub.logicalPose().transformPositionInverse(JOMLConversion.toJOML(world), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    override fun worldNormalToLocal(world: Vec3): Vec3 {
        val out = sub.logicalPose().transformNormalInverse(JOMLConversion.toJOML(world), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    override fun localGravity(): Vec3 = gravity

    override fun worldVelocityAt(localPos: Vec3): Vec3 =
        Sable.HELPER.getVelocity(level, sub, localPos)
}
