package net.omori_sunny.create_waterparked.client.editor
// shared state between the CCS RivetPlacement mixin and our preview overrides

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

object WaterslideRivetClientCtx {

    // active synthetic target while the crosshair is on a waterslide tube wall
    class TubeTarget(
        @JvmField val anchor: BlockPos,
        @JvmField val point: Vec3,
        @JvmField val inward: Vec3,
        @JvmField val tangent: Vec3,
        @JvmField val hit: BlockHitResult
    ) {
        @JvmField val circum: Vec3 = inward.cross(tangent).normalize()
    }

    @JvmStatic
    var active: TubeTarget? = null
}
