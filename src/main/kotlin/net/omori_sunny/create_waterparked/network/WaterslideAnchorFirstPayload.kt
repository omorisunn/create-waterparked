package net.omori_sunny.create_waterparked.network

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlock
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackItem
import net.omori_sunny.create_waterparked.game.WaterslideTrackPlacement
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.neoforged.neoforge.network.handling.IPayloadContext

// First anchor selection sync.
class WaterslideAnchorFirstPayload(val anchor: BlockPos) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() ?: return@enqueueWork
            if (player !is ServerPlayer) return@enqueueWork
            val level = player.serverLevel()
            if (level.getBlockState(anchor).block !is WaterslideAnchorBlock) return@enqueueWork
            val be = level.getBlockEntity(anchor) as? WaterslideAnchorBlockEntity ?: return@enqueueWork
            if (be.legCount() >= 2) return@enqueueWork

            val stack = player.mainHandItem
            if (stack.item !is WaterslideTrackItem) return@enqueueWork
            val existing = WaterslideTrackPlacement.readAnchorFirstSelection(stack)
            if (existing != null && existing == anchor) return@enqueueWork
            WaterslideTrackPlacement.applyAnchorFirstSelection(
                level, stack, anchor, player, InteractionHand.MAIN_HAND
            )
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideAnchorFirstPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_anchor_first")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideAnchorFirstPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, WaterslideAnchorFirstPayload::anchor,
            ::WaterslideAnchorFirstPayload
        )
    }
}
