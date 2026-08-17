package net.omori_sunny.create_waterparked.client.water

import com.simibubi.create.content.trains.track.BezierConnection
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.companion.math.JOMLConversion
import dev.ryanhcode.sable.sublevel.ClientSubLevel
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.omori_sunny.create_waterparked.game.physics.SlideSpace
import net.omori_sunny.create_waterparked.game.water.ServerWaterSimulation
import net.omori_sunny.create_waterparked.network.WaterslideWaterSyncPayload
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import org.joml.Vector3d
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
        val r0: Float,
        val r1: Float,
        val edge: Pair<Long, Long>,
        val lat: Vec3,
        val up: Vec3
    )

    private val fields = HashMap<String, HashMap<Pair<Long, Long>, CurveWater>>()
    private val segCache = HashMap<Pair<ResourceKey<Level>, String>, Pair<String, List<TubeSeg>>>()
    private var version = 0
    private var debugPolylines: List<List<Vec3>> = emptyList()
    // cooldown cache: thrown-stream trajectories recompute only on a sync refresh
    private var streamCacheVersion = -1
    private val streamCache = HashMap<String, Pair<List<List<Vec3>>, List<List<Vec3>>>>()

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
        val level = Minecraft.getInstance().level ?: return
        val space = when {
            payload.contraptionEntityId != null -> SlideSpace.Contraption(payload.contraptionEntityId!!)
            payload.subLevelId != null -> SlideSpace.SubLevel(payload.subLevelId)
            else -> SlideSpace.Main
        }
        val key = space.cacheKey(level)
        net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
            "Water sync received space={} entries={}", key, payload.entries.size
        )
        val target = HashMap<Pair<Long, Long>, CurveWater>()
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
            target[edgeKey(e.edgeA, e.edgeB)] = CurveWater(true, flowSign, segments, exit)
        }
        fields[key] = target
        bumpVersion()
        WaterslideTubeVisual.refreshAll()
    }

    @JvmStatic
    fun resultFor(level: Level, space: SlideSpace, bc: BezierConnection): CurveWater? =
        fields[space.cacheKey(level)]?.get(edgeKeyOf(bc))

    @JvmStatic
    fun resultFor(level: Level, bc: BezierConnection): CurveWater? {
        val edge = edgeKeyOf(bc)
        for (map in fields.values) {
            map[edge]?.let { return it }
        }
        return null
    }

    @JvmStatic
    fun fieldFor(level: Level, space: SlideSpace, a: BlockPos, b: BlockPos): CurveWater? =
        fields[space.cacheKey(level)]?.get(edgeKey(a.asLong(), b.asLong()))

    @JvmStatic
    fun fieldFor(level: Level, a: BlockPos, b: BlockPos): CurveWater? {
        val edge = edgeKey(a.asLong(), b.asLong())
        for (map in fields.values) {
            map[edge]?.let { return it }
        }
        return null
    }

    // Client-side contact check against the RENDERED water data: true when the
    // position is inside the tube cylinder of a curve that has a synced water
    // field. This matches what the player sees (the flywheel water band),
    // independent of the server trajectory's per-frame watered flag.
    @JvmStatic
    fun isInsideWateredTube(level: Level, pos: Vec3, margin: Double = 0.6): Boolean {
        val seen = HashSet<Pair<Long, Long>>()
        for (be in WaterslideCurveRenderer.clientAnchors()) {
            if (be.level !== level || be.isRemoved) continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val key = edgeKeyOf(bc)
                if (!seen.add(key)) continue
                val space = SlideSpace.ofLevelAndSub(level, be.blockPos)
                if (fields[space.cacheKey(level)]?.get(key)?.exists != true) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(level, a)
                val r1 = SlideCurveGeometry.radiusAt(level, b)
                val samples = 24
                for (i in 0..samples) {
                    val t = i.toFloat() / samples
                    val center = bc.getPosition(t.toDouble())
                    val radius = r0 + (r1 - r0) * t + margin
                    if (center.distanceToSqr(pos) <= radius * radius) return true
                }
            }
        }
        return false
    }

    // all cached thrown-stream polylines (world coordinates), exposed for the
    // player splash spawner so flying through the thrown water also counts
    @JvmStatic
    fun hasAnyWaterFields(): Boolean = fields.values.any { map -> map.values.any { it.exists } }

    @JvmStatic
    fun allStreamPolylines(): List<List<Vec3>> {
        val out = ArrayList<List<Vec3>>()
        for ((outer, inner) in streamCache.values) {
            out.addAll(outer)
            out.addAll(inner)
        }
        return out
    }

    data class StreamContact(val pos: Vec3, val velocity: Vec3)

    // Velocity of the thrown stream at the closest polyline point to pos.
    // Stream polylines are traced with fixed 0.05s steps, so finite
    // differences recover the true ballistic velocity at each point.
    @JvmStatic
    fun streamVelocityAt(level: Level, pos: Vec3, radius: Double): Vec3? =
        streamContactAt(level, pos, radius)?.velocity

    // Closest point on a thrown stream polyline to pos, plus that point's
    // water velocity. Returns null when no polyline is within radius.
    @JvmStatic
    fun streamContactAt(level: Level, pos: Vec3, radius: Double): StreamContact? {
        val maxDistSq = radius * radius
        var bestSq = Double.MAX_VALUE
        var bestPos: Vec3? = null
        var bestVel: Vec3? = null
        for (poly in allStreamPolylines()) {
            for (i in 0 until poly.size - 1) {
                val a = poly[i]
                val b = poly[i + 1]
                val ab = b.subtract(a)
                val lenSq = ab.lengthSqr()
                val t = if (lenSq < 1.0E-12) 0.0
                else ((pos.subtract(a)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
                val closest = a.add(ab.scale(t))
                val distSq = pos.distanceToSqr(closest)
                if (distSq <= maxDistSq && distSq < bestSq) {
                    bestSq = distSq
                    bestPos = closest
                    bestVel = streamPointVelocity(poly, i).lerp(streamPointVelocity(poly, i + 1), t)
                }
            }
        }
        val p = bestPos ?: return null
        return StreamContact(p, bestVel ?: Vec3.ZERO)
    }

    private fun streamPointVelocity(poly: List<Vec3>, idx: Int): Vec3 {
        val i0 = (idx - 1).coerceAtLeast(0)
        val i1 = (idx + 1).coerceAtMost(poly.size - 1)
        if (i1 <= i0) return Vec3.ZERO
        val span = 0.05 * (i1 - i0)
        return poly[i1].subtract(poly[i0]).scale(1.0 / span)
    }

    // Collision-box version of the contact checks: use the player's actual
    // AABB instead of a single probe point. Distance is measured to the
    // closest point of the box (clamped sphere test), which is exact for a
    // box-vs-tube check and catches tubes passing through the box centre.
    @JvmStatic
    fun intersectsWateredTubeBox(level: Level, box: AABB, space: SlideSpace? = null): Boolean {
        val seen = HashSet<Pair<Long, Long>>()
        for (be in WaterslideCurveRenderer.clientAnchors()) {
            if (be.level !== level || be.isRemoved) continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val key = edgeKeyOf(bc)
                if (!seen.add(key)) continue
                val keySpace = space ?: SlideSpace.ofLevelAndSub(level, be.blockPos)
                if (fields[keySpace.cacheKey(level)]?.get(key)?.exists != true) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(level, a)
                val r1 = SlideCurveGeometry.radiusAt(level, b)
                val samples = 48
                for (i in 0..samples) {
                    val t = i.toFloat() / samples
                    val center = bc.getPosition(t.toDouble())
                    val radius = r0 + (r1 - r0) * t + 0.1
                    val dx = min(max(center.x, box.minX), box.maxX) - center.x
                    val dy = min(max(center.y, box.minY), box.maxY) - center.y
                    val dz = min(max(center.z, box.minZ), box.maxZ) - center.z
                    if (dx * dx + dy * dy + dz * dz <= radius * radius) return true
                }
            }
        }
        return false
    }

    @JvmStatic
    fun intersectsStreamBox(level: Level, box: AABB, radius: Double): Boolean {
        for (poly in allStreamPolylines()) {
            for (i in 0 until poly.size - 1) {
                val segBox = AABB(poly[i], poly[i + 1]).inflate(radius)
                if (box.intersects(segBox)) return true
            }
        }
        return false
    }

    @JvmStatic
    fun isInsideStream(level: Level, pos: Vec3, radius: Double): Boolean {
        val r2 = radius * radius
        for (poly in allStreamPolylines()) {
            for (i in 0 until poly.size - 1) {
                val a = poly[i]
                val b = poly[i + 1]
                val ab = b.subtract(a)
                val lenSq = ab.lengthSqr()
                val t = if (lenSq < 1.0E-12) 0.0
                else ((pos.subtract(a)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
                if (pos.distanceToSqr(a.add(ab.scale(t))) <= r2) return true
            }
        }
        return false
    }

    // diagnostic: distance from pos to the closest watered curve surface
    // (negative means inside); used to debug Sable coordinate mismatches
    @JvmStatic
    fun debugNearestWateredTube(level: Level, pos: Vec3): Double {
        var best = Double.MAX_VALUE
        val seen = HashSet<Pair<Long, Long>>()
        for (be in WaterslideCurveRenderer.clientAnchors()) {
            if (be.level !== level || be.isRemoved) continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val key = edgeKeyOf(bc)
                if (!seen.add(key)) continue
                val space = SlideSpace.ofLevelAndSub(level, be.blockPos)
                if (fields[space.cacheKey(level)]?.get(key)?.exists != true) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(level, a)
                val r1 = SlideCurveGeometry.radiusAt(level, b)
                val samples = 24
                for (i in 0..samples) {
                    val t = i.toFloat() / samples
                    val center = bc.getPosition(t.toDouble())
                    val radius = r0 + (r1 - r0) * t
                    val d = center.distanceTo(pos) - radius
                    if (d < best) best = d
                }
            }
        }
        return best
    }

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
            val r = max(seg.r0, seg.r1).toDouble() + 0.5
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

        fun hit(p: Vec3): TubeSeg? {
            val cx = Mth.floor(p.x / GRID_SIZE)
            val cy = Mth.floor(p.y / GRID_SIZE)
            val cz = Mth.floor(p.z / GRID_SIZE)
            var best: TubeSeg? = null
            var bestD = Double.MAX_VALUE
            for (x in cx - 1..cx + 1) {
                for (y in cy - 1..cy + 1) {
                    for (z in cz - 1..cz + 1) {
                        val list = buckets[bucketKey(x, y, z)] ?: continue
                        for (s in list) {
                            val ab = s.b.subtract(s.a)
                            val lenSq = ab.lengthSqr()
                            if (lenSq < 1.0E-12) continue
                            val t = ((p.subtract(s.a)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
                            val r = (s.r0 + (s.r1 - s.r0) * t).toDouble()
                            val closest = s.a.add(ab.scale(t))
                            val d = p.distanceToSqr(closest)
                            if (d < r * r && d < bestD) {
                                bestD = d
                                best = s
                            }
                        }
                    }
                }
            }
            return best
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
        ownFrames: List<Vec3>,
        gravity: Vec3 = Vec3(0.0, -32.0, 0.0),
        sourceSpace: SlideSpace = SlideSpace.Main
    ): Pair<List<List<Vec3>>, List<List<Vec3>>>? {
        // cooldown: only recompute after a sync refresh (version bump)
        if (streamCacheVersion != version) {
            streamCacheVersion = version
            streamCache.clear()
        }
        val cacheKey = "${sourceSpace.cacheKey(level)}|${center.x},${center.y},${center.z}|${exitVel.x},${exitVel.y},${exitVel.z}|$rIn|$rSurf|$coverStart|$coverEnd|${gravity.x},${gravity.y},${gravity.z}"
        streamCache[cacheKey]?.let { return it }

        val own = ownCenterHash(ownFrames)
        val grid = SegGrid()
        for (s in allTubeSegments(level, sourceSpace)) {
            if (own.contains(hashVec(s.a)) || own.contains(hashVec(s.b))) continue
            grid.add(s)
        }
        // fixed angular grid (same spacing as the in-tube band) so the thrown
        // sheet lines up ring-to-ring at the mouth; the ragged cut-off comes from
        // each ray's independent pipe/ground collision, not from random angles
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
            outer += traceStream(level, outerPos, exitVel, grid, gravity)
            inner += traceStream(level, innerPos, exitVel, grid, gravity)
        }
        // keep every ray's full length (no shortest-ray truncation); the end
        // fades out instead of cutting mid-air
        if (outer.any { it.size >= 2 } && inner.any { it.size >= 2 }) {
            val result = outer to inner
            streamCache[cacheKey] = result
            return result
        }
        return null
    }

    private fun traceStream(
        level: Level,
        pos: Vec3,
        vel: Vec3,
        grid: SegGrid,
        gravity: Vec3
    ): List<Vec3> {
        // strict physics: dt in seconds, v in blocks/s, gravity in blocks/s^2
        val dt = 0.05
        val poly = ArrayList<Vec3>()
        var p = pos
        var v = vel
        poly += p
        var grace = 0
        for (step in 0 until 240) {
            val v0 = v
            val delta = v0.scale(dt)
            // Fast streams move 1-2 blocks per fixed step, which used to
            // tunnel straight through other slides between two point samples.
            // Sweep the same ballistic step in <=0.25-block sub-samples and
            // stop at the first collision so the sheet is actually cut by
            // every tube it touches. The p/v updates below are byte-for-byte
            // the original integration; only the collision probe is denser.
            val subSteps = max(1, Math.ceil(delta.length() / 0.25).toInt())
            var hitFrac: Double? = null
            for (j in 1..subSteps) {
                val f = j.toDouble() / subSteps
                val q = p.add(delta.scale(f))
                val bp = BlockPos.containing(q)
                if (level.getBlockState(bp).isSolid || q.y < level.minBuildHeight) {
                    hitFrac = f
                    break
                }
                val hitSeg = grid.hit(q)
                if (hitSeg != null && streamHitsWall(level, hitSeg, q)) {
                    // keep flying a short stretch past the pipe before cutting,
                    // so the water doesn't vanish right at the flywheel/BE surface
                    grace++
                    if (grace >= 8) {
                        hitFrac = f
                        break
                    }
                } else {
                    grace = 0
                }
            }
            v = v0.add(gravity.scale(dt))
            val stepEnd = p.add(delta)
            p = if (hitFrac != null) p.add(delta.scale(hitFrac)) else stepEnd
            poly += p
            if (hitFrac != null) break
        }
        return poly
    }

    // A thrown-water ray only cuts when the contact lands on a BLOCK sector
    // wall. Entering through an OPEN sector (the blank opening of the slide)
    // must keep flying instead of cutting the sheet.
    private fun streamHitsWall(level: Level, seg: TubeSeg, p: Vec3): Boolean {
        val a = BlockPos.of(seg.edge.first)
        val b = BlockPos.of(seg.edge.second)
        val config = SlideCurveGeometry.sectorConfig(level, a, b)
            ?: WaterslideSectorConfig.defaultConfig()
        val ab = seg.b.subtract(seg.a)
        val lenSq = ab.lengthSqr()
        if (lenSq < 1.0E-12) return false
        val t = ((p.subtract(seg.a)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
        val closest = seg.a.add(ab.scale(t))
        val radial = p.subtract(closest)
        if (radial.lengthSqr() < 1.0E-12) return false
        val lat = seg.lat.normalize()
        val up = seg.up.normalize()
        val angle = Math.toDegrees(
            kotlin.math.atan2(radial.dot(up), radial.dot(lat))
        ).toFloat()
        val placed = WaterslideSectorLayout.place(config)
        val sector = WaterslideSectorLayout.sectorAt(placed, angle)
        return sector != null && sector.sector.material != SectorMaterial.OPEN
    }

    private fun allTubeSegments(level: Level, sourceSpace: SlideSpace): List<TubeSeg> {
        val sig = structureSignature(level, sourceSpace)
        val cacheKey = level.dimension() to sourceSpace.cacheKey(level)
        segCache[cacheKey]?.let { if (it.first == sig) return it.second }
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
                // reuse the exact same sampling as the flywheel renderer
                // (interpolated radius + open-end extensions) so the collision
                // surface equals the visible tube, not a fat collision box
                val segmentSpace = SlideSpace.ofLevelAndSub(level, be.blockPos)
                val frames = WaterslideTubeMesh.sampleSegments(level, bc, r0, r1, Vec3.ZERO)
                val radiusScale = radiusScaleBetween(level, segmentSpace, sourceSpace)
                for (f in frames) {
                    val fa = mapToSpace(level, f.prevSpine, segmentSpace, sourceSpace)
                    val fb = mapToSpace(level, f.currSpine, segmentSpace, sourceSpace)
                    val latMid = f.prevLateral.lerp(f.currLateral, 0.5).normalize()
                    val tanMid = f.prevTangent.lerp(f.currTangent, 0.5).normalize()
                    val upMid = tanMid.cross(latMid).normalize()
                    val lat = mapNormalToSpace(level, latMid, segmentSpace, sourceSpace)
                    val up = mapNormalToSpace(level, upMid, segmentSpace, sourceSpace)
                    out += TubeSeg(
                        fa, fb,
                        (f.prevRadius * radiusScale), (f.currRadius * radiusScale),
                        edgeKeyOf(bc), lat, up
                    )
                }
            }
        }
        if (segCache.size > 8) segCache.clear()
        segCache[cacheKey] = sig to out
        return out
    }

    // Collision grid segments are expressed in the SOURCE slide's coordinate
    // space. Tubes belonging to another Sable space are mapped through their
    // poses, so a sub-level mouth can cut a main-world thrown sheet and vice
    // versa.
    private fun mapToSpace(level: Level, local: Vec3, from: SlideSpace, to: SlideSpace): Vec3 {
        if (from == to) return local
        val world = spaceToWorld(level, from, local)
        return worldToSpace(level, to, world)
    }

    private fun mapNormalToSpace(level: Level, local: Vec3, from: SlideSpace, to: SlideSpace): Vec3 {
        if (from == to) return local
        val world = spaceToWorldNormal(level, from, local)
        return worldNormalToSpace(level, to, world)
    }

    private fun spaceToWorldNormal(level: Level, space: SlideSpace, local: Vec3): Vec3 {
        val sub = clientSubLevel(level, space) ?: return local
        val out = sub.logicalPose().transformNormal(JOMLConversion.toJOML(local), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    private fun worldNormalToSpace(level: Level, space: SlideSpace, world: Vec3): Vec3 {
        val sub = clientSubLevel(level, space) ?: return world
        val out = sub.logicalPose().transformNormalInverse(JOMLConversion.toJOML(world), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    private fun spaceToWorld(level: Level, space: SlideSpace, local: Vec3): Vec3 {
        val sub = clientSubLevel(level, space) ?: return local
        val out = sub.logicalPose().transformPosition(JOMLConversion.toJOML(local), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    private fun worldToSpace(level: Level, space: SlideSpace, world: Vec3): Vec3 {
        val sub = clientSubLevel(level, space) ?: return world
        val out = sub.logicalPose().transformPositionInverse(JOMLConversion.toJOML(world), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    private fun clientSubLevel(level: Level, space: SlideSpace): ClientSubLevel? {
        val id = (space as? SlideSpace.SubLevel)?.id ?: return null
        val container = SubLevelContainer.getContainer(level) ?: return null
        return container.getSubLevel(id) as? ClientSubLevel
    }

    private fun radiusScaleBetween(level: Level, from: SlideSpace, to: SlideSpace): Float {
        fun scaleOf(space: SlideSpace): Float {
            val sub = clientSubLevel(level, space) ?: return 1f
            val s = sub.logicalPose().scale()
            return maxOf(s.x(), s.y(), s.z()).coerceAtLeast(0.1).toFloat()
        }
        return scaleOf(from) / scaleOf(to)
    }

    private fun structureSignature(level: Level, sourceSpace: SlideSpace): String {
        val anchors = WaterslideCurveRenderer.clientAnchors()
            .filter { it.level === level && !it.isRemoved }
            .sortedBy { it.blockPos.asLong() }
        val sb = StringBuilder()
        sb.append(sourceSpace.cacheKey(level)).append('|')
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
