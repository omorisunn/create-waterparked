package net.omori_sunny.create_waterparked.network

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.attachment.GrabBarHoldClient

// pinned hold state of one entity on a grab bar; a negative progress means released
class GrabBarHoldPayload(
    val entityId: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val progress: Float,
    val seated: Boolean,
    val armPitch: Float,
    val gripX: Double,
    val gripY: Double,
    val gripZ: Double
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnClient(ctx: IPayloadContext) {
        ctx.enqueueWork {
            GrabBarHoldClient.apply(
                entityId, x, y, z, yaw, progress, seated, armPitch, gripX, gripY, gripZ
            )
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<GrabBarHoldPayload> =
            CustomPacketPayload.Type(
                ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "grab_bar_hold")
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, GrabBarHoldPayload> =
            StreamCodec.of(
                { buf, p ->
                    buf.writeVarInt(p.entityId)
                    buf.writeDouble(p.x)
                    buf.writeDouble(p.y)
                    buf.writeDouble(p.z)
                    buf.writeFloat(p.yaw)
                    buf.writeFloat(p.progress)
                    buf.writeBoolean(p.seated)
                    buf.writeFloat(p.armPitch)
                    buf.writeDouble(p.gripX)
                    buf.writeDouble(p.gripY)
                    buf.writeDouble(p.gripZ)
                },
                { buf ->
                    GrabBarHoldPayload(
                        buf.readVarInt(), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(), buf.readFloat(), buf.readFloat(),
                        buf.readBoolean(), buf.readFloat(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble()
                    )
                }
            )
    }
}
