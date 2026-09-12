package net.omori_sunny.create_waterparked.ponder

import net.createmod.ponder.foundation.PonderScene
import net.createmod.ponder.foundation.instruction.TickingInstruction
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import net.omori_sunny.create_waterparked.content.attachment.door.MechanicalDoorAttachment

// client only; occupies exactly the given scene ticks
class PonderDoorSweepInstruction(
    private val door: BlockPos,
    private val from: Float,
    private val to: Float,
    private val mode: Int,
    ticks: Int
) : TickingInstruction(true, ticks.coerceAtLeast(1)) {

    override fun firstTick(scene: PonderScene) {
        super.firstTick(scene)
        write(scene, from)
    }

    override fun tick(scene: PonderScene) {
        super.tick(scene)
        val progress = 1f - remainingTicks.toFloat() / totalTicks
        val eased = progress * progress * (3f - 2f * progress)
        write(scene, from + (to - from) * eased)
    }

    // must stay in sync with WaterslidePonderScene.setDoor
    private fun write(scene: PonderScene, open: Float) {
        val be = scene.world.getBlockEntity(door) as? SlideAttachmentBlockEntity ?: return
        val registries = scene.world.registryAccess()
        val tag = be.saveWithFullMetadata(registries)
        tag.putInt("ScrollValue", mode)
        val data = CompoundTag()
        data.putFloat(MechanicalDoorAttachment.TAG_OPEN, open)
        data.putInt(MechanicalDoorAttachment.TAG_MODE, mode)
        tag.put("Data", data)
        be.loadWithComponents(tag, registries)
    }
}
