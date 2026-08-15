package net.omori_sunny.create_waterparked.game.water

import com.simibubi.create.content.trains.track.BezierConnection
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideAnchorIndex
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.omori_sunny.create_waterparked.game.physics.MainSlideSpaceAccess
import net.omori_sunny.create_waterparked.game.physics.SlideSpace
import net.omori_sunny.create_waterparked.game.physics.SlideSpaceAccess
import net.omori_sunny.create_waterparked.network.WaterslideDebugTrajectoryPayload
import net.omori_sunny.create_waterparked.network.WaterslideWaterSyncPayload
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Server-side water shape from point-mass trajectories.
object ServerWaterSimulation {

    // one watered sub-segment of a curve
    data class WaterSegment(
        val arc: Float,
        val speed: Float
    )

    data class ExitInfo(val pos: Vec3, val vel: Vec3)

    data class CurveField(val segments: List<WaterSegment>, val exit: ExitInfo?)

    private data class TubeSeg(
        val a: Vec3,
        val b: Vec3,
        val rOut: Float,
        val rIn: Float,
        val curveKey: Pair<Long, Long>,
        val arcStart: Float,
        val len: Float,
        val lat: Vec3,
        val up: Vec3
    )

    private data class LegStart(
        val center: Vec3,
        val tangent: Vec3,
        val lateral: Vec3,
        val up: Vec3,
        val rIn: Float
    )

    private class SegmentAcc {
        var velSum = Vec3.ZERO
        var axisSum = Vec3.ZERO
        var count = 0

        fun add(vel: Vec3, axis: Vec3) {
            velSum = velSum.add(vel)
            axisSum = axisSum.add(axis)
            count++
        }

        // average particle velocity projected onto the segment's average axis;
        // positive = along the curve forward direction (downstream)
        fun signedSpeed(): Float {
            if (count == 0) return 0f
            val avgAxis = axisSum.normalize()
            if (avgAxis.lengthSqr() < 1.0E-12) return 0f
            return velSum.scale(1.0 / count).dot(avgAxis).toFloat()
        }
    }

    private class SegGrid {
        private val buckets = HashMap<Long, MutableList<TubeSeg>>()

        fun add(seg: TubeSeg) {
            val r = seg.rOut.toDouble() + 0.5
            val minX = Mth.floor((min(seg.a.x, seg.b.x) - r) / GRID)
            val maxX = Mth.floor((max(seg.a.x, seg.b.x) + r) / GRID)
            val minY = Mth.floor((min(seg.a.y, seg.b.y) - r) / GRID)
            val maxY = Mth.floor((max(seg.a.y, seg.b.y) + r) / GRID)
            val minZ = Mth.floor((min(seg.a.z, seg.b.z) - r) / GRID)
            val maxZ = Mth.floor((max(seg.a.z, seg.b.z) + r) / GRID)
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    for (z in minZ..maxZ) {
                        buckets.getOrPut(key(x, y, z)) { ArrayList() } += seg
                    }
                }
            }
        }

        fun nearest(p: Vec3): TubeSeg? {
            val cx = Mth.floor(p.x / GRID)
            val cy = Mth.floor(p.y / GRID)
            val cz = Mth.floor(p.z / GRID)
            var best: TubeSeg? = null
            var bestD = Double.MAX_VALUE
            for (x in cx - 1..cx + 1) {
                for (y in cy - 1..cy + 1) {
                    for (z in cz - 1..cz + 1) {
                        val list = buckets[key(x, y, z)] ?: continue
                        for (s in list) {
                            val ab = s.b.subtract(s.a)
                            val lenSq = ab.lengthSqr()
                            if (lenSq < 1.0E-12) continue
                            val t = ((p.subtract(s.a)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
                            val closest = s.a.add(ab.scale(t))
                            val d = p.distanceToSqr(closest)
                            if (d < bestD) {
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
            const val GRID = 8.0

            fun key(x: Int, y: Int, z: Int): Long =
                ((x.toLong() and 0x1FFFFF) shl 42) or
                    ((y.toLong() and 0x1FFFFF) shl 21) or
                    (z.toLong() and 0x1FFFFF)
        }
    }

    private val fields = HashMap<String, Map<Pair<Long, Long>, CurveField>>()
    private val dirty = mutableSetOf<String>()
    private val lastCalc = mutableMapOf<String, Long>()
    private val segCache = HashMap<String, Pair<String, List<TubeSeg>>>()
    private val lastSig = HashMap<String, String>()
    private val debugPlayers = mutableSetOf<UUID>()

    private fun spaceKey(access: SlideSpaceAccess): String = access.space.cacheKey(access.level)

    // client debug toggle; recomputes once so trajectories get collected
    fun setDebug(player: ServerPlayer, enable: Boolean) {
        if (enable) {
            if (debugPlayers.add(player.uuid)) {
                resync(player.level())
            }
        } else {
            debugPlayers.remove(player.uuid)
        }
    }

    fun markDirty(level: Level) {
        if (!level.isClientSide) dirty += SlideSpace.Main.cacheKey(level)
    }

    // force a recalculation + resend for a joined player
    fun resync(level: Level) {
        if (level.isClientSide) return
        val key = SlideSpace.Main.cacheKey(level)
        dirty += key
        lastSig.remove(key)
    }

    // resend the current field to players who just joined
    fun resendTo(level: ServerLevel) {
        val key = MainSlideSpaceAccess(level).space.cacheKey(level)
        val f = fields[key] ?: return
        val payload = WaterslideWaterSyncPayload(toEntries(f))
        CreateWaterparked.LOGGER.info("Water resend entries={}", payload.entries.size)
        for (player in level.players()) {
            PacketDistributor.sendToPlayer(player, payload)
        }
    }

    private fun toEntries(f: Map<Pair<Long, Long>, CurveField>): List<WaterslideWaterSyncPayload.Entry> =
        f.map { (key, field) ->
            WaterslideWaterSyncPayload.Entry(
                key.first, key.second, field.segments.map {
                    WaterslideWaterSyncPayload.Segment(it.arc, it.speed)
                }, field.exit?.pos, field.exit?.vel
            )
        }

    fun field(level: Level, a: BlockPos, b: BlockPos): CurveField? =
        fields[SlideSpace.Main.cacheKey(level)]?.get(edgeKey(a, b))

    fun field(access: SlideSpaceAccess, a: BlockPos, b: BlockPos): CurveField? =
        fields[spaceKey(access)]?.get(edgeKey(a, b))

    // water flow velocity (blocks/s) at a world position, for pushing players.
    // Returns null when the position isn't inside a simulated water tube.
    @JvmStatic
    fun waterVelocityAt(level: ServerLevel, pos: Vec3): Vec3? {
        val fieldMap = fields[SlideSpace.Main.cacheKey(level)] ?: return null
        if (fieldMap.isEmpty()) return null
        val segs = allSegments(MainSlideSpaceAccess(level), structureSignature(MainSlideSpaceAccess(level)))
        var best: TubeSeg? = null
        var bestD = Double.MAX_VALUE
        var bestT = 0.0
        for (s in segs) {
            val ab = s.b.subtract(s.a)
            val lenSq = ab.lengthSqr()
            if (lenSq < 1.0E-12) continue
            val t = ((pos.subtract(s.a)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
            val closest = s.a.add(ab.scale(t))
            val d = pos.distanceToSqr(closest)
            if (d < bestD) { bestD = d; best = s; bestT = t }
        }
        val seg = best ?: return null
        if (bestD > (seg.rIn * seg.rIn).toDouble()) return null
        val arc = seg.arcStart + (bestT * seg.len).toFloat()
        val field = fieldMap[seg.curveKey] ?: return null
        if (field.segments.isEmpty()) return null
        val segLen = ModConfig.waterSegmentLength().toFloat()
        val idx = (arc / segLen).toInt().coerceIn(0, field.segments.size - 1)
        val speed = field.segments[idx].speed
        if (kotlin.math.abs(speed) < 0.01f) return Vec3.ZERO
        return seg.b.subtract(seg.a).normalize().scale(speed.toDouble())
    }

    // called every server tick; recalculates on demand with a cooldown guard
    fun tickServer(level: ServerLevel) {
        tickServer(MainSlideSpaceAccess(level))
    }

    fun tickServer(access: SlideSpaceAccess) {
        val level = access.level
        val key = spaceKey(access)
        // periodic structural/water check
        if (level.gameTime % 100 == 0L && !dirty.contains(key)) {
            dirty += key
        }
        if (level.gameTime % 100 == 0L) {
            CreateWaterparked.LOGGER.info(
                "Water tick space={} dirty={} fieldsNull={} last={} time={}",
                key, dirty.contains(key), fields[key] == null,
                lastCalc[key] ?: "none", level.gameTime
            )
        }
        if (!dirty.contains(key) && fields[key] != null) return
        val last = lastCalc[key]
        if (last != null && level.gameTime - last < ModConfig.waterSimCooldownTicks()) return
        dirty.remove(key)
        lastCalc[key] = level.gameTime
        val sig = try {
            structureSignature(access)
        } catch (e: Exception) {
            CreateWaterparked.LOGGER.error("Water sig failed", e)
            return
        }
        if (sig == lastSig[key] && fields[key] != null) return
        val sigChanged = sig != lastSig[key]
        lastSig[key] = sig
        // diagnostic: anchor/water counts on every calc attempt
        var anchors = 0
        var wet = 0
        for (pos in SlideAnchorIndex.all(level, access.space)) {
            val be = access.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            anchors++
            if (be.hasWater()) wet++
        }
        CreateWaterparked.LOGGER.info(
            "Water sim attempt space={} sigChanged={} anchors={} wet={} time={}",
            key, sigChanged, anchors, wet, level.gameTime
        )
        calculate(access, sig)
    }

    private fun calculate(access: SlideSpaceAccess, sig: String) {
        val level = access.level
        val key = spaceKey(access)
        val sources = collectSources(access)
        if (sources.isEmpty()) {
            fields[key] = emptyMap()
            CreateWaterparked.LOGGER.info("Water sim: no water sources")
            PacketDistributor.sendToPlayersInDimension(level, WaterslideWaterSyncPayload(emptyList()))
            return
        }
        val segments = allSegments(access, sig)
        val grid = SegGrid()
        for (s in segments) grid.add(s)
        val acc = HashMap<Pair<Long, Long>, HashMap<Int, SegmentAcc>>()
        val exits = HashMap<Pair<Long, Long>, ExitInfo>()
        val collectDebug = debugPlayers.any { uuid ->
            level.server.playerList.getPlayer(uuid)?.level() == level
        }
        val debugOut = if (collectDebug) ArrayList<MutableList<Vec3>>() else null
        val count = ModConfig.waterSimParticleCount()
        val perLeg = max(1, count / max(1, sources.size))
        // Deterministic sampling: the same structure signature must always produce
        // the same particle rays. Random world RNG made every recalculation (login
        // resync, periodic recalc, edits) change ExitInfo.vel, so the client's
        // thrown water visibly drifted. Sort sources and seed per particle instead.
        val sortedSources = sources.sortedWith(
            compareBy<LegStart>(
                { it.center.x }, { it.center.y }, { it.center.z },
                { it.tangent.x }, { it.tangent.y }, { it.tangent.z },
                { it.lateral.x }, { it.lateral.y }, { it.lateral.z },
                { it.rIn }
            )
        )
        val sigSeed = sig.hashCode().toLong()
        var idx = 0
        for ((sourceIndex, src) in sortedSources.withIndex()) {
            for (i in 0 until perLeg) {
                val seed = sigSeed + sourceIndex * -7046029254386353131L + i * -2960836687051489901L
                val rng = java.util.Random(seed)
                integrate(access, src, grid, segments, acc, exits, debugOut,
                    rng.nextDouble(), rng.nextDouble())
                if (++idx >= count * 2) break
            }
        }
        val out = HashMap<Pair<Long, Long>, CurveField>()
        val segLen = ModConfig.waterSegmentLength().toFloat()
        for ((key, segMap) in acc) {
            val list = segMap.entries.sortedBy { it.key }.map { (segIdx, a) ->
                WaterSegment(
                    (segIdx * segLen + segLen / 2f),
                    a.signedSpeed()
                )
            }
            out[key] = CurveField(list, exits[key])
        }
        // TEMP DIAGNOSTIC: per-curve segment coverage + flow direction sign
        for ((key, field) in out) {
            val segs = field.segments
            if (segs.isEmpty()) continue
            val arcs = segs.map { it.arc }.sorted()
            var gaps = 0
            for (i in 1 until arcs.size) {
                if (arcs[i] - arcs[i - 1] > segLen * 1.5f) gaps++
            }
            val pos = segs.count { it.speed >= 0f }
            val spdMin = segs.minOf { it.speed }
            val spdMax = segs.maxOf { it.speed }
            CreateWaterparked.LOGGER.info(
                "[WaterSeg] key={},{} n={} arcMin={} arcMax={} gaps={} pos={} neg={} spdMin={} spdMax={}",
                key.first, key.second, segs.size, arcs.first(), arcs.last(), gaps, pos, segs.size - pos, spdMin, spdMax
            )
        }
        fields[key] = out
        val entries = out.map { (key, field) ->
            WaterslideWaterSyncPayload.Entry(
                key.first, key.second, field.segments.map {
                    WaterslideWaterSyncPayload.Segment(it.arc, it.speed)
                }, field.exit?.pos, field.exit?.vel
            )
        }
        CreateWaterparked.LOGGER.info(
            "Water sim done sources={} curves={} segments={}", sources.size, entries.size,
            entries.sumOf { it.segments.size }
        )
        try {
            val payload = WaterslideWaterSyncPayload(entries)
            CreateWaterparked.LOGGER.info("Water payload sending entries={}", payload.entries.size)
            PacketDistributor.sendToPlayersInDimension(level, payload)
        } catch (e: Exception) {
            CreateWaterparked.LOGGER.error("Water payload send failed", e)
        }
        if (debugOut != null) {
            val debugPayload = WaterslideDebugTrajectoryPayload(debugOut)
            for (uuid in debugPlayers.toList()) {
                val p = level.server.playerList.getPlayer(uuid) ?: continue
                if (p.level() == level) {
                    PacketDistributor.sendToPlayer(p, debugPayload)
                }
            }
        }
    }

    private fun collectSources(access: SlideSpaceAccess): List<LegStart> {
        val level = access.level
        val out = ArrayList<LegStart>()
        for (pos in SlideAnchorIndex.all(level, access.space)) {
            val be = access.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            if (!be.hasWater()) continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(access, a)
                val r1 = SlideCurveGeometry.radiusAt(access, b)
                val frames = SlideCurveGeometry.sampleFrames(access, bc, r0, r1, 0.5, includeExtensions = false)
                if (frames.size < 2) continue
                val atFirst = a == pos
                val f = if (atFirst) frames.first() else frames.last()
                val tangent = if (atFirst) f.tangent else f.tangent.scale(-1.0)
                val lateral = if (atFirst) f.lateral else f.lateral.scale(-1.0)
                out += LegStart(
                    f.center, tangent.normalize(), lateral.normalize(), f.up,
                    (f.radius - 0.15f).coerceAtLeast(0.2f)
                )
            }
        }
        return out
    }

    private fun integrate(
        access: SlideSpaceAccess,
        src: LegStart,
        grid: SegGrid,
        segments: List<TubeSeg>,
        acc: HashMap<Pair<Long, Long>, HashMap<Int, SegmentAcc>>,
        exits: HashMap<Pair<Long, Long>, ExitInfo>,
        debugOut: MutableList<MutableList<Vec3>>?,
        u: Double,
        ang: Double
    ) {
        val dt = 0.03
        val gravity = 32.0 * dt
        val maxSteps = (ModConfig.waterSimMaxBlocks() / 0.2).toInt()
        val segLen = ModConfig.waterSegmentLength().toFloat()
        // uniform disc sample on the anchor opening (deterministic per particle)
        val rr = kotlin.math.sqrt(u) * src.rIn
        var pos = src.center
            .add(src.lateral.scale(cos(ang) * rr))
            .add(src.up.scale(sin(ang) * rr))
        var vel = src.tangent.scale(0.5)
        var lastKey: Pair<Long, Long>? = null
        var lastInside: Vec3? = null
        val poly = debugOut?.let { ArrayList<Vec3>().also { l -> debugOut.add(l) } }
        poly?.add(pos)
        for (step in 0 until maxSteps) {
            vel = vel.add(0.0, -gravity, 0.0)
            val newPos = pos.add(vel.scale(dt))
            val seg = grid.nearest(newPos)
            if (seg != null) {
                val ab = seg.b.subtract(seg.a)
                val lenSq = ab.lengthSqr()
                if (lenSq < 1.0E-12) {
                    pos = newPos
                } else {
                    val tRaw = ((newPos.subtract(seg.a)).dot(ab) / lenSq)
                    val axis = seg.a.add(ab.scale(tRaw.coerceIn(0.0, 1.0)))
                    val radial = newPos.subtract(axis)
                    val dist = radial.length()
                    if (tRaw < 0.0 || tRaw > 1.0) {
                        // beyond the segment end: free flight (outlet throw)
                        pos = newPos
                    } else if (dist <= seg.rOut + 1.0) {
                        if (dist > seg.rIn) {
                            // wall contact: drop the outward component, slide along the wall
                            val n = radial.scale(1.0 / dist)
                            val vn = vel.dot(n)
                            if (vn > 0.0) {
                                vel = vel.subtract(n.scale(vn))
                                if (vel.lengthSqr() < 1.0E-9) {
                                    // fully radial hit: slide along the segment axis
                                    // instead of zeroing (keeps the flow horizontal)
                                    vel = ab.normalize().scale(vn)
                                }
                            }
                            pos = axis.add(n.scale(seg.rIn.toDouble()))
                            vel = vel.scale((1.0 - ModConfig.slideWaterFriction()).coerceAtLeast(0.0))
                        } else {
                            pos = newPos
                        }
                        lastKey = seg.curveKey
                        lastInside = pos
                    } else {
                        pos = newPos
                    }
                }
            } else {
                pos = newPos
            }
            if (step % 2 == 0) {
                poly?.add(pos)
                val seg2 = grid.nearest(pos)
                var inTube = false
                if (seg2 != null) {
                    val ab2 = seg2.b.subtract(seg2.a)
                    val lenSq2 = ab2.lengthSqr()
                    val t2 = if (lenSq2 < 1.0E-12) 0.0
                    else ((pos.subtract(seg2.a)).dot(ab2) / lenSq2).coerceIn(0.0, 1.0)
                    val axis2 = seg2.a.add(ab2.scale(t2))
                    val radial2 = pos.subtract(axis2)
                    val dist2 = radial2.length()
                    if (dist2 <= seg2.rOut) {
                        inTube = true
                        val arc = seg2.arcStart + (t2 * seg2.len).toFloat()
                        val segIdx = (arc / segLen).toInt()
                        val segMap = acc.getOrPut(seg2.curveKey) { HashMap() }
                        val segAcc = segMap.getOrPut(segIdx) { SegmentAcc() }
                        segAcc.add(vel, seg2.b.subtract(seg2.a).normalize())
                    }
                }
                if (!inTube) {
                    val bp = BlockPos.containing(pos)
                    if (access.getBlockState(bp).isSolid || pos.y < access.level.minBuildHeight) break
                    if (lastKey != null) {
                        exits.putIfAbsent(lastKey!!, ExitInfo(lastInside ?: pos, vel))
                    }
                }
            }
        }
    }

    private fun allSegments(access: SlideSpaceAccess, sig: String): List<TubeSeg> {
        val key = spaceKey(access)
        segCache[key]?.let { if (it.first == sig) return it.second }
        val out = ArrayList<TubeSeg>()
        for (pos in SlideAnchorIndex.all(access.level, access.space)) {
            val be = access.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(access, a)
                val r1 = SlideCurveGeometry.radiusAt(access, b)
                val frames = SlideCurveGeometry.sampleFrames(access, bc, r0, r1, 0.5)
                var arc = 0.0
                for (i in 0 until frames.size - 1) {
                    val fa = frames[i]
                    val fb = frames[i + 1]
                    val len = fa.center.distanceTo(fb.center)
                    if (len < 1.0E-6) continue
                    val avgR = (fa.radius + fb.radius) / 2f
                    val lat = fa.lateral.lerp(fb.lateral, 0.5).normalize()
                    val up = fa.up.lerp(fb.up, 0.5).normalize()
                    out += TubeSeg(
                        fa.center, fb.center,
                        avgR + 0.2f,
                        (avgR - 0.15f).coerceAtLeast(0.1f),
                        edgeKey(a, b),
                        arc.toFloat(),
                        len.toFloat(),
                        lat, up
                    )
                    arc += len
                }
            }
        }
        if (segCache.size > 8) segCache.clear()
        segCache[key] = sig to out
        return out
    }

    private fun structureSignature(access: SlideSpaceAccess): String {
        val sb = StringBuilder()
        for (pos in SlideAnchorIndex.all(access.level, access.space).sortedBy { it.asLong() }) {
            val be = access.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            sb.append(pos.asLong()).append(if (be.hasWater()) 'w' else '.').append('|')
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
                    .append(SlideCurveGeometry.radiusAt(access, a)).append(',')
                    .append(SlideCurveGeometry.radiusAt(access, b)).append(';')
            }
        }
        return sb.toString()
    }

    private fun edgeKey(a: BlockPos, b: BlockPos): Pair<Long, Long> =
        if (a.asLong() <= b.asLong()) a.asLong() to b.asLong() else b.asLong() to a.asLong()
}
