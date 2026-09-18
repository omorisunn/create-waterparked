package net.omori_sunny.create_waterparked.content.waterslide

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.omori_sunny.create_waterparked.network.findSubLevelAnchor

// water bucket interaction for anchors inside Sable sub levels
object WaterslideAnchorInteraction {

    @JvmStatic
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (event.isCanceled) return
        val level = event.level as? ServerLevel ?: return
        val player = event.entity as? Player ?: return
        val held = player.getItemInHand(event.hand)
        if (held.item !== Items.WATER_BUCKET && held.item !== Items.BUCKET) return

        val anchorPos = findSubLevelAnchor(level, event.pos) ?: event.pos
        val be = level.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity ?: return

        if (held.item === Items.WATER_BUCKET) {
            be.refillWater()
            player.setItemInHand(event.hand, ItemStack(Items.BUCKET))
            player.swing(event.hand)
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
        } else if (player.isShiftKeyDown && be.hasWater()) {
            be.drainWater(1000)
            player.setItemInHand(event.hand, ItemStack(Items.WATER_BUCKET))
            player.swing(event.hand)
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
        }
    }
}
