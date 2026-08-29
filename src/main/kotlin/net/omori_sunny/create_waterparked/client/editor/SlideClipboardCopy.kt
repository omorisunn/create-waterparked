package net.omori_sunny.create_waterparked.client.editor

import com.simibubi.create.content.equipment.clipboard.ClipboardBlockItem
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.network.WaterslideSlideCopyPayload
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.network.PacketDistributor

// Copy the sector config of the slide the cursor points at: the same
// eye-ray interaction as the dye, so right clicks work on the tube body
// instead of requiring a hit on the anchor block.
object SlideClipboardCopy {

    @JvmStatic
    fun onUseItemKey(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem) return
        // the paste mode owns the use key while active
        if (event.isCanceled) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (player.mainHandItem.item !is ClipboardBlockItem) return
        val wall = WaterslideSectorEdit.pickWallAtCursor(mc) ?: return
        val raw = wall.curve
        val primary = if (raw.isPrimary) raw else raw.secondary()
        if (!WaterslideTrackMaterials.isWaterslide(primary)) return
        event.setCanceled(true)
        event.setSwingHand(true)
        PacketDistributor.sendToServer(
            WaterslideSlideCopyPayload(
                primary.bePositions.getFirst(),
                primary.bePositions.getSecond()
            )
        )
    }
}
