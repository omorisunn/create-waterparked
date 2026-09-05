package net.omori_sunny.create_waterparked.network

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.BoatRideClient

// high-rate boat pose sync while a player rides: vanilla packets arrive at the
// entity's tracking rate but the client applies them through a 3-tick lerp,
// which riders feel as stutter; this arrives every server tick and the client
// snaps to it in a single lerp step
class BoatSyncPayload(
    val entityId: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnClient(ctx: IPayloadContext) {
        ctx.enqueueWork { BoatRideClient.sync(this) }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<BoatSyncPayload> =
            CustomPacketPayload.Type(
                ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "boat_sync")
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BoatSyncPayload> =
            StreamCodec.of(
                { buf, p ->
                    buf.writeVarInt(p.entityId)
                    buf.writeDouble(p.x)
                    buf.writeDouble(p.y)
                    buf.writeDouble(p.z)
                    buf.writeFloat(p.yaw)
                    buf.writeFloat(p.pitch)
                },
                { buf ->
                    BoatSyncPayload(
                        buf.readVarInt(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readFloat(), buf.readFloat()
                    )
                }
            )
    }
}
