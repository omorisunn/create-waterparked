package net.omori_sunny.create_waterparked.network
// Ghost block removal payload (client to server).

import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.track.CoasterTrackGauge
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.neoforge.network.handling.IPayloadContext

class WaterslideGhostMinePayload(
    val curveA: BlockPos,
    val curveB: BlockPos,
    val cell: BlockPos
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() ?: return@enqueueWork
            if (player !is ServerPlayer) return@enqueueWork
            val level = player.serverLevel()
            val globalA = resolveSubLevelPos(level, curveA)
            val globalB = resolveSubLevelPos(level, curveB)
            if (!player.canInteractWithBlock(globalA, CoasterTrackGauge.maxCoasterCurvePacketInteractionRangeBlocks().toDouble())) {
                return@enqueueWork
            }

            val curve = findCurve(level, globalA, globalB) ?: return@enqueueWork
            val a = curve.bePositions.getFirst()
            val b = curve.bePositions.getSecond()
            if (!WaterslideAnchorBlockEntity.commitGhostBlockRemoval(level, a, b, cell)) return@enqueueWork

            (level.getBlockEntity(a) as? WaterslideAnchorBlockEntity)
                ?.let { player.connection.send(ClientboundBlockEntityDataPacket.create(it)) }
            (level.getBlockEntity(b) as? WaterslideAnchorBlockEntity)
                ?.let { player.connection.send(ClientboundBlockEntityDataPacket.create(it)) }
        }
    }

    private fun findCurve(
        level: Level,
        a: BlockPos,
        b: BlockPos
    ): BezierConnection? {
        val be = level.getBlockEntity(a) as? WaterslideAnchorBlockEntity ?: return null
        val raw = be.getAnchorPeerCurvesView()[b] ?: return null
        val primary = if (raw.isPrimary) raw else raw.secondary()
        return if (WaterslideTrackMaterials.isWaterslide(primary)) primary else null
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideGhostMinePayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_ghost_mine")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideGhostMinePayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, WaterslideGhostMinePayload::curveA,
                BlockPos.STREAM_CODEC, WaterslideGhostMinePayload::curveB,
                BlockPos.STREAM_CODEC, WaterslideGhostMinePayload::cell,
                { a, b, cell -> WaterslideGhostMinePayload(a, b, cell) }
            )
    }
}