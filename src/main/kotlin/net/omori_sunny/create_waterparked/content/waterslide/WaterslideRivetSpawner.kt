package net.omori_sunny.create_waterparked.content.waterslide
// sub-level placement for the extended wall rivet, mirroring CCS
// RivetSpawner.tryPlace but with the pose derived from the slide curve frame

import com.simibubi.create.content.trains.track.BezierConnection
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.companion.math.Pose3d
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.silvergold.simulatedcoasters.rivet.RivetHostKey
import dev.silvergold.simulatedcoasters.rivet.RivetPlacement
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d

object WaterslideRivetSpawner {

    // every wall-rivet sub-level ever placed (re-populated by serverTick's
    // ZERO-cell scan after reloads); the slide riding feature refuses these
    val rivetSubIds: MutableSet<java.util.UUID> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    // wall frame at (t, angle): outer surface point + orthonormal basis
    // (circum, radial-out, tangent)
    fun wallFrame(
        level: ServerLevel,
        curve: BezierConnection,
        t: Float,
        angle: Float
    ): Triple<Vec3, Vec3, Vec3>? {
        val spine = curve.getPosition(t.toDouble())
        val tangent = CoasterBezierRailFrames.unitTangentAt(curve, t).normalize()
        val (lateral, up) = SlideCurveGeometry.stableFrame(tangent)
        if (lateral.lengthSqr() < 1.0E-12 || up.lengthSqr() < 1.0E-12) return null
        val rad = Math.toRadians(angle.toDouble())
        val radial = lateral.scale(Math.cos(rad)).add(up.scale(Math.sin(rad)))
        val r0 = SlideCurveGeometry.radiusAt(level, curve.bePositions.getFirst())
        val r1 = SlideCurveGeometry.radiusAt(level, curve.bePositions.getSecond())
        val outer = Mth.lerp(t, r0, r1).toDouble() +
            (ModConfig.wallThickness().toDouble() - 0.125)
        return Triple(spine.add(radial.scale(outer)), radial, tangent)
    }

    private fun orientation(radial: Vec3, tangent: Vec3, yRotationDegrees: Int): Quaterniond {
        val n = Vector3d(radial.x, radial.y, radial.z).normalize()
        val t = Vector3d(tangent.x, tangent.y, tangent.z).normalize()
        val c = Vector3d(n).cross(t).normalize()
        // right-handed basis [c, n, t]: model +Y (plate normal) -> radial,
        // model +Z -> travel tangent
        val m = Matrix3d(
            c.x, c.y, c.z,
            n.x, n.y, n.z,
            t.x, t.y, t.z
        )
        val q = Quaterniond().setFromNormalized(m)
        if (yRotationDegrees != 0) {
            q.mul(Quaterniond().rotateY(Math.toRadians(yRotationDegrees.toDouble())))
        }
        return q
    }

    fun tryPlaceOnWall(
        level: ServerLevel,
        curve: BezierConnection,
        t: Float,
        angle: Float,
        yRotationDegrees: Int,
        player: net.minecraft.server.level.ServerPlayer
    ): ServerSubLevel? {
        val frame = wallFrame(level, curve, t, angle) ?: return null
        val (surface, radial, tangent) = frame
        val container = SubLevelContainer.getContainer(level)
            as? dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer ?: return null

        val pose = Pose3d()
        pose.position().set(surface.x, surface.y, surface.z)
        pose.orientation().set(orientation(radial, tangent, yRotationDegrees))
        val sub = container.allocateNewSubLevel(pose) as? ServerSubLevel ?: return null
        try {
            sub.plot.newEmptyChunk(sub.plot.centerChunk)
            val ok = sub.plot.embeddedLevelAccessor.setBlock(
                BlockPos.ZERO,
                net.omori_sunny.create_waterparked.content.registry.ModBlocks.WATERSLIDE_RIVET
                    .defaultBlockState().setValue(dev.silvergold.simulatedcoasters.rivet.RivetBlock.FACING, Direction.UP),
                3
            )
            if (!ok) throw IllegalStateException("rivet setBlock failed")
            val be = sub.plot.embeddedLevelAccessor.getBlockEntity(BlockPos.ZERO)
                as? WaterslideRivetBlockEntity ?: throw IllegalStateException("no rivet BE")
            val inward = Direction.getNearest(-radial.x, -radial.y, -radial.z)
            be.bindHost(
                RivetHostKey.world(curve.bePositions.getFirst()), inward,
                RivetPlacement.Attachment(0.5, 0.5, 0.5), yRotationDegrees
            )
            be.bindCurve(curve.bePositions.getSecond(), t, angle)
            rivetSubIds.add(sub.uniqueId)
            // record the body-local COM so later block edits can be compensated
            runCatching {
                val com = sub.massTracker.centerOfMass ?: return@runCatching
                be.comBaseX = com.x(); be.comBaseY = com.y(); be.comBaseZ = com.z()
            }
            // bindHost internally pins a world lock at the host FACE anchor
            // (correct for CCS track rivets, wrong for wall rivets) - drop it
            // so the per-tick wall teleport is the sole holder
            be.removeWorldLock()
            sub.updateMergedMassData(1.0f)
            return sub
        } catch (e: Exception) {
            sub.markRemoved()
            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.error(
                "Waterslide rivet placement failed", e
            )
            return null
        }
    }

    // per-tick wall realignment, mirroring CCS RivetLoadReconciler for their
    // rivets: the sub-level is a free physics body held in place by
    // continuously teleporting it onto the live curve frame (this also makes
    // slide radius/shape edits move the rivets along for free)
    @JvmStatic
    fun serverTick(level: ServerLevel) {
        val container = SubLevelContainer.getContainer(level)
            as? dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer ?: return
        for (raw in container.allSubLevels) {
            val sub = raw as? ServerSubLevel ?: continue
            hold(level, sub)
        }
    }

    // ---- wall snapping: a uniform 0.25 x 0.25 grid unfolded onto the tube ----
    // u = arc length along the spine, v = circumferential arc length at the
    // local radius, so cells stay the same physical size on variable radii

    private const val SNAP_GRID = 0.25

    private fun arcLengthAt(curve: BezierConnection, t: Float): Double {
        val steps = maxOf(32, curve.getSegmentCount() * 4)
        var arc = 0.0
        var prev = curve.getPosition(0.0)
        for (i in 1..steps) {
            val ti = t * i / steps
            val p = curve.getPosition(ti.toDouble())
            arc += prev.distanceTo(p)
            prev = p
        }
        return arc
    }

    private fun tAtArcLength(curve: BezierConnection, targetArc: Double): Float {
        val steps = maxOf(32, curve.getSegmentCount() * 4)
        var arc = 0.0
        var prev = curve.getPosition(0.0)
        for (i in 1..steps) {
            val ti = i.toDouble() / steps
            val p = curve.getPosition(ti)
            val seg = prev.distanceTo(p)
            if (arc + seg >= targetArc) {
                val f = if (seg > 1.0E-9) (targetArc - arc) / seg else 0.0
                return ((i - 1 + f) / steps).toFloat()
            }
            arc += seg
            prev = p
        }
        return 1f
    }

    @JvmStatic
    fun snapWall(
        level: net.minecraft.world.level.Level,
        curve: BezierConnection,
        t: Float,
        angle: Float
    ): Pair<Float, Float> {
        val r0 = SlideCurveGeometry.radiusAt(level, curve.bePositions.getFirst())
        val r1 = SlideCurveGeometry.radiusAt(level, curve.bePositions.getSecond())
        val radius = Mth.lerp(t, r0, r1).toDouble()
        // quantise the unfolded grid coordinates and fold back
        val u = kotlin.math.round(arcLengthAt(curve, t) / SNAP_GRID) * SNAP_GRID
        val vRaw = Math.toRadians(angle.toDouble()) * radius
        val v = kotlin.math.round(vRaw / SNAP_GRID) * SNAP_GRID
        val snappedT = tAtArcLength(curve, u)
        val snappedRadius = Mth.lerp(snappedT, r0, r1).toDouble().coerceAtLeast(0.05)
        val snappedAngle = Math.toDegrees(v / snappedRadius).toFloat()
        val norm = ((snappedAngle % 360f) + 360f) % 360f
        return snappedT to norm
    }

    // true when a wall rivet already occupies this (t, angle) cell - used to
    // hide the outline and reject duplicates
    @JvmStatic
    fun isRivetOccupied(
        level: net.minecraft.world.level.Level,
        curve: BezierConnection,
        t: Float,
        angle: Float
    ): Boolean {
        val container = SubLevelContainer.getContainer(level) ?: return false
        val host = curve.bePositions.getFirst()
        val peer = curve.bePositions.getSecond()
        val radius = runCatching {
            Mth.lerp(t,
                SlideCurveGeometry.radiusAt(level, host),
                SlideCurveGeometry.radiusAt(level, peer)).toDouble()
        }.getOrDefault(1.0)
        val tangent = CoasterBezierRailFrames.unitTangentAt(curve, t).normalize()
        val frame = SlideCurveGeometry.stableFrame(tangent)
        val rad = Math.toRadians(angle.toDouble())
        val radial = frame.first.scale(Math.cos(rad)).add(frame.second.scale(Math.sin(rad)))
        val point = curve.getPosition(t.toDouble()).add(radial.scale(radius))
        for (raw in container.allSubLevels) {
            val be = runCatching {
                (raw as? dev.ryanhcode.sable.sublevel.SubLevel)
                    ?.plot?.embeddedLevelAccessor?.getBlockEntity(BlockPos.ZERO)
            }.getOrNull() as? WaterslideRivetBlockEntity ?: continue
            if (be.hostPos() != host || be.curvePeer != peer) continue
            val otherTangent = CoasterBezierRailFrames.unitTangentAt(curve, be.curveT).normalize()
            val otherFrame = SlideCurveGeometry.stableFrame(otherTangent)
            val otherRad = Math.toRadians(be.wallAngle.toDouble())
            val otherRadius = Mth.lerp(be.curveT,
                SlideCurveGeometry.radiusAt(level, host),
                SlideCurveGeometry.radiusAt(level, peer)).toDouble()
            val otherRadial = otherFrame.first.scale(Math.cos(otherRad))
                .add(otherFrame.second.scale(Math.sin(otherRad)))
            val otherPoint = curve.getPosition(be.curveT.toDouble())
                .add(otherRadial.scale(otherRadius))
            if (point.distanceToSqr(otherPoint) < 0.25 * 0.25) return true
        }
        return false
    }

    @JvmStatic
    fun isRivetSub(sub: ServerSubLevel): Boolean =
        rivetSubIds.contains(sub.uniqueId)

    // re-apply the wall frame for one rivet sub-level; returns true when the
    // sub is one of ours (registered or recognised by its ZERO-cell BE)
    @JvmStatic
    fun hold(level: ServerLevel, sub: ServerSubLevel): Boolean {
        val be = runCatching {
            sub.plot.embeddedLevelAccessor.getBlockEntity(BlockPos.ZERO)
        }.getOrNull() as? WaterslideRivetBlockEntity ?: return false
        rivetSubIds.add(sub.uniqueId)
        // heal legacy rivets whose pin predates the removeWorldLock fix
        be.removeWorldLock()
        val anchor = level.getBlockEntity(be.hostPos()) as? WaterslideAnchorBlockEntity ?: return true
        val rawCurve = anchor.anchorPeerCurvesView[be.curvePeer] ?: return true
        val curve = if (rawCurve.isPrimary) rawCurve else rawCurve.secondary()
        val frame = wallFrame(level, curve, be.curveT, be.wallAngle) ?: return true
        val q = orientation(frame.second, frame.third, be.yRotationDegrees())
        val handle = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(sub) ?: return true
        if (!handle.isValid()) return true
        // block edits inside the plot shift the body-local COM; physics keeps
        // the world COM fixed, which would drag the content - compensate the
        // anchor so the rivet stays exactly on the wall
        val comNow = runCatching { sub.massTracker.centerOfMass }.getOrNull()
        val world = Vector3d(frame.first.x, frame.first.y, frame.first.z)
        if (comNow != null) {
            val delta = Vector3d(
                comNow.x() - be.comBaseX,
                comNow.y() - be.comBaseY,
                comNow.z() - be.comBaseZ
            )
            if (delta.lengthSquared() > 1.0E-12) {
                q.transform(delta)
                world.add(delta)
            }
        }
        handle.teleport(world, q)
        // cancel whatever gravity accumulated between the teleports
        val lin = handle.getLinearVelocity(Vector3d())
        val ang = handle.getAngularVelocity(Vector3d())
        handle.addLinearAndAngularVelocity(lin.negate(), ang.negate())
        return true
    }
}
