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
import java.util.UUID

// server to client water field sync for one slide space
class WaterslideWaterSyncPayload(
    val entries: List<Entry>,
    val subLevelId: UUID? = null,
    val contraptionEntityId: Int? = null
) : CustomPacketPayload {

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
        CreateWaterparked.LOGGER.info(
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

        private const val MAX_SEGMENTS_PER_ENTRY = 4096
        private const val MAX_ENTRIES = 256

        private fun <T> nullableCodec(
            write: (RegistryFriendlyByteBuf, T) -> Unit,
            read: (RegistryFriendlyByteBuf) -> T
        ): StreamCodec<RegistryFriendlyByteBuf, T?> = StreamCodec.of(
            { buf, v ->
                buf.writeBoolean(v != null)
                if (v != null) write(buf, v)
            },
            { buf -> if (buf.readBoolean()) read(buf) else null }
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
            SEGMENT_CODEC.apply(ByteBufCodecs.list(MAX_SEGMENTS_PER_ENTRY)), Entry::segments,
            nullableCodec({ buf, v -> VEC3_CODEC.encode(buf, v) }, { buf -> VEC3_CODEC.decode(buf) }), Entry::exitPos,
            nullableCodec({ buf, v -> VEC3_CODEC.encode(buf, v) }, { buf -> VEC3_CODEC.decode(buf) }), Entry::exitVel,
            ::Entry
        )

        private val NULLABLE_UUID_CODEC: StreamCodec<RegistryFriendlyByteBuf, UUID?> =
            nullableCodec({ buf, v -> buf.writeUUID(v) }, { buf -> buf.readUUID() })

        private val NULLABLE_INT_CODEC: StreamCodec<RegistryFriendlyByteBuf, Int?> =
            nullableCodec({ buf, v -> buf.writeVarInt(v) }, { buf -> buf.readVarInt() })

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideWaterSyncPayload> =
            StreamCodec.composite(
                ENTRY_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), WaterslideWaterSyncPayload::entries,
                NULLABLE_UUID_CODEC, WaterslideWaterSyncPayload::subLevelId,
                NULLABLE_INT_CODEC, WaterslideWaterSyncPayload::contraptionEntityId,
                ::WaterslideWaterSyncPayload
            )
    }
}
