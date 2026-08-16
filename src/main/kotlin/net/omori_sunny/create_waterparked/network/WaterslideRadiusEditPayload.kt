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
            var be = level.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity
            var globalPos = anchorPos
            if (be == null) {
                val container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)
                container?.allSubLevels?.forEach { raw ->
                    val sub = raw as? dev.ryanhcode.sable.sublevel.ServerSubLevel ?: return@forEach
                    val candidate = anchorPos.offset(sub.getPlot().getCenterBlock())
                    val found = level.getBlockEntity(candidate) as? WaterslideAnchorBlockEntity
                    if (found != null) {
                        be = found
                        globalPos = candidate
                    }
                }
            }
            if (be == null) return@enqueueWork
            val range = CoasterTrackGauge.maxCoasterCurvePacketInteractionRangeBlocks().toDouble()
            if (!player.canInteractWithBlock(globalPos, range)) {
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
