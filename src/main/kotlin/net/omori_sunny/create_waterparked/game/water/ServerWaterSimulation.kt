package net.omori_sunny.create_waterparked.game.water

import com.simibubi.create.content.trains.track.BezierConnection
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideAnchorIndex
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
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
        var speedSum = 0.0
        var count = 0

        fun add(speed: Double) {
            speedSum += speed
            count++
        }

        fun averageSpeed(): Float = if (count == 0) 0f else (speedSum / count).toFloat()
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

    private val fields = HashMap<ResourceKey<Level>, Map<Pair<Long, Long>, CurveField>>()
    private val dirty = mutableSetOf<ResourceKey<Level>>()
    private val lastCalc = mutableMapOf<ResourceKey<Level>, Long>()
    private val segCache = HashMap<ResourceKey<Level>, Pair<String, List<TubeSeg>>>()
    private val lastSig = HashMap<ResourceKey<Level>, String>()
    private val debugPlayers = mutableSetOf<UUID>()

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
        if (!level.isClientSide) dirty += level.dimension()
    }

    // force a recalculation + resend for a joined player
    fun resync(level: Level) {
        if (level.isClientSide) return
        dirty += level.dimension()
        lastSig.remove(level.dimension())
    }

    // resend the current field to players who just joined
    fun resendTo(level: ServerLevel) {
        val f = fields[level.dimension()] ?: return
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
        fields[level.dimension()]?.get(edgeKey(a, b))

    // called every server tick; recalculates on demand with a cooldown guard
    fun tickServer(level: ServerLevel) {
        val dim = level.dimension()
        // periodic structural/water check
        if (level.gameTime % 100 == 0L && !dirty.contains(dim)) {
            dirty += dim
        }
        if (level.gameTime % 100 == 0L) {
            CreateWaterparked.LOGGER.info(
                "Water tick dim={} dirty={} fieldsNull={} last={} time={}",
                dim.location(), dirty.contains(dim), fields[dim] == null,
                lastCalc[dim] ?: "none", level.gameTime
            )
        }
        if (!dirty.contains(dim) && fields[dim] != null) return
        val last = lastCalc[dim]
        if (last != null && level.gameTime - last < ModConfig.waterSimCooldownTicks()) return
        dirty.remove(dim)
        lastCalc[dim] = level.gameTime
        val sig = try {
            structureSignature(level)
        } catch (e: Exception) {
            CreateWaterparked.LOGGER.error("Water sig failed", e)
            return
        }
        if (sig == lastSig[dim] && fields[dim] != null) return
        val sigChanged = sig != lastSig[dim]
        lastSig[dim] = sig
        // diagnostic: anchor/water counts on every calc attempt
        var anchors = 0
        var wet = 0
        for (pos in SlideAnchorIndex.all(level)) {
            val be = level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            anchors++
            if (be.hasWater()) wet++
        }
        CreateWaterparked.LOGGER.info(
            "Water sim attempt sigChanged={} anchors={} wet={} time={}",
            sigChanged, anchors, wet, level.gameTime
        )
        calculate(level, sig)
    }

    private fun calculate(level: ServerLevel, sig: String) {
        val dim = level.dimension()
        val sources = collectSources(level)
        if (sources.isEmpty()) {
            fields[dim] = emptyMap()
            CreateWaterparked.LOGGER.info("Water sim: no water sources")
            PacketDistributor.sendToPlayersInDimension(level, WaterslideWaterSyncPayload(emptyList()))
            return
        }
        val segments = allSegments(level, sig)
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
        var idx = 0
        for (src in sources) {
            for (i in 0 until perLeg) {
                integrate(level, src, grid, segments, acc, exits, debugOut)
                if (++idx >= count * 2) break
            }
        }
        val out = HashMap<Pair<Long, Long>, CurveField>()
        val segLen = ModConfig.waterSegmentLength().toFloat()
        for ((key, segMap) in acc) {
            val list = segMap.entries.sortedBy { it.key }.map { (segIdx, a) ->
                WaterSegment(
                    (segIdx * segLen + segLen / 2f),
                    a.averageSpeed()
                )
            }
            out[key] = CurveField(list, exits[key])
        }
        fields[dim] = out
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

    private fun collectSources(level: ServerLevel): List<LegStart> {
        val out = ArrayList<LegStart>()
        for (pos in SlideAnchorIndex.all(level)) {
            val be = level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            if (!be.hasWater()) continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(level, a)
                val r1 = SlideCurveGeometry.radiusAt(level, b)
                val frames = SlideCurveGeometry.sampleFrames(level, bc, r0, r1, 0.5, includeExtensions = false)
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
        level: ServerLevel,
        src: LegStart,
        grid: SegGrid,
        segments: List<TubeSeg>,
        acc: HashMap<Pair<Long, Long>, HashMap<Int, SegmentAcc>>,
        exits: HashMap<Pair<Long, Long>, ExitInfo>,
        debugOut: MutableList<MutableList<Vec3>>?
    ) {
        val dt = 0.03
        val gravity = 32.0 * dt
        val maxSteps = (ModConfig.waterSimMaxBlocks() / 0.2).toInt()
        val segLen = ModConfig.waterSegmentLength().toFloat()
        // uniform disc sample on the anchor opening
        val u = level.random.nextDouble()
        val ang = level.random.nextDouble() * Math.PI * 2.0
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
                            if (vn > 0.0) vel = vel.subtract(n.scale(vn))
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
                        val spd = vel.dot(seg2.b.subtract(seg2.a).normalize())
                        segAcc.add(spd)
                    }
                }
                if (!inTube) {
                    val bp = BlockPos.containing(pos)
                    if (level.getBlockState(bp).isSolid || pos.y < level.minBuildHeight) break
                    if (lastKey != null) {
                        exits.putIfAbsent(lastKey!!, ExitInfo(lastInside ?: pos, vel))
                    }
                }
            }
        }
    }

    private fun allSegments(level: ServerLevel, sig: String): List<TubeSeg> {
        val dim = level.dimension()
        segCache[dim]?.let { if (it.first == sig) return it.second }
        val out = ArrayList<TubeSeg>()
        for (pos in SlideAnchorIndex.all(level)) {
            val be = level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(level, a)
                val r1 = SlideCurveGeometry.radiusAt(level, b)
                val frames = SlideCurveGeometry.sampleFrames(level, bc, r0, r1, 0.5)
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
        segCache[dim] = sig to out
        return out
    }

    private fun structureSignature(level: ServerLevel): String {
        val sb = StringBuilder()
        for (pos in SlideAnchorIndex.all(level).sortedBy { it.asLong() }) {
            val be = level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
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
                    .append(SlideCurveGeometry.radiusAt(level, a)).append(',')
                    .append(SlideCurveGeometry.radiusAt(level, b)).append(';')
            }
        }
        return sb.toString()
    }

    private fun edgeKey(a: BlockPos, b: BlockPos): Pair<Long, Long> =
        if (a.asLong() <= b.asLong()) a.asLong() to b.asLong() else b.asLong() to a.asLong()
}
