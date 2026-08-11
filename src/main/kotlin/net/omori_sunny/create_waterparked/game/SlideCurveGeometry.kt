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

// Shared slide curve frames, usable on server and client.
object SlideCurveGeometry {

    data class Frame(
        val t: Float,
        val center: Vec3,
        val tangent: Vec3,
        val lateral: Vec3,
        val up: Vec3,
        val radius: Float
    )

    fun radiusAt(level: Level, pos: BlockPos): Float =
        (level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity)?.radius
            ?: ModConfig.defaultSlideRadius()

    // Frames from first endpoint to second, including open-end extensions.
    fun sampleFrames(
        level: Level,
        bc: BezierConnection,
        r0: Float,
        r1: Float,
        spacing: Double = 0.5
    ): List<Frame> {
        val count = bc.getSegmentCount().coerceAtLeast(1)
        val ts = FloatArray(count + 1) { i ->
            if (i == 0) 0f else if (i == count) 1f else bc.getSegmentT(i)
        }
        val coarse = ArrayList<Frame>(count + 3)
        val ext0 = openEndExtension(level, bc, atFirst = true)
        if (ext0 > 0.01f) {
            val first = frameAt(level, bc, 0f, r0, r1)
            coarse += Frame(0f, first.center.subtract(first.tangent.scale(ext0.toDouble())),
                first.tangent, first.lateral, first.up, r0)
        }
        for (t in ts) coarse += frameAt(level, bc, t, r0, r1)
        val ext1 = openEndExtension(level, bc, atFirst = false)
        if (ext1 > 0.01f) {
            val last = frameAt(level, bc, 1f, r0, r1)
            coarse += Frame(1f, last.center.add(last.tangent.scale(ext1.toDouble())),
                last.tangent, last.lateral, last.up, r1)
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
            out += Frame(f.t, f.center, f.tangent, lat, up, f.radius)
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
                push(frameAt(level, bc, t, r0, r1))
            }
            push(b)
        }
        return out
    }

    private fun frameAt(
        level: Level,
        bc: BezierConnection,
        t: Float,
        r0: Float,
        r1: Float
    ): Frame {
        val center = bc.getPosition(t.toDouble())
        var tangent = CoasterBezierRailFrames.unitTangentAt(bc, t)
        if (tangent.lengthSqr() < 1.0E-12) tangent = Vec3(0.0, 1.0, 0.0)
        tangent = tangent.normalize()
        var lat = CoasterBezierRailFrames.lateralAt(bc, t, tangent, level)
        var up = tangent.cross(lat)
        val valid = lat.lengthSqr() > 1.0E-12 &&
            up.lengthSqr() > 1.0E-12 &&
            !lat.x.isNaN() && !lat.y.isNaN() && !lat.z.isNaN() &&
            !up.x.isNaN() && !up.y.isNaN() && !up.z.isNaN()
        if (!valid) {
            var fallbackUp = Vec3(0.0, 1.0, 0.0)
            if (abs(tangent.y) > 0.999) fallbackUp = Vec3(1.0, 0.0, 0.0)
            fallbackUp = fallbackUp.subtract(tangent.scale(fallbackUp.dot(tangent)))
            if (fallbackUp.lengthSqr() < 1.0E-12) {
                fallbackUp = Vec3(0.0, 0.0, 1.0).subtract(tangent.scale(tangent.z))
            }
            fallbackUp = fallbackUp.normalize()
            lat = fallbackUp.cross(tangent)
            if (lat.lengthSqr() < 1.0E-12) {
                lat = Vec3(0.0, 0.0, 1.0).cross(tangent)
            }
            lat = lat.normalize()
            up = tangent.cross(lat).normalize()
        } else {
            lat = lat.normalize()
            up = up.normalize()
        }
        return Frame(t, center, tangent, lat, up, Mth.lerp(t, r0, r1))
    }

    // Extension only at open ends (mirrors the render mesh).
    private fun openEndExtension(level: Level, bc: BezierConnection, atFirst: Boolean): Float {
        val anchor = if (atFirst) bc.bePositions.getFirst() else bc.bePositions.getSecond()
        val be = level.getBlockEntity(anchor) as? CoasterAnchorpointBlockEntity ?: return 0f
        if (be.legCount() != 1) return 0f
        return CoasterOpenEndExtension.extensionBlocks(level, anchor)
    }

    // Reversed frames for walking from the second endpoint.
    fun reversed(frames: List<Frame>): List<Frame> {
        val out = ArrayList<Frame>(frames.size)
        for (i in frames.indices.reversed()) {
            val f = frames[i]
            out += Frame(f.t, f.center, f.tangent.scale(-1.0), f.lateral.scale(-1.0), f.up, f.radius)
        }
        return out
    }

    // Angle of the lowest point of the tube circle, in degrees.
    fun bottomAngleDegrees(lat: Vec3, up: Vec3): Float {
        val a0 = atan2(up.y.toDouble(), lat.y.toDouble())
        val p0 = lat.y * cos(a0) + up.y * sin(a0)
        val a1 = a0 + Math.PI
        val p1 = lat.y * cos(a1) + up.y * sin(a1)
        return Math.toDegrees(if (p0 <= p1) a0 else a1).toFloat()
    }

    // Sable friction of the sector touching the tube bottom.
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
