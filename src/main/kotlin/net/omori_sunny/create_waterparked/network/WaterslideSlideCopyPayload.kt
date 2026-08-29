package net.omori_sunny.create_waterparked.network

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.SlideClipboardInteraction
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

// Client to server: copy the sector config of the hovered curve into the held clipboard.
class WaterslideSlideCopyPayload(
    val anchor: BlockPos,
    val peer: BlockPos?
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            if (!SlideClipboardInteraction.isClipboard(player.mainHandItem)) return@enqueueWork
            SlideClipboardInteraction.copyToClipboard(player.serverLevel(), player, anchor, peer)
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideSlideCopyPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_slide_copy")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideSlideCopyPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, WaterslideSlideCopyPayload::anchor,
                ByteBufCodecs.optional(BlockPos.STREAM_CODEC), WaterslideSlideCopyPayload::optionalPeer,
                { anchor, peer -> WaterslideSlideCopyPayload(anchor, peer.orElse(null)) }
            )
    }
}

private fun WaterslideSlideCopyPayload.optionalPeer(): java.util.Optional<BlockPos> =
    java.util.Optional.ofNullable(peer)
