package net.omori_sunny.create_waterparked.network

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext

// server -> client water field sync
class WaterslideWaterSyncPayload(val entries: List<Entry>) : CustomPacketPayload {

    data class Entry(
        val edgeA: Long,
        val edgeB: Long,
        val segments: List<Segment>,
        val exitPos: Vec3?,
        val exitVel: Vec3?
    )

    data class Segment(
        val arc: Float,
        val speed: Float
    )

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnClient(ctx: IPayloadContext) {
        net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
            "Water payload arrived entries={}", entries.size
        )
        ctx.enqueueWork {
            WaterFlowSimulation.applySync(this)
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideWaterSyncPayload> =
            CustomPacketPayload.Type(
                ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "water_sync")
            )

        private fun <T> nullableVec3(
            codec: StreamCodec<RegistryFriendlyByteBuf, T>
        ): StreamCodec<RegistryFriendlyByteBuf, T?> = StreamCodec.of(
            { buf, v ->
                if (v != null) {
                    buf.writeBoolean(true)
                    codec.encode(buf, v)
                } else {
                    buf.writeBoolean(false)
                }
            },
            { buf -> if (buf.readBoolean()) codec.decode(buf) else null }
        )

        private val VEC3_CODEC: StreamCodec<RegistryFriendlyByteBuf, Vec3> = StreamCodec.of(
            { buf, v ->
                buf.writeDouble(v.x)
                buf.writeDouble(v.y)
                buf.writeDouble(v.z)
            },
            { buf -> Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) }
        )

        private val SEGMENT_CODEC: StreamCodec<RegistryFriendlyByteBuf, Segment> =
            StreamCodec.composite(
                ByteBufCodecs.FLOAT, Segment::arc,
                ByteBufCodecs.FLOAT, Segment::speed,
                ::Segment
            )

        private val ENTRY_CODEC: StreamCodec<RegistryFriendlyByteBuf, Entry> = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, Entry::edgeA,
            ByteBufCodecs.VAR_LONG, Entry::edgeB,
            SEGMENT_CODEC.apply(ByteBufCodecs.list(4096)), Entry::segments,
            nullableVec3(VEC3_CODEC), Entry::exitPos,
            nullableVec3(VEC3_CODEC), Entry::exitVel,
            ::Entry
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideWaterSyncPayload> =
            StreamCodec.composite(
                ENTRY_CODEC.apply(ByteBufCodecs.list(256)), WaterslideWaterSyncPayload::entries,
                ::WaterslideWaterSyncPayload
            )
    }
}
