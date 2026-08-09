package net.omori_sunny.create_waterparked.content.waterslide

import com.simibubi.create.AllSoundEvents
import dev.silvergold.simulatedcoasters.client.track.BezierHandleClientSounds
import net.omori_sunny.create_waterparked.game.WaterslideConnectionRules
import net.omori_sunny.create_waterparked.game.WaterslideTrackPlacement
import net.omori_sunny.create_waterparked.network.WaterslideAnchorFirstPayload
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.network.PacketDistributor

// Slide connection tool, mirroring the CCS item state machine.
class WaterslideTrackItem(properties: Properties) : Item(properties) {

    override fun isFoil(stack: ItemStack): Boolean =
        WaterslideTrackPlacement.hasAnchorFirstSelection(stack)

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val state = level.getBlockState(pos)
        val player = context.player ?: return InteractionResult.PASS
        if (context.hand == InteractionHand.OFF_HAND) return InteractionResult.PASS

        val stack = context.itemInHand
        val pending = WaterslideTrackPlacement.hasAnchorFirstSelection(stack)

        if (!pending) {
            if (state.block !is WaterslideAnchorBlock) {
                if (!level.isClientSide) {
                    player.displayClientMessage(
                        Component.translatable("create_waterparked.track.must_attach_to_slide_anchors")
                            .withStyle(ChatFormatting.RED),
                        true
                    )
                }
                return InteractionResult.FAIL
            }
            WaterslideTrackPlacement.applyAnchorFirstSelection(level, stack, pos, player, context.hand)
            if (level.isClientSide) {
                PacketDistributor.sendToServer(WaterslideAnchorFirstPayload(pos.immutable()))
            }
            return InteractionResult.SUCCESS
        }

        if (player.isShiftKeyDown) {
            WaterslideTrackPlacement.clearPendingConnection(stack)
            if (!level.isClientSide) {
                player.setItemInHand(context.hand, stack)
                if (player is ServerPlayer) {
                    WaterslideTrackPlacement.syncMainHand(player, context.hand, null)
                }
            }
            return InteractionResult.SUCCESS
        }

        val first = WaterslideTrackPlacement.readAnchorFirstSelection(stack)
        if (level.isClientSide) {
            if (first == null || first == pos || !WaterslideConnectionRules.validate(level, first, pos).valid) {
                AllSoundEvents.DENY.playFrom(player, 1.0f, 1.0f)
                return InteractionResult.FAIL
            }
            WaterslideTrackPlacement.clearPendingConnection(stack)
            player.setItemInHand(context.hand, stack)
            playConnectionCommitSound()
            return InteractionResult.SUCCESS
        }

        if (player is ServerPlayer) {
            if (first != null && first != pos) {
                WaterslideTrackPlacement.commitSecondAnchorOnServer(player, context.hand, first, pos)
            }
        }
        return InteractionResult.SUCCESS
    }

    @OnlyIn(Dist.CLIENT)
    private fun playConnectionCommitSound() {
        BezierHandleClientSounds.playCommitSuccessSound(Minecraft.getInstance())
    }
}
