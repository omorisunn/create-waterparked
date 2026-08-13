package net.omori_sunny.create_waterparked.client.water

import com.simibubi.create.content.trains.track.BezierConnection
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.omori_sunny.create_waterparked.game.water.ServerWaterSimulation
import net.omori_sunny.create_waterparked.network.WaterslideWaterSyncPayload
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

// Client-side water data warehouse fed by the server simulation.
@OnlyIn(Dist.CLIENT)
object WaterFlowSimulation {

    const val WATER_V_CYCLES_PER_BLOCK = 1.0f

    data class CurveWater(
        val exists: Boolean,
        val flowSign: Float,
        val segments: List<ServerWaterSimulation.WaterSegment>,
        val exit: ServerWaterSimulation.ExitInfo?
    )

    private data class TubeSeg(
        val a: Vec3,
        val b: Vec3,
        val r: Float
    )

    private val fields = HashMap<Pair<Long, Long>, CurveWater>()
    private val segCache = HashMap<ResourceKey<Level>, Pair<String, List<TubeSeg>>>()
    private var version = 0
    private var debugPolylines: List<List<Vec3>> = emptyList()

    @JvmStatic
    fun debugPolylines(): List<List<Vec3>> = debugPolylines

    @JvmStatic
    fun applyDebugTrajectories(polylines: List<List<Vec3>>) {
        debugPolylines = polylines
    }

    @JvmStatic
    fun clearDebugTrajectories() {
        debugPolylines = emptyList()
    }

    @JvmStatic
    fun version(): Int = version

    @JvmStatic
    fun bumpVersion() {
        version++
    }

    @JvmStatic
    fun applySync(payload: WaterslideWaterSyncPayload) {
        net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
            "Water sync received entries={}", payload.entries.size
        )
        fields.clear()
        for (e in payload.entries) {
            if (e.segments.isEmpty()) continue
            var sum = 0.0
            for (s in e.segments) sum += s.speed
            val flowSign = if (sum >= 0.0) -1f else 1f
            val segments = e.segments.map {
                ServerWaterSimulation.WaterSegment(it.arc, it.speed)
            }
            val exit = if (e.exitPos != null) {
                ServerWaterSimulation.ExitInfo(e.exitPos, e.exitVel ?: Vec3.ZERO)
            } else null
            fields[edgeKey(e.edgeA, e.edgeB)] = CurveWater(true, flowSign, segments, exit)
        }
        bumpVersion()
        WaterslideTubeVisual.refreshAll()
    }

    @JvmStatic
    fun resultFor(level: Level, bc: BezierConnection): CurveWater? =
        fields[edgeKeyOf(bc)]

    @JvmStatic
    fun fieldFor(level: Level, a: BlockPos, b: BlockPos): CurveWater? =
        fields[edgeKey(a.asLong(), b.asLong())]

    @JvmStatic
    fun clear() {
        fields.clear()
        segCache.clear()
        debugPolylines = emptyList()
    }

    private fun edgeKeyOf(bc: BezierConnection): Pair<Long, Long> =
        edgeKey(bc.bePositions.getFirst().asLong(), bc.bePositions.getSecond().asLong())

    private fun edgeKey(a: Long, b: Long): Pair<Long, Long> =
        if (a <= b) a to b else b to a

    // ---- exit stream prediction (client side, visual only) ----

    private class SegGrid {
        private val buckets = HashMap<Long, MutableList<TubeSeg>>()

        fun add(seg: TubeSeg) {
            val r = seg.r.toDouble() + 0.5
            val minX = Mth.floor((min(seg.a.x, seg.b.x) - r) / GRID_SIZE)
            val maxX = Mth.floor((max(seg.a.x, seg.b.x) + r) / GRID_SIZE)
            val minY = Mth.floor((min(seg.a.y, seg.b.y) - r) / GRID_SIZE)
            val maxY = Mth.floor((max(seg.a.y, seg.b.y) + r) / GRID_SIZE)
            val minZ = Mth.floor((min(seg.a.z, seg.b.z) - r) / GRID_SIZE)
            val maxZ = Mth.floor((max(seg.a.z, seg.b.z) + r) / GRID_SIZE)
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    for (z in minZ..maxZ) {
                        buckets.getOrPut(bucketKey(x, y, z)) { ArrayList() } += seg
                    }
                }
            }
        }

        fun hit(p: Vec3): Boolean {
            val cx = Mth.floor(p.x / GRID_SIZE)
            val cy = Mth.floor(p.y / GRID_SIZE)
            val cz = Mth.floor(p.z / GRID_SIZE)
            for (x in cx - 1..cx + 1) {
                for (y in cy - 1..cy + 1) {
                    for (z in cz - 1..cz + 1) {
                        val list = buckets[bucketKey(x, y, z)] ?: continue
                        for (s in list) {
                            val ab = s.b.subtract(s.a)
                            val lenSq = ab.lengthSqr()
                            if (lenSq < 1.0E-12) continue
                            val t = ((p.subtract(s.a)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
                            val closest = s.a.add(ab.scale(t))
                            if (p.distanceToSqr(closest) < s.r.toDouble() * s.r.toDouble()) return true
                        }
                    }
                }
            }
            return false
        }

        companion object {
            const val GRID_SIZE = 8.0

            fun bucketKey(x: Int, y: Int, z: Int): Long =
                ((x.toLong() and 0x1FFFFF) shl 42) or
                    ((y.toLong() and 0x1FFFFF) shl 21) or
                    (z.toLong() and 0x1FFFFF)
        }
    }

    // outer and inner ring polylines of the exit water sheet
    @JvmStatic
    fun predictStreams(
        level: Level,
        exitPos: Vec3,
        exitVel: Vec3,
        center: Vec3,
        lat: Vec3,
        up: Vec3,
        rIn: Float,
        rSurf: Float,
        coverStart: Float,
        coverEnd: Float,
        ownFrames: List<Vec3>
    ): Pair<List<List<Vec3>>, List<List<Vec3>>>? {
        val own = ownCenterHash(ownFrames)
        val grid = SegGrid()
        for (s in allTubeSegments(level)) {
            if (own.contains(hashVec(s.a)) || own.contains(hashVec(s.b))) continue
            grid.add(s)
        }
        val count = 16
        val outer = ArrayList<List<Vec3>>(count)
        val inner = ArrayList<List<Vec3>>(count)
        for (i in 0 until count) {
            val theta = Math.toRadians(
                (coverStart + (i + 0.5) / count * (coverEnd - coverStart)).toDouble()
            )
            val c = cos(theta)
            val s = sin(theta)
            val outerPos = center.add(lat.scale(c * rIn)).add(up.scale(s * rIn))
            val innerPos = center.add(lat.scale(c * rSurf)).add(up.scale(s * rSurf))
            outer += traceStream(level, outerPos, exitVel, grid)
            inner += traceStream(level, innerPos, exitVel, grid)
        }
        val maxSamples = minOf(
            outer.minOfOrNull { it.size } ?: return null,
            inner.minOfOrNull { it.size } ?: return null
        )
        if (maxSamples < 2) return null
        val outO = outer.map { it.subList(0, maxSamples) }
        val outI = inner.map { it.subList(0, maxSamples) }
        return outO to outI
    }

    private fun traceStream(
        level: Level,
        pos: Vec3,
        vel: Vec3,
        grid: SegGrid
    ): List<Vec3> {
        val dt = 0.5
        val poly = ArrayList<Vec3>()
        var p = pos
        var v = vel
        poly += p
        for (step in 0 until 400) {
            p = p.add(v.scale(dt / 20.0))
            v = v.add(0.0, -32.0 * dt / 20.0, 0.0)
            val bp = BlockPos.containing(p)
            if (level.getBlockState(bp).isSolid || p.y < level.minBuildHeight) break
            if (grid.hit(p)) break
            if (step % 2 == 0) poly += p
        }
        return poly
    }

    private fun allTubeSegments(level: Level): List<TubeSeg> {
        val sig = structureSignature(level)
        segCache[level.dimension()]?.let { if (it.first == sig) return it.second }
        val out = ArrayList<TubeSeg>()
        for (be in WaterslideCurveRenderer.clientAnchors()) {
            if (be.level !== level || be.isRemoved) continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(level, a)
                val r1 = SlideCurveGeometry.radiusAt(level, b)
                val count = bc.getSegmentCount().coerceAtLeast(1)
                for (i in 0 until count) {
                    val t0 = if (i == 0) 0f else bc.getSegmentT(i)
                    val t1 = if (i == count - 1) 1f else bc.getSegmentT(i + 1)
                    val p0 = bc.getPosition(t0.toDouble())
                    val p1 = bc.getPosition(t1.toDouble())
                    out += TubeSeg(p0, p1, max(Mth.lerp(t0, r0, r1), Mth.lerp(t1, r0, r1)))
                }
            }
        }
        if (segCache.size > 8) segCache.clear()
        segCache[level.dimension()] = sig to out
        return out
    }

    private fun structureSignature(level: Level): String {
        val anchors = WaterslideCurveRenderer.clientAnchors()
            .filter { it.level === level && !it.isRemoved }
            .sortedBy { it.blockPos.asLong() }
        val sb = StringBuilder()
        for (be in anchors) {
            sb.append(be.blockPos.asLong()).append('|')
            for (e in be.anchorPeerCurvesView.entries.sortedBy { it.key.asLong() }) {
                val raw = e.value
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                sb.append(a.asLong()).append(',').append(b.asLong()).append(',')
                    .append(bc.getSegmentCount()).append(',')
                    .append(bc.starts.getFirst().x).append(',')
                    .append(bc.starts.getFirst().y).append(',')
                    .append(bc.starts.getFirst().z).append(',')
                    .append(bc.starts.getSecond().x).append(',')
                    .append(bc.starts.getSecond().y).append(',')
                    .append(bc.starts.getSecond().z).append(',')
                    .append(SlideCurveGeometry.radiusAt(level, a)).append(',')
                    .append(SlideCurveGeometry.radiusAt(level, b)).append(';')
            }
        }
        return sb.toString()
    }

    private fun ownCenterHash(frames: List<Vec3>): Set<Long> {
        val set = HashSet<Long>(frames.size * 2 + 1)
        for (f in frames) set.add(hashVec(f))
        return set
    }

    private fun hashVec(v: Vec3): Long =
        (v.x * 1000.0).roundToLong() * 73856093 xor
            (v.y * 1000.0).roundToLong() * 19349663 xor
            (v.z * 1000.0).roundToLong() * 83492791
}
