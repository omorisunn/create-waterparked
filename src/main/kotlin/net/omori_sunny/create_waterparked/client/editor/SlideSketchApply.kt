package net.omori_sunny.create_waterparked.client.editor

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.omori_sunny.create_waterparked.content.registry.ModItems
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideSketchData
import net.omori_sunny.create_waterparked.network.SlideSketchApplyPayload

object SlideSketchApply {

    @JvmStatic
    fun onUseItemKey(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem) return
        if (event.isCanceled) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (player.mainHandItem.item !== ModItems.SLIDE_SKETCH) return
        val wall = WaterslideSectorEdit.pickWallAtCursor(mc) ?: return
        val raw = wall.curve
        val primary = if (raw.isPrimary) raw else raw.secondary() ?: return
        if (!WaterslideTrackMaterials.isWaterslide(primary)) return
        val clearing = player.isShiftKeyDown || SlideSketchData.of(player.mainHandItem) == null
        event.setCanceled(true)
        event.setSwingHand(true)
        if (!clearing) {
            player.displayClientMessage(
                Component.translatable("create_waterparked.sketch.applied"), true
            )
        }
        PacketDistributor.sendToServer(
            SlideSketchApplyPayload(
                primary.bePositions.getFirst(),
                primary.bePositions.getSecond(),
                clearing
            )
        )
    }
}
