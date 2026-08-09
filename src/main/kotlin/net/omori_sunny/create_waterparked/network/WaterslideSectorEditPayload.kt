package net.omori_sunny.create_waterparked.network

import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.track.CoasterTrackGauge
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.SectorType
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSector
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
import net.neoforged.neoforge.network.handling.IPayloadContext

enum class SectorEditAction {
    ADD_BLOCK,
    ADD_OPEN,
    DELETE,
    MOVE,
    // resize at a boundary
    RESIZE
}

// Sector edit packet.
class WaterslideSectorEditPayload(
    val curveA: BlockPos,
    val curveB: BlockPos,
    val action: SectorEditAction,
    val angleDegrees: Float,
    val blockId: ResourceLocation? = null,
    val sectorId: Int = -1
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() ?: return@enqueueWork
            if (player !is ServerPlayer) return@enqueueWork
            val level = player.serverLevel()
            if (!player.canInteractWithBlock(curveA, CoasterTrackGauge.maxCoasterCurvePacketInteractionRangeBlocks().toDouble())) {
                return@enqueueWork
            }

            val curve = findCurve(level, curveA, curveB) ?: return@enqueueWork
            val storageBe = level.getBlockEntity(curve.bePositions.getFirst()) as? WaterslideAnchorBlockEntity
                ?: return@enqueueWork
            val peer = curve.bePositions.getSecond()
            val config = storageBe.sectorConfigFor(peer)

            when (action) {
                SectorEditAction.ADD_BLOCK, SectorEditAction.ADD_OPEN -> {
                    CreateWaterparked.LOGGER.debug(
                        "WaterslideSectorEdit: server add {} at angle {} (sectors before={})",
                        action, angleDegrees, config.sectors.size
                    )
                    if (config.sectors.size >= ModConfig.maxSectors()) return@enqueueWork
                    val placed = WaterslideSectorLayout.place(config)
                    val insertIndex = WaterslideSectorLayout.insertionIndex(placed, angleDegrees)
                    val material = if (action == SectorEditAction.ADD_BLOCK) SectorMaterial.BLOCK else SectorMaterial.OPEN
                    val newSector = WaterslideSector(
                        id = config.newId(),
                        material = material,
                        blockId = if (action == SectorEditAction.ADD_BLOCK) blockId else null,
                        type = SectorType.AUTO,
                        widthDegrees = 0f
                    )
                    config.sectors.add(insertIndex, newSector)
                }

                SectorEditAction.DELETE -> {
                    val placed = WaterslideSectorLayout.place(config)
                    val target = WaterslideSectorLayout.sectorAt(placed, angleDegrees) ?: return@enqueueWork
                    config.sectors.removeAll { it.id == target.sector.id }
                }

                SectorEditAction.MOVE -> {
                    WaterslideSectorLayout.applyMove(config, sectorId, angleDegrees)
                }

                SectorEditAction.RESIZE -> {
                    WaterslideSectorLayout.applyBoundaryResize(config, sectorId, angleDegrees)
                }
            }

            WaterslideAnchorBlockEntity.commitSectorConfig(level, curve, config)

            // Send fresh BE data to the player.
            val a = curve.bePositions.getFirst()
            val b = curve.bePositions.getSecond()
            (level.getBlockEntity(a) as? WaterslideAnchorBlockEntity)
                ?.let { player.connection.send(ClientboundBlockEntityDataPacket.create(it)) }
            (level.getBlockEntity(b) as? WaterslideAnchorBlockEntity)
                ?.let { player.connection.send(ClientboundBlockEntityDataPacket.create(it)) }
        }
    }

    private fun findCurve(
        level: net.minecraft.world.level.Level,
        a: BlockPos,
        b: BlockPos
    ): BezierConnection? {
        val be = level.getBlockEntity(a) as? WaterslideAnchorBlockEntity ?: return null
        val raw = be.getAnchorPeerCurvesView()[b] ?: return null
        val primary = if (raw.isPrimary) raw else raw.secondary()
        return if (WaterslideTrackMaterials.isWaterslide(primary)) primary else null
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideSectorEditPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_sector_edit")
        )

        private val ACTION_CODEC: StreamCodec<io.netty.buffer.ByteBuf, SectorEditAction> =
            ByteBufCodecs.STRING_UTF8.map<SectorEditAction>(
                { name -> SectorEditAction.valueOf(name) },
                { action -> action.name }
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideSectorEditPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, WaterslideSectorEditPayload::curveA,
                BlockPos.STREAM_CODEC, WaterslideSectorEditPayload::curveB,
                ACTION_CODEC, WaterslideSectorEditPayload::action,
                ByteBufCodecs.FLOAT, WaterslideSectorEditPayload::angleDegrees,
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), WaterslideSectorEditPayload::optionalBlockId,
                ByteBufCodecs.INT, WaterslideSectorEditPayload::sectorId,
                { a, b, action, angle, block, sectorId ->
                    WaterslideSectorEditPayload(a, b, action, angle, block.orElse(null), sectorId)
                }
            )
    }
}

private fun WaterslideSectorEditPayload.optionalBlockId(): java.util.Optional<ResourceLocation> =
    java.util.Optional.ofNullable(blockId)
