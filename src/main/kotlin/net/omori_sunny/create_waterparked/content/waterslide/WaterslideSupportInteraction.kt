package net.omori_sunny.create_waterparked.content.waterslide

import com.simibubi.create.AllItems
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LevelEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Server half of the copycat-style support material interaction.
//
// The client ray-picks the rendered Flywheel support geometry (which has no
// block hitbox) and reports the hovered anchor+part. That short-lived state is
// used ONLY to cancel the server's RightClickBlock event for the real block
// behind the support, so vanilla placement cannot happen through the beam.
// The actual material operation is sent as an explicit apply payload.
object WaterslideSupportInteraction {

    const val HOVER_TTL_TICKS = 40L

    data class Hover(val anchor: BlockPos, val part: WaterslideSupportPart, val gameTime: Long)

    private val hovers = ConcurrentHashMap<UUID, Hover>()

    @JvmStatic
    fun setHover(player: ServerPlayer, anchor: BlockPos, part: WaterslideSupportPart?, gameTime: Long) {
        if (part == null) {
            hovers.remove(player.uuid)
        } else {
            hovers[player.uuid] = Hover(anchor.immutable(), part, gameTime)
        }
    }

    @JvmStatic
    fun onPlayerLoggedOut(event: net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent) {
        hovers.remove(event.entity.uuid)
    }

    @JvmStatic
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (event.level.isClientSide) return
        val level = event.level as? ServerLevel ?: return
        val player = event.entity as? Player ?: return
        val hover = hovers.remove(player.uuid) ?: return
        if (level.gameTime - hover.gameTime > HOVER_TTL_TICKS) return
        if (level.getBlockEntity(hover.anchor) !is WaterslideAnchorBlockEntity) return
        if (!canInteract(player.getItemInHand(event.hand))) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
    }

    @JvmStatic
    fun applyFromPayload(
        player: ServerPlayer,
        anchor: BlockPos,
        part: WaterslideSupportPart,
        hand: InteractionHand,
        face: Direction
    ): Boolean {
        val level = player.serverLevel()
        val be = level.getBlockEntity(anchor) as? WaterslideAnchorBlockEntity ?: return false
        return apply(level, player, be, part, face, hand)
    }

    private fun canInteract(stack: ItemStack): Boolean =
        !stack.isEmpty && (AllItems.WRENCH.isIn(stack) || stack.item is net.minecraft.world.item.BlockItem)

    private fun apply(
        level: Level,
        player: Player,
        be: WaterslideAnchorBlockEntity,
        part: WaterslideSupportPart,
        face: Direction,
        hand: InteractionHand
    ): Boolean {
        val stack = player.getItemInHand(hand)

        // Wrench: copycat reset - give the consumed item back and restore the
        // default copycat_base look.
        if (AllItems.WRENCH.isIn(stack)) {
            val returned = be.resetSupportMaterial(part)
            if (returned.isEmpty) return false
            if (!player.isCreative) player.inventory.placeItemBackInInventory(returned)
            level.levelEvent(
                LevelEvent.PARTICLES_DESTROY_BLOCK,
                be.blockPos,
                Block.getId(be.blockState)
            )
            return true
        }

        val accepted = WaterslideSupportMaterials.acceptedBlockState(level, be.blockPos, stack, face)
            ?: return false

        val current = be.supportMaterial(part)
        if (current.`is`(accepted.block)) {
            // Same material again: cycle orientation/axis properties like copycat.
            if (!be.cycleSupportMaterial(part)) return false
            level.playSound(
                null, be.blockPos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.75f, 0.95f
            )
            return true
        }

        if (be.hasCustomSupportMaterial(part)) return false

        be.setSupportMaterial(part, accepted, stack)
        level.playSound(
            null, be.blockPos, accepted.soundType.placeSound, SoundSource.BLOCKS, 1f, 0.75f
        )

        if (player.isCreative) return true
        stack.shrink(1)
        if (stack.isEmpty) player.setItemInHand(hand, ItemStack.EMPTY)
        return true
    }
}
