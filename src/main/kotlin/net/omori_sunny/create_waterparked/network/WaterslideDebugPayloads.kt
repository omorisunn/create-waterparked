package net.omori_sunny.create_waterparked.network

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.omori_sunny.create_waterparked.game.water.ServerWaterSimulation
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext

// client -> server debug toggle
class WaterslideDebugRequestPayload(val enable: Boolean) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        val player = ctx.player() as? ServerPlayer ?: return
        ServerWaterSimulation.setDebug(player, enable)
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideDebugRequestPayload> =
            CustomPacketPayload.Type(
                ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "water_debug_request")
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideDebugRequestPayload> =
            StreamCodec.composite(
                ByteBufCodecs.BOOL, WaterslideDebugRequestPayload::enable,
                ::WaterslideDebugRequestPayload
            )
    }
}

// server -> client trajectory polylines for the debug overlay
class WaterslideDebugTrajectoryPayload(val polylines: List<List<Vec3>>) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnClient(ctx: IPayloadContext) {
        ctx.enqueueWork {
            WaterFlowSimulation.applyDebugTrajectories(polylines)
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideDebugTrajectoryPayload> =
            CustomPacketPayload.Type(
                ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "water_debug_trajectory")
            )

        private val POLYLINE_CODEC: StreamCodec<RegistryFriendlyByteBuf, List<Vec3>> =
            StreamCodec.of(
                { buf, pts ->
                    buf.writeVarInt(pts.size)
                    for (p in pts) {
                        buf.writeShort((p.x.coerceIn(-4096.0, 4096.0) * 16.0).toInt())
                        buf.writeShort((p.y.coerceIn(-4096.0, 4096.0) * 16.0).toInt())
                        buf.writeShort((p.z.coerceIn(-4096.0, 4096.0) * 16.0).toInt())
                    }
                },
                { buf ->
                    val n = buf.readVarInt().coerceIn(0, 4000)
                    val out = ArrayList<Vec3>(n)
                    repeat(n) {
                        out += Vec3(
                            buf.readShort() / 16.0,
                            buf.readShort() / 16.0,
                            buf.readShort() / 16.0
                        )
                    }
                    out
                }
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideDebugTrajectoryPayload> =
            StreamCodec.composite(
                POLYLINE_CODEC.apply(ByteBufCodecs.list(64)), WaterslideDebugTrajectoryPayload::polylines,
                ::WaterslideDebugTrajectoryPayload
            )
    }
}
