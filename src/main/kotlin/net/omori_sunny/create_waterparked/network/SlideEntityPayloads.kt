package net.omori_sunny.create_waterparked.network

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.EntitySlideClientSessions
import java.util.UUID

// entity ride playback payloads; full trajectory, periodic sync, end
class SlideEntityTrajectoryPayload(
    val sessionId: Long,
    val entityId: Int,
    val startGameTime: Long,
    val subLevelId: UUID?,
    val contraptionEntityId: Int?,
    val samples: List<SlideSampleWire>
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnClient(ctx: IPayloadContext) {
        ctx.enqueueWork { EntitySlideClientSessions.start(this) }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<SlideEntityTrajectoryPayload> =
            CustomPacketPayload.Type(
                ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "slide_entity_trajectory")
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SlideEntityTrajectoryPayload> =
            StreamCodec.of(
                { buf, p ->
                    buf.writeLong(p.sessionId)
                    buf.writeVarInt(p.entityId)
                    buf.writeLong(p.startGameTime)
                    buf.writeNullableUuid(p.subLevelId)
                    buf.writeNullableInt(p.contraptionEntityId)
                    writeSamples(buf, p.samples)
                },
                { buf ->
                    SlideEntityTrajectoryPayload(
                        buf.readLong(), buf.readVarInt(), buf.readLong(),
                        buf.readNullableUuid(), buf.readNullableInt(), readSamples(buf)
                    )
                }
            )
    }
}

class SlideEntitySyncPayload(val sessionId: Long, val elapsedTicks: Int) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnClient(ctx: IPayloadContext) {
        ctx.enqueueWork { EntitySlideClientSessions.sync(sessionId, elapsedTicks) }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<SlideEntitySyncPayload> =
            CustomPacketPayload.Type(
                ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "slide_entity_sync")
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SlideEntitySyncPayload> =
            StreamCodec.of(
                { buf, p ->
                    buf.writeLong(p.sessionId)
                    buf.writeInt(p.elapsedTicks)
                },
                { buf -> SlideEntitySyncPayload(buf.readLong(), buf.readInt()) }
            )
    }
}

class SlideEntityEndPayload(val sessionId: Long) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnClient(ctx: IPayloadContext) {
        ctx.enqueueWork { EntitySlideClientSessions.end(sessionId) }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<SlideEntityEndPayload> =
            CustomPacketPayload.Type(
                ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "slide_entity_end")
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SlideEntityEndPayload> =
            StreamCodec.of(
                { buf, p -> buf.writeLong(p.sessionId) },
                { buf -> SlideEntityEndPayload(buf.readLong()) }
            )
    }
}

private fun RegistryFriendlyByteBuf.writeNullableUuid(uuid: UUID?) {
    writeBoolean(uuid != null)
    if (uuid != null) writeUUID(uuid)
}

private fun RegistryFriendlyByteBuf.readNullableUuid(): UUID? = if (readBoolean()) readUUID() else null

private fun RegistryFriendlyByteBuf.writeNullableInt(value: Int?) {
    writeBoolean(value != null)
    if (value != null) writeInt(value)
}

private fun RegistryFriendlyByteBuf.readNullableInt(): Int? = if (readBoolean()) readInt() else null

private fun writeSamples(buf: RegistryFriendlyByteBuf, samples: List<SlideSampleWire>) =
    buf.writeCollection(samples) { b, s ->
        b.writeFloat(s.time)
        b.writeFloat(s.cx)
        b.writeFloat(s.cy)
        b.writeFloat(s.cz)
        b.writeFloat(s.tcx)
        b.writeFloat(s.tcy)
        b.writeFloat(s.tcz)
        b.writeFloat(s.tx)
        b.writeFloat(s.ty)
        b.writeFloat(s.tz)
        b.writeFloat(s.ux)
        b.writeFloat(s.uy)
        b.writeFloat(s.uz)
        b.writeFloat(s.radius)
        b.writeFloat(s.speed)
        b.writeBoolean(s.inTube)
        b.writeBoolean(s.watered)
    }

private fun readSamples(buf: RegistryFriendlyByteBuf): List<SlideSampleWire> =
    buf.readCollection({ ArrayList() }) { b ->
        SlideSampleWire(
            b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(),
            b.readFloat(), b.readFloat(), b.readFloat(),
            b.readFloat(), b.readFloat(), b.readFloat(),
            b.readFloat(), b.readFloat(), b.readFloat(),
            b.readFloat(), b.readFloat(),
            b.readBoolean(), b.readBoolean()
        )
    }
