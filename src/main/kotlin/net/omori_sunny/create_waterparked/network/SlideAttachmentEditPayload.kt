package net.omori_sunny.create_waterparked.network

import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity

// client to server: a stop-distance endpoint drag was committed
class SlideAttachmentEditPayload(
    val bePos: BlockPos,
    val kind: String,
    val value: Float
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        val player = ctx.player() as? ServerPlayer ?: return
        ctx.enqueueWork { apply(player) }
    }

    private fun apply(player: ServerPlayer) {
        val level = player.level() as? ServerLevel ?: return
        if (!player.canInteractWithBlock(bePos, 64.0)) return
        val be = level.getBlockEntity(bePos) as? SlideAttachmentBlockEntity ?: return
        val entry = be.entry ?: return
        when (kind) {
            "stopL", "stopR" -> {
                val key = if (kind == "stopL") "DoorStopL" else "DoorStopR"
                entry.data.putFloat(key, value.coerceIn(0.5f, 5.0f))
            }
            "t" -> {
                entry.t = value.coerceIn(0.02f, 0.98f)
            }
            else -> return
        }
        be.notifyBlockUpdated()
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<SlideAttachmentEditPayload> =
            CustomPacketPayload.Type(
                ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "slide_attachment_edit")
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SlideAttachmentEditPayload> =
            StreamCodec.of(
                { buf, p ->
                    buf.writeBlockPos(p.bePos)
                    buf.writeUtf(p.kind, 16)
                    buf.writeFloat(p.value)
                },
                { buf -> SlideAttachmentEditPayload(buf.readBlockPos(), buf.readUtf(16), buf.readFloat()) }
            )
    }
}
