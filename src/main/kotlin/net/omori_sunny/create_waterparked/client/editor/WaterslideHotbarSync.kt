package net.omori_sunny.create_waterparked.client.editor

import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackItem
import net.omori_sunny.create_waterparked.game.WaterslideTrackPlacement
import net.omori_sunny.create_waterparked.network.WaterslideAnchorClearPayload
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.network.PacketDistributor

// Clear selection on hotbar switch.
@OnlyIn(Dist.CLIENT)
object WaterslideHotbarSync {

    private var lastSlot = -1

    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            lastSlot = -1
            return
        }
        val selected = player.inventory.selected
        if (lastSlot >= 0 && selected != lastSlot) {
            val prev = player.inventory.getItem(lastSlot)
            if (prev.item is WaterslideTrackItem && WaterslideTrackPlacement.hasAnchorFirstSelection(prev)) {
                WaterslideTrackPlacement.clearPendingConnection(prev)
                PacketDistributor.sendToServer(WaterslideAnchorClearPayload())
            }
        }
        lastSlot = selected
    }
}
