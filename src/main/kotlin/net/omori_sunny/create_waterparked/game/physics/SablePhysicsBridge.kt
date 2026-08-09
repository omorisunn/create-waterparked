package net.omori_sunny.create_waterparked.game.physics

import dev.ryanhcode.sable.Sable
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level

// Sable physics bridge (detection only for now).
object SablePhysicsBridge {

    @JvmStatic
    fun isInPhysicsSubLevel(entity: Entity): Boolean =
        Sable.HELPER.getTrackingSubLevel(entity) != null

    @JvmStatic
    fun containingSubLevel(level: Level, pos: BlockPos): Any? =
        Sable.HELPER.getContaining(level, pos)

    @JvmStatic
    fun isSlideInsidePhysicsStructure(level: Level, anchorPos: BlockPos): Boolean =
        Sable.HELPER.getContaining(level, anchorPos) != null

    // TODO: wire into the Sable physics pipeline.
    fun registerPhysicsTickHandler() {
        // placeholder
    }
}
