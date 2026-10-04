package net.omori_sunny.create_waterparked.game

import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import dev.silvergold.simulatedcoasters.track.CoasterOpenEndExtension
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.game.physics.SlideSpaceAccess
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

object SlideCurveGeometry {

    private const val ZERO_EPS = 1.0E-12
    private const val AXIS_ALIGNED = 0.999

    data class Frame(
        val t: Float,
        val center: Vec3,
        val tangent: Vec3,
        val lateral: Vec3,
        val up: Vec3,
        val radius: Float,
        val profile: FloatArray? = null
    ) {
        fun wallMultiplier(angleDeg: Float): Float =
            if (profile == null) 1f else SlideProfile.multiplierAt(profile, angleDeg)

        val maxMultiplier: Float
            get() = profile?.max() ?: 1f
    }

    fun radiusAt(level: Level, pos: BlockPos): Float =
        (level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity)?.radius
            ?: ModConfig.defaultSlideRadius()

    fun radiusAt(access: SlideSpaceAccess, pos: BlockPos): Float =
        (access.getBlockEntity(pos) as? WaterslideAnchorBlockEntity)?.radius
            ?: ModConfig.defaultSlideRadius()

    fun sectorConfig(access: SlideSpaceAccess, anchor: BlockPos, peer: BlockPos): WaterslideSectorConfig? =
        (access.getBlockEntity(anchor) as? WaterslideAnchorBlockEntity)?.sectorConfigFor(peer)

    fun stableFrame(tangent: Vec3): Pair<Vec3, Vec3> {
        var ref = Vec3(0.0, 1.0, 0.0)
        if (abs(tangent.y) > AXIS_ALIGNED) ref = Vec3(1.0, 0.0, 0.0)
        var faceUp = ref.subtract(tangent.scale(ref.dot(tangent)))
        if (faceUp.lengthSqr() < ZERO_EPS) {
            ref = Vec3(0.0, 0.0, 1.0)
            faceUp = ref.subtract(tangent.scale(ref.dot(tangent)))
        }
        faceUp = faceUp.normalize()
        val lat = faceUp.cross(tangent).normalize()
        val up = tangent.cross(lat).normalize()
        return lat to up
    }

    fun sampleFrames(
        level: Level,
        bc: BezierConnection,
        r0: Float,
        r1: Float,
        spacing: Double = 0.5,
        includeExtensions: Boolean = true
    ): List<Frame> {
        val count = bc.getSegmentCount().coerceAtLeast(1)
        val ts = FloatArray(count + 1) { i ->
            if (i == 0) 0f else if (i == count) 1f else bc.getSegmentT(i)
        }
        val own = curveProfile(level, bc)
        val before = neighborProfile(level, bc, atFirst = true)
        val after = neighborProfile(level, bc, atFirst = false)
        val length = bc.length.coerceAtLeast(0.01)
        val window0 = (transitionOf(own, before) / length).toFloat().coerceIn(0f, 0.45f)
        val window1 = (transitionOf(own, after) / length).toFloat().coerceIn(0f, 0.45f)
        fun sectionAt(t: Float): FloatArray? {
            if (own == null && before == null && after == null) return null
            val ownRadii = own?.radii ?: SlideProfile.CIRCLE
            var s = SlideProfile.blendShared(before?.radii, ownRadii, SlideProfile.smoothstep((t / window0).coerceIn(0f, 1f)))
            // own at t <= 1-window1, the downstream neighbour's shape at t=1
            s = SlideProfile.blendShared(s, after?.radii, SlideProfile.smoothstep(((t - (1f - window1)) / window1).coerceIn(0f, 1f)))
            return s
        }

        val coarse = ArrayList<Frame>(count + 3)
        val ext0 = if (includeExtensions) openEndExtension(level, bc, atFirst = true) else 0f
        if (ext0 > 0.01f) {
            val first = frameAt(level, bc, 0f, r0, r1, sectionAt(0f))
            coarse += Frame(0f, first.center.subtract(first.tangent.scale(ext0.toDouble())),
                first.tangent, first.lateral, first.up, r0, first.profile)
        }
        for (t in ts) coarse += frameAt(level, bc, t, r0, r1, sectionAt(t))
        val ext1 = if (includeExtensions) openEndExtension(level, bc, atFirst = false) else 0f
        if (ext1 > 0.01f) {
            val last = frameAt(level, bc, 1f, r0, r1, sectionAt(1f))
            coarse += Frame(1f, last.center.add(last.tangent.scale(ext1.toDouble())),
                last.tangent, last.lateral, last.up, r1, last.profile)
        }

        if (coarse.size < 2) return coarse
        val out = ArrayList<Frame>(coarse.size * 4)
        var prevLat: Vec3? = null
        fun push(f: Frame) {
            var lat = f.lateral
            var up = f.up
            if (prevLat != null && lat.dot(prevLat!!) < 0.0) {
                lat = lat.scale(-1.0)
                up = up.scale(-1.0)
            }
            prevLat = lat
            out += Frame(f.t, f.center, f.tangent, lat, up, f.radius, f.profile)
        }
        push(coarse[0])
        for (i in 0 until coarse.size - 1) {
            val a = coarse[i]
            val b = coarse[i + 1]
            val dist = a.center.distanceTo(b.center)
            val steps = max(1, ceil(dist / spacing).toInt())
            for (j in 1 until steps) {
                val f = j.toDouble() / steps
                val t = a.t + (b.t - a.t) * f.toFloat()
                push(frameAt(level, bc, t, r0, r1, sectionAt(t)))
            }
            push(b)
        }
        return out
    }

    fun sampleFrames(
        access: SlideSpaceAccess,
        bc: BezierConnection,
        r0: Float,
        r1: Float,
        spacing: Double = 0.5,
        includeExtensions: Boolean = true
    ): List<Frame> = sampleFrames(access.level, bc, r0, r1, spacing, includeExtensions)

    fun sectionSampler(level: Level, bc: BezierConnection): (Float) -> FloatArray? {
        val own = curveProfile(level, bc)
        val before = neighborProfile(level, bc, atFirst = true)
        val after = neighborProfile(level, bc, atFirst = false)
        if (own == null && before == null && after == null) return { null }
        val length = bc.length.coerceAtLeast(0.01)
        val window0 = (transitionOf(own, before) / length).toFloat().coerceIn(0f, 0.45f)
        val window1 = (transitionOf(own, after) / length).toFloat().coerceIn(0f, 0.45f)
        return { t ->
            val ownRadii = own?.radii ?: SlideProfile.CIRCLE
            var s = SlideProfile.blendShared(before?.radii, ownRadii, SlideProfile.smoothstep((t / window0).coerceIn(0f, 1f)))
            // own at t <= 1-window1, the downstream neighbour's shape at t=1
            s = SlideProfile.blendShared(s, after?.radii, SlideProfile.smoothstep(((t - (1f - window1)) / window1).coerceIn(0f, 1f)))
            s
        }
    }

    private fun frameAt(
        level: Level,
        bc: BezierConnection,
        t: Float,
        r0: Float,
        r1: Float,
        profile: FloatArray? = null
    ): Frame {
        val center = bc.getPosition(t.toDouble())
        var tangent = CoasterBezierRailFrames.unitTangentAt(bc, t)
        if (tangent.lengthSqr() < ZERO_EPS) tangent = Vec3(0.0, 1.0, 0.0)
        tangent = tangent.normalize()
        val (lat, up) = stableFrame(tangent)
        return Frame(t, center, tangent, lat, up, Mth.lerp(t, r0, r1), profile)
    }

    fun curveProfile(level: Level, bc: BezierConnection): SlideProfile? {
        val a = bc.bePositions.first
        val b = bc.bePositions.second
        (level.getBlockEntity(a) as? WaterslideAnchorBlockEntity)?.curveProfileFor(b)?.let { return it }
        return (level.getBlockEntity(b) as? WaterslideAnchorBlockEntity)?.curveProfileFor(a)
    }

    private fun neighborProfile(level: Level, bc: BezierConnection, atFirst: Boolean): SlideProfile? {
        val self = if (atFirst) bc.bePositions.first else bc.bePositions.second
        val peer = if (atFirst) bc.bePositions.second else bc.bePositions.first
        val be = level.getBlockEntity(self) as? WaterslideAnchorBlockEntity ?: return null
        val key = peer.immutable()
        return be.anchorPeerCurvesView.keys
            .filter { it != key }
            .sortedBy { it.asLong() }
            .firstNotNullOfOrNull { be.curveProfileFor(it) }
    }

    private fun transitionOf(own: SlideProfile?, neighbor: SlideProfile?): Double =
        (own?.transition ?: neighbor?.transition ?: SlideProfile.DEFAULT_TRANSITION).toDouble()

    private fun openEndExtension(level: Level, bc: BezierConnection, atFirst: Boolean): Float {
        val anchor = if (atFirst) bc.bePositions.getFirst() else bc.bePositions.getSecond()
        val be = level.getBlockEntity(anchor) as? CoasterAnchorpointBlockEntity ?: return 0f
        if (be.legCount() != 1) return 0f
        return CoasterOpenEndExtension.extensionBlocks(level, anchor)
    }

    fun reversed(frames: List<Frame>): List<Frame> {
        val out = ArrayList<Frame>(frames.size)
        for (i in frames.indices.reversed()) {
            val f = frames[i]
            out += Frame(f.t, f.center, f.tangent.scale(-1.0), f.lateral.scale(-1.0), f.up, f.radius, f.profile)
        }
        return out
    }

    fun bottomAngleDegrees(lat: Vec3, up: Vec3): Float {
        val a0 = atan2(up.y.toDouble(), lat.y.toDouble())
        val p0 = lat.y * cos(a0) + up.y * sin(a0)
        val a1 = a0 + Math.PI
        val p1 = lat.y * cos(a1) + up.y * sin(a1)
        return Math.toDegrees(if (p0 <= p1) a0 else a1).toFloat()
    }

    fun sectorFriction(config: WaterslideSectorConfig, bottomAngle: Float): Double {
        val placed = WaterslideSectorLayout.place(config)
        val sector = WaterslideSectorLayout.sectorAt(placed, bottomAngle) ?: return 0.0
        if (sector.sector.material == SectorMaterial.OPEN) return 0.0
        val blockId = sector.sector.blockId ?: return 0.0
        val block = BuiltInRegistries.BLOCK.get(blockId) ?: return 0.0
        return PhysicsBlockPropertyHelper.getFriction(block.defaultBlockState())
    }

    fun sectorConfig(level: Level, anchor: BlockPos, peer: BlockPos): WaterslideSectorConfig? =
        (level.getBlockEntity(anchor) as? WaterslideAnchorBlockEntity)?.sectorConfigFor(peer)

    fun averageCenterY(frames: List<Frame>): Double {
        if (frames.isEmpty()) return 0.0
        var sum = 0.0
        for (f in frames) sum += f.center.y
        return sum / frames.size
    }
}
