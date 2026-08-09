package net.omori_sunny.create_waterparked.network

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.game.WaterslideTrackPlacement
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.neoforged.neoforge.network.handling.IPayloadContext

// Clear selection.
class WaterslideAnchorClearPayload : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() ?: return@enqueueWork
            if (player !is ServerPlayer) return@enqueueWork
            WaterslideTrackPlacement.clearServerTrackSelection(player, InteractionHand.MAIN_HAND)
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideAnchorClearPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_anchor_clear")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideAnchorClearPayload> =
            StreamCodec.of(
                { _, _ -> },
                { WaterslideAnchorClearPayload() }
            )
    }
}
