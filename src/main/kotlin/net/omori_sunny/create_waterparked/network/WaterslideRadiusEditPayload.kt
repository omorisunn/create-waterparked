package net.omori_sunny.create_waterparked.network

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.silvergold.simulatedcoasters.track.CoasterTrackGauge
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
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
            val anchor = resolveAnchor(level, anchorPos) ?: return@enqueueWork
            val range = CoasterTrackGauge.maxCoasterCurvePacketInteractionRangeBlocks().toDouble()
            if (!player.canInteractWithBlock(anchor.globalPos, range)) return@enqueueWork
            anchor.be.setRadius(radius)
        }
    }

    private data class AnchorTarget(val be: WaterslideAnchorBlockEntity, val globalPos: BlockPos)

    private fun resolveAnchor(level: ServerLevel, pos: BlockPos): AnchorTarget? {
        (level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity)?.let {
            return AnchorTarget(it, pos)
        }
        val subLevels = SubLevelContainer.getContainer(level)?.allSubLevels ?: return null
        var last: AnchorTarget? = null
        for (raw in subLevels) {
            val sub = raw as? ServerSubLevel ?: continue
            val candidate = pos.offset(sub.getPlot().getCenterBlock())
            val be = level.getBlockEntity(candidate) as? WaterslideAnchorBlockEntity ?: continue
            last = AnchorTarget(be, candidate)
        }
        return last
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
