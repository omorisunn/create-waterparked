package net.omori_sunny.create_waterparked.network

import dev.silvergold.simulatedcoasters.track.CoasterTrackGauge
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

// Radius edit packet.
class WaterslideRadiusEditPayload(val anchorPos: BlockPos, val radius: Float) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() ?: return@enqueueWork
            if (player !is ServerPlayer) return@enqueueWork
            val level = player.serverLevel()
            val be = level.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity ?: return@enqueueWork
            val range = CoasterTrackGauge.maxCoasterCurvePacketInteractionRangeBlocks().toDouble()
            if (!player.canInteractWithBlock(anchorPos, range)) {
                return@enqueueWork
            }
            be.setRadius(radius)
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideRadiusEditPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_radius_edit")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideRadiusEditPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, WaterslideRadiusEditPayload::anchorPos,
            ByteBufCodecs.FLOAT, WaterslideRadiusEditPayload::radius,
            ::WaterslideRadiusEditPayload
        )
    }
}
