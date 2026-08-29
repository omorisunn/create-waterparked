package net.omori_sunny.create_waterparked.network

import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.track.CoasterTrackGauge
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.SlideClipboardCodec
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import io.netty.buffer.ByteBuf
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.neoforge.network.handling.IPayloadContext

// Client to server: paste the encoded line onto the hovered curve.
class WaterslideSlidePastePayload(
    val curveA: BlockPos,
    val curveB: BlockPos,
    val line: String
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val level = player.serverLevel()
            val globalA = resolveSubLevelPos(level, curveA)
            val globalB = resolveSubLevelPos(level, curveB)
            if (!player.canInteractWithBlock(globalA, CoasterTrackGauge.maxCoasterCurvePacketInteractionRangeBlocks().toDouble())) {
                return@enqueueWork
            }
            val curve = findCurve(level, globalA, globalB) ?: run {
                message(player, "create_waterparked.clipboard.paste_bad", ChatFormatting.RED)
                return@enqueueWork
            }
            val parsed = SlideClipboardCodec.parse(line) ?: run {
                message(player, "create_waterparked.clipboard.paste_bad", ChatFormatting.RED)
                return@enqueueWork
            }

            // whole-config replace on both ends, the tube mesh rebuilds via NBT
            WaterslideAnchorBlockEntity.commitSectorConfig(level, curve, parsed.config)

            // fresh BE data for the player on both curve ends
            val a = curve.bePositions.getFirst()
            val b = curve.bePositions.getSecond()
            (level.getBlockEntity(a) as? WaterslideAnchorBlockEntity)
                ?.let { player.connection.send(ClientboundBlockEntityDataPacket.create(it)) }
            (level.getBlockEntity(b) as? WaterslideAnchorBlockEntity)
                ?.let { player.connection.send(ClientboundBlockEntityDataPacket.create(it)) }
            message(player, "create_waterparked.clipboard.paste_ok", ChatFormatting.GREEN)
        }
    }

    private fun message(player: ServerPlayer, key: String, color: ChatFormatting) {
        player.displayClientMessage(Component.translatable(key).withStyle(color), true)
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
        val TYPE: CustomPacketPayload.Type<WaterslideSlidePastePayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_slide_paste")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideSlidePastePayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, WaterslideSlidePastePayload::curveA,
                BlockPos.STREAM_CODEC, WaterslideSlidePastePayload::curveB,
                ByteBufCodecs.STRING_UTF8, WaterslideSlidePastePayload::line,
                ::WaterslideSlidePastePayload
            )
    }
}
