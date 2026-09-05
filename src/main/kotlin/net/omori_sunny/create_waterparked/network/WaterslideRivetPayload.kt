package net.omori_sunny.create_waterparked.network
// Rivet placement/removal payload; the client sends only the wall hit point,
// the SERVER derives the curve-relative (t, angle) and places a REAL CCS
// RivetBlock bound to the slide anchor, so rendering, drops and wrenching all
// stay native.

import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.rivet.RivetBlock
import dev.silvergold.simulatedcoasters.rivet.RivetHostKey
import dev.silvergold.simulatedcoasters.rivet.RivetPlacement
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import dev.silvergold.simulatedcoasters.track.CoasterTrackGauge
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry

class WaterslideRivetPayload(
    val curveA: BlockPos,
    val curveB: BlockPos,
    val remove: Boolean,
    val hitX: Float,
    val hitY: Float,
    val hitZ: Float
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() ?: return@enqueueWork
            if (player !is ServerPlayer) return@enqueueWork
            val level = player.serverLevel()
            val globalA = resolveSubLevelPos(level, curveA)
            if (!player.canInteractWithBlock(globalA, CoasterTrackGauge.maxCoasterCurvePacketInteractionRangeBlocks().toDouble())) {
                return@enqueueWork
            }

            val curve = findCurve(level, globalA, resolveSubLevelPos(level, curveB)) ?: return@enqueueWork
            val storageBe = level.getBlockEntity(curve.bePositions.getFirst()) as? WaterslideAnchorBlockEntity
                ?: return@enqueueWork
            val hit = Vec3(hitX.toDouble(), hitY.toDouble(), hitZ.toDouble())

            if (remove) {
                val pos = rivetBlockNear(level, hit) ?: return@enqueueWork
                level.destroyBlock(pos, true)
            } else {
                val projected = projectHit(curve, hit) ?: return@enqueueWork
                val inward = radialAt(curve, projected.first, projected.second).scale(-1.0)
                // air cell adjacent to the tube inner wall, containing the hit
                val pos = BlockPos.containing(
                    hit.x + inward.x * 0.45, hit.y + inward.y * 0.45, hit.z + inward.z * 0.45
                )
                if (!level.getBlockState(pos).canBeReplaced()) return@enqueueWork
                val facing = Direction.getNearest(inward.x, inward.y, inward.z)
                val block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("simulatedcoasters", "rivet")
                )
                val state = block.defaultBlockState().setValue(RivetBlock.FACING, facing)
                level.setBlock(pos, state, 3)
                // bind the rivet to the slide anchor as its host so CCS keeps it
                (level.getBlockEntity(pos) as? dev.silvergold.simulatedcoasters.rivet.RivetBlockEntity)
                    ?.bindHost(
                        RivetHostKey.world(curve.bePositions.getFirst()), facing,
                        RivetPlacement.Attachment(0.5, 0.5, 0.5), projected.second.toInt()
                    )
                if (!player.abilities.instabuild) consumeHeldRivet(player)
            }

            for (pos in listOf(curve.bePositions.getFirst(), curve.bePositions.getSecond())) {
                (level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity)
                    ?.let { player.connection.send(ClientboundBlockEntityDataPacket.create(it)) }
            }
        }
    }

    private fun rivetItem() =
        net.minecraft.world.item.ItemStack(dev.silvergold.simulatedcoasters.SimulatedCoasters.RIVET.get())

    private fun consumeHeldRivet(player: ServerPlayer) {
        for (hand in net.minecraft.world.InteractionHand.entries) {
            val held = player.getItemInHand(hand)
            if (held.`is`(dev.silvergold.simulatedcoasters.SimulatedCoasters.RIVET.get())) {
                held.shrink(1)
                return
            }
        }
    }

    private fun peer(curve: BezierConnection): net.minecraft.core.BlockPos = curve.bePositions.getSecond()

    // server-side wall projection: nearest curve point to the hit, angle from
    // the radial direction in the stable frame
    private fun projectHit(curve: BezierConnection, hit: Vec3): Pair<Float, Float>? {
        val samples = maxOf(64, curve.getSegmentCount() * 4)
        var bestT = -1f
        var bestD = Double.MAX_VALUE
        for (i in 0..samples) {
            val t = i.toFloat() / samples
            val d = hit.distanceToSqr(curve.getPosition(t.toDouble()))
            if (d < bestD) {
                bestD = d
                bestT = t
            }
        }
        if (bestT < 0f) return null
        val tangent = CoasterBezierRailFrames.unitTangentAt(curve, bestT).normalize()
        val (lateral, up) = SlideCurveGeometry.stableFrame(tangent)
        if (lateral.lengthSqr() < 1.0E-12 || up.lengthSqr() < 1.0E-12) return null
        val rel = hit.subtract(curve.getPosition(bestT.toDouble()))
        val angle = Math.toDegrees(kotlin.math.atan2(rel.dot(up), rel.dot(lateral))).toFloat()
        return bestT to ((angle % 360f) + 360f) % 360f
    }

    private fun radiusAt(level: Level, curve: BezierConnection, t: Float): Float {
        val r0 = SlideCurveGeometry.radiusAt(level, curve.bePositions.getFirst())
        val r1 = SlideCurveGeometry.radiusAt(level, curve.bePositions.getSecond())
        return Mth.lerp(t, r0, r1)
    }

    private fun radialAt(curve: BezierConnection, t: Float, angle: Float): Vec3 {
        val tangent = CoasterBezierRailFrames.unitTangentAt(curve, t).normalize()
        val (lateral, up) = SlideCurveGeometry.stableFrame(tangent)
        val rad = Math.toRadians(angle.toDouble())
        return lateral.scale(Math.cos(rad)).add(up.scale(Math.sin(rad)))
    }

    private fun rivetBlockNear(level: Level, hit: Vec3): BlockPos? {
        val center = BlockPos.containing(hit)
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val pos = center.offset(dx, dy, dz)
            if (level.getBlockState(pos).block is RivetBlock) return pos
        }
        return null
    }

    private fun findCurve(level: Level, a: BlockPos, b: BlockPos): BezierConnection? {
        val be = level.getBlockEntity(a) as? WaterslideAnchorBlockEntity ?: return null
        val raw = be.getAnchorPeerCurvesView()[b] ?: return null
        val primary = if (raw.isPrimary) raw else raw.secondary()
        return if (WaterslideTrackMaterials.isWaterslide(primary)) primary else null
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideRivetPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_rivet")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideRivetPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, WaterslideRivetPayload::curveA,
                BlockPos.STREAM_CODEC, WaterslideRivetPayload::curveB,
                ByteBufCodecs.BOOL, WaterslideRivetPayload::remove,
                ByteBufCodecs.FLOAT, WaterslideRivetPayload::hitX,
                ByteBufCodecs.FLOAT, WaterslideRivetPayload::hitY,
                ByteBufCodecs.FLOAT, WaterslideRivetPayload::hitZ,
                ::WaterslideRivetPayload
            )
    }
}
