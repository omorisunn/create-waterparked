package net.omori_sunny.create_waterparked.network

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportInteraction
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

// Client -> server: which support part is under the player's cursor. The hover
// state lets the server-side RightClickBlock event cancel the vanilla block
// interaction behind the Flywheel support geometry. The material operation
// itself travels in WaterslideSupportApplyPayload.
class WaterslideSupportHoverPayload(val anchor: BlockPos, val partId: Byte) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val part = WaterslideSupportPart.entries.getOrNull(partId.toInt())
            WaterslideSupportInteraction.setHover(player, anchor, part, player.serverLevel().gameTime)
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideSupportHoverPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_support_hover")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideSupportHoverPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, WaterslideSupportHoverPayload::anchor,
                ByteBufCodecs.BYTE, WaterslideSupportHoverPayload::partId,
                ::WaterslideSupportHoverPayload
            )
    }
}