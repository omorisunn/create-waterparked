package net.omori_sunny.create_waterparked.content.waterslide

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent

// Water-bucket interaction for anchors inside Sable sub-levels. Sable gives
// the click a local content position while the block entity lives at a
// plot-global position, so the generic fluid-handler path never finds it.
object WaterslideAnchorInteraction {

    @JvmStatic
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (event.isCanceled) return
        val level = event.level as? ServerLevel ?: return
        val player = event.entity as? Player ?: return
        val held = player.getItemInHand(event.hand)
        if (held.item !== Items.WATER_BUCKET && held.item !== Items.BUCKET) return

        var be = level.getBlockEntity(event.pos) as? WaterslideAnchorBlockEntity
        var globalPos = event.pos
        if (be == null) {
            val container = SubLevelContainer.getContainer(level) ?: return
            for (raw in container.allSubLevels) {
                val sub = raw as? ServerSubLevel ?: continue
                val candidate = event.pos.offset(sub.getPlot().getCenterBlock())
                val found = level.getBlockEntity(candidate) as? WaterslideAnchorBlockEntity
                if (found != null) {
                    be = found
                    globalPos = candidate
                }
            }
        }
        if (be == null) return

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
