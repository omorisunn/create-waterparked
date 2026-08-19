package net.omori_sunny.create_waterparked.network

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportInteraction
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.neoforged.neoforge.network.handling.IPayloadContext

// Client -> server: explicit support interaction (the canceled client
// RightClickBlock never reaches the server, and RightClickEmpty has no block
// event at all). faceId = -1 falls back to the player's facing direction.
class WaterslideSupportApplyPayload(
    val anchor: BlockPos,
    val partId: Byte,
    val handId: Byte,
    val faceId: Byte
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val part = WaterslideSupportPart.entries.getOrNull(partId.toInt()) ?: return@enqueueWork
            val hand = InteractionHand.entries.getOrNull(handId.toInt()) ?: return@enqueueWork
            val face = Direction.entries.getOrNull(faceId.toInt())
                ?: Direction.orderedByNearest(player)[0]
            WaterslideSupportInteraction.applyFromPayload(player, anchor, part, hand, face)
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideSupportApplyPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_support_apply")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideSupportApplyPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, WaterslideSupportApplyPayload::anchor,
                ByteBufCodecs.BYTE, WaterslideSupportApplyPayload::partId,
                ByteBufCodecs.BYTE, WaterslideSupportApplyPayload::handId,
                ByteBufCodecs.BYTE, WaterslideSupportApplyPayload::faceId,
                ::WaterslideSupportApplyPayload
            )
    }
}
