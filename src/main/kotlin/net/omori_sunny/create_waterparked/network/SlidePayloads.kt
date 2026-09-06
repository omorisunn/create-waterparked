package net.omori_sunny.create_waterparked.network

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.SlideClientSession
import net.omori_sunny.create_waterparked.game.physics.PlayerSlideController
import net.omori_sunny.create_waterparked.game.physics.SlideSample
import java.util.UUID

data class SlideSampleWire(
    val time: Float,
    val cx: Float,
    val cy: Float,
    val cz: Float,
    val tcx: Float,
    val tcy: Float,
    val tcz: Float,
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val ux: Float,
    val uy: Float,
    val uz: Float,
    val radius: Float,
    val speed: Float,
    val inTube: Boolean,
    val watered: Boolean
) {
    fun toSample(offset: Vec3? = null): SlideSample =
        SlideSample(
            time.toDouble(),
            offsetOrZero(cx, cy, cz, offset),
            offsetOrZero(tcx, tcy, tcz, offset),
            Vec3(tx.toDouble(), ty.toDouble(), tz.toDouble()),
            Vec3(ux.toDouble(), uy.toDouble(), uz.toDouble()),
            radius,
            speed.toDouble(),
            inTube,
            watered
        )

    private fun offsetOrZero(x: Float, y: Float, z: Float, offset: Vec3?): Vec3 =
        if (offset == null) Vec3(x.toDouble(), y.toDouble(), z.toDouble())
        else Vec3(x + offset.x, y + offset.y, z + offset.z)

    companion object {
        // positions are relative to the plot center for float32
        fun from(s: SlideSample, offset: Vec3? = null): SlideSampleWire =
            SlideSampleWire(
                s.time.toFloat(),
                relCoord(s.center.x, offset?.x),
                relCoord(s.center.y, offset?.y),
                relCoord(s.center.z, offset?.z),
                relCoord(s.tubeCenter.x, offset?.x),
                relCoord(s.tubeCenter.y, offset?.y),
                relCoord(s.tubeCenter.z, offset?.z),
                s.tangent.x.toFloat(), s.tangent.y.toFloat(), s.tangent.z.toFloat(),
                s.up.x.toFloat(), s.up.y.toFloat(), s.up.z.toFloat(),
                s.radius,
                s.speed.toFloat(),
                s.inTube,
                s.watered
            )

        private fun relCoord(value: Double, offset: Double?): Float =
            (value - (offset ?: 0.0)).toFloat()
    }
}

// Full trajectory, sent once when a ride starts.
class SlideTrajectoryPayload(
    val sessionId: Long,
    val startTick: Long,
    val swimmingPose: Boolean,
    val subLevelId: UUID?,
    val contraptionEntityId: Int?,
    val samples: List<SlideSampleWire>
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnClient(ctx: IPayloadContext) {
        ctx.enqueueWork {
            SlideClientSession.start(this)
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<SlideTrajectoryPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "slide_trajectory")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SlideTrajectoryPayload> =
            StreamCodec.of(
                { buf, p ->
                    buf.writeLong(p.sessionId)
                    buf.writeLong(p.startTick)
                    buf.writeBoolean(p.swimmingPose)
                    buf.writeNullableUuid(p.subLevelId)
                    buf.writeNullableInt(p.contraptionEntityId)
                    buf.writeSamples(p.samples)
                },
                { buf ->
                    val sessionId = buf.readLong()
                    val startTick = buf.readLong()
                    val swimming = buf.readBoolean()
                    val subLevelId = buf.readNullableUuid()
                    val contraptionEntityId = buf.readNullableInt()
                    SlideTrajectoryPayload(
                        sessionId, startTick, swimming, subLevelId, contraptionEntityId, buf.readSamples()
                    )
                }
            )
    }
}

// Trajectory segment appended when the slide switches into another space.
class SlideSegmentPayload(
    val sessionId: Long,
    val startTick: Long,
    val subLevelId: UUID?,
    val contraptionEntityId: Int?,
    val samples: List<SlideSampleWire>
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnClient(ctx: IPayloadContext) {
        ctx.enqueueWork { SlideClientSession.appendSegment(this) }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<SlideSegmentPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "slide_segment")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SlideSegmentPayload> =
            StreamCodec.of(
                { buf, p ->
                    buf.writeLong(p.sessionId)
                    buf.writeLong(p.startTick)
                    buf.writeNullableUuid(p.subLevelId)
                    buf.writeNullableInt(p.contraptionEntityId)
                    buf.writeSamples(p.samples)
                },
                { buf ->
                    val sessionId = buf.readLong()
                    val startTick = buf.readLong()
                    val subLevelId = buf.readNullableUuid()
                    val contraptionEntityId = buf.readNullableInt()
                    SlideSegmentPayload(
                        sessionId, startTick, subLevelId, contraptionEntityId, buf.readSamples()
                    )
                }
            )
    }
}

// Client requests an early exit.
class SlideCancelPayload(val sessionId: Long) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            PlayerSlideController.onCancel(player, sessionId)
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<SlideCancelPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "slide_cancel")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SlideCancelPayload> =
            StreamCodec.of(
                { buf, p -> buf.writeLong(p.sessionId) },
                { buf -> SlideCancelPayload(buf.readLong()) }
            )
    }
}

// Server ends the ride; client clears its playback state.
class SlideEndPayload(
    val sessionId: Long,
    val reason: Byte,
    val x: Float,
    val y: Float,
    val z: Float,
    val vx: Float,
    val vy: Float,
    val vz: Float
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnClient(ctx: IPayloadContext) {
        ctx.enqueueWork {
            SlideClientSession.end(this)
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<SlideEndPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "slide_end")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SlideEndPayload> =
            StreamCodec.of(
                { buf, p ->
                    buf.writeLong(p.sessionId)
                    buf.writeByte(p.reason.toInt())
                    buf.writeFloat(p.x)
                    buf.writeFloat(p.y)
                    buf.writeFloat(p.z)
                    buf.writeFloat(p.vx)
                    buf.writeFloat(p.vy)
                    buf.writeFloat(p.vz)
                },
                { buf ->
                    SlideEndPayload(
                        buf.readLong(), buf.readByte(),
                        buf.readFloat(), buf.readFloat(), buf.readFloat(),
                        buf.readFloat(), buf.readFloat(), buf.readFloat()
                    )
                }
            )
    }
}

// Periodic server time correction; timeScale carries attachment speed
// effects (e.g. a closing door braking the rider).
class SlideSyncPayload(val sessionId: Long, val elapsedTicks: Int, val timeScale: Float = 1f) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnClient(ctx: IPayloadContext) {
        ctx.enqueueWork {
            SlideClientSession.sync(sessionId, elapsedTicks, timeScale)
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<SlideSyncPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "slide_sync")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SlideSyncPayload> =
            StreamCodec.of(
                { buf, p ->
                    buf.writeLong(p.sessionId)
                    buf.writeInt(p.elapsedTicks)
                    buf.writeFloat(p.timeScale)
                },
                { buf -> SlideSyncPayload(buf.readLong(), buf.readInt(), buf.readFloat()) }
            )
    }
}

// Server-side helper for sending slide packets.
object SlidePackets {
    @JvmStatic
    fun sendTo(player: ServerPlayer, payload: CustomPacketPayload) {
        PacketDistributor.sendToPlayer(player, payload)
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

private fun RegistryFriendlyByteBuf.writeSamples(samples: List<SlideSampleWire>) =
    writeCollection(samples) { b, s ->
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

private fun RegistryFriendlyByteBuf.readSamples(): List<SlideSampleWire> =
    readCollection({ ArrayList() }) { b ->
        SlideSampleWire(
            b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(),
            b.readFloat(), b.readFloat(), b.readFloat(),
            b.readFloat(), b.readFloat(), b.readFloat(),
            b.readFloat(), b.readFloat(), b.readFloat(),
            b.readFloat(), b.readFloat(),
            b.readBoolean(), b.readBoolean()
        )
    }
