package net.omori_sunny.create_waterparked.network

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.attachment.grab_bar.GrabBarAttachment

// client to server: which keys a player hanging on a grab bar is holding
class GrabBarInputPayload(
    val forward: Boolean,
    val backward: Boolean
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        val player = ctx.player() as? ServerPlayer ?: return
        ctx.enqueueWork { GrabBarAttachment.onClientInput(player, forward, backward) }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<GrabBarInputPayload> =
            CustomPacketPayload.Type(
                ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "grab_bar_input")
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, GrabBarInputPayload> =
            StreamCodec.of(
                { buf, p ->
                    buf.writeBoolean(p.forward)
                    buf.writeBoolean(p.backward)
                },
                { buf -> GrabBarInputPayload(buf.readBoolean(), buf.readBoolean()) }
            )
    }
}
