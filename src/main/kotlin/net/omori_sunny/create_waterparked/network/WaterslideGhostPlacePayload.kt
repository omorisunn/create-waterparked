package net.omori_sunny.create_waterparked.network
// Ghost block placement payload with server validation (client to server).

import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.track.CoasterTrackGauge
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.GhostBlockEntry
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.Optional

class WaterslideGhostPlacePayload(
    val curveA: BlockPos,
    val curveB: BlockPos,
    val cell: BlockPos,
    val stack: ItemStack?,
    val t: Float,
    val angle: Float
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
            val storageBe = level.getBlockEntity(curve.bePositions.getFirst()) as? WaterslideAnchorBlockEntity
                ?: return@enqueueWork
            val peer = curve.bePositions.getSecond()
            val held = stack ?: return@enqueueWork
            if (held.item !is net.minecraft.world.item.BlockItem) return@enqueueWork

            if (!level.getBlockState(resolveSubLevelPos(level, cell)).canBeReplaced()) return@enqueueWork

            if (storageBe.ghostBlockCount(peer) >= ModConfig.maxGhostBlocksPerCurve()) return@enqueueWork
            if (storageBe.ghostBlockAtCell(peer, cell) != null) return@enqueueWork
            val config = storageBe.sectorConfigFor(peer)
            val placed = WaterslideSectorLayout.place(config)
            val sector = WaterslideSectorLayout.sectorAt(placed, angle) ?: return@enqueueWork
            if (sector.sector.material != SectorMaterial.BLOCK && sector.sector.material != SectorMaterial.OPEN) return@enqueueWork

            val entry = GhostBlockEntry.of(
                id = storageBe.nextGhostId(peer),
                cell = cell,
                stack = held,
                t = t,
                angle = angle
            ) ?: return@enqueueWork
            if (!WaterslideAnchorBlockEntity.commitGhostBlock(level, curve, entry)) return@enqueueWork

            val a = curve.bePositions.getFirst()
            val b = curve.bePositions.getSecond()
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
        val TYPE: CustomPacketPayload.Type<WaterslideGhostPlacePayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_ghost_place")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideGhostPlacePayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, WaterslideGhostPlacePayload::curveA,
                BlockPos.STREAM_CODEC, WaterslideGhostPlacePayload::curveB,
                BlockPos.STREAM_CODEC, WaterslideGhostPlacePayload::cell,
                ByteBufCodecs.optional(ItemStack.STREAM_CODEC), WaterslideGhostPlacePayload::optionalStack,
                ByteBufCodecs.FLOAT, WaterslideGhostPlacePayload::t,
                ByteBufCodecs.FLOAT, WaterslideGhostPlacePayload::angle,
                { a, b, cell, stack, t, angle ->
                    WaterslideGhostPlacePayload(a, b, cell, stack.orElse(null), t, angle)
                }
            )
    }
}

private fun WaterslideGhostPlacePayload.optionalStack(): Optional<ItemStack> =
    Optional.ofNullable(stack)