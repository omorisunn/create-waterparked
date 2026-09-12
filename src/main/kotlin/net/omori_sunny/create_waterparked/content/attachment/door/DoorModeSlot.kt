package net.omori_sunny.create_waterparked.content.attachment.door

import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform
import net.createmod.catnip.math.VecHelper
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlock

// excludes the two faces the shaft connects to, so the slot never lands inside it
class DoorModeSlot : CenteredSideValueBoxTransform({ state, side ->
    side.axis != state.getValue(RotatedPillarBlock.AXIS)
}) {
    // anchors on the hub face so it matches the pick ray, not the full block surface
    override fun getSouthLocation(): Vec3 =
        VecHelper.voxelSpace(8.0, 8.0, SlideAttachmentBlock.HUB_MAX_VOXEL)
}
