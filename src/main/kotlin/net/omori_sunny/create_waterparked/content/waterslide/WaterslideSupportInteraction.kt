package net.omori_sunny.create_waterparked.content.waterslide

import com.simibubi.create.AllItems
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LevelEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.omori_sunny.create_waterparked.CreateWaterparked
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// server half of the copycat style support material interaction
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
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        hovers.remove(event.entity.uuid)
    }

    @JvmStatic
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (event.level.isClientSide) return
        val level = event.level as? ServerLevel ?: return
        val player = event.entity as? Player ?: return
        val hover = hovers.remove(player.uuid) ?: return
        if (level.gameTime - hover.gameTime > HOVER_TTL_TICKS) return
        if (resolveAnchorBe(level, hover.anchor) == null) return
        if (!canInteract(player.getItemInHand(event.hand))) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
    }

    // the item-use stage is where a held BLOCK would be placed: swallow it while
    // a support click is being processed so right-clicking the beam/bracket never
    // drops a block into the world. The first of the two handlers wins the hover
    // entry; a canceled block event already skips the item use on the server.
    @JvmStatic
    fun onRightClickItem(event: PlayerInteractEvent.RightClickItem) {
        if (event.level.isClientSide) return
        val level = event.level as? ServerLevel ?: return
        val player = event.entity as? Player ?: return
        val hover = hovers.remove(player.uuid) ?: return
        if (level.gameTime - hover.gameTime > HOVER_TTL_TICKS) return
        if (resolveAnchorBe(level, hover.anchor) == null) return
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
        // Sable plot payloads carry the plot-local anchor position of the
        // picked BE (SupportPick.anchorPos = be.getBlockPos()); resolve it the
        // same way WaterslideAnchorInteraction does: direct hit first, then the
        // plot-center offset fallback over every server sub level
        val be = resolveAnchorBe(level, anchor)
        if (be == null) {
            // failure-only warn: an unresolvable anchor means the click never
            // produces a visible action - keep that diagnosable without spamming
            CreateWaterparked.LOGGER.warn("[SupportApply] anchor miss: {}", anchor)
            return false
        }
        val ok = apply(level, player, be, part, face, hand)
        CreateWaterparked.LOGGER.debug(
            "[SupportApply] anchor={} part={} item={} ok={} deleted={} filled={}",
            be.blockPos, part, player.getItemInHand(hand).item.descriptionId, ok,
            !be.isSupportVisible(part), be.hasCustomSupportMaterial(part)
        )
        return ok
    }

    // main world first (sub=null), then the plot-center offset mapping used by
    // every other sublevel-aware interaction in this mod
    private fun resolveAnchorBe(level: ServerLevel, pos: BlockPos): WaterslideAnchorBlockEntity? {
        (level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity)?.let { return it }
        val container = SubLevelContainer.getContainer(level) ?: return null
        var found: WaterslideAnchorBlockEntity? = null
        for (raw in container.allSubLevels) {
            val sub = raw as? ServerSubLevel ?: continue
            val candidate = pos.offset(sub.getPlot().getCenterBlock())
            (level.getBlockEntity(candidate) as? WaterslideAnchorBlockEntity)?.let { found = it }
        }
        return found
    }

    private fun canInteract(stack: ItemStack): Boolean =
        !stack.isEmpty && (AllItems.WRENCH.isIn(stack) || stack.item is BlockItem ||
            stack.item is AxeItem)

    private fun apply(
        level: Level,
        player: Player,
        be: WaterslideAnchorBlockEntity,
        part: WaterslideSupportPart,
        face: Direction,
        hand: InteractionHand
    ): Boolean {
        val stack = player.getItemInHand(hand)
        val deleted = !be.isSupportVisible(part)
        val filled = be.hasCustomSupportMaterial(part)
        // deleted part: only the wrench may restore it
        // filled part: only the wrench may act; empty part: the wrench is rejected
        val wrench = AllItems.WRENCH.isIn(stack)
        val allowed = if (deleted) wrench else filled == wrench
        if (!allowed) return false

        // axe deletes the support part, the wrench restores it
        if (stack.item is AxeItem) {
            if (!be.isSupportVisible(part)) return false
            be.setSupportVisible(part, false)
            return true
        }

        // wrench reset: return the consumed item and restore the default look
        if (AllItems.WRENCH.isIn(stack)) {
            if (!be.isSupportVisible(part)) {
                be.setSupportVisible(part, true)
                return true
            }
            // nothing to clear: the part already uses the default look
            if (!be.hasCustomSupportMaterial(part)) return false
            val returned = be.resetSupportMaterial(part)
            if (!returned.isEmpty && !player.isCreative) {
                player.inventory.placeItemBackInInventory(returned)
            }
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
