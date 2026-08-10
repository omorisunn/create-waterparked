package net.omori_sunny.create_waterparked.network

import dev.silvergold.simulatedcoasters.track.CoasterTrackGauge
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.game.WaterslideSectorBlockEdit
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

// Sector block edit packet.
class WaterslideSectorBlockEditPayload(
    val curveA: BlockPos,
    val curveB: BlockPos,
    val sectorId: Int,
    val blockId: ResourceLocation?
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() ?: return@enqueueWork
            if (player !is ServerPlayer) return@enqueueWork
            val level = player.serverLevel()
            val range = CoasterTrackGauge.maxCoasterCurvePacketInteractionRangeBlocks().toDouble()
            if (!player.canInteractWithBlock(curveA, range)) return@enqueueWork
            if (!WaterslideSectorBlockEdit.setSectorBlock(level, curveA, curveB, sectorId, blockId)) {
                return@enqueueWork
            }

// fresh BE data to the player
            for (pos in listOf(curveA, curveB)) {
                (level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity)
                    ?.let { player.connection.send(ClientboundBlockEntityDataPacket.create(it)) }
            }
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideSectorBlockEditPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_sector_block_edit")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideSectorBlockEditPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, WaterslideSectorBlockEditPayload::curveA,
                BlockPos.STREAM_CODEC, WaterslideSectorBlockEditPayload::curveB,
                ByteBufCodecs.INT, WaterslideSectorBlockEditPayload::sectorId,
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), WaterslideSectorBlockEditPayload::optionalBlockId,
                { a, b, id, block ->
                    WaterslideSectorBlockEditPayload(a, b, id, block.orElse(null))
                }
            )
    }
}

private fun WaterslideSectorBlockEditPayload.optionalBlockId(): java.util.Optional<ResourceLocation> =
    java.util.Optional.ofNullable(blockId)
